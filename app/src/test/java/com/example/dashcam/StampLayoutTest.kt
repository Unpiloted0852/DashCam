package com.example.dashcam

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The stamp is a fixed set of fields per option set; live values fill the spots reserved by the
 * widest-case template, so nothing moves from second to second.
 */
class StampLayoutTest {
    private val everything = OverlayOptions(
        camLabel = "FRONT", userText = "AB-123", clock12h = true, showPlace = true,
        weather = setOf("station", "temp", "dew", "rh", "wind", "vis", "clouds", "wx", "qnh"),
    )
    private val mine = OverlayOptions(   // the user's own setup: km/h, no sats, temp/RH/wind/clouds in °C
        camLabel = "FRONT", speedUnit = "kmh", showSats = false, showPlace = true,
        weather = setOf("temp", "rh", "wind", "clouds"), tempUnit = "C",
    )

    private fun shape(g: List<List<Telemetry.Field>>) = g.map { it.size }

    @Test fun liveStampHasTheTemplateShape() {
        for (o in listOf(everything, mine, OverlayOptions("REAR"))) for (portrait in listOf(true, false)) {
            val t = Telemetry.stampTemplate(o, portrait)
            val live = Telemetry.stampFields(o, portrait, wallMs = 0)   // no GPS / METAR data in tests
            assertEquals("shape for $o portrait=$portrait", shape(t), shape(live))
        }
    }

    @Test fun noStraySpacesInFields() {
        for (o in listOf(everything, mine)) for (portrait in listOf(true, false)) {
            val fields = Telemetry.stampFields(o, portrait, 0).flatten() + Telemetry.stampTemplate(o, portrait).flatten()
            fields.forEach { assertTrue("'${it.text}'", it.text == it.text.trim()) }
        }
    }

    @Test fun printForReview() {
        for ((name, o) in listOf("everything" to everything, "mine" to mine)) {
            println("--- $name, template ---")
            Telemetry.stampTemplate(o, true).forEach { g -> println(g.joinToString("  |  ") { it.text }) }
            println("--- $name, no data ---")
            Telemetry.stampFields(o, true, 0).forEach { g -> println(g.joinToString("  |  ") { it.text }) }
        }
    }

    /** Standing still with a METAR: plan the real weather text and no heading, not the worst case. */
    @Test fun stillPlansLiveSlowValues() {
        val wx = Altimeter.Weather(tempC = 4f, dewC = 3f, windDir = 140, windKt = 1, cloudCover = "FEW", cloudBaseFt = 3500)
        val cur = Telemetry.Sample(50.98, 6.03, 10, 0f, null, 240, 20, 30, wx = wx)
        val still = Telemetry.planSample(cur, 2 to 1, moving = false)
        assertEquals(null, still.bearing); assertEquals(0f, still.mps); assertEquals(888, still.altFt)
        assertEquals(wx, still.wx)
        val moving = Telemetry.planSample(cur, 2 to 1, moving = true)
        assertTrue(moving.bearing != null && (moving.mps ?: 0f) > 30f)
    }
}
