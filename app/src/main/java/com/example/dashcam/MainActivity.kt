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

        applyOrientationLayout()
        requestPermissions()
    }

    override fun onStart() {
        super.onStart()
        applyOrientationLayout()   // stamp options may have changed in Settings
        if (settings.keepScreenOn) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        // "Start recording when the app opens": every time the app comes to the front,
        // except when just coming back from our own settings screen.
        if (settings.autoRecord && !inOwnScreen) autoRecordPending = true
        inOwnScreen = false
        if (hasPerm(Manifest.permission.CAMERA)) startAndBind()
        ticker.post(tick)
        getSystemService(DisplayManager::class.java).registerDisplayListener(displayListener, ticker)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    override fun onStop() {
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

    private val tick = object : Runnable {
        override fun run() {
            renderLive()
            ticker.postDelayed(this, 500)
        }
    }

    /** Things that change continuously: speed, GPS, record timer. */
    private fun renderLive() {
        val mps = Telemetry.speedMps()
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
        focusStatus.text = "Front focus: $f" + (s?.cabinCam?.focusStatus?.let { "  ·  Rear: $it" } ?: "")
    }

    /** Things that change on service events. */
    private fun render() {
        val s = service
        val rec = s?.recording == true
        // Recording follows the phone's orientation (a turn starts a new clip), so never lock it.
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_FULL_USER
        recBtn.setBackgroundResource(if (rec) R.drawable.stop_button else R.drawable.rec_button)
        recBtn.isEnabled = s != null && !s.stopping
        lockBtn.isEnabled = rec
        lockBtn.alpha = if (rec) 1f else 0.4f
        val narrow = resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT
        focusBtn.text = when {
            narrow -> if (settings.mainFocus.far) "∞" else "AF"
            else -> if (settings.mainFocus.far) "Focus ∞" else "Focus auto"
        }
        if (!rec) {
            recStatus.text = if (s?.stopping == true) "Saving…" else "STANDBY"
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
