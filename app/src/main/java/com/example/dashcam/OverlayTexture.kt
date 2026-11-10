package com.example.dashcam

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.opengl.GLES20
import android.opengl.GLUtils
import java.nio.FloatBuffer
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Text stamp (time/date/GPS/speed) drawn with Canvas into a bitmap, uploaded as a GL texture and
 * composited onto the bottom of every frame. Must be used on the GL thread.
 */
class OverlayTexture(val frameW: Int, val frameH: Int, private val drawer: FrameDrawer, val textSize: Float = 0.044f) {
    private val portrait = frameH > frameW
    /** Room for the most lines any combination of options can wrap to; unused rows stay clear. */
    private val lineCount = MAX_LINES
    private val lineH = max(16, (minOf(frameW, frameH) * textSize).roundToInt()).toFloat()
    private val bmpH = (lineH * lineCount).roundToInt()
    private val bmp = Bitmap.createBitmap(frameW, bmpH, Bitmap.Config.ARGB_8888)
    private val canvas = Canvas(bmp)
    private val tex = drawer.create2dTexture()
    private var allocated = false
    private var lastKey = ""

    private val quad: FloatBuffer = run {
        val top = -1f + 2f * bmpH / frameH
        FrameDrawer.floats(-1f, -1f, 1f, -1f, -1f, top, 1f, top)
    }

    private val fill = newPaint(lineH)
    private val stroke = Paint(fill).apply {
        color = Color.BLACK
        style = Paint.Style.STROKE
        strokeWidth = fill.textSize * 0.14f
        strokeJoin = Paint.Join.ROUND
    }
    // See-through background: the outlined text stays readable and the video shows through.
    private val bar = Paint().apply { color = 0x55000000 }
    private val pad = lineH * 0.3f

    private var layoutKey: Any? = null
    private var layout: Layout? = null

    /** Work out, once per set of options, where every field sits; live values then fill those spots. */
    private fun layoutFor(o: OverlayOptions): Layout {
        // Re-plan whenever what's planned for changes: options, new METAR, starting/stopping, etc.
        val template = Telemetry.stampTemplate(o, portrait)
        val key = template
        layout?.let { if (layoutKey == key) return it }
        layoutKey = key
        return computeLayout(fill, template, textWidth(bmp.width, lineH, o), lineH)
            .also { lay ->
                layout = lay; stroke.textScaleX = fill.textScaleX
                EventLog.log("Stamp", "${frameW}x$frameH: ${lay.lines} lines — " + template.mapIndexed { gi, g ->
                    g.mapIndexed { fi, f -> "${lay.slots[gi][fi].line}:${f.text}" }.joinToString(" | ")
                }.joinToString(" // "))
            }
    }

    /** [frameWallMs]: accurate wall time at which the frame was captured. */
    fun update(o: OverlayOptions, frameWallMs: Long) {
        val key = "${frameWallMs / 1000}|${Telemetry.version}|${o.hashCode()}"
        if (key == lastKey) return
        lastKey = key
        val lay = layoutFor(o)
        val groups = Telemetry.stampFields(o, portrait, frameWallMs)

        bmp.eraseColor(Color.TRANSPARENT)
        val lines = lay.lines.coerceAtMost(lineCount)
        val top = (lineCount - lines) * lineH   // keep the stamp hugging the bottom edge
        // Full-width box exactly as tall as the text lines.
        canvas.drawRect(RectF(0f, top, bmp.width.toFloat(), bmpH.toFloat()), bar)
        // Which line each live field goes on comes from the layout; within a line, fields are drawn
        // with even gaps (empty ones, like heading while stopped, take no space).
        val perLine = Array(lines) { ArrayList<Pair<Telemetry.Field, Boolean>>() }
        groups.forEachIndexed { gi, fields ->
            fields.forEachIndexed { fi, f ->
                val slot = lay.slots.getOrNull(gi)?.getOrNull(fi) ?: return@forEachIndexed
                if (slot.line < lines && f.text.isNotEmpty()) perLine[slot.line].add(f to slot.right)
            }
        }
        val avail = textWidth(bmp.width, lineH, o)
        val gap = lineH * GAP_EM
        for (li in 0 until lines) {
            val left = perLine[li].filter { !it.second }.map { it.first.text }
            val right = perLine[li].filter { it.second }.map { it.first.text }
            val sx = lay.scaleX
            fill.textScaleX = sx; stroke.textScaleX = sx
            fun width(items: List<String>) = items.sumOf { fill.measureText(it).toDouble() }.toFloat() + gap * (items.size - 1).coerceAtLeast(0)
            val need = width(left) + (if (right.isEmpty()) 0f else gap * 2 + width(right))
            // Rare: live values longer than planned for — narrow just this line to fit.
            if (need > avail) { val k = sx * avail / need; fill.textScaleX = k; stroke.textScaleX = k }
            val y = top + li * lineH + lineH * 0.76f
            var x = pad
            for (t in left) { canvas.drawText(t, x, y, stroke); canvas.drawText(t, x, y, fill); x += fill.measureText(t) + gap }
            if (right.isNotEmpty()) {
                var rx = pad + avail - width(right)
                for (t in right) { canvas.drawText(t, rx, y, stroke); canvas.drawText(t, rx, y, fill); rx += fill.measureText(t) + gap }
            }
        }

        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, tex)
        if (!allocated) {
            GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bmp, 0)
            allocated = true
        } else {
            GLUtils.texSubImage2D(GLES20.GL_TEXTURE_2D, 0, 0, 0, bmp)
        }
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)
    }

    fun draw() {
        if (allocated) drawer.drawOverlay(tex, quad)
    }

    /**
     * Acceleration gauge just above the stamp, at the right edge: a bar with a zero line across the
     * middle, green growing upwards while speeding up, red growing downwards while braking, and
     * the value in g underneath. Drawn straight in GL on every frame (the text bitmap only changes
     * once a second). [g]: forward acceleration, null if unknown.
     */
    fun drawAccel(o: OverlayOptions, g: Float?) {
        if (!o.showAccel || !allocated) return
        val lines = (layout?.lines ?: return).coerceAtMost(lineCount)
        fun x(px: Float) = -1f + 2f * px / frameW
        fun y(pxFromBottom: Float) = -1f + 2f * pxFromBottom / frameH
        val right = frameW - pad
        val stampTop = lines * lineH

        // The number, right-aligned on its own line above the stamp. Refreshed 4 times a second:
        // digits changing on every frame can't be read.
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - accelTextAt >= 250) {
            accelTextAt = now
            accelText = if (g == null) "" else {
                val v = Math.round(g * 100) / 100f
                (if (v > 0) "+" else if (v < 0) "\u2212" else " ") + String.format(java.util.Locale.US, "%.2f", kotlin.math.abs(v)) + " g"
            }
        }
        val a = glyphs ?: Glyphs().also { glyphs = it }
        val textBottom = stampTop + lineH * 0.12f
        // Glyph cells include room for the outline; step by the letter width so the spacing matches the stamp.
        var cx = right - (accelText.length - 1) * a.advance - a.cellW
        for (ch in accelText) {
            val i = GLYPHS.indexOf(ch)
            if (i >= 0) drawer.drawTex(a.tex, x(cx), y(textBottom), x(cx + a.cellW), y(textBottom + a.cellH),
                i * a.cellW / a.width, 0f, (i + 1) * a.cellW / a.width, 1f)
            cx += a.advance
        }

        // The bar, above the number.
        val barW = lineH * ACCEL_W_EM
        val x1 = right; val x0 = x1 - barW
        val bottom = textBottom + a.cellH + lineH * 0.15f
        val half = lineH * ACCEL_HALF_EM
        val mid = bottom + half; val top = mid + half
        val edge = max(1f, lineH * 0.04f)
        drawer.drawRect(x(x0 - edge), y(bottom - edge), x(x1 + edge), y(top + edge), 0x66000000)   // outline
        drawer.drawRect(x(x0), y(bottom), x(x1), y(top), 0x55FFFFFF)                                // track
        if (g != null) {
            val h = (g / ACCEL_FULL_G).coerceIn(-1f, 1f) * half
            if (h > 0) drawer.drawRect(x(x0), y(mid), x(x1), y(mid + h), 0xFF2ECC40.toInt())        // speeding up
            else if (h < 0) drawer.drawRect(x(x0), y(mid + h), x(x1), y(mid), 0xFFFF3B30.toInt())   // braking
        }
        drawer.drawRect(x(x0 - barW * 0.35f), y(mid - edge), x(x1), y(mid + edge), 0xFFFFFFFF.toInt())   // zero line
    }

    private var accelText = ""
    private var accelTextAt = 0L
    private var glyphs: Glyphs? = null

    /** The characters of the g readout, drawn once (white with a dark outline, like the stamp). */
    private inner class Glyphs {
        val cellW = Paint(fill).apply { textScaleX = 1f }.measureText("0") + stroke.strokeWidth
        val advance = cellW - stroke.strokeWidth
        val cellH = lineH * 0.92f
        /** Atlas width in pixels (whole pixels, so glyph positions map exactly onto the texture). */
        val width = Math.ceil((cellW * GLYPHS.length).toDouble()).toFloat()
        val tex = drawer.create2dTexture()
        init {
            val b = Bitmap.createBitmap(width.toInt(), Math.ceil(cellH.toDouble()).toInt(), Bitmap.Config.ARGB_8888)
            val c = Canvas(b)
            val s = Paint(stroke).apply { textScaleX = 1f }
            val f = Paint(fill).apply { textScaleX = 1f }
            for ((i, ch) in GLYPHS.withIndex()) {
                val px = i * cellW + stroke.strokeWidth / 2; val py = cellH * 0.78f
                c.drawText(ch.toString(), px, py, s); c.drawText(ch.toString(), px, py, f)
            }
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, tex)
            GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, b, 0)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)
            b.recycle()
        }
        fun release() { GLES20.glDeleteTextures(1, intArrayOf(tex), 0) }
    }

    fun release() {
        GLES20.glDeleteTextures(1, intArrayOf(tex), 0)
        glyphs?.release(); glyphs = null
        bmp.recycle()
    }

    /** Where one field goes: line number, left edge and reserved width (pixels, inside the padding). */
    class Slot(val line: Int, val x: Float, val width: Float, val right: Boolean)
    class Layout(val slots: List<List<Slot>>, val lines: Int, val scaleX: Float)

    companion object {
        const val MAX_LINES = 8
        /** Space between fields, as a fraction of the line height. */
        const val GAP_EM = 0.6f
        /** Acceleration bar, in line heights: width, and the height of each half (up = speeding up). */
        const val ACCEL_W_EM = 0.7f
        const val ACCEL_HALF_EM = 2.2f
        /** The acceleration that fills half the bar. */
        const val ACCEL_FULL_G = 0.6f
        /** Characters the g readout can show (U+2212 is the minus sign). */
        const val GLYPHS = "0123456789.+\u2212g "

        /** Width available to the stamp's text. */
        @Suppress("UNUSED_PARAMETER")
        fun textWidth(frameW: Int, lineH: Float, o: OverlayOptions): Float = frameW - 2 * lineH * 0.3f

        /** Bold monospace: every digit is the same width, so numbers don't wobble as they change. */
        fun newPaint(lineH: Float) = Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
            color = Color.WHITE
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            textSize = lineH * 0.70f
        }

        /**
         * Place fields from the widest-case [template]: each group starts a new line, fields flow
         * left to right with a fixed gap and wrap when the next one wouldn't fit; right-aligned
         * fields (camera name) sit at the right end of their group's first line. If a single field
         * is wider than the line, the font is narrowed just enough to fit it.
         */
        fun computeLayout(paint: Paint, template: List<List<Telemetry.Field>>, avail: Float, lineH: Float): Layout {
            val gap = lineH * GAP_EM
            paint.textScaleX = 1f
            val widest = template.flatten().maxOfOrNull { paint.measureText(it.text) } ?: 0f
            if (widest > avail) paint.textScaleX = (avail / widest) * 0.98f
            val slots = ArrayList<List<Slot>>()
            var line = 0
            for (group in template) {
                val firstLine = line
                val rightW = group.filter { it.right }.sumOf { paint.measureText(it.text).toDouble() }.toFloat()
                var limit = if (rightW > 0) avail - rightW - gap else avail
                var x = -1f   // -1 = nothing on this line yet
                val out = ArrayList<Slot>()
                for (f in group) {
                    val w = paint.measureText(f.text)
                    if (f.right) { out.add(Slot(firstLine, avail - w, w, true)); continue }
                    if (f.text.isEmpty()) { out.add(Slot(line, x.coerceAtLeast(0f), 0f, false)); continue }
                    if (x >= 0 && x + gap + w > limit) { line++; x = -1f; limit = avail }
                    val left = if (x < 0) 0f else x + gap
                    out.add(Slot(line, left, w, false))
                    x = left + w
                }
                slots.add(out)
                line++
            }
            return Layout(slots, line, paint.textScaleX)
        }

        /** How many lines the stamp takes on a [frameW] x [frameH] frame (for placing on-screen controls). */
        fun lineCountFor(frameW: Int, frameH: Int, o: OverlayOptions): Int {
            val lineH = max(16, (minOf(frameW, frameH) * o.textSize).roundToInt()).toFloat()
            val avail = textWidth(frameW, lineH, o)
            return computeLayout(newPaint(lineH), Telemetry.stampTemplate(o, frameH > frameW), avail, lineH).lines
        }
    }
}
