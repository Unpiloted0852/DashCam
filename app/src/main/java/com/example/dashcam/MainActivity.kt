package com.example.dashcam

import android.Manifest
import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.SurfaceTexture
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.preference.PreferenceManager
import kotlin.math.roundToInt

class MainActivity : AppCompatActivity() {

    private lateinit var mainPreview: TextureView
    private lateinit var cabinPreview: TextureView
    private lateinit var recStatus: TextView
    private lateinit var gpsStatus: TextView
    private lateinit var linkStatus: TextView
    private lateinit var focusStatus: TextView
    private lateinit var warning: TextView
    private lateinit var speedMph: TextView
    private lateinit var speedKph: TextView
    private lateinit var recBtn: Button
    private lateinit var lockBtn: Button
    private lateinit var focusBtn: Button
    private lateinit var settings: DashSettings

    private var service: DashCamService? = null
    private var boundRequested = false
    private var autoRecordPending = false
    private var inOwnScreen = false   // Settings/Clips are part of the app, not a fresh "open"
    private val ticker = Handler(Looper.getMainLooper())
    private val stateListener: () -> Unit = { render() }

    private val permLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { res ->
        if (hasPerm(Manifest.permission.CAMERA)) startAndBind()
        else warning.apply { visibility = View.VISIBLE; text = "Camera permission is required." }
        if (res[Manifest.permission.ACCESS_FINE_LOCATION] == false) {
            Toast.makeText(this, "Without location the stamp has no GPS or speed", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        PreferenceManager.setDefaultValues(this, R.xml.preferences, false)
        settings = DashSettings(this)
        setContentView(R.layout.activity_main)
        // Reopened by itself after an update (a remote camera nobody is touching): come up over the
        // lock screen and wake the display, or the cameras wouldn't be allowed to start.
        if (intent?.getBooleanExtra(Updater.EXTRA_AFTER_UPDATE, false) == true && Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(true); setTurnScreenOn(true)
        }
        if (Build.VERSION.SDK_INT >= 30) {
            window.attributes.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        }
        // Keep buttons/pills clear of the camera cutout and rounded corners; video uses the full screen.
        val hud = findViewById<View>(R.id.hud)
        ViewCompat.setOnApplyWindowInsetsListener(hud) { v, insets ->
            val i = insets.getInsetsIgnoringVisibility(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            v.setPadding(i.left, i.top, i.right, 0)
            insets
        }

        mainPreview = findViewById(R.id.mainPreview)
        cabinPreview = findViewById(R.id.cabinPreview)
        recStatus = findViewById(R.id.recStatus)
        gpsStatus = findViewById(R.id.gpsStatus)
        linkStatus = findViewById(R.id.linkStatus)
        focusStatus = findViewById(R.id.focusStatus)
        warning = findViewById(R.id.warning)
        speedMph = findViewById(R.id.speedMph)
        speedKph = findViewById(R.id.speedKph)
        recBtn = findViewById(R.id.recBtn)
        lockBtn = findViewById(R.id.lockBtn)
        focusBtn = findViewById(R.id.focusBtn)

        mainPreview.surfaceTextureListener = PreviewListener(CameraPipeline.Role.MAIN)
        cabinPreview.surfaceTextureListener = PreviewListener(CameraPipeline.Role.CABIN)

        recBtn.setOnClickListener {
            val s = service ?: return@setOnClickListener
            if (s.recording) s.userStop()
            else { s.userStart(); BatteryHelper.requestOnce(this) }
        }
        lockBtn.setOnClickListener { service?.lockClip() }
        focusBtn.setOnClickListener { settings.setMainFarFocus(!settings.mainFocus.far) }
        findViewById<Button>(R.id.settingsBtn).setOnClickListener {
            inOwnScreen = true
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        findViewById<Button>(R.id.clipsBtn).setOnClickListener {
            inOwnScreen = true
            startActivity(Intent(this, ClipsActivity::class.java))
        }

        setupLiveView()
        applyOrientationLayout()
        requestPermissions()
    }

    // ------------------------------------------------------------------ live view of the remote camera

    private lateinit var liveBox: View
    private lateinit var liveTexture: TextureView
    private lateinit var liveStatusText: TextView
    private var liveViewer: LiveViewer? = null
    private var liveOpen = false
    private var liveW = 0
    private var liveH = 0
    private val livePrefs by lazy { PreferenceManager.getDefaultSharedPreferences(this) }

    private val liveKeepAlive = object : Runnable {
        override fun run() {
            if (!liveOpen) return
            service?.requestLive(true)
            ticker.postDelayed(this, 8_000)
        }
    }

    private val liveBack = object : androidx.activity.OnBackPressedCallback(false) {
        override fun handleOnBackPressed() = closeLive()
    }

    private fun setupLiveView() {
        liveBox = findViewById(R.id.liveBox)
        liveTexture = findViewById(R.id.liveTexture)
        liveStatusText = findViewById(R.id.liveStatus)
        findViewById<Button>(R.id.liveBtn).setOnClickListener { openLive() }
        findViewById<Button>(R.id.backupBtn).setOnClickListener { openLive(backup = true) }
        linkStatus.setOnClickListener { if (settings.isPrimary) openLive() }
        // Holding the remote-camera line: update an outdated remote now (otherwise it waits until
        // neither phone is recording).
        linkStatus.setOnLongClickListener {
            val s = service
            if (s == null || !s.remoteOutdated) return@setOnLongClickListener false
            androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("Update the remote camera now?")
                .setMessage("This phone sends its DashCam (v${BuildConfig.VERSION_NAME}) to the remote camera, which installs it and reopens. " +
                    "The remote stops recording for about 20 seconds.")
                .setPositiveButton("Update now") { _, _ -> s.maybeUpdateRemote(manual = true) }
                .setNegativeButton("Later", null).show()
            true
        }
        findViewById<Button>(R.id.liveCloseBtn).setOnClickListener { closeLive() }
        findViewById<Button>(R.id.liveMirrorBtn).setOnClickListener {
            livePrefs.edit().putBoolean("live_mirror", !livePrefs.getBoolean("live_mirror", true)).apply()
            layoutLive()
        }
        liveBox.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> layoutLive() }
        onBackPressedDispatcher.addCallback(this, liveBack)
        liveTexture.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) { if (liveOpen) startViewer(st) }
            override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) {}
            override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
                liveViewer?.close(); liveViewer = null
                return true
            }
            override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}
        }
    }

    private fun setLiveStatus(t: String) {
        liveStatusText.text = t
        liveStatusText.visibility = if (t.isEmpty()) View.GONE else View.VISIBLE
    }

    /** The live view was opened with the Backup Cam button (so it may close itself when driving off). */
    private var liveForBackup = false
    private var liveTitle = "REMOTE CAMERA · LIVE"
    private var fastSince = 0L

    private fun openLive(backup: Boolean = false) {
        val s = service ?: return
        if (!s.linkConnected) { Toast.makeText(this, "The remote camera isn't connected", Toast.LENGTH_SHORT).show(); return }
        LiveLink.problem(this)?.let { p ->
            if (p.startsWith("Nearby") && Build.VERSION.SDK_INT >= 33)
                permLauncher.launch(arrayOf(Manifest.permission.NEARBY_WIFI_DEVICES))
            else Toast.makeText(this, p, Toast.LENGTH_LONG).show()
            return
        }
        liveOpen = true
        liveForBackup = backup
        fastSince = 0L
        liveTitle = if (backup) "BACKUP CAM · LIVE" else "REMOTE CAMERA · LIVE"
        findViewById<TextView>(R.id.liveLabel).text = liveTitle
        liveBack.isEnabled = true
        liveBox.visibility = View.VISIBLE
        liveW = 0; liveH = 0
        setLiveStatus("Asking the remote camera…")
        s.liveListener = { updateLive() }
        liveTexture.surfaceTexture?.let { startViewer(it) }
        ticker.removeCallbacks(liveKeepAlive)
        ticker.post(liveKeepAlive)
        ticker.removeCallbacks(liveNoAnswer)
        ticker.postDelayed(liveNoAnswer, 12_000)
    }

    private val liveNoAnswer = Runnable {
        if (liveOpen && service?.liveAnswer == null && service?.liveAddr == null)
            setLiveStatus("⚠ No answer from the remote camera — it may run an older DashCam without live view. Update it.")
    }

    private fun startViewer(st: SurfaceTexture) {
        if (liveViewer != null) return
        liveViewer = LiveViewer(this, Surface(st), { t -> if (liveOpen) setLiveStatus(t) }, { w, h ->
            liveW = w; liveH = h; layoutLive()
        }) { delayMs ->
            // How old the picture is (capture on the remote to decoded here; the screen adds a frame or two).
            if (liveOpen) {
                val mhz = service?.liveAnswer?.optInt("freq") ?: 0
                val band = when { mhz >= 5000 -> " · 5 GHz"; mhz > 0 -> " · ⚠ 2.4 GHz (slow band)"; else -> "" }
                findViewById<TextView>(R.id.liveLabel).text = "$liveTitle$band · delay ≈ $delayMs ms"
            }
        }
        updateLive()
    }

    /** Service news (group joined, error, …): connect the viewer or say what's happening. */
    private fun updateLive() {
        if (!liveOpen) return
        val s = service ?: return
        val addr = s.liveAddr
        val token = s.liveAnswer?.optString("token")
        val a = s.liveAnswer
        when {
            addr != null && !token.isNullOrEmpty() -> liveViewer?.connect(addr, token)
            a != null && !a.optBoolean("ok") -> setLiveStatus("⚠ Remote camera: ${a.optString("err", "can't show live view")}")
            s.liveJoinStatus.isNotEmpty() -> setLiveStatus(s.liveJoinStatus)
        }
    }

    /** Fit the video into the screen, keeping its shape; optionally mirrored like a rear-view mirror. */
    private fun layoutLive() {
        val bw = liveBox.width; val bh = liveBox.height
        if (bw == 0 || bh == 0) return
        val lp = liveTexture.layoutParams
        if (liveW > 0 && liveH > 0) {
            val scale = minOf(bw.toFloat() / liveW, bh.toFloat() / liveH)
            lp.width = (liveW * scale).toInt(); lp.height = (liveH * scale).toInt()
        } else { lp.width = bw; lp.height = bh }
        liveTexture.layoutParams = lp
        liveTexture.scaleX = if (livePrefs.getBoolean("live_mirror", true)) -1f else 1f
    }

    private fun closeLive() {
        if (!liveOpen) return
        liveOpen = false
        liveBack.isEnabled = false
        ticker.removeCallbacks(liveKeepAlive); ticker.removeCallbacks(liveNoAnswer)
        liveViewer?.close(); liveViewer = null
        service?.let { it.liveListener = null; it.requestLive(false) }
        liveBox.visibility = View.GONE
    }

    override fun onStart() {
        super.onStart()
        applyOrientationLayout()   // stamp options may have changed in Settings
        applyKeepScreenOn()
        // "Start recording when the app opens": every time the app comes to the front,
        // except when just coming back from our own settings screen.
        if (settings.autoRecord && !inOwnScreen) autoRecordPending = true
        inOwnScreen = false
        if (hasPerm(Manifest.permission.CAMERA)) startAndBind()
        ticker.post(tick)
        getSystemService(DisplayManager::class.java).registerDisplayListener(displayListener, ticker)
    }

    /** Keep the screen on as set, except on a resting remote camera: there it may go dark to save power. */
    private fun applyKeepScreenOn() {
        val on = settings.keepScreenOn && service?.isIdle != true
        if (on) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    override fun onUserInteraction() {
        super.onUserInteraction()
        service?.userActive()   // remote camera: a touch wakes the cameras
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    override fun onStop() {
        closeLive()
        ticker.removeCallbacks(tick)
        getSystemService(DisplayManager::class.java).unregisterDisplayListener(displayListener)
        service?.let {
            it.setPreview(CameraPipeline.Role.MAIN, null, 0, 0)
            it.setPreview(CameraPipeline.Role.CABIN, null, 0, 0)
            it.removeListener(stateListener)
        }
        if (boundRequested) { unbindService(conn); boundRequested = false }
        service = null
        super.onStop()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        service?.displayRotationDeg = displayRotationDeg()
        applyOrientationLayout()
    }

    // A 180° flip (landscape <-> reverse landscape) is not a configuration change, so watch the display.
    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(id: Int) {}
        override fun onDisplayRemoved(id: Int) {}
        override fun onDisplayChanged(id: Int) { service?.displayRotationDeg = displayRotationDeg() }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).roundToInt()

    /** Rearranges the HUD for portrait vs landscape. */
    private fun applyOrientationLayout() {
        val portrait = resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT
        (cabinPreview.layoutParams as FrameLayout.LayoutParams).apply {
            width = dp(if (portrait) 113 else 200); height = dp(if (portrait) 200 else 113)
        }.also { cabinPreview.layoutParams = it }
        val speedBox = findViewById<View>(R.id.speedBox)
        (speedBox.layoutParams as FrameLayout.LayoutParams).apply {
            // Portrait: below the rear-camera thumbnail on the right. Landscape: top centre.
            gravity = if (portrait) Gravity.TOP or Gravity.END else Gravity.TOP or Gravity.CENTER_HORIZONTAL
            topMargin = dp(if (portrait) 224 else 12)
            marginEnd = dp(12)
        }.also { speedBox.layoutParams = it }
        val controls = findViewById<View>(R.id.controls)
        (controls.layoutParams as FrameLayout.LayoutParams).apply {
            // Portrait: the video sits at the top, so the controls go in the empty space below it.
            // Landscape: lift them above the text stamp.
            // Portrait: below the video. Landscape: just above the stamp, however many lines it has.
            bottomMargin = if (portrait) dp(28) else {
                val opts = settings.overlayOptions("FRONT")
                val h = resources.displayMetrics.heightPixels
                val w = h * 16 / 9   // landscape video frame as shown on screen
                val lines = OverlayTexture.lineCountFor(w, h, opts)
                (lines * opts.textSize * resources.displayMetrics.heightPixels).toInt() + dp(10)
            }
        }.also { controls.layoutParams = it }
        // Portrait is narrow: icon-only buttons so the row fits.
        findViewById<Button>(R.id.clipsBtn).text = if (portrait) "🎞" else "🎞 Clips"
        findViewById<Button>(R.id.liveBtn).text = if (portrait) "📺" else "📺 Remote"
        lockBtn.text = if (portrait) "🔒" else "🔒 Lock"
        if (::settings.isInitialized && service != null) render()
        // Keep the warning clear of the rear-camera thumbnail in portrait.
        val widthDp = resources.configuration.screenWidthDp
        warning.maxWidth = dp(if (portrait) (widthDp - 113 - 48).coerceAtLeast(160) else 380)
        gpsStatus.maxWidth = warning.maxWidth
        linkStatus.maxWidth = warning.maxWidth
        focusStatus.maxWidth = warning.maxWidth
    }

    private fun requestPermissions() {
        val perms = mutableListOf(
            Manifest.permission.CAMERA,
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.RECORD_AUDIO,
        )
        if (settings.endOnWalking) perms += Manifest.permission.ACTIVITY_RECOGNITION
        if (Build.VERSION.SDK_INT >= 33) perms += Manifest.permission.POST_NOTIFICATIONS
        if (Build.VERSION.SDK_INT >= 31 && settings.linkRole != "normal") perms += Manifest.permission.BLUETOOTH_CONNECT
        if (Build.VERSION.SDK_INT >= 33 && settings.linkRole != "normal") perms += Manifest.permission.NEARBY_WIFI_DEVICES
        val missing = perms.filter { !hasPerm(it) }
        if (missing.isNotEmpty()) permLauncher.launch(missing.toTypedArray())
    }

    private fun startAndBind() {
        if (boundRequested) return
        val i = Intent(this, DashCamService::class.java)
        startForegroundService(i)
        boundRequested = bindService(i, conn, BIND_AUTO_CREATE)
    }

    private val conn = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val s = (binder as DashCamService.LocalBinder).service
            service = s
            s.displayRotationDeg = displayRotationDeg()
            s.addListener(stateListener)
            attachPreview(CameraPipeline.Role.MAIN, mainPreview)
            attachPreview(CameraPipeline.Role.CABIN, cabinPreview)
            if (autoRecordPending && !s.recording) s.startRecording()
            autoRecordPending = false
            // A remote camera sits with the screen off for hours: ask once to be spared battery limits.
            if (settings.isRemote) BatteryHelper.requestOnce(this@MainActivity)
            Updater.checkInBackground(this@MainActivity) { service?.recording == true || autoRecordPending }
            render()
        }
        override fun onServiceDisconnected(name: ComponentName?) { service = null }
    }

    private fun attachPreview(role: CameraPipeline.Role, v: TextureView) {
        val st = v.surfaceTexture ?: return
        service?.setPreview(role, Surface(st), v.width, v.height)
    }

    private inner class PreviewListener(val role: CameraPipeline.Role) : TextureView.SurfaceTextureListener {
        override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) {
            service?.setPreview(role, Surface(st), w, h)
        }
        override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) {
            service?.setPreviewSize(role, w, h)
        }
        override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
            service?.setPreview(role, null, 0, 0)
            return true
        }
        override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}
    }

    private companion object {
        /** The Backup Cam button shows at or below this speed. */
        const val BACKUP_SHOW_KMH = 10f
    }

    private val tick = object : Runnable {
        override fun run() {
            renderLive()
            ticker.postDelayed(this, 500)
        }
    }

    /**
     * Backup Cam: the big button shows while a remote camera is connected and the car is slow or
     * stopped; a view opened with it goes back to normal once the car drives off above the chosen speed.
     */
    private fun updateBackupCam(mps: Float?) {
        val btn = findViewById<Button>(R.id.backupBtn)
        val kmh = mps?.let { Telemetry.kph(it) }
        val show = settings.backupButton && service?.remoteCamReady == true && !liveOpen &&
            (kmh == null || kmh <= BACKUP_SHOW_KMH)
        btn.visibility = if (show) View.VISIBLE else View.GONE
        val limit = settings.backupReturnKmh
        if (liveOpen && liveForBackup && limit > 0 && kmh != null && kmh > limit) {
            val now = SystemClock.elapsedRealtime()
            if (fastSince == 0L) fastSince = now
            else if (now - fastSince >= 1500) closeLive()   // held above the limit: not just GPS noise
        } else fastSince = 0L
    }

    /** Things that change continuously: speed, GPS, record timer. */
    private fun renderLive() {
        val mps = Telemetry.speedMps()
        updateBackupCam(mps)
        val mphText = if (mps != null) "${Telemetry.mph(mps).roundToInt()} mph" else "-- mph"
        val kphText = if (mps != null) "${Telemetry.kph(mps).roundToInt()} km/h" else "-- km/h"
        when (settings.speedUnit) {
            "kmh" -> { speedMph.text = kphText; speedKph.visibility = View.GONE }
            "mph" -> { speedMph.text = mphText; speedKph.visibility = View.GONE }
            else -> { speedMph.text = mphText; speedKph.text = kphText; speedKph.visibility = View.VISIBLE }
        }
        val fix = Telemetry.freshFix()
        val relay = Telemetry.relayed()
        gpsStatus.text = if (relay != null)
            "GPS via main phone" + (relay.accM?.let { " ±$it m" } ?: ": no fix") + " · ${relay.satsUsed} sats"
        else if (fix != null)
            "GPS ±${fix.accuracy.roundToInt()} m · ${Telemetry.satsUsed} sats"
        else "GPS: searching (${Telemetry.satsUsed}/${Telemetry.satsVisible})"
        val alt = Altimeter.altitudeFt()
        if (alt != null) gpsStatus.text = "${gpsStatus.text}\nAlt ${alt.roundToInt()} ft"
        else Telemetry.altitudeText()?.let { gpsStatus.text = "${gpsStatus.text}\n${it.replaceFirstChar { c -> c.uppercase() }}" }

        val s = service
        if (s != null && s.recording) {
            val secs = (SystemClock.elapsedRealtime() - s.recordingStartElapsed) / 1000
            val cams = listOfNotNull(s.mainCam?.takeIf { it.isRecording }?.role?.label,
                s.cabinCam?.takeIf { it.isRecording }?.role?.label).joinToString("+")
            recStatus.text = String.format("● REC %02d:%02d:%02d  %s%s", secs / 3600, secs / 60 % 60, secs % 60,
                cams, if (s.audioActive) " + mic" else "") +
                (s.autoStopCountdown?.let { (why, ms) ->
                    String.format("\nStopping in %d:%02d — %s", ms / 60000, ms / 1000 % 60, why) } ?: "")
            recStatus.setTextColor(if (secs % 2 == 0L) 0xFFFF5252.toInt() else 0xFFFFFFFF.toInt())
        }
        s?.muxerError()?.let { showWarning(it) }
        val ls = s?.linkStatusText()
        linkStatus.visibility = if (ls == null) View.GONE else View.VISIBLE
        linkStatus.text = ls
        linkStatus.setTextColor(if (ls?.startsWith("⚠") == true) 0xFFFFCC80.toInt() else 0xFFFFFFFF.toInt())
        val f = s?.mainCam?.focusStatus
        focusStatus.visibility = if (f.isNullOrEmpty()) View.GONE else View.VISIBLE
        val stab = listOfNotNull(
            s?.mainCam?.stabStatus?.takeIf { it.isNotEmpty() }?.let { "Front: $it" },
            s?.cabinCam?.stabStatus?.takeIf { it.isNotEmpty() }?.let { "Rear: $it" })
        focusStatus.text = "Front focus: $f" + (s?.cabinCam?.focusStatus?.let { "  ·  Rear: $it" } ?: "") +
            if (stab.isEmpty()) "" else "\n" + stab.joinToString("  ·  ")
    }

    /** Things that change on service events. */
    private fun render() {
        val s = service
        applyKeepScreenOn()
        // A resting remote camera's preview would show its last frame frozen: hide it.
        val previewAlpha = if (s?.isIdle == true) 0f else 1f
        mainPreview.alpha = previewAlpha; cabinPreview.alpha = previewAlpha
        val rec = s?.recording == true
        // Recording follows the phone's orientation (a turn starts a new clip), so never lock it.
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_FULL_USER
        recBtn.setBackgroundResource(if (rec) R.drawable.stop_button else R.drawable.rec_button)
        findViewById<Button>(R.id.liveBtn).visibility = if (settings.isPrimary) View.VISIBLE else View.GONE
        recBtn.isEnabled = s != null && !s.stopping
        lockBtn.isEnabled = rec
        lockBtn.alpha = if (rec) 1f else 0.4f
        val narrow = resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT
        focusBtn.text = when {
            narrow -> if (settings.mainFocus.far) "∞" else "AF"
            else -> if (settings.mainFocus.far) "Focus ∞" else "Focus auto"
        }
        if (!rec) {
            recStatus.text = when {
                s?.stopping == true -> "Saving…"
                s?.isIdle == true -> "RESTING — camera off until the main phone connects (tap to wake)"
                else -> "STANDBY"
            }
            recStatus.setTextColor(0xFFFFFFFF.toInt())
        }
        cabinPreview.visibility = if (s?.cabinCam != null) View.VISIBLE else View.INVISIBLE
        showWarning(s?.warning)
        s?.toast?.let { Toast.makeText(this, it, Toast.LENGTH_SHORT).show(); s.toast = null }
        renderLive()
    }

    private fun showWarning(text: String?) {
        warning.visibility = if (text.isNullOrEmpty()) View.GONE else View.VISIBLE
        warning.text = text
    }

    @Suppress("DEPRECATION")
    private fun displayRotationDeg(): Int {
        val r = if (Build.VERSION.SDK_INT >= 30) display?.rotation ?: Surface.ROTATION_0
                else windowManager.defaultDisplay.rotation
        return when (r) {
            Surface.ROTATION_90 -> 90
            Surface.ROTATION_180 -> 180
            Surface.ROTATION_270 -> 270
            else -> 0
        }
    }

    private fun hideSystemBars() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    private fun hasPerm(p: String) = checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED
}
