package com.example.dashcam

import android.content.Context
import android.os.Build
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Small on-device log of camera / recording events, so problems seen on the road can be shared
 * from Settings and diagnosed later. Kept under ~256 KB.
 */
object EventLog {
    private const val MAX_BYTES = 256 * 1024
    private var file: File? = null
    private val fmt = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)

    fun init(ctx: Context) {
        if (file != null) return
        file = File(ctx.filesDir, "events.log")
        log("App", "start v${BuildConfig.VERSION_NAME} on ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE}")
    }

    @Synchronized
    fun log(tag: String, msg: String) {
        Log.i("Event/$tag", msg)
        val f = file ?: return
        try {
            f.appendText("${fmt.format(Date())} [$tag] $msg\n")
            if (f.length() > MAX_BYTES) {
                val text = f.readText()
                f.writeText(text.substring(text.length / 2).substringAfter('\n'))
            }
        } catch (_: Exception) {}
    }

    @Synchronized
    fun read(): String = try { file?.readText() ?: "" } catch (_: Exception) { "" }
}
