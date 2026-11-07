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
import org.json.JSONObject
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

    // ---- remote camera: stamp data relayed from the main phone once per second

    private const val RELAY_FRESH_MS = 3_000L
    @Volatile private var relay: Sample? = null
    @Volatile private var relayElapsed = 0L

    /** The main phone's live stamp data, if it arrived in the last few seconds. */
    fun relayed(): Sample? = relay?.takeIf { SystemClock.elapsedRealtime() - relayElapsed <= RELAY_FRESH_MS }

    /** On the main phone: what the remote camera should stamp. */
    fun relayJson(): JSONObject {
        val v = localSample()
        val o = JSONObject()
        fun put(k: String, x: Any?) { if (x != null) o.put(k, x) }
        put("lat", v.lat); put("lon", v.lon); put("acc", v.accM); put("mps", v.mps?.toDouble())
        put("brg", v.bearing?.toDouble()); put("alt", v.altFt); o.put("su", v.satsUsed); o.put("sv", v.satsVisible)
        put("place", v.place); put("stn", v.station); put("qnh", v.qnhHpa?.toDouble())
        val w = v.wx
        val wx = JSONObject()
        fun putW(k: String, x: Any?) { if (x != null) wx.put(k, x) }
        putW("t", w.tempC?.toDouble()); putW("d", w.dewC?.toDouble()); putW("wd", w.windDir); putW("ws", w.windKt)
        putW("wg", w.gustKt); putW("vis", w.visMi?.toDouble()); wx.put("visp", w.visPlus); putW("cov", w.cloudCover)
        putW("base", w.cloudBaseFt); putW("wx", w.wx)
        o.put("w", wx)
        Altimeter.metar?.let { o.put("mlat", it.lat).put("mlon", it.lon).put("mobs", it.obsEpochSec) }
        return o
    }

    /** On the remote camera: stamp data from the main phone arrived. */
    fun onRelay(o: JSONObject) {
        fun d(k: String) = if (o.has(k) && !o.isNull(k)) o.optDouble(k).takeIf { !it.isNaN() } else null
        fun i(k: String) = if (o.has(k) && !o.isNull(k)) o.optInt(k) else null
        fun str(k: String) = if (o.has(k) && !o.isNull(k)) o.optString(k) else null
        val w = o.optJSONObject("w") ?: JSONObject()
        fun wd(k: String) = if (w.has(k) && !w.isNull(k)) w.optDouble(k).takeIf { !it.isNaN() }?.toFloat() else null
        fun wi(k: String) = if (w.has(k) && !w.isNull(k)) w.optInt(k) else null
        fun ws(k: String) = if (w.has(k) && !w.isNull(k)) w.optString(k) else null
        val wx = Altimeter.Weather(wd("t"), wd("d"), wi("wd"), wi("ws"), wi("wg"), wd("vis"), w.optBoolean("visp"),
            ws("cov"), wi("base"), ws("wx"))
        val s = Sample(d("lat"), d("lon"), i("acc"), d("mps")?.toFloat(), d("brg")?.toFloat(), i("alt"),
            o.optInt("su"), o.optInt("sv"), str("place"), str("stn"), d("qnh")?.toFloat(), wx)
        relay = s; relayElapsed = SystemClock.elapsedRealtime()
        // Keep the weather and QNH for when the link drops (this phone may have no internet).
        val stn = s.station; val q = s.qnhHpa
        if (stn != null && q != null) Altimeter.adoptRelayed(stn, q, d("mlat") ?: s.lat ?: 0.0, d("mlon") ?: s.lon ?: 0.0,
            o.optLong("mobs"), wx)
        version++
    }

    /** Latest fix if it is recent enough to trust, else null. */
    fun freshFix(): Location? {
        val l = location ?: return null
        val ageMs = (SystemClock.elapsedRealtimeNanos() - l.elapsedRealtimeNanos) / 1_000_000
        return if (ageMs <= STALE_MS) l else null
    }

    /** Ground speed in m/s from a fresh fix, null if unknown. Tiny GPS jitter is shown as 0. */
    fun speedMps(): Float? {
        relayed()?.let { r -> return r.mps }
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
        val place: String? = null,
        val station: String? = null, val qnhHpa: Float? = null,
        val wx: Altimeter.Weather = Altimeter.Weather(),
    )

    /** Position for clip metadata: relayed from the main phone, else this phone's GPS. */
    fun latLon(): Pair<Double, Double>? {
        relayed()?.let { r -> if (r.lat != null && r.lon != null) return r.lat to r.lon }
        return freshFix()?.let { it.latitude to it.longitude }
    }

    private fun currentSample(): Sample = relayed() ?: localSample()

    private fun localSample(): Sample {
        val fix = freshFix()
        val mps = speedMps()
        val m = Altimeter.metar
        return Sample(
            lat = fix?.latitude, lon = fix?.longitude,
            accM = fix?.takeIf { it.hasAccuracy() }?.accuracy?.roundToInt(),
            mps = mps,
            bearing = fix?.takeIf { it.hasBearing() && (mps ?: 0f) > 1f }?.bearing,
            altFt = altitudeFeet()?.roundToInt(),
            satsUsed = satsUsed, satsVisible = satsVisible,
            place = PlaceName.name,
            station = m?.station, qnhHpa = m?.altimHpa, wx = m?.weather ?: Altimeter.Weather(),
        )
    }

    private const val PLACE_W = 32
    private const val WX_W = 8
    private val WEATHER_ORDER = listOf("station", "temp", "dew", "rh", "wind", "vis", "clouds", "wx", "qnh")

    /** Widest possible values, used once to size the stamp so it never changes shape. */
    private val WIDEST = Sample(-89.999999, -179.999999, 999, 99f, 359f, -9999, 99, 99,
        place = "W".repeat(PLACE_W), station = "WWWW", qnhHpa = 1050f,
        wx = Altimeter.Weather(-40f, -40f, 360, 99, 99, 10f, true, "OVC", 99999, "W".repeat(WX_W)))

    /** Lines burned into the video (how many depends on the options and the frame's shape). */
    fun overlayLines(o: OverlayOptions, portrait: Boolean, wallMs: Long = TrueTime.nowMs(),
                     maxChars: Int = Int.MAX_VALUE): List<String> =
        wrap(format(o, portrait, currentSample(), wallMs), maxChars)

    /** Same layout filled with the widest possible values. */
    fun overlayTemplate(o: OverlayOptions, portrait: Boolean, maxChars: Int = Int.MAX_VALUE): List<String> =
        wrap(format(o, portrait, WIDEST, TrueTime.nowMs()), maxChars)

    /**
     * Lay out groups of fields as lines of at most [maxChars] characters, breaking only between
     * whole fields. Fields have fixed widths, so the breaks never move from second to second.
     */
    private fun wrap(groups: List<List<String>>, maxChars: Int): List<String> {
        val out = ArrayList<String>()
        for (fields in groups) {
            var line = ""
            for (f in fields) {
                line = when {
                    line.isEmpty() -> f
                    line.length + 2 + f.length <= maxChars -> "$line  $f"
                    else -> { out.add(line); f }
                }
            }
            if (line.isNotEmpty()) out.add(line)
        }
        return out
    }

    /** Fit [s] to exactly [w] characters so a field never changes width. */
    private fun fit(s: String, w: Int): String = if (s.length > w) s.take(w - 1) + "…" else s.padEnd(w)

    private fun n3(x: Int?): String = x?.let { String.format(Locale.US, "%3d", it) } ?: "---"

    /**
     * Every enabled field has a fixed width (monospace font), so the text never shifts or changes
     * length from one second to the next, with or without GPS or a METAR.
     */
    /** The stamp as groups of fixed-width fields; [wrap] turns them into lines. */
    private fun format(o: OverlayOptions, portrait: Boolean, v: Sample, wallMs: Long): List<List<String>> {
        val pattern = if (o.clock12h) "yyyy-MM-dd  hh:mm:ss a z" else "yyyy-MM-dd  HH:mm:ss z"
        val fmt = SimpleDateFormat(pattern, Locale.US).apply {
            timeZone = if (o.utc) TimeZone.getTimeZone("UTC") else TimeZone.getDefault()
        }
        val time = buildString {
            append(fmt.format(Date(wallMs)))
            append("   ").append(o.camLabel)
            if (o.userText.isNotEmpty()) append("   ").append(o.userText)
        }
        val place: String? = if (o.showPlace) fit(v.place ?: "", PLACE_W) else null

        var position = ""
        if (o.showCoords) {
            val lat = v.lat?.let { String.format(Locale.US, "%9.6f°%s", abs(it), if (it >= 0) "N" else "S") } ?: "--.------° "
            val lon = v.lon?.let { String.format(Locale.US, "%10.6f°%s", abs(it), if (it >= 0) "E" else "W") } ?: "---.------° "
            val acc = if (!o.showAccuracy) "" else " " + (v.accM?.let { "±${it.coerceAtMost(999)}m" } ?: "±---m").padEnd(6)
            val full = "$lat $lon$acc"
            // Same width as a real position, so the stamp keeps its shape when the fix drops.
            position = if (v.lat == null) fit("no GPS fix (${v.satsUsed}/${v.satsVisible} sats)", full.length) else full
        }

        val motion = ArrayList<String>()
        val mph: Int? = v.mps?.let { mph(it).roundToInt().coerceAtMost(999) }
        val kph: Int? = v.mps?.let { kph(it).roundToInt().coerceAtMost(999) }
        when (o.speedUnit) {
            "mph" -> motion.add("${n3(mph)} mph")
            "kmh" -> motion.add("${n3(kph)} km/h")
            "off" -> {}
            else -> motion.add("${n3(mph)} mph ${n3(kph)} km/h")
        }
        if (o.showHeading) {
            val b = v.bearing
            motion.add(if (b != null) String.format(Locale.US, "%-3s %3d°", cardinal(b), b.roundToInt() % 360) else "        ")
        }
        if (o.showAltitude) {
            val ft = v.altFt
            motion.add(when {
                o.altUnit == "m" && ft != null -> String.format(Locale.US, "alt %5dm", (ft * 0.3048).roundToInt().coerceIn(-9999, 99999))
                o.altUnit == "m" -> "alt -----m"
                ft != null -> String.format(Locale.US, "alt %5dft", ft.coerceIn(-9999, 99999))
                else -> "alt -----ft"
            })
        }
        if (o.showSats) motion.add(String.format(Locale.US, "%2d sats", v.satsUsed.coerceAtMost(99)))

        val weather = weatherFields(o, v)

        // Groups always start on a new line; within a group, fields wrap as space allows.
        val groups = ArrayList<List<String>>()
        groups.add(listOfNotNull(time, place))
        if (portrait) {
            if (position.isNotEmpty()) groups.add(listOf(position))
            if (motion.isNotEmpty()) groups.add(motion)
        } else {
            val parts = ArrayList<String>()
            if (position.isNotEmpty()) parts.add(position)
            parts.addAll(motion)
            if (parts.isNotEmpty()) groups.add(parts)
        }
        if (weather.isNotEmpty()) groups.add(weather)
        return groups
    }

    private fun temp(o: OverlayOptions, c: Float?): String {
        val cs = if (c != null) String.format(Locale.US, "%3d°C", c.roundToInt()) else " --°C"
        val fs = if (c != null) String.format(Locale.US, "%3d°F", (c * 9 / 5 + 32).roundToInt()) else " --°F"
        return when (o.tempUnit) { "C" -> cs; "F" -> fs; else -> "$cs $fs" }
    }

    private fun wind(w: Altimeter.Weather): String {
        val kt = w.windKt ?: return "wind --"
        if (kt == 0) return "wind calm"
        val dir = w.windDir
        val d = if (dir == null || dir < 0) "VRB" else String.format(Locale.US, "%03d°", dir)
        val g = w.gustKt?.let { " G${it.coerceAtMost(99)}" } ?: ""
        // The whole field is padded to a fixed width by fit(), so no padding inside it.
        return "wind $d/${kt.coerceAtMost(99)}kt$g"
    }

    private fun vis(o: OverlayOptions, w: Altimeter.Weather): String {
        val mi = w.visMi ?: return "vis --"
        val plus = if (w.visPlus) "+" else ""
        return if (o.altUnit == "m" || o.speedUnit == "kmh") {
            val km = mi * 1.609f
            "vis " + (if (km >= 10) String.format(Locale.US, "%.0f", km) else String.format(Locale.US, "%.1f", km)) + plus + "km"
        } else {
            "vis " + (if (mi >= 3) String.format(Locale.US, "%.0f", mi) else String.format(Locale.US, "%.1f", mi)) + plus + "mi"
        }
    }

    private fun clouds(o: OverlayOptions, w: Altimeter.Weather): String {
        val c = w.cloudCover ?: return "sky --"
        if (c in setOf("CLR", "SKC", "NSC", "NCD", "CAVOK")) return "clear sky"
        val base = w.cloudBaseFt ?: return c
        return if (o.altUnit == "m") "$c ${(base * 0.3048).roundToInt()}m" else "$c ${base}ft"
    }

    private fun qnh(o: OverlayOptions, hpa: Float?): String = when {
        o.altUnit == "m" && hpa != null -> String.format(Locale.US, "Q%4d", hpa.roundToInt())
        o.altUnit == "m" -> "Q----"
        hpa != null -> String.format(Locale.US, "A%5.2f", hpa / 33.8639f)
        else -> "A--.--"
    }

    /** Selected METAR details, each at a fixed width; dashes until a report arrives. */
    private fun weatherFields(o: OverlayOptions, v: Sample): List<String> {
        if (o.weather.isEmpty()) return emptyList()
        val w = v.wx
        val parts = ArrayList<String>()
        for (f in WEATHER_ORDER) {
            if (f !in o.weather) continue
            parts.add(when (f) {
                "station" -> fit(v.station ?: "----", 4)
                "temp" -> temp(o, w.tempC)
                "dew" -> "dew " + temp(o, w.dewC)
                "rh" -> w.rhPercent?.let { String.format(Locale.US, "RH %3d%%", it) } ?: "RH  --%"
                "wind" -> fit(wind(w), 18)
                "vis" -> fit(vis(o, w), 10)
                "clouds" -> fit(clouds(o, w), 12)
                "wx" -> fit(w.wx ?: "", WX_W)
                "qnh" -> qnh(o, v.qnhHpa)
                else -> ""
            })
        }
        return parts
    }
}
