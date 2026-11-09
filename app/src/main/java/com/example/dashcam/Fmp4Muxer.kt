package com.example.dashcam

import android.media.MediaCodec
import android.media.MediaFormat
import java.io.FileOutputStream
import java.io.FileDescriptor
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import java.util.Locale

/**
 * Crash-safe MP4 writer (H.264 + AAC), a stand-in for MediaMuxer.
 *
 * MediaMuxer writes the index only when a clip is closed, so a clip cut off by a crash, a dead battery
 * or the phone being thrown is unplayable. This writes a *fragmented* MP4 instead: the header comes
 * first, then one self-contained fragment per second (one GOP), each flushed to storage as it is
 * written. Whatever was written is playable; at most the last second is lost.
 *
 * A reserved block after the header gets a seek index (sidx) and the durations when the clip is
 * closed, so players can seek and show the length. [repair] does the same for a clip that was never
 * closed.
 */
class Fmp4Muxer(fd: FileDescriptor) {
    private val out = FileOutputStream(fd)          // doesn't own (close) the descriptor
    private val ch: FileChannel = out.channel
    private val rd: FileChannel = java.io.FileInputStream(fd).channel

    private class Track(val format: MediaFormat, val video: Boolean) {
        val timescale = if (video) VIDEO_TIMESCALE else format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        val samples = ArrayList<Sample>()
        var lastPtsUs = -1L
    }
    private class Sample(val data: ByteArray, val ptsUs: Long, val key: Boolean)

    private val tracks = ArrayList<Track>()
    private var location: Pair<Float, Float>? = null
    private var started = false
    private var basePtsUs = -1L
    private var seq = 0
    private var reserveAt = 0L
    private var meta = Meta(LongArray(0), 0L)
    private val frags = ArrayList<Frag>()
    private var lastVideoDurTicks = VIDEO_TIMESCALE / 30L

    fun setLocation(lat: Float, lon: Float) { location = lat to lon }

    fun addTrack(format: MediaFormat): Int {
        check(!started)
        val mime = format.getString(MediaFormat.KEY_MIME)
        val video = mime == MediaFormat.MIMETYPE_VIDEO_AVC
        require(video || mime == MediaFormat.MIMETYPE_AUDIO_AAC) { "unsupported $mime" }
        tracks += Track(format, video)
        return tracks.size - 1
    }

    fun start() {
        check(tracks.isNotEmpty() && tracks[0].video) { "video track must come first" }
        val head = Buf()
        head.box("ftyp") { str("isom"); u32(0x200); str("isom"); str("iso6"); str("avc1"); str("mp41") }
        val patch = ArrayList<Long>()
        writeMoov(head, patch)
        reserveAt = head.size.toLong()
        meta = Meta(patch.toLongArray(), reserveAt)
        head.bytes(meta.reservedBox())
        ch.position(0)
        writeFully(ByteBuffer.wrap(head.array()))
        out.fd.sync()
        started = true
    }

    fun writeSampleData(trackIndex: Int, buf: ByteBuffer, info: MediaCodec.BufferInfo) {
        check(started)
        val t = tracks[trackIndex]
        val pts = info.presentationTimeUs
        if (t.video) {
            val key = info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0
            if (basePtsUs < 0) { if (!key) return; basePtsUs = pts }
            if (pts <= t.lastPtsUs) return   // no B-frames: decode order = presentation order
            // A new GOP: everything before it becomes a fragment.
            if (key && t.samples.isNotEmpty()) flushFragment(pts)
            t.samples += Sample(annexBToLengthPrefixed(buf, info), pts, key)
            t.lastPtsUs = pts
        } else {
            if (basePtsUs < 0 || pts < basePtsUs || pts <= t.lastPtsUs) return
            val b = ByteArray(info.size)
            buf.position(info.offset); buf.get(b, 0, info.size)
            t.samples += Sample(b, pts, true)
            t.lastPtsUs = pts
        }
    }

    /** Write the buffered GOP (plus audio) as one moof+mdat; [nextVideoPtsUs] ends its last frame. */
    private fun flushFragment(nextVideoPtsUs: Long?) {
        val v = tracks[0]
        if (v.samples.isEmpty()) return
        fun ticks(t: Track, pts: Long) = (pts - basePtsUs) * t.timescale / 1_000_000L
        val vDur = LongArray(v.samples.size) { i ->
            val next = if (i + 1 < v.samples.size) v.samples[i + 1].ptsUs else nextVideoPtsUs
            if (next != null) ticks(v, next) - ticks(v, v.samples[i].ptsUs) else lastVideoDurTicks
        }
        if (vDur.isNotEmpty()) lastVideoDurTicks = vDur.last().coerceAtLeast(1)

        val moof = Buf()
        val dataOffsetPos = ArrayList<Int>()
        moof.box("moof") {
            fullBox("mfhd", 0, 0) { u32(++seq) }
            for ((ti, t) in tracks.withIndex()) {
                if (t.samples.isEmpty()) continue
                box("traf") {
                    fullBox("tfhd", 0, 0x020000) { u32(ti + 1) }   // default-base-is-moof
                    fullBox("tfdt", 1, 0) { u64(ticks(t, t.samples[0].ptsUs)) }
                    val flags = 0x000001 or 0x000100 or 0x000200 or (if (t.video) 0x000400 else 0)
                    fullBox("trun", 0, flags) {
                        u32(t.samples.size)
                        dataOffsetPos += size; u32(0)
                        for ((i, s) in t.samples.withIndex()) {
                            u32(if (t.video) vDur[i] else AAC_FRAME.toLong())
                            u32(s.data.size)
                            if (t.video) u32(if (s.key) SYNC_FLAGS else NON_SYNC_FLAGS)
                        }
                    }
                }
            }
        }
        val mdatPayload = tracks.sumOf { t -> t.samples.sumOf { it.data.size } }
        var off = moof.size + 8
        var k = 0
        for (t in tracks) {
            if (t.samples.isEmpty()) continue
            moof.patch32(dataOffsetPos[k++], off.toLong())
            off += t.samples.sumOf { it.data.size }
        }
        val all = ByteBuffer.allocate(moof.size + 8 + mdatPayload)
        all.put(moof.array())
        all.putInt(8 + mdatPayload); all.put("mdat".toByteArray(Charsets.US_ASCII))
        for (t in tracks) for (s in t.samples) all.put(s.data)
        all.flip()
        val at = ch.size()
        ch.position(at)
        writeFully(all)
        out.fd.sync()   // survive a power loss, not just an app crash
        frags += Frag(at, all.limit().toLong(), vDur.sum(), ticks(v, v.samples[0].ptsUs))
        for (t in tracks) t.samples.clear()
    }

    /** Write the last fragment, the seek index and the durations. */
    fun stop() {
        if (!started) return
        flushFragment(null)
        finish(rd, ch, meta, frags)
        out.fd.sync()
        started = false
    }

    fun release() {}   // the caller closes the descriptor

    private fun writeFully(b: ByteBuffer) { while (b.hasRemaining()) ch.write(b) }

    // ------------------------------------------------------------------ header

    private fun writeMoov(b: Buf, patch: MutableList<Long>) {
        b.box("moov") {
            fullBox("mvhd", 0, 0) {
                u32(0); u32(0); u32(MOVIE_TIMESCALE.toLong())
                patch += size.toLong(); u32(0)                 // duration
                u32(0x00010000); u16(0x0100); zeros(10); matrix(); zeros(24)
                u32(tracks.size + 1L)
            }
            for ((i, t) in tracks.withIndex()) trak(this, i + 1, t, patch)
            box("mvex") {
                fullBox("mehd", 1, 0) { patch += size.toLong(); u64(0) }
                for ((i, t) in tracks.withIndex()) fullBox("trex", 0, 0) {
                    u32(i + 1L); u32(1); u32(0); u32(0); u32(if (t.video) NON_SYNC_FLAGS else SYNC_FLAGS)
                }
            }
            location?.let { (la, lo) ->
                box("udta") {
                    val s = String.format(Locale.US, "%+08.4f%+09.4f/", la, lo).toByteArray(Charsets.US_ASCII)
                    rawBox(byteArrayOf(0xA9.toByte(), 'x'.code.toByte(), 'y'.code.toByte(), 'z'.code.toByte())) {
                        u16(s.size); u16(0x15c7); bytes(s)
                    }
                }
            }
        }
    }

    private fun trak(b: Buf, id: Int, t: Track, patch: MutableList<Long>) = b.box("trak") {
        val w = if (t.video) t.format.getInteger(MediaFormat.KEY_WIDTH) else 0
        val h = if (t.video) t.format.getInteger(MediaFormat.KEY_HEIGHT) else 0
        fullBox("tkhd", 0, 3) {
            u32(0); u32(0); u32(id.toLong()); u32(0)
            patch += size.toLong(); u32(0)                     // duration (movie timescale)
            zeros(8); u16(0); u16(0); u16(if (t.video) 0 else 0x0100); u16(0)
            matrix(); u32(w.toLong() shl 16); u32(h.toLong() shl 16)
        }
        box("mdia") {
            fullBox("mdhd", 0, 0) {
                u32(0); u32(0); u32(t.timescale.toLong())
                patch += size.toLong(); u32(0)                 // duration (track timescale)
                u16(0x55c4); u16(0)
            }
            fullBox("hdlr", 0, 0) {
                u32(0); str(if (t.video) "vide" else "soun"); zeros(12)
                bytes((if (t.video) "VideoHandle" else "SoundHandle").toByteArray() + 0)
            }
            box("minf") {
                if (t.video) fullBox("vmhd", 0, 1) { zeros(8) } else fullBox("smhd", 0, 0) { zeros(4) }
                box("dinf") { fullBox("dref", 0, 0) { u32(1); fullBox("url ", 0, 1) {} } }
                box("stbl") {
                    fullBox("stsd", 0, 0) { u32(1); if (t.video) avc1(this, t, w, h) else mp4a(this, t) }
                    fullBox("stts", 0, 0) { u32(0) }
                    fullBox("stsc", 0, 0) { u32(0) }
                    fullBox("stsz", 0, 0) { u32(0); u32(0) }
                    fullBox("stco", 0, 0) { u32(0) }
                }
            }
        }
    }

    private fun avc1(b: Buf, t: Track, w: Int, h: Int) = b.box("avc1") {
        zeros(6); u16(1); zeros(16); u16(w); u16(h)
        u32(0x00480000); u32(0x00480000); u32(0); u16(1); zeros(32); u16(0x18); u16(0xFFFF)
        val sps = stripStartCode(csd(t.format, "csd-0"))
        val pps = stripStartCode(csd(t.format, "csd-1"))
        box("avcC") {
            u8(1); u8(sps[1].toInt() and 0xFF); u8(sps[2].toInt() and 0xFF); u8(sps[3].toInt() and 0xFF)
            u8(0xFF); u8(0xE1); u16(sps.size); bytes(sps); u8(1); u16(pps.size); bytes(pps)
        }
    }

    private fun mp4a(b: Buf, t: Track) = b.box("mp4a") {
        val rate = t.format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        val channels = t.format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        zeros(6); u16(1); zeros(8); u16(channels); u16(16); u16(0); u16(0); u32(rate.toLong() shl 16)
        val asc = csd(t.format, "csd-0")
        val bitrate = if (t.format.containsKey(MediaFormat.KEY_BIT_RATE)) t.format.getInteger(MediaFormat.KEY_BIT_RATE) else 96_000
        fullBox("esds", 0, 0) {
            u8(0x03); u8(3 + 2 + 13 + 2 + asc.size + 3); u16(0); u8(0)
            u8(0x04); u8(13 + 2 + asc.size); u8(0x40); u8(0x15); u8(0); u16(0)
            u32(bitrate.toLong()); u32(bitrate.toLong())
            u8(0x05); u8(asc.size); bytes(asc)
            u8(0x06); u8(1); u8(0x02)
        }
    }

    // ------------------------------------------------------------------ index / repair

    /** One moof+mdat: file offset, byte size, video duration and start (video ticks). */
    class Frag(val offset: Long, val size: Long, val durTicks: Long, val startTicks: Long)

    /** Where the duration fields and reserved block sit; stored inside the reserved block itself. */
    class Meta(val patch: LongArray, val reserveAt: Long) {
        fun reservedBox(): ByteArray {
            val b = ByteBuffer.allocate(RESERVE).order(ByteOrder.BIG_ENDIAN)
            b.putInt(RESERVE); b.put("free".toByteArray()); b.put(MARKER.toByteArray())
            b.putInt(patch.size); patch.forEach { b.putLong(it) }
            return b.array()
        }
    }

    companion object {
        const val VIDEO_TIMESCALE = 90_000
        private const val MOVIE_TIMESCALE = 1000
        private const val AAC_FRAME = 1024
        private const val SYNC_FLAGS = 0x02000000L
        private const val NON_SYNC_FLAGS = 0x01010000L
        /** Room for the seek index: 1 entry per second → over 90 minutes; longer clips merge entries. */
        private const val RESERVE = 64 * 1024
        private const val MARKER = "DCIX"

        private fun csd(f: MediaFormat, key: String): ByteArray {
            val bb = f.getByteBuffer(key) ?: throw IllegalStateException("no $key")
            val dup = bb.duplicate(); dup.position(0)
            return ByteArray(dup.remaining()).also { dup.get(it) }
        }

        private fun stripStartCode(b: ByteArray): ByteArray = when {
            b.size > 4 && b[0] == 0.toByte() && b[1] == 0.toByte() && b[2] == 0.toByte() && b[3] == 1.toByte() -> b.copyOfRange(4, b.size)
            b.size > 3 && b[0] == 0.toByte() && b[1] == 0.toByte() && b[2] == 1.toByte() -> b.copyOfRange(3, b.size)
            else -> b
        }

        /** Encoder output is Annex B (start codes); MP4 wants each NAL prefixed by its length. */
        internal fun annexBToLengthPrefixed(buf: ByteBuffer, info: MediaCodec.BufferInfo): ByteArray {
            val src = ByteArray(info.size)
            buf.position(info.offset); buf.get(src, 0, info.size)
            return annexB(src)
        }

        internal fun annexB(src: ByteArray): ByteArray {
            val starts = ArrayList<Int>()   // index of first NAL byte
            var i = 0
            while (i + 2 < src.size) {
                if (src[i] == 0.toByte() && src[i + 1] == 0.toByte() && src[i + 2] == 1.toByte()) { starts += i + 3; i += 3 } else i++
            }
            if (starts.isEmpty()) {   // already length-prefixed (or a single raw NAL)
                return ByteBuffer.allocate(4 + src.size).putInt(src.size).put(src).array()
            }
            val o = java.io.ByteArrayOutputStream(src.size + 16)
            for ((n, s) in starts.withIndex()) {
                var e = if (n + 1 < starts.size) starts[n + 1] - 3 else src.size
                while (e > s && src[e - 1] == 0.toByte() && n + 1 < starts.size) e--   // trailing zero of a 4-byte start code
                val len = e - s
                if (len <= 0) continue
                if ((src[s].toInt() and 0x1F) == 9) continue   // access unit delimiter
                o.write(byteArrayOf((len ushr 24).toByte(), (len ushr 16).toByte(), (len ushr 8).toByte(), len.toByte()))
                o.write(src, s, len)
            }
            return o.toByteArray()
        }

        /** Fill the reserved block with a sidx (seek index) and patch the durations. */
        internal fun finish(rd: FileChannel, ch: FileChannel, meta: Meta, fragsIn: List<Frag>) {
            if (fragsIn.isEmpty()) return
            var frags = fragsIn
            val maxRefs = (RESERVE - 8 - 32) / 12
            while (frags.size > maxRefs) {   // merge neighbours until the index fits
                frags = frags.chunked(2).map { p ->
                    if (p.size == 1) p[0] else Frag(p[0].offset, p[0].size + p[1].size, p[0].durTicks + p[1].durTicks, p[0].startTicks)
                }
            }
            val sidxSize = 32 + 12 * frags.size
            val firstOffset = (meta.reserveAt + RESERVE) - (meta.reserveAt + sidxSize)
            val b = ByteBuffer.allocate(sidxSize + 8)
            b.putInt(sidxSize); b.put("sidx".toByteArray()); b.putInt(0)
            b.putInt(1); b.putInt(VIDEO_TIMESCALE)
            b.putInt(frags[0].startTicks.toInt()); b.putInt(firstOffset.toInt())
            b.putShort(0); b.putShort(frags.size.toShort())
            for (f in frags) {
                b.putInt(f.size.toInt() and 0x7FFFFFFF)
                b.putInt(f.durTicks.toInt())
                b.putInt(0x90000000.toInt())   // starts with SAP, type 1
            }
            // What's left of the reserved block stays a 'free' box (the marker is overwritten).
            b.putInt(RESERVE - sidxSize); b.put("free".toByteArray())
            b.flip()
            var pos = meta.reserveAt
            while (b.hasRemaining()) pos += ch.write(b, pos)

            val totalTicks = frags.last().startTicks + frags.last().durTicks
            val ms = totalTicks * MOVIE_TIMESCALE / VIDEO_TIMESCALE
            // patch[0]=mvhd, then per track tkhd+mdhd, last = mehd (64-bit). Track timescales vary, so read them back.
            val p = meta.patch
            put32(ch, p[0], ms)
            var i = 1
            while (i + 1 <= p.size - 2) {
                put32(ch, p[i], ms)                       // tkhd
                val ts = get32(rd, p[i + 1] - 4)          // mdhd timescale sits just before its duration
                put32(ch, p[i + 1], totalTicks * ts / VIDEO_TIMESCALE)
                i += 2
            }
            put64(ch, p[p.size - 1], ms)
        }

        private fun put32(ch: FileChannel, pos: Long, v: Long) { val b = ByteBuffer.allocate(4).putInt(v.toInt()); b.flip(); ch.write(b, pos) }
        private fun put64(ch: FileChannel, pos: Long, v: Long) { val b = ByteBuffer.allocate(8).putLong(v); b.flip(); ch.write(b, pos) }
        private fun get32(ch: FileChannel, pos: Long): Long { val b = ByteBuffer.allocate(4); ch.read(b, pos); b.flip(); return b.int.toLong() and 0xFFFFFFFFL }

        /** Result of [repair]. */
        enum class Repair { NOT_NEEDED, REPAIRED, EMPTY, NOT_OURS }

        /**
         * Make a clip that was never closed (crash, power loss) playable and seekable: cut off a
         * half-written last fragment, then write the index and durations.
         */
        fun repair(fd: FileDescriptor): Repair {
            val rd = java.io.FileInputStream(fd).channel
            val ch = FileOutputStream(fd).channel
            val len = rd.size()
            fun header(pos: Long): Pair<Long, String>? {
                if (pos + 8 > len) return null
                val b = ByteBuffer.allocate(16); rd.read(b, pos); b.flip()
                if (b.limit() < 8) return null
                var size = b.int.toLong() and 0xFFFFFFFFL
                val type = String(ByteArray(4).also { b.get(it) }, Charsets.US_ASCII)
                if (size == 1L && b.remaining() >= 8) size = b.long
                if (size < 8) return null
                return size to type
            }
            var pos = 0L
            var meta: Meta? = null
            var sawFtyp = false
            // Header boxes, up to the reserved block.
            while (true) {
                val (size, type) = header(pos) ?: return Repair.NOT_OURS
                when (type) {
                    "ftyp" -> sawFtyp = true
                    "sidx" -> return Repair.NOT_NEEDED
                    "moof", "mdat" -> return if (meta == null) Repair.NOT_OURS else Repair.NOT_NEEDED
                    "free" -> {
                        val b = ByteBuffer.allocate(8 + 4 + 4 + 8 * 32); rd.read(b, pos); b.flip()
                        b.position(8)
                        val m = ByteArray(4).also { b.get(it) }
                        if (String(m, Charsets.US_ASCII) != MARKER) return if (sawFtyp) Repair.NOT_NEEDED else Repair.NOT_OURS
                        val n = b.int
                        if (n !in 3..32) return Repair.NOT_OURS
                        meta = Meta(LongArray(n) { b.long }, pos)
                        pos += size
                        break
                    }
                }
                pos += size
            }
            val m = meta ?: return Repair.NOT_OURS
            // Fragments: keep every complete moof+mdat pair.
            val frags = ArrayList<Frag>()
            while (true) {
                val moofH = header(pos) ?: break
                if (moofH.second != "moof" || pos + moofH.first > len) break
                val mdatH = header(pos + moofH.first) ?: break
                if (mdatH.second != "mdat") break
                val end = pos + moofH.first + mdatH.first
                if (end > len) break
                val info = videoTiming(rd, pos, moofH.first) ?: break
                frags += Frag(pos, end - pos, info.second, info.first)
                pos = end
            }
            if (frags.isEmpty()) return Repair.EMPTY
            if (pos < len) ch.truncate(pos)
            finish(rd, ch, m, frags)
            fd.sync()
            return Repair.REPAIRED
        }

        /** (start, duration) in video ticks of track 1 in the moof at [pos]. */
        private fun videoTiming(rd: FileChannel, pos: Long, size: Long): Pair<Long, Long>? {
            if (size > 4 * 1024 * 1024) return null
            val b = ByteBuffer.allocate(size.toInt()); rd.read(b, pos); b.flip()
            var start: Long? = null
            var dur: Long? = null
            fun walk(from: Int, to: Int, trackId: Int) {
                var p = from
                var tid = trackId
                while (p + 8 <= to) {
                    val s = b.getInt(p); val type = String(ByteArray(4) { b.get(p + 4 + it) }, Charsets.US_ASCII)
                    if (s < 8 || p + s > to) return
                    when (type) {
                        "traf" -> walk(p + 8, p + s, 0)
                        "tfhd" -> tid = b.getInt(p + 12)
                        "tfdt" -> if (tid == 1) start = if (b.get(p + 8).toInt() == 1) b.getLong(p + 12) else b.getInt(p + 12).toLong() and 0xFFFFFFFFL
                        "trun" -> if (tid == 1) {
                            val flags = b.getInt(p + 8) and 0xFFFFFF
                            val count = b.getInt(p + 12)
                            var q = p + 16
                            if (flags and 0x1 != 0) q += 4
                            if (flags and 0x4 != 0) q += 4
                            var sum = 0L
                            for (i in 0 until count) {
                                if (flags and 0x100 != 0) { sum += b.getInt(q).toLong() and 0xFFFFFFFFL; q += 4 }
                                if (flags and 0x200 != 0) q += 4
                                if (flags and 0x400 != 0) q += 4
                                if (flags and 0x800 != 0) q += 4
                            }
                            dur = sum
                        }
                    }
                    p += s
                }
            }
            walk(8, b.limit(), 0)
            val s = start ?: return null
            return s to (dur ?: return null)
        }
    }
}

/** Tiny big-endian box builder. */
private class Buf {
    private var a = ByteArray(4096)
    var size = 0; private set

    private fun ensure(n: Int) { if (size + n > a.size) a = a.copyOf(maxOf(a.size * 2, size + n)) }
    fun u8(v: Int) { ensure(1); a[size++] = v.toByte() }
    fun u16(v: Int) { u8(v ushr 8); u8(v) }
    fun u32(v: Long) { u8((v ushr 24).toInt()); u8((v ushr 16).toInt()); u8((v ushr 8).toInt()); u8(v.toInt()) }
    fun u32(v: Int) = u32(v.toLong())
    fun u64(v: Long) { u32(v ushr 32); u32(v and 0xFFFFFFFFL) }
    fun str(s: String) = bytes(s.toByteArray(Charsets.US_ASCII))
    fun zeros(n: Int) { ensure(n); size += n }
    fun bytes(b: ByteArray) { ensure(b.size); System.arraycopy(b, 0, a, size, b.size); size += b.size }
    fun matrix() { u32(0x00010000); u32(0); u32(0); u32(0); u32(0x00010000); u32(0); u32(0); u32(0); u32(0x40000000) }
    fun patch32(at: Int, v: Long) { a[at] = (v ushr 24).toByte(); a[at + 1] = (v ushr 16).toByte(); a[at + 2] = (v ushr 8).toByte(); a[at + 3] = v.toByte() }

    fun rawBox(type: ByteArray, body: Buf.() -> Unit) {
        val start = size; u32(0); bytes(type); body(); patch32(start, (size - start).toLong())
    }
    fun box(type: String, body: Buf.() -> Unit) = rawBox(type.toByteArray(Charsets.US_ASCII), body)
    fun fullBox(type: String, version: Int, flags: Int, body: Buf.() -> Unit) = box(type) {
        u8(version); u8(flags ushr 16); u8(flags ushr 8); u8(flags); body()
    }
    fun array(): ByteArray = a.copyOf(size)
}
