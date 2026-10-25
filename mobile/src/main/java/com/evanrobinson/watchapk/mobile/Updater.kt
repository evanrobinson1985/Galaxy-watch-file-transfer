package com.evanrobinson.watchapk.mobile

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import android.widget.Toast
import androidx.core.content.IntentCompat
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Checks GitHub Releases for a newer build of the app (published by the CI workflow) and
 * installs it over this one. The watch app is updated afterwards from the copy bundled inside
 * the new phone app.
 */
object Updater {

    private const val REPO = "evanrobinson1985/Galaxy-watch-file-transfer"
    private const val LATEST_URL = "https://api.github.com/repos/$REPO/releases/latest"
    private const val PHONE_ASSET = "WatchApkInstaller-phone.apk"

    private const val PREFS = "updater"
    private const val KEY_LAST_CHECK = "last_check"
    private const val KEY_REMIND_AFTER = "remind_after"
    private const val KEY_WATCH_REMIND_AFTER = "watch_remind_after"

    private const val CHECK_INTERVAL_MS = 6 * 60 * 60 * 1000L
    const val REMIND_LATER_MS = 24 * 60 * 60 * 1000L

    data class Release(
        val versionCode: Int,
        val versionName: String,
        val notes: String,
        val apkUrl: String,
        val apkSize: Long,
    )

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Automatic checks run at most every few hours, and not while the user said "later". */
    fun shouldAutoCheck(context: Context): Boolean {
        val p = prefs(context)
        val now = System.currentTimeMillis()
        return now - p.getLong(KEY_LAST_CHECK, 0) > CHECK_INTERVAL_MS && now > p.getLong(KEY_REMIND_AFTER, 0)
    }

    fun remindLater(context: Context) {
        prefs(context).edit().putLong(KEY_REMIND_AFTER, System.currentTimeMillis() + REMIND_LATER_MS).apply()
    }

    fun watchSnoozed(context: Context): Boolean =
        System.currentTimeMillis() < prefs(context).getLong(KEY_WATCH_REMIND_AFTER, 0)

    fun remindWatchLater(context: Context) {
        prefs(context).edit().putLong(KEY_WATCH_REMIND_AFTER, System.currentTimeMillis() + REMIND_LATER_MS).apply()
    }

    /** Blocking. Returns the latest published release, or null if it has no phone APK. */
    fun fetchLatest(context: Context): Release? {
        val connection = (URL(LATEST_URL).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 15_000
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("User-Agent", "WatchApkInstaller")
        }
        try {
            if (connection.responseCode == 404) return null // nothing published yet
            if (connection.responseCode != 200) throw IOException("GitHub answered ${connection.responseCode}")
            val json = JSONObject(connection.inputStream.bufferedReader().readText())
            prefs(context).edit().putLong(KEY_LAST_CHECK, System.currentTimeMillis()).apply()

            val notes = json.optString("body")
            val code = Regex("versionCode:\\s*(\\d+)").find(notes)?.groupValues?.get(1)?.toIntOrNull() ?: return null
            val assets = json.getJSONArray("assets")
            for (i in 0 until assets.length()) {
                val asset = assets.getJSONObject(i)
                if (asset.getString("name") == PHONE_ASSET) {
                    return Release(
                        versionCode = code,
                        versionName = json.optString("tag_name").removePrefix("v"),
                        notes = notes.lines().drop(1).joinToString("\n").trim(),
                        apkUrl = asset.getString("browser_download_url"),
                        apkSize = asset.optLong("size", -1),
                    )
                }
            }
            return null
        } finally {
            connection.disconnect()
        }
    }

    /** Blocking download; [onProgress] gets (bytesDone, total). Throws if cancelled via [isCancelled]. */
    fun download(release: Release, dest: File, isCancelled: () -> Boolean, onProgress: (Long, Long) -> Unit) {
        dest.parentFile?.mkdirs()
        val connection = (URL(release.apkUrl).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 30_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "WatchApkInstaller")
        }
        try {
            if (connection.responseCode != 200) throw IOException("Download failed (${connection.responseCode})")
            val total = connection.contentLengthLong.takeIf { it > 0 } ?: release.apkSize
            connection.inputStream.use { input ->
                dest.outputStream().use { out ->
                    val buffer = ByteArray(64 * 1024)
                    var done = 0L
                    while (true) {
                        if (isCancelled()) throw IOException("Cancelled")
                        val n = input.read(buffer)
                        if (n < 0) break
                        out.write(buffer, 0, n)
                        done += n
                        onProgress(done, total)
                    }
                }
            }
            if (release.apkSize > 0 && dest.length() != release.apkSize) {
                throw IOException("Download incomplete, please try again")
            }
        } catch (e: Exception) {
            dest.delete()
            throw e
        } finally {
            connection.disconnect()
        }
    }

    /** Hands the APK to Android's installer; the result arrives in [UpdateResultReceiver]. Blocking. */
    fun install(context: Context, apk: File) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(context.packageName)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
            }
        }
        val sessionId = installer.createSession(params)
        try {
            installer.openSession(sessionId).use { session ->
                apk.inputStream().use { input ->
                    session.openWrite("base.apk", 0, apk.length()).use { out ->
                        input.copyTo(out, 64 * 1024)
                        session.fsync(out)
                    }
                }
                val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                    (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0)
                val callback = PendingIntent.getBroadcast(
                    context,
                    sessionId,
                    Intent(context, UpdateResultReceiver::class.java).setPackage(context.packageName),
                    flags,
                )
                session.commit(callback.intentSender)
            }
        } catch (e: Exception) {
            runCatching { installer.abandonSession(sessionId) }
            throw e
        }
    }
}

/** Shows Android's install confirmation for the phone update, or reports a failure. */
class UpdateResultReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                IntentCompat.getParcelableExtra(intent, Intent.EXTRA_INTENT, Intent::class.java)?.let {
                    context.startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }
            }
            PackageInstaller.STATUS_SUCCESS -> Unit // This process is replaced by the new version.
            else -> {
                val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
                Toast.makeText(
                    context,
                    context.getString(R.string.update_failed, message ?: "status $status"),
                    Toast.LENGTH_LONG,
                ).show()
            }
        }
    }
}
