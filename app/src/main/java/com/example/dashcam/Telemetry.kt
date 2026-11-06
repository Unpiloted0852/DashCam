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

    /** Lines burned into the video: 2 for landscape frames, 3 for narrow portrait frames. */
    fun overlayLines(o: OverlayOptions, portrait: Boolean): List<String> {
        val fmt = SimpleDateFormat("yyyy-MM-dd  HH:mm:ss z", Locale.US).apply {
            timeZone = if (o.utc) TimeZone.getTimeZone("UTC") else TimeZone.getDefault()
        }
        val line1 = buildString {
            append(fmt.format(Date()))
            append("   ").append(o.camLabel)
            if (o.userText.isNotEmpty()) append("   ").append(o.userText)
        }
        val fix = freshFix()
        if (fix == null) {
            val nofix = "GPS: no fix ($satsUsed/$satsVisible sats)"
            val spd = "--- mph  --- km/h"
            return if (portrait) listOf(line1, nofix, spd) else listOf(line1, "$nofix   $spd")
        }
        val coords = if (!o.showCoords) "" else buildString {
            append(String.format(Locale.US, "%.6f°%s %.6f°%s",
                abs(fix.latitude), if (fix.latitude >= 0) "N" else "S",
                abs(fix.longitude), if (fix.longitude >= 0) "E" else "W"))
            if (fix.hasAccuracy()) append(" ±").append(fix.accuracy.roundToInt()).append("m")
        }
        val motion = buildString {
            val mps = speedMps()
            if (mps != null) {
                append(String.format(Locale.US, "%3d mph  %3d km/h", mph(mps).roundToInt(), kph(mps).roundToInt()))
            } else append("--- mph  --- km/h")
            if (fix.hasBearing() && (mps ?: 0f) > 1f) {
                append("   ").append(cardinal(fix.bearing)).append(' ').append(fix.bearing.roundToInt()).append('°')
            }
            altitudeText()?.let { append("   ").append(it) }
            append("   ").append(satsUsed).append(" sats")
        }
        return when {
            coords.isEmpty() -> listOf(line1, motion)
            portrait -> listOf(line1, coords, motion)
            else -> listOf(line1, "$coords   $motion")
        }
    }
}
