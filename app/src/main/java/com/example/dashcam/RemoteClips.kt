package com.example.dashcam

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.ThumbnailUtils
import android.net.Uri
import android.os.SystemClock
import android.util.Size
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URL
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.concurrent.Executors

/** A clip on the remote camera phone, as listed by its [ClipServer]. */
data class RemoteClip(val name: String, val key: String, val size: Long, val durationMs: Long, val locked: Boolean)

/**
 * Remote camera: a tiny HTTP server on the Wi‑Fi Direct group so the main phone can list the remote's
 * clips, fetch thumbnails and download them in full quality. Same token as the live view.
 */
class ClipServer(ctx: Context, private val token: String) {
    private val app = ctx.applicationContext
    private val server = ServerSocket().apply { reuseAddress = true; bind(InetSocketAddress(PORT)) }
    private val pool = Executors.newFixedThreadPool(4)
    @Volatile private var running = true
    @Volatile private var cache: Pair<Long, List<Clip>>? = null
    private val transfers = java.util.concurrent.atomic.AtomicInteger()
    /** A clip download is in progress (don't move the group to another channel now). */
    val busy: Boolean get() = transfers.get() > 0

    init { Thread({ accept() }, "clip-server").apply { isDaemon = true; start() } }

    private fun accept() {
        while (running) {
            val s = try { server.accept() } catch (_: Exception) { break }
            try { pool.execute { handle(s) } } catch (_: Exception) { try { s.close() } catch (_: Exception) {} }
        }
    }

    private fun clips(): List<Clip> {
        cache?.let { (at, list) -> if (SystemClock.elapsedRealtime() - at < 10_000) return list }
        val list = ClipRepo.load(app).mapNotNull { it.remote }.filter { !it.recording }
        cache = SystemClock.elapsedRealtime() to list
        return list
    }

    private fun handle(s: Socket) {
        s.use { sock ->
            try {
                sock.soTimeout = 15_000
                // Header lines are read byte by byte so that a request body (an update) stays in the stream.
                val raw = java.io.BufferedInputStream(sock.getInputStream(), 64 * 1024)
                fun line(): String? {
                    val b = StringBuilder()
                    while (true) {
                        val c = raw.read()
                        if (c < 0) return if (b.isEmpty()) null else b.toString()
                        if (c == '\n'.code) return b.toString().trimEnd('\r')
                        if (b.length < 4096) b.append(c.toChar())
                    }
                }
                val req = line() ?: return
                var range = 0L
                var bodyLen = -1L
                while (true) {
                    val h = line() ?: break
                    if (h.isEmpty()) break
                    if (h.startsWith("Range:", true)) range = h.substringAfter("bytes=").substringBefore("-").trim().toLongOrNull() ?: 0L
                    if (h.startsWith("Content-Length:", true)) bodyLen = h.substringAfter(":").trim().toLongOrNull() ?: -1L
                }
                val target = req.split(" ").getOrNull(1) ?: return
                val path = target.substringBefore("?")
                val q = target.substringAfter("?", "").split("&").filter { it.contains("=") }
                    .associate { it.substringBefore("=") to URLDecoder.decode(it.substringAfter("="), "UTF-8") }
                val out = sock.getOutputStream()
                val addr = sock.inetAddress.hostAddress ?: ""
                if (!addr.startsWith("192.168.49.") || q["t"] != token) { reply(out, 403, "text/plain", "forbidden".toByteArray()); return }
                when (path) {
                    "/clips" -> {
                        val arr = JSONArray()
                        clips().forEach { c ->
                            arr.put(JSONObject().put("name", c.name).put("size", c.sizeBytes).put("dur", c.durationMs).put("locked", c.locked))
                        }
                        reply(out, 200, "application/json", arr.toString().toByteArray())
                    }
                    "/thumb" -> {
                        val c = clips().firstOrNull { it.name == q["name"] } ?: return reply(out, 404, "text/plain", ByteArray(0))
                        val bmp = Storage.fileOf(c.uri)?.let { ThumbnailUtils.createVideoThumbnail(it, Size(480, 270), null) }
                            ?: app.contentResolver.loadThumbnail(c.uri, Size(480, 270), null)
                        val b = ByteArrayOutputStream().also { bmp.compress(Bitmap.CompressFormat.JPEG, 80, it) }.toByteArray()
                        reply(out, 200, "image/jpeg", b)
                    }
                    "/file" -> {
                        val c = clips().firstOrNull { it.name == q["name"] } ?: return reply(out, 404, "text/plain", ByteArray(0))
                        val len = c.sizeBytes
                        val from = range.coerceIn(0, len)
                        val head = if (from > 0) "HTTP/1.1 206 Partial Content\r\nContent-Range: bytes $from-${len - 1}/$len\r\n"
                                   else "HTTP/1.1 200 OK\r\n"
                        out.write((head + "Content-Type: video/mp4\r\nContent-Length: ${len - from}\r\nConnection: close\r\n\r\n").toByteArray())
                        transfers.incrementAndGet()
                        try {
                            app.contentResolver.openInputStream(c.uri)?.use { fin ->
                                var skip = from
                                while (skip > 0) { val n = fin.skip(skip); if (n <= 0) break; skip -= n }
                                fin.copyTo(out, 256 * 1024)
                            }
                        } finally { transfers.decrementAndGet() }
                        out.flush()
                    }
                    "/update" -> {
                        // The main phone sends a newer DashCam: save it, answer, then install.
                        if (bodyLen !in 1_000_000L..200_000_000L) return reply(out, 400, "text/plain", "bad size".toByteArray())
                        val f = File(app.cacheDir, "update.apk")
                        sock.soTimeout = 30_000
                        var got = 0L
                        f.outputStream().use { fo ->
                            val buf = ByteArray(64 * 1024)
                            while (got < bodyLen) {
                                val n = raw.read(buf, 0, minOf(buf.size.toLong(), bodyLen - got).toInt()); if (n < 0) break
                                fo.write(buf, 0, n); got += n
                            }
                        }
                        val problem = when {
                            got != bodyLen -> "transfer incomplete"
                            Updater.apkVersion(app, f) == null -> "not a DashCam update"
                            !app.packageManager.canRequestPackageInstalls() -> "installing updates isn't allowed on the remote yet"
                            else -> null
                        }
                        val o = JSONObject().put("ok", problem == null)
                        problem?.let { o.put("err", it) }
                        reply(out, 200, "application/json", o.toString().toByteArray())
                        EventLog.log("Update", "update from the main phone: " + (problem ?: "received ${got / 1024} KB"))
                        if (problem == null) Thread({
                            Thread.sleep(300)   // let the answer reach the main phone first
                            Updater.installFile(app, f)?.let { EventLog.log("Update", "not installed: $it") }
                        }, "remote-update").start() else f.delete()
                    }
                    else -> reply(out, 404, "text/plain", ByteArray(0))
                }
            } catch (e: Exception) {
                if (running) EventLog.log("Clips", "remote clip request: ${e.message}")
            }
        }
    }

    private fun reply(out: java.io.OutputStream, code: Int, type: String, body: ByteArray) {
        out.write(("HTTP/1.1 $code ${if (code == 200) "OK" else "Error"}\r\nContent-Type: $type\r\n" +
            "Content-Length: ${body.size}\r\nConnection: close\r\n\r\n").toByteArray())
        out.write(body); out.flush()
    }

    fun stop() {
        running = false
        try { server.close() } catch (_: Exception) {}
        pool.shutdownNow()
    }

    companion object {
        const val PORT = 8766
        private val KEY = Regex("""^(\d{4}-\d{2}-\d{2}_\d{2}-\d{2}-\d{2})_remote""")

        private fun url(addr: InetAddress, token: String, path: String, name: String? = null) =
            URL("http://${addr.hostAddress}:$PORT$path?t=$token" + (name?.let { "&name=" + URLEncoder.encode(it, "UTF-8") } ?: ""))

        private fun open(u: URL, timeout: Int = 8000) = (u.openConnection() as HttpURLConnection).apply {
            connectTimeout = 4000; readTimeout = timeout; useCaches = false
        }

        /**
         * Main phone: send [apk] (this phone's own DashCam) to the remote for it to install.
         * Returns null if the remote accepted it, else what went wrong. Blocking.
         */
        fun pushUpdate(addr: InetAddress, token: String, apk: File): String? {
            val c = open(url(addr, token, "/update"), 30_000)
            return try {
                c.requestMethod = "POST"; c.doOutput = true
                c.setRequestProperty("Content-Type", "application/vnd.android.package-archive")
                c.setFixedLengthStreamingMode(apk.length())
                c.outputStream.use { o -> apk.inputStream().use { it.copyTo(o, 64 * 1024) } }
                when (val code = c.responseCode) {
                    200 -> JSONObject(c.inputStream.bufferedReader().readText()).let { if (it.optBoolean("ok")) null else it.optString("err", "refused") }
                    404 -> "the remote's DashCam is too old to be updated this way — update it by hand once"
                    else -> "HTTP $code"
                }
            } catch (e: Exception) { e.message ?: e.toString() } finally { c.disconnect() }
        }

        /** Main phone: list the remote's clips. Blocking. */
        fun list(addr: InetAddress, token: String): List<RemoteClip> {
            val c = open(url(addr, token, "/clips"))
            try {
                if (c.responseCode != 200) throw java.io.IOException("HTTP ${c.responseCode}")
                val arr = JSONArray(c.inputStream.bufferedReader().readText())
                return (0 until arr.length()).mapNotNull { i ->
                    val o = arr.getJSONObject(i)
                    val name = o.getString("name")
                    val key = KEY.find(name)?.groupValues?.get(1) ?: return@mapNotNull null
                    RemoteClip(name, key, o.optLong("size"), o.optLong("dur"), o.optBoolean("locked"))
                }
            } finally { c.disconnect() }
        }

        fun thumb(addr: InetAddress, token: String, name: String): Bitmap? {
            val c = open(url(addr, token, "/thumb", name), 15_000)
            try { return if (c.responseCode == 200) BitmapFactory.decodeStream(c.inputStream) else null }
            finally { c.disconnect() }
        }

        /**
         * Download [clip] into [dir] (resuming a partial download). [progress] gets bytes done.
         * Returns the finished file. Blocking.
         */
        fun download(addr: InetAddress, token: String, clip: RemoteClip, dir: File, progress: (Long) -> Unit): File {
            dir.mkdirs()
            val part = File(dir, clip.name + ".part")
            val have = if (part.exists()) part.length() else 0L
            val c = open(url(addr, token, "/file", clip.name), 30_000)
            if (have > 0) c.setRequestProperty("Range", "bytes=$have-")
            try {
                val code = c.responseCode
                if (code != 200 && code != 206) throw java.io.IOException("HTTP $code")
                val append = code == 206
                var done = if (append) have else 0L
                java.io.FileOutputStream(part, append).use { fout ->
                    c.inputStream.use { inp ->
                        val buf = ByteArray(256 * 1024)
                        var last = 0L
                        while (true) {
                            val n = inp.read(buf); if (n < 0) break
                            fout.write(buf, 0, n); done += n
                            val now = SystemClock.elapsedRealtime()
                            if (now - last > 300) { last = now; progress(done) }
                        }
                    }
                }
                if (done < clip.size) throw java.io.IOException("connection ended early")
                val dest = File(dir, clip.name)
                if (!part.renameTo(dest)) throw java.io.IOException("couldn't save ${dest.name}")
                return dest
            } finally { c.disconnect() }
        }
    }
}

/** Remote clip names as Uris for the thumbnail cache. */
fun RemoteClip.thumbKey(): Uri = Uri.parse("remote-clip:$name")
