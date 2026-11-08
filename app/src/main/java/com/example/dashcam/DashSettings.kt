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
    val cabinEnabled: Boolean get() = bool("cabin_enabled", true) && !isRemote
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
    val autoStopMs: Long get() = str("auto_stop_minutes", "10").toLong() * 60_000L
    /** End-of-drive signals (see DriveEndDetector). */
    val endOnWalking: Boolean get() = bool("auto_stop_walk", true)
    val endOnUnplug: Boolean get() = bool("auto_stop_unplug", true)
    /** MAC address of the car's Bluetooth, or "" for off. */
    val endBluetoothAddress: String get() = str("auto_stop_bt", "")
    val autoRecord: Boolean get() = bool("auto_record", false)
    val keepScreenOn: Boolean get() = bool("keep_screen_on", true)

    /** Remote camera feature: "normal", "primary" (has a remote camera) or "remote" (is one). */
    val linkRole: String get() = str("link_role", "normal")
    val isRemote: Boolean get() = linkRole == "remote"
    val isPrimary: Boolean get() = linkRole == "primary"
    /** Bluetooth address of the other phone. */
    val linkDevice: String get() = str("link_device", "")
    /** Remote camera: "back" (uses the Lens setting) or "selfie". */
    val remoteCamera: String get() = str("remote_camera", "back")

    /** Mirrored main phone -> remote camera (settings that don't depend on how a phone is mounted). */
    fun mirroredJson(): org.json.JSONObject {
        val o = org.json.JSONObject()
        for ((k, v) in p.all) {
            if (k !in MIRRORED_KEYS) continue
            when (v) {
                is Set<*> -> o.put(k, org.json.JSONObject().put("set", org.json.JSONArray(v.map { it.toString() })))
                else -> o.put(k, v)
            }
        }
        return o
    }

    /** Remote camera: apply the main phone's settings. Returns the keys that changed. */
    fun applyMirrored(o: org.json.JSONObject): List<String> {
        val e = p.edit()
        val changed = ArrayList<String>()
        for (k in o.keys()) {
            if (k !in MIRRORED_KEYS) continue
            val v = o.get(k)
            val cur = p.all[k]
            when (v) {
                is Boolean -> if (cur != v) { e.putBoolean(k, v); changed += k }
                is String -> if (cur != v) { e.putString(k, v); changed += k }
                is org.json.JSONObject -> {
                    val arr = v.optJSONArray("set") ?: continue
                    val set = (0 until arr.length()).map { arr.getString(it) }.toSet()
                    if (cur != set) { e.putStringSet(k, set); changed += k }
                }
            }
        }
        if (changed.isNotEmpty()) e.apply()
        return changed
    }

    private fun set(key: String, def: Set<String>): Set<String> = p.getStringSet(key, def) ?: def

    fun overlayOptions(camLabel: String) = OverlayOptions(
        camLabel = camLabel,
        userText = overlayLabel,
        showCoords = showCoords,
        utc = useUtc,
        clock12h = str("stamp_clock", "24") == "12",
        zoneStyle = str("stamp_zone", "abbr"),
        showAccuracy = bool("stamp_accuracy", true),
        speedUnit = str("stamp_speed", "both"),
        showHeading = bool("stamp_heading", true),
        showAltitude = bool("stamp_altitude", true),
        altUnit = str("stamp_alt_unit", "ft"),
        showSats = bool("stamp_sats", true),
        showPlace = bool("stamp_place", false),
        weather = if (bool("stamp_weather", false)) set("stamp_weather_fields", DEFAULT_WEATHER) else emptySet(),
        tempUnit = str("stamp_temp_unit", "both"),
        textSize = stampTextSize(str("stamp_size", "normal")),
    )

    /** Text height as a fraction of the frame's short side; kept small so the video stays clear. */
    private fun stampTextSize(v: String): Float = when (v) {
        "small" -> 0.036f
        "large" -> 0.054f
        "normal" -> 0.044f
        else -> v.toFloatOrNull()?.let { if (it < 0.05f) 0.036f else if (it > 0.06f) 0.054f else 0.044f } ?: 0.044f
    }

    val showPlace: Boolean get() = bool("stamp_place", false)
    val speedUnit: String get() = str("stamp_speed", "both")

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
        val DEFAULT_WEATHER = setOf("station", "temp", "rh", "wind", "clouds")
        val RESTART_KEYS = setOf("main_lens", "main_resolution", "cabin_resolution", "cabin_enabled",
            "link_role", "remote_camera")
        val LINK_KEYS = setOf("link_role", "link_device")
        val MIRRORED_KEYS = setOf("segment_minutes", "quality", "audio_enabled",
            "auto_stop_walk", "auto_stop_unplug", "auto_stop_minutes",
            "stamp_size", "stamp_clock", "use_utc", "overlay_label", "show_coords", "stamp_accuracy", "stamp_place",
            "stamp_speed", "stamp_heading", "stamp_altitude", "stamp_alt_unit", "stamp_sats", "stamp_weather",
            "stamp_weather_fields", "stamp_temp_unit")
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

/** What the burned-in stamp shows. Equal options -> identical layout. */
data class OverlayOptions(
    val camLabel: String,
    val userText: String = "",
    val showCoords: Boolean = true,
    val utc: Boolean = false,
    val clock12h: Boolean = false,
    /** Time zone after the time: "abbr" (CET), "offset" (UTC+1), "both" (CET (UTC+1)) or "none". */
    val zoneStyle: String = "abbr",
    val showAccuracy: Boolean = true,
    /** "both", "mph", "kmh" or "off". */
    val speedUnit: String = "both",
    val showHeading: Boolean = true,
    val showAltitude: Boolean = true,
    /** "ft" or "m" (also used for cloud bases). */
    val altUnit: String = "ft",
    val showSats: Boolean = true,
    val showPlace: Boolean = false,
    /** Weather fields from the nearest METAR; empty = no weather line. */
    val weather: Set<String> = emptySet(),
    /** "F", "C" or "both". */
    val tempUnit: String = "both",
    /** Text height as a fraction of the frame's short side. */
    val textSize: Float = 0.044f,
)
