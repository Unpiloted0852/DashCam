package com.example.dashcam

import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.preference.ListPreference
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
        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            setPreferencesFromResource(R.xml.preferences, rootKey)
            findPreference<ListPreference>("storage_volume")?.let { pref ->
                val vols = Storage.volumes(requireContext())
                pref.entries = vols.map { it.label }.toTypedArray()
                pref.entryValues = vols.map { it.name }.toTypedArray()
                if (vols.none { it.name == pref.value }) pref.value = "external_primary"
            }
            findPreference<ListPreference>("auto_stop_minutes")?.summaryProvider =
                Preference.SummaryProvider<ListPreference> { p ->
                    if (p.value == "0") "Off" else "${p.entry} — only after you've driven off; uses GPS speed"
                }
            findPreference<Preference>("check_update")?.apply {
                summary = "Installed: v${BuildConfig.VERSION_NAME} · github.com/${BuildConfig.UPDATE_REPO}"
                setOnPreferenceClickListener { Updater.checkNow(requireActivity()); true }
            }
            findPreference<androidx.preference.Preference>("battery_opt")?.setOnPreferenceClickListener {
                BatteryHelper.request(requireActivity()); true
            }
        }

        override fun onResume() {
            super.onResume()
            findPreference<androidx.preference.Preference>("battery_opt")?.summary =
                if (BatteryHelper.isExempt(requireContext())) "Unrestricted — recording keeps running in the background"
                else "Currently restricted: Android may pause recording when the screen is off or another app is in front. Tap to allow."
        }
    }
}
