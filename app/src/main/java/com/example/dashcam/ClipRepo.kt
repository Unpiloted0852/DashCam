package com.example.dashcam

import android.content.ContentUris
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

    fun load(ctx: Context): List<ClipPair> {
        val front = HashMap<String, Clip>()
        val rear = HashMap<String, Clip>()
        val remote = HashMap<String, Clip>()
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
                        val m = NAME.matchEntire(name) ?: continue
                        val key = "${m.groupValues[1]}"
                        val clip = Clip(ContentUris.withAppendedId(coll, c.getLong(0)), name, c.getLong(2),
                            c.getLong(3), c.getString(4) == Storage.LOCKED_DIR || Storage.isLockedName(ctx, name), vol)
                        // Same key on two volumes is unlikely; keep the first seen.
                        when (m.groupValues[2]) {
                            "front" -> front.putIfAbsent(key, clip)
                            "rear" -> rear.putIfAbsent(key, clip)
                            else -> remote.putIfAbsent(key, clip)
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
