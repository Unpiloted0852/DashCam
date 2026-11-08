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
}

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
    fun createGroup(ssid: String, pass: String, onOk: (Int) -> Unit, onFail: (String) -> Unit) =
        tryCreate(ssid, pass, { reportFreq(ssid, onOk, 0) }, onFail, 0)

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
        val cfg = WifiP2pConfig.Builder().setNetworkName(ssid).setPassphrase(pass).build()
        mgr!!.removeGroup(c, action("remove", { go(c, cfg, ssid, pass, onOk, onFail, attempt) },
            { go(c, cfg, ssid, pass, onOk, onFail, attempt) }))
    }

    private fun go(c: WifiP2pManager.Channel, cfg: WifiP2pConfig, ssid: String, pass: String,
                   onOk: () -> Unit, onFail: (String) -> Unit, attempt: Int) {
        if (closed) return
        mgr!!.createGroup(c, cfg, action("create group", onOk) { err ->
            if (attempt < 3 && !closed) ui.postDelayed({ tryCreate(ssid, pass, onOk, onFail, attempt + 1) }, 1500)
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
class LiveServer(val token: String, private val onKeyFrameWanted: () -> Unit) : CameraPipeline.LiveSink {
    private class Pkt(val kind: Int, val key: Boolean, val pts: Long, val data: ByteArray)

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
            s.tcpNoDelay = true; s.soTimeout = 0; s.sendBufferSize = 256 * 1024
            client = s
            lastClientAt = SystemClock.elapsedRealtime()
            EventLog.log("Live", "viewer connected")
            Thread({ sendLoop(s) }, "live-send").apply { isDaemon = true; start() }
        }
    }

    private fun sendLoop(s: Socket) {
        queue.clear(); dropping = true
        try {
            val out = DataOutputStream(s.getOutputStream().buffered(64 * 1024))
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
        // Viewer falling behind (weak link): drop everything until the next key frame rather than lag.
        if (queue.size > 20) { queue.removeIf { it.kind == LiveLink.KIND_FRAME }; dropping = true; onKeyFrameWanted() }
        if (dropping && !key) return
        dropping = false
        queue.add(Pkt(LiveLink.KIND_FRAME, key, ptsUs, data))
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
    private val surface: Surface,
    private val onStatus: (String) -> Unit,
    private val onSize: (Int, Int) -> Unit,
) {
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

    private fun decode(inp: DataInputStream) {
        var codec: MediaCodec? = null
        var config: ByteArray? = null
        var shown = false
        val info = MediaCodec.BufferInfo()
        try {
            while (running) {
                val len = inp.readInt(); val kind = inp.readByte().toInt(); val key = inp.readByte().toInt() == 1
                val pts = inp.readLong()
                if (len < 0 || len > 8_000_000) throw IOException("bad packet")
                val data = ByteArray(len).also { inp.readFully(it) }
                when (kind) {
                    LiveLink.KIND_FORMAT -> {
                        val b = java.nio.ByteBuffer.wrap(data); val w = b.int; val h = b.int
                        codec?.let { try { it.stop(); it.release() } catch (_: Exception) {} }
                        codec = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC).apply {
                            val f = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, w, h)
                            if (Build.VERSION.SDK_INT >= 30) f.setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
                            f.setInteger(MediaFormat.KEY_PRIORITY, 0)
                            configure(f, surface, null, 0); start()
                        }
                        config = null; shown = false
                        ui.post { if (running) onSize(w, h) }
                    }
                    LiveLink.KIND_CONFIG -> { config = data; codec?.let { queue(it, data, pts, MediaCodec.BUFFER_FLAG_CODEC_CONFIG) } }
                    LiveLink.KIND_FRAME -> {
                        val c = codec ?: continue
                        if (config == null) continue
                        queue(c, data, pts, if (key) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0)
                    }
                }
                val c = codec ?: continue
                while (true) {
                    val idx = c.dequeueOutputBuffer(info, 0)
                    if (idx < 0) break
                    c.releaseOutputBuffer(idx, true)
                    if (!shown) { shown = true; status("") }
                }
            }
        } finally {
            codec?.let { try { it.stop() } catch (_: Exception) {}; try { it.release() } catch (_: Exception) {} }
        }
    }

    private fun queue(c: MediaCodec, data: ByteArray, pts: Long, flags: Int) {
        val idx = c.dequeueInputBuffer(50_000)
        if (idx < 0) return   // decoder busy: drop this frame
        c.getInputBuffer(idx)?.apply { clear(); put(data) }
        c.queueInputBuffer(idx, 0, data.size, pts, flags)
    }

    fun close() {
        running = false
        target = null
        socket?.let { try { it.close() } catch (_: Exception) {} }
        thread?.interrupt()
    }
}
