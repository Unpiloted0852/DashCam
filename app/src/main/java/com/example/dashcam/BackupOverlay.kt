package com.example.dashcam

import android.annotation.SuppressLint
import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.TextView
import androidx.preference.PreferenceManager
import kotlin.math.abs

/**
 * Backup Cam button floating over other apps (navigation, music, …): while a remote camera is ready
 * and the car is slow, a tap brings DashCam to the front with the Backup Cam view, which hands the
 * screen back to the app you were in once you drive off. Drag it to move it; the spot is kept.
 * Needs Android's "Display over other apps" permission.
 */
class BackupOverlay(private val ctx: Context) {

    private val wm = ctx.getSystemService(WindowManager::class.java)
    private val prefs = PreferenceManager.getDefaultSharedPreferences(ctx)
    private var view: TextView? = null

    val showing: Boolean get() = view != null

    fun update(wanted: Boolean) {
        val show = wanted && Settings.canDrawOverlays(ctx) && !appInFront()
        if (show && view == null) add() else if (!show && view != null) remove()
    }

    fun remove() {
        view?.let { try { wm.removeView(it) } catch (_: Exception) {} }
        view = null
    }

    /** One of DashCam's own screens is on top (then the normal button does the job). */
    private fun appInFront(): Boolean {
        val info = ActivityManager.RunningAppProcessInfo()
        ActivityManager.getMyMemoryState(info)
        return info.importance <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND
    }

    private fun dp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, ctx.resources.displayMetrics)

    @SuppressLint("ClickableViewAccessibility")
    private fun add() {
        val b = TextView(ctx).apply {
            text = "◀ BACKUP CAM"
            setTextColor(0xFF000000.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            gravity = Gravity.CENTER
            minWidth = dp(200f).toInt()
            minHeight = dp(84f).toInt()
            setPadding(dp(20f).toInt(), 0, dp(20f).toInt(), 0)
            setBackgroundResource(R.drawable.backup_bg)
            elevation = dp(6f)
            contentDescription = "Backup Cam"
        }
        val p = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,   // kept on screen by Android, wherever it's dragged
            PixelFormat.TRANSLUCENT).apply {
            gravity = Gravity.TOP or Gravity.START
            x = prefs.getInt(KEY_X, dp(16f).toInt())
            y = prefs.getInt(KEY_Y, (ctx.resources.displayMetrics.heightPixels / 2 - dp(42f)).toInt())
        }
        val slop = ViewConfiguration.get(ctx).scaledTouchSlop
        var downX = 0f; var downY = 0f; var startX = 0; var startY = 0; var dragging = false
        b.setOnTouchListener { v, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX; downY = e.rawY; startX = p.x; startY = p.y; dragging = false
                    v.isPressed = true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downX; val dy = e.rawY - downY
                    if (!dragging && (abs(dx) > slop || abs(dy) > slop)) { dragging = true; v.isPressed = false }
                    if (dragging) {
                        p.x = (startX + dx).toInt(); p.y = (startY + dy).toInt()
                        try { wm.updateViewLayout(v, p) } catch (_: Exception) {}
                    }
                }
                MotionEvent.ACTION_UP -> {
                    v.isPressed = false
                    if (dragging) prefs.edit().putInt(KEY_X, p.x).putInt(KEY_Y, p.y).apply()
                    else open()
                }
                MotionEvent.ACTION_CANCEL -> v.isPressed = false
            }
            true
        }
        try {
            wm.addView(b, p)
            view = b
        } catch (e: Exception) {
            EventLog.log("Backup", "can't show the button over other apps: ${e.message}")
        }
    }

    private fun open() {
        remove()
        EventLog.log("Backup", "Backup Cam opened from over another app")
        ctx.startActivity(Intent(ctx, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
            .putExtra(MainActivity.EXTRA_BACKUP_OVERLAY, true))
    }

    companion object {
        private const val KEY_X = "backup_overlay_x"
        private const val KEY_Y = "backup_overlay_y"
    }
}
