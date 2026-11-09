package com.example.dashcam

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.SystemClock
import kotlin.math.sqrt

/**
 * Crash detection: watches the accelerometer while recording and calls [onImpact] on
 * - a hard jolt: acceleration (gravity removed) above the chosen threshold, held for 50 ms, so a
 *   single rattle of the mount doesn't count but a collision does;
 * - a fall: near-weightlessness for 0.3 s, e.g. the phone flung off its mount.
 *
 * Only while the car has been driving in the last 30 s (or there's no GPS to tell), so picking the
 * phone up at a standstill doesn't lock clips.
 */
class ImpactDetector(
    private val ctx: Context,
    /** Jolt threshold in g (gravity removed). */
    private val thresholdG: Float,
    private val onImpact: (what: String) -> Unit,
) : SensorEventListener {

    private val sm = ctx.getSystemService(SensorManager::class.java)
    private val gravity = FloatArray(3)
    private var gravityReady = 0
    private val window = ArrayDeque<Pair<Long, Float>>()   // (time ns, |linear| in g)
    private var fallSince = 0L
    private var cooldownUntil = 0L
    private var lastMovingMs = 0L
    private var lastGpsCheck = 0L

    fun start() {
        val acc = sm?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) ?: return
        sm.registerListener(this, acc, 10_000)   // 100 Hz
    }

    fun stop() { sm?.unregisterListener(this) }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    override fun onSensorChanged(e: SensorEvent) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastGpsCheck > 1000) {
            lastGpsCheck = now
            val fix = Telemetry.freshFix()
            if (fix == null || (fix.hasSpeed() && fix.speed >= MOVING_MPS)) lastMovingMs = now
        }
        val (x, y, z) = Triple(e.values[0], e.values[1], e.values[2])
        // Slow low-pass = gravity; what's left is the car's (and the phone's) own acceleration.
        if (gravityReady < 50) {
            gravity[0] = x; gravity[1] = y; gravity[2] = z; gravityReady++
            if (gravityReady < 50) return
        }
        val k = 0.02f
        gravity[0] += k * (x - gravity[0]); gravity[1] += k * (y - gravity[1]); gravity[2] += k * (z - gravity[2])
        val lx = x - gravity[0]; val ly = y - gravity[1]; val lz = z - gravity[2]
        val linG = sqrt(lx * lx + ly * ly + lz * lz) / SensorManager.GRAVITY_EARTH
        val rawG = sqrt(x * x + y * y + z * z) / SensorManager.GRAVITY_EARTH

        window.addLast(e.timestamp to linG)
        while (window.isNotEmpty() && e.timestamp - window.first().first > JOLT_NS) window.removeFirst()

        fallSince = if (rawG < FREE_FALL_G) (if (fallSince == 0L) now else fallSince) else 0L

        if (now < cooldownUntil || now - lastMovingMs > DRIVE_RECENT_MS) return
        val span = e.timestamp - window.first().first
        val mean = window.sumOf { it.second.toDouble() } / window.size
        when {
            span >= JOLT_NS * 0.8 && mean >= thresholdG -> fire(String.format(java.util.Locale.US, "jolt %.1f g", mean))
            fallSince != 0L && now - fallSince >= FALL_MS -> fire("phone fell")
        }
    }

    private fun fire(what: String) {
        cooldownUntil = SystemClock.elapsedRealtime() + COOLDOWN_MS
        window.clear(); fallSince = 0L
        onImpact(what)
    }

    companion object {
        private const val JOLT_NS = 50_000_000L
        private const val FREE_FALL_G = 0.3f
        private const val FALL_MS = 300L
        private const val COOLDOWN_MS = 15_000L
        private const val DRIVE_RECENT_MS = 30_000L
        private const val MOVING_MPS = 2.2f

        /** Setting value → jolt threshold in g, or null for off. */
        fun thresholdFor(setting: String): Float? = when (setting) {
            "off" -> null
            "low" -> 3.0f
            "high" -> 1.4f
            else -> 2.0f
        }
    }
}
