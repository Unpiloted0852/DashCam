package com.example.dashcam

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Rect
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.params.MeteringRectangle
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.opengl.EGLSurface
import android.opengl.GLES20
import android.opengl.Matrix
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log
import android.util.Range
import android.util.Size
import android.view.Surface
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import kotlin.math.abs

/**
 * One camera: Camera2 -> SurfaceTexture (OES) -> GL compositing (camera + text stamp) ->
 * H.264 encoder input surface (+ optional on-screen preview surface).
 */
class CameraPipeline(
    ctx: Context,
    val role: Role,
    val cameraId: String,
    requestedSize: Size,
    private val listener: Listener,
    /** Zoom ratio for lenses reached by zooming the main camera (e.g. 0.5x); null = 1x. */
    private val zoomRatio: Float? = null,
) : SurfaceTexture.OnFrameAvailableListener {

    enum class Role(val tag: String, val label: String) {
        MAIN("front", "FRONT"), CABIN("rear", "REAR")
    }

    /** Why a camera went away. */
    enum class Loss {
        /** Something with higher priority took it (face unlock, another camera app, policy). Comes back. */
        BORROWED,
        /** The phone can't run this many cameras at once. Won't come back by retrying. */
        TOO_MANY,
        OTHER,
    }

    interface Listener {
        fun onCameraStarted(p: CameraPipeline)
        fun onCameraLost(p: CameraPipeline, reason: String, kind: Loss)
    }

    private val manager = ctx.getSystemService(CameraManager::class.java)
    private val prefs = androidx.preference.PreferenceManager.getDefaultSharedPreferences(ctx.applicationContext)
    private val chars = manager.getCameraCharacteristics(cameraId)
    val isFrontFacing = chars.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_FRONT
    private val sensorOrientation = chars.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0
    private val timestampRealtime = chars.get(CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE) ==
        CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE_REALTIME
    private val minFocusDiopters = chars.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE) ?: 0f
    private val hyperfocal = chars.get(CameraCharacteristics.LENS_INFO_HYPERFOCAL_DISTANCE) ?: 0f
    private val afModes = chars.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES)?.toSet() ?: emptySet()
    private val canManualFocus = minFocusDiopters > 0f && CaptureRequest.CONTROL_AF_MODE_OFF in afModes
    private val focusCalibrated = chars.get(CameraCharacteristics.LENS_INFO_FOCUS_DISTANCE_CALIBRATION) !=
        CameraCharacteristics.LENS_INFO_FOCUS_DISTANCE_CALIBRATION_UNCALIBRATED
    private val maxAfRegions = chars.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AF) ?: 0
    private val activeArray = chars.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
    private val fpsRange: Range<Int>? = chooseFps()
    private val eisModes = chars.get(CameraCharacteristics.CONTROL_AVAILABLE_VIDEO_STABILIZATION_MODES)?.toSet() ?: emptySet()
    private val oisModes = chars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION)?.toSet() ?: emptySet()
    private val hasEis = CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_ON in eisModes
    private val hasOis = CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_ON in oisModes

    @Volatile var size: Size = chooseSize(requestedSize); private set

    private val glThread = HandlerThread("gl-${role.tag}").apply { start() }
    private val gl = Handler(glThread.looper)
    private val camThread = HandlerThread("cam-${role.tag}").apply { start() }
    private val cam = Handler(camThread.looper)
    private val encThread = HandlerThread("enc-${role.tag}").apply { start() }
    private val enc = Handler(encThread.looper)

    // --- GL thread state ---
    private var egl: EglCore? = null
    private var pbuffer: EGLSurface? = null
    private var drawer: FrameDrawer? = null
    private var encOverlay: OverlayTexture? = null
    private var previewOverlay: OverlayTexture? = null
    private var oesTex = 0
    private var st: SurfaceTexture? = null
    @Volatile private var camSurface: Surface? = null   // made on the GL thread, used on the camera thread
    private val stMatrix = FloatArray(16)
    private val mvp = FloatArray(16)
    private var previewEgl: EGLSurface? = null
    private var previewW = 0
    private var previewH = 0
    private var encEgl: EGLSurface? = null
    private var recAngle = 0
    private var recSize = Size(0, 0)

    /** After a rotation the old encoder keeps receiving frames briefly so the clips overlap. */
    private class Retiring(
        val egl: EGLSurface, val codec: MediaCodec?, val angle: Int, val size: Size,
        var overlay: OverlayTexture?, val untilElapsed: Long,
    )
    private var retiring: Retiring? = null
    private var lastSyncIdx = -1L
    private var frames = 0
    private var fpsWindowStart = 0L

    // --- recording state ---
    private var encoder: MediaCodec? = null
    private var encInput: Surface? = null
    @Volatile private var muxer: SegmentMuxer? = null
    @Volatile private var clock: SegmentClock? = null
    private var eos: CountDownLatch? = null

    // --- live view (GL thread): a small low-latency H.264 stream for the main phone's screen ---
    interface LiveSink {
        fun onLiveFormat(w: Int, h: Int)
        /** Encoded data; [config] = SPS/PPS. Called on the encoder thread. */
        fun onLiveData(data: ByteArray, ptsUs: Long, key: Boolean, config: Boolean)
    }
    private var liveCodec: MediaCodec? = null
    private var liveInput: Surface? = null
    private var liveEgl: EGLSurface? = null
    private var liveSize = Size(0, 0)
    private var liveAngle = 0
    val liveActive: Boolean get() = liveCodec != null

    // --- camera thread state ---
    private var device: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    @Volatile private var released = false

    // --- settings (any thread) ---
    @Volatile var focus = FocusConfig(false, "inf")
        set(v) { if (field != v) { field = v; cam.post { applyRequest() } } }
    @Volatile var displayRotation = 0
        set(v) { if (field != v) { field = v; cam.post { applyRequest() } } }   // AF region depends on orientation
    /**
     * Against wobble from road vibration: "default" (whatever the phone does), "electronic"
     * (gyro-based; also straightens rolling-shutter "jello"; crops a little), "optical" (lens
     * stabiliser only) or "off" (both off: for a lens stabiliser that rattles on a car mount).
     */
    @Volatile var stabilization = "default"
        set(v) {
            if (field == v) return
            field = v
            // Many phones only switch stabilization when the camera session is set up, so set it
            // up again (a brief pause in the picture).
            cam.post { stabRefused = false; val d = device; if (d != null && session != null) createSession(d, again = true) }
        }
    /** The phone rejected a session with the chosen stabilization: carry on without it. */
    private var stabRefused = false
    /** What the camera is actually doing about stabilization, for the UI ("" = phone default). */
    @Volatile var stabStatus = ""; private set
    @Volatile var mirror = false
    @Volatile var rotate180 = false
    @Volatile var overlayOptions = OverlayOptions(role.label)

    // --- status for UI ---
    @Volatile var cameraOpen = false; private set
    /** When the camera last started streaming (elapsedRealtime), 0 if never. */
    @Volatile var startedAt = 0L; private set
    private var opening = false   // camera thread only: an open is in flight
    private var openAttempt = 0
    @Volatile var fps = 0f; private set
    @Volatile var focusStatus = ""; private set
    val isRecording: Boolean get() = muxer != null
    val muxerError: String? get() = muxer?.lastError
    val currentClip get() = muxer?.currentUri

    fun start() {
        gl.post { setupGl() }
    }

    // ---------------------------------------------------------------- GL

    private fun setupGl() {
        try {
            val e = EglCore()
            egl = e
            val pb = e.createPbuffer(1, 1)
            pbuffer = pb
            e.makeCurrent(pb)
            val d = FrameDrawer()
            drawer = d
            oesTex = d.createOesTexture()
            val t = SurfaceTexture(oesTex)
            t.setDefaultBufferSize(size.width, size.height)
            t.setOnFrameAvailableListener(this, gl)
            st = t
            camSurface = Surface(t)
            cam.post { openCamera() }
        } catch (ex: Exception) {
            Log.e(TAG, "GL setup failed", ex)
            listener.onCameraLost(this, "GL setup failed: ${ex.message}", Loss.OTHER)
        }
    }

    override fun onFrameAvailable(t: SurfaceTexture) {
        val e = egl ?: return
        try {
            e.makeCurrent(encEgl ?: previewEgl ?: pbuffer!!)
            t.updateTexImage()
            t.getTransformMatrix(stMatrix)
            val ts = t.timestamp
            val frameWall = TrueTime.atElapsedNs(toElapsedNs(ts))

            retiring?.let { r ->
                e.makeCurrent(r.egl)
                val ov = overlayFor(r.overlay, r.size).also { r.overlay = it }
                ov.update(overlayOptions, frameWall)
                GLES20.glViewport(0, 0, r.size.width, r.size.height)
                buildMvp(r.angle)
                drawer?.drawCamera(oesTex, stMatrix, mvp)
                ov.draw()
                ov.drawAccel(overlayOptions, Gforce.forwardG)
                e.setPresentationTime(r.egl, toMonotonicNs(ts))
                e.swap(r.egl)
                if (SystemClock.elapsedRealtime() >= r.untilElapsed) finishRetiring()
            }
            encEgl?.let { s ->
                e.makeCurrent(s)
                val ov = overlayFor(encOverlay, recSize).also { encOverlay = it }
                ov.update(overlayOptions, frameWall)
                GLES20.glViewport(0, 0, recSize.width, recSize.height)
                buildMvp(recAngle)
                drawer?.drawCamera(oesTex, stMatrix, mvp)
                ov.draw()
                ov.drawAccel(overlayOptions, Gforce.forwardG)
                e.setPresentationTime(s, toMonotonicNs(ts))
                e.swap(s)
                maybeRequestKeyFrame()
            }
            liveEgl?.let { s ->
                e.makeCurrent(s)
                GLES20.glViewport(0, 0, liveSize.width, liveSize.height)
                buildMvp(liveAngle)
                drawer?.drawCamera(oesTex, stMatrix, mvp)
                e.setPresentationTime(s, toMonotonicNs(ts))
                e.swap(s)
            }
            previewEgl?.let { s ->
                e.makeCurrent(s)
                val angle = currentAngle()
                val frame = outputSize(angle)
                val ov = overlayFor(previewOverlay, frame).also { previewOverlay = it }
                ov.update(overlayOptions, frameWall)
                GLES20.glViewport(0, 0, previewW, previewH)
                GLES20.glClearColor(0f, 0f, 0f, 1f)
                GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
                fitViewport(frame)
                buildMvp(angle)
                drawer?.drawCamera(oesTex, stMatrix, mvp)
                ov.draw()
                ov.drawAccel(overlayOptions, Gforce.forwardG)
                if (!e.swap(s)) {
                    Log.w(TAG, "preview swap failed; dropping preview")
                    e.makeCurrent(pbuffer!!)
                    e.releaseSurface(s)
                    previewEgl = null
                }
            }
            countFps()
        } catch (ex: Exception) {
            Log.e(TAG, "frame failed", ex)
        }
    }

    private fun overlayFor(cur: OverlayTexture?, frame: Size): OverlayTexture {
        val size = overlayOptions.textSize
        if (cur != null && cur.frameW == frame.width && cur.frameH == frame.height && cur.textSize == size) return cur
        cur?.release()
        return OverlayTexture(frame.width, frame.height, drawer!!, size)
    }

    private fun currentAngle() = (displayRotation + if (rotate180) 180 else 0) % 360

    /**
     * Shape of the upright output frame for a given rotation: the SurfaceTexture content is upright
     * for the device's natural orientation (portrait for phones), then we rotate by [angle].
     * So holding the phone upright gives portrait video, sideways gives landscape.
     */
    private fun outputSize(angle: Int): Size {
        val big = maxOf(size.width, size.height); val small = minOf(size.width, size.height)
        val naturalPortrait = sensorOrientation % 180 == 90
        val portrait = naturalPortrait != (angle % 180 == 90)
        return if (portrait) Size(small, big) else Size(big, small)
    }

    /**
     * The SurfaceTexture transform makes the image upright for the device's natural orientation
     * (and mirrors the selfie camera). Rotate by the display rotation to get an upright landscape
     * frame, then optionally flip to undo / add mirroring.
     */
    private fun buildMvp(angle: Int) {
        val flip = isFrontFacing != mirror
        Matrix.setIdentityM(mvp, 0)
        if (flip) Matrix.scaleM(mvp, 0, -1f, 1f, 1f)
        Matrix.rotateM(mvp, 0, angle.toFloat(), 0f, 0f, 1f)
    }

    /** Letterbox the frame into the view; when the view is taller, pin the frame to the top. */
    private fun fitViewport(frame: Size) {
        val frameAspect = frame.width.toFloat() / frame.height
        val viewAspect = previewW.toFloat() / previewH
        if (viewAspect > frameAspect) {
            val w = (previewH * frameAspect).toInt()
            GLES20.glViewport((previewW - w) / 2, 0, w, previewH)
        } else {
            val h = (previewW / frameAspect).toInt()
            GLES20.glViewport(0, previewH - h, previewW, h)   // GL origin is bottom-left
        }
    }

    /** Camera timestamp -> elapsedRealtimeNanos (the clock TrueTime is anchored to). */
    private fun toElapsedNs(ts: Long): Long =
        if (timestampRealtime) ts else ts + (SystemClock.elapsedRealtimeNanos() - System.nanoTime())

    private fun toMonotonicNs(ts: Long): Long =
        if (timestampRealtime) ts - (SystemClock.elapsedRealtimeNanos() - System.nanoTime()) else ts

    private fun maybeRequestKeyFrame() {
        val c = clock ?: return
        val idx = c.indexNow()
        if (idx != lastSyncIdx) {
            lastSyncIdx = idx
            try {
                encoder?.setParameters(Bundle().apply { putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0) })
            } catch (_: Exception) {}
        }
    }

    private fun countFps() {
        frames++
        val now = SystemClock.elapsedRealtime()
        if (fpsWindowStart == 0L) fpsWindowStart = now
        if (now - fpsWindowStart >= 2000) {
            fps = frames * 1000f / (now - fpsWindowStart)
            frames = 0; fpsWindowStart = now
        }
    }

    /** Attach/detach an on-screen preview. Blocks until the GL thread has done it. */
    fun setPreview(surface: Surface?, w: Int, h: Int) {
        runOnGl {
            val e = egl ?: return@runOnGl
            previewEgl?.let { e.makeCurrent(pbuffer!!); e.releaseSurface(it) }
            previewEgl = null
            if (surface != null && surface.isValid) {
                try {
                    previewEgl = e.createWindowSurface(surface)
                    previewW = w; previewH = h
                } catch (ex: Exception) {
                    Log.e(TAG, "preview surface", ex)
                }
            }
        }
    }

    fun setPreviewSize(w: Int, h: Int) {
        gl.post { previewW = w; previewH = h }
    }

    // ---------------------------------------------------------------- live view

    /** Start the live-view encoder (about [longSide] px on the long side). Idempotent. */
    fun startLive(sink: LiveSink, longSide: Int = 1280, bitrate: Int = 6_000_000) {
        runOnGl(5000) {
            val e = egl ?: return@runOnGl
            if (liveCodec != null) { requestLiveKeyFrame(); return@runOnGl }
            val angle = currentAngle()
            val full = outputSize(angle)
            val scale = minOf(1f, longSide.toFloat() / maxOf(full.width, full.height))
            val w = ((full.width * scale).toInt() / 16) * 16
            val h = ((full.height * scale).toInt() / 16) * 16
            var codec: MediaCodec? = null
            try {
                // First with every low-delay option; if this phone's encoder refuses the
                // combination, again with the plain settings that are known to work.
                for (tuned in listOf(true, false)) {
                    val c = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
                    codec = c
                    val fmt = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, w, h).apply {
                        setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                        setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
                        setInteger(MediaFormat.KEY_FRAME_RATE, 30)
                        setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline)
                        setInteger(MediaFormat.KEY_LEVEL, MediaCodecInfo.CodecProfileLevel.AVCLevel4)
                        setInteger(MediaFormat.KEY_PRIORITY, 0)
                        if (Build.VERSION.SDK_INT >= 30) setInteger(MediaFormat.KEY_LATENCY, 1)
                        if (tuned) {
                            // Key frames are several times larger than the rest and each one is a small
                            // traffic jam: only every 2 s (a new viewer or a hiccup asks for one at once).
                            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2)
                            setInteger(MediaFormat.KEY_MAX_B_FRAMES, 0)
                            // Even-sized frames (constant bit rate) travel with even delay.
                            if (c.codecInfo.getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_AVC).encoderCapabilities
                                    .isBitrateModeSupported(MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR))
                                setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR)
                            setInteger("vendor.qti-ext-enc-low-latency.enable", 1)   // Qualcomm; ignored elsewhere
                        } else setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
                    }
                    try {
                        c.setCallback(LiveCallback(sink), enc)
                        c.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                        break
                    } catch (ex: Exception) {
                        if (!tuned) throw ex
                        EventLog.log("Live", "low-delay encoder settings refused (${ex.message}) — using plain ones")
                        try { c.release() } catch (_: Exception) {}
                        codec = null
                    }
                }
                val live = codec!!
                val input = live.createInputSurface()
                sink.onLiveFormat(w, h)   // before start(): the SPS/PPS that follows must not be wiped
                live.start()
                liveEgl = e.createWindowSurface(input)
                liveCodec = live; liveInput = input; liveSize = Size(w, h); liveAngle = angle
                EventLog.log("Live", "${role.label}: live view encoder ${w}x$h")
            } catch (ex: Exception) {
                EventLog.log("Live", "live encoder failed: ${ex.message}")
                try { codec?.release() } catch (_: Exception) {}
            }
        }
    }

    fun requestLiveKeyFrame() {
        try { liveCodec?.setParameters(Bundle().apply { putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0) }) }
        catch (_: Exception) {}
    }

    fun stopLive() {
        runOnGl {
            val e = egl
            liveEgl?.let { s -> e?.let { it.makeCurrent(pbuffer!!); it.releaseSurface(s) } }
            liveEgl = null
            val c = liveCodec
            liveCodec = null
            try { c?.stop() } catch (_: Exception) {}
            try { c?.release() } catch (_: Exception) {}
            liveInput?.release(); liveInput = null
            if (c != null) EventLog.log("Live", "${role.label}: live view encoder stopped")
        }
    }

    private class LiveCallback(val sink: LiveSink) : MediaCodec.Callback() {
        override fun onInputBufferAvailable(codec: MediaCodec, index: Int) {}
        override fun onOutputFormatChanged(codec: MediaCodec, format: MediaFormat) {}
        override fun onError(codec: MediaCodec, e: MediaCodec.CodecException) {
            EventLog.log("Live", "live encoder error: ${e.diagnosticInfo}")
        }
        override fun onOutputBufferAvailable(codec: MediaCodec, index: Int, info: MediaCodec.BufferInfo) {
            try {
                val buf = codec.getOutputBuffer(index)
                if (buf != null && info.size > 0) {
                    buf.position(info.offset); buf.limit(info.offset + info.size)
                    val data = ByteArray(info.size).also { buf.get(it) }
                    sink.onLiveData(data, info.presentationTimeUs,
                        info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0,
                        info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0)
                }
                codec.releaseOutputBuffer(index, false)
            } catch (_: IllegalStateException) {   // stopped meanwhile
            } catch (e: Exception) { Log.w(TAG, "live output", e) }
        }
    }

    // ---------------------------------------------------------------- recording

    /** Returns null on success or an error message. */
    fun startRecording(m: SegmentMuxer, c: SegmentClock, bitrate: Int): String? {
        recBitrate = bitrate
        var err: String? = null
        // Creating an encoder can take a few seconds on slow devices: wait for it properly.
        runOnGl(10_000) { err = startEncoder(m, c) }
        return err
    }

    private var recBitrate = 0

    /** A configured, started encoder that isn't drawn to yet. Can be built on any thread. */
    private class PreparedEncoder(
        val codec: MediaCodec, val input: Surface, val latch: CountDownLatch, val size: Size, val angle: Int,
    )

    private fun prepareEncoder(m: SegmentMuxer, angle: Int): PreparedEncoder {
        val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        try {
            val out = encoderSize(codec, outputSize(angle))
            val fmt = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, out.width, out.height).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                setInteger(MediaFormat.KEY_BIT_RATE, recBitrate)
                setInteger(MediaFormat.KEY_FRAME_RATE, 30)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            }
            val latch = CountDownLatch(1)
            codec.setCallback(EncoderCallback(m, latch), enc)
            codec.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            val input = codec.createInputSurface()
            codec.start()
            return PreparedEncoder(codec, input, latch, out, angle)
        } catch (ex: Exception) {
            runCatching { codec.release() }
            throw ex
        }
    }

    /** GL thread: create an encoder for the current orientation, feeding [m]. */
    private fun startEncoder(m: SegmentMuxer, c: SegmentClock): String? {
        val e = egl ?: return "camera not ready"
        return try {
            val p = prepareEncoder(m, currentAngle())
            encEgl = e.createWindowSurface(p.input)
            encoder = p.codec; encInput = p.input; muxer = m; clock = c; eos = p.latch
            recAngle = p.angle; recSize = p.size
            lastSyncIdx = c.indexNow()
            null
        } catch (ex: Exception) {
            Log.e(TAG, "encoder start failed", ex)
            EventLog.log("Enc", "${role.label} encoder start failed: ${ex.message}")
            "${role.label} encoder failed: ${ex.message}"
        }
    }

    /** GL thread: stop feeding the old encoder and tell it to finish its file. */
    private fun finishRetiring() {
        val r = retiring ?: return
        retiring = null
        egl?.let { it.makeCurrent(pbuffer!!); it.releaseSurface(r.egl) }
        r.overlay?.release()
        try { r.codec?.signalEndOfInputStream() } catch (_: Exception) {}
    }

    /** True if the phone has turned between portrait and landscape since this clip started. */
    fun needsNewClipForRotation(): Boolean = isRecording && outputSize(currentAngle()) != outputSize(recAngle)

    /**
     * Blocking; call off the main thread. Makes the recording follow the phone's orientation.
     * A 180° flip just rotates the picture. Portrait <-> landscape continues in a new clip with no
     * gap: the new encoder is prepared while the old one keeps recording, frames switch over
     * between one camera frame and the next, and the old clip is finished in the background.
     * [makeMuxer] supplies the new clip's muxer. Returns the old (now closed) muxer, or null if no
     * new clip was started.
     */
    fun reorient(newClock: SegmentClock, makeMuxer: () -> SegmentMuxer): SegmentMuxer? {
        val oldMuxer = muxer ?: return null
        var angle = 0
        var needNew = false
        runOnGl {
            angle = currentAngle()
            if (outputSize(angle) == outputSize(recAngle)) recAngle = angle else needNew = true
        }
        if (!needNew) return null
        val newMuxer = makeMuxer()
        val prepared = try { prepareEncoder(newMuxer, angle) } catch (ex: Exception) {
            EventLog.log("Rec", "${role.label} could not switch orientation: ${ex.message} — keeping current clip")
            newMuxer.close()
            return null
        }
        var oldCodec: MediaCodec? = null
        var oldLatch: CountDownLatch? = null
        var oldInput: Surface? = null
        var swapped = false
        runOnGl(10_000) {
            val e = egl ?: return@runOnGl
            val newEgl = try { e.createWindowSurface(prepared.input) } catch (_: Exception) { return@runOnGl }
            finishRetiring()   // a previous overlap still running (very quick double turn)
            oldCodec = encoder; oldLatch = eos; oldInput = encInput
            // Keep feeding the old clip for a second so the two clips overlap: no frame is lost
            // even if the new encoder is slow to produce its first frames.
            encEgl?.let { old ->
                retiring = Retiring(old, oldCodec, recAngle, recSize, encOverlay, SystemClock.elapsedRealtime() + 1000)
            }
            encOverlay = null
            encEgl = newEgl; encoder = prepared.codec; eos = prepared.latch; encInput = prepared.input
            muxer = newMuxer; clock = newClock
            recAngle = prepared.angle; recSize = prepared.size
            lastSyncIdx = newClock.indexNow()
            swapped = true
        }
        if (!swapped) {
            runCatching { prepared.codec.stop() }; runCatching { prepared.codec.release() }; prepared.input.release()
            newMuxer.close()
            return null
        }
        // The old clip ends by itself after the overlap (or right away if no frames arrive).
        if (oldLatch?.await(1500, TimeUnit.MILLISECONDS) != true) {
            runOnGl { finishRetiring() }
            oldLatch?.await(3, TimeUnit.SECONDS)
        }
        try { oldCodec?.stop() } catch (_: Exception) {}
        try { oldCodec?.release() } catch (_: Exception) {}
        oldInput?.release()
        oldMuxer.close()
        EventLog.log("Rec", "${role.label} now ${recSize.width}x${recSize.height} after rotation (clips overlap)")
        return oldMuxer
    }

    /** Largest size <= [want] (same shape) that this encoder accepts, e.g. portrait 4K often isn't. */
    private fun encoderSize(codec: MediaCodec, want: Size): Size {
        val caps = try {
            codec.codecInfo.getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_AVC).videoCapabilities
        } catch (_: Exception) { null } ?: return want
        for (scale in floatArrayOf(1f, 0.75f, 2f / 3f, 0.5f, 0.375f)) {
            val w = (want.width * scale / 16).toInt() * 16
            val h = (want.height * scale / 16).toInt() * 16
            val sz = if (scale == 1f) want else Size(w, h)
            if (caps.isSizeSupported(sz.width, sz.height)) return sz
        }
        return want
    }

    /** Blocking: flushes the encoder and closes the current clip. Call off the main thread. */
    fun stopRecording() {
        var codec: MediaCodec? = null
        var latch: CountDownLatch? = null
        runOnGl {
            finishRetiring()
            encEgl?.let { s -> egl?.let { it.makeCurrent(pbuffer!!); it.releaseSurface(s) } }
            encEgl = null
            encOverlay?.release(); encOverlay = null
            codec = encoder; latch = eos
            encoder = null; eos = null; clock = null
            try { codec?.signalEndOfInputStream() } catch (_: Exception) {}
        }
        latch?.await(3, TimeUnit.SECONDS)
        try { codec?.stop() } catch (_: Exception) {}
        try { codec?.release() } catch (_: Exception) {}
        encInput?.release(); encInput = null
        muxer?.close(); muxer = null
    }

    fun lockClip() { muxer?.lock() }

    /** Remote camera: move onto the main phone's segment timeline without stopping the encoder. */
    fun switchClock(c: SegmentClock) {
        val m = muxer ?: return
        clock = c
        m.switchClock(c)
        try {
            encoder?.setParameters(Bundle().apply { putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0) })
        } catch (_: Exception) {}
    }

    private class EncoderCallback(val m: SegmentMuxer, val eos: CountDownLatch) : MediaCodec.Callback() {
        override fun onInputBufferAvailable(codec: MediaCodec, index: Int) {}
        override fun onOutputFormatChanged(codec: MediaCodec, format: MediaFormat) = m.setVideoFormat(format)
        override fun onError(codec: MediaCodec, e: MediaCodec.CodecException) {
            Log.e(TAG, "encoder error", e); eos.countDown()
            EventLog.log("Enc", "encoder error: ${e.diagnosticInfo}")
        }
        override fun onOutputBufferAvailable(codec: MediaCodec, index: Int, info: MediaCodec.BufferInfo) {
            try {
                val buf = codec.getOutputBuffer(index)
                if (buf != null && info.size > 0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                    buf.position(info.offset); buf.limit(info.offset + info.size)
                    m.writeVideo(buf, info)
                }
                codec.releaseOutputBuffer(index, false)
            } catch (e: Exception) {
                Log.w(TAG, "output buffer", e)
            }
            if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) eos.countDown()
        }
    }

    // ---------------------------------------------------------------- camera

    @SuppressLint("MissingPermission")
    private fun openCamera() {
        if (released || device != null || opening) return
        // Asked to open before the GL side is ready (e.g. by the "camera is free" callback right
        // after start-up): GL setup opens the camera itself once the surface exists. Opening now
        // would leave a camera with nowhere to send frames and nothing to retry it.
        if (camSurface == null) return
        opening = true
        openAttempt++
        val attempt = openAttempt
        // Watchdog: if the camera service never answers, give up on this attempt and let the
        // normal recovery path retry.
        cam.postDelayed({
            if (opening && openAttempt == attempt && device == null && !released) {
                opening = false
                EventLog.log("Cam", "${role.label} open timed out — retrying")
                listener.onCameraLost(this, "open timed out", Loss.BORROWED)
            }
        }, 10_000)
        try {
            manager.openCamera(cameraId, stateCallback, cam)
        } catch (e: CameraAccessException) {
            opening = false
            Log.w(TAG, "${role.label} openCamera: ${e.message}")
            val kind = when (e.reason) {
                CameraAccessException.CAMERA_IN_USE, CameraAccessException.CAMERA_DISABLED,
                CameraAccessException.CAMERA_DISCONNECTED -> Loss.BORROWED
                CameraAccessException.MAX_CAMERAS_IN_USE -> Loss.TOO_MANY
                else -> Loss.OTHER
            }
            listener.onCameraLost(this, e.message ?: "open failed", kind)
        } catch (e: Exception) {
            opening = false
            Log.e(TAG, "openCamera", e)
            listener.onCameraLost(this, e.message ?: "open failed", Loss.OTHER)
        }
    }

    fun reopen() { cam.post { openCamera() } }

    private val stateCallback = object : CameraDevice.StateCallback() {
        override fun onOpened(d: CameraDevice) {
            opening = false
            // Late answer to an attempt the watchdog already gave up on, after a newer open succeeded.
            if (released || (device != null && device != d)) { d.close(); return }
            device = d
            createSession(d)
        }
        // A higher-priority client (e.g. face unlock on the selfie camera) shows up as a disconnect.
        override fun onDisconnected(d: CameraDevice) = lost(d, "disconnected", Loss.BORROWED)
        override fun onError(d: CameraDevice, error: Int) = lost(d, "error $error", when (error) {
            ERROR_CAMERA_IN_USE, ERROR_CAMERA_DISABLED -> Loss.BORROWED
            ERROR_MAX_CAMERAS_IN_USE -> Loss.TOO_MANY
            else -> Loss.OTHER
        })
        private fun lost(d: CameraDevice, why: String, kind: Loss) {
            opening = false
            d.close()
            if (device == d || device == null) { device = null; session = null }
            cameraOpen = false
            if (!released) listener.onCameraLost(this@CameraPipeline, why, kind)
        }
    }

    private fun createSession(d: CameraDevice, again: Boolean = false) {
        val surface = camSurface
        if (surface == null) {   // surface went away meanwhile: close, so a later open can succeed
            d.close(); device = null; session = null
            return
        }
        val exec = Executor { cam.post(it) }
        val cb = object : CameraCaptureSession.StateCallback() {
            override fun onConfigured(s: CameraCaptureSession) {
                if (released) { s.close(); return }
                session = s
                cameraOpen = true
                startedAt = SystemClock.elapsedRealtime()
                retired.forEach { it.close() }; retired.clear()
                relay?.let { r ->
                    try { r.attach(surface) } catch (e: Exception) {
                        EventLog.log("Cam", "${role.label}: can't pass on stabilized frames (${e.message}) — continuing without")
                        stabRefused = true
                        createSession(d, again)
                        return
                    }
                }
                applyRequest()
                if (again) EventLog.log("Cam", "${role.label}: ${stabStatus.ifEmpty { "phone-default stabilization" }}")
                else listener.onCameraStarted(this@CameraPipeline)
            }
            override fun onConfigureFailed(s: CameraCaptureSession) {
                Log.w(TAG, "${role.label} configure failed at $size")
                if (stabilization != "default" && !stabRefused) {
                    // Not the size's fault: this phone won't stabilize in this setup. Go on without.
                    stabRefused = true
                    EventLog.log("Cam", "${role.label}: session with $stabilization stabilization refused — continuing without")
                    createSession(d, again)
                    return
                }
                d.close(); device = null; session = null
                val smaller = fallbackSize()
                if (smaller != null && !isRecording) {
                    gl.post { resizeBuffers(smaller); cam.post { openCamera() } }
                } else listener.onCameraLost(this@CameraPipeline, "configure failed", Loss.TOO_MANY)
            }
        }
        try {
            // Stop the old session's frames and free our GL surface for its next producer.
            try { session?.stopRepeating() } catch (_: Exception) {}
            val oldRelay = relay; relay = null
            oldRelay?.detach()
            retired += listOfNotNull(oldRelay)

            val electronic = stabilization == "electronic" && hasEis && !stabRefused
            val outputs = ArrayList<OutputConfiguration>()
            val r = if (electronic && videoRoute) try { Relay() } catch (e: Exception) {
                EventLog.log("Cam", "${role.label}: can't set up the video stream (${e.message}) — continuing without stabilization")
                stabRefused = true
                null
            } else null
            if (r != null) {
                relay = r
                outputs += OutputConfiguration(r.viewfinder.surface)
                outputs += OutputConfiguration(r.video.surface).apply {
                    // A "video encoder" output gets recorder-style timestamps by default; we need the sensor's.
                    if (Build.VERSION.SDK_INT >= 33) timestampBase = OutputConfiguration.TIMESTAMP_BASE_SENSOR
                }
                requestTargets = listOf(r.video.surface)
            } else {
                outputs += OutputConfiguration(surface)
                requestTargets = listOf(surface)
            }
            val config = SessionConfiguration(SessionConfiguration.SESSION_REGULAR, outputs, exec, cb)
            // Stabilization (and frame rate) are "session keys" on many phones: they only take
            // effect if they are already part of the session's set-up.
            val params = d.createCaptureRequest(CameraDevice.TEMPLATE_RECORD)
            fpsRange?.let { params.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, it) }
            applyStabilization(params, if (stabRefused) "default" else stabilization)
            vendorVideoMode = r != null && setVendorVideoMode(params)
            config.sessionParameters = params.build()
            d.createCaptureSession(config)
        } catch (e: Exception) {
            Log.e(TAG, "createCaptureSession", e)
            listener.onCameraLost(this, e.message ?: "session failed", Loss.OTHER)
        }
    }

    // ---------------------------------------------------------------- stabilization via the video stream

    /**
     * Many phones stabilize only the stream that goes to a video encoder, not the viewfinder stream,
     * and our GL surface looks like a viewfinder to them. So for electronic stabilization the camera
     * records into a "video encoder" buffer queue and each frame is handed on, untouched and without
     * copying, to the GL surface. The camera also wants a viewfinder output to exist; it gets no frames.
     */
    private inner class Relay {
        val video: android.media.ImageReader = android.media.ImageReader.newInstance(size.width, size.height,
            android.graphics.ImageFormat.PRIVATE, 6,
            android.hardware.HardwareBuffer.USAGE_VIDEO_ENCODE or android.hardware.HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE)
        val viewfinder: android.media.ImageReader = android.media.ImageReader.newInstance(size.width, size.height,
            android.graphics.ImageFormat.PRIVATE, 2, android.hardware.HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE)
        private var writer: android.media.ImageWriter? = null
        private var failures = 0

        /**
         * Start handing frames to the GL surface. Only once the session is configured: until then
         * the previous session's camera stream may still be connected to that surface.
         */
        fun attach(target: Surface) {
            writer = android.media.ImageWriter.newInstance(target, 6, android.graphics.ImageFormat.PRIVATE)
        }

        init {
            video.setOnImageAvailableListener({ r ->
                val img = try { r.acquireNextImage() } catch (_: Exception) { null } ?: return@setOnImageAvailableListener
                val w = writer
                if (w == null) { img.close(); return@setOnImageAvailableListener }
                try { w.queueInputImage(img); failures = 0 } catch (e: Exception) {
                    runCatching { img.close() }
                    if (++failures == 30 && relay === this && !released) {
                        EventLog.log("Cam", "${role.label}: stabilized frames can't be passed on (${e.message}) — continuing without")
                        stabRefused = true
                        device?.let { createSession(it, again = true) }
                    }
                }
            }, cam)
            viewfinder.setOnImageAvailableListener({ r -> runCatching { r.acquireLatestImage()?.close() } }, cam)
        }

        /** Let go of the GL surface (frames still arriving are dropped). */
        fun detach() { runCatching { writer?.close() }; writer = null }
        fun close() { detach(); runCatching { video.close() }; runCatching { viewfinder.close() } }
    }

    private var relay: Relay? = null
    /** Relays of earlier sessions: closed once the new session is up (the old one may still write to them). */
    private val retired = ArrayList<Relay>()
    private var requestTargets: List<Surface> = emptyList()
    private var vendorVideoMode = false
    /** This camera only stabilizes its video stream (found out once, then remembered). */
    private var videoRoute = prefs.getBoolean("stab_video_route_$cameraId", false)

    /**
     * Motorola phones keep their stabilizer for their own camera app's video mode; this is the
     * switch that app sets. Returns false on phones that don't have it.
     */
    private fun setVendorVideoMode(b: CaptureRequest.Builder): Boolean = try {
        b.set(CaptureRequest.Key("com.lenovo.moto.clientapp.is_motcamera2", Byte::class.javaObjectType), 1.toByte())
        true
    } catch (_: Exception) { false }

    private fun resizeBuffers(s: Size) {
        size = s
        st?.setDefaultBufferSize(s.width, s.height)
    }

    private fun applyRequest() {
        val d = device ?: return
        val s = session ?: return
        val target = camSurface ?: return
        try {
            val b = d.createCaptureRequest(CameraDevice.TEMPLATE_RECORD)
            requestTargets.ifEmpty { listOf(target) }.forEach { b.addTarget(it) }
            b.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
            fpsRange?.let { b.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, it) }
            if (zoomRatio != null && Build.VERSION.SDK_INT >= 30) b.set(CaptureRequest.CONTROL_ZOOM_RATIO, zoomRatio)
            val f = focus
            focusStatus = when {
                minFocusDiopters == 0f -> "fixed-focus lens"
                f.far && canManualFocus -> {
                    val dio = f.diopters(hyperfocal).coerceIn(0f, minFocusDiopters)
                    b.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
                    b.set(CaptureRequest.LENS_FOCUS_DISTANCE, dio)
                    val where = if (dio == 0f) "∞" else String.format("%.1f m", 1f / dio)
                    "locked $where" + if (!focusCalibrated && dio != 0f) " (approx.)" else ""
                }
                f.far && maxAfRegions > 0 && activeArray != null -> {
                    // No manual lens control: keep AF but only let it look at the road ahead.
                    b.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
                    b.set(CaptureRequest.CONTROL_AF_REGIONS, arrayOf(roadRegion(activeArray)))
                    "AF on road only"
                }
                else -> {
                    b.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
                    "auto"
                }
            }
            val want = if (stabRefused) "default" else stabilization
            stabStatus = if (stabRefused) "stabilization not available in this setup" else applyStabilization(b, want)
            if (want == "electronic" && relay != null && vendorVideoMode) {
                setVendorVideoMode(b); stabStatus = "electronic stabilization (video mode)"
            }
            if (stabRefused) applyStabilization(b, "default")
            try {
                s.setRepeatingRequest(b.build(), if (want == "electronic" && hasEis) stabCheck() else null, cam)
            } catch (e: IllegalArgumentException) {
                // Some phones refuse stabilization in certain modes (e.g. two cameras at once).
                if (want == "default") throw e
                applyStabilization(b, "default")
                stabStatus = "stabilization not available"
                s.setRepeatingRequest(b.build(), null, cam)
            }
        } catch (e: Exception) {
            Log.e(TAG, "applyRequest", e)
        }
    }

    /** Set the stabilization keys for [mode]; returns the status to show. */
    private fun applyStabilization(b: CaptureRequest.Builder, mode: String): String {
        fun eis(on: Boolean) { if (eisModes.isNotEmpty()) b.set(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE,
            if (on) CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_ON else CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_OFF) }
        fun ois(on: Boolean) { if (hasOis) b.set(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE,
            if (on) CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_ON else CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_OFF) }
        return when (mode) {
            // Android advises against running both at once: they fight each other.
            "electronic" -> if (hasEis) { eis(true); ois(false); "electronic stabilization" }
                            else if (hasOis) { ois(true); "optical stabilization (electronic not supported)" }
                            else "no stabilization on this lens"
            "optical" -> if (hasOis) { eis(false); ois(true); "optical stabilization" }
                         else { eis(false); "no optical stabilizer on this lens" }
            "off" -> { eis(false); ois(false); "stabilization off" }
            else -> ""
        }
    }

    /** Checks once, on the first frame, whether the phone really switched electronic stabilization on. */
    private fun stabCheck() = object : CameraCaptureSession.CaptureCallback() {
        private var checked = false
        override fun onCaptureCompleted(s: CameraCaptureSession, r: CaptureRequest, result: android.hardware.camera2.TotalCaptureResult) {
            if (checked) return
            checked = true
            if (stabilization != "electronic" || s !== session) return
            val on = result.get(android.hardware.camera2.CaptureResult.CONTROL_VIDEO_STABILIZATION_MODE) ==
                CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_ON
            when {
                on -> {}
                !videoRoute -> {
                    // Not applied to our viewfinder-style stream: try again through the video stream.
                    EventLog.log("Cam", "${role.label}: stabilization not applied directly — switching to the video stream")
                    videoRoute = true
                    prefs.edit().putBoolean("stab_video_route_$cameraId", true).apply()
                    device?.let { createSession(it, again = true) }
                }
                // Motorola's video mode stabilizes but doesn't report it in the frame data.
                vendorVideoMode -> { stabStatus = "electronic stabilization (video mode)" }
                else -> {
                    stabStatus = "electronic stabilization asked for, but the phone doesn't confirm it"
                    EventLog.log("Cam", "${role.label}: $stabStatus")
                }
            }
        }
    }

    /** Upper-middle part of the scene (the road, not the dash), in sensor coordinates. */
    private fun roadRegion(a: Rect): MeteringRectangle {
        val rel = if (isFrontFacing) (sensorOrientation + displayRotation) % 360
                  else (sensorOrientation - displayRotation + 360) % 360
        val rot = (rel + if (rotate180) 180 else 0) % 360
        val w = a.width(); val h = a.height()
        // Region in upright-scene fractions: x 0.25..0.75, y 0.15..0.5 (top = 0)
        val r = when (rot) {
            0 -> Rect((w * .25).toInt(), (h * .15).toInt(), (w * .75).toInt(), (h * .5).toInt())
            180 -> Rect((w * .25).toInt(), (h * .5).toInt(), (w * .75).toInt(), (h * .85).toInt())
            90 -> Rect((w * .15).toInt(), (h * .25).toInt(), (w * .5).toInt(), (h * .75).toInt())
            else -> Rect((w * .5).toInt(), (h * .25).toInt(), (w * .85).toInt(), (h * .75).toInt())
        }
        r.offset(a.left, a.top)
        return MeteringRectangle(r, MeteringRectangle.METERING_WEIGHT_MAX)
    }

    private fun chooseFps(): Range<Int>? {
        val ranges = chars.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES) ?: return null
        return ranges.firstOrNull { it.lower == 30 && it.upper == 30 }
            ?: ranges.filter { it.upper == 30 }.maxByOrNull { it.lower }
            ?: ranges.filter { it.upper <= 30 }.maxByOrNull { it.upper }
    }

    private fun outputSizes(): List<Size> =
        chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            ?.getOutputSizes(SurfaceTexture::class.java)?.toList() ?: emptyList()

    private fun chooseSize(req: Size): Size {
        val sizes = outputSizes()
        if (sizes.isEmpty()) return req
        sizes.firstOrNull { it == req }?.let { return it }
        val wide = sizes.filter { abs(it.width.toFloat() / it.height - 16f / 9f) < 0.02f }
        return wide.filter { it.width <= req.width }.maxByOrNull { it.width }
            ?: sizes.filter { it.width <= req.width }.maxByOrNull { it.width * it.height }
            ?: sizes.minBy { it.width * it.height }
    }

    private fun fallbackSize(): Size? = when {
        size.width > 1280 -> chooseSize(Size(1280, 720)).takeIf { it.width < size.width }
        size.width > 854 -> chooseSize(Size(854, 480)).takeIf { it.width < size.width }
        else -> null
    }

    // ---------------------------------------------------------------- teardown

    fun release() {
        if (isRecording) stopRecording()
        stopLive()
        released = true
        val camDone = CountDownLatch(1)
        if (cam.post {
                try { session?.close() } catch (_: Exception) {}
                try { device?.close() } catch (_: Exception) {}
                relay?.close(); relay = null
                retired.forEach { it.close() }; retired.clear()
                session = null; device = null; cameraOpen = false
                camDone.countDown()
            }) camDone.await(2, TimeUnit.SECONDS)
        runOnGl {
            val e = egl ?: return@runOnGl
            try {
                e.makeCurrent(pbuffer!!)
                finishRetiring()
                previewEgl?.let { e.releaseSurface(it) }; previewEgl = null
                encEgl?.let { e.releaseSurface(it) }; encEgl = null
                encOverlay?.release(); encOverlay = null
                previewOverlay?.release(); previewOverlay = null
                drawer?.release(); drawer = null
                camSurface?.release(); camSurface = null
                st?.release(); st = null
                pbuffer?.let { e.releaseSurface(it) }; pbuffer = null
                e.release()
            } catch (ex: Exception) { Log.w(TAG, "GL release", ex) }
            egl = null
        }
        glThread.quitSafely(); camThread.quitSafely(); encThread.quitSafely()
    }

    private inline fun runOnGl(timeoutMs: Long = 3000, crossinline block: () -> Unit) {
        if (Thread.currentThread() == glThread) { block(); return }
        val latch = CountDownLatch(1)
        if (gl.post { try { block() } finally { latch.countDown() } }) {
            if (!latch.await(timeoutMs, TimeUnit.MILLISECONDS)) {
                EventLog.log("GL", "${role.label}: GL task still running after $timeoutMs ms")
            }
        }
    }

    companion object { private const val TAG = "CameraPipeline" }
}
