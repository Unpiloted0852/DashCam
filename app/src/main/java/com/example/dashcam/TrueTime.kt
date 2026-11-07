package com.example.dashcam

import android.location.Location
import android.os.SystemClock
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import kotlin.math.abs

/**
 * Accurate wall-clock time, the way "atomic clock" apps do it: query internet time servers (SNTP),
 * correct for network delay, and keep the result anchored to the phone's monotonic clock so the
 * phone's own (drifting, user-settable) clock doesn't matter. Falls back to GNSS time from GPS
 * fixes when there's no data connection.
 */
object TrueTime {
    private val SERVERS = listOf("time.google.com", "time.cloudflare.com", "pool.ntp.org")
    private const val RESYNC_MS = 15 * 60_000L
    private const val NTP_EPOCH_OFFSET = 2_208_988_800L   // seconds 1900 -> 1970

    /** True UTC (ms) at elapsedRealtime [anchorElapsedNs]; null until the first sync. */
    @Volatile private var anchorTrueMs: Double? = null
    @Volatile private var anchorElapsedNs = 0L
    @Volatile var source = "phone clock"; private set
    @Volatile var uncertaintyMs = -1L; private set
    @Volatile private var lastNtpElapsed = 0L
    private var exec: ScheduledExecutorService? = null

    fun start() {
        if (exec != null) return
        exec = Executors.newSingleThreadScheduledExecutor().also {
            // Never let one failure cancel the schedule (a thrown exception would stop all resyncs).
            it.scheduleWithFixedDelay({
                try { syncIfDue() } catch (t: Throwable) { EventLog.log("Time", "sync error: $t") }
            }, 0, 60, TimeUnit.SECONDS)
        }
    }

    fun stop() { exec?.shutdownNow(); exec = null }

    /** Accurate current time in ms since 1970 (UTC). */
    fun nowMs(): Long = atElapsedNs(SystemClock.elapsedRealtimeNanos())

    /** Same as [nowMs] with sub-millisecond precision (for the remote-camera clock exchange). */
    fun nowMsExact(): Double {
        val e = SystemClock.elapsedRealtimeNanos()
        val t = anchorTrueMs ?: return System.currentTimeMillis().toDouble()
        return t + (e - anchorElapsedNs) / 1e6
    }

    // ---- remote camera: the main phone is this phone's time source (see RemoteLink)

    private const val LINK_FRESH_MS = 10 * 60_000L
    @Volatile private var lastLinkElapsed = 0L
    @Volatile var linkRttMs = -1.0; private set

    /** True while time from the main phone is recent; NTP and GPS then stay out of the way. */
    fun linkFresh(): Boolean = lastLinkElapsed != 0L && SystemClock.elapsedRealtime() - lastLinkElapsed < LINK_FRESH_MS

    /**
     * Best sample of a ping exchange: the main phone's clock read [trueMs] at about [elapsedNs] here,
     * measured with round trip [rttMs]. [peerUncertaintyMs] is how sure the main phone is of its own time.
     */
    fun onLinkSample(trueMs: Double, elapsedNs: Long, rttMs: Double, peerUncertaintyMs: Long, peerSource: String) {
        val wasFresh = linkFresh()
        // Keep the current anchor if it is still better: its error grows only by the two crystals' drift.
        val boundNow = currentLinkBoundMs()
        if (wasFresh && boundNow != null && rttMs / 2 > boundNow) {
            lastLinkElapsed = SystemClock.elapsedRealtime()
            uncertaintyMs = maxOf(1L, boundNow.toLong()) + maxOf(0L, peerUncertaintyMs)
            return
        }
        val before = if (anchorTrueMs != null) nowMsExact() else null
        anchorTrueMs = trueMs; anchorElapsedNs = elapsedNs
        uncertaintyMs = maxOf(1L, (rttMs / 2).toLong()) + maxOf(0L, peerUncertaintyMs)
        source = "main phone ($peerSource)"
        linkRttMs = rttMs; linkAnchorNs = elapsedNs
        lastLinkElapsed = SystemClock.elapsedRealtime(); lastSyncElapsed = lastLinkElapsed
        val step = before?.let { String.format(Locale.US, "%+.1f", trueMs + (SystemClock.elapsedRealtimeNanos() - elapsedNs) / 1e6 - it) } ?: "n/a"
        if (!wasFresh) EventLog.log("Time", String.format(Locale.US,
            "synced from main phone, round trip %.1f ms, step %s ms; phone clock off by %d ms", rttMs, step, phoneClockErrorMs()))
    }

    private const val DRIFT_MS_PER_S = 0.1   // two phone crystals, up to ~50 ppm each
    @Volatile private var linkAnchorNs = 0L

    /** Worst-case error (ms) of the current link-based anchor, or null if there is none. */
    private fun currentLinkBoundMs(): Double? {
        if (linkRttMs < 0 || linkAnchorNs == 0L) return null
        val ageS = (SystemClock.elapsedRealtimeNanos() - linkAnchorNs) / 1e9
        return linkRttMs / 2 + ageS * DRIFT_MS_PER_S
    }


    /** Accurate wall time at a given elapsedRealtimeNanos instant (e.g. when a frame was captured). */
    fun atElapsedNs(elapsedNs: Long): Long {
        val t = anchorTrueMs ?: return System.currentTimeMillis() - (SystemClock.elapsedRealtimeNanos() - elapsedNs) / 1_000_000
        return (t + (elapsedNs - anchorElapsedNs) / 1e6).toLong()
    }

    /** How far the phone's own clock is off, in ms (positive = phone is behind). */
    fun phoneClockErrorMs(): Long = nowMs() - System.currentTimeMillis()

    @Volatile private var lastSyncElapsed = 0L

    private fun syncIfDue() {
        val now = SystemClock.elapsedRealtime()
        if (linkFresh()) return
        if (lastNtpElapsed != 0L && now - lastNtpElapsed < RESYNC_MS) return
        var best: Sample? = null
        for (host in SERVERS) {
            repeat(4) {
                val s = try { query(host) } catch (_: Exception) { null }
                if (s != null && (best == null || s.rttMs < best!!.rttMs)) best = s
            }
            if (best != null && best!!.rttMs < 150) break   // good enough; skip the other servers
        }
        val b = best ?: return
        if (linkFresh()) return
        anchorTrueMs = b.trueMs; anchorElapsedNs = b.elapsedNs
        uncertaintyMs = maxOf(1L, (b.rttMs / 2).toLong())
        source = "NTP ${b.host}"
        lastNtpElapsed = now; lastSyncElapsed = now
        EventLog.log("Time", "NTP ${b.host} ±$uncertaintyMs ms; phone clock off by ${phoneClockErrorMs()} ms")
    }

    /**
     * GNSS fallback: a GPS fix carries satellite time. Used when there's no recent NTP sync
     * (e.g. no signal on a road trip).
     */
    fun onGpsFix(loc: Location) {
        if (loc.provider != "gps" || loc.time <= 0) return
        val now = SystemClock.elapsedRealtime()
        val ntpFresh = lastNtpElapsed != 0L && now - lastNtpElapsed < 2 * RESYNC_MS
        if (ntpFresh || linkFresh()) return
        val wasUnsynced = anchorTrueMs == null
        anchorTrueMs = loc.time.toDouble(); anchorElapsedNs = loc.elapsedRealtimeNanos
        uncertaintyMs = 50; source = "GPS satellites"; lastSyncElapsed = now
        if (wasUnsynced) EventLog.log("Time", "synced from GPS; phone clock off by ${phoneClockErrorMs()} ms")
    }

    private class Sample(val host: String, val trueMs: Double, val elapsedNs: Long, val rttMs: Double)

    private fun query(host: String): Sample? {
        DatagramSocket().use { sock ->
            sock.soTimeout = 3000
            val addr = InetAddress.getByName(host)
            val buf = ByteArray(48)
            buf[0] = 0x1B   // LI=0, version 3, mode 3 (client)
            val t0 = SystemClock.elapsedRealtimeNanos()
            sock.send(DatagramPacket(buf, buf.size, addr, 123))
            val resp = DatagramPacket(ByteArray(48), 48)
            sock.receive(resp)
            val t3 = SystemClock.elapsedRealtimeNanos()
            val d = resp.data
            val leap = (d[0].toInt() shr 6) and 3
            val mode = d[0].toInt() and 7
            val stratum = d[1].toInt() and 0xff
            if (leap == 3 || mode != 4 || stratum !in 1..15) return null
            val serverRx = ntpMs(d, 32)   // T1: server received request
            val serverTx = ntpMs(d, 40)   // T2: server sent reply
            if (serverTx <= 0) return null
            val clientRoundMs = (t3 - t0) / 1e6
            val serverHoldMs = serverTx - serverRx
            val rtt = clientRoundMs - serverHoldMs
            if (rtt < 0 || abs(serverHoldMs) > 1000) return null
            // Reply left the server at T2 and took about half the network round trip to arrive.
            return Sample(host, serverTx + rtt / 2, t3, rtt)
        }
    }

    private fun ntpMs(d: ByteArray, off: Int): Double {
        var secs = 0L; var frac = 0L
        for (i in 0..3) secs = (secs shl 8) or (d[off + i].toLong() and 0xff)
        for (i in 4..7) frac = (frac shl 8) or (d[off + i].toLong() and 0xff)
        if (secs == 0L) return 0.0
        return (secs - NTP_EPOCH_OFFSET) * 1000.0 + frac * 1000.0 / 4294967296.0
    }
}
