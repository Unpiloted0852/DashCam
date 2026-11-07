package com.example.dashcam

import android.content.Context
import android.location.Geocoder
import android.location.Location
import android.os.SystemClock
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * Optional "where am I" for the stamp: road and town from Android's geocoder (which sends the
 * position to Google), refreshed after moving ~300 m or every 5 minutes. Off unless enabled.
 */
object PlaceName {
    @Volatile var name: String? = null; private set
    private var exec: ScheduledExecutorService? = null
    private var lastAt: Location? = null
    private var lastElapsed = 0L

    fun start(ctx: Context, enabled: () -> Boolean) {
        if (exec != null || !Geocoder.isPresent()) return
        val geocoder = Geocoder(ctx.applicationContext, Locale.getDefault())
        exec = Executors.newSingleThreadScheduledExecutor().also {
            it.scheduleWithFixedDelay({
                try { refresh(geocoder, enabled()) } catch (_: Throwable) {}
            }, 2, 20, TimeUnit.SECONDS)
        }
    }

    fun stop() { exec?.shutdownNow(); exec = null }

    @Suppress("DEPRECATION")
    private fun refresh(g: Geocoder, enabled: Boolean) {
        if (!enabled) return
        val loc = Telemetry.freshFix() ?: return
        val prev = lastAt
        val due = prev == null || loc.distanceTo(prev) > 300 || SystemClock.elapsedRealtime() - lastElapsed > 5 * 60_000
        if (!due) return
        lastAt = Location(loc); lastElapsed = SystemClock.elapsedRealtime()
        val a = g.getFromLocation(loc.latitude, loc.longitude, 1)?.firstOrNull() ?: return
        val road = a.thoroughfare
        val town = a.locality ?: a.subAdminArea ?: a.adminArea
        name = listOfNotNull(road, town).joinToString(", ").ifEmpty { null }
    }
}
