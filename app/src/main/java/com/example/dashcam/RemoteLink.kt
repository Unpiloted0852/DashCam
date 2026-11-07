package com.example.dashcam

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.util.UUID
import java.util.concurrent.Executors

/**
 * Always-on control link between a primary phone and a remote-camera phone over classic Bluetooth
 * (RFCOMM). AOSP APIs only, so it works on phones without Google Play Services or internet. The
 * phones are paired once in Android's Bluetooth settings; after that the remote listens and the
 * primary keeps (re)connecting.
 *
 * Wire format: one JSON object per line, `{"t": type, ...}`. The first message each way is
 * `hello` with [PROTO]; a mismatch closes the link with a clear message instead of misbehaving.
 *
 * Footage never goes over this link: each phone records locally, so a dropped link loses nothing.
 */
class RemoteLink(
    private val ctx: Context,
    private val primary: Boolean,
    private val peerAddress: String,
    private val listener: Listener,
) {
    interface Listener {
        /** Link state changed (connected/disconnected/error); called on a link thread. */
        fun onLinkState(state: State)
        /** A message from the other phone; called on the link's reader thread. */
        fun onMessage(type: String, m: JSONObject)
    }

    enum class Phase { OFF, WAITING, CONNECTING, CONNECTED, MISMATCH, ERROR }

    data class State(val phase: Phase, val detail: String = "", val peerName: String = "")

    @Volatile var state = State(Phase.OFF); private set
    val connected: Boolean get() = state.phase == Phase.CONNECTED

    @Volatile private var running = false
    @Volatile private var socket: BluetoothSocket? = null
    @Volatile private var server: BluetoothServerSocket? = null
    @Volatile private var out: OutputStream? = null
    @Volatile private var lastRx = 0L
    private var thread: Thread? = null
    private val writer = Executors.newSingleThreadExecutor()

    private val adapter: BluetoothAdapter? get() = ctx.getSystemService(BluetoothManager::class.java)?.adapter

    fun start() {
        if (running) return
        running = true
        thread = Thread({ loop() }, "remote-link").apply { isDaemon = true; start() }
    }

    fun stop() {
        running = false
        closeSocket()
        try { server?.close() } catch (_: Exception) {}
        server = null
        thread?.interrupt(); thread = null
        writer.shutdown()
        setState(State(Phase.OFF))
    }

    /** Queue a message; silently dropped if not connected. Never blocks the caller. */
    fun send(type: String, body: JSONObject = JSONObject()) {
        if (!connected && type != "hello") return
        body.put("t", type)
        val line = body.toString() + "\n"
        try {
            writer.execute {
                val o = out ?: return@execute
                try { o.write(line.toByteArray(Charsets.UTF_8)); o.flush() }
                catch (e: Exception) { EventLog.log("Link", "send failed: ${e.message}"); closeSocket() }
            }
        } catch (_: Exception) {}   // executor shut down
    }

    private fun setState(s: State) {
        if (s == state) return
        state = s
        listener.onLinkState(s)
    }

    private fun hasPerm(): Boolean = Build.VERSION.SDK_INT < 31 ||
        ctx.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission")
    private fun peerName(): String = try {
        adapter?.getRemoteDevice(peerAddress)?.name ?: peerAddress
    } catch (_: Exception) { peerAddress }

    private fun pause(ms: Long) { try { Thread.sleep(ms) } catch (_: InterruptedException) {} }

    @SuppressLint("MissingPermission")
    private fun loop() {
        var backoff = 3_000L
        while (running) {
            val a = adapter
            when {
                a == null -> { setState(State(Phase.ERROR, "This phone has no Bluetooth")); pause(30_000); continue }
                !hasPerm() -> { setState(State(Phase.ERROR, "Bluetooth permission needed — open Settings")); pause(5_000); continue }
                !a.isEnabled -> { setState(State(Phase.ERROR, "Bluetooth is off")); pause(5_000); continue }
                peerAddress.isEmpty() -> { setState(State(Phase.ERROR, "No phone chosen in Settings")); pause(5_000); continue }
            }
            val name = peerName()
            var mismatch = false
            try {
                val s: BluetoothSocket = if (primary) {
                    setState(State(Phase.CONNECTING, "", name))
                    val dev = a!!.getRemoteDevice(peerAddress)
                    dev.createRfcommSocketToServiceRecord(SERVICE_UUID).also { socket = it; it.connect() }
                } else {
                    setState(State(Phase.WAITING, "", name))
                    val srv = server ?: a!!.listenUsingRfcommWithServiceRecord("DashCam remote", SERVICE_UUID)
                        .also { server = it }
                    var accepted: BluetoothSocket
                    while (true) {
                        accepted = srv.accept()
                        // Only the phone chosen in Settings may control this one.
                        if (accepted.remoteDevice?.address.equals(peerAddress, true)) break
                        EventLog.log("Link", "refused connection from ${accepted.remoteDevice?.address}")
                        try { accepted.close() } catch (_: Exception) {}
                    }
                    socket = accepted
                    accepted
                }
                mismatch = session(s, name)
                backoff = 3_000L
            } catch (e: Exception) {
                if (running) EventLog.log("Link", "${if (primary) "connect" else "listen"}: ${e.message}")
                if (!primary) { try { server?.close() } catch (_: Exception) {}; server = null }
            }
            closeSocket()
            if (!running) break
            if (state.phase != Phase.MISMATCH) setState(State(if (primary) Phase.CONNECTING else Phase.WAITING, "", name))
            // A version mismatch won't fix itself: retry slowly. Otherwise back off up to 15 s.
            pause(if (mismatch) 60_000 else backoff)
            backoff = (backoff * 2).coerceAtMost(15_000)
        }
    }

    /** Runs one connection until it drops. Returns true if it ended because of a version mismatch. */
    private fun session(s: BluetoothSocket, name: String): Boolean {
        out = s.outputStream
        lastRx = SystemClock.elapsedRealtime()
        send("hello", JSONObject().put("proto", PROTO).put("app", BuildConfig.VERSION_NAME)
            .put("role", if (primary) "primary" else "remote"))
        val reader = BufferedReader(InputStreamReader(s.inputStream, Charsets.UTF_8))
        // Bluetooth sockets have no read timeout: a watchdog closes a silent link.
        val watchdog = Thread {
            while (socket === s) {
                pause(2_000)
                if (socket === s && SystemClock.elapsedRealtime() - lastRx > SILENCE_MS) {
                    EventLog.log("Link", "no data for ${SILENCE_MS / 1000} s — reconnecting")
                    closeSocket()
                }
            }
        }.apply { isDaemon = true; start() }
        var helloOk = false
        try {
            while (running) {
                val line = reader.readLine() ?: break
                lastRx = SystemClock.elapsedRealtime()
                val m = try { JSONObject(line) } catch (_: Exception) { continue }
                val t = m.optString("t")
                if (t == "hello") {
                    val proto = m.optInt("proto", -1)
                    val theirApp = m.optString("app", "?")
                    val theirRole = m.optString("role")
                    if (proto != PROTO) {
                        val msg = "Versions don't match: this phone v${BuildConfig.VERSION_NAME} (link $PROTO), " +
                            "$name v$theirApp (link $proto). Update both phones to the same version."
                        EventLog.log("Link", msg)
                        setState(State(Phase.MISMATCH, msg, name))
                        return true
                    }
                    if (theirRole == (if (primary) "primary" else "remote")) {
                        val msg = "$name is also set up as ${if (primary) "the main phone" else "a remote camera"} — " +
                            "set one phone to each role in Settings"
                        setState(State(Phase.MISMATCH, msg, name))
                        return true
                    }
                    helloOk = true
                    EventLog.log("Link", "connected to $name (v$theirApp)")
                    setState(State(Phase.CONNECTED, "v$theirApp", name))
                    continue
                }
                if (!helloOk) continue
                if (t == "hb") continue
                try { listener.onMessage(t, m) } catch (e: Exception) { EventLog.log("Link", "handling $t: $e") }
            }
        } catch (e: Exception) {
            if (running && socket === s) EventLog.log("Link", "link to $name lost: ${e.message}")
        } finally {
            watchdog.interrupt()
        }
        if (helloOk) EventLog.log("Link", "disconnected from $name")
        return false
    }

    private fun closeSocket() {
        val s = socket
        socket = null; out = null
        try { s?.close() } catch (_: Exception) {}
    }

    companion object {
        /** Bump when messages change incompatibly; both phones must match. */
        const val PROTO = 1
        val SERVICE_UUID: UUID = UUID.fromString("6f1d2a8e-3b7c-4e55-9a0d-d45c0a3e7b21")
        private const val SILENCE_MS = 12_000L

        @SuppressLint("MissingPermission")
        fun pairedDevices(ctx: Context) = try {
            ctx.getSystemService(BluetoothManager::class.java)?.adapter?.bondedDevices.orEmpty()
                .sortedBy { it.name ?: it.address }
        } catch (_: SecurityException) { emptyList() }
    }
}
