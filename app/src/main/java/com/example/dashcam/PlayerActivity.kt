package com.example.dashcam

import android.content.res.Configuration
import android.media.MediaPlayer
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.format.DateUtils
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.VideoView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import kotlin.math.abs

/**
 * Plays one recording: any one of its clips (front, rear, remote camera) or all of them together,
 * kept in sync. Sound comes from the first clip shown (the clips carry the same audio).
 */
class PlayerActivity : AppCompatActivity() {

    /** One camera's clip and its view. [offsetMs]: how much later it started than the recording. */
    private inner class Track(val name: String, boxId: Int, videoId: Int, val modeBtn: Button, var uri: Uri?) {
        val box: View = findViewById(boxId)
        val video: VideoView = findViewById(videoId)
        var mp: MediaPlayer? = null
        var offsetMs = 0
        fun local(pos: Int) = pos - offsetMs
        fun shared(mp: MediaPlayer) = runCatching { mp.currentPosition }.getOrNull()?.let { it + offsetMs }
    }

    private lateinit var videos: LinearLayout
    private lateinit var tracks: List<Track>
    private lateinit var seek: SeekBar
    private lateinit var time: TextView
    private lateinit var playBtn: Button
    private lateinit var lockBtn: Button
    private lateinit var allBtn: Button
    private lateinit var actions: ClipActions

    private var pair: ClipPair? = null
    /** Index of the one track shown, or -1 for all of them. */
    private var mode = -1
    private var playing = true
    /** Position on the recording's shared timeline. */
    private var positionMs = 0
    private var dragging = false
    private val ui = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_player)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.playerRoot)) { v, insets ->
            val i = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            v.setPadding(i.left, i.top, i.right, i.bottom); insets
        }
        videos = findViewById(R.id.videos)
        seek = findViewById(R.id.seek); time = findViewById(R.id.time)
        playBtn = findViewById(R.id.playBtn); lockBtn = findViewById(R.id.lockBtn)
        allBtn = findViewById(R.id.modeBoth)
        actions = ClipActions(this) { refreshPair() }

        fun uri(extra: String) = intent.getStringExtra(extra)?.let(Uri::parse)
        tracks = listOf(
            Track("front", R.id.frontBox, R.id.frontVideo, findViewById(R.id.modeFront), uri(EXTRA_FRONT)),
            Track("rear", R.id.rearBox, R.id.rearVideo, findViewById(R.id.modeRear), uri(EXTRA_REAR)),
            Track("remote", R.id.remoteBox, R.id.remoteVideo, findViewById(R.id.modeRemote), uri(EXTRA_REMOTE)),
        )
        val key = intent.getStringExtra(EXTRA_KEY) ?: ""
        // Clips of one recording start a moment apart; line them up by their recorded start times.
        val starts = tracks.map { if (it.uri != null) Storage.clipStart(this, "${key}_${it.name}.mp4") else null }
        val base = starts.filterNotNull().minOrNull()
        if (base != null) tracks.forEachIndexed { i, t -> t.offsetMs = ((starts[i] ?: base) - base).toInt().coerceIn(0, 10_000) }

        val start = ClipPair(key, null, null).startMs
        findViewById<TextView>(R.id.title).text = if (start != null) DateUtils.formatDateTime(this, start,
            DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_TIME or DateUtils.FORMAT_SHOW_WEEKDAY or
                DateUtils.FORMAT_SHOW_YEAR) else key

        positionMs = savedInstanceState?.getInt("pos") ?: 0
        playing = savedInstanceState?.getBoolean("playing") ?: true
        val available = tracks.filter { it.uri != null }
        mode = savedInstanceState?.getInt("mode") ?: if (available.size > 1) -1 else tracks.indexOfFirst { it.uri != null }

        for ((i, t) in tracks.withIndex()) {
            t.video.setOnPreparedListener { mp -> t.mp = mp; onPrepared(t, mp) }
            t.video.setOnCompletionListener { if (master() === t) finished() }
            t.video.setOnErrorListener { _, _, _ -> t.mp = null; true }
            t.modeBtn.setOnClickListener { setMode(i) }
            t.modeBtn.visibility = if (t.uri != null && available.size > 1) View.VISIBLE else View.GONE
        }
        allBtn.text = if (available.size > 2) "All" else "Both"
        allBtn.visibility = if (available.size > 1) View.VISIBLE else View.GONE
        allBtn.setOnClickListener { setMode(-1) }

        playBtn.setOnClickListener { if (playing) pause() else play() }
        lockBtn.setOnClickListener { pair?.let { actions.setLocked(listOf(it), !it.locked) } }
        findViewById<Button>(R.id.shareBtn).setOnClickListener { pair?.let { actions.share(listOf(it)) } }
        findViewById<Button>(R.id.saveBtn).setOnClickListener { pair?.let { actions.saveToGallery(listOf(it)) } }
        findViewById<Button>(R.id.deleteBtn).setOnClickListener {
            pair?.let { p -> pause(); actions.delete(listOf(p)) { finish() } }
        }

        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, p: Int, fromUser: Boolean) {
                if (fromUser) { positionMs = p; updateTime() }
            }
            override fun onStartTrackingTouch(sb: SeekBar) { dragging = true }
            override fun onStopTrackingTouch(sb: SeekBar) { dragging = false; seekAll(sb.progress) }
        })

        applyOrientation()
        setMode(mode)
        refreshPair()
    }

    override fun onSaveInstanceState(out: Bundle) {
        super.onSaveInstanceState(out)
        out.putInt("pos", currentPos()); out.putBoolean("playing", playing); out.putInt("mode", mode)
    }

    override fun onResume() { super.onResume(); ui.post(tick) }
    override fun onPause() {
        positionMs = currentPos()
        ui.removeCallbacks(tick)
        tracks.forEach { it.video.pause() }
        super.onPause()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        applyOrientation()
    }

    private fun applyOrientation() {
        val portrait = resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT
        videos.orientation = if (portrait) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
        for (t in tracks) {
            t.box.layoutParams = LinearLayout.LayoutParams(
                if (portrait) LinearLayout.LayoutParams.MATCH_PARENT else 0,
                if (portrait) 0 else LinearLayout.LayoutParams.MATCH_PARENT, 1f)
        }
    }

    private fun refreshPair() {
        // Re-read so the lock state is current after lock/unlock. Look the recording up by its
        // start time: locking a private clip moves its file, which changes its URI.
        val key = intent.getStringExtra(EXTRA_KEY)
        Thread {
            val p = ClipRepo.load(this).firstOrNull { pp ->
                pp.key == key || pp.clips.any { c -> tracks.any { it.uri == c.uri } }
            }
            ui.post {
                if (p == null && pair != null) { finish(); return@post }
                pair = p
                // Follow moved files so switching views reopens the right ones.
                if (p != null) listOf(p.front, p.rear, p.remote).forEachIndexed { i, c ->
                    if (tracks[i].uri != null && c != null) tracks[i].uri = c.uri
                }
                lockBtn.text = if (p?.locked == true) "🔓 Unlock" else "🔒 Lock"
            }
        }.start()
    }

    private fun shown(t: Track) = t.uri != null && (mode == -1 || tracks[mode] === t)

    private fun setMode(m: Int) {
        positionMs = currentPos()
        mode = m
        for (t in tracks) {
            val show = shown(t)
            // Hiding a VideoView destroys its surface and player; it reopens (and re-syncs) when shown.
            if (!show) { t.video.stopPlayback(); t.mp = null }
            t.box.visibility = if (show) View.VISIBLE else View.GONE
            if (show && t.mp == null) t.video.setVideoURI(t.uri)
        }
        tracks.forEachIndexed { i, t -> t.modeBtn.alpha = if (i == m) 1f else 0.5f }
        allBtn.alpha = if (m == -1) 1f else 0.5f
        applyVolumes()
    }

    /** The clip that drives the seek bar; the others follow it. */
    private fun master(): Track? = tracks.firstOrNull { shown(it) && it.mp != null }

    private fun onPrepared(t: Track, mp: MediaPlayer) {
        mp.setOnSeekCompleteListener(null)
        try {
            mp.seekTo(t.local(positionMs).coerceAtLeast(0).toLong(), MediaPlayer.SEEK_CLOSEST)
            if (playing) mp.start()
        } catch (_: IllegalStateException) {}
        applyVolumes()
        seek.max = tracks.maxOf { tr -> tr.mp?.let { runCatching { it.duration }.getOrDefault(0) + tr.offsetMs } ?: 0 }
        playBtn.text = if (playing) "❚❚" else "▶"
    }

    private fun applyVolumes() {
        // One soundtrack at a time: the first clip that is showing.
        val m = master()
        for (t in tracks) { val v = if (t === m) 1f else 0f; runCatching { t.mp?.setVolume(v, v) } }
    }

    private fun play() {
        playing = true
        if (currentPos() >= seek.max - 200) seekAll(0)
        tracks.forEach { t -> runCatching { t.mp?.start() } }
        playBtn.text = "❚❚"
    }

    private fun pause() {
        playing = false
        tracks.forEach { t -> runCatching { t.mp?.pause() } }
        playBtn.text = "▶"
    }

    private fun finished() = pause()

    private fun seekAll(ms: Int) {
        positionMs = ms
        for (t in tracks) {
            val mp = t.mp ?: continue
            runCatching { mp.seekTo(t.local(ms).coerceAtLeast(0).toLong(), MediaPlayer.SEEK_CLOSEST) }
        }
        updateTime()
    }

    private fun currentPos(): Int {
        val m = master() ?: return positionMs
        return m.mp?.let { m.shared(it) } ?: positionMs
    }

    private fun updateTime() {
        time.text = "${ClipsActivity.fmtDuration(positionMs.toLong())} / ${ClipsActivity.fmtDuration(seek.max.toLong())}"
    }

    private var lastSync = 0L
    private val tick = object : Runnable {
        override fun run() {
            if (!dragging) {
                positionMs = currentPos()
                seek.progress = positionMs
                updateTime()
                // Keep followers within ~0.4 s of the master (checked every 2 s to avoid stutter).
                val now = System.currentTimeMillis()
                val m = master()
                val a = m?.mp?.let { m.shared(it) }
                if (playing && a != null && now - lastSync > 2000) {
                    lastSync = now
                    for (t in tracks) {
                        if (t === m) continue
                        val mp = t.mp ?: continue
                        val b = t.shared(mp) ?: continue
                        val target = t.local(a)
                        if (abs(a - b) > 400 && target >= 0) runCatching { mp.seekTo(target.toLong(), MediaPlayer.SEEK_CLOSEST) }
                    }
                }
            }
            ui.postDelayed(this, 250)
        }
    }

    companion object {
        const val EXTRA_KEY = "key"
        const val EXTRA_FRONT = "front"
        const val EXTRA_REAR = "rear"
        const val EXTRA_REMOTE = "remote"
    }
}
