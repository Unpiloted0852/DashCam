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
        val place: String? = null,
        val station: String? = null, val qnhHpa: Float? = null,
        val wx: Altimeter.Weather = Altimeter.Weather(),
    )

    private fun currentSample(): Sample {
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

    /** Longest place name shown; longer names are shortened with "…". */
    private const val PLACE_MAX = 34
    private val WEATHER_ORDER = listOf("station", "temp", "dew", "rh", "wind", "vis", "clouds", "wx", "qnh")

    /**
     * Realistic long values (not extremes). Line breaks are decided once from these, so the number of
     * lines never changes while driving; a live line that ends up longer is narrowed slightly instead.
     */
    private val WIDEST = Sample(-48.88888, -128.88888, 18, 36f /* 80 mph / 130 km/h */, 338f /* "NNW 338°" */,
        8888, 38, 38,
        place = "n".repeat(PLACE_MAX), station = "WWWW", qnhHpa = 1028.8f,
        wx = Altimeter.Weather(-18f, -18f, 250, 28, 38, 10f, true, "BKN", 12000, "-SHRA"))

    /** One item on the stamp. [right] = aligned to the right edge of its line (camera name). */
    data class Field(val text: String, val right: Boolean = false)

    /** The stamp as groups of fields; each group starts a new line and wraps if needed. */
    fun stampFields(o: OverlayOptions, portrait: Boolean, wallMs: Long = TrueTime.nowMs()): List<List<Field>> =
        build(o, portrait, currentSample(), wallMs, widest = false)

    /** Same structure with the widest values, for reserving space. Always the same shape as [stampFields]. */
    fun stampTemplate(o: OverlayOptions, portrait: Boolean): List<List<Field>> =
        build(o, portrait, templateSample(), TrueTime.nowMs(), widest = true)

    /**
     * Number of whole-degree digits in the current latitude/longitude (e.g. 2 and 1 for 50.9°N 6.0°E).
     * The layout plans for these, so coordinates don't reserve room for "128°W" in Europe.
     */
    fun coordDigits(): Pair<Int, Int>? = freshFix()?.let {
        abs(it.latitude).toInt().toString().length to abs(it.longitude).toInt().toString().length
    }

    private fun templateSample(): Sample {
        val d = coordDigits() ?: return WIDEST
        fun eights(digits: Int) = ("8".repeat(digits) + ".88888").toDouble()
        return Sample(-eights(d.first), -eights(d.second), WIDEST.accM, WIDEST.mps, WIDEST.bearing, WIDEST.altFt,
            WIDEST.satsUsed, WIDEST.satsVisible, WIDEST.place, WIDEST.station, WIDEST.qnhHpa, WIDEST.wx)
    }

    /** Plain text lines (for logs and tests). */
    fun overlayLines(o: OverlayOptions, portrait: Boolean, wallMs: Long = TrueTime.nowMs()): List<String> =
        stampFields(o, portrait, wallMs).map { g -> g.joinToString("  ") { it.text } }

    private fun shorten(s: String, max: Int) = if (s.length > max) s.take(max - 1).trimEnd() + "…" else s

    /** Zone abbreviation (CET, EST, PDT…), using common English names rather than "GMT+01:00". */
    private fun abbr(tz: TimeZone, daylight: Boolean): String {
        // British English knows European names (CET), US English the American ones (EST, PDT).
        val names = listOf(Locale.UK, Locale.US, Locale.getDefault()).map { tz.getDisplayName(daylight, TimeZone.SHORT, it) }
        return names.firstOrNull { !it.startsWith("GMT") } ?: names.first()
    }

    /** Offset from UTC, e.g. "UTC+1", "UTC-5", "UTC+5:30", "UTC". */
    private fun offset(tz: TimeZone, wallMs: Long): String {
        val mins = tz.getOffset(wallMs) / 60_000
        if (mins == 0) return "UTC"
        val sign = if (mins > 0) "+" else "-"
        val h = kotlin.math.abs(mins) / 60; val m = kotlin.math.abs(mins) % 60
        return "UTC$sign$h" + if (m != 0) String.format(Locale.US, ":%02d", m) else ""
    }

    /** The time-zone text after the clock, per the user's choice. */
    private fun zoneText(o: OverlayOptions, tz: TimeZone, wallMs: Long, widest: Boolean): String {
        if (o.utc) return "UTC"
        val a = if (widest) maxOf(abbr(tz, false), abbr(tz, true), compareBy { it.length })
                else abbr(tz, tz.inDaylightTime(Date(wallMs)))
        val off = if (widest) {
            // Widest of the standard and summer offsets.
            val y = java.util.Calendar.getInstance(tz).get(java.util.Calendar.YEAR)
            listOf(java.util.GregorianCalendar(y, 0, 15), java.util.GregorianCalendar(y, 6, 15))
                .map { it.timeZone = tz; offset(tz, it.timeInMillis) }.maxBy { it.length }
        } else offset(tz, wallMs)
        return when (o.zoneStyle) {
            "none" -> ""
            "offset" -> off
            "both" -> if (a == off || a.startsWith("GMT")) off else "$a ($off)"
            else -> if (a.startsWith("GMT")) off else a   // no common abbreviation: show the offset
        }
    }

    private fun build(o: OverlayOptions, portrait: Boolean, v: Sample, wallMs: Long, widest: Boolean = false): List<List<Field>> {
        val tz = if (o.utc) TimeZone.getTimeZone("UTC") else TimeZone.getDefault()
        val fmt = SimpleDateFormat(if (o.clock12h) "yyyy-MM-dd h:mm:ss a" else "yyyy-MM-dd HH:mm:ss", Locale.US)
            .apply { timeZone = tz }
        val clock = if (widest) "2088-12-28 " + (if (o.clock12h) "12:58:58 PM" else "23:58:58")
                    else fmt.format(Date(wallMs))
        val dateTime = Field(listOf(clock, zoneText(o, tz, wallMs, widest = widest)).filter { it.isNotEmpty() }.joinToString(" "))
        val label = Field(listOf(o.camLabel, o.userText).filter { it.isNotEmpty() }.joinToString("  "), right = true)
        val place = if (o.showPlace) Field(shorten(v.place ?: "", PLACE_MAX)) else null

        val position = if (!o.showCoords) null else Field(
            if (v.lat == null || v.lon == null) "No GPS fix (${v.satsUsed}/${v.satsVisible} sats)"
            else buildString {
                append(String.format(Locale.US, "%.5f°%s %.5f°%s",
                    abs(v.lat), if (v.lat >= 0) "N" else "S", abs(v.lon), if (v.lon >= 0) "E" else "W"))
                if (o.showAccuracy && v.accM != null) append("  ±").append(v.accM.coerceAtMost(999)).append(" m")
            })

        val motion = ArrayList<Field>()
        val mph = v.mps?.let { mph(it).roundToInt().coerceAtMost(999).toString() } ?: "–"
        val kph = v.mps?.let { kph(it).roundToInt().coerceAtMost(999).toString() } ?: "–"
        when (o.speedUnit) {
            "mph" -> motion.add(Field("$mph mph"))
            "kmh" -> motion.add(Field("$kph km/h"))
            "off" -> {}
            else -> motion.add(Field("$mph mph · $kph km/h"))
        }
        if (o.showHeading) {
            motion.add(Field(v.bearing?.let { "${cardinal(it)} ${String.format(Locale.US, "%03d", it.roundToInt() % 360)}°" } ?: ""))
        }
        if (o.showAltitude) {
            motion.add(Field(when {
                v.altFt == null -> "Alt –"
                o.altUnit == "m" -> "Alt ${(v.altFt * 0.3048).roundToInt()} m"
                else -> "Alt ${v.altFt} ft"
            }))
        }
        if (o.showSats) motion.add(Field("${v.satsUsed.coerceAtMost(99)} sats"))

        val weather = weatherFields(o, v)

        val groups = ArrayList<List<Field>>()
        if (portrait) {
            groups.add(listOf(dateTime, label))
            if (place != null) groups.add(listOf(place))
        } else {
            groups.add(listOfNotNull(dateTime, place, label))
        }
        // Position and motion share a group so they can sit on one line when there's room.
        val pm = ArrayList<Field>()
        position?.let { pm.add(it) }
        pm.addAll(motion)
        if (pm.isNotEmpty()) groups.add(pm)
        if (weather.isNotEmpty()) groups.add(weather)
        return groups
    }

    private fun temp(o: OverlayOptions, c: Float?): String {
        if (c == null) return when (o.tempUnit) { "C" -> "–°C"; "F" -> "–°F"; else -> "–°C / –°F" }
        val cs = "${c.roundToInt()}°C"
        val fs = "${(c * 9 / 5 + 32).roundToInt()}°F"
        return when (o.tempUnit) { "C" -> cs; "F" -> fs; else -> "$cs / $fs" }
    }

    private fun wind(w: Altimeter.Weather): String {
        val kt = w.windKt ?: return "Wind –"
        if (kt == 0) return "Wind calm"
        val dir = w.windDir
        val d = if (dir == null || dir < 0) "VRB" else String.format(Locale.US, "%03d°", dir)
        val g = w.gustKt?.let { " G${it.coerceAtMost(99)}" } ?: ""
        return "Wind $d ${kt.coerceAtMost(99)}$g kt"
    }

    private fun vis(o: OverlayOptions, w: Altimeter.Weather): String {
        val mi = w.visMi ?: return "Vis –"
        val plus = if (w.visPlus) "+" else ""
        return if (o.altUnit == "m" || o.speedUnit == "kmh") {
            val km = mi * 1.609f
            "Vis " + (if (km >= 10) String.format(Locale.US, "%.0f", km) else String.format(Locale.US, "%.1f", km)) + plus + " km"
        } else {
            "Vis " + (if (mi >= 3) String.format(Locale.US, "%.0f", mi) else String.format(Locale.US, "%.1f", mi)) + plus + " mi"
        }
    }

    private fun clouds(o: OverlayOptions, w: Altimeter.Weather): String {
        val c = w.cloudCover ?: return "Sky –"
        if (c in setOf("CLR", "SKC", "NSC", "NCD", "CAVOK")) return "Clear sky"
        val base = w.cloudBaseFt ?: return c
        return if (o.altUnit == "m") "$c ${(base * 0.3048).roundToInt()} m" else "$c $base ft"
    }

    private fun qnh(o: OverlayOptions, hpa: Float?): String = when {
        hpa == null -> "QNH –"
        o.altUnit == "m" -> "QNH ${hpa.roundToInt()}"
        else -> String.format(Locale.US, "QNH %.2f", hpa / 33.8639f)
    }

    /** Selected METAR details; a dash until a report arrives. */
    private fun weatherFields(o: OverlayOptions, v: Sample): List<Field> {
        if (o.weather.isEmpty()) return emptyList()
        val w = v.wx
        val out = ArrayList<Field>()
        for (f in WEATHER_ORDER) {
            if (f !in o.weather) continue
            out.add(Field(when (f) {
                "station" -> v.station ?: "––––"
                "temp" -> temp(o, w.tempC)
                "dew" -> "Dew " + temp(o, w.dewC)
                "rh" -> "RH " + (w.rhPercent?.let { "$it%" } ?: "–")
                "wind" -> wind(w)
                "vis" -> vis(o, w)
                "clouds" -> clouds(o, w)
                "wx" -> w.wx ?: ""
                "qnh" -> qnh(o, v.qnhHpa)
                else -> ""
            }))
        }
        return out
    }
}
