package com.example.dashcam

import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.TimeZone

class ZoneTextTest {
    private val saved = TimeZone.getDefault()
    @After fun restore() = TimeZone.setDefault(saved)

    private fun firstLine(zone: String, style: String, wallMs: Long): String {
        TimeZone.setDefault(TimeZone.getTimeZone(zone))
        return Telemetry.stampFields(OverlayOptions("FRONT", zoneStyle = style), true, wallMs)[0][0].text
    }

    private val winter = 1_799_000_000_000L   // 2027-01-03, standard time
    private val summer = 1_783_000_000_000L   // 2026-07-02, summer time

    @Test fun formats() {
        val cases = listOf(
            Triple("Europe/Berlin", "abbr", winter) to "CET",
            Triple("Europe/Berlin", "offset", winter) to "UTC+1",
            Triple("Europe/Berlin", "both", winter) to "CET (UTC+1)",
            Triple("Europe/Berlin", "both", summer) to "CEST (UTC+2)",
            Triple("America/New_York", "both", winter) to "EST (UTC-5)",
            Triple("Asia/Kolkata", "offset", winter) to "UTC+5:30",
        )
        for ((c, expect) in cases) {
            val line = firstLine(c.first, c.second, c.third)
            println("${c.first} ${c.second}: $line")
            assertTrue("$c -> '$line'", line.endsWith(expect))
        }
        val none = firstLine("Europe/Berlin", "none", winter)
        println("Europe/Berlin none: $none")
        assertTrue(none.matches(Regex("""\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}""")))
    }
}
