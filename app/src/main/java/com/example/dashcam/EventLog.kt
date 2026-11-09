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

    /** File writes happen here, so logging never stalls the UI, camera or encoder threads. */
    private val io = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
        Thread(r, "event-log").apply { isDaemon = true }
    }

    fun log(tag: String, msg: String) {
        Log.i("Event/$tag", msg)
        val f = file ?: return
        val now = Date()
        io.execute {
            try {
                f.appendText("${fmt.format(now)} [$tag] $msg\n")
                if (f.length() > MAX_BYTES) {
                    val text = f.readText()
                    f.writeText(text.substring(text.length / 2).substringAfter('\n'))
                }
            } catch (_: Exception) {}
        }
    }

    /** The whole log, including lines still waiting to be written. */
    fun read(): String = try {
        io.submit<String> { file?.readText() ?: "" }.get(3, java.util.concurrent.TimeUnit.SECONDS)
    } catch (_: Exception) { "" }
}
