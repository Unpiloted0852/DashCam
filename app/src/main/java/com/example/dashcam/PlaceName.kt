package com.example.dashcam

import android.annotation.SuppressLint
import android.content.Context
import android.location.Geocoder
import android.location.Location
import android.location.LocationManager
import android.os.SystemClock
import java.io.File
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import kotlin.math.cos
import kotlin.math.roundToLong
import kotlin.math.sin

/**
 * Optional "where am I" for the stamp: road and town. Off unless enabled.
 *
 * - Road: matched every second against OpenStreetMap road shapes, using position *and* heading,
 *   the way navigation apps do ([RoadMatcher], [RoadTiles]). Road tiles stay on the phone, so roads
 *   driven before work offline.
 * - Town: Android's geocoder (Google), looked up every ~300 m and remembered on disk by ~30 m square.
 *   Its street is only used until the road tile for the area has downloaded.
 */
object PlaceName {
    @Volatile var name: String? = null; private set
    private var exec: ScheduledExecutorService? = null
    private var geocoder: Geocoder? = null

    private var currentRoad: RoadMatcher.Road? = null
    private var roadLabel: String? = null
    private var roadFromMap = false
    private var geoRoad: String? = null
    private var geoTown: String? = null
    private var geoAt: Location? = null
    private var geoElapsed = 0L

    private const val CACHE_MAX = 5000
    private const val SEP = '\u001F'
    /** Geocoder answers by ~30 m square: "road<SEP>town". */
    private val cache = object : LinkedHashMap<String, String>(256, 0.75f, true) {
        override fun removeEldestEntry(e: MutableMap.MutableEntry<String, String>?) = size > CACHE_MAX
    }
    private var cacheFile: File? = null
    private var cacheDirty = 0

    fun start(ctx: Context, enabled: () -> Boolean) {
        if (exec != null) return
        val app = ctx.applicationContext
        RoadTiles.init(app)
        geocoder = if (Geocoder.isPresent()) Geocoder(app, Locale.getDefault()) else null
        cacheFile = File(app.filesDir, "place_cache2.tsv")
        File(app.filesDir, "place_cache.tsv").delete()   // older format (street and town joined)
        exec = Executors.newSingleThreadScheduledExecutor().also {
            it.execute { loadCache(); if (enabled()) try { warmStart(app) } catch (_: Throwable) {} }
            it.scheduleWithFixedDelay({
                try { if (enabled()) refresh() } catch (_: Throwable) {}
            }, 1, 1, TimeUnit.SECONDS)
        }
    }

    fun stop() {
        exec?.let { e -> e.execute { saveCache() }; e.shutdown() }
        exec = null
    }

    /** Before the first GPS fix: name the place the phone last knew (usually where the car is parked). */
    @SuppressLint("MissingPermission")
    private fun warmStart(ctx: Context) {
        if (name != null || Telemetry.freshFix() != null) return
        val lm = ctx.getSystemService(LocationManager::class.java) ?: return
        val last = listOf(LocationManager.GPS_PROVIDER, LocationManager.FUSED_PROVIDER, LocationManager.NETWORK_PROVIDER)
            .mapNotNull { p -> try { lm.getLastKnownLocation(p) } catch (_: Exception) { null } }
            .maxByOrNull { it.elapsedRealtimeNanos } ?: return
        val ageMin = (SystemClock.elapsedRealtimeNanos() - last.elapsedRealtimeNanos) / 60_000_000_000L
        if (ageMin > 30) return
        update(last, bearing = null)
    }

    private fun refresh() {
        val loc = Telemetry.freshFix() ?: return
        val bearing = if (loc.hasBearing() && loc.hasSpeed() && loc.speed > 2.5f) loc.bearing else null
        update(loc, bearing)
    }

    private fun update(loc: Location, bearing: Float?) {
        // Road: map matching.
        val segs = RoadTiles.segmentsAround(loc.latitude, loc.longitude)
        if (bearing != null) {   // have the next tile ready before the car reaches it
            val ahead = 600.0
            val r = Math.toRadians(bearing.toDouble())
            RoadTiles.prefetch(loc.latitude + ahead * cos(r) / 111_320,
                loc.longitude + ahead * sin(r) / (111_320 * cos(Math.toRadians(loc.latitude)).coerceAtLeast(0.1)))
        }
        if (segs != null) {
            val m = RoadMatcher.match(segs, loc.latitude, loc.longitude, bearing,
                if (loc.hasAccuracy()) loc.accuracy else 20f, currentRoad)
            currentRoad = m?.road
            roadLabel = m?.road?.label
            roadFromMap = true
        } else roadFromMap = false

        // Town (and fallback street): geocoder, now and then.
        val prev = geoAt
        val moved = prev?.let { loc.distanceTo(it) } ?: Float.MAX_VALUE
        val geoDue = prev == null || moved > (if (roadFromMap) 300f else 150f) ||
            SystemClock.elapsedRealtime() - geoElapsed > 5 * 60_000
        if (geoDue) {
            geoAt = Location(loc); geoElapsed = SystemClock.elapsedRealtime()
            val found = lookup(loc)
            if (found != null) { geoRoad = found.first; geoTown = found.second }
            else if (moved != Float.MAX_VALUE && moved > 1000f) { geoRoad = null; geoTown = null }   // offline, far away
        }

        val road = if (roadFromMap) roadLabel else geoRoad
        name = listOfNotNull(road, geoTown).joinToString(", ").ifEmpty { null }
    }

    /** Cached geocoder answer (street, town) for this ~30 m square, else ask the geocoder. */
    @Suppress("DEPRECATION")
    private fun lookup(loc: Location): Pair<String?, String?>? {
        val g = geocoder ?: return null
        val key = key(loc.latitude, loc.longitude)
        synchronized(cache) { cache[key] }?.let { v -> val i = v.indexOf(SEP); return v.substring(0, i).ifEmpty { null } to v.substring(i + 1).ifEmpty { null } }
        val a = try { g.getFromLocation(loc.latitude, loc.longitude, 1)?.firstOrNull() } catch (_: Exception) { null } ?: return null
        val road = a.thoroughfare
        val town = a.locality ?: a.subAdminArea ?: a.adminArea
        if (road == null && town == null) return null
        synchronized(cache) { cache[key] = (road ?: "") + SEP + (town ?: "") }
        if (++cacheDirty >= 20) saveCache()
        return road to town
    }

    /** ~30 m grid square (longitude steps widen toward the equator to stay ~square). */
    internal fun key(lat: Double, lon: Double): String {
        val la = (lat * 3000).roundToLong()
        val lonScale = 3000 * cos(Math.toRadians(lat)).coerceAtLeast(0.1)
        val lo = (lon * lonScale).roundToLong()
        return "$la,$lo"
    }

    private fun loadCache() {
        val f = cacheFile ?: return
        try {
            f.forEachLine { line ->
                val i = line.indexOf('\t')
                if (i > 0) synchronized(cache) { cache[line.substring(0, i)] = line.substring(i + 1) }
            }
        } catch (_: Exception) {}
    }

    private fun saveCache() {
        val f = cacheFile ?: return
        cacheDirty = 0
        try {
            val text = synchronized(cache) { cache.entries.joinToString("\n") { "${it.key}\t${it.value}" } }
            val tmp = File(f.path + ".tmp"); tmp.writeText(text); tmp.renameTo(f)
        } catch (_: Exception) {}
    }
}
