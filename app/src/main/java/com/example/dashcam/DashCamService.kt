package com.example.dashcam

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.net.Uri
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import android.view.Surface
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.preference.PreferenceManager
import java.util.concurrent.Executors

/**
 * Owns the cameras, so recording keeps going with the screen off or the app in the background.
 * Runs as a foreground service while the UI is open (preview) or while recording.
 */
class DashCamService : Service(), CameraPipeline.Listener,
    SharedPreferences.OnSharedPreferenceChangeListener {

    inner class LocalBinder : Binder() { val service: DashCamService get() = this@DashCamService }

    private val binder = LocalBinder()
    private val ui = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private lateinit var settings: DashSettings
    private var wakeLock: PowerManager.WakeLock? = null

    var mainCam: CameraPipeline? = null; private set
    var cabinCam: CameraPipeline? = null; private set
    private var cabinGaveUp = false
    private var concurrentSupported: Boolean? = null

    var recording = false; private set
    var stopping = false; private set
    var recordingStartElapsed = 0L; private set
    private var audio: AudioCapture? = null
    var audioActive = false; private set
    private var pendingRestart = false
    private var bound = false

    /** Short human-readable problem to show on screen, or null. */
    var warning: String? = null; private set
    var toast: String? = null

    private val listeners = mutableSetOf<() -> Unit>()
    fun addListener(l: () -> Unit) { listeners += l }
    fun removeListener(l: () -> Unit) { listeners -= l }
    private fun changed() = ui.post { listeners.toList().forEach { it() } }

    private class Preview(val surface: Surface, val w: Int, val h: Int)
    private var previewMain: Preview? = null
    private var previewCabin: Preview? = null

    var displayRotationDeg = 0
        set(v) { field = v; mainCam?.displayRotation = v; cabinCam?.displayRotation = v }

    // ------------------------------------------------------------------ lifecycle

    override fun onCreate() {
        super.onCreate()
        settings = DashSettings(this)
        EventLog.init(this)
        createChannel()
        goForeground()
        PreferenceManager.getDefaultSharedPreferences(this).registerOnSharedPreferenceChangeListener(this)
        Telemetry.start(this)
        Altimeter.start(this)
        TrueTime.start()
        getSystemService(CameraManager::class.java).registerAvailabilityCallback(availability, ui)
        startCameras()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> { if (recording) stopRecording() else if (!bound) stopSelf() }
            ACTION_LOCK -> lockClip()
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder { bound = true; return binder }
    override fun onRebind(intent: Intent?) { bound = true }
    override fun onUnbind(intent: Intent?): Boolean {
        bound = false
        previewMain = null; previewCabin = null
        if (!recording && !stopping) stopSelf()
        return true
    }

    override fun onDestroy() {
        PreferenceManager.getDefaultSharedPreferences(this).unregisterOnSharedPreferenceChangeListener(this)
        getSystemService(CameraManager::class.java).unregisterAvailabilityCallback(availability)
        ui.removeCallbacks(retry)
        audio?.stop(); audio = null
        stopCameras()
        Telemetry.stop()
        Altimeter.stop()
        TrueTime.stop()
        releaseWakeLock()
        worker.shutdown()
        super.onDestroy()
    }

    // ------------------------------------------------------------------ cameras

    private fun startCameras() {
        val mgr = getSystemService(CameraManager::class.java)
        val lens = Lenses.resolve(this, settings.mainLens)
        val backId: String? = lens?.cameraId
        var frontId: String? = null
        try {
            frontId = mgr.cameraIdList.firstOrNull {
                mgr.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_FRONT
            }
            if (Build.VERSION.SDK_INT >= 30) {
                val combos = mgr.concurrentCameraIds
                if (backId != null && frontId != null) {
                    concurrentSupported = combos.any { it.contains(backId) && it.contains(frontId) }
                }
                EventLog.log("Cam", "lens ${lens?.label} [${Lenses.lastProbe}]; concurrent pairs: ${combos.joinToString { it.sorted().toString() }}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "camera enumeration", e)
        }
        warning = null
        cabinGaveUp = !settings.cabinEnabled || frontId == null
        cabinOpenFailures = 0
        if (backId != null) {
            mainCam = CameraPipeline(this, CameraPipeline.Role.MAIN, backId, settings.mainSize, this, lens?.zoom).also {
                configure(it); it.start(); previewMain?.let { p -> it.setPreview(p.surface, p.w, p.h) }
            }
        } else {
            warning = "No rear-facing camera found"
            startCabin()
        }
        changed()
    }

    /** Started after the main camera is up so the road camera always wins if only one can run. */
    private fun startCabin() {
        if (cabinGaveUp || cabinCam != null) return
        val mgr = getSystemService(CameraManager::class.java)
        val frontId = mgr.cameraIdList.firstOrNull {
            mgr.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_FRONT
        } ?: return
        cabinCam = CameraPipeline(this, CameraPipeline.Role.CABIN, frontId, settings.cabinSize, this).also {
            configure(it); it.start(); previewCabin?.let { p -> it.setPreview(p.surface, p.w, p.h) }
            EventLog.log("Cam", "rear camera $frontId starting (concurrent pair listed: $concurrentSupported)")
            // Recording may already be running (auto-start, or the rear camera came back later):
            // the rear camera joins it instead of being left out of the whole drive.
            if (recording) joinRecording(it)
        }
        if (concurrentSupported == false) {
            warning = "This phone doesn't list front+back as a supported pair; trying anyway"
        }
        changed()
    }

    private fun stopCameras() {
        mainCam?.release(); mainCam = null
        cabinCam?.release(); cabinCam = null
    }

    private fun restartCameras() {
        stopCameras()
        startCameras()
    }

    private fun configure(p: CameraPipeline) {
        val main = p.role == CameraPipeline.Role.MAIN
        p.focus = if (main) settings.mainFocus else settings.cabinFocus
        p.mirror = !main && settings.cabinMirror
        p.rotate180 = if (main) settings.mainRotate180 else settings.cabinRotate180
        p.displayRotation = displayRotationDeg
        p.overlayOptions = OverlayOptions(p.role.label, settings.overlayLabel, settings.showCoords, settings.useUtc)
    }

    override fun onCameraStarted(p: CameraPipeline) {
        ui.post {
            EventLog.log("Cam", "${p.role.label} streaming ${p.size.width}x${p.size.height}, focus ${p.focusStatus}")
            if (p === cabinCam) cabinOpenFailures = 0
            if (warning?.contains("will resume automatically") == true &&
                listOfNotNull(mainCam, cabinCam).all { it.cameraOpen }) warning = null
            if (p === mainCam) startCabin()
            if (p === cabinCam && warning?.startsWith("This phone doesn't list") == true) warning = null
            changed()
        }
    }

    override fun onCameraLost(p: CameraPipeline, reason: String, kind: CameraPipeline.Loss) {
        ui.post {
            Log.w(TAG, "${p.role.label} camera lost: $reason ($kind)")
            EventLog.log("Cam", "${p.role.label} lost: $reason ($kind), recording=${p.isRecording}")
            val now = SystemClock.elapsedRealtime()
            val c = cabinCam
            when {
                p === cabinCam && (kind == CameraPipeline.Loss.TOO_MANY ||
                        (p.startedAt == 0L && ++cabinOpenFailures >= 3)) -> {
                    // The phone can't run both cameras (or the selfie camera never came up at all).
                    giveUpOnCabin("Rear (selfie) camera unavailable while the front camera runs — this phone " +
                        "probably can't use both cameras at once. Recording front only.")
                }
                p === mainCam && c != null && c.startedAt != 0L && now - c.startedAt < 5000 -> {
                    // Starting the selfie camera kicked the road camera off: concurrency isn't supported.
                    giveUpOnCabin("This phone can't record both cameras at once. Recording front only.")
                    worker.execute { ui.post { mainCam?.reopen() } }
                }
                else -> {
                    // Borrowed (face unlock, another camera app…) or a transient error: keep the clip
                    // going and grab the camera back as soon as it is free.
                    warning = "${p.role.label.lowercase().replaceFirstChar { it.uppercase() }} camera is in use by " +
                        "something else (face unlock?) — it will resume automatically"
                    scheduleRetry()
                }
            }
            changed()
        }
    }

    private var cabinOpenFailures = 0

    private fun giveUpOnCabin(msg: String) {
        EventLog.log("Cam", "giving up on rear camera: $msg")
        cabinGaveUp = true
        warning = msg
        val c = cabinCam; cabinCam = null
        if (c != null) worker.execute { c.release() }
    }

    /** Reopen any camera that isn't running. Also driven by the camera-availability callback. */
    private val retry = object : Runnable {
        override fun run() {
            val down = listOfNotNull(mainCam, cabinCam).filter { !it.cameraOpen }
            down.forEach { it.reopen() }
            if (down.isNotEmpty()) ui.postDelayed(this, 5000)
        }
    }

    private fun scheduleRetry() {
        ui.removeCallbacks(retry)
        ui.postDelayed(retry, 1500)
    }

    private val availability = object : CameraManager.AvailabilityCallback() {
        override fun onCameraAvailable(cameraId: String) {
            listOfNotNull(mainCam, cabinCam).firstOrNull { it.cameraId == cameraId && !it.cameraOpen }?.let {
                Log.i(TAG, "${it.role.label} camera free again — reopening")
                EventLog.log("Cam", "${it.role.label} free again — reopening")
                it.reopen()
            }
        }
    }

    fun setPreview(role: CameraPipeline.Role, surface: Surface?, w: Int, h: Int) {
        val p = surface?.let { Preview(it, w, h) }
        if (role == CameraPipeline.Role.MAIN) { previewMain = p; mainCam?.setPreview(surface, w, h) }
        else { previewCabin = p; cabinCam?.setPreview(surface, w, h) }
    }

    fun setPreviewSize(role: CameraPipeline.Role, w: Int, h: Int) {
        if (role == CameraPipeline.Role.MAIN) {
            previewMain = previewMain?.let { Preview(it.surface, w, h) }; mainCam?.setPreviewSize(w, h)
        } else {
            previewCabin = previewCabin?.let { Preview(it.surface, w, h) }; cabinCam?.setPreviewSize(w, h)
        }
    }

    // ------------------------------------------------------------------ recording

    fun startRecording() {
        if (recording || stopping) return
        val cams = listOfNotNull(mainCam, cabinCam)
        if (cams.isEmpty()) { warning = "No camera available"; changed(); return }
        val clock = SegmentClock(TrueTime.nowMs(), settings.segmentMs)
        recVolume = Storage.resolveVolume(this, settings.storageVolume)
        val volumeNote = if (recVolume != settings.storageVolume)
            "Chosen storage isn't available (SD card removed?) — saving to internal storage" else null
        val wantAudio = settings.audioEnabled && hasPerm(Manifest.permission.RECORD_AUDIO)
        recClock = clock
        recWantAudio = wantAudio
        val muxers = ArrayList<SegmentMuxer>()
        val errors = ArrayList<String>()
        for (cam in cams) {
            val m = SegmentMuxer(this, cam.role.tag, recVolume, wantAudio, clock, ::onSegmentClosed)
            val err = cam.startRecording(m, clock, settings.bitrateFor(cam.size))
            if (err == null) muxers += m else errors += err
        }
        EventLog.log("Rec", "start: ${cams.joinToString { it.role.label + if (it.isRecording) " ok" else " FAILED" }}" +
            (if (errors.isEmpty()) "" else " — ${errors.joinToString("; ")}"))
        if (muxers.isEmpty()) {
            warning = errors.joinToString("\n"); changed(); return
        }
        audioActive = false
        if (wantAudio) {
            val a = AudioCapture(muxers)
            if (a.start()) { audio = a; audioActive = true }
            else {
                muxers.forEach { it.expectAudio = false }
                errors += "Microphone unavailable — recording without sound"
            }
        }
        volumeNote?.let { errors += it }
        if (errors.isNotEmpty()) warning = errors.joinToString("\n")
        recording = true
        stillSince = 0L; hasMoved = false; autoStopRemainingMs = null
        ui.removeCallbacks(autoStopCheck)
        ui.postDelayed(autoStopCheck, 2000)
        recordingStartElapsed = SystemClock.elapsedRealtime()
        acquireWakeLock()
        goForeground()
        changed()
    }

    /** Start recording on a camera that came up after recording began. */
    private fun joinRecording(cam: CameraPipeline) {
        val clock = recClock ?: return
        if (cam.isRecording) return
        val m = SegmentMuxer(this, cam.role.tag, recVolume, recWantAudio && audio != null, clock, ::onSegmentClosed)
        val err = cam.startRecording(m, clock, settings.bitrateFor(cam.size))
        if (err == null) {
            audio?.addMuxer(m)
            EventLog.log("Rec", "${cam.role.label} joined the recording")
        } else {
            warning = err
            EventLog.log("Rec", "${cam.role.label} could not join: $err")
        }
    }

    private var recClock: SegmentClock? = null
    private var recWantAudio = false

    fun stopRecording() {
        if (!recording) return
        EventLog.log("Rec", "stop")
        ui.removeCallbacks(autoStopCheck)
        autoStopRemainingMs = null
        recording = false
        stopping = true
        changed()
        val a = audio; audio = null; audioActive = false
        val cams = listOfNotNull(mainCam, cabinCam)
        worker.execute {
            a?.stop()
            cams.forEach { it.stopRecording() }
            ui.post {
                stopping = false
                releaseWakeLock()
                goForeground()
                if (!bound) { stopSelf(); return@post }
                if (pendingRestart) { pendingRestart = false; restartCameras() }
                changed()
            }
        }
    }

    fun lockClip() {
        if (!recording) return
        mainCam?.lockClip(); cabinCam?.lockClip()
        toast = "Clip locked — saved to Movies/DashCam/Locked"
        changed()
    }

    private fun onSegmentClosed(uri: Uri, locked: Boolean) {
        worker.execute {
            Storage.enforceLimit(this, recVolume, settings.storageLimitBytes)
        }
    }

    // ------------------------------------------------------------------ auto-stop when parked

    private var recVolume = "external_primary"
    private var stillSince = 0L
    private var hasMoved = false
    /** Time left before auto-stop while parked, or null when not counting down. */
    var autoStopRemainingMs: Long? = null; private set

    /**
     * Counts time spent at ~0 mph and stops recording once it passes the configured limit. It only
     * arms after the car has actually moved, so sitting in the driveway setting up navigation
     * doesn't end the recording. If GPS drops out (e.g. parked in a garage) the last known speed is used.
     */
    private val autoStopCheck = object : Runnable {
        override fun run() {
            if (!recording) return
            val limit = settings.autoStopMs
            val speed: Float? = Telemetry.speedMps()
                ?: if (Telemetry.freshFix() == null) Telemetry.location?.takeIf { it.hasSpeed() }?.speed else null
            if (speed != null && speed > 2.2f) hasMoved = true   // ~5 mph
            val still = when {
                speed == null -> null                              // unknown: leave the timer alone
                else -> speed < DashSettings.STOP_SPEED_MPS
            }
            val now = SystemClock.elapsedRealtime()
            if (limit <= 0 || !hasMoved || still == false) {
                stillSince = 0L; autoStopRemainingMs = null
            } else if (still == true || stillSince != 0L) {
                if (stillSince == 0L) stillSince = now
                val left = limit - (now - stillSince)
                autoStopRemainingMs = left.coerceAtLeast(0)
                if (left <= 0) {
                    Log.i(TAG, "auto-stop after ${limit / 60000} min parked")
                    EventLog.log("Rec", "auto-stop after ${limit / 60000} min parked")
                    toast = "Parked for ${limit / 60000} min — recording stopped"
                    notifyAutoStopped(limit)
                    stopRecording()
                    return
                }
            }
            ui.postDelayed(this, 2000)
        }
    }

    private fun notifyAutoStopped(limit: Long) {
        val n = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("DashCam stopped")
            .setContentText("Stopped automatically after ${limit / 60000} min at 0 mph")
            .setAutoCancel(true)
            .setContentIntent(PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE))
            .build()
        try { getSystemService(NotificationManager::class.java).notify(NOTIF_AUTOSTOP, n) } catch (_: Exception) {}
    }

    /** First problem reported by a muxer, e.g. storage full. */
    fun muxerError(): String? = mainCam?.muxerError ?: cabinCam?.muxerError

    // ------------------------------------------------------------------ settings

    override fun onSharedPreferenceChanged(prefs: SharedPreferences?, key: String?) {
        key ?: return
        if (key in DashSettings.RESTART_KEYS) {
            if (recording || stopping) pendingRestart = true else restartCameras()
        } else {
            listOfNotNull(mainCam, cabinCam).forEach { configure(it) }
        }
        changed()
    }

    // ------------------------------------------------------------------ plumbing

    private fun hasPerm(p: String) = checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED

    private fun goForeground() {
        var types = ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
        if (hasPerm(Manifest.permission.ACCESS_FINE_LOCATION)) types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
        if (hasPerm(Manifest.permission.RECORD_AUDIO) && settings.audioEnabled) {
            types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        }
        try {
            ServiceCompat.startForeground(this, NOTIF_ID, buildNotification(), types)
        } catch (e: Exception) {
            Log.e(TAG, "startForeground", e)
        }
    }

    private fun buildNotification(): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val b = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(if (recording) "DashCam recording" else "DashCam ready")
            .setContentText(if (recording) "Recording to Movies/DashCam" else "Cameras on, not recording")
            .setOngoing(true)
            .setContentIntent(open)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
        if (recording) {
            b.addAction(0, "Lock clip", servicePI(ACTION_LOCK, 1))
            b.addAction(0, "Stop", servicePI(ACTION_STOP, 2))
        }
        return b.build()
    }

    private fun servicePI(action: String, code: Int) = PendingIntent.getService(this, code,
        Intent(this, DashCamService::class.java).setAction(action),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    private fun createChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Recording", NotificationManager.IMPORTANCE_LOW))
    }

    private fun acquireWakeLock() {
        if (wakeLock == null) {
            wakeLock = getSystemService(PowerManager::class.java)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "DashCam:recording").apply { acquire() }
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
    }

    companion object {
        private const val TAG = "DashCamService"
        private const val CHANNEL = "recording"
        private const val NOTIF_ID = 1
        private const val NOTIF_AUTOSTOP = 2
        const val ACTION_STOP = "com.example.dashcam.STOP"
        const val ACTION_LOCK = "com.example.dashcam.LOCK"
    }
}
