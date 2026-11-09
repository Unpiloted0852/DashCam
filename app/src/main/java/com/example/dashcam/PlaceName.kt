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
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.roundToLong

/**
 * Optional "where am I" for the stamp: road and town from Android's geocoder (which sends the
 * position to Google). Off unless enabled.
 *
 * Kept quick by:
 * - checking every 3 s, and looking up again after ~150 m, after a turn (a new road), or every 5 min;
 * - starting from the phone's last known position, so the name is there before GPS has a fix;
 * - remembering answers on disk by ~30 m square, so roads driven before (the commute) show at once,
 *   even without a data connection.
 */
object PlaceName {
    @Volatile var name: String? = null; private set
    private var exec: ScheduledExecutorService? = null
    private var lastAt: Location? = null
    private var lastElapsed = 0L
    private var lastBearing: Float? = null

    private const val CACHE_MAX = 5000
    private val cache = object : LinkedHashMap<String, String>(256, 0.75f, true) {
        override fun removeEldestEntry(e: MutableMap.MutableEntry<String, String>?) = size > CACHE_MAX
    }
    private var cacheFile: File? = null
    private var cacheDirty = 0

    fun start(ctx: Context, enabled: () -> Boolean) {
        if (exec != null || !Geocoder.isPresent()) return
        val app = ctx.applicationContext
        val geocoder = Geocoder(app, Locale.getDefault())
        cacheFile = File(app.filesDir, "place_cache.tsv")
        exec = Executors.newSingleThreadScheduledExecutor().also {
            it.execute { loadCache(); if (enabled()) try { warmStart(app, geocoder) } catch (_: Throwable) {} }
            it.scheduleWithFixedDelay({
                try { refresh(geocoder, enabled()) } catch (_: Throwable) {}
            }, 1, 3, TimeUnit.SECONDS)
        }
    }

    fun stop() {
        exec?.let { e -> e.execute { saveCache() }; e.shutdown() }
        exec = null
    }

    /** Before the first GPS fix: name the place the phone last knew (usually where the car is parked). */
    @SuppressLint("MissingPermission")
    private fun warmStart(ctx: Context, g: Geocoder) {
        if (name != null || Telemetry.freshFix() != null) return
        val lm = ctx.getSystemService(LocationManager::class.java) ?: return
        val last = listOf(LocationManager.GPS_PROVIDER, LocationManager.FUSED_PROVIDER, LocationManager.NETWORK_PROVIDER)
            .mapNotNull { p -> try { lm.getLastKnownLocation(p) } catch (_: Exception) { null } }
            .maxByOrNull { it.elapsedRealtimeNanos } ?: return
        val ageMin = (SystemClock.elapsedRealtimeNanos() - last.elapsedRealtimeNanos) / 60_000_000_000L
        if (ageMin > 30) return
        lookup(g, last)?.let { if (Telemetry.freshFix() == null || name == null) name = it }
    }

    private fun refresh(g: Geocoder, enabled: Boolean) {
        if (!enabled) return
        val loc = Telemetry.freshFix() ?: return
        val prev = lastAt
        val moved = prev?.let { loc.distanceTo(it) } ?: Float.MAX_VALUE
        val bearing = if (loc.hasBearing() && loc.hasSpeed() && loc.speed > 3f) loc.bearing else null
        val turned = bearing != null && lastBearing != null && angleDiff(bearing, lastBearing!!) > 50f && moved > 40f
        val due = prev == null || moved > 150f || turned || SystemClock.elapsedRealtime() - lastElapsed > 5 * 60_000
        if (!due) return
        lastAt = Location(loc); lastElapsed = SystemClock.elapsedRealtime()
        if (bearing != null) lastBearing = bearing
        val found = lookup(g, loc)
        if (found != null) name = found
        else if (moved != Float.MAX_VALUE && moved > 1000f) name = null   // offline and far away: don't show a stale place
    }

    /** Cached answer for this ~30 m square, else ask the geocoder (and remember it). */
    @Suppress("DEPRECATION")
    private fun lookup(g: Geocoder, loc: Location): String? {
        val key = key(loc.latitude, loc.longitude)
        synchronized(cache) { cache[key] }?.let { return it }
        val a = try { g.getFromLocation(loc.latitude, loc.longitude, 1)?.firstOrNull() } catch (_: Exception) { null } ?: return null
        val road = a.thoroughfare
        val town = a.locality ?: a.subAdminArea ?: a.adminArea
        val n = listOfNotNull(road, town).joinToString(", ").ifEmpty { return null }
        synchronized(cache) { cache[key] = n }
        if (++cacheDirty >= 20) saveCache()
        return n
    }

    /** ~30 m grid square (longitude steps widen toward the equator to stay ~square). */
    internal fun key(lat: Double, lon: Double): String {
        val la = (lat * 3000).roundToLong()
        val lonScale = 3000 * cos(Math.toRadians(lat)).coerceAtLeast(0.1)
        val lo = (lon * lonScale).roundToLong()
        return "$la,$lo"
    }

    private fun angleDiff(a: Float, b: Float): Float { val d = abs(a - b) % 360f; return if (d > 180f) 360f - d else d }

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
