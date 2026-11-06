package com.example.dashcam

import android.app.Activity
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.preference.PreferenceManager
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Self-update from GitHub Releases: compares the latest release tag (vX.Y.Z) with this build and,
 * if newer, offers to download the attached APK and install it through Android's package installer
 * (the user confirms the install on the system screen).
 */
object Updater {
    private const val TAG = "Updater"
    private const val CHECK_EVERY_MS = 6 * 3600_000L
    private val main = Handler(Looper.getMainLooper())

    data class Release(val version: String, val notes: String, val apkUrl: String, val apkSize: Long, val pageUrl: String)

    /** Called when the main screen opens; quiet unless an update exists. */
    fun checkInBackground(a: Activity, isRecording: () -> Boolean) {
        val prefs = PreferenceManager.getDefaultSharedPreferences(a)
        if (!prefs.getBoolean("auto_update_check", true)) return
        val last = prefs.getLong("update_last_check", 0)
        if (System.currentTimeMillis() - last < CHECK_EVERY_MS) return
        prefs.edit().putLong("update_last_check", System.currentTimeMillis()).apply()
        Thread {
            val r = fetchLatest() ?: return@Thread
            if (!isNewer(r.version, BuildConfig.VERSION_NAME)) return@Thread
            if (prefs.getString("update_skipped", null) == r.version) return@Thread
            main.post {
                // Never interrupt a recording; it will ask again next time the app opens.
                if (!a.isFinishing && !isRecording()) offer(a, r, allowSkip = true)
                else prefs.edit().putLong("update_last_check", 0).apply()
            }
        }.start()
    }

    /** "Check for updates" button: always reports the result. */
    fun checkNow(a: Activity) {
        Toast.makeText(a, "Checking for updates…", Toast.LENGTH_SHORT).show()
        Thread {
            val r = fetchLatest()
            main.post {
                when {
                    a.isFinishing -> {}
                    r == null -> Toast.makeText(a, "Couldn't reach GitHub", Toast.LENGTH_LONG).show()
                    !isNewer(r.version, BuildConfig.VERSION_NAME) ->
                        Toast.makeText(a, "You're up to date (v${BuildConfig.VERSION_NAME})", Toast.LENGTH_LONG).show()
                    else -> offer(a, r, allowSkip = false)
                }
            }
        }.start()
    }

    private fun offer(a: Activity, r: Release, allowSkip: Boolean) {
        val b = AlertDialog.Builder(a)
            .setTitle("Update available: v${r.version}")
            .setMessage("You have v${BuildConfig.VERSION_NAME}.\n\n${r.notes.take(1500)}")
            .setPositiveButton("Update") { _, _ -> startInstall(a, r) }
            .setNegativeButton("Later", null)
        if (allowSkip) b.setNeutralButton("Skip this version") { _, _ ->
            PreferenceManager.getDefaultSharedPreferences(a).edit().putString("update_skipped", r.version).apply()
        }
        b.show()
    }

    private fun startInstall(a: Activity, r: Release) {
        if (!a.packageManager.canRequestPackageInstalls()) {
            Toast.makeText(a, "Allow DashCam to install updates, then tap Update again", Toast.LENGTH_LONG).show()
            a.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${a.packageName}")))
            PreferenceManager.getDefaultSharedPreferences(a).edit().putLong("update_last_check", 0).apply()
            return
        }
        Toast.makeText(a, "Downloading v${r.version}…", Toast.LENGTH_SHORT).show()
        val ctx = a.applicationContext
        Thread {
            try {
                val pi = ctx.packageManager.packageInstaller
                val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
                params.setAppPackageName(ctx.packageName)
                if (Build.VERSION.SDK_INT >= 31) {
                    params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
                }
                val id = pi.createSession(params)
                pi.openSession(id).use { session ->
                    val conn = URL(r.apkUrl).openConnection() as HttpURLConnection
                    conn.instanceFollowRedirects = true
                    conn.connectTimeout = 15_000; conn.readTimeout = 30_000
                    conn.inputStream.use { input ->
                        session.openWrite("dashcam.apk", 0, if (r.apkSize > 0) r.apkSize else -1).use { out ->
                            input.copyTo(out, 64 * 1024)
                            session.fsync(out)
                        }
                    }
                    val status = PendingIntent.getBroadcast(ctx, id,
                        Intent(ctx, InstallReceiver::class.java),
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE)
                    session.commit(status.intentSender)
                }
            } catch (e: Exception) {
                Log.e(TAG, "update failed", e)
                main.post { Toast.makeText(ctx, "Update failed: ${e.message}", Toast.LENGTH_LONG).show() }
            }
        }.start()
    }

    /** Receives the installer's result; shows Android's confirmation screen when it asks for one. */
    class InstallReceiver : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
                PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                    @Suppress("DEPRECATION")
                    val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT) ?: return
                    ctx.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }
                PackageInstaller.STATUS_SUCCESS -> {}
                else -> {
                    val msg = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: "unknown error"
                    Log.e(TAG, "install failed: $msg")
                    Toast.makeText(ctx, "Update not installed: $msg", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun fetchLatest(): Release? = try {
        val conn = URL("https://api.github.com/repos/${BuildConfig.UPDATE_REPO}/releases/latest")
            .openConnection() as HttpURLConnection
        conn.setRequestProperty("Accept", "application/vnd.github+json")
        conn.setRequestProperty("User-Agent", "DashCam-Android")
        conn.connectTimeout = 10_000; conn.readTimeout = 15_000
        if (conn.responseCode != 200) null else {
            val o = JSONObject(conn.inputStream.bufferedReader().readText())
            val assets = o.getJSONArray("assets")
            var url: String? = null; var size = 0L
            for (i in 0 until assets.length()) {
                val asset = assets.getJSONObject(i)
                if (asset.getString("name").endsWith(".apk")) {
                    url = asset.getString("browser_download_url"); size = asset.optLong("size"); break
                }
            }
            url?.let {
                Release(o.getString("tag_name").removePrefix("v"), o.optString("body", ""), it, size,
                    o.optString("html_url"))
            }
        }
    } catch (e: Exception) {
        Log.w(TAG, "update check failed: ${e.message}"); null
    }

    /** Numeric compare of dotted versions: 1.10.0 > 1.9.3. */
    fun isNewer(candidate: String, current: String): Boolean {
        val a = candidate.split('.', '-').map { it.toIntOrNull() ?: 0 }
        val b = current.split('.', '-').map { it.toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }; val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }
}
