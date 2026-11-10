package com.example.dashcam

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin

class GforceTest {
    @Test fun forwardIsWhereTheRoadCameraPoints() {
        // Upright in portrait (Y up), camera (−Z) looking down the road: speeding up at 2 m/s².
        assertEquals(2f, Gforce.forwardComponent(0f, 0f, -2f, floatArrayOf(0f, 1f, 0f))!!, 0.001f)
        // Landscape (X up): the same.
        assertEquals(2f, Gforce.forwardComponent(0f, 0f, -2f, floatArrayOf(1f, 0f, 0f))!!, 0.001f)
        // Braking reads negative.
        assertEquals(-3f, Gforce.forwardComponent(0f, 0f, 3f, floatArrayOf(0f, 1f, 0f))!!, 0.001f)
        // Leaning back 20° on the dash: forward is still horizontal, not along the tilted lens.
        val t = Math.toRadians(20.0)
        val up = floatArrayOf(0f, cos(t).toFloat(), sin(t).toFloat())
        assertEquals(2f, Gforce.forwardComponent(0f, 2f * sin(t).toFloat(), -2f * cos(t).toFloat(), up)!!, 0.001f)
        // A bump (vertical jolt) is not acceleration.
        assertEquals(0f, Gforce.forwardComponent(5f * up[0], 5f * up[1], 5f * up[2], up)!!, 0.001f)
        // Lying flat: no forward direction.
        assertNull(Gforce.forwardComponent(1f, 1f, 1f, floatArrayOf(0f, 0f, 1f)))
    }

    /** Runs the blend at 50 Hz with a GPS fix every second. */
    private fun run(seconds: Float, b: Gforce.Blend = Gforce.Blend(), t0: Float = 0f,
                    gps: Boolean = true, sensor: (Float) -> Float?, speed: (Float) -> Float): Float {
        var out = 0f
        var i = 0
        while (i * 0.02f < seconds) {
            val t = t0 + i * 0.02f
            val fixT = kotlin.math.floor(t)
            out = b.step(sensor(t), (t * 1e9f).toLong() + 1, if (gps) speed(fixT) else null, (fixT * 1e9f).toLong() + 1)
            i++
        }
        return out
    }

    @Test fun reactsAtOnce() {
        val b = Gforce.Blend()
        run(5f, b, sensor = { 0f }, speed = { 20f })
        // Brakes hit at t = 5 s: within 0.3 s the bar shows most of it, long before GPS notices.
        val out = run(0.3f, b, t0 = 5f, sensor = { -4f }, speed = { 20f })
        assertTrue("got $out", out < -3f)
    }

    @Test fun holdsThroughALongBrake() {
        // 9 s at −3 m/s², with a sensor that wrongly fades to zero within a few seconds (a harsher
        // fade than real gravity estimates show): the bar must still show most of the braking.
        val out = run(9f, sensor = { t -> -3f * kotlin.math.exp(-t / 2f) }, speed = { t -> 30f - 3f * t })
        assertEquals(-3f, out, 0.75f)
    }

    @Test fun ignoresSensorDrift() {
        // Cruising at steady speed with a sensor that reads 1 m/s² too high (tilted mount, drift).
        assertEquals(0f, run(25f, sensor = { 1f }, speed = { 25f }), 0.02f)
    }

    @Test fun worksWithoutGps() {
        assertEquals(2f, run(3f, gps = false, sensor = { 2f }, speed = { 0f }), 0.1f)
    }

    @Test fun fallsBackToGpsWhenThePhoneLiesFlat() {
        assertEquals(1.5f, run(8f, sensor = { null }, speed = { t -> 1.5f * t }), 0.2f)
    }
}
