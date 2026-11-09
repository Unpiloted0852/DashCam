package com.example.dashcam

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class Fmp4Test {
    private fun b(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }

    /** Start codes (3- and 4-byte) become 4-byte lengths; access unit delimiters are dropped. */
    @Test fun annexBToLengthPrefixed() {
        val src = b(0, 0, 0, 1, 0x09, 0xF0,  0, 0, 0, 1, 0x67, 1, 2,  0, 0, 1, 0x68, 3,  0, 0, 0, 1, 0x65, 4, 5, 6)
        val want = b(0, 0, 0, 3, 0x67, 1, 2,  0, 0, 0, 2, 0x68, 3,  0, 0, 0, 4, 0x65, 4, 5, 6)
        assertArrayEquals(want, Fmp4Muxer.annexB(src))
    }

    @Test fun placeGridIsAbout30m() {
        assertEquals(PlaceName.key(50.98132, 6.03340), PlaceName.key(50.98135, 6.03345))
        assert(PlaceName.key(50.98132, 6.03340) != PlaceName.key(50.98182, 6.03340))
    }
}
