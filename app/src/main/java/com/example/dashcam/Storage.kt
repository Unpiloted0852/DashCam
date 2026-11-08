package com.example.dashcam

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.os.StatFs
import android.os.storage.StorageManager
import android.os.storage.StorageVolume
import android.provider.MediaStore
import android.util.Log
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import androidx.preference.PreferenceManager

/**
 * Where clips live. By default in DashCam's private app storage (Android/data/<app>/files/Movies on
 * each volume): other apps and the gallery can't see them, so sharing is always deliberate (Share or
 * Save to gallery). Optionally in the shared Movies/DashCam folder, visible to other apps.
 * Locked clips sit in a Locked/ subfolder and are never loop-deleted.
 * Private clips are identified by file:// URIs, shared ones by MediaStore content:// URIs.
 */
object Storage {
    private const val TAG = "Storage"
    const val DIR = "Movies/DashCam/"
    const val LOCKED_DIR = "Movies/DashCam/Locked/"
    private const val MIN_FREE_BYTES = 1L * 1024 * 1024 * 1024

    /** Clips currently being written (by URI). Guarded by [lock] together with create/delete. */
    private val openIds = HashSet<String>()

    const val EXPORT_DIR = "Movies/DashCam Exports/"
    const val PREF_PRIVATE = "clip_storage"

    /** New clips go to private storage unless the user chose the shared folder. */
    fun privateMode(ctx: Context): Boolean =
        PreferenceManager.getDefaultSharedPreferences(ctx).getString(PREF_PRIVATE, "private") != "shared"

    fun isPrivate(uri: Uri) = uri.scheme == "file"
    fun fileOf(uri: Uri): File? = if (isPrivate(uri)) uri.path?.let { File(it) } else null

    /** DashCam's private clip folder on each mounted volume, keyed by MediaStore volume name. */
    fun privateDirs(ctx: Context): Map<String, File> {
        val sm = ctx.getSystemService(StorageManager::class.java)
        val out = LinkedHashMap<String, File>()
        for (dir in ctx.getExternalFilesDirs(Environment.DIRECTORY_MOVIES)) {
            if (dir == null) continue
            val sv = try { sm.getStorageVolume(dir) } catch (_: Exception) { null }
            val name = when {
                sv == null -> continue
                Build.VERSION.SDK_INT >= 30 -> sv.mediaStoreVolumeName ?: continue
                sv.isPrimary -> MediaStore.VOLUME_EXTERNAL_PRIMARY
                else -> sv.uuid?.lowercase() ?: continue
            }
            out[name] = dir
        }
        return out
    }

    private fun privateDir(ctx: Context, volume: String): File =
        privateDirs(ctx)[volume] ?: privateDirs(ctx)[MediaStore.VOLUME_EXTERNAL_PRIMARY]
        ?: ctx.getExternalFilesDir(Environment.DIRECTORY_MOVIES) ?: File(ctx.filesDir, "Movies")
    private val lock = Any()

    private fun collection(volume: String): Uri = MediaStore.Video.Media.getContentUri(volume)

    data class Volume(val name: String, val label: String)

    /** Shared-storage volumes clips can go to: internal storage plus any SD card / USB drive. */
    fun volumes(ctx: Context): List<Volume> {
        return MediaStore.getExternalVolumeNames(ctx).sortedBy { it != MediaStore.VOLUME_EXTERNAL_PRIMARY }.map { name ->
            val sv = storageVolume(ctx, name)
            val desc = when {
                name == MediaStore.VOLUME_EXTERNAL_PRIMARY -> "Internal storage"
                sv != null -> sv.getDescription(ctx)
                else -> "SD card ($name)"
            }
            val free = freeBytes(ctx, name)
            val freeTxt = if (free == Long.MAX_VALUE) "" else String.format(" — %.1f GB free", free / 1e9)
            Volume(name, desc + freeTxt)
        }
    }

    /** The chosen volume if it is mounted, otherwise internal storage. */
    fun resolveVolume(ctx: Context, wanted: String): String =
        if (wanted in MediaStore.getExternalVolumeNames(ctx)) wanted else MediaStore.VOLUME_EXTERNAL_PRIMARY

    private fun storageVolume(ctx: Context, name: String): StorageVolume? {
        val sm = ctx.getSystemService(StorageManager::class.java)
        return if (Build.VERSION.SDK_INT >= 30) sm.storageVolumes.firstOrNull { it.mediaStoreVolumeName == name }
        else try { sm.getStorageVolume(MediaStore.Video.Media.getContentUri(name)) } catch (_: Exception) { null }
    }

    fun create(ctx: Context, volume: String, displayName: String): Pair<Uri, ParcelFileDescriptor> {
        if (privateMode(ctx)) {
            val dir = privateDir(ctx, volume).apply { mkdirs() }
            var f = File(dir, displayName)
            var n = 1
            while (f.exists() || File(dir, "Locked/${f.name}").exists()) f = File(dir, displayName.removeSuffix(".mp4") + " (${n++}).mp4")
            val uri = Uri.fromFile(f)
            synchronized(lock) { openIds += uri.toString() }
            val pfd = ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_WRITE or
                ParcelFileDescriptor.MODE_CREATE or ParcelFileDescriptor.MODE_TRUNCATE)
            return uri to pfd
        }
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, displayName)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, DIR)
        }
        val uri = synchronized(lock) {
            ctx.contentResolver.insert(collection(volume), values)?.also { openIds += it.toString() }
        } ?: throw IllegalStateException("MediaStore insert failed")
        val pfd = ctx.contentResolver.openFileDescriptor(uri, "rw")
            ?: throw IllegalStateException("open failed")
        return uri to pfd
    }

    fun isOpen(uri: Uri): Boolean = synchronized(lock) { uri.toString() in openIds }

    /** Move a private file between the clip folder and its Locked/ subfolder; returns its new URI. */
    private fun movePrivate(f: File, locked: Boolean): Uri {
        val inLocked = f.parentFile?.name == "Locked"
        if (inLocked == locked) return Uri.fromFile(f)
        val base = if (inLocked) f.parentFile!!.parentFile!! else f.parentFile!!
        val dest = File(if (locked) File(base, "Locked").apply { mkdirs() } else base, f.name)
        return if (f.renameTo(dest)) Uri.fromFile(dest) else Uri.fromFile(f)
    }

    /** Permanently delete a clip this app can delete directly (private, or shared and ours). */
    fun delete(ctx: Context, uri: Uri): Boolean {
        val name = fileOf(uri)?.name
        val ok = fileOf(uri)?.delete() ?: (try { ctx.contentResolver.delete(uri, null, null) > 0 } catch (_: SecurityException) { false })
        if (ok && name != null) { forgetStart(ctx, name); rememberLocked(ctx, name, false) }
        return ok
    }

    /** Move a clip between Movies/DashCam and Movies/DashCam/Locked. Throws SecurityException if not ours. */
    fun setLocked(ctx: Context, uri: Uri, locked: Boolean) {
        fileOf(uri)?.let { f -> rememberLocked(ctx, f.name, locked); movePrivate(f, locked); return }
        val name = displayName(ctx, uri)
        name?.let { rememberLocked(ctx, it, locked) }
        val oldPath = dataPath(ctx, uri)
        val v = ContentValues().apply { put(MediaStore.Video.Media.RELATIVE_PATH, if (locked) LOCKED_DIR else DIR) }
        ctx.contentResolver.update(uri, v, null, null)
        rescanAfterMove(ctx, oldPath, name, locked)
    }

    /** Re-index the DashCam folders, e.g. to pick up clips the media index missed. */
    fun rescanFolders(ctx: Context) {
        for (vol in MediaStore.getExternalVolumeNames(ctx)) {
            val root = if (vol == MediaStore.VOLUME_EXTERNAL_PRIMARY) Environment.getExternalStorageDirectory()
                       else if (Build.VERSION.SDK_INT >= 30) storageVolume(ctx, vol)?.directory else null
            val dir = File(root ?: continue, DIR)
            val files = (dir.listFiles().orEmpty().toList() + File(dir, "Locked").listFiles().orEmpty().toList())
                .filter { it.isFile && it.name.endsWith(".mp4") }.map { it.path }
            // Index entries whose file is gone (moved/deleted outside the app): scanning the old path drops them.
            val stale = ArrayList<String>()
            try {
                @Suppress("DEPRECATION")
                ctx.contentResolver.query(MediaStore.Video.Media.getContentUri(vol), arrayOf(MediaStore.MediaColumns.DATA),
                    "${MediaStore.Video.Media.RELATIVE_PATH} LIKE ?", arrayOf("$DIR%"), null)?.use { c ->
                    while (c.moveToNext()) c.getString(0)?.let { if (!File(it).exists()) stale += it }
                }
            } catch (_: Exception) {}
            val all = files + stale
            if (all.isNotEmpty()) MediaScannerConnection.scanFile(ctx, all.toTypedArray(), null, null)
        }
    }

    /** Mark a clip finished so loop recording may delete it later. */
    fun markClosed(uri: Uri) = synchronized(lock) { openIds -= uri.toString() }

    /**
     * Protect a just-finished clip. The name is remembered first (loop deletion never removes a
     * remembered name), then the file is moved to Locked/. The move can race with the media scan
     * that runs when the file is closed, so it is checked and retried.
     */
    fun lock(ctx: Context, uri: Uri) {
        fileOf(uri)?.let { f -> rememberLocked(ctx, f.name, true); if (f.exists()) movePrivate(f, true); return }
        val name = displayName(ctx, uri)
        if (name != null) rememberLocked(ctx, name, true)
        // Closing the file starts a media scan; let it finish first or it can undo the move.
        val oldPath = dataPath(ctx, uri)
        oldPath?.let { scanAndWait(ctx, it) }
        for (attempt in 1..6) {
            try {
                val v = ContentValues().apply { put(MediaStore.Video.Media.RELATIVE_PATH, LOCKED_DIR) }
                val n = ctx.contentResolver.update(uri, v, null, null)
                if (n > 0 && isInLockedDir(ctx, uri, name)) {
                    rescanAfterMove(ctx, oldPath, name, true)
                    return
                }
                Log.w(TAG, "lock attempt $attempt for $uri updated $n rows; retrying")
            } catch (e: Exception) {
                Log.w(TAG, "lock attempt $attempt failed for $uri: ${e.message}")
            }
            Thread.sleep(500)
        }
        Log.e(TAG, "could not move $uri to Locked/ (still protected by name)")
    }

    /** Absolute file path of a MediaStore item (read-only use, for the media scanner). */
    private fun dataPath(ctx: Context, uri: Uri): String? = try {
        @Suppress("DEPRECATION")
        ctx.contentResolver.query(uri, arrayOf(MediaStore.MediaColumns.DATA), null, null, null)
            ?.use { if (it.moveToFirst()) it.getString(0) else null }
    } catch (_: Exception) { null }

    private fun scanAndWait(ctx: Context, path: String) {
        val done = CountDownLatch(1)
        MediaScannerConnection.scanFile(ctx, arrayOf(path), null) { _, _ -> done.countDown() }
        done.await(5, TimeUnit.SECONDS)
    }

    /** Make the media index match the file's new folder (and forget the old location). */
    private fun rescanAfterMove(ctx: Context, oldPath: String?, name: String?, locked: Boolean) {
        if (oldPath == null || name == null) return
        val base = File(oldPath).parentFile?.let { if (it.name == "Locked") it.parentFile else it } ?: return
        val newPath = File(if (locked) File(base, "Locked") else base, name).path
        scanAndWait(ctx, newPath)
        if (newPath != oldPath) scanAndWait(ctx, oldPath)
    }

    private fun displayName(ctx: Context, uri: Uri): String? = try {
        ctx.contentResolver.query(uri, arrayOf(MediaStore.Video.Media.DISPLAY_NAME), null, null, null)
            ?.use { if (it.moveToFirst()) it.getString(0) else null }
    } catch (_: Exception) { null }

    /** True if the clip (by uri, or by name in case the scan gave it a new id) sits in Locked/. */
    private fun isInLockedDir(ctx: Context, uri: Uri, name: String?): Boolean = try {
        val byUri = ctx.contentResolver.query(uri, arrayOf(MediaStore.Video.Media.RELATIVE_PATH), null, null, null)
            ?.use { if (it.moveToFirst()) it.getString(0) else null }
        if (byUri != null) byUri == LOCKED_DIR
        else name != null && ctx.contentResolver.query(
            MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL),
            arrayOf(MediaStore.Video.Media._ID),
            "${MediaStore.Video.Media.DISPLAY_NAME}=? AND ${MediaStore.Video.Media.RELATIVE_PATH}=?",
            arrayOf(name, LOCKED_DIR), null)?.use { it.count > 0 } == true
    } catch (_: Exception) { false }

    private const val PREF_LOCKED_NAMES = "locked_names"

    private fun lockedNames(ctx: Context): Set<String> =
        PreferenceManager.getDefaultSharedPreferences(ctx).getStringSet(PREF_LOCKED_NAMES, emptySet()) ?: emptySet()

    // Exact wall-clock start of each clip (ms), so the player can line up front and rear.
    private fun starts(ctx: Context) = ctx.getSharedPreferences("clip_starts", Context.MODE_PRIVATE)
    fun recordStart(ctx: Context, name: String, ms: Long) = starts(ctx).edit().putLong(name, ms).apply()
    fun clipStart(ctx: Context, name: String): Long? = starts(ctx).getLong(name, -1).takeIf { it > 0 }
    private fun forgetStart(ctx: Context, name: String) = starts(ctx).edit().remove(name).apply()

    fun isLockedName(ctx: Context, name: String) = name in lockedNames(ctx)

    private fun rememberLocked(ctx: Context, name: String, locked: Boolean) {
        val p = PreferenceManager.getDefaultSharedPreferences(ctx)
        val set = HashSet(lockedNames(ctx))
        if (locked) set += name else set -= name
        p.edit().putStringSet(PREF_LOCKED_NAMES, set).commit()
    }

    /**
     * Loop recording: delete the oldest unlocked clips while the folder is over [limitBytes] or the
     * card is nearly full. Clips still being written ([inUse]) are never touched.
     */
    fun enforceLimit(ctx: Context, volume: String, limitBytes: Long) = synchronized(lock) {
        enforceLocked(ctx, volume, limitBytes)
    }

    private fun enforceLocked(ctx: Context, volume: String, limitBytes: Long) {
        val collection = collection(volume)
        data class Clip(val uri: Uri, val size: Long, val locked: Boolean, val name: String)
        val protectedNames = lockedNames(ctx)
        val clips = ArrayList<Clip>()
        // Private clips on this volume (plus any older shared ones below), oldest first by name.
        privateDirs(ctx)[volume]?.let { dir ->
            for (f in dir.listFiles().orEmpty()) if (f.isFile && f.name.endsWith(".mp4"))
                clips += Clip(Uri.fromFile(f), f.length(), f.name in protectedNames, f.name)
            for (f in File(dir, "Locked").listFiles().orEmpty()) if (f.isFile && f.name.endsWith(".mp4"))
                clips += Clip(Uri.fromFile(f), f.length(), true, f.name)
        }
        try {
            ctx.contentResolver.query(
                collection,
                arrayOf(MediaStore.Video.Media._ID, MediaStore.Video.Media.SIZE, MediaStore.Video.Media.RELATIVE_PATH,
                    MediaStore.Video.Media.DISPLAY_NAME),
                "${MediaStore.Video.Media.RELATIVE_PATH} LIKE ?", arrayOf("$DIR%"),
                "${MediaStore.Video.Media.DATE_ADDED} ASC"
            )?.use { c ->
                while (c.moveToNext()) {
                    val uri = ContentUris.withAppendedId(collection, c.getLong(0))
                    val name = c.getString(3) ?: ""
                    clips += Clip(uri, c.getLong(1), c.getString(2) == LOCKED_DIR || name in protectedNames, name)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "query failed", e)
        }
        clips.sortBy { it.name }   // names start with the recording time
        var total = clips.sumOf { it.size }
        for (clip in clips) {
            if (total <= limitBytes && freeBytes(ctx, volume) >= MIN_FREE_BYTES) break
            if (clip.locked || clip.uri.toString() in openIds) continue
            try {
                if (delete(ctx, clip.uri)) {
                    total -= clip.size
                    forgetStart(ctx, clip.name)
                    Log.i(TAG, "loop-deleted ${clip.uri}")
                }
            } catch (e: Exception) {
                Log.e(TAG, "delete failed ${clip.uri}", e)
            }
        }
    }

    fun freeBytes(ctx: Context, volume: String): Long = try {
        val dir = if (volume == MediaStore.VOLUME_EXTERNAL_PRIMARY) Environment.getExternalStorageDirectory()
                  else if (Build.VERSION.SDK_INT >= 30) storageVolume(ctx, volume)?.directory
                  else null
        if (dir != null) StatFs(dir.path).availableBytes else Long.MAX_VALUE
    } catch (e: Exception) { Long.MAX_VALUE }

    // ---------------------------------------------------------------- sharing / gallery / moving

    private fun input(ctx: Context, uri: Uri) = fileOf(uri)?.inputStream() ?: ctx.contentResolver.openInputStream(uri)

    /** A URI another app can be given for this clip (read-only, only when the user shares it). */
    fun shareUri(ctx: Context, uri: Uri): Uri = fileOf(uri)?.let { ClipProvider.uriFor(ctx, it) } ?: uri

    /** Copy a clip into the gallery (Movies/DashCam Exports on internal storage). */
    fun exportToGallery(ctx: Context, uri: Uri, name: String): Boolean {
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, name)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, EXPORT_DIR)
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }
        val out = ctx.contentResolver.insert(collection(MediaStore.VOLUME_EXTERNAL_PRIMARY), values) ?: return false
        return try {
            ctx.contentResolver.openOutputStream(out)!!.use { o -> input(ctx, uri)!!.use { it.copyTo(o, 256 * 1024) } }
            ctx.contentResolver.update(out, ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }, null, null)
            EventLog.log("Clip", "saved to gallery: $name")
            true
        } catch (e: Exception) {
            Log.e(TAG, "export $name", e)
            runCatching { ctx.contentResolver.delete(out, null, null) }
            false
        }
    }

    data class SharedClip(val uri: Uri, val name: String, val size: Long, val locked: Boolean, val volume: String)

    /** Clips still in the shared Movies/DashCam folder (from before private storage, or shared mode). */
    fun sharedClips(ctx: Context): List<SharedClip> {
        val out = ArrayList<SharedClip>()
        val protectedNames = lockedNames(ctx)
        for (vol in MediaStore.getExternalVolumeNames(ctx)) {
            val coll = collection(vol)
            try {
                ctx.contentResolver.query(coll,
                    arrayOf(MediaStore.Video.Media._ID, MediaStore.Video.Media.DISPLAY_NAME,
                        MediaStore.Video.Media.SIZE, MediaStore.Video.Media.RELATIVE_PATH),
                    "${MediaStore.Video.Media.RELATIVE_PATH} LIKE ?", arrayOf("$DIR%"), null)?.use { c ->
                    while (c.moveToNext()) {
                        val name = c.getString(1) ?: continue
                        if (!name.endsWith(".mp4")) continue
                        out += SharedClip(ContentUris.withAppendedId(coll, c.getLong(0)), name, c.getLong(2),
                            c.getString(3) == LOCKED_DIR || name in protectedNames, vol)
                    }
                }
            } catch (e: Exception) { Log.e(TAG, "shared query $vol", e) }
        }
        return out
    }

    /**
     * Move shared clips into private storage: copy, then delete the original. A clip made by an
     * earlier install can't be deleted by this app, so its copy is removed again and it's skipped.
     * Returns (moved, skipped).
     */
    fun moveSharedToPrivate(ctx: Context, progress: (done: Int, total: Int) -> Unit): Pair<Int, Int> {
        val clips = sharedClips(ctx).filter { !isOpen(it.uri) }
        var moved = 0; var skipped = 0
        clips.forEachIndexed { i, c ->
            progress(i, clips.size)
            val dir = privateDir(ctx, c.volume).let { if (c.locked) File(it, "Locked") else it }.apply { mkdirs() }
            val dest = File(dir, c.name)
            val tmp = File(dir, c.name + ".part")
            try {
                input(ctx, c.uri)!!.use { inp -> tmp.outputStream().use { inp.copyTo(it, 256 * 1024) } }
                if (tmp.length() != c.size && c.size > 0) throw IllegalStateException("size mismatch")
                if (!tmp.renameTo(dest)) throw IllegalStateException("rename failed")
                val gone = try { ctx.contentResolver.delete(c.uri, null, null) > 0 } catch (_: SecurityException) { false }
                if (gone) { moved++; if (c.locked) rememberLocked(ctx, c.name, true) }
                else { dest.delete(); skipped++ }
            } catch (e: Exception) {
                Log.e(TAG, "move ${c.name}", e); tmp.delete(); skipped++
            }
        }
        progress(clips.size, clips.size)
        EventLog.log("Clip", "moved $moved clips into private storage, skipped $skipped")
        return moved to skipped
    }
}
