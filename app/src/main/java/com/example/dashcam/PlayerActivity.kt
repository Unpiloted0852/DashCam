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
 * Plays one recording: front only, rear only, or both side by side kept in sync.
 * Sound comes from the front clip in "Both" (the clips carry the same audio).
 */
class PlayerActivity : AppCompatActivity() {
    private enum class Mode { FRONT, BOTH, REAR }

    private lateinit var videos: LinearLayout
    private lateinit var frontBox: View
    private lateinit var rearBox: View
    private lateinit var frontVideo: VideoView
    private lateinit var rearVideo: VideoView
    private lateinit var seek: SeekBar
    private lateinit var time: TextView
    private lateinit var playBtn: Button
    private lateinit var lockBtn: Button
    private lateinit var actions: ClipActions

    private var pair: ClipPair? = null
    private var frontUri: Uri? = null
    private var rearUri: Uri? = null
    private var frontMp: MediaPlayer? = null
    private var rearMp: MediaPlayer? = null
    private var mode = Mode.BOTH
    private var playing = true
    /** Position on the front clip's timeline; the rear clip runs at (position - rearOffsetMs). */
    private var positionMs = 0
    /** How much later the rear clip started than the front one (ms), from the recorded start times. */
    private var rearOffsetMs = 0
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
        frontBox = findViewById(R.id.frontBox); rearBox = findViewById(R.id.rearBox)
        frontVideo = findViewById(R.id.frontVideo); rearVideo = findViewById(R.id.rearVideo)
        seek = findViewById(R.id.seek); time = findViewById(R.id.time)
        playBtn = findViewById(R.id.playBtn); lockBtn = findViewById(R.id.lockBtn)
        actions = ClipActions(this) { refreshPair() }

        frontUri = intent.getStringExtra(EXTRA_FRONT)?.let(Uri::parse)
        rearUri = intent.getStringExtra(EXTRA_REAR)?.let(Uri::parse)
        val key = intent.getStringExtra(EXTRA_KEY) ?: ""
        val fs = Storage.clipStart(this, "${key}_front.mp4")
        val rs = Storage.clipStart(this, "${key}_rear.mp4")
        if (fs != null && rs != null && frontUri != null) rearOffsetMs = (rs - fs).toInt().coerceIn(-10_000, 10_000)
        val start = ClipPair(key, null, null).startMs
        findViewById<TextView>(R.id.title).text = if (start != null) DateUtils.formatDateTime(this, start,
            DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_TIME or DateUtils.FORMAT_SHOW_WEEKDAY or
                DateUtils.FORMAT_SHOW_YEAR) else key

        positionMs = savedInstanceState?.getInt("pos") ?: 0
        playing = savedInstanceState?.getBoolean("playing") ?: true
        mode = savedInstanceState?.getString("mode")?.let { Mode.valueOf(it) } ?: when {
            frontUri != null && rearUri != null -> Mode.BOTH
            frontUri != null -> Mode.FRONT
            else -> Mode.REAR
        }

        frontVideo.setOnPreparedListener { mp -> frontMp = mp; onPrepared(mp) }
        rearVideo.setOnPreparedListener { mp -> rearMp = mp; onPrepared(mp) }
        frontVideo.setOnCompletionListener { if (master() === frontMp) finished() }
        rearVideo.setOnCompletionListener { if (master() === rearMp) finished() }
        frontVideo.setOnErrorListener { _, _, _ -> frontMp = null; true }
        rearVideo.setOnErrorListener { _, _, _ -> rearMp = null; true }

        playBtn.setOnClickListener { if (playing) pause() else play() }
        findViewById<Button>(R.id.modeFront).setOnClickListener { setMode(Mode.FRONT) }
        findViewById<Button>(R.id.modeBoth).setOnClickListener { setMode(Mode.BOTH) }
        findViewById<Button>(R.id.modeRear).setOnClickListener { setMode(Mode.REAR) }
        if (frontUri == null || rearUri == null) {
            listOf(R.id.modeFront, R.id.modeBoth, R.id.modeRear).forEach { findViewById<View>(it).visibility = View.GONE }
        }
        lockBtn.setOnClickListener { pair?.let { actions.setLocked(it, !it.locked) } }
        findViewById<Button>(R.id.shareBtn).setOnClickListener { pair?.let { actions.share(it) } }
        findViewById<Button>(R.id.deleteBtn).setOnClickListener {
            pair?.let { p -> pause(); actions.delete(p) { finish() } }
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
        out.putInt("pos", currentPos()); out.putBoolean("playing", playing); out.putString("mode", mode.name)
    }

    override fun onResume() { super.onResume(); ui.post(tick) }
    override fun onPause() {
        positionMs = currentPos()
        ui.removeCallbacks(tick)
        frontVideo.pause(); rearVideo.pause()
        super.onPause()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        applyOrientation()
    }

    private fun applyOrientation() {
        val portrait = resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT
        videos.orientation = if (portrait) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
        for (b in listOf(frontBox, rearBox)) {
            b.layoutParams = LinearLayout.LayoutParams(
                if (portrait) LinearLayout.LayoutParams.MATCH_PARENT else 0,
                if (portrait) 0 else LinearLayout.LayoutParams.MATCH_PARENT, 1f)
        }
    }

    private fun refreshPair() {
        // Re-read from MediaStore so the lock state is current after lock/unlock.
        Thread {
            val p = ClipRepo.load(this).firstOrNull { pp ->
                pp.clips.any { it.uri == frontUri || it.uri == rearUri }
            }
            ui.post {
                if (p == null && pair != null) { finish(); return@post }
                pair = p
                lockBtn.text = if (p?.locked == true) "🔓 Unlock" else "🔒 Lock"
            }
        }.start()
    }

    private fun setMode(m: Mode) {
        positionMs = currentPos()
        mode = m
        val showFront = m != Mode.REAR && frontUri != null
        val showRear = m != Mode.FRONT && rearUri != null
        // Hiding a VideoView destroys its surface and player; it reopens (and re-syncs) when shown.
        if (!showFront) { frontVideo.stopPlayback(); frontMp = null }
        if (!showRear) { rearVideo.stopPlayback(); rearMp = null }
        frontBox.visibility = if (showFront) View.VISIBLE else View.GONE
        rearBox.visibility = if (showRear) View.VISIBLE else View.GONE
        if (showFront && frontMp == null) frontVideo.setVideoURI(frontUri)
        if (showRear && rearMp == null) rearVideo.setVideoURI(rearUri)
        listOf(R.id.modeFront to Mode.FRONT, R.id.modeBoth to Mode.BOTH, R.id.modeRear to Mode.REAR).forEach { (id, md) ->
            findViewById<View>(id).alpha = if (md == m) 1f else 0.5f
        }
        applyVolumes()
    }

    /** The clip that drives the seek bar and the other clip follows. */
    private fun master(): MediaPlayer? = if (mode == Mode.REAR) rearMp else frontMp ?: rearMp

    private fun localPos(mp: MediaPlayer, pos: Int) = if (mp === rearMp) pos - rearOffsetMs else pos

    private fun onPrepared(mp: MediaPlayer) {
        mp.setOnSeekCompleteListener(null)
        try {
            mp.seekTo(localPos(mp, positionMs).coerceAtLeast(0).toLong(), MediaPlayer.SEEK_CLOSEST)
            if (playing) mp.start()
        } catch (_: IllegalStateException) {}
        applyVolumes()
        val fd = frontMp?.let { runCatching { it.duration }.getOrDefault(0) } ?: 0
        val rd = rearMp?.let { runCatching { it.duration }.getOrDefault(0) + rearOffsetMs } ?: 0
        seek.max = maxOf(fd, rd)
        playBtn.text = if (playing) "❚❚" else "▶"
    }

    private fun applyVolumes() {
        // One soundtrack at a time: front in Front/Both, rear in Rear.
        runCatching { frontMp?.setVolume(if (mode == Mode.REAR) 0f else 1f, if (mode == Mode.REAR) 0f else 1f) }
        val rearVol = if (mode == Mode.REAR || frontMp == null) 1f else 0f
        runCatching { rearMp?.setVolume(rearVol, rearVol) }
    }

    private fun play() {
        playing = true
        if (currentPos() >= seek.max - 200) seekAll(0)
        runCatching { frontMp?.start() }; runCatching { rearMp?.start() }
        playBtn.text = "❚❚"
    }

    private fun pause() {
        playing = false
        runCatching { frontMp?.pause() }; runCatching { rearMp?.pause() }
        playBtn.text = "▶"
    }

    private fun finished() {
        playing = false
        runCatching { frontMp?.pause() }; runCatching { rearMp?.pause() }
        playBtn.text = "▶"
    }

    private fun seekAll(ms: Int) {
        positionMs = ms
        listOfNotNull(frontMp, rearMp).forEach { mp ->
            runCatching { mp.seekTo(localPos(mp, ms).coerceAtLeast(0).toLong(), MediaPlayer.SEEK_CLOSEST) }
        }
        updateTime()
    }

    private fun currentPos(): Int {
        val m = master() ?: return positionMs
        val p = runCatching { m.currentPosition }.getOrNull() ?: return positionMs
        return if (m === rearMp) p + rearOffsetMs else p
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
                // Keep the follower within ~0.4 s of the master (checked every 2 s to avoid stutter).
                val now = System.currentTimeMillis()
                val m = master(); val other = if (m === frontMp) rearMp else frontMp
                if (playing && m != null && other != null && now - lastSync > 2000) {
                    lastSync = now
                    // Compare on the shared (front) timeline.
                    val a = runCatching { m.currentPosition }.getOrNull()?.let { if (m === rearMp) it + rearOffsetMs else it }
                    val b = runCatching { other.currentPosition }.getOrNull()?.let { if (other === rearMp) it + rearOffsetMs else it }
                    if (a != null && b != null && abs(a - b) > 400) {
                        val target = localPos(other, a)
                        if (target >= 0) runCatching { other.seekTo(target.toLong(), MediaPlayer.SEEK_CLOSEST) }
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
    }
}
