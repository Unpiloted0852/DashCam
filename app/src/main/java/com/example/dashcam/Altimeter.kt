package com.example.dashcam

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.os.SystemClock
import android.util.Log
import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import kotlin.math.pow

/**
 * Barometric altitude: the phone's pressure sensor (station pressure) corrected with the altimeter
 * setting (QNH) from the nearest METAR, the same way an aircraft altimeter works:
 *   alt_ft = 145366.45 * (1 - (P / QNH)^0.190284)
 * Without a METAR yet (no signal) it falls back to standard pressure, 1013.25 hPa.
 */
object Altimeter : SensorEventListener {
    private const val TAG = "Altimeter"
    private const val STD_HPA = 1013.25f
    private const val REFRESH_MS = 15 * 60_000L       // METARs are hourly, specials (SPECI) in between
    private const val RETRY_MS = 2 * 60_000L
    private const val MOVE_REFETCH_M = 25_000f       // nearest station may have changed

    data class Metar(val station: String, val altimHpa: Float, val lat: Double, val lon: Double,
                     val obsEpochSec: Long, val fetchedElapsed: Long, val fetchedAt: Location)

    @Volatile var pressureHpa: Float? = null; private set
    @Volatile var metar: Metar? = null; private set
    val hasSensor: Boolean get() = sensorPresent

    private var sensorPresent = false
    private var sm: SensorManager? = null
    private var exec: ScheduledExecutorService? = null
    private var lastAttempt = 0L

    fun start(ctx: Context) {
        if (sm != null) return
        val m = ctx.getSystemService(SensorManager::class.java) ?: return
        sm = m
        val s = m.getDefaultSensor(Sensor.TYPE_PRESSURE)
        sensorPresent = s != null
        if (s != null) m.registerListener(this, s, SensorManager.SENSOR_DELAY_NORMAL)
        else Log.i(TAG, "no barometer; GPS altitude will be used")
        exec = Executors.newSingleThreadScheduledExecutor().also {
            it.scheduleWithFixedDelay({ maybeFetch() }, 3, 15, TimeUnit.SECONDS)
        }
    }

    fun stop() {
        sm?.unregisterListener(this); sm = null
        exec?.shutdownNow(); exec = null
    }

    override fun onSensorChanged(e: SensorEvent) {
        val v = e.values[0]
        val prev = pressureHpa
        // Light smoothing: cabin airflow / HVAC makes the raw reading jitter.
        pressureHpa = if (prev == null) v else prev + 0.1f * (v - prev)
    }

    override fun onAccuracyChanged(s: Sensor?, a: Int) {}

    /** Altitude in feet, or null if there's no barometer reading. */
    fun altitudeFt(): Float? {
        val p = pressureHpa ?: return null
        val qnh = metar?.altimHpa ?: STD_HPA
        return (145366.45 * (1.0 - (p / qnh).toDouble().pow(0.190284))).toFloat()
    }

    /** e.g. "KNYC 29.87" or "std" when no METAR. */
    fun sourceLabel(): String = metar?.let {
        String.format(Locale.US, "%s %.2f", it.station, it.altimHpa / 33.8639f)
    } ?: "std 29.92"

    private fun maybeFetch() {
        try {
            val loc = Telemetry.location ?: return
            val now = SystemClock.elapsedRealtime()
            val m = metar
            val due = m == null || now - m.fetchedElapsed > REFRESH_MS || loc.distanceTo(m.fetchedAt) > MOVE_REFETCH_M
            if (!due || now - lastAttempt < RETRY_MS) return
            lastAttempt = now
            val found = fetchNearest(loc, 1.0) ?: fetchNearest(loc, 2.5)
            if (found != null) {
                val prev = metar
                metar = found
                Log.i(TAG, "QNH ${found.altimHpa} hPa from ${found.station}")
                if (prev?.station != found.station || prev.obsEpochSec != found.obsEpochSec) {
                    EventLog.log("METAR", String.format(Locale.US, "%s QNH %.1f hPa, obs %d min ago",
                        found.station, found.altimHpa, (TrueTime.nowMs() / 1000 - found.obsEpochSec) / 60))
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "METAR fetch failed: ${e.message}")
        }
    }

    private fun fetchNearest(loc: Location, deg: Double): Metar? {
        val bbox = String.format(Locale.US, "%.3f,%.3f,%.3f,%.3f",
            loc.latitude - deg, loc.longitude - deg, loc.latitude + deg, loc.longitude + deg)
        val conn = URL("https://aviationweather.gov/api/data/metar?bbox=$bbox&format=json&hours=3")
            .openConnection() as HttpURLConnection
        conn.connectTimeout = 10_000; conn.readTimeout = 15_000
        conn.setRequestProperty("User-Agent", "DashCam-Android")
        try {
            if (conn.responseCode != 200) return null
            val arr = JSONArray(conn.inputStream.bufferedReader().readText())
            val nowSec = TrueTime.nowMs() / 1000
            // Newest report per station (the query can return several, e.g. a METAR and a later SPECI).
            val newest = HashMap<String, org.json.JSONObject>()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                if (!o.has("altim") || o.isNull("altim")) continue
                val id = o.optString("icaoId")
                if (id.isEmpty()) continue
                val cur = newest[id]
                if (cur == null || o.optLong("obsTime") > cur.optLong("obsTime")) newest[id] = o
            }
            var best: Metar? = null
            var bestDist = Float.MAX_VALUE
            for ((id, o) in newest) {
                val obs = o.optLong("obsTime", 0)
                if (obs > 0 && nowSec - obs > 3 * 3600) continue   // ignore stale reports
                val lat = o.optDouble("lat"); val lon = o.optDouble("lon")
                val d = FloatArray(1)
                Location.distanceBetween(loc.latitude, loc.longitude, lat, lon, d)
                if (d[0] < bestDist) {
                    bestDist = d[0]
                    best = Metar(id, o.getDouble("altim").toFloat(), lat, lon, obs,
                        SystemClock.elapsedRealtime(), Location(loc))
                }
            }
            return best
        } finally {
            conn.disconnect()
        }
    }
}
