package com.example.dashcam

import android.content.Context
import android.media.MediaCodec
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.util.Log
import java.nio.ByteBuffer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Shared segment timeline so front and rear clips get identical names and boundaries. */
class SegmentClock(val startMs: Long, val lengthMs: Long) {
    fun indexNow(): Long = (TrueTime.nowMs() - startMs).coerceAtLeast(0) / lengthMs
    fun fileName(index: Long, tag: String): String {
        val f = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US)
        return "${f.format(Date(startMs + index * lengthMs))}_$tag.mp4"
    }
}

/**
 * Writes encoded samples into a series of MP4 files, starting a new file on the first key frame of
 * each new segment. Thread-safe: video and audio arrive from different threads.
 */
class SegmentMuxer(
    private val ctx: Context,
    private val tag: String,
    private val volume: String,
    @Volatile var expectAudio: Boolean,
    @Volatile private var clock: SegmentClock,
    private val onClosed: (uri: Uri, locked: Boolean, tag: String) -> Unit,
) {
    private var videoFormat: MediaFormat? = null
    private var audioFormat: MediaFormat? = null
    private var muxer: Sink? = null
    private var pfd: ParcelFileDescriptor? = null
    private var uri: Uri? = null
    private var curName: String? = null
    private var firstElapsedNs = 0L
    private var startWall = 0L

    /**
     * The accurate clock may have been set only after this clip began (a remote camera gets the main
     * phone's time a few seconds after linking; a phone without internet waits for NTP or GPS). The
     * first frame's moment is known exactly on the phone's own steady clock, so work out its wall
     * time again with the clock as it is now, and store the better value.
     */
    private fun correctStartWall(m: Sink) {
        if (firstElapsedNs == 0L || startWall == 0L) return
        val better = TrueTime.atElapsedNs(firstElapsedNs)
        val diff = better - startWall
        firstElapsedNs = 0L
        if (kotlin.math.abs(diff) < 5) return
        try { m.setStartWall(better) } catch (e: Exception) { Log.w(TAG, "start time", e); return }
        curName?.let { Storage.recordStart(ctx, it, better) }
        EventLog.log("Clip", "$tag: start time corrected by $diff ms (clock synced after the clip began)")
    }
    private var vTrack = -1
    private var aTrack = -1
    private var segIndex = -1L
    private var lockCurrent = false
    private var prevUri: Uri? = null
    private var prevLocked = false
    private var waitingForAudioSince = 0L
    private var firstPtsUs = -1L   // monotonic presentation times of this file's first/last frame
    private var lastPtsUs = -1L
    @Volatile var lastError: String? = null; private set

    val currentUri: Uri? @Synchronized get() = uri

    private var forceRoll = false

    /** Follow a new timeline (the main phone's): the current clip ends at the next key frame. */
    @Synchronized fun switchClock(c: SegmentClock) { clock = c; forceRoll = true }

    @Synchronized fun setVideoFormat(f: MediaFormat) { videoFormat = f }
    @Synchronized fun setAudioFormat(f: MediaFormat) { audioFormat = f }

    @Synchronized
    fun writeVideo(buf: ByteBuffer, info: MediaCodec.BufferInfo) {
        val key = info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0
        if (key) {
            val idx = clock.indexNow()
            if (muxer == null || idx != segIndex || forceRoll) {
                // Give the audio encoder up to 2 s to report its format so the first file has sound.
                val holdForAudio = muxer == null && expectAudio && audioFormat == null && run {
                    val now = SystemClock.elapsedRealtime()
                    if (waitingForAudioSince == 0L) waitingForAudioSince = now
                    now - waitingForAudioSince < 2000
                }
                if (!holdForAudio) roll(idx)
            }
        }
        val m = muxer ?: return
        val first = firstPtsUs < 0
        if (first) firstPtsUs = info.presentationTimeUs
        lastPtsUs = info.presentationTimeUs
        try {
            m.writeSampleData(vTrack, buf, info)
        } catch (e: Exception) {
            Log.e(TAG, "video write", e)
            // Shown on screen: a full or removed card must not fail silently.
            if (lastError == null) {
                lastError = "Can't save video (${e.message ?: e.javaClass.simpleName}) — storage full or removed?"
                EventLog.log("Clip", "$tag: write failed: ${e.message}")
            }
        }
        if (first) {
            // When the file's first frame was captured (frame times use the monotonic clock).
            val elapsedNs = info.presentationTimeUs * 1000 + (SystemClock.elapsedRealtimeNanos() - System.nanoTime())
            firstElapsedNs = elapsedNs
            val wall = TrueTime.atElapsedNs(elapsedNs)
            startWall = wall
            try { m.setStartWall(wall) } catch (e: Exception) { Log.w(TAG, "start time", e) }
            curName?.let { Storage.recordStart(ctx, it, wall) }
        }
    }

    @Synchronized
    fun writeAudio(buf: ByteBuffer, info: MediaCodec.BufferInfo) {
        val m = muxer ?: return
        if (aTrack < 0) return
        try {
            m.writeSampleData(aTrack, buf, info)
        } catch (e: Exception) {
            Log.e(TAG, "audio write", e)
        }
    }

    /** Protect the clip being recorded now plus the one before it from loop deletion. */
    @Synchronized
    fun lock() {
        lockCurrent = true
        val p = prevUri
        // Remember the current clip's name right away, so it stays protected even if the app or
        // phone dies before the clip is closed.
        uri?.let { u -> Thread { Storage.protect(ctx, u) }.start() }
        if (p != null && !prevLocked) {
            prevLocked = true
            Thread { Storage.lock(ctx, p) }.start()   // may retry for a few seconds; keep it off the UI thread
        }
    }

    @Synchronized
    fun close() {
        closeCurrent()
    }


    private fun roll(idx: Long) {
        forceRoll = false
        closeCurrent()
        val vf = videoFormat ?: return
        var created: Uri? = null
        try {
            val (u, fd) = Storage.create(ctx, volume, clock.fileName(idx, tag))
            created = u
            val m = openSink(fd, vf, audioFormat)
            vTrack = 0
            aTrack = if (audioFormat != null) 1 else -1
            muxer = m; pfd = fd; uri = u; segIndex = idx
            firstPtsUs = -1L; lastPtsUs = -1L
            firstElapsedNs = 0L; startWall = 0L
            curName = clock.fileName(idx, tag)
            Storage.recordStart(ctx, clock.fileName(idx, tag), TrueTime.nowMs())   // refined at the first frame
            lastError = null
            Log.i(TAG, "new segment $u")
            EventLog.log("Clip", "$tag: new ${clock.fileName(idx, tag)} (audio track: ${aTrack >= 0})")
        } catch (e: Exception) {
            Log.e(TAG, "could not start segment", e)
            created?.let {
                Storage.markClosed(it)
                try { ctx.contentResolver.delete(it, null, null) } catch (_: Exception) {}
            }
            lastError = "Can't write clip: ${e.message}"
            EventLog.log("Clip", "$tag: could not start segment: ${e.message}")
            muxer = null; pfd = null; uri = null
            segIndex = idx // don't retry on every key frame; try again next segment
        }
    }

    private fun closeCurrent() {
        val m = muxer ?: return
        correctStartWall(m)
        try { m.stop() } catch (e: Exception) {
            Log.w(TAG, "muxer stop", e)
            EventLog.log("Clip", "$tag: finalising clip failed: ${e.message}")
        }
        try { m.release() } catch (_: Exception) {}
        try { pfd?.close() } catch (_: Exception) {}
        val u = uri
        muxer = null; pfd = null; uri = null
        if (u != null) {
            // Move to Locked/ before loop deletion is allowed to see the clip.
            if (lockCurrent) Storage.lock(ctx, u)
            Storage.markClosed(u)
            EventLog.log("Clip", "$tag: closed, video pts ${firstPtsUs / 1000}..${lastPtsUs / 1000} ms")
            onClosed(u, lockCurrent, tag)
            prevUri = u
            prevLocked = lockCurrent
        }
        lockCurrent = false
    }

    /** The file writer: crash-safe fragmented MP4, or MediaMuxer if that can't start. */
    private interface Sink {
        fun writeSampleData(track: Int, buf: ByteBuffer, info: MediaCodec.BufferInfo)
        fun setStartWall(ms: Long) {}
        fun stop()
        fun release()
    }

    private fun openSink(fd: ParcelFileDescriptor, vf: MediaFormat, af: MediaFormat?): Sink {
        val loc = Telemetry.latLon()
        try {
            val f = Fmp4Muxer(fd.fileDescriptor)
            loc?.let { (la, lo) -> f.setLocation(la.toFloat(), lo.toFloat()) }
            f.addTrack(vf); af?.let { f.addTrack(it) }
            f.start()
            return object : Sink {
                override fun writeSampleData(track: Int, buf: ByteBuffer, info: MediaCodec.BufferInfo) = f.writeSampleData(track, buf, info)
                override fun setStartWall(ms: Long) = f.setStartWall(ms)
                override fun stop() = f.stop()
                override fun release() = f.release()
            }
        } catch (e: Exception) {
            Log.w(TAG, "fragmented writer failed, using MediaMuxer", e)
            EventLog.log("Clip", "$tag: crash-safe writer unavailable (${e.message}); using standard MP4")
            android.system.Os.ftruncate(fd.fileDescriptor, 0)
            android.system.Os.lseek(fd.fileDescriptor, 0, android.system.OsConstants.SEEK_SET)
        }
        val m = MediaMuxer(fd.fileDescriptor, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        loc?.let { (la, lo) -> m.setLocation(la.toFloat(), lo.toFloat()) }
        m.addTrack(vf); af?.let { m.addTrack(it) }
        m.start()
        return object : Sink {
            override fun writeSampleData(track: Int, buf: ByteBuffer, info: MediaCodec.BufferInfo) = m.writeSampleData(track, buf, info)
            override fun stop() = m.stop()
            override fun release() = m.release()
        }
    }

    companion object { private const val TAG = "SegmentMuxer" }
}
