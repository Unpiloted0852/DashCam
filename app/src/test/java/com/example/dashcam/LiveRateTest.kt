package com.example.dashcam

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveRateTest {
    /** Feeds one frame every 33 ms for [seconds]; returns every change made. */
    private fun run(r: LiveRate, seconds: Int, t0: Long = 0, lag: (Long) -> Long): List<Int> {
        val changes = ArrayList<Int>()
        var t = t0
        while (t < t0 + seconds * 1000) { r.step(lag(t), t)?.let { changes += it }; t += 33 }
        return changes
    }

    @Test fun clearLinkCreepsUpToTheCeiling() {
        val r = LiveRate(start = 4_000_000, min = 500_000, max = 8_000_000)
        val c = run(r, 30) { 0 }
        assertTrue(c.isNotEmpty() && c.zipWithNext().all { (a, b) -> b > a })
        assertEquals(8_000_000, r.current)
        // Not in a rush: at most one step every 3 s.
        assertTrue(c.size <= 10)
    }

    @Test fun backsOffFastWhenFramesWait() {
        val r = LiveRate(start = 4_000_000, min = 500_000, max = 8_000_000)
        val c = run(r, 1) { 200 }
        assertTrue("within a second it has cut at least twice: $c", c.size >= 2)
        assertTrue(r.current <= 1_500_000)
        run(r, 10) { 200 }
        assertEquals(500_000, r.current)   // and never below the floor
    }

    @Test fun briefHiccupIsIgnored() {
        val r = LiveRate()
        // Two late frames in a row, then fine again.
        assertNull(r.step(60, 0)); assertNull(r.step(60, 33)); assertNull(r.step(0, 66))
        assertEquals(LiveLink.BITRATE_START, r.current)
    }

    @Test fun recoversAfterTrouble() {
        val r = LiveRate(start = 4_000_000, min = 500_000, max = 8_000_000)
        run(r, 2) { 200 }
        val low = r.current
        run(r, 20, t0 = 2000) { 0 }
        assertTrue(r.current > low * 2)
    }
}
