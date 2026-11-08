package com.example.dashcam

import android.content.ContentUris
import android.media.MediaMetadataRetriever
import java.io.File
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import android.util.Log
import java.text.SimpleDateFormat
import java.util.Locale

data class Clip(
    val uri: Uri,
    val name: String,
    val sizeBytes: Long,
    val durationMs: Long,
    val locked: Boolean,
    val volume: String,
) {
    val recording: Boolean get() = Storage.isOpen(uri)
    /** In DashCam's private storage (not visible to other apps). */
    val isPrivate: Boolean get() = Storage.isPrivate(uri)
}

/**
 * One recording slot: the front and rear clips that share a start time, plus the remote camera's
 * clip ("_remote", recorded by a second phone on the same timeline) when there is one.
 */
data class ClipPair(val key: String, val front: Clip?, val rear: Clip?, val remote: Clip? = null) {
    val clips: List<Clip> get() = listOfNotNull(front, rear, remote)
    val locked: Boolean get() = clips.any { it.locked }
    val recording: Boolean get() = clips.any { it.recording }
    val sizeBytes: Long get() = clips.sumOf { it.sizeBytes }
    val durationMs: Long get() = clips.maxOf { it.durationMs }
    val startMs: Long? get() = try { FMT.parse(key)?.time } catch (_: Exception) { null }

    companion object { val FMT = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US) }
}

/** Finds DashCam clips on every storage volume (internal + SD card) and pairs front/rear. */
object ClipRepo {
    private const val TAG = "ClipRepo"
    private val NAME = Regex("""^(\d{4}-\d{2}-\d{2}_\d{2}-\d{2}-\d{2})_(front|rear|remote)(?: \(\d+\))?\.mp4$""")

    /** Durations read from private files, cached by path + modification time. */
    private val durations = HashMap<String, Long>()

    private fun durationOf(f: File): Long {
        val key = "${f.path}|${f.lastModified()}|${f.length()}"
        synchronized(durations) { durations[key]?.let { return it } }
        val ms = try {
            MediaMetadataRetriever().run {
                try { setDataSource(f.path); extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLong() ?: 0L }
                finally { release() }
            }
        } catch (_: Exception) { 0L }
        synchronized(durations) { durations[key] = ms }
        return ms
    }

    fun load(ctx: Context): List<ClipPair> {
        val front = HashMap<String, Clip>()
        val rear = HashMap<String, Clip>()
        val remote = HashMap<String, Clip>()
        fun add(name: String, clip: () -> Clip) {
            val m = NAME.matchEntire(name) ?: return
            val key = m.groupValues[1]
            val map = when (m.groupValues[2]) { "front" -> front; "rear" -> rear; else -> remote }
            if (key !in map) map[key] = clip()
        }
        // Private clips first, so a clip that exists in both places shows the private copy.
        for ((vol, dir) in Storage.privateDirs(ctx)) {
            for ((sub, locked) in listOf(dir to false, File(dir, "Locked") to true)) {
                for (f in sub.listFiles().orEmpty()) {
                    if (!f.isFile || !f.name.endsWith(".mp4")) continue
                    add(f.name) {
                        val uri = Uri.fromFile(f)
                        Clip(uri, f.name, f.length(), if (Storage.isOpen(uri)) 0 else durationOf(f),
                            locked || Storage.isLockedName(ctx, f.name), vol)
                    }
                }
            }
        }
        for (vol in MediaStore.getExternalVolumeNames(ctx)) {
            val coll = MediaStore.Video.Media.getContentUri(vol)
            try {
                ctx.contentResolver.query(
                    coll,
                    arrayOf(MediaStore.Video.Media._ID, MediaStore.Video.Media.DISPLAY_NAME,
                        MediaStore.Video.Media.SIZE, MediaStore.Video.Media.DURATION,
                        MediaStore.Video.Media.RELATIVE_PATH),
                    "${MediaStore.Video.Media.RELATIVE_PATH} LIKE ?", arrayOf("${Storage.DIR}%"), null
                )?.use { c ->
                    while (c.moveToNext()) {
                        val name = c.getString(1) ?: continue
                        val id = c.getLong(0); val size = c.getLong(2); val dur = c.getLong(3); val rel = c.getString(4)
                        // Same key twice (other volume, or already private) is unlikely; keep the first seen.
                        add(name) {
                            Clip(ContentUris.withAppendedId(coll, id), name, size, dur,
                                rel == Storage.LOCKED_DIR || Storage.isLockedName(ctx, name), vol)
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "query $vol", e)
            }
        }
        return (front.keys + rear.keys + remote.keys).sortedDescending()
            .map { ClipPair(it, front[it], rear[it], remote[it]) }
    }
}
