package com.example.dashcam

import android.app.Activity
import android.content.ClipData
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.widget.Toast
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity

/**
 * Lock / share / save to gallery / delete for one or more recordings (all their clips together).
 * Private clips are handled directly. Shared clips this install didn't create (e.g. after a
 * reinstall) need the user's OK through Android's own consent dialog.
 * Must be constructed during the activity's onCreate.
 */
class ClipActions(private val a: AppCompatActivity, private val onChanged: () -> Unit) {
    private var pending: (() -> Unit)? = null

    private val consent = a.registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { r ->
        if (r.resultCode == Activity.RESULT_OK) pending?.invoke()
        pending = null
        onChanged()
    }

    private fun clips(pairs: List<ClipPair>) = pairs.flatMap { it.clips }

    /** Hand the clips to an app the user picks; it gets read access to exactly these files. */
    fun share(pairs: List<ClipPair>) {
        val list = clips(pairs).filter { !it.recording }
        if (list.isEmpty()) { toast("Nothing to share"); return }
        val uris = ArrayList<Uri>(list.map { Storage.shareUri(a, it.uri) })
        val send = if (uris.size == 1) Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, uris[0])
                   else Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
        send.type = "video/mp4"
        // ClipData carries the read grant to the receiving app for every file.
        send.clipData = ClipData.newRawUri("", uris[0]).apply { uris.drop(1).forEach { addItem(ClipData.Item(it)) } }
        send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        a.startActivity(Intent.createChooser(send, if (uris.size == 1) "Share clip" else "Share ${uris.size} clips"))
    }

    fun setLocked(pairs: List<ClipPair>, locked: Boolean) {
        val list = clips(pairs)
        Thread {
            val refused = ArrayList<Uri>()
            for (c in list) try { Storage.setLocked(a, c.uri, locked) } catch (e: SecurityException) { refused += c.uri }
            a.runOnUiThread {
                if (refused.isEmpty()) {
                    toast(if (locked) "Locked — won't be loop-deleted" else "Unlocked")
                    onChanged()
                } else if (Build.VERSION.SDK_INT >= 30) {
                    pending = { Thread { refused.forEach { runCatching { Storage.setLocked(a, it, locked) } }; a.runOnUiThread(onChanged) }.start() }
                    consent.launch(IntentSenderRequest.Builder(MediaStore.createWriteRequest(a.contentResolver, refused).intentSender).build())
                } else { toast("Some clips were made by an earlier install and can't be changed"); onChanged() }
            }
        }.start()
    }

    /** Copy clips into the gallery (Movies/DashCam Exports), for keeping or using in other apps. */
    fun saveToGallery(pairs: List<ClipPair>) {
        val list = clips(pairs).filter { !it.recording }
        if (list.isEmpty()) return
        toast(if (list.size == 1) "Saving to gallery…" else "Saving ${list.size} clips to gallery…")
        Thread {
            val ok = list.count { Storage.exportToGallery(a, it.uri, it.name) }
            a.runOnUiThread {
                toast(if (ok == list.size) "Saved to gallery (Movies/DashCam Exports)"
                      else "Saved $ok of ${list.size} clips — check free space")
            }
        }.start()
    }

    /** Delete after confirmation; [onDeleted] runs once they're gone. */
    fun delete(pairs: List<ClipPair>, onDeleted: () -> Unit = {}) {
        if (pairs.any { it.recording }) { toast("Still recording — stop recording first"); return }
        val list = clips(pairs)
        if (list.isEmpty()) return
        val (priv, shared) = list.partition { it.isPrivate }
        val locked = pairs.count { it.locked }
        val size = ClipsActivity.fmtSize(list.sumOf { it.sizeBytes })
        val what = if (pairs.size == 1) "this recording" else "${pairs.size} recordings"
        val doDelete = {
            Thread {
                priv.forEach { Storage.delete(a, it.uri) }
                val leftShared = shared.filter { !Storage.delete(a, it.uri) }.map { it.uri }
                a.runOnUiThread {
                    if (leftShared.isNotEmpty() && Build.VERSION.SDK_INT >= 30) {
                        // Shared clips from an earlier install: Android asks the user itself.
                        pending = onDeleted
                        consent.launch(IntentSenderRequest.Builder(
                            MediaStore.createDeleteRequest(a.contentResolver, leftShared).intentSender).build())
                    } else { onDeleted(); onChanged() }
                }
            }.start()
        }
        AlertDialog.Builder(a)
            .setTitle("Delete $what?")
            .setMessage("${list.size} file(s), $size, will be permanently deleted." +
                if (locked > 0) "\n\n$locked of them ${if (locked == 1) "is" else "are"} locked." else "")
            .setPositiveButton("Delete") { _, _ -> doDelete() }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun toast(t: String) = Toast.makeText(a, t, Toast.LENGTH_SHORT).show()
}
