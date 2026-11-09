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

    // ---- header readers, on a hand-built file: ftyp, moov { mvhd, udta { dcst } }, then [tail]

    private fun box(type: String, body: ByteArray): ByteArray =
        java.nio.ByteBuffer.allocate(8 + body.size).putInt(8 + body.size).put(type.toByteArray()).put(body).array()

    private fun clip(durationMs: Int, startWall: Long, tail: ByteArray): java.io.File {
        val mvhd = java.nio.ByteBuffer.allocate(100).putInt(0).putInt(0).putInt(0).putInt(1000).putInt(durationMs).array()
        val dcst = java.nio.ByteBuffer.allocate(8).putLong(startWall).array()
        val moov = box("moov", box("mvhd", mvhd) + box("udta", box("dcst", dcst)))
        return java.io.File.createTempFile("clip", ".mp4").apply {
            deleteOnExit(); writeBytes(box("ftyp", "isom".toByteArray()) + moov + tail)
        }
    }

    @Test fun readsLengthAndStartFromHeader() {
        val f = clip(183_000, 1_794_206_224_440L, box("sidx", ByteArray(24)))
        java.io.FileInputStream(f).use {
            assertEquals(183_000L, Fmp4Muxer.readDurationMs(it.fd))
            assertEquals(1_794_206_224_440L, Fmp4Muxer.readStartWall(it.fd))
            assertEquals(false, Fmp4Muxer.isUnfinished(it.fd))
        }
    }

    @Test fun spotsAClipThatWasNeverClosed() {
        // The reserved block still carries the marker, and the length was never written.
        val f = clip(0, 0, box("free", "DCIX".toByteArray() + ByteArray(60)))
        java.io.FileInputStream(f).use {
            assertEquals(true, Fmp4Muxer.isUnfinished(it.fd))
            assertEquals(null, Fmp4Muxer.readDurationMs(it.fd))
            assertEquals(null, Fmp4Muxer.readStartWall(it.fd))
        }
    }

    @Test fun otherFilesAreLeftAlone() {
        val f = java.io.File.createTempFile("other", ".mp4").apply { deleteOnExit(); writeBytes(ByteArray(64) { 7 }) }
        java.io.FileInputStream(f).use {
            assertEquals(false, Fmp4Muxer.isUnfinished(it.fd))
            assertEquals(null, Fmp4Muxer.readDurationMs(it.fd))
        }
    }
}
