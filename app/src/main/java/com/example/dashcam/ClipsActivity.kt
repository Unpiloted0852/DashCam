package com.example.dashcam

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.text.format.DateUtils
import android.util.LruCache
import android.util.Size
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.PopupMenu
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.MaterialToolbar
import java.util.concurrent.Executors

/** In-app file manager: every recording (front + rear paired), newest first. */
class ClipsActivity : AppCompatActivity() {
    private lateinit var list: RecyclerView
    private lateinit var summary: TextView
    private lateinit var empty: TextView
    private lateinit var actions: ClipActions
    private val io = Executors.newFixedThreadPool(2)
    private val main = Handler(Looper.getMainLooper())
    private val thumbs = LruCache<Uri, Bitmap>(80)
    private var all: List<ClipPair> = emptyList()
    private var lockedOnly = false
    private val adapter = Adapter()

    private val readPerm = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_VIDEO
                           else Manifest.permission.READ_EXTERNAL_STORAGE
    private val permLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { reload() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_clips)
        setSupportActionBar(findViewById<MaterialToolbar>(R.id.toolbar))
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.clipsRoot)) { v, insets ->
            val i = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            v.setPadding(i.left, i.top, i.right, i.bottom); insets
        }
        list = findViewById(R.id.list)
        summary = findViewById(R.id.summary)
        empty = findViewById(R.id.empty)
        list.layoutManager = LinearLayoutManager(this)
        list.adapter = adapter
        actions = ClipActions(this) { reload() }
        io.execute { Storage.rescanFolders(this); main.postDelayed({ reload() }, 1500) }
        // Needed only to see clips from an earlier install; our own clips are always visible.
        if (checkSelfPermission(readPerm) != PackageManager.PERMISSION_GRANTED) permLauncher.launch(readPerm)
    }

    override fun onResume() { super.onResume(); reload() }
    override fun onDestroy() { io.shutdownNow(); super.onDestroy() }
    override fun onSupportNavigateUp(): Boolean { finish(); return true }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.clips, menu); return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId) {
        R.id.lockedOnly -> { lockedOnly = !lockedOnly; item.isChecked = lockedOnly; show(); true }
        R.id.refresh -> { reload(); true }
        else -> super.onOptionsItemSelected(item)
    }

    private fun reload() {
        io.execute {
            val pairs = ClipRepo.load(this)
            main.post { all = pairs; show() }
        }
    }

    private fun show() {
        val shown = if (lockedOnly) all.filter { it.locked } else all
        adapter.items = shown
        adapter.notifyDataSetChanged()
        empty.visibility = if (shown.isEmpty()) View.VISIBLE else View.GONE
        empty.text = if (lockedOnly) "No locked clips." else "No clips yet.\nRecordings appear here."
        val bytes = all.sumOf { it.sizeBytes }
        summary.text = "${all.size} recordings · ${fmtSize(bytes)} · ${all.count { it.locked }} locked" +
            " · Free: ${fmtSize(Storage.freeBytes(this, Storage.resolveVolume(this, DashSettings(this).storageVolume)))}"
    }

    private fun open(p: ClipPair) {
        if (p.recording) return
        startActivity(Intent(this, PlayerActivity::class.java)
            .putExtra(PlayerActivity.EXTRA_KEY, p.key)
            .putExtra(PlayerActivity.EXTRA_FRONT, p.front?.uri?.toString())
            .putExtra(PlayerActivity.EXTRA_REAR, p.rear?.uri?.toString()))
    }

    private fun menuFor(v: View, p: ClipPair) {
        val pm = PopupMenu(this, v)
        pm.menuInflater.inflate(R.menu.clip_actions, pm.menu)
        pm.menu.findItem(R.id.lock).title = if (p.locked) "Unlock" else "Lock"
        pm.menu.findItem(R.id.play).isEnabled = !p.recording
        pm.setOnMenuItemClickListener {
            when (it.itemId) {
                R.id.play -> open(p)
                R.id.lock -> actions.setLocked(p, !p.locked)
                R.id.share -> actions.share(p)
                R.id.delete -> actions.delete(p)
            }
            true
        }
        pm.show()
    }

    private fun loadThumb(iv: ImageView, uri: Uri?) {
        iv.tag = uri
        iv.setImageDrawable(null)
        if (uri == null) return
        thumbs.get(uri)?.let { iv.setImageBitmap(it); return }
        io.execute {
            val bmp = try { contentResolver.loadThumbnail(uri, Size(320, 180), null) } catch (_: Exception) { null }
            if (bmp != null) {
                thumbs.put(uri, bmp)
                main.post { if (iv.tag == uri) iv.setImageBitmap(bmp) }
            }
        }
    }

    private inner class Holder(v: View) : RecyclerView.ViewHolder(v) {
        val front: ImageView = v.findViewById(R.id.thumbFront)
        val rear: ImageView = v.findViewById(R.id.thumbRear)
        val title: TextView = v.findViewById(R.id.title)
        val subtitle: TextView = v.findViewById(R.id.subtitle)
        val badge: TextView = v.findViewById(R.id.badge)
    }

    private inner class Adapter : RecyclerView.Adapter<Holder>() {
        var items: List<ClipPair> = emptyList()
        override fun getItemCount() = items.size
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            Holder(LayoutInflater.from(parent.context).inflate(R.layout.item_clip, parent, false))

        override fun onBindViewHolder(h: Holder, pos: Int) {
            val p = items[pos]
            val start = p.startMs
            h.title.text = if (start != null) DateUtils.formatDateTime(this@ClipsActivity, start,
                DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_TIME or DateUtils.FORMAT_SHOW_WEEKDAY or
                    DateUtils.FORMAT_ABBREV_ALL) else p.key
            val cams = listOfNotNull(p.front?.let { "Front" }, p.rear?.let { "Rear" }).joinToString(" + ")
            val where = if (p.clips.any { it.volume != MediaStore.VOLUME_EXTERNAL_PRIMARY }) " · SD card" else ""
            h.subtitle.text = if (p.recording) "Recording… · $cams$where"
                else "${fmtDuration(p.durationMs)} · ${fmtSize(p.sizeBytes)} · $cams$where"
            h.badge.text = when { p.recording -> "●"; p.locked -> "🔒"; else -> "" }
            h.badge.setTextColor(if (p.recording) 0xFFE53935.toInt() else h.title.currentTextColor)
            loadThumb(h.front, if (p.recording) null else p.front?.uri)
            loadThumb(h.rear, if (p.recording) null else p.rear?.uri)
            h.rear.visibility = if (p.rear != null) View.VISIBLE else View.INVISIBLE
            h.itemView.alpha = if (p.recording) 0.6f else 1f
            h.itemView.setOnClickListener { if (p.recording) menuFor(it, p) else open(p) }
            h.itemView.setOnLongClickListener { menuFor(it, p); true }
        }
    }

    companion object {
        fun fmtSize(b: Long): String = when {
            b >= 1L shl 30 -> String.format("%.1f GB", b / (1L shl 30).toDouble())
            else -> String.format("%.0f MB", b / (1L shl 20).toDouble())
        }
        fun fmtDuration(ms: Long): String { val s = ms / 1000; return String.format("%d:%02d", s / 60, s % 60) }
    }
}
