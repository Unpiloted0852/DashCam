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
class OverlayTexture(val frameW: Int, val frameH: Int, private val drawer: FrameDrawer, val textSize: Float = 0.055f) {
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

    // Sub-pixel positioning keeps squeezed text from drifting wider than measured.
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG or Paint.LINEAR_TEXT_FLAG).apply {
        color = Color.WHITE
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        textSize = lineH * 0.70f
    }
    private val stroke = Paint(fill).apply {
        color = Color.BLACK
        style = Paint.Style.STROKE
        strokeWidth = fill.textSize * 0.14f
        strokeJoin = Paint.Join.ROUND
    }
    private val bar = Paint().apply { color = 0x80000000.toInt() }
    private val pad = lineH * 0.3f

    private var layoutKey: Any? = null
    private var maxChars = Int.MAX_VALUE
    private var scaleX = 1f
    private var barW = 0f

    /**
     * Size the stamp once from the widest possible text for these options, so the bar and the
     * text size stay put second to second instead of growing and shrinking with the data.
     */
    private fun layoutFor(o: OverlayOptions) {
        if (layoutKey == o) return
        layoutKey = o
        val avail = bmp.width - 2 * pad
        fill.textScaleX = 1f
        // How many characters fit at normal width; longer groups wrap onto extra lines.
        maxChars = (avail / fill.measureText("0")).toInt().coerceAtLeast(20)
        val template = Telemetry.overlayTemplate(o, portrait, maxChars).take(lineCount)
        fun widestAt(sx: Float): Float { fill.textScaleX = sx; return template.maxOf { fill.measureText(it) } }
        // Glyph widths don't scale exactly linearly, so measure with the squeeze applied and refine.
        var sx = 1f
        repeat(3) { val w = widestAt(sx); if (w > avail) sx *= avail / w * 0.995f }
        scaleX = sx
        // The box always spans the full frame width, however short the lines are.
        barW = bmp.width.toFloat()
    }

    /** [frameWallMs]: accurate wall time at which the frame was captured. */
    fun update(o: OverlayOptions, frameWallMs: Long) {
        val key = "${frameWallMs / 1000}|${Telemetry.version}|${o.hashCode()}"
        if (key == lastKey) return
        lastKey = key
        layoutFor(o)
        val lines = Telemetry.overlayLines(o, portrait, frameWallMs, maxChars).take(lineCount)

        bmp.eraseColor(Color.TRANSPARENT)
        fill.textScaleX = scaleX; stroke.textScaleX = scaleX
        val top = (lineCount - lines.size) * lineH   // keep text hugging the bottom edge
        canvas.drawRoundRect(RectF(0f, top, barW, bmpH.toFloat()), pad, pad, bar)
        lines.forEachIndexed { i, l ->
            val y = top + i * lineH + lineH * 0.78f
            canvas.drawText(l, pad, y, stroke); canvas.drawText(l, pad, y, fill)
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

    companion object {
        const val MAX_LINES = 8

        /** Characters per line that fit at normal width on a [frameW] x [frameH] frame. */
        fun maxCharsFor(frameW: Int, frameH: Int, textSize: Float): Int {
            val lineH = max(16, (minOf(frameW, frameH) * textSize).roundToInt()).toFloat()
            val p = Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
                typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
                this.textSize = lineH * 0.70f
            }
            return ((frameW - 2 * lineH * 0.3f) / p.measureText("0")).toInt().coerceAtLeast(20)
        }
    }
}
