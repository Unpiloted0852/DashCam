package com.example.dashcam

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import java.io.File
import java.io.FileNotFoundException

/**
 * Hands a single private clip to another app, read-only, when the user shares it. Not exported:
 * other apps can only open a URI they were explicitly granted, and only files inside DashCam's own
 * clip folders are ever served.
 */
class ClipProvider : ContentProvider() {
    companion object {
        fun authority(ctx: Context) = "${ctx.packageName}.clips"
        fun uriFor(ctx: Context, f: File): Uri = Uri.Builder().scheme("content").authority(authority(ctx))
            .appendPath("f").appendPath(f.absolutePath).build()
    }

    override fun onCreate() = true

    private fun fileFor(uri: Uri): File {
        val ctx = context ?: throw FileNotFoundException()
        val path = uri.pathSegments.getOrNull(1) ?: throw FileNotFoundException("bad uri")
        val f = File(path).canonicalFile
        val allowed = Storage.privateDirs(ctx).values.any { f.path.startsWith(it.canonicalPath + File.separator) }
        if (!allowed || !f.isFile || !f.name.endsWith(".mp4")) throw FileNotFoundException("not a DashCam clip")
        return f
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor {
        val f = fileFor(uri)
        val cols = projection ?: arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        return MatrixCursor(cols).apply {
            addRow(cols.map { when (it) { OpenableColumns.DISPLAY_NAME -> f.name; OpenableColumns.SIZE -> f.length(); else -> null } })
        }
    }

    override fun getType(uri: Uri) = "video/mp4"

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        if (mode != "r") throw SecurityException("read-only")
        return ParcelFileDescriptor.open(fileFor(uri), ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? = throw UnsupportedOperationException()
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
}
