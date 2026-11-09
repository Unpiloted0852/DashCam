package com.example.dashcam

import android.content.Context
import android.os.SystemClock
import androidx.preference.PreferenceManager
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import kotlin.math.cos

/**
 * OpenStreetMap road tiles (via OpenFreeMap, free, no account) for [RoadMatcher]. Each zoom-14 tile
 * covers ~1.5 km and is ~40 KB; tiles are kept on the phone, so roads driven before work offline.
 * Only the tile's area is ever sent to the server, never the exact position.
 */
object RoadTiles {
    private const val TILEJSON = "https://tiles.openfreemap.org/planet"
    private const val UA = "DashCam-Android (+https://github.com/Unpiloted0852/DashCam)"
    private const val MAX_AGE_MS = 60L * 24 * 3600 * 1000   // refresh road data every ~2 months
    private const val MEM_TILES = 12

    private lateinit var app: Context
    private lateinit var dir: File
    private val exec = Executors.newSingleThreadExecutor()
    private val pending = ConcurrentHashMap.newKeySet<String>()
    private val failedAt = ConcurrentHashMap<String, Long>()
    private val mem = object : LinkedHashMap<String, RoadMatcher.Tile>(16, 0.75f, true) {
        override fun removeEldestEntry(e: MutableMap.MutableEntry<String, RoadMatcher.Tile>?) = size > MEM_TILES
    }
    @Volatile private var template: String? = null

    fun init(ctx: Context) {
        if (::app.isInitialized) return
        app = ctx.applicationContext
        dir = File(app.filesDir, "roadtiles").apply { mkdirs() }
    }

    /**
     * Road segments around a position (the tile it's in, plus neighbours when near an edge), or null
     * if the tile isn't on the phone yet (it is then fetched in the background).
     */
    fun segmentsAround(lat: Double, lon: Double): List<RoadMatcher.Segment>? {
        if (!::app.isInitialized) return null
        val dLat = 120.0 / 111_320
        val dLon = 120.0 / (111_320 * cos(Math.toRadians(lat)).coerceAtLeast(0.1))
        val keys = LinkedHashSet<Pair<Int, Int>>()
        for (la in listOf(lat - dLat, lat, lat + dLat)) for (lo in listOf(lon - dLon, lon, lon + dLon))
            keys += RoadMatcher.tileX(lo) to RoadMatcher.tileY(la)
        val center = RoadMatcher.tileX(lon) to RoadMatcher.tileY(lat)
        val out = ArrayList<RoadMatcher.Segment>()
        var haveCenter = false
        for ((x, y) in keys) {
            val t = tile(x, y) ?: continue
            if (x == center.first && y == center.second) haveCenter = true
            out += t.segments
        }
        return if (haveCenter) out else null
    }

    /** Fetch the tile a little way ahead so it's there before the car gets to it. */
    fun prefetch(lat: Double, lon: Double) { tile(RoadMatcher.tileX(lon), RoadMatcher.tileY(lat)) }

    private fun tile(x: Int, y: Int): RoadMatcher.Tile? {
        val key = "${x}_$y"
        synchronized(mem) { mem[key] }?.let { return it }
        val f = File(dir, "$key.mvt")
        if (f.exists()) {
            val t = try { RoadMatcher.decode(f.readBytes(), x, y) } catch (_: Exception) { null }
            if (t != null) {
                synchronized(mem) { mem[key] = t }
                if (System.currentTimeMillis() - f.lastModified() > MAX_AGE_MS) fetch(x, y)   // refresh, keep using old
                return t
            }
            f.delete()
        }
        fetch(x, y)
        return null
    }

    private fun fetch(x: Int, y: Int) {
        val key = "${x}_$y"
        failedAt[key]?.let { if (SystemClock.elapsedRealtime() - it < 60_000) return }
        if (!pending.add(key)) return
        exec.execute {
            try {
                val url = tileUrl()?.replace("{z}", RoadMatcher.ZOOM.toString())?.replace("{x}", "$x")?.replace("{y}", "$y")
                    ?: throw IllegalStateException("no tile address")
                val bytes = get(url)
                val t = RoadMatcher.decode(bytes, x, y)   // validate before saving
                val tmp = File(dir, "$key.tmp"); tmp.writeBytes(bytes); tmp.renameTo(File(dir, "$key.mvt"))
                synchronized(mem) { mem[key] = t }
                failedAt.remove(key)
            } catch (e: Exception) {
                failedAt[key] = SystemClock.elapsedRealtime()
                if (e is java.io.FileNotFoundException) template = null   // tile set replaced: re-read the address
            } finally { pending.remove(key) }
        }
    }

    /** Current tile address (OpenFreeMap publishes new versions; the TileJSON names the current one). */
    private fun tileUrl(): String? {
        template?.let { return it }
        val p = PreferenceManager.getDefaultSharedPreferences(app)
        val saved = p.getString("road_tiles_url", null)
        val savedAt = p.getLong("road_tiles_url_at", 0)
        if (saved != null && System.currentTimeMillis() - savedAt < 7L * 24 * 3600 * 1000) { template = saved; return saved }
        val fresh = try {
            JSONObject(String(get(TILEJSON), Charsets.UTF_8)).getJSONArray("tiles").getString(0)
        } catch (_: Exception) { null }
        if (fresh != null) p.edit().putString("road_tiles_url", fresh).putLong("road_tiles_url_at", System.currentTimeMillis()).apply()
        template = fresh ?: saved
        return template
    }

    private fun get(url: String): ByteArray {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 10_000; c.readTimeout = 20_000
        c.setRequestProperty("User-Agent", UA)
        try {
            if (c.responseCode != 200) throw java.io.FileNotFoundException("HTTP ${c.responseCode}")
            // HttpURLConnection undoes the gzip transfer encoding itself.
            return c.inputStream.use { it.readBytes() }
        } finally { c.disconnect() }
    }
}
