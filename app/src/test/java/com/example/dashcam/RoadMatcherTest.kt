package com.example.dashcam

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Synthetic crossroads: "Main St" runs north-south, "Cross St" east-west, meeting at (0, 0). */
class RoadMatcherTest {
    private val main = RoadMatcher.Road("Main St", null, "minor")
    private val cross = RoadMatcher.Road("Cross St", null, "minor")
    private val parking = RoadMatcher.Road(null, null, "service")
    private val m = 1.0 / 111_320   // one metre in degrees (near the equator)
    private val segs = listOf(
        RoadMatcher.Segment(-200 * m, 0.0, 200 * m, 0.0, main),
        RoadMatcher.Segment(0.0, -200 * m, 0.0, 200 * m, cross),
        // A car park aisle 40 m east of Main St, unnamed.
        RoadMatcher.Segment(-100 * m, 40 * m, -150 * m, 40 * m, parking),
    )

    @Test fun headingPicksTheRoadYouDriveAlong() {
        // 6 m from the junction, closer to Cross St's line, but heading north: Main St.
        assertEquals("Main St", RoadMatcher.match(segs, 6 * m, 3 * m, 0f, 5f, null)?.road?.label)
        // Same spot heading east: Cross St.
        assertEquals("Cross St", RoadMatcher.match(segs, 3 * m, 6 * m, 90f, 5f, null)?.road?.label)
        // Heading south counts as along Main St too.
        assertEquals("Main St", RoadMatcher.match(segs, 50 * m, 4 * m, 180f, 5f, null)?.road?.label)
    }

    @Test fun keepsTheCurrentRoadThroughAJunction() {
        // Right on the junction, slightly closer to Cross St, stopped: stay on Main St.
        assertEquals("Main St", RoadMatcher.match(segs, 2 * m, 1 * m, null, 5f, main)?.road?.label)
    }

    @Test fun unnamedRoadShowsNoStreet() {
        val r = RoadMatcher.match(segs, -120 * m, 40 * m, 0f, 5f, null)
        assertNull(r?.road?.label)
    }

    @Test fun nothingNearby() {
        assertNull(RoadMatcher.match(segs, 500 * m, 500 * m, null, 5f, null))
    }

    @Test fun motorwaysByNumber() {
        assertEquals("A46", RoadMatcher.Road("Autobahn", "A46", "motorway").label)
        assertEquals("Hauptstraße", RoadMatcher.Road("Hauptstraße", "B56", "primary").label)
        assertEquals("L42", RoadMatcher.Road(null, "L42", "secondary").label)
    }
}
