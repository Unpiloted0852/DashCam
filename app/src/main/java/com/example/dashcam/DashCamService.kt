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
import org.json.JSONObject
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

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
        set(v) {
            if (v == field) return
            field = v; mainCam?.displayRotation = v; cabinCam?.displayRotation = v
            if (recording) { ui.removeCallbacks(followRotation); ui.postDelayed(followRotation, 800) }
        }

    /**
     * The phone was turned while recording. Portrait <-> landscape: both cameras finish their clip
     * and continue in a new clip pair in the new shape (same start time, so they still pair up).
     * A 180° flip just rotates the picture.
     */
    private val followRotation = Runnable {
        if (!recording) return@Runnable
        val cams = listOfNotNull(mainCam, cabinCam).filter { it.isRecording }
        worker.execute {
            val current = recClock ?: return@execute
            // A new clip pair shares one fresh start time so front and rear still pair up.
            // A remote camera stays on the main phone's timeline whatever happens.
            val newClock = if (!settings.isRemote && cams.any { it.needsNewClipForRotation() }) {
                EventLog.log("Rec", "phone rotated to $displayRotationDeg° — continuing in new clips")
                SegmentClock(TrueTime.nowMs(), settings.segmentMs).also { recClock = it }
            } else current
            for (cam in cams) {
                val old = cam.reorient(newClock) {
                    SegmentMuxer(this, tagOf(cam), recVolume, recWantAudio && audio != null, newClock,
                        ::onSegmentClosed).also { audio?.addMuxer(it) }   // new clip gets sound from the start
                }
                old?.let { audio?.removeMuxer(it) }
            }
            if (newClock !== current) sendState()
        }
    }

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
        PlaceName.start(this) { settings.showPlace }
        getSystemService(CameraManager::class.java).registerAvailabilityCallback(availability, ui)
        startCameras()
        startLink()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> { if (recording) userStop() else if (!bound) stopSelf() }
            ACTION_LOCK -> lockClip()
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder { bound = true; exitIdle(); return binder }
    override fun onRebind(intent: Intent?) { bound = true; exitIdle() }
    override fun onUnbind(intent: Intent?): Boolean {
        bound = false
        previewMain = null; previewCabin = null
        if (!recording && !stopping) {
            // A remote camera keeps listening for the main phone (it can't be started from the background).
            if (settings.isRemote) enterIdle() else stopSelf()
        }
        return true
    }

    override fun onDestroy() {
        PreferenceManager.getDefaultSharedPreferences(this).unregisterOnSharedPreferenceChangeListener(this)
        getSystemService(CameraManager::class.java).unregisterAvailabilityCallback(availability)
        ui.removeCallbacks(retry)
        stopLink()
        audio?.stop(); audio = null
        stopCameras()
        Telemetry.stop()
        Altimeter.stop()
        TrueTime.stop()
        PlaceName.stop()
        releaseWakeLock()
        worker.shutdown()
        super.onDestroy()
    }

    // ------------------------------------------------------------------ cameras

    private fun startCameras() {
        val mgr = getSystemService(CameraManager::class.java)
        val lens = Lenses.resolve(this, settings.mainLens)
        var backId: String? = lens?.cameraId
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
        var zoom = lens?.zoom
        if (settings.isRemote && settings.remoteCamera == "selfie" && frontId != null) {
            backId = frontId; zoom = null   // the remote records one camera: here the selfie one
        }
        warning = null
        cabinGaveUp = !settings.cabinEnabled || frontId == null
        cabinOpenFailures = 0
        if (backId != null) {
            mainCam = CameraPipeline(this, CameraPipeline.Role.MAIN, backId, settings.mainSize, this, zoom).also {
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
        p.overlayOptions = settings.overlayOptions(if (settings.isRemote) REMOTE_LABEL else p.role.label)
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

    /** Record button / notification. A linked remote camera asks the main phone instead. */
    fun userStart() {
        if (settings.isRemote && link?.connected == true) { link?.send("req", JSONObject().put("do", "start")); return }
        startRecording()
    }

    fun userStop() {
        if (settings.isRemote && link?.connected == true) { link?.send("req", JSONObject().put("do", "stop")); return }
        stopRecording()
    }

    /** [linkClock]: the main phone's segment timeline, so a remote camera's clips pair up with its clips. */
    fun startRecording(linkClock: SegmentClock? = null) {
        if (recording || stopping) return
        if (idle) exitIdle()
        val cams = listOfNotNull(mainCam, cabinCam)
        if (cams.isEmpty()) { warning = "No camera available"; changed(); return }
        val clock = linkClock ?: SegmentClock(TrueTime.nowMs(), settings.segmentMs)
        recVolume = Storage.resolveVolume(this, settings.storageVolume)
        val volumeNote = if (recVolume != settings.storageVolume)
            "Chosen storage isn't available (SD card removed?) — saving to internal storage" else null
        val wantAudio = settings.audioEnabled && hasPerm(Manifest.permission.RECORD_AUDIO)
        recClock = clock
        recWantAudio = wantAudio
        stopReason = null
        val muxers = ArrayList<SegmentMuxer>()
        val errors = ArrayList<String>()
        for (cam in cams) {
            val m = SegmentMuxer(this, tagOf(cam), recVolume, wantAudio, clock, ::onSegmentClosed)
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
        driveEnd?.stop()
        driveEnd = DriveEndDetector(this, settings, ::onDriveEnded) {
            settings.isRemote && link?.connected == true
        }.also { it.start() }
        ui.removeCallbacks(autoStopCheck)
        ui.postDelayed(autoStopCheck, 2000)
        recordingStartElapsed = SystemClock.elapsedRealtime()
        acquireWakeLock()
        goForeground()
        sendState()
        changed()
    }

    /** Start recording on a camera that came up after recording began. */
    private fun joinRecording(cam: CameraPipeline) {
        val clock = recClock ?: return
        if (cam.isRecording) return
        val m = SegmentMuxer(this, tagOf(cam), recVolume, recWantAudio && audio != null, clock, ::onSegmentClosed)
        val err = cam.startRecording(m, clock, settings.bitrateFor(cam.size))
        if (err == null) {
            audio?.addMuxer(m)
            EventLog.log("Rec", "${cam.role.label} joined the recording")
        } else {
            warning = err
            EventLog.log("Rec", "${cam.role.label} could not join: $err")
        }
    }

    @Volatile private var recClock: SegmentClock? = null
    private var recWantAudio = false

    fun stopRecording(reason: String? = null) {
        if (!recording) return
        EventLog.log("Rec", "stop" + (reason?.let { " ($it)" } ?: ""))
        stopReason = reason
        ui.removeCallbacks(autoStopCheck)
        driveEnd?.stop(); driveEnd = null
        recording = false
        stopping = true
        sendState()
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
                if (!bound && settings.isRemote) { pendingRestart = false; enterIdle(); changed(); return@post }
                if (!bound) { stopSelf(); return@post }
                if (pendingRestart) { pendingRestart = false; restartCameras() }
                changed()
            }
        }
    }

    fun lockClip(fromPeer: Boolean = false) {
        if (!recording) return
        // Lock on either phone locks both.
        if (!fromPeer) link?.send("lock")
        EventLog.log("Rec", "lock" + if (fromPeer) " (from the other phone)" else "")
        mainCam?.lockClip(); cabinCam?.lockClip()
        // Also the clip just before (covers a clip that ended because the phone was turned).
        val previous = lastClosed.values.toList(); lastClosed.clear()
        if (previous.isNotEmpty()) Thread { previous.forEach { Storage.lock(this, it) } }.start()
        toast = "Clip locked — saved to Movies/DashCam/Locked"
        changed()
    }

    /** Each camera's most recently finished clip, so Lock also covers the clip before a rotation. */
    private val lastClosed = java.util.concurrent.ConcurrentHashMap<String, Uri>()

    private fun onSegmentClosed(uri: Uri, locked: Boolean, tag: String) {
        if (!locked) lastClosed[tag] = uri else lastClosed.remove(tag)
        worker.execute {
            Storage.enforceLimit(this, recVolume, settings.storageLimitBytes)
        }
    }

    // ------------------------------------------------------------------ auto-stop when parked

    private var recVolume = "external_primary"
    /** Why and how soon recording will stop on its own, or null. */
    val autoStopCountdown: Pair<String, Long>? get() = driveEnd?.countdown
    private var driveEnd: DriveEndDetector? = null

    /** Runs the end-of-drive detector every 2 s while recording. */
    private val autoStopCheck = object : Runnable {
        override fun run() {
            if (!recording) return
            driveEnd?.tick()
            if (recording) ui.postDelayed(this, 2000)
        }
    }

    private fun onDriveEnded(reason: String) {
        Log.i(TAG, "drive ended: $reason")
        toast = "Drive ended ($reason) — recording stopped"
        notifyAutoStopped(reason)
        stopRecording("drive ended: $reason")
    }

    private fun notifyAutoStopped(reason: String) {
        val n = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("DashCam stopped")
            .setContentText("Drive ended ($reason) — recording stopped")
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
        if (key in DashSettings.LINK_KEYS) restartLink()
        if (settings.isPrimary && key in DashSettings.MIRRORED_KEYS) link?.send("settings", settings.mirroredJson())
        if (key in DashSettings.RESTART_KEYS) {
            if (recording || stopping) pendingRestart = true else restartCameras()
        } else {
            listOfNotNull(mainCam, cabinCam).forEach { configure(it) }
        }
        changed()
    }

    // ------------------------------------------------------------------ remote camera link

    private var link: RemoteLink? = null
    @Volatile var linkState = RemoteLink.State(RemoteLink.Phase.OFF); private set
    /** Last status report from the remote camera (main phone only). */
    @Volatile private var peerStatus: JSONObject? = null
    @Volatile private var peerStatusAt = 0L
    private var stopReason: String? = null
    private var linkExec: ScheduledExecutorService? = null
    /** Remote camera with the app closed and not recording: cameras and GPS off, link listening. */
    private var idle = false

    private fun tagOf(cam: CameraPipeline) = if (settings.isRemote) "remote" else cam.role.tag

    private fun startLink() {
        if (settings.linkRole == "normal") return
        val l = RemoteLink(this, settings.isPrimary, settings.linkDevice, linkListener)
        link = l
        l.start()
        linkExec = Executors.newSingleThreadScheduledExecutor().also { ex ->
            ex.scheduleWithFixedDelay({ try { linkTick() } catch (t: Throwable) { EventLog.log("Link", "tick: $t") } },
                1, 1, TimeUnit.SECONDS)
        }
    }

    private fun stopLink() {
        linkExec?.shutdownNow(); linkExec = null
        link?.stop(); link = null
        linkState = RemoteLink.State(RemoteLink.Phase.OFF)
        peerStatus = null
    }

    private fun restartLink() { stopLink(); startLink(); changed(); updateNotification() }

    private var tickCount = 0L
    private var lastTimeSync = 0L

    /** Once a second on the link executor. */
    private fun linkTick() {
        val l = link ?: return
        if (!l.connected) return
        tickCount++
        if (settings.isPrimary) {
            l.send("tel", Telemetry.relayJson())
            if (tickCount % 5 == 0L) sendState()
        } else {
            if (tickCount % 3 == 0L) sendStatus()
            val now = SystemClock.elapsedRealtime()
            if (lastTimeSync == 0L || now - lastTimeSync > TIME_SYNC_EVERY_MS) { lastTimeSync = now; syncTimeFromPeer() }
        }
    }

    private val linkListener = object : RemoteLink.Listener {
        override fun onLinkState(state: RemoteLink.State) {
            val was = linkState.phase
            linkState = state
            if (state.phase == RemoteLink.Phase.CONNECTED && was != RemoteLink.Phase.CONNECTED) {
                if (settings.isPrimary) {
                    link?.send("settings", settings.mirroredJson())
                    sendState()
                } else {
                    lastTimeSync = 0L   // sync the clock right away
                    sendStatus()
                }
            }
            if (state.phase != RemoteLink.Phase.CONNECTED) peerStatus = null
            if (was == RemoteLink.Phase.CONNECTED && state.phase != RemoteLink.Phase.CONNECTED && settings.isRemote && recording) {
                EventLog.log("Link", "main phone gone — still recording; own end-of-drive detection takes over")
            }
            ui.post { changed(); updateNotification() }
        }

        override fun onMessage(type: String, m: JSONObject) {
            if (settings.isPrimary) when (type) {
                "ping" -> link?.send("pong", JSONObject().put("id", m.optInt("id")).put("ms", TrueTime.nowMsExact())
                    .put("unc", TrueTime.uncertaintyMs).put("src", TrueTime.source))
                "status" -> { peerStatus = m; peerStatusAt = SystemClock.elapsedRealtime(); ui.post { changed() } }
                "lock" -> ui.post { lockClip(fromPeer = true) }
                "req" -> ui.post {
                    when (m.optString("do")) {
                        "start" -> { startRecording(); if (!recording) sendState() }
                        "stop" -> stopRecording("stopped on the remote camera")
                    }
                }
            } else when (type) {
                "pong" -> onPong(m)
                "tel" -> Telemetry.onRelay(m)
                "settings" -> {
                    val changedKeys = settings.applyMirrored(m)
                    if (changedKeys.isNotEmpty()) EventLog.log("Link", "settings from main phone: ${changedKeys.joinToString()}")
                }
                "state" -> ui.post { applyPeerState(m) }
                "lock" -> ui.post { lockClip(fromPeer = true) }
            }
        }
    }

    /** Main phone: tell the remote camera whether to record and on which segment timeline. */
    private fun sendState() {
        if (!settings.isPrimary) return
        val l = link ?: return
        val c = recClock
        val o = JSONObject().put("rec", recording && c != null)
        if (recording && c != null) o.put("start", c.startMs).put("len", c.lengthMs)
        stopReason?.let { o.put("why", it) }
        l.send("state", o)
    }

    /** Remote camera: follow the main phone. */
    private fun applyPeerState(m: JSONObject) {
        val rec = m.optBoolean("rec")
        if (rec) {
            val c = SegmentClock(m.getLong("start"), m.getLong("len"))
            val cur = recClock
            when {
                stopping -> {}   // the next state message (every 5 s) starts it again
                !recording -> {
                    EventLog.log("Rec", "main phone is recording — starting")
                    startRecording(c)
                }
                cur == null || cur.startMs != c.startMs || cur.lengthMs != c.lengthMs -> {
                    EventLog.log("Rec", "joining the main phone's clip timeline")
                    recClock = c
                    worker.execute { listOfNotNull(mainCam, cabinCam).forEach { it.switchClock(c) } }
                }
            }
        } else if (recording) {
            val why = m.optString("why").ifEmpty { "stopped on the main phone" }
            toast = "Main phone stopped recording ($why)"
            stopRecording("main phone: $why")
        }
    }

    private fun battery(): Pair<Int, Boolean> {
        val bm = getSystemService(android.os.BatteryManager::class.java)
        return (bm?.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1) to (bm?.isCharging == true)
    }

    private fun sendStatus() {
        val l = link ?: return
        val (pct, chg) = battery()
        val o = JSONObject().put("rec", recording).put("batt", pct).put("chg", chg)
            .put("unc", TrueTime.uncertaintyMs).put("rtt", TrueTime.linkRttMs).put("tsrc", TrueTime.source)
            .put("cam", mainCam?.let { if (it.cameraOpen) "ok" else "down" } ?: if (idle) "idle" else "none")
        (muxerError() ?: warning)?.let { o.put("warn", it) }
        o.put("fix", Telemetry.freshFix() != null)
        l.send("status", o)
    }

    // NTP-style exchange with the main phone: several pings, keep the one with the shortest round trip.
    private class Ping(val sentNs: Long)
    private class PongSample(val trueMs: Double, val atNs: Long, val rttMs: Double, val unc: Long, val src: String)
    private val pings = java.util.concurrent.ConcurrentHashMap<Int, Ping>()
    private val pongs = java.util.Collections.synchronizedList(ArrayList<PongSample>())
    private var pingId = 0

    private fun syncTimeFromPeer() {
        val l = link ?: return
        pings.clear(); pongs.clear()
        repeat(PINGS) {
            val id = ++pingId
            pings[id] = Ping(SystemClock.elapsedRealtimeNanos())
            l.send("ping", JSONObject().put("id", id))
            try { Thread.sleep(80) } catch (_: InterruptedException) { return }
        }
        try { Thread.sleep(800) } catch (_: InterruptedException) { return }
        val got = synchronized(pongs) { pongs.toList() }
        val best = got.minByOrNull { it.rttMs } ?: run { EventLog.log("Time", "no answer to time pings"); return }
        // Offsets implied by each sample; their spread shows how much to trust the best one.
        val offs = got.map { it.trueMs - it.atNs / 1e6 }
        Log.i(TAG, String.format(java.util.Locale.US, "time pings: %d/$PINGS, rtt %s ms, offset spread %.1f ms",
            got.size, got.map { it.rttMs.toInt() }.sorted(), (offs.maxOrNull() ?: 0.0) - (offs.minOrNull() ?: 0.0)))
        if (best.rttMs > 500) { EventLog.log("Time", "link round trip too long (${best.rttMs.toInt()} ms) — skipped"); return }
        TrueTime.onLinkSample(best.trueMs, best.atNs, best.rttMs, best.unc, best.src)
    }

    private fun onPong(m: JSONObject) {
        val now = SystemClock.elapsedRealtimeNanos()
        val p = pings.remove(m.optInt("id")) ?: return
        val rtt = (now - p.sentNs) / 1e6
        // The main phone read its clock about half way through the round trip.
        pongs += PongSample(m.optDouble("ms") + rtt / 2, now, rtt, m.optLong("unc", -1), m.optString("src"))
    }

    /** One- or two-line link status for the main screen, or null when the feature is off. */
    fun linkStatusText(): String? {
        if (settings.linkRole == "normal") return null
        val st = linkState
        val who = if (settings.isPrimary) "Remote camera" else "Main phone"
        val name = st.peerName.ifEmpty { "the other phone" }
        return when (st.phase) {
            RemoteLink.Phase.OFF -> "$who: off"
            RemoteLink.Phase.ERROR -> "$who: ${st.detail}"
            RemoteLink.Phase.MISMATCH -> "⚠ ${st.detail}"
            RemoteLink.Phase.WAITING -> "$who: waiting for $name…"
            RemoteLink.Phase.CONNECTING -> "$who: looking for $name…"
            RemoteLink.Phase.CONNECTED -> if (settings.isPrimary) {
                val p = peerStatus?.takeIf { SystemClock.elapsedRealtime() - peerStatusAt < 10_000 }
                if (p == null) "$who: connected to $name" else {
                    val parts = ArrayList<String>()
                    parts += if (p.optBoolean("rec")) "● REC" else "standby"
                    val b = p.optInt("batt", -1)
                    if (b >= 0) parts += "$b%" + if (p.optBoolean("chg")) "⚡" else ""
                    val unc = p.optLong("unc", -1)
                    if (p.optString("tsrc").startsWith("main phone") && unc >= 0) parts += "time ±$unc ms"
                    if (p.optString("cam") == "down") parts += "camera busy"
                    "$who ($name): ${parts.joinToString(" · ")}" + (p.optString("warn").takeIf { it.isNotEmpty() }?.let { "\n⚠ $it" } ?: "")
                }
            } else {
                val t = if (TrueTime.linkFresh()) " · time ±${TrueTime.uncertaintyMs} ms" else " · syncing time…"
                "$who: connected to $name$t"
            }
        }
    }

    private fun enterIdle() {
        if (idle || recording || stopping) return
        idle = true
        EventLog.log("Link", "remote camera idle: cameras and GPS off, waiting for the main phone")
        stopCameras()
        Telemetry.stop()
        updateNotification()
    }

    private fun exitIdle() {
        if (!idle) return
        idle = false
        Telemetry.start(this)
        startCameras()
        updateNotification()
    }

    private fun updateNotification() {
        try { getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotification()) } catch (_: Exception) {}
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
            .setContentTitle(when {
                settings.isRemote && recording -> "DashCam remote camera recording"
                settings.isRemote -> "DashCam remote camera"
                recording -> "DashCam recording"
                else -> "DashCam ready"
            })
            .setContentText(linkStatusText()?.lineSequence()?.first()
                ?: if (recording) "Recording to Movies/DashCam" else "Cameras on, not recording")
            .setOngoing(true)
            .setContentIntent(open)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
        if (recording) {
            b.addAction(0, "Lock clip", servicePI(ACTION_LOCK, 1))
            b.addAction(0, "Stop", servicePI(ACTION_STOP, 2))
        } else if (settings.isRemote && idle) {
            b.addAction(0, "Turn off", servicePI(ACTION_STOP, 2))
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
        const val REMOTE_LABEL = "REMOTE"
        private const val TIME_SYNC_EVERY_MS = 60_000L
        private const val PINGS = 12
    }
}
