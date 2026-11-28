package com.example.dashcam

import android.content.Context
import android.provider.Settings
import android.view.Surface

/**
 * Optional: switch the phone's own auto-rotate with the drive, so it is on while driving and back
 * to upright portrait afterwards. Needs Android's "Modify system settings" permission.
 */
object AutoRotate {
    fun allowed(ctx: Context): Boolean = Settings.System.canWrite(ctx)

    /** A drive started: turn auto-rotate on if the user chose "on while driving". */
    fun onDriveStart(ctx: Context, mode: String) {
        if (mode == "drive") set(ctx, true)
    }

    /** The drive was detected as over: auto-rotate off, screen upright. */
    fun onDriveEnd(ctx: Context, mode: String) {
        if (mode == "drive" || mode == "end") set(ctx, false)
    }

    private fun set(ctx: Context, on: Boolean) {
        if (!allowed(ctx)) {
            EventLog.log("Rotate", "can't change auto-rotate: \"Modify system settings\" not allowed")
            return
        }
        try {
            val r = ctx.contentResolver
            if (!on) Settings.System.putInt(r, Settings.System.USER_ROTATION, Surface.ROTATION_0)
            if (Settings.System.getInt(r, Settings.System.ACCELEROMETER_ROTATION, 0) == (if (on) 1 else 0)) return
            Settings.System.putInt(r, Settings.System.ACCELEROMETER_ROTATION, if (on) 1 else 0)
            EventLog.log("Rotate", "phone auto-rotate turned ${if (on) "on" else "off (portrait)"}")
        } catch (e: Exception) {
            EventLog.log("Rotate", "can't change auto-rotate: ${e.message}")
        }
    }
}
