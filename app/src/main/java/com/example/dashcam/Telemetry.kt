package com.example.dashcam

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.GnssStatus
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.math.abs
import kotlin.math.roundToInt

/** Process-wide GPS state shared by the overlay renderers and the UI. */
object Telemetry {
    private const val TAG = "Telemetry"
    private const val STALE_MS = 5_000L

    @Volatile var location: Location? = null; private set
    @Volatile var satsUsed = 0; private set
    @Volatile var satsVisible = 0; private set
    /** Bumped on each update so renderers know to redraw. */
    @Volatile var version = 0; private set

    private var lm: LocationManager? = null

    private val listener = LocationListener { loc ->
        location = loc
        TrueTime.onGpsFix(loc)
        version++
    }

    private val gnssCallback = object : GnssStatus.Callback() {
        override fun onSatelliteStatusChanged(status: GnssStatus) {
            var used = 0
            for (i in 0 until status.satelliteCount) if (status.usedInFix(i)) used++
            satsUsed = used
            satsVisible = status.satelliteCount
        }
    }

    @SuppressLint("MissingPermission")
    fun start(ctx: Context) {
        if (lm != null) return
        if (ctx.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            Log.w(TAG, "no fine location permission")
            return
        }
        val m = ctx.getSystemService(LocationManager::class.java) ?: return
        lm = m
        try {
            m.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, listener, Looper.getMainLooper())
            m.registerGnssStatusCallback(gnssCallback, Handler(Looper.getMainLooper()))
            m.getLastKnownLocation(LocationManager.GPS_PROVIDER)?.let { location = it }
        } catch (e: Exception) {
            Log.e(TAG, "location start failed", e)
        }
    }

    fun stop() {
        lm?.let {
            it.removeUpdates(listener)
            it.unregisterGnssStatusCallback(gnssCallback)
        }
        lm = null
    }

    /** Latest fix if it is recent enough to trust, else null. */
    fun freshFix(): Location? {
        val l = location ?: return null
        val ageMs = (SystemClock.elapsedRealtimeNanos() - l.elapsedRealtimeNanos) / 1_000_000
        return if (ageMs <= STALE_MS) l else null
    }

    /** Ground speed in m/s from a fresh fix, null if unknown. Tiny GPS jitter is shown as 0. */
    fun speedMps(): Float? {
        val l = freshFix() ?: return null
        if (!l.hasSpeed()) return null
        return if (l.speed < 0.4f) 0f else l.speed
    }

    fun mph(mps: Float) = mps * 2.2369363f
    fun kph(mps: Float) = mps * 3.6f

    /** Baro altitude (METAR-corrected) in feet if the phone has a barometer, else GPS altitude. */
    fun altitudeFeet(): Float? {
        Altimeter.altitudeFt()?.let { return it }
        val f = freshFix() ?: return null
        if (!f.hasAltitude()) return null
        val m = if (android.os.Build.VERSION.SDK_INT >= 34 && f.hasMslAltitude()) f.mslAltitudeMeters else f.altitude
        return (m * 3.28084).toFloat()
    }

    /** Baro altitude (METAR-corrected) if the phone has a barometer, else GPS altitude. */
    fun altitudeText(): String? {
        Altimeter.altitudeFt()?.let { return "alt ${it.roundToInt()}ft" }
        val f = freshFix() ?: return null
        if (!f.hasAltitude()) return null
        val m = if (android.os.Build.VERSION.SDK_INT >= 34 && f.hasMslAltitude()) f.mslAltitudeMeters else f.altitude
        return "gps alt ${(m * 3.28084).roundToInt()}ft"
    }

    fun cardinal(deg: Float): String {
        val names = arrayOf("N", "NNE", "NE", "ENE", "E", "ESE", "SE", "SSE", "S", "SSW", "SW", "WSW", "W", "WNW", "NW", "NNW")
        return names[(((deg % 360f) + 360f) % 360f / 22.5f).roundToInt() % 16]
    }

    /** Values shown on the stamp. A null field means "unknown" and is drawn as dashes. */
    class Sample(
        val lat: Double?, val lon: Double?, val accM: Int?,
        val mps: Float?, val bearing: Float?, val altFt: Int?,
        val satsUsed: Int, val satsVisible: Int,
    )

    private fun currentSample(): Sample {
        val fix = freshFix()
        val mps = speedMps()
        return Sample(
            lat = fix?.latitude, lon = fix?.longitude,
            accM = fix?.takeIf { it.hasAccuracy() }?.accuracy?.roundToInt(),
            mps = mps,
            bearing = fix?.takeIf { it.hasBearing() && (mps ?: 0f) > 1f }?.bearing,
            altFt = altitudeFeet()?.roundToInt(),
            satsUsed = satsUsed, satsVisible = satsVisible,
        )
    }

    /** Widest possible values, used once to size the stamp so it never changes shape. */
    private val WIDEST = Sample(-89.999999, -179.999999, 999, 99f, 359f, -9999, 99, 99)

    /** Lines burned into the video: 2 for landscape frames, 3 for narrow portrait frames. */
    fun overlayLines(o: OverlayOptions, portrait: Boolean, wallMs: Long = TrueTime.nowMs()): List<String> =
        format(o, portrait, currentSample(), wallMs)

    /** Same layout filled with the widest possible values. */
    fun overlayTemplate(o: OverlayOptions, portrait: Boolean): List<String> = format(o, portrait, WIDEST, TrueTime.nowMs())

    /**
     * Every field has a fixed width (monospace font), so the text never shifts or changes length
     * from one second to the next, with or without a GPS fix.
     */
    private fun format(o: OverlayOptions, portrait: Boolean, v: Sample, wallMs: Long): List<String> {
        val fmt = SimpleDateFormat("yyyy-MM-dd  HH:mm:ss z", Locale.US).apply {
            timeZone = if (o.utc) TimeZone.getTimeZone("UTC") else TimeZone.getDefault()
        }
        val line1 = buildString {
            append(fmt.format(Date(wallMs)))
            append("   ").append(o.camLabel)
            if (o.userText.isNotEmpty()) append("   ").append(o.userText)
        }
        val coords = if (!o.showCoords) "" else {
            val lat = v.lat?.let { String.format(Locale.US, "%9.6f°%s", abs(it), if (it >= 0) "N" else "S") }
                ?: "--.------° "
            val lon = v.lon?.let { String.format(Locale.US, "%10.6f°%s", abs(it), if (it >= 0) "E" else "W") }
                ?: "---.------° "
            val acc = v.accM?.let { "±${it.coerceAtMost(999)}m".padEnd(6) } ?: "±---m "
            val full = "$lat $lon $acc"
            // Same width as a real position, so the stamp keeps its shape when the fix drops.
            if (v.lat == null) "no GPS fix (${v.satsUsed}/${v.satsVisible} sats)".padEnd(full.length).take(full.length) else full
        }
        val motion = buildString {
            if (v.mps != null) {
                append(String.format(Locale.US, "%3d mph %3d km/h",
                    mph(v.mps).roundToInt().coerceAtMost(999), kph(v.mps).roundToInt().coerceAtMost(999)))
            } else append("--- mph --- km/h")
            append("  ")
            append(v.bearing?.let { String.format(Locale.US, "%-3s %3d°", cardinal(it), it.roundToInt() % 360) } ?: "        ")
            append("  ")
            append(v.altFt?.let { String.format(Locale.US, "alt %5dft", it.coerceIn(-9999, 99999)) } ?: "alt -----ft")
            append("  ")
            append(String.format(Locale.US, "%2d sats", v.satsUsed.coerceAtMost(99)))
        }
        return when {
            coords.isEmpty() -> listOf(line1, motion)
            portrait -> listOf(line1, coords, motion)
            else -> listOf(line1, "$coords  $motion")
        }
    }
}
