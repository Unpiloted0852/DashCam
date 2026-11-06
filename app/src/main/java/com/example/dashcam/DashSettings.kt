package com.example.dashcam

import android.content.Context
import android.util.Size
import androidx.preference.PreferenceManager

/** Typed access to the user's preferences (see res/xml/preferences.xml). */
class DashSettings(ctx: Context) {
    private val p = PreferenceManager.getDefaultSharedPreferences(ctx)

    private fun str(key: String, def: String) = p.getString(key, def) ?: def
    private fun bool(key: String, def: Boolean) = p.getBoolean(key, def)

    val segmentMs: Long get() = str("segment_minutes", "3").toLong() * 60_000L
    val storageLimitBytes: Long get() = str("storage_limit_gb", "16").toLong() * 1024L * 1024L * 1024L
    /** Road-camera lens: "widest" (default), "main", or a key from Lenses.backLenses. */
    val mainLens: String get() = str("main_lens", "widest")
    val mainSize: Size get() = sizeFor(str("main_resolution", "1080"))
    val cabinEnabled: Boolean get() = bool("cabin_enabled", true)
    val cabinSize: Size get() = sizeFor(str("cabin_resolution", "720"))
    private val bitsPerPixel: Float get() = str("quality", "0.15").toFloat()
    val audioEnabled: Boolean get() = bool("audio_enabled", true)

    val mainFocus: FocusConfig get() = FocusConfig(bool("main_far_focus", true), str("main_focus_target", "inf"))
    val cabinFocus: FocusConfig get() = FocusConfig(bool("cabin_far_focus", false), str("cabin_focus_target", "inf"))

    val cabinMirror: Boolean get() = bool("cabin_mirror", false)
    val mainRotate180: Boolean get() = bool("main_rotate180", false)
    val cabinRotate180: Boolean get() = bool("cabin_rotate180", false)

    val showCoords: Boolean get() = bool("show_coords", true)
    val useUtc: Boolean get() = bool("use_utc", false)
    val overlayLabel: String get() = str("overlay_label", "").trim()
    val storageVolume: String get() = str("storage_volume", "external_primary")
    /** Stop recording after this long at ~0 mph; 0 = never. */
    val autoStopMs: Long get() = str("auto_stop_minutes", "0").toLong() * 60_000L
    val autoRecord: Boolean get() = bool("auto_record", false)
    val keepScreenOn: Boolean get() = bool("keep_screen_on", true)

    fun setMainFarFocus(on: Boolean) = p.edit().putBoolean("main_far_focus", on).apply()

    fun bitrateFor(size: Size): Int =
        (size.width * size.height * 30 * bitsPerPixel).toInt().coerceIn(1_000_000, 60_000_000)

    private fun sizeFor(v: String) = when (v) {
        "480" -> Size(854, 480)
        "720" -> Size(1280, 720)
        "2160" -> Size(3840, 2160)
        else -> Size(1920, 1080)
    }

    companion object {
        /** Changing these needs the cameras to be reopened. */
        const val STOP_SPEED_MPS = 1.0f  // ~2 mph: GPS jitter while parked stays below this
        val RESTART_KEYS = setOf("main_lens", "main_resolution", "cabin_resolution", "cabin_enabled")
    }
}

/**
 * Focus behaviour. [far] = lock the lens far away so it can't hunt onto the dashboard/windshield.
 * [target] = "inf", "hyper" (hyperfocal distance) or a distance in metres.
 */
data class FocusConfig(val far: Boolean, val target: String) {
    /** Diopters (1/metres) for LENS_FOCUS_DISTANCE; 0 = infinity. */
    fun diopters(hyperfocal: Float): Float = when (target) {
        "inf" -> 0f
        "hyper" -> if (hyperfocal > 0f) hyperfocal else 0f
        else -> target.toFloatOrNull()?.takeIf { it > 0f }?.let { 1f / it } ?: 0f
    }
}

data class OverlayOptions(
    val camLabel: String,
    val userText: String,
    val showCoords: Boolean,
    val utc: Boolean,
)
