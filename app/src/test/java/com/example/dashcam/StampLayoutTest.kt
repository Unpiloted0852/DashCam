package com.example.dashcam

import org.junit.Assert.assertTrue
import org.junit.Test

/** The stamp wraps between whole fields instead of squeezing every line to fit the longest one. */
class StampLayoutTest {
    private val everything = OverlayOptions(
        camLabel = "FRONT", userText = "AB-123", clock12h = true, showPlace = true,
        weather = setOf("station", "temp", "dew", "rh", "wind", "vis", "clouds", "wx", "qnh"),
    )

    private fun show(name: String, lines: List<String>, maxChars: Int) {
        println("--- $name (max $maxChars chars) ---")
        lines.forEach { println("|$it|") }
    }

    @Test fun portraitLinesFitWithoutSqueezing() {
        val max = 52   // about what a 720x1280 portrait frame fits at normal text size
        val lines = Telemetry.overlayTemplate(everything, portrait = true, maxChars = max)
        show("portrait", lines, max)
        assertTrue(lines.all { it.length <= max })
        assertTrue(lines.size <= OverlayTexture.MAX_LINES)
    }

    @Test fun landscapeLinesFit() {
        val max = 92
        val lines = Telemetry.overlayTemplate(everything, portrait = false, maxChars = max)
        show("landscape", lines, max)
        assertTrue(lines.all { it.length <= max })
    }

    @Test fun layoutIsStableWithoutData() {
        // No GPS / METAR: same line breaks and lengths as the widest-case template.
        val max = 52
        val template = Telemetry.overlayTemplate(everything, portrait = true, maxChars = max)
        val live = Telemetry.overlayLines(everything, portrait = true, wallMs = 0, maxChars = max)
        show("portrait, no data", live, max)
        assertTrue(template.size == live.size)
        assertTrue(template.zip(live).all { (a, b) -> a.length == b.length })
    }
}
