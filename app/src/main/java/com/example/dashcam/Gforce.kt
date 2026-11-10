package com.example.dashcam

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.HandlerThread
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * How hard the car is speeding up or braking, in g, along its direction of travel.
 *
 * Two sources, combined:
 * - the phone's motion sensors (instant, but they drift and can't tell a long steady push from
 *   gravity): acceleration with gravity removed, measured along the direction the road camera
 *   points, levelled to the horizontal;
 * - the change in GPS speed (exact over a second or two, but slow).
 * Quick changes come from the sensors, the steady level from GPS, so the bar reacts at once and
 * still reads right through a long braking or acceleration. Without GPS the sensors are used alone.
 */
object Gforce : SensorEventListener {
    /** Forward acceleration in g (+ speeding up, − braking), or null if it can't be measured. */
    val forwardG: Float?
        get() {
            // Remote camera: the main phone's value (this phone may point anywhere).
            if (Telemetry.relayed() != null) return relayedG
            return if (android.os.SystemClock.elapsedRealtime() - lastEventMs < 1000) shown else null
        }

    /** Value to send to a remote camera. */
    val localG: Float? get() = if (android.os.SystemClock.elapsedRealtime() - lastEventMs < 1000) shown else null

    @Volatile var relayedG: Float? = null

    @Volatile private var shown = 0f
    @Volatile private var lastEventMs = 0L

    private var sm: SensorManager? = null
    private var thread: HandlerThread? = null
    private val up = FloatArray(3)          // unit vector pointing up, in phone coordinates
    private val grav = FloatArray(3)
    private var haveUp = false
    private var ownGravity = false          // no gravity sensor: estimate it from the accelerometer
    private val state = Blend()

    fun start(ctx: Context) {
        if (sm != null) return
        val m = ctx.getSystemService(SensorManager::class.java) ?: return
        val t = HandlerThread("gforce").apply { start() }
        val h = Handler(t.looper)
        val lin = m.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION)
        val grav = m.getDefaultSensor(Sensor.TYPE_GRAVITY)
        val acc = m.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        when {
            lin != null && grav != null -> {
                m.registerListener(this, lin, 20_000, h); m.registerListener(this, grav, 100_000, h)
            }
            acc != null -> { ownGravity = true; m.registerListener(this, acc, 20_000, h) }
            else -> { t.quitSafely(); return }
        }
        sm = m; thread = t
    }

    fun stop() {
        sm?.unregisterListener(this); sm = null
        thread?.quitSafely(); thread = null
        haveUp = false; state.reset(); shown = 0f; lastEventMs = 0
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    override fun onSensorChanged(e: SensorEvent) {
        val v = e.values
        when (e.sensor.type) {
            Sensor.TYPE_GRAVITY -> setUp(v[0], v[1], v[2])
            Sensor.TYPE_ACCELEROMETER -> {
                // Slow average = gravity (good enough as a fallback; the GPS blend corrects the rest).
                if (!haveUp) { grav[0] = v[0]; grav[1] = v[1]; grav[2] = v[2] }
                else for (i in 0..2) grav[i] += 0.01f * (v[i] - grav[i])
                setUp(grav[0], grav[1], grav[2])
                sample(v[0] - grav[0], v[1] - grav[1], v[2] - grav[2], e.timestamp)
            }
            Sensor.TYPE_LINEAR_ACCELERATION -> if (haveUp) sample(v[0], v[1], v[2], e.timestamp)
        }
    }

    private fun setUp(x: Float, y: Float, z: Float) {
        val n = sqrt(x * x + y * y + z * z)
        if (n < 1f) return
        up[0] = x / n; up[1] = y / n; up[2] = z / n; haveUp = true
    }

    private const val G0 = SensorManager.GRAVITY_EARTH

    /** [lx],[ly],[lz]: acceleration without gravity, m/s², phone coordinates. */
    private fun sample(lx: Float, ly: Float, lz: Float, tNs: Long) {
        val along = forwardComponent(lx, ly, lz, up)   // null: camera pointing up or down
        val fix = Telemetry.freshFix()
        val speed = if (fix != null && fix.hasSpeed()) fix.speed else null
        val fixNs = fix?.elapsedRealtimeNanos ?: 0L
        shown = state.step(along, tNs, speed, fixNs) / G0
        lastEventMs = android.os.SystemClock.elapsedRealtime()
    }

    /**
     * Acceleration along the car's forward direction, taken as where the road camera (the phone's
     * back, −Z) points, levelled to the horizontal. Null if the camera points mostly up or down.
     */
    internal fun forwardComponent(lx: Float, ly: Float, lz: Float, up: FloatArray): Float? {
        // forward = −Z with its vertical part removed: (0,0,−1) + up.z · up
        val fx = up[2] * up[0]; val fy = up[2] * up[1]; val fz = -1f + up[2] * up[2]
        val n = sqrt(fx * fx + fy * fy + fz * fz)
        if (n < 0.5f) return null
        return (lx * fx + ly * fy + lz * fz) / n
    }

    /** The sensor/GPS blend; plain arithmetic so it can be unit-tested. All values in m/s². */
    internal class Blend {
        private var lastNs = 0L
        private var sensLp = 0f          // slow average of the sensor reading (its drift and tilt error)
        private var gpsAcc = 0f          // latest change of GPS speed per second
        private var gpsLp = 0f
        private var gpsQuick = 0f        // GPS alone, lightly smoothed (when the sensors can't be used)
        private var lastFixNs = 0L
        private var lastSpeed = 0f
        private var haveGps = false
        private var offset = 0f          // sensor drift as last seen while GPS was available
        private var out = 0f

        fun reset() { lastNs = 0; sensLp = 0f; gpsAcc = 0f; gpsLp = 0f; gpsQuick = 0f; lastFixNs = 0; haveGps = false; offset = 0f; out = 0f }

        /**
         * [sensor]: forward acceleration from the motion sensors (null = unusable right now);
         * [speed]/[fixNs]: latest GPS speed (m/s) and its time. Returns the value to show.
         */
        fun step(sensor: Float?, tNs: Long, speed: Float?, fixNs: Long): Float {
            val dt = if (lastNs == 0L) 0.02f else ((tNs - lastNs) / 1e9f).coerceIn(0.001f, 0.5f)
            lastNs = tNs
            if (speed != null && fixNs != lastFixNs) {
                if (lastFixNs != 0L) {
                    val span = (fixNs - lastFixNs) / 1e9f
                    if (span in 0.2f..3f) { gpsAcc = (speed - lastSpeed) / span; haveGps = true } else haveGps = false
                }
                lastFixNs = fixNs; lastSpeed = speed
            }
            val gpsFresh = haveGps && speed != null && tNs - fixNs < 2_500_000_000L
            if (!gpsFresh) { haveGps = false; gpsAcc = 0f }
            // The slow part (from GPS) and the quick part (from the sensors) use the same time
            // constant, so together they add up to the true value.
            gpsLp += (gpsAcc - gpsLp) * (dt / CROSS_TAU).coerceAtMost(1f)
            gpsQuick += (gpsAcc - gpsQuick) * (dt / GPS_TAU).coerceAtMost(1f)

            val target = when {
                sensor == null -> if (gpsFresh) gpsQuick else 0f
                gpsFresh -> {
                    sensLp += (sensor - sensLp) * (dt / CROSS_TAU).coerceAtMost(1f)
                    offset = sensLp - gpsLp
                    gpsLp + (sensor - sensLp)
                }
                else -> sensor - offset
            }
            out += (target - out) * (dt / SHOW_TAU).coerceAtMost(1f)
            return if (abs(out) < DEADBAND) 0f else out
        }

        companion object {
            const val CROSS_TAU = 4.0f     // s: slower than this comes from GPS, quicker from the sensors
            const val GPS_TAU = 0.6f
            const val SHOW_TAU = 0.12f
            const val DEADBAND = 0.15f     // m/s² (~0.015 g): hides idle vibration
        }
    }
}
