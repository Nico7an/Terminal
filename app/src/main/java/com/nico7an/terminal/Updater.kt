package com.nico7an.terminal

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
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * In-app updates from the GitHub releases of the public repository.
 * Same package name + same signing key = Android keeps all app data (servers, keys) across the update.
 */
object Updater {

    data class Release(val version: String, val downloadUrl: String)

    private const val TAG = "Updater"
    private const val CHECK_INTERVAL_MS = 15 * 60 * 1000L

    private val main = Handler(Looper.getMainLooper())
    private var lastCheck = 0L
    private var cached: Release? = null

    /** Calls back on the main thread with a newer release, or null. Network is hit at most every 15 min. */
    fun check(callback: (Release?) -> Unit) {
        if (System.currentTimeMillis() - lastCheck < CHECK_INTERVAL_MS) return callback(cached)
        Thread({
            val release = runCatching { fetchLatest() }
                .onFailure { Log.w(TAG, "Update check failed: $it") }
                .getOrNull()
            if (release != null || cached == null) lastCheck = System.currentTimeMillis()
            cached = release?.takeIf { isNewer(it.version, BuildConfig.VERSION_NAME) }
            main.post { callback(cached) }
        }, "UpdateCheck").start()
    }

    private fun fetchLatest(): Release? {
        val conn = http("https://api.github.com/repos/${BuildConfig.UPDATE_REPO}/releases/latest")
        conn.setRequestProperty("Accept", "application/vnd.github+json")
        conn.inputStream.use { stream ->
            val json = JSONObject(stream.readBytes().toString(Charsets.UTF_8))
            val assets = json.getJSONArray("assets")
            for (i in 0 until assets.length()) {
                val asset = assets.getJSONObject(i)
                if (asset.getString("name").endsWith(".apk")) {
                    return Release(json.getString("tag_name").removePrefix("v"), asset.getString("browser_download_url"))
                }
            }
        }
        return null
    }

    private fun http(url: String) = (URL(url).openConnection() as HttpURLConnection).apply {
        connectTimeout = 10_000
        readTimeout = 30_000
    }

    /** "1.10.0" > "1.9.3"; a dev build is older than any release. */
    fun isNewer(remote: String, local: String): Boolean {
        fun parts(v: String) = v.substringBefore('-').split('.').map { it.toIntOrNull() ?: 0 }
        val r = parts(remote)
        val l = parts(local)
        for (i in 0 until maxOf(r.size, l.size)) {
            val a = r.getOrElse(i) { 0 }
            val b = l.getOrElse(i) { 0 }
            if (a != b) return a > b
        }
        return false
    }

    /** Progress 0..100 on the main thread, -1 on failure. */
    fun downloadAndInstall(activity: Activity, release: Release, progress: (Int) -> Unit) {
        if (!activity.packageManager.canRequestPackageInstalls()) {
            Toast.makeText(activity, "Autorise Terminal à installer des applis, puis réessaie", Toast.LENGTH_LONG).show()
            activity.startActivity(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${activity.packageName}"))
            )
            progress(-1)
            return
        }
        val app = activity.applicationContext
        Thread({
            try {
                val apk = download(app, release) { p -> main.post { progress(p) } }
                main.post { progress(100) }
                install(app, apk)
            } catch (e: Exception) {
                Log.w(TAG, "Update failed", e)
                main.post { progress(-1) }
            }
        }, "UpdateDownload").start()
    }

    private fun download(context: Context, release: Release, progress: (Int) -> Unit): File {
        // github.com redirects to its storage host, followed automatically (https to https).
        val stream = http(release.downloadUrl)
        val total = stream.contentLengthLong
        val file = File(context.cacheDir, "update.apk")
        stream.inputStream.use { input ->
            file.outputStream().use { output ->
                val buffer = ByteArray(64 * 1024)
                var done = 0L
                var last = -1
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    output.write(buffer, 0, n)
                    done += n
                    if (total > 0) {
                        val p = (done * 100 / total).toInt().coerceAtMost(99)
                        if (p != last) { last = p; progress(p) }
                    }
                }
            }
        }
        return file
    }

    private fun install(context: Context, apk: File) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(context.packageName)
            if (Build.VERSION.SDK_INT >= 31) setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        }
        val id = installer.createSession(params)
        installer.openSession(id).use { session ->
            session.openWrite("base.apk", 0, apk.length()).use { out ->
                apk.inputStream().use { it.copyTo(out, 64 * 1024) }
                session.fsync(out)
            }
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
            val intent = PendingIntent.getBroadcast(context, id, Intent(context, UpdateReceiver::class.java), flags)
            session.commit(intent.intentSender)
        }
    }
}

/** Receives the installer status: forwards the confirmation screen to the user, reports failures. */
class UpdateReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION")
                val confirm = if (Build.VERSION.SDK_INT >= 33) {
                    intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                } else {
                    intent.getParcelableExtra(Intent.EXTRA_INTENT)
                }
                confirm?.let { context.startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            }
            PackageInstaller.STATUS_SUCCESS -> Unit
            else -> {
                val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
                Toast.makeText(context, "Mise à jour échouée : $message", Toast.LENGTH_LONG).show()
            }
        }
    }
}
