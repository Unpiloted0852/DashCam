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
    const val EXTRA_AFTER_UPDATE = "after_update"
    private const val CHECK_EVERY_MS = 6 * 3600_000L
    private val main = Handler(Looper.getMainLooper())

    data class Release(val version: String, val notes: String, val apkUrl: String, val apkSize: Long, val pageUrl: String)

    /** Called when the main screen opens; quiet unless an update exists. */
    fun checkInBackground(a: Activity, isRecording: () -> Boolean) {
        val prefs = PreferenceManager.getDefaultSharedPreferences(a)
        if (!BuildConfig.UPDATES_ENABLED) return
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
        if (!BuildConfig.UPDATES_ENABLED) {
            Toast.makeText(a, "Experimental build: updates are off", Toast.LENGTH_LONG).show(); return
        }
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
            val pi = ctx.packageManager.packageInstaller
            var lastError: Exception? = null
            // A download can drop on a weak connection: try a few times before giving up.
            for (attempt in 1..3) {
                var id = -1
                try {
                    val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
                    params.setAppPackageName(ctx.packageName)
                    if (Build.VERSION.SDK_INT >= 31) {
                        params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
                    }
                    id = pi.createSession(params)
                    val session = pi.openSession(id)
                    session.use {
                        val conn = URL(r.apkUrl).openConnection() as HttpURLConnection
                        conn.instanceFollowRedirects = true
                        conn.connectTimeout = 15_000; conn.readTimeout = 30_000
                        var got = 0L
                        try {
                            if (conn.responseCode != 200) throw java.io.IOException("server answered ${conn.responseCode}")
                            conn.inputStream.use { input ->
                                session.openWrite("dashcam.apk", 0, if (r.apkSize > 0) r.apkSize else -1).use { out ->
                                    got = input.copyTo(out, 64 * 1024)
                                    session.fsync(out)
                                }
                            }
                        } finally { conn.disconnect() }
                        if (r.apkSize > 0 && got < r.apkSize) throw java.io.IOException("download incomplete ($got of ${r.apkSize} bytes)")
                        val status = PendingIntent.getBroadcast(ctx, id,
                            Intent(ctx, InstallReceiver::class.java),
                            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE)
                        // Installing replaces the running app: let a recording finish its clips first.
                        val ready = java.util.concurrent.CountDownLatch(1)
                        val hook = beforeInstall
                        if (hook != null) main.post { try { hook { ready.countDown() } } catch (_: Exception) { ready.countDown() } }
                        else ready.countDown()
                        ready.await(15, java.util.concurrent.TimeUnit.SECONDS)
                        // commit(): the app is about to be replaced, an async write might not land.
                        PreferenceManager.getDefaultSharedPreferences(ctx).edit().putString("update_installing", r.version).commit()
                        EventLog.log("Update", "installing v${r.version}")
                        session.commit(status.intentSender)
                    }
                    return@Thread
                } catch (e: Exception) {
                    lastError = e
                    Log.e(TAG, "update attempt $attempt failed", e)
                    EventLog.log("Update", "download attempt $attempt failed: ${e.javaClass.simpleName}: ${e.message}")
                    if (id >= 0) try { pi.abandonSession(id) } catch (_: Exception) {}
                    if (attempt < 3) try { Thread.sleep(2000L * attempt) } catch (_: InterruptedException) { break }
                }
            }
            main.post { Toast.makeText(ctx, "Update failed: ${lastError?.message ?: "unknown error"}", Toast.LENGTH_LONG).show() }
        }.start()
    }

    /** Can this phone install an update without anyone tapping (needs Android 12 and the install permission)? */
    fun canInstallQuietly(ctx: Context) = Build.VERSION.SDK_INT >= 31 && ctx.packageManager.canRequestPackageInstalls()

    /** Can this phone bring itself back on screen after an update ("display over other apps")? */
    fun canReopen(ctx: Context) = Settings.canDrawOverlays(ctx)

    /** Version name and code of an APK file, or null if it isn't a readable APK of this app. */
    fun apkVersion(ctx: Context, apk: java.io.File): Pair<String, Long>? = try {
        val pi = ctx.packageManager.getPackageArchiveInfo(apk.path, 0)
        if (pi == null || pi.packageName != ctx.packageName) null else (pi.versionName ?: "?") to pi.longVersionCode
    } catch (_: Exception) { null }

    /**
     * Install an update that is already on this phone (sent by the main phone to a remote camera).
     * Returns null once the install is handed to Android, else what is wrong. Android itself only
     * accepts an APK signed with the same key as the installed app. Blocking.
     */
    fun installFile(ctx: Context, apk: java.io.File): String? {
        val (name, code) = apkVersion(ctx, apk) ?: return "not a DashCam update"
        val mine = ctx.packageManager.getPackageInfo(ctx.packageName, 0).longVersionCode
        if (code <= mine) return "already on v${BuildConfig.VERSION_NAME}"
        if (!ctx.packageManager.canRequestPackageInstalls()) return "installing updates isn't allowed on this phone yet"
        val pi = ctx.packageManager.packageInstaller
        var id = -1
        return try {
            val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
            params.setAppPackageName(ctx.packageName)
            if (Build.VERSION.SDK_INT >= 31) params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
            id = pi.createSession(params)
            pi.openSession(id).use { session ->
                apk.inputStream().use { input ->
                    session.openWrite("dashcam.apk", 0, apk.length()).use { out -> input.copyTo(out, 64 * 1024); session.fsync(out) }
                }
                val status = PendingIntent.getBroadcast(ctx, id, Intent(ctx, InstallReceiver::class.java),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE)
                val ready = java.util.concurrent.CountDownLatch(1)
                val hook = beforeInstall
                if (hook != null) main.post { try { hook { ready.countDown() } } catch (_: Exception) { ready.countDown() } }
                else ready.countDown()
                ready.await(15, java.util.concurrent.TimeUnit.SECONDS)
                PreferenceManager.getDefaultSharedPreferences(ctx).edit().putString("update_installing", name).commit()
                EventLog.log("Update", "installing v$name sent by the main phone")
                session.commit(status.intentSender)
            }
            null
        } catch (e: Exception) {
            if (id >= 0) try { pi.abandonSession(id) } catch (_: Exception) {}
            EventLog.log("Update", "install from the main phone failed: ${e.message}")
            e.message ?: "install failed"
        }
    }

    /** Set by the recording service: stops a recording cleanly, then calls back. */
    @Volatile var beforeInstall: ((done: () -> Unit) -> Unit)? = null

    /**
     * After an update Android leaves the app closed, and a closed dashcam isn't recording: put up a
     * notification that reopens it with one tap.
     */
    class UpdatedReceiver : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            if (intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
            val prefs = PreferenceManager.getDefaultSharedPreferences(ctx)
            // Only for the app's own updater (not e.g. an install from a computer).
            if (prefs.getString("update_installing", null) == null) return
            prefs.edit().remove("update_installing").apply()
            EventLog.init(ctx); EventLog.log("Update", "now v${BuildConfig.VERSION_NAME}")
            // With "display over other apps" allowed the app puts itself back on screen (a remote
            // camera nobody is touching); the notification stays as the fallback.
            if (canReopen(ctx)) try {
                ctx.startActivity(Intent(ctx, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra(EXTRA_AFTER_UPDATE, true))
                EventLog.log("Update", "reopened after the update")
                return
            } catch (e: Exception) { EventLog.log("Update", "couldn't reopen: ${e.message}") }
            try {
                val nm = ctx.getSystemService(android.app.NotificationManager::class.java)
                nm.createNotificationChannel(android.app.NotificationChannel("updates", "Updates",
                    android.app.NotificationManager.IMPORTANCE_HIGH))
                val open = PendingIntent.getActivity(ctx, 0, Intent(ctx, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
                nm.notify(20, androidx.core.app.NotificationCompat.Builder(ctx, "updates")
                    .setSmallIcon(R.drawable.ic_notification)
                    .setContentTitle("DashCam updated to v${BuildConfig.VERSION_NAME}")
                    .setContentText("Tap to reopen — it isn't recording while closed")
                    .setContentIntent(open).setAutoCancel(true).build())
            } catch (e: Exception) { Log.w(TAG, "update notice", e) }
        }
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
                    EventLog.init(ctx); EventLog.log("Update", "install failed: $msg")
                    PreferenceManager.getDefaultSharedPreferences(ctx).edit().remove("update_installing").apply()
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
