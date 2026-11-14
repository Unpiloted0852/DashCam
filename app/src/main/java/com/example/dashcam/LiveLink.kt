package com.example.dashcam

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.MediaCodec
import android.media.MediaFormat
import android.net.wifi.WifiManager
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Surface
import java.io.BufferedInputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.SecureRandom
import java.util.concurrent.LinkedBlockingDeque
import java.util.concurrent.TimeUnit

/**
 * Live view from the remote camera to the main phone over Wi‑Fi Direct.
 *
 * The Bluetooth link negotiates it: the main phone asks (`live` {on}), the remote creates a Wi‑Fi
 * Direct group with a random name/passphrase and answers with them; the main phone joins that group
 * without any prompt and opens a TCP connection to the remote, which streams a small H.264 feed.
 * Neither phone needs to be on a Wi‑Fi network, but Wi‑Fi must be switched on.
 */
object LiveLink {
    const val PORT = 8765

    fun wifiPermission(): String =
        if (Build.VERSION.SDK_INT >= 33) Manifest.permission.NEARBY_WIFI_DEVICES else Manifest.permission.ACCESS_FINE_LOCATION

    /** Null if Wi‑Fi Direct can be used now, else what the user must fix. */
    fun problem(ctx: Context): String? = when {
        ctx.checkSelfPermission(wifiPermission()) != PackageManager.PERMISSION_GRANTED ->
            "Nearby-devices permission needed — open DashCam on this phone"
        ctx.getSystemService(WifiManager::class.java)?.isWifiEnabled != true -> "Turn Wi‑Fi on (no network needed)"
        ctx.getSystemService(WifiP2pManager::class.java) == null -> "This phone has no Wi‑Fi Direct"
        else -> null
    }

    fun randomString(n: Int): String {
        val abc = "abcdefghijkmnopqrstuvwxyzABCDEFGHJKLMNPQRSTUVWXYZ23456789"
        val r = SecureRandom()
        return (1..n).map { abc[r.nextInt(abc.length)] }.joinToString("")
    }

    // Stream framing: int32 payload length, byte kind, byte key, int64 pts (µs), payload.
    const val KIND_FORMAT = 0   // payload: int32 width, int32 height
    const val KIND_CONFIG = 1   // SPS/PPS
    const val KIND_FRAME = 2
    /** Frames waiting longer than this on the sending side are dropped (after lowering quality didn't help). */
    const val MAX_QUEUE_LAG_MS = 350L
    /** Live video bit rate: where it starts, and the range it adapts within. */
    const val BITRATE_START = 4_000_000
    const val BITRATE_MIN = 500_000
    const val BITRATE_MAX = 8_000_000
}

/** Channel (MHz) of the Wi‑Fi network this phone is connected to, or 0. */
@Suppress("DEPRECATION")
fun wifiStationFreq(ctx: Context): Int = try {
    val info = ctx.applicationContext.getSystemService(android.net.wifi.WifiManager::class.java)?.connectionInfo
    if (info != null && info.networkId != -1 && info.frequency > 0) info.frequency else 0
} catch (_: Exception) { 0 }

/** Thin wrapper over WifiP2pManager callbacks. All callbacks arrive on the main thread. */
@SuppressLint("MissingPermission")
class WifiDirect(ctx: Context) {
    private val app = ctx.applicationContext
    private val mgr = app.getSystemService(WifiP2pManager::class.java)
    private val ui = Handler(Looper.getMainLooper())
    private var channel: WifiP2pManager.Channel? = null
    @Volatile private var closed = false

    private fun ch(): WifiP2pManager.Channel? {
        if (channel == null) channel = mgr?.initialize(app, Looper.getMainLooper()) { channel = null }
        return channel
    }

    private fun action(name: String, ok: () -> Unit, fail: (String) -> Unit) = object : WifiP2pManager.ActionListener {
        override fun onSuccess() = ok()
        override fun onFailure(reason: Int) = fail("$name failed (${when (reason) {
            WifiP2pManager.BUSY -> "busy"; WifiP2pManager.P2P_UNSUPPORTED -> "unsupported"
            WifiP2pManager.ERROR -> "error"; else -> "code $reason" }})")
    }

    /** Remote camera: become a group owner with this name and passphrase. */
    /** [onOk] gets the group's channel frequency (MHz, 0 if unknown) so joiners can skip scanning. */
    /**
     * [preferFreq]: put the group on this channel (MHz), e.g. the home Wi‑Fi's, so a phone that is
     * also on that Wi‑Fi doesn't have to keep hopping between two channels (which halves the speed
     * or worse). 0 = any 5 GHz channel.
     */
    fun createGroup(ssid: String, pass: String, preferFreq: Int, onOk: (Int) -> Unit, onFail: (String) -> Unit) {
        this.preferFreq = preferFreq
        tryCreate(ssid, pass, { reportFreq(ssid, onOk, 0) }, onFail, 0)
    }
    private var preferFreq = 0

    private fun reportFreq(ssid: String, onOk: (Int) -> Unit, tries: Int) {
        val c = ch() ?: return onOk(0)
        mgr!!.requestGroupInfo(c) { g ->
            val f = if (g != null && g.networkName == ssid) g.frequency else 0
            if (f > 0 || tries >= 6) onOk(f) else ui.postDelayed({ reportFreq(ssid, onOk, tries + 1) }, 300)
        }
    }

    /** Is this phone currently the owner of group [ssid]? */
    fun ownsGroup(ssid: String, cb: (Boolean) -> Unit) {
        val c = ch() ?: return cb(false)
        mgr!!.requestGroupInfo(c) { g -> cb(g != null && g.isGroupOwner && g.networkName == ssid) }
    }

    private fun tryCreate(ssid: String, pass: String, onOk: () -> Unit, onFail: (String) -> Unit, attempt: Int) {
        val c = ch() ?: return onFail("Wi‑Fi Direct unavailable")
        // 5 GHz first (several times faster for clip downloads); any band if the phone can't.
        // Attempts: the preferred (Wi‑Fi) channel, then any 5 GHz channel, then any band.
        val tryFreq = preferFreq > 0 && attempt == 0
        val try5 = if (preferFreq > 0) attempt == 1 else attempt == 0
        val cfg = WifiP2pConfig.Builder().setNetworkName(ssid).setPassphrase(pass)
            .apply {
                if (tryFreq) setGroupOperatingFrequency(preferFreq)
                else if (try5) setGroupOperatingBand(WifiP2pConfig.GROUP_OWNER_BAND_5GHZ)
            }.build()
        mgr!!.removeGroup(c, action("remove", { go(c, cfg, ssid, pass, onOk, onFail, attempt) },
            { go(c, cfg, ssid, pass, onOk, onFail, attempt) }))
    }

    private fun go(c: WifiP2pManager.Channel, cfg: WifiP2pConfig, ssid: String, pass: String,
                   onOk: () -> Unit, onFail: (String) -> Unit, attempt: Int) {
        if (closed) return
        mgr!!.createGroup(c, cfg, action("create group", onOk) { err ->
            val maxAttempt = if (preferFreq > 0) 4 else 3
            if (attempt < maxAttempt && !closed) ui.postDelayed({ tryCreate(ssid, pass, onOk, onFail, attempt + 1) }, 1500)
            else onFail(err)
        })
    }

    /** Main phone: join the remote's group and report the group owner's address. */
    fun join(ssid: String, pass: String, freq: Int, onJoined: (InetAddress) -> Unit, onFail: (String) -> Unit) {
        val c = ch() ?: return onFail("Wi‑Fi Direct unavailable")
        val cfg = WifiP2pConfig.Builder().setNetworkName(ssid).setPassphrase(pass)
            .apply { if (freq > 0) setGroupOperatingFrequency(freq) }.build()
        val deadline = SystemClock.elapsedRealtime() + 30_000
        fun poll() {
            if (closed) return
            mgr!!.requestGroupInfo(c) { g ->
                if (closed) return@requestGroupInfo
                if (g != null && g.networkName == ssid && !g.isGroupOwner) {
                    mgr.requestConnectionInfo(c) { info ->
                        val a = info?.groupOwnerAddress
                        if (info?.groupFormed == true && a != null) onJoined(a)
                        else if (SystemClock.elapsedRealtime() < deadline) ui.postDelayed({ poll() }, 500)
                        else onFail("joined but no address")
                    }
                } else if (SystemClock.elapsedRealtime() < deadline) ui.postDelayed({ poll() }, 500)
                else onFail("couldn't join the remote's Wi‑Fi Direct group")
            }
        }
        mgr!!.requestGroupInfo(c) { g ->
            if (g != null && g.networkName == ssid) { poll(); return@requestGroupInfo }
            // In some other group (e.g. an old one): leave it first.
            val connect = { mgr.connect(c, cfg, action("connect", { poll() }, onFail)) }
            if (g != null) mgr.removeGroup(c, action("remove", { connect() }, { connect() })) else connect()
        }
    }

    /** [leave] = also end the group membership (otherwise the phone stays joined for next time). */
    fun close(leave: Boolean = true) {
        closed = true
        ui.removeCallbacksAndMessages(null)
        val c = channel ?: return
        if (leave) try { mgr?.removeGroup(c, null) } catch (_: Exception) {}
        if (Build.VERSION.SDK_INT >= 27) try { c.close() } catch (_: Exception) {}
        channel = null
    }
}

/** Remote camera: serves the live H.264 feed to the main phone (one viewer at a time). */
class LiveServer(
    val token: String,
    private val onKeyFrameWanted: () -> Unit,
    /** Asks the encoder for a new bit rate (bits per second). */
    private val onBitrate: (Int) -> Unit = {},
) : CameraPipeline.LiveSink {
    private class Pkt(val kind: Int, val key: Boolean, val pts: Long, val data: ByteArray) {
        val at = SystemClock.elapsedRealtime()
    }
    private var lastDropAt = 0L
    private val rate = LiveRate()

    @Volatile private var running = true
    @Volatile private var client: Socket? = null
    private val queue = LinkedBlockingDeque<Pkt>()
    @Volatile private var format: Pkt? = null
    @Volatile private var config: Pkt? = null
    @Volatile private var dropping = true   // until a key frame starts the stream for the current viewer
    @Volatile var lastClientAt = SystemClock.elapsedRealtime(); private set
    val hasClient: Boolean get() = client != null
    private val server = ServerSocket().apply { reuseAddress = true; bind(InetSocketAddress(LiveLink.PORT)) }

    init { Thread({ acceptLoop() }, "live-accept").apply { isDaemon = true; start() } }

    private fun acceptLoop() {
        while (running) {
            val s = try { server.accept() } catch (_: IOException) { break }
            // Only the Wi‑Fi Direct group's own subnet, and only with the token sent over Bluetooth.
            val ok = try {
                s.soTimeout = 4000
                val addr = s.inetAddress.hostAddress ?: ""
                val line = DataInputStream(s.getInputStream()).let { inp ->
                    val b = StringBuilder()
                    while (b.length < 64) { val c = inp.read(); if (c < 0 || c == '\n'.code) break; b.append(c.toChar()) }
                    b.toString()
                }
                addr.startsWith("192.168.49.") && line == token
            } catch (_: Exception) { false }
            if (!ok) { EventLog.log("Live", "refused viewer ${s.inetAddress?.hostAddress}"); try { s.close() } catch (_: Exception) {}; continue }
            client?.let { try { it.close() } catch (_: Exception) {} }
            // A small send buffer: when the link can't keep up, frames must pile up in our queue
            // (where stale ones are thrown away), not out of sight inside the socket.
            s.tcpNoDelay = true; s.soTimeout = 0; s.sendBufferSize = 48 * 1024
            client = s
            lastClientAt = SystemClock.elapsedRealtime()
            EventLog.log("Live", "viewer connected")
            Thread({ sendLoop(s) }, "live-send").apply { isDaemon = true; start() }
        }
    }

    private fun sendLoop(s: Socket) {
        queue.clear(); dropping = true
        onBitrate(rate.reset())
        try {
            val out = DataOutputStream(s.getOutputStream().buffered(32 * 1024))
            format?.let { write(out, it) }
            config?.let { write(out, it) }
            out.flush()
            onKeyFrameWanted()
            while (running && client === s) {
                val p = queue.poll(1, TimeUnit.SECONDS) ?: continue
                write(out, p)
                if (queue.isEmpty()) out.flush()
                lastClientAt = SystemClock.elapsedRealtime()
            }
        } catch (e: Exception) {
            if (running) EventLog.log("Live", "viewer gone: ${e.message}")
        }
        if (client === s) client = null
        try { s.close() } catch (_: Exception) {}
    }

    private fun write(out: DataOutputStream, p: Pkt) {
        out.writeInt(p.data.size); out.writeByte(p.kind); out.writeByte(if (p.key) 1 else 0)
        out.writeLong(p.pts); out.write(p.data)
    }

    override fun onLiveFormat(w: Int, h: Int) {
        val d = java.nio.ByteBuffer.allocate(8).putInt(w).putInt(h).array()
        val p = Pkt(LiveLink.KIND_FORMAT, false, 0, d)
        format = p; config = null
        if (client != null) { queue.clear(); queue.add(p); dropping = true }
    }

    override fun onLiveData(data: ByteArray, ptsUs: Long, key: Boolean, config: Boolean) {
        if (config) {
            val p = Pkt(LiveLink.KIND_CONFIG, false, ptsUs, data)
            this.config = p
            if (client != null) queue.add(p)
            return
        }
        if (client == null) return
        // Viewer falling behind (weak link): a late picture is worse than a missing one, so throw
        // away what's waiting and restart from a fresh key frame. At most once a second, since key
        // frames are large and would only make a struggling link worse.
        val now = SystemClock.elapsedRealtime()
        val oldest = queue.peekFirst()
        val lag = if (oldest != null && oldest.kind == LiveLink.KIND_FRAME) now - oldest.at else 0L
        // First line of defence: match the picture quality to what the link carries right now.
        // A slightly softer picture that keeps moving beats a sharp one that freezes.
        rate.step(lag, now)?.let { bps ->
            onBitrate(bps)
            EventLog.log("Live", "link ${if (bps < rate.previous) "struggling" else "clear"}: video now ${bps / 1000} kbit/s")
        }
        // Last resort, when the link has all but stopped: skip ahead to a fresh key frame.
        if (lag > LiveLink.MAX_QUEUE_LAG_MS && now - lastDropAt > 1500) {
            lastDropAt = now
            queue.removeIf { it.kind == LiveLink.KIND_FRAME }; dropping = true; onKeyFrameWanted()
        }
        if (dropping && !key) return
        dropping = false
        // Stamp each frame with the accurate wall-clock time it was captured (both phones share
        // that clock), so the viewer can show how old the picture is.
        val captureMs = TrueTime.atElapsedNs(ptsUs * 1000 + (SystemClock.elapsedRealtimeNanos() - System.nanoTime()))
        queue.add(Pkt(LiveLink.KIND_FRAME, key, captureMs * 1000, data))
    }

    fun stop() {
        running = false
        try { server.close() } catch (_: Exception) {}
        client?.let { try { it.close() } catch (_: Exception) {} }
        client = null
    }
}

/**
 * Main phone: receives the feed from the remote (already joined to its Wi‑Fi Direct group) and
 * decodes it onto a Surface. [onStatus] gets short states on the main thread ("" once video shows).
 */
class LiveViewer(
    ctx: Context,
    private val surface: Surface,
    private val onStatus: (String) -> Unit,
    private val onSize: (Int, Int) -> Unit,
    /** How old the picture is when it leaves the decoder, in ms (averaged, about twice a second). */
    private val onDelay: (Int) -> Unit = {},
) {
    private val wifi = WifiFast(ctx, "DashCam:live-view").also { it.acquire() }
    private val ui = Handler(Looper.getMainLooper())
    @Volatile private var running = true
    @Volatile private var socket: Socket? = null
    @Volatile private var target: Pair<InetAddress, String>? = null
    private var thread: Thread? = null

    private fun status(s: String) = ui.post { if (running) onStatus(s) }

    /** Stream from [addr]; a no-op if already doing so. */
    fun connect(addr: InetAddress, token: String) {
        val t = addr to token
        if (t == target && thread?.isAlive == true) return
        target = t
        socket?.let { try { it.close() } catch (_: Exception) {} }
        thread?.interrupt()
        thread = Thread({
            while (running && target == t) {
                status("Connecting to the remote camera…")
                try {
                    val s = Socket()
                    socket = s
                    s.tcpNoDelay = true
                    s.receiveBufferSize = 512 * 1024
                    s.connect(InetSocketAddress(addr, LiveLink.PORT), 3000)
                    s.getOutputStream().apply { write("$token\n".toByteArray()); flush() }
                    status("Starting the remote camera…")
                    decode(DataInputStream(BufferedInputStream(s.getInputStream(), 256 * 1024)))
                } catch (e: Exception) {
                    if (running) EventLog.log("Live", "stream: ${e.message}")
                }
                socket?.let { try { it.close() } catch (_: Exception) {} }
                if (!running || target != t) break
                try { Thread.sleep(700) } catch (_: InterruptedException) { break }
            }
        }, "live-view").apply { isDaemon = true; start() }
    }

    /**
     * A decoder plus the thread that puts its pictures on screen. Output has its own thread so a
     * frame is shown the moment it is decoded, instead of waiting for the next one to arrive.
     */
    private inner class Decoder(w: Int, h: Int) {
        val codec: MediaCodec = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        @Volatile private var alive = true
        @Volatile var shown = false
        private val out: Thread

        init {
            val f = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, w, h)
            if (Build.VERSION.SDK_INT >= 30) f.setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
            f.setInteger(MediaFormat.KEY_PRIORITY, 0)
            f.setInteger(MediaFormat.KEY_OPERATING_RATE, Short.MAX_VALUE.toInt())   // run flat out, don't pace
            // Chip makers' own low-latency switches (ignored where unknown): no reordering queue.
            f.setInteger("vendor.qti-ext-dec-low-latency.enable", 1)
            f.setInteger("vendor.qti-ext-dec-picture-order.enable", 1)
            f.setInteger("vendor.rtc-ext-dec-low-latency.enable", 1)
            f.setInteger("vendor.low-latency.enable", 1)
            codec.configure(f, surface, null, 0); codec.start()
            out = Thread({
                val info = MediaCodec.BufferInfo()
                var sum = 0L; var n = 0
                while (alive) {
                    val idx = try { codec.dequeueOutputBuffer(info, 20_000) } catch (_: Exception) { break }
                    if (idx < 0) continue
                    try { codec.releaseOutputBuffer(idx, true) } catch (_: Exception) { break }
                    if (!shown) { shown = true; status("") }
                    val delay = TrueTime.nowMs() - info.presentationTimeUs / 1000
                    if (delay in 0..5000) {   // (an older remote sends a different clock: ignore)
                        sum += delay; n++
                        if (n >= 15) { val avg = (sum / n).toInt(); sum = 0; n = 0; ui.post { if (running) onDelay(avg) } }
                    }
                }
            }, "live-show").apply { isDaemon = true; priority = Thread.MAX_PRIORITY; start() }
        }

        fun stop() {
            alive = false
            try { out.join(300) } catch (_: InterruptedException) {}
            try { codec.stop() } catch (_: Exception) {}
            try { codec.release() } catch (_: Exception) {}
        }
    }

    private fun decode(inp: DataInputStream) {
        var dec: Decoder? = null
        var config: ByteArray? = null
        try {
            while (running) {
                val len = inp.readInt(); val kind = inp.readByte().toInt(); val key = inp.readByte().toInt() == 1
                val pts = inp.readLong()
                if (len < 0 || len > 8_000_000) throw IOException("bad packet")
                val data = ByteArray(len).also { inp.readFully(it) }
                when (kind) {
                    LiveLink.KIND_FORMAT -> {
                        val b = java.nio.ByteBuffer.wrap(data); val w = b.int; val h = b.int
                        dec?.stop()
                        dec = Decoder(w, h)
                        config = null
                        ui.post { if (running) onSize(w, h) }
                    }
                    LiveLink.KIND_CONFIG -> { config = data; dec?.let { queue(it.codec, data, pts, MediaCodec.BUFFER_FLAG_CODEC_CONFIG) } }
                    LiveLink.KIND_FRAME -> {
                        val d = dec ?: continue
                        if (config == null) continue
                        queue(d.codec, data, pts, if (key) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0)
                    }
                }
            }
        } finally {
            dec?.stop()
        }
    }

    private fun queue(c: MediaCodec, data: ByteArray, pts: Long, flags: Int) {
        // Every frame builds on the one before: skipping one would smear the picture until the
        // next key frame. So wait for the decoder; if it is truly stuck, start the stream afresh.
        val idx = c.dequeueInputBuffer(1_000_000)
        if (idx < 0) throw IOException("decoder not accepting frames")
        c.getInputBuffer(idx)?.apply { clear(); put(data) }
        c.queueInputBuffer(idx, 0, data.size, pts, flags)
    }

    fun close() {
        running = false
        wifi.release()
        target = null
        socket?.let { try { it.close() } catch (_: Exception) {} }
        thread?.interrupt()
    }
}

/**
 * Keeps Wi‑Fi out of its power-saving mode while live video runs: in that mode the radio naps
 * between beacons, which adds bursts of delay.
 */
class WifiFast(ctx: Context, tag: String) {
    private val wm = ctx.applicationContext.getSystemService(WifiManager::class.java)
    // Low-latency mode only works with the screen on and the app in front (the viewing phone);
    // high-performance mode also works on a remote camera with its screen off.
    @Suppress("DEPRECATION")
    private val locks = listOfNotNull(
        wm?.createWifiLock(WifiManager.WIFI_MODE_FULL_LOW_LATENCY, tag),
        wm?.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "$tag-perf"),
    ).onEach { it.setReferenceCounted(false) }

    fun acquire() { locks.forEach { runCatching { it.acquire() } } }
    fun release() { locks.forEach { runCatching { if (it.isHeld) it.release() } } }
}

/**
 * Picks the live video's bit rate from how long frames wait to be sent ([lagMs]): waiting means
 * the link carries less than we produce, so step down quickly; a link that has stayed clear for a
 * few seconds gets a little more. Plain arithmetic so it can be unit-tested.
 */
class LiveRate(
    private val start: Int = LiveLink.BITRATE_START,
    private val min: Int = LiveLink.BITRATE_MIN,
    private val max: Int = LiveLink.BITRATE_MAX,
) {
    var current = start; private set
    var previous = start; private set
    private var slowFrames = 0
    private var lastChange = 0L
    private var clearSince = 0L

    fun reset(): Int { current = start; previous = start; slowFrames = 0; lastChange = 0; clearSince = 0; return current }

    /** Call once per frame. Returns the new bit rate when it should change, else null. */
    fun step(lagMs: Long, nowMs: Long): Int? {
        if (lagMs > 20) clearSince = 0L else if (clearSince == 0L) clearSince = nowMs
        slowFrames = if (lagMs > 50) slowFrames + 1 else 0
        val next = when {
            // Clearly behind (or behind for 4 frames running): cut by 40 %, at most every 0.4 s.
            (lagMs > 120 || slowFrames >= 4) && nowMs - lastChange >= 400 -> (current * 0.6f).toInt().coerceAtLeast(min)
            // Clear for 3 s: add 20 %, then wait another 3 s.
            clearSince != 0L && nowMs - clearSince >= 3000 && nowMs - lastChange >= 3000 -> (current * 1.2f).toInt().coerceAtMost(max)
            else -> current
        }
        if (next == current) return null
        previous = current; current = next; lastChange = nowMs; slowFrames = 0
        if (next > previous) clearSince = nowMs
        return next
    }
}
