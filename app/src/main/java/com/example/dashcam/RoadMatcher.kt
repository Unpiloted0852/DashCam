package com.example.dashcam

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.sinh
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * Which road the car is on, the way navigation apps do it: snap the GPS position onto the road
 * shapes nearby, preferring roads that run the way the car is heading, and don't jump to a cross
 * street at every junction. Road shapes and names come from OpenStreetMap vector tiles
 * (OpenMapTiles schema, zoom 14). Pure Kotlin so it can be unit-tested.
 */
object RoadMatcher {
    const val ZOOM = 14

    /** A straight piece of road. Coordinates in degrees. */
    class Segment(val lat1: Double, val lon1: Double, val lat2: Double, val lon2: Double, val road: Road)
    class Road(val name: String?, val ref: String?, val cls: String) {
        /** What the stamp shows: motorways by number, other roads by name (number if unnamed). */
        val label: String? get() =
            if (cls == "motorway" || cls == "trunk") ref ?: name else name ?: ref
    }

    /** All roads of one tile: named ones and every road (named or not). */
    class Tile(val segments: List<Segment>)

    // ---------------------------------------------------------------- tile coordinates

    fun tileX(lon: Double, z: Int = ZOOM) = floor((lon + 180) / 360 * (1 shl z)).toInt()
    fun tileY(lat: Double, z: Int = ZOOM): Int {
        val r = Math.toRadians(lat)
        return floor((1 - ln(tan(r) + 1 / cos(r)) / PI) / 2 * (1 shl z)).toInt()
    }

    private fun lonOf(x: Double, z: Int) = x / (1 shl z) * 360 - 180
    private fun latOf(y: Double, z: Int) = Math.toDegrees(atan(sinh(PI * (1 - 2 * y / (1 shl z)))))

    // ---------------------------------------------------------------- vector tile decoding

    /** Decode the road layers of a (decompressed) Mapbox vector tile. */
    fun decode(bytes: ByteArray, x: Int, y: Int, z: Int = ZOOM): Tile {
        val segs = ArrayList<Segment>()
        val r = Pb(bytes)
        while (r.more()) {
            val (field, wire) = r.tag()
            if (field == 3 && wire == 2) decodeLayer(r.sub(), x, y, z, segs) else r.skip(wire)
        }
        return Tile(segs)
    }

    private fun decodeLayer(r: Pb, tx: Int, ty: Int, z: Int, out: MutableList<Segment>) {
        var name = ""
        val keys = ArrayList<String>()
        val values = ArrayList<Any?>()
        val features = ArrayList<Pb>()
        var extent = 4096
        while (r.more()) {
            val (f, w) = r.tag()
            when {
                f == 1 && w == 2 -> name = r.string()
                f == 2 && w == 2 -> features += r.sub()
                f == 3 && w == 2 -> keys += r.string()
                f == 4 && w == 2 -> values += value(r.sub())
                f == 5 && w == 0 -> extent = r.varint().toInt()
                else -> r.skip(w)
            }
        }
        // transportation_name: named roads (with geometry); transportation: every road (no names).
        if (name != "transportation_name" && name != "transportation") return
        val named = name == "transportation_name"
        for (fr in features) {
            var type = 0
            var tags = IntArray(0)
            var geom = IntArray(0)
            while (fr.more()) {
                val (f, w) = fr.tag()
                when {
                    f == 2 && w == 2 -> tags = fr.packed()
                    f == 3 && w == 0 -> type = fr.varint().toInt()
                    f == 4 && w == 2 -> geom = fr.packed()
                    else -> fr.skip(w)
                }
            }
            if (type != 2) continue   // lines only
            val props = HashMap<String, Any?>()
            var i = 0
            while (i + 1 < tags.size) { props[keys[tags[i]]] = values[tags[i + 1]]; i += 2 }
            val cls = props["class"] as? String ?: continue
            if (cls in NOT_ROADS) continue
            val road = if (named) Road(props["name"] as? String, (props["ref"] as? String)?.takeIf { it.isNotBlank() }, cls)
                       else Road(null, null, cls)
            if (named && road.label == null) continue
            lines(geom, extent) { x1, y1, x2, y2 ->
                val s = Segment(latOf(ty + y1, z), lonOf(tx + x1, z), latOf(ty + y2, z), lonOf(tx + x2, z), road)
                out += s
            }
        }
    }

    /** Line geometry commands -> segments, in tile units (0..1). */
    private inline fun lines(g: IntArray, extent: Int, seg: (Double, Double, Double, Double) -> Unit) {
        var cx = 0; var cy = 0
        var i = 0
        while (i < g.size) {
            val cmd = g[i] and 7; val count = g[i] ushr 3; i++
            for (k in 0 until count) {
                if (cmd == 7) continue
                if (i + 1 >= g.size) return
                val nx = cx + zz(g[i]); val ny = cy + zz(g[i + 1]); i += 2
                if (cmd == 2) seg(cx.toDouble() / extent, cy.toDouble() / extent, nx.toDouble() / extent, ny.toDouble() / extent)
                cx = nx; cy = ny
            }
        }
    }

    private fun zz(n: Int) = (n ushr 1) xor -(n and 1)

    private fun value(r: Pb): Any? {
        var v: Any? = null
        while (r.more()) {
            val (f, w) = r.tag()
            v = when {
                f == 1 && w == 2 -> r.string()
                f == 2 && w == 5 -> Float.fromBits(r.fixed32())
                f == 3 && w == 1 -> Double.fromBits(r.fixed64())
                (f == 4 || f == 5) && w == 0 -> r.varint()
                f == 6 && w == 0 -> r.varint().let { (it ushr 1) xor -(it and 1) }
                f == 7 && w == 0 -> r.varint() != 0L
                else -> { r.skip(w); v }
            }
        }
        return v
    }

    /** Things in the transportation layers that aren't roads you drive on. */
    private val NOT_ROADS = setOf("path", "track", "rail", "transit", "ferry", "aerialway", "bridge", "pier",
        "busway", "bus_guideway", "raceway", "path_construction", "track_construction", "rail_construction",
        "transit_construction")

    // ---------------------------------------------------------------- matching

    class Match(val road: Road?, val distM: Double)

    /**
     * Best road for a position. [bearing] (degrees) only when moving: roads running the other way
     * (e.g. a cross street) are then penalised. [current]: the road matched last time, kept unless
     * another is clearly better, so the name doesn't flicker at junctions.
     * Returns a match with road = null when on an unnamed road (car park, driveway) or none nearby.
     */
    fun match(segments: List<Segment>, lat: Double, lon: Double, bearing: Float?, accuracyM: Float,
              current: Road?): Match? {
        val radius = maxOf(30.0, accuracyM * 1.5)
        val mPerLat = 111_320.0
        val mPerLon = 111_320.0 * cos(Math.toRadians(lat))
        var bestNamed: Pair<Segment, Double>? = null
        var bestAny: Pair<Segment, Double>? = null
        var bestCurrent: Double? = null
        for (s in segments) {
            // Local metres, current position at the origin.
            val ax = (s.lon1 - lon) * mPerLon; val ay = (s.lat1 - lat) * mPerLat
            val bx = (s.lon2 - lon) * mPerLon; val by = (s.lat2 - lat) * mPerLat
            val d = distToSegment(ax, ay, bx, by)
            if (d > radius) continue
            var score = d
            if (bearing != null) {
                val dir = Math.toDegrees(atan2(bx - ax, by - ay))   // 0 = north
                var diff = abs(((bearing - dir) % 180 + 180) % 180)
                if (diff > 90) diff = 180 - diff                      // either direction of travel
                score += diff / 90.0 * HEADING_PENALTY_M
            }
            val named = s.road.label != null
            if (named && (bestNamed == null || score < bestNamed.second)) bestNamed = s to score
            if (bestAny == null || score < bestAny.second) bestAny = s to score
            if (current != null && named && s.road.label == current.label && (bestCurrent == null || score < bestCurrent)) bestCurrent = score
        }
        val any = bestAny ?: return null
        val nm = bestNamed
        // Clearly on an unnamed road (only the all-roads layer has it there): no street name.
        if (any.first.road.label == null && (nm == null || any.second + UNNAMED_MARGIN_M < nm.second)) return Match(null, any.second)
        nm ?: return Match(null, any.second)
        // Stick with the current road unless another is clearly better.
        if (bestCurrent != null && bestCurrent <= nm.second + STICKY_M) return Match(current, bestCurrent)
        return Match(nm.first.road, nm.second)
    }

    private const val HEADING_PENALTY_M = 40.0
    private const val STICKY_M = 6.0
    private const val UNNAMED_MARGIN_M = 8.0

    internal fun distToSegment(ax: Double, ay: Double, bx: Double, by: Double): Double {
        val dx = bx - ax; val dy = by - ay
        val len2 = dx * dx + dy * dy
        val t = if (len2 == 0.0) 0.0 else ((-ax) * dx + (-ay) * dy) / len2
        val c = t.coerceIn(0.0, 1.0)
        val px = ax + c * dx; val py = ay + c * dy
        return sqrt(px * px + py * py)
    }

    // ---------------------------------------------------------------- tiny protobuf reader

    private class Pb(val b: ByteArray, var pos: Int = 0, val end: Int = b.size) {
        fun more() = pos < end
        fun varint(): Long {
            var shift = 0; var r = 0L
            while (true) {
                val x = b[pos++].toInt() and 0xFF
                r = r or ((x and 0x7F).toLong() shl shift)
                if (x and 0x80 == 0) return r
                shift += 7
            }
        }
        fun tag(): Pair<Int, Int> { val t = varint().toInt(); return (t ushr 3) to (t and 7) }
        fun len(): Int = varint().toInt()
        fun sub(): Pb { val n = len(); val p = Pb(b, pos, pos + n); pos += n; return p }
        fun string(): String { val n = len(); val s = String(b, pos, n, Charsets.UTF_8); pos += n; return s }
        fun fixed32(): Int { var v = 0; for (i in 0 until 4) v = v or ((b[pos + i].toInt() and 0xFF) shl (8 * i)); pos += 4; return v }
        fun fixed64(): Long { var v = 0L; for (i in 0 until 8) v = v or ((b[pos + i].toLong() and 0xFF) shl (8 * i)); pos += 8; return v }
        fun packed(): IntArray {
            val n = len(); val stop = pos + n
            val out = ArrayList<Int>()
            while (pos < stop) out += varint().toInt()
            return out.toIntArray()
        }
        fun skip(wire: Int) {
            when (wire) {
                0 -> varint()
                1 -> pos += 8
                2 -> { val n = len(); pos += n }
                5 -> pos += 4
                else -> throw IllegalStateException("wire $wire")
            }
        }
    }
}
