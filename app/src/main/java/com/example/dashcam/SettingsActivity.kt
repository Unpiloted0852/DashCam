package com.example.dashcam

import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import android.Manifest
import android.bluetooth.BluetoothManager
import android.content.pm.PackageManager
import android.os.Build
import kotlin.math.pow
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.preference.ListPreference
import androidx.preference.SwitchPreferenceCompat
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import com.google.android.material.appbar.MaterialToolbar

class SettingsActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        // Android 15+ draws edge-to-edge; keep the content out from under the system bars.
        ViewCompat.setOnApplyWindowInsetsListener(findViewById<View>(R.id.settingsRoot)) { v, insets ->
            val i = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            v.setPadding(i.left, i.top, i.right, i.bottom)
            insets
        }
        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .replace(R.id.settingsContainer, Fragment())
                .commit()
        }
    }

    override fun onSupportNavigateUp(): Boolean { finish(); return true }

    class Fragment : PreferenceFragmentCompat() {
        private val activityPerm = registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
            if (!ok) findPreference<SwitchPreferenceCompat>("auto_stop_walk")?.isChecked = false
        }
        private val btPerm = registerForActivityResult(ActivityResultContracts.RequestPermission()) { setupBluetoothList() }

        private fun granted(p: String) =
            ContextCompat.checkSelfPermission(requireContext(), p) == PackageManager.PERMISSION_GRANTED

        /** Lists paired Bluetooth devices so the user can pick their car. */
        @android.annotation.SuppressLint("MissingPermission")
        private fun setupBluetoothList() {
            val pref = findPreference<ListPreference>("auto_stop_bt") ?: return
            val perm = if (Build.VERSION.SDK_INT >= 31) Manifest.permission.BLUETOOTH_CONNECT else Manifest.permission.BLUETOOTH
            if (!granted(perm)) {
                pref.entries = arrayOf("Off"); pref.entryValues = arrayOf("")
                pref.summaryProvider = null
                pref.summary = "Tap to allow Bluetooth access and pick your car"
                pref.setOnPreferenceClickListener { btPerm.launch(perm); true }
                findPreference<ListPreference>("link_device")?.apply {
                    entries = arrayOf("None"); entryValues = arrayOf("")
                    summaryProvider = null
                    summary = "Tap to allow Bluetooth access and pick the other phone"
                    setOnPreferenceClickListener { btPerm.launch(perm); true }
                }
                updateLinkPrefs(findPreference<ListPreference>("link_role")?.value ?: "normal")
                return
            }
            pref.onPreferenceClickListener = null
            val bonded = RemoteLink.pairedDevices(requireContext())
            findPreference<ListPreference>("link_device")?.onPreferenceClickListener = null
            setupLinkDeviceList(bonded.map { (it.name ?: it.address) to it.address })
            pref.entries = (listOf("Off") + bonded.map { it.name ?: it.address }).toTypedArray()
            pref.entryValues = (listOf("") + bonded.map { it.address }).toTypedArray()
            pref.summaryProvider = Preference.SummaryProvider<ListPreference> { p ->
                if (p.value.isNullOrEmpty()) "Off — pick your car to stop when it disconnects"
                else "${p.entry} disconnecting while stopped ends the drive"
            }
        }

        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            setPreferencesFromResource(R.xml.preferences, rootKey)
            findPreference<ListPreference>("link_role")?.setOnPreferenceChangeListener { _, v ->
                updateLinkPrefs(v.toString()); true
            }
            findPreference<ListPreference>("main_lens")?.let { pref ->
                val lenses = Lenses.backLenses(requireContext())
                val widest = lenses.minByOrNull { it.relative }
                pref.entries = (listOf("Widest available" + (widest?.let { " — now ${it.label}" } ?: "")) +
                    lenses.map { it.label }).toTypedArray()
                pref.entryValues = (listOf("widest") + lenses.map { it.key }).toTypedArray()
                if (pref.value !in pref.entryValues.map { it.toString() }) pref.value = "widest"
            }
            findPreference<ListPreference>("storage_volume")?.let { pref ->
                val vols = Storage.volumes(requireContext())
                pref.entries = vols.map { it.label }.toTypedArray()
                pref.entryValues = vols.map { it.name }.toTypedArray()
                if (vols.none { it.name == pref.value }) pref.value = "external_primary"
            }
            findPreference<ListPreference>("auto_stop_minutes")?.summaryProvider =
                Preference.SummaryProvider<ListPreference> { p ->
                    if (p.value == "0") "Off" else "${p.entry} at walking pace or slower — backup for the signals above"
                }
            findPreference<SwitchPreferenceCompat>("auto_stop_walk")?.setOnPreferenceChangeListener { _, on ->
                if (on == true && !granted(Manifest.permission.ACTIVITY_RECOGNITION)) {
                    activityPerm.launch(Manifest.permission.ACTIVITY_RECOGNITION)
                }
                true
            }
            setupBluetoothList()
            findPreference<androidx.preference.MultiSelectListPreference>("stamp_weather_fields")?.summaryProvider =
                Preference.SummaryProvider<androidx.preference.MultiSelectListPreference> { p ->
                    val labels = p.entries.zip(p.entryValues).filter { it.second.toString() in p.values }.map { it.first }
                    if (labels.isEmpty()) "Nothing selected" else labels.joinToString(", ")
                }
            findPreference<Preference>("check_update")?.apply {
                summary = "Installed: v${BuildConfig.VERSION_NAME} · github.com/${BuildConfig.UPDATE_REPO}"
                setOnPreferenceClickListener { Updater.checkNow(requireActivity()); true }
            }
            findPreference<Preference>("baro_calibrate")?.setOnPreferenceClickListener { calibrateDialog(); true }
            findPreference<Preference>("remote_update_setup")?.setOnPreferenceClickListener {
                val ctx = requireContext()
                val pkg = android.net.Uri.parse("package:${ctx.packageName}")
                when {
                    !ctx.packageManager.canRequestPackageInstalls() ->
                        startActivity(android.content.Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, pkg))
                    !Updater.canReopen(ctx) ->
                        startActivity(android.content.Intent(android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION, pkg))
                    else -> android.widget.Toast.makeText(ctx, "Nothing more to set up", android.widget.Toast.LENGTH_SHORT).show()
                }
                true
            }
            findPreference<Preference>("share_log")?.setOnPreferenceClickListener {
                EventLog.init(requireContext())
                val text = EventLog.read().takeLast(200_000).ifEmpty { "(log is empty)" }
                startActivity(android.content.Intent.createChooser(
                    android.content.Intent(android.content.Intent.ACTION_SEND)
                        .setType("text/plain")
                        .putExtra(android.content.Intent.EXTRA_SUBJECT, "DashCam diagnostic log")
                        .putExtra(android.content.Intent.EXTRA_TEXT, text),
                    "Share diagnostic log"))
                true
            }
            findPreference<androidx.preference.Preference>("battery_opt")?.setOnPreferenceClickListener {
                BatteryHelper.request(requireActivity()); true
            }
        }

        /** Paired phones for the remote-camera link (same Bluetooth permission as the car list). */
        private fun setupLinkDeviceList(bonded: List<Pair<String, String>>) {
            val pref = findPreference<ListPreference>("link_device") ?: return
            pref.entries = (listOf("None") + bonded.map { it.first }).toTypedArray()
            pref.entryValues = (listOf("") + bonded.map { it.second }).toTypedArray()
            pref.summaryProvider = Preference.SummaryProvider<ListPreference> { p ->
                if (p.value.isNullOrEmpty()) "Pick the other phone (pair them first in Android's Bluetooth settings)"
                else p.entry ?: p.value
            }
            updateLinkPrefs(findPreference<ListPreference>("link_role")?.value ?: "normal")
        }

        private fun updateLinkPrefs(role: String) {
            findPreference<Preference>("link_device")?.isVisible = role != "normal"
            findPreference<Preference>("remote_camera")?.isVisible = role == "remote"
            findPreference<Preference>("remote_update_setup")?.isVisible = role == "remote"
            findPreference<Preference>("backup_button")?.isVisible = role == "primary"
            findPreference<Preference>("backup_return_kmh")?.isVisible = role == "primary"
            // A remote camera records one camera and takes its stamp/clip settings from the main phone.
            findPreference<Preference>("cabin_enabled")?.isEnabled = role != "remote"
            findPreference<Preference>("cabin_enabled")?.summary = if (role == "remote")
                "Off while this phone is a remote camera" else "Needs a phone that can run both cameras at once"
        }

        // ---- altitude calibration: read the barometer live while settings are open
        private var liveHpa: Float? = null
        private val baroListener = object : android.hardware.SensorEventListener {
            override fun onSensorChanged(e: android.hardware.SensorEvent) {
                val v = e.values[0]; val prev = liveHpa
                liveHpa = if (prev == null) v else prev + 0.2f * (v - prev)
            }
            override fun onAccuracyChanged(s: android.hardware.Sensor?, a: Int) {}
        }
        private val sensors get() = requireContext().getSystemService(android.hardware.SensorManager::class.java)
        private fun barometer() = sensors?.getDefaultSensor(android.hardware.Sensor.TYPE_PRESSURE)

        override fun onPause() {
            sensors?.unregisterListener(baroListener)
            super.onPause()
        }

        private fun metric() = preferenceManager.sharedPreferences?.getString("stamp_alt_unit", "ft") == "m"

        private fun updateCalibrationSummary() {
            val pref = findPreference<Preference>("baro_calibrate") ?: return
            if (barometer() == null) {
                pref.summary = "This phone has no barometer (altitude comes from GPS)"; pref.isEnabled = false; return
            }
            Altimeter.loadCalibration(requireContext())
            val off = Altimeter.offsetHpa
            pref.summary = if (off == 0f) "Not calibrated — correct the barometer using a known altitude"
            else {
                val ft = -off * 27.3f   // ~27 ft per hPa near sea level; lower pressure reads higher
                if (metric()) String.format(java.util.Locale.US, "Corrected by %+.1f hPa (≈ %+d m). Tap to recalibrate or reset", off, (ft * 0.3048f).toInt())
                else String.format(java.util.Locale.US, "Corrected by %+.1f hPa (≈ %+d ft). Tap to recalibrate or reset", off, ft.toInt())
            }
        }

        private fun calibrateDialog() {
            val ctx = requireContext()
            val raw = liveHpa ?: Altimeter.pressureHpa
            if (raw == null) {
                android.widget.Toast.makeText(ctx, "Waiting for the barometer — try again in a moment", android.widget.Toast.LENGTH_SHORT).show()
                return
            }
            val m = Altimeter.metar
            val qnh = Altimeter.currentQnhHpa()
            val unit = if (metric()) "m" else "ft"
            fun shown(ft: Float) = if (metric()) (ft * 0.3048f).toInt() else ft.toInt()
            val nowFt = (145366.45 * (1.0 - ((raw + Altimeter.offsetHpa) / qnh).toDouble().pow(0.190284))).toFloat()
            val source = m?.let { String.format(java.util.Locale.US, "%s %.2f inHg", it.station, it.altimHpa / 33.8639f) }
                ?: "standard pressure — no METAR yet, so today's weather would end up in the correction"
            val input = android.widget.EditText(ctx).apply {
                inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_SIGNED
                hint = "Known altitude in $unit"
            }
            val pad = (20 * resources.displayMetrics.density).toInt()
            val box = android.widget.FrameLayout(ctx).apply { setPadding(pad, 0, pad, 0); addView(input) }
            androidx.appcompat.app.AlertDialog.Builder(ctx)
                .setTitle("Calibrate altitude")
                .setMessage("Currently showing ${shown(nowFt)} $unit (using $source).\n\n" +
                    "Stand somewhere with a known altitude (survey marker, airport, topo map) and enter it here.")
                .setView(box)
                .setPositiveButton("Calibrate") { _, _ ->
                    val entered = input.text.toString().trim().toFloatOrNull() ?: return@setPositiveButton
                    val trueFt = if (metric()) entered / 0.3048f else entered
                    val off = Altimeter.offsetFor(trueFt, raw, qnh)
                    if (kotlin.math.abs(off) > Altimeter.MAX_OFFSET_HPA) {
                        android.widget.Toast.makeText(ctx, "That would be a ${"%.0f".format(off)} hPa correction — too large to be sensor error. Check the altitude and units.", android.widget.Toast.LENGTH_LONG).show()
                        return@setPositiveButton
                    }
                    preferenceManager.sharedPreferences?.edit()?.putFloat(Altimeter.PREF_OFFSET, off)?.apply()
                    Altimeter.offsetHpa = off
                    EventLog.log("Baro", String.format(java.util.Locale.US, "calibrated: %+.2f hPa (true %.0f ft, raw %.2f hPa, QNH %.2f hPa%s)",
                        off, trueFt, raw, qnh, m?.let { " from ${it.station}" } ?: " standard"))
                    updateCalibrationSummary()
                }
                .setNeutralButton("Reset") { _, _ ->
                    preferenceManager.sharedPreferences?.edit()?.remove(Altimeter.PREF_OFFSET)?.apply()
                    Altimeter.offsetHpa = 0f
                    EventLog.log("Baro", "calibration reset")
                    updateCalibrationSummary()
                }
                .setNegativeButton("Cancel", null)
                .show()
        }

        override fun onResume() {
            super.onResume()
            barometer()?.let { sensors?.registerListener(baroListener, it, android.hardware.SensorManager.SENSOR_DELAY_NORMAL) }
            updateCalibrationSummary()
            findPreference<Preference>("remote_update_setup")?.summary = run {
                val ctx = requireContext()
                val install = ctx.packageManager.canRequestPackageInstalls()
                val reopen = Updater.canReopen(ctx)
                when {
                    !install -> "The main phone can update this phone's DashCam over the link. Step 1 of 2: tap, then allow DashCam to install apps."
                    !reopen -> "Step 2 of 2: tap, then allow DashCam to display over other apps, so it can reopen itself after an update."
                    android.os.Build.VERSION.SDK_INT < 31 -> "Set up. On this Android version each update still needs one tap on this phone to confirm."
                    else -> "Set up: the main phone updates this phone's DashCam, and it reopens by itself."
                }
            }
            findPreference<androidx.preference.Preference>("battery_opt")?.summary =
                if (BatteryHelper.isExempt(requireContext())) "Unrestricted — recording keeps running in the background"
                else "Currently restricted: Android may pause recording when the screen is off or another app is in front. Tap to allow."
        }
    }
}
