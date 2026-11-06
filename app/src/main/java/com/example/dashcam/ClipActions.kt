package com.example.dashcam

import android.app.Activity
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
 * Lock / share / delete for a recording (both clips together). Clips this install didn't create
 * (e.g. after a reinstall) need the user's OK through Android's own consent dialog.
 * Must be constructed during the activity's onCreate.
 */
class ClipActions(private val a: AppCompatActivity, private val onChanged: () -> Unit) {
    private var pending: (() -> Unit)? = null

    private val consent = a.registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { r ->
        if (r.resultCode == Activity.RESULT_OK) pending?.invoke()
        pending = null
        onChanged()
    }

    fun share(p: ClipPair) {
        val uris = ArrayList<Uri>(p.clips.map { it.uri })
        val send = if (uris.size == 1) {
            Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, uris[0])
        } else {
            Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
        }
        send.type = "video/mp4"
        send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        a.startActivity(Intent.createChooser(send, "Share clip"))
    }

    fun setLocked(p: ClipPair, locked: Boolean) {
        val apply = { p.clips.forEach { runCatching { Storage.setLocked(a, it.uri, locked) } } }
        Thread {
            val result = try {
                p.clips.forEach { Storage.setLocked(a, it.uri, locked) }; null
            } catch (e: SecurityException) { e }
            a.runOnUiThread {
                if (result == null) {
                    toast(if (locked) "Locked — won't be loop-deleted" else "Unlocked")
                    onChanged()
                } else if (Build.VERSION.SDK_INT >= 30) {
                    pending = { Thread { apply(); a.runOnUiThread(onChanged) }.start() }
                    consent.launch(IntentSenderRequest.Builder(
                        MediaStore.createWriteRequest(a.contentResolver, p.clips.map { it.uri }).intentSender).build())
                } else toast("This clip was made by an earlier install and can't be changed")
            }
        }.start()
    }

    /** Deletes both clips after confirmation; [onDeleted] runs once they're gone. */
    fun delete(p: ClipPair, onDeleted: () -> Unit = {}) {
        if (p.recording) { toast("Still recording — stop recording first"); return }
        if (Build.VERSION.SDK_INT >= 30) {
            // The system dialog is the confirmation, and it performs the delete itself.
            pending = onDeleted
            consent.launch(IntentSenderRequest.Builder(
                MediaStore.createDeleteRequest(a.contentResolver, p.clips.map { it.uri }).intentSender).build())
        } else {
            AlertDialog.Builder(a)
                .setTitle("Delete this recording?")
                .setMessage("${p.clips.size} file(s) will be permanently deleted.")
                .setPositiveButton("Delete") { _, _ ->
                    p.clips.forEach { runCatching { a.contentResolver.delete(it.uri, null, null) } }
                    onDeleted(); onChanged()
                }
                .setNegativeButton("Cancel", null)
                .show()
        }
    }

    private fun toast(t: String) = Toast.makeText(a, t, Toast.LENGTH_SHORT).show()
}
