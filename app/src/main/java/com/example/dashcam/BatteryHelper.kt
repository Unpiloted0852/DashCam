package com.example.dashcam

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import androidx.preference.PreferenceManager

/** Battery-optimisation exemption so Doze / app standby don't throttle a long background recording. */
object BatteryHelper {
    fun isExempt(ctx: Context): Boolean =
        ctx.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(ctx.packageName)

    @SuppressLint("BatteryLife")
    fun request(a: Activity) {
        if (isExempt(a)) return
        try {
            a.startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                Uri.parse("package:${a.packageName}")))
        } catch (_: Exception) {
            a.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }
    }

    /** Ask once, the first time the user starts recording. */
    fun requestOnce(a: Activity) {
        val p = PreferenceManager.getDefaultSharedPreferences(a)
        if (isExempt(a) || p.getBoolean("battery_asked", false)) return
        p.edit().putBoolean("battery_asked", true).apply()
        request(a)
    }
}
