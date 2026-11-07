package com.example.dashcam

import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.math.pow

class BaroCalibrationTest {
    private fun altFt(hpa: Float, qnh: Float) = 145366.45 * (1.0 - (hpa / qnh).toDouble().pow(0.190284))

    @Test fun calibratedReadingShowsTheKnownAltitude() {
        val qnh = 1022.0f
        for ((raw, trueFt) in listOf(1013.4f to 251f, 1010.0f to 400f, 980.0f to 1500f, 1022.0f to -30f)) {
            val off = Altimeter.offsetFor(trueFt, raw, qnh)
            assertEquals("raw $raw -> $trueFt ft", trueFt.toDouble(), altFt(raw + off, qnh), 0.5)
        }
    }

    @Test fun aSmallSensorErrorIsASmallCorrection() {
        // Phone reads 1 hPa low at a spot that's really at 250 ft: correction should be about +1 hPa.
        val qnh = 1013.25f
        val truthHpa = (qnh * (1.0 - 250 / 145366.45).pow(1 / 0.190284)).toFloat()
        assertEquals(1.0f, Altimeter.offsetFor(250f, truthHpa - 1f, qnh), 0.01f)
    }
}
