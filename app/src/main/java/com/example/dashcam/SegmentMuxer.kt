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
class SegmentClock(@Volatile private var startMs: Long, private val lengthMs: Long) {
    /** Begin a new run of segments now (e.g. after the phone was rotated). */
    fun restart() { startMs = TrueTime.nowMs() }

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
    private val clock: SegmentClock,
    private val onClosed: (uri: Uri, locked: Boolean) -> Unit,
) {
    private var videoFormat: MediaFormat? = null
    private var audioFormat: MediaFormat? = null
    private var muxer: MediaMuxer? = null
    private var pfd: ParcelFileDescriptor? = null
    private var uri: Uri? = null
    private var vTrack = -1
    private var aTrack = -1
    private var segIndex = -1L
    private var lockCurrent = false
    private var prevUri: Uri? = null
    private var prevLocked = false
    private var waitingForAudioSince = 0L
    @Volatile var lastError: String? = null; private set

    val currentUri: Uri? @Synchronized get() = uri

    @Synchronized fun setVideoFormat(f: MediaFormat) { videoFormat = f }
    @Synchronized fun setAudioFormat(f: MediaFormat) { audioFormat = f }

    @Synchronized
    fun writeVideo(buf: ByteBuffer, info: MediaCodec.BufferInfo) {
        val key = info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0
        if (key) {
            val idx = clock.indexNow()
            if (muxer == null || idx != segIndex) {
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
        try {
            m.writeSampleData(vTrack, buf, info)
        } catch (e: Exception) {
            Log.e(TAG, "video write", e)
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
        if (p != null && !prevLocked) {
            prevLocked = true
            Thread { Storage.lock(ctx, p) }.start()   // may retry for a few seconds; keep it off the UI thread
        }
    }

    @Synchronized
    fun close() {
        closeCurrent()
    }

    /** Finish the current file; the next key frame (possibly in a new format) starts a new one. */
    @Synchronized
    fun closeSegment() {
        closeCurrent()
        segIndex = -1
    }

    private fun roll(idx: Long) {
        closeCurrent()
        val vf = videoFormat ?: return
        var created: Uri? = null
        try {
            val (u, fd) = Storage.create(ctx, volume, clock.fileName(idx, tag))
            created = u
            val m = MediaMuxer(fd.fileDescriptor, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            Telemetry.freshFix()?.let { m.setLocation(it.latitude.toFloat(), it.longitude.toFloat()) }
            vTrack = m.addTrack(vf)
            aTrack = audioFormat?.let { m.addTrack(it) } ?: -1
            m.start()
            muxer = m; pfd = fd; uri = u; segIndex = idx
            Storage.recordStart(ctx, clock.fileName(idx, tag), TrueTime.nowMs())
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
            onClosed(u, lockCurrent)
            prevUri = u
            prevLocked = lockCurrent
        }
        lockCurrent = false
    }

    companion object { private const val TAG = "SegmentMuxer" }
}
