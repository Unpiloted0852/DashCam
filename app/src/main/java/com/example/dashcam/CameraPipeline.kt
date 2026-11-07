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
    private var camSurface: Surface? = null
    private val stMatrix = FloatArray(16)
    private val mvp = FloatArray(16)
    private var previewEgl: EGLSurface? = null
    private var previewW = 0
    private var previewH = 0
    private var encEgl: EGLSurface? = null
    private var recAngle = 0
    private var recSize = Size(0, 0)
    private var lastSyncIdx = -1L
    private var frames = 0
    private var fpsWindowStart = 0L

    // --- recording state ---
    private var encoder: MediaCodec? = null
    private var encInput: Surface? = null
    @Volatile private var muxer: SegmentMuxer? = null
    @Volatile private var clock: SegmentClock? = null
    private var eos: CountDownLatch? = null

    // --- camera thread state ---
    private var device: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    @Volatile private var released = false

    // --- settings (any thread) ---
    @Volatile var focus = FocusConfig(false, "inf")
        set(v) { field = v; cam.post { applyRequest() } }
    @Volatile var displayRotation = 0
        set(v) { field = v; cam.post { applyRequest() } }   // AF region depends on orientation
    @Volatile var mirror = false
    @Volatile var rotate180 = false
    @Volatile var overlayOptions = OverlayOptions(role.label, "", true, false)

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

            encEgl?.let { s ->
                e.makeCurrent(s)
                val ov = overlayFor(encOverlay, recSize).also { encOverlay = it }
                ov.update(overlayOptions, frameWall)
                GLES20.glViewport(0, 0, recSize.width, recSize.height)
                buildMvp(recAngle)
                drawer?.drawCamera(oesTex, stMatrix, mvp)
                ov.draw()
                e.setPresentationTime(s, toMonotonicNs(ts))
                e.swap(s)
                maybeRequestKeyFrame()
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
        if (cur != null && cur.frameW == frame.width && cur.frameH == frame.height) return cur
        cur?.release()
        return OverlayTexture(frame.width, frame.height, drawer!!)
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

    /** GL thread: create an encoder for the current orientation, feeding [m]. */
    private fun startEncoder(m: SegmentMuxer, c: SegmentClock): String? {
        val e = egl ?: return "camera not ready"
        val bitrate = recBitrate
        var err: String? = null
        run {
            try {
                val angle = currentAngle()
                val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
                val out = encoderSize(codec, outputSize(angle))
                val fmt = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, out.width, out.height).apply {
                    setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                    setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
                    setInteger(MediaFormat.KEY_FRAME_RATE, 30)
                    setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
                }
                val latch = CountDownLatch(1)
                codec.setCallback(EncoderCallback(m, latch), enc)
                codec.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                val input = codec.createInputSurface()
                codec.start()
                encEgl = e.createWindowSurface(input)
                encoder = codec; encInput = input; muxer = m; clock = c; eos = latch
                recAngle = angle; recSize = out
                lastSyncIdx = c.indexNow()
            } catch (ex: Exception) {
                Log.e(TAG, "encoder start failed", ex)
                EventLog.log("Enc", "${role.label} encoder start failed: ${ex.message}")
                err = "${role.label} encoder failed: ${ex.message}"
            }
        }
        return err
    }

    /** True if the phone has turned between portrait and landscape since this clip started. */
    fun needsNewClipForRotation(): Boolean = isRecording && outputSize(currentAngle()) != outputSize(recAngle)

    /**
     * Blocking; call off the main thread. Makes the recording follow the phone's orientation:
     * a 180° flip just rotates the picture, while portrait <-> landscape finishes the current clip
     * and continues in a new clip with the new shape. Returns true if a new clip was started.
     */
    fun reorient(): Boolean {
        val m = muxer ?: return false
        var restart = false
        var oldCodec: MediaCodec? = null
        var oldLatch: CountDownLatch? = null
        var oldInput: Surface? = null
        runOnGl {
            val angle = currentAngle()
            if (outputSize(angle) == outputSize(recAngle)) { recAngle = angle; return@runOnGl }
            restart = true
            encEgl?.let { s -> egl?.let { it.makeCurrent(pbuffer!!); it.releaseSurface(s) } }
            encEgl = null
            encOverlay?.release(); encOverlay = null
            oldCodec = encoder; oldLatch = eos; oldInput = encInput
            encoder = null; eos = null; encInput = null
            try { oldCodec?.signalEndOfInputStream() } catch (_: Exception) {}
        }
        if (!restart) return false
        oldLatch?.await(3, TimeUnit.SECONDS)
        try { oldCodec?.stop() } catch (_: Exception) {}
        try { oldCodec?.release() } catch (_: Exception) {}
        oldInput?.release()
        m.closeSegment()
        val c = clock ?: return false
        var err: String? = null
        runOnGl(10_000) { err = startEncoder(m, c) }
        EventLog.log("Rec", "${role.label} now ${recSize.width}x${recSize.height} after rotation" +
            (err?.let { " — FAILED: $it" } ?: ""))
        return err == null
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

    private fun createSession(d: CameraDevice) {
        val surface = camSurface ?: return
        val exec = Executor { cam.post(it) }
        val cb = object : CameraCaptureSession.StateCallback() {
            override fun onConfigured(s: CameraCaptureSession) {
                if (released) { s.close(); return }
                session = s
                cameraOpen = true
                startedAt = SystemClock.elapsedRealtime()
                applyRequest()
                listener.onCameraStarted(this@CameraPipeline)
            }
            override fun onConfigureFailed(s: CameraCaptureSession) {
                Log.w(TAG, "${role.label} configure failed at $size")
                d.close(); device = null; session = null
                val smaller = fallbackSize()
                if (smaller != null && !isRecording) {
                    gl.post { resizeBuffers(smaller); cam.post { openCamera() } }
                } else listener.onCameraLost(this@CameraPipeline, "configure failed", Loss.TOO_MANY)
            }
        }
        try {
            d.createCaptureSession(SessionConfiguration(SessionConfiguration.SESSION_REGULAR,
                listOf(OutputConfiguration(surface)), exec, cb))
        } catch (e: Exception) {
            Log.e(TAG, "createCaptureSession", e)
            listener.onCameraLost(this, e.message ?: "session failed", Loss.OTHER)
        }
    }

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
            b.addTarget(target)
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
            s.setRepeatingRequest(b.build(), null, cam)
        } catch (e: Exception) {
            Log.e(TAG, "applyRequest", e)
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
        released = true
        val camDone = CountDownLatch(1)
        if (cam.post {
                try { session?.close() } catch (_: Exception) {}
                try { device?.close() } catch (_: Exception) {}
                session = null; device = null; cameraOpen = false
                camDone.countDown()
            }) camDone.await(2, TimeUnit.SECONDS)
        runOnGl {
            val e = egl ?: return@runOnGl
            try {
                e.makeCurrent(pbuffer!!)
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
