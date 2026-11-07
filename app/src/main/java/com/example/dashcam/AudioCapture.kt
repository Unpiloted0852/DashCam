package com.example.dashcam

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaRecorder
import android.util.Log
import kotlin.math.abs
import kotlin.math.max

/**
 * Microphone -> AAC, encoded once and written into every clip (front and rear), so each file has
 * sound on its own. Timestamps use System.nanoTime like the video frames.
 */
class AudioCapture(initial: List<SegmentMuxer>) {
    private val muxers = java.util.concurrent.CopyOnWriteArrayList(initial)
    @Volatile private var format: MediaFormat? = null

    /** Add a clip that joins mid-recording (e.g. the rear camera came up late). */
    fun addMuxer(m: SegmentMuxer) {
        format?.let { m.setAudioFormat(it) }
        muxers += m
    }

    /** Stop feeding a clip that has been finished. */
    fun removeMuxer(m: SegmentMuxer) {
        muxers -= m
    }

    @Volatile private var running = false
    private var thread: Thread? = null
    private var rec: AudioRecord? = null
    private var codec: MediaCodec? = null

    @SuppressLint("MissingPermission")
    fun start(): Boolean {
        return try {
            val minBuf = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            val r = AudioRecord(MediaRecorder.AudioSource.CAMCORDER, RATE, AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT, max(minBuf * 4, 16384))
            if (r.state != AudioRecord.STATE_INITIALIZED) { r.release(); return false }
            val fmt = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, RATE, 1).apply {
                setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                setInteger(MediaFormat.KEY_BIT_RATE, 96_000)
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, CHUNK * 2)
            }
            val c = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
            c.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            c.start()
            r.startRecording()
            rec = r; codec = c
            running = true
            thread = Thread({ loop(r, c) }, "audio").apply { start() }
            true
        } catch (e: Exception) {
            Log.e(TAG, "audio start failed", e)
            false
        }
    }

    fun stop() {
        running = false
        thread?.join(2000)
        thread = null
        try { rec?.stop() } catch (_: Exception) {}
        rec?.release(); rec = null
        try { codec?.stop() } catch (_: Exception) {}
        codec?.release(); codec = null
    }

    private fun loop(r: AudioRecord, c: MediaCodec) {
        val info = MediaCodec.BufferInfo()
        var anchorUs = -1L
        var samples = 0L
        var lastPts = -1L
        try {
            while (running) {
                val inIdx = c.dequeueInputBuffer(10_000)
                if (inIdx >= 0) {
                    val buf = c.getInputBuffer(inIdx)!!
                    buf.clear()
                    val n = r.read(buf, minOf(buf.capacity(), CHUNK))
                    if (n > 0) {
                        val nowUs = System.nanoTime() / 1000
                        val chunkUs = n / 2 * 1_000_000L / RATE
                        if (anchorUs < 0) anchorUs = nowUs - chunkUs
                        var pts = anchorUs + samples * 1_000_000L / RATE
                        // Re-anchor if the sample clock drifts noticeably from the system clock.
                        if (abs(pts - (nowUs - chunkUs)) > 50_000) {
                            anchorUs = nowUs - chunkUs; samples = 0; pts = anchorUs
                        }
                        if (pts <= lastPts) pts = lastPts + 1
                        lastPts = pts
                        samples += n / 2
                        c.queueInputBuffer(inIdx, 0, n, pts, 0)
                    } else {
                        c.queueInputBuffer(inIdx, 0, 0, max(lastPts, 0), 0)
                    }
                }
                drain(c, info)
            }
        } catch (e: Exception) {
            Log.e(TAG, "audio loop", e)
        }
    }

    private fun drain(c: MediaCodec, info: MediaCodec.BufferInfo) {
        while (true) {
            val idx = c.dequeueOutputBuffer(info, 0)
            when {
                idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    val f = c.outputFormat
                    format = f
                    muxers.forEach { it.setAudioFormat(f) }
                }
                idx < 0 -> return
                else -> {
                    val out = c.getOutputBuffer(idx)
                    if (out != null && info.size > 0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                        for (m in muxers) {
                            out.limit(info.offset + info.size); out.position(info.offset)
                            m.writeAudio(out, info)
                        }
                    }
                    c.releaseOutputBuffer(idx, false)
                }
            }
        }
    }

    companion object {
        private const val TAG = "AudioCapture"
        private const val RATE = 48_000
        private const val CHUNK = 2048
    }
}
