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
        // Re-plan if the options change or the position moves to a different number of degree digits.
        val key = o to Telemetry.coordDigits()
        layout?.let { if (layoutKey == key) return it }
        layoutKey = key
        return computeLayout(fill, Telemetry.stampTemplate(o, portrait), bmp.width - 2 * pad, lineH)
            .also { layout = it; stroke.textScaleX = fill.textScaleX }
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
        val avail = bmp.width - 2 * pad
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

    fun release() {
        GLES20.glDeleteTextures(1, intArrayOf(tex), 0)
        bmp.recycle()
    }

    /** Where one field goes: line number, left edge and reserved width (pixels, inside the padding). */
    class Slot(val line: Int, val x: Float, val width: Float, val right: Boolean)
    class Layout(val slots: List<List<Slot>>, val lines: Int, val scaleX: Float)

    companion object {
        const val MAX_LINES = 8
        /** Space between fields, as a fraction of the line height. */
        const val GAP_EM = 0.6f

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
            val avail = frameW - 2 * lineH * 0.3f
            return computeLayout(newPaint(lineH), Telemetry.stampTemplate(o, frameH > frameW), avail, lineH).lines
        }
    }
}
