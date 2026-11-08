package com.example.dashcam

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.media.ThumbnailUtils
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
import android.widget.CheckBox
import android.widget.ImageView
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.preference.PreferenceManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.MaterialToolbar
import java.util.Calendar
import java.util.concurrent.Executors

/**
 * In-app file manager: every recording (front + rear [+ remote] paired), newest first, grouped by
 * day. Long-press (or "Select") to pick recordings, then lock, unlock, share, save to gallery or
 * delete them together. Tapping a day header while selecting picks the whole day.
 */
class ClipsActivity : AppCompatActivity() {
    private lateinit var list: RecyclerView
    private lateinit var summary: TextView
    private lateinit var empty: TextView
    private lateinit var toolbar: MaterialToolbar
    private lateinit var actions: ClipActions
    private val io = Executors.newFixedThreadPool(2)
    private val main = Handler(Looper.getMainLooper())
    private val thumbs = LruCache<Uri, Bitmap>(120)
    private var all: List<ClipPair> = emptyList()
    private var shown: List<ClipPair> = emptyList()
    private var lockedOnly = false
    private val adapter = Adapter()

    /** Keys of the selected recordings; null = not in selection mode. */
    private var selected: MutableSet<String>? = null

    private val readPerm = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_VIDEO
                           else Manifest.permission.READ_EXTERNAL_STORAGE
    private val permLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { reload() }

    private val backInSelection = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() = endSelection()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_clips)
        toolbar = findViewById(R.id.toolbar)
        setSupportActionBar(toolbar)
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
        actions = ClipActions(this) { endSelection(); reload() }
        onBackPressedDispatcher.addCallback(this, backInSelection)
        // Only needed to see older clips in the shared folder made by an earlier install.
        if (checkSelfPermission(readPerm) != PackageManager.PERMISSION_GRANTED) permLauncher.launch(readPerm)
        offerMoveOnce()
    }

    override fun onResume() { super.onResume(); reload() }
    override fun onDestroy() { io.shutdownNow(); super.onDestroy() }
    override fun onSupportNavigateUp(): Boolean {
        if (selected != null) endSelection() else finish()
        return true
    }

    // ------------------------------------------------------------------ menus / selection

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        if (selected != null) {
            menuInflater.inflate(R.menu.clips_selection, menu)
        } else {
            menuInflater.inflate(R.menu.clips, menu)
            menu.findItem(R.id.lockedOnly).isChecked = lockedOnly
            menu.findItem(R.id.moveIn).isVisible = Storage.privateMode(this)
        }
        return true
    }

    override fun onPrepareOptionsMenu(menu: Menu): Boolean {
        val sel = picked()
        menu.findItem(R.id.lock)?.isVisible = sel.any { !it.locked }
        menu.findItem(R.id.unlock)?.isVisible = sel.any { it.locked }
        menu.findItem(R.id.save)?.isVisible = sel.isNotEmpty()
        listOf(R.id.share, R.id.delete).forEach { menu.findItem(it)?.isEnabled = sel.isNotEmpty() }
        return super.onPrepareOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.select -> startSelection(null)
            R.id.lockedOnly -> { lockedOnly = !lockedOnly; item.isChecked = lockedOnly; show() }
            R.id.refresh -> reload()
            R.id.moveIn -> offerMove(force = true)
            R.id.selectAll -> { selected?.addAll(shown.filter { !it.recording }.map { it.key }); selectionChanged() }
            R.id.selectNone -> { selected?.clear(); selectionChanged() }
            R.id.lock -> actions.setLocked(picked(), true)
            R.id.unlock -> actions.setLocked(picked(), false)
            R.id.share -> actions.share(picked())
            R.id.save -> actions.saveToGallery(picked())
            R.id.delete -> actions.delete(picked())
            else -> return super.onOptionsItemSelected(item)
        }
        return true
    }

    private fun picked(): List<ClipPair> = selected?.let { s -> all.filter { it.key in s } } ?: emptyList()

    private fun startSelection(first: ClipPair?) {
        selected = HashSet<String>().also { s -> if (first != null && !first.recording) s += first.key }
        backInSelection.isEnabled = true
        supportActionBar?.setHomeAsUpIndicator(android.R.drawable.ic_menu_close_clear_cancel)
        selectionChanged()
    }

    private fun endSelection() {
        if (selected == null) return
        selected = null
        backInSelection.isEnabled = false
        supportActionBar?.setHomeAsUpIndicator(null)
        supportActionBar?.title = "Clips"
        supportActionBar?.subtitle = null
        invalidateOptionsMenu()
        adapter.notifyDataSetChanged()
    }

    private fun selectionChanged() {
        val sel = picked()
        supportActionBar?.title = if (sel.isEmpty()) "Select clips" else "${sel.size} selected"
        supportActionBar?.subtitle = if (sel.isEmpty()) "Tap recordings or a day" else fmtSize(sel.sumOf { it.sizeBytes })
        invalidateOptionsMenu()
        adapter.notifyDataSetChanged()
    }

    private fun toggle(p: ClipPair) {
        val s = selected ?: return
        if (p.recording) return
        if (!s.remove(p.key)) s.add(p.key)
        selectionChanged()
    }

    /** Tapping a day header while selecting: select the whole day, or clear it if all were selected. */
    private fun toggleDay(day: List<ClipPair>) {
        val s = selected ?: return
        val keys = day.filter { !it.recording }.map { it.key }
        if (keys.all { it in s }) s.removeAll(keys.toSet()) else s.addAll(keys)
        selectionChanged()
    }

    // ------------------------------------------------------------------ data

    private fun reload() {
        io.execute {
            val pairs = ClipRepo.load(this)
            main.post {
                all = pairs
                selected?.retainAll(pairs.map { it.key }.toSet())
                show()
                if (selected != null) selectionChanged()
            }
        }
    }

    private fun show() {
        shown = if (lockedOnly) all.filter { it.locked } else all
        adapter.setData(shown)
        empty.visibility = if (shown.isEmpty()) View.VISIBLE else View.GONE
        empty.text = if (lockedOnly) "No locked clips." else "No clips yet.\nRecordings appear here."
        val bytes = all.sumOf { it.sizeBytes }
        val where = if (Storage.privateMode(this)) "private" else "visible to other apps"
        summary.text = "${all.size} recordings · ${fmtSize(bytes)} · ${all.count { it.locked }} locked · " +
            "Free: ${fmtSize(Storage.freeBytes(this, Storage.resolveVolume(this, DashSettings(this).storageVolume)))} · New clips: $where"
    }

    private fun open(p: ClipPair) {
        if (p.recording) return
        startActivity(Intent(this, PlayerActivity::class.java)
            .putExtra(PlayerActivity.EXTRA_KEY, p.key)
            // Two views for now: a remote camera clip fills whichever slot is free.
            .putExtra(PlayerActivity.EXTRA_FRONT, (p.front ?: p.remote)?.uri?.toString())
            .putExtra(PlayerActivity.EXTRA_REAR, (p.rear ?: p.remote?.takeIf { p.front != null })?.uri?.toString()))
    }

    // ------------------------------------------------------------------ moving old clips in

    private fun offerMoveOnce() {
        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        if (!Storage.privateMode(this) || prefs.getBoolean("move_in_asked", false)) return
        io.execute {
            val n = Storage.sharedClips(this).size
            if (n > 0) main.post { if (!isFinishing) offerMove(force = false) }
            else prefs.edit().putBoolean("move_in_asked", true).apply()
        }
    }

    /** Move clips from the shared Movies/DashCam folder into private storage, after asking. */
    private fun offerMove(force: Boolean) {
        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        io.execute {
            val clips = Storage.sharedClips(this)
            main.post {
                if (isFinishing) return@post
                if (clips.isEmpty()) {
                    if (force) AlertDialog.Builder(this).setMessage("There are no DashCam clips in the gallery folder.")
                        .setPositiveButton("OK", null).show()
                    return@post
                }
                AlertDialog.Builder(this)
                    .setTitle("Make older clips private?")
                    .setMessage("${plural(clips.size, "clip")} (${fmtSize(clips.sumOf { it.size })}) ${if (clips.size == 1) "is" else "are"} in the shared " +
                        "Movies/DashCam folder, where your gallery and other apps can see them.\n\n" +
                        "Move them into DashCam's private storage? New clips already go there.")
                    .setPositiveButton("Move them") { _, _ -> prefs.edit().putBoolean("move_in_asked", true).apply(); runMove() }
                    .setNegativeButton("Leave them") { _, _ -> prefs.edit().putBoolean("move_in_asked", true).apply() }
                    .show()
            }
        }
    }

    private fun runMove() {
        val dlg = AlertDialog.Builder(this).setTitle("Moving clips…").setMessage("Starting…").setCancelable(false).show()
        io.execute {
            val (moved, skipped) = Storage.moveSharedToPrivate(this) { done, total ->
                main.post { dlg.setMessage("$done of $total") }
            }
            main.post {
                dlg.dismiss()
                AlertDialog.Builder(this)
                    .setMessage("Moved ${plural(moved, "clip")} into private storage." +
                        if (skipped > 0) "\n\n$skipped were left in the gallery folder — they were made by an earlier " +
                            "install of DashCam, so Android doesn't let it remove them. You can delete them here." else "")
                    .setPositiveButton("OK", null).show()
                reload()
            }
        }
    }

    // ------------------------------------------------------------------ list

    private fun loadThumb(iv: ImageView, uri: Uri?) {
        iv.tag = uri
        iv.setImageDrawable(null)
        if (uri == null) return
        thumbs.get(uri)?.let { iv.setImageBitmap(it); return }
        io.execute {
            val bmp = try {
                Storage.fileOf(uri)?.let { ThumbnailUtils.createVideoThumbnail(it, Size(320, 180), null) }
                    ?: contentResolver.loadThumbnail(uri, Size(320, 180), null)
            } catch (_: Exception) { null }
            if (bmp != null) {
                thumbs.put(uri, bmp)
                main.post { if (iv.tag == uri) iv.setImageBitmap(bmp) }
            }
        }
    }

    private sealed class Row {
        class Day(val label: String, val pairs: List<ClipPair>) : Row()
        class Item(val pair: ClipPair) : Row()
    }

    private fun dayLabel(ms: Long?): String {
        if (ms == null) return "Other"
        val c = Calendar.getInstance().apply { timeInMillis = ms }
        val today = Calendar.getInstance()
        fun sameDay(a: Calendar, b: Calendar) = a.get(Calendar.YEAR) == b.get(Calendar.YEAR) && a.get(Calendar.DAY_OF_YEAR) == b.get(Calendar.DAY_OF_YEAR)
        val yesterday = (today.clone() as Calendar).apply { add(Calendar.DAY_OF_YEAR, -1) }
        return when {
            sameDay(c, today) -> "Today"
            sameDay(c, yesterday) -> "Yesterday"
            else -> DateUtils.formatDateTime(this, ms, DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_WEEKDAY or
                DateUtils.FORMAT_ABBREV_MONTH or DateUtils.FORMAT_SHOW_YEAR)
        }
    }

    private inner class Holder(v: View) : RecyclerView.ViewHolder(v)

    private inner class Adapter : RecyclerView.Adapter<Holder>() {
        private var rows: List<Row> = emptyList()

        fun setData(pairs: List<ClipPair>) {
            val out = ArrayList<Row>()
            pairs.groupBy { dayLabel(it.startMs) }.forEach { (label, day) ->
                out += Row.Day(label, day); day.forEach { out += Row.Item(it) }
            }
            rows = out
            notifyDataSetChanged()
        }

        override fun getItemCount() = rows.size
        override fun getItemViewType(pos: Int) = if (rows[pos] is Row.Day) 0 else 1
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = Holder(LayoutInflater.from(parent.context)
            .inflate(if (viewType == 0) R.layout.item_day else R.layout.item_clip, parent, false))

        override fun onBindViewHolder(h: Holder, pos: Int) {
            when (val r = rows[pos]) {
                is Row.Day -> bindDay(h.itemView, r)
                is Row.Item -> bindItem(h.itemView, r.pair)
            }
        }

        private fun bindDay(v: View, r: Row.Day) {
            v.findViewById<TextView>(R.id.day).text = r.label
            val sel = selected
            val info = "${r.pairs.size} · ${fmtSize(r.pairs.sumOf { it.sizeBytes })}"
            v.findViewById<TextView>(R.id.dayInfo).text = if (sel != null) {
                val n = r.pairs.count { it.key in sel }
                if (n == r.pairs.count { !it.recording } && n > 0) "$info · all selected ✓" else "$info · tap to select day"
            } else info
            v.setOnClickListener { if (selected != null) toggleDay(r.pairs) }
            v.isClickable = selected != null
        }

        private fun bindItem(v: View, p: ClipPair) {
            val title = v.findViewById<TextView>(R.id.title)
            val start = p.startMs
            title.text = if (start != null) DateUtils.formatDateTime(this@ClipsActivity, start, DateUtils.FORMAT_SHOW_TIME)
                         else p.key
            val cams = listOfNotNull(p.front?.let { "Front" }, p.rear?.let { "Rear" }, p.remote?.let { "Remote" })
                .joinToString(" + ")
            val where = buildList {
                if (p.clips.any { it.volume != MediaStore.VOLUME_EXTERNAL_PRIMARY }) add("SD card")
                if (p.clips.any { !it.isPrivate }) add("in gallery folder")
            }.joinToString(" · ").let { if (it.isEmpty()) "" else " · $it" }
            v.findViewById<TextView>(R.id.subtitle).text = if (p.recording) "Recording… · $cams$where"
                else "${fmtDuration(p.durationMs)} · ${fmtSize(p.sizeBytes)} · $cams$where"
            val badge = v.findViewById<TextView>(R.id.badge)
            badge.text = when { p.recording -> "●"; p.locked -> "🔒"; else -> "" }
            badge.setTextColor(if (p.recording) 0xFFE53935.toInt() else title.currentTextColor)
            val second = p.rear ?: p.remote?.takeIf { p.front != null }
            loadThumb(v.findViewById(R.id.thumbFront), if (p.recording) null else (p.front ?: p.remote)?.uri)
            val rearIv = v.findViewById<ImageView>(R.id.thumbRear)
            loadThumb(rearIv, if (p.recording) null else second?.uri)
            rearIv.visibility = if (second != null) View.VISIBLE else View.INVISIBLE
            val sel = selected
            val check = v.findViewById<CheckBox>(R.id.check)
            check.visibility = if (sel != null) View.VISIBLE else View.GONE
            check.isChecked = sel != null && p.key in sel
            check.isEnabled = !p.recording
            v.alpha = if (p.recording) 0.6f else 1f
            v.setOnClickListener { if (selected != null) toggle(p) else open(p) }
            v.setOnLongClickListener {
                if (selected == null) startSelection(p) else toggle(p)
                true
            }
        }
    }

    companion object {
        fun fmtSize(b: Long): String = when {
            b >= 1L shl 30 -> String.format("%.1f GB", b / (1L shl 30).toDouble())
            else -> String.format("%.0f MB", b / (1L shl 20).toDouble())
        }
        fun plural(n: Int, word: String) = "$n $word" + if (n == 1) "" else "s"
        fun fmtDuration(ms: Long): String { val s = ms / 1000; return String.format("%d:%02d", s / 60, s % 60) }
    }
}
