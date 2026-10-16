package com.evanrobinson.watchapk.wear

import android.app.PendingIntent
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import java.io.File
import java.io.IOException
import java.util.zip.ZipException
import java.util.zip.ZipFile

/**
 * Installs a received file with PackageInstaller. Supports a plain .apk as well as split
 * bundles (.apks / .xapk = a zip of several .apk files), which are installed as one session.
 */
object ApkInstaller {

    fun canInstall(context: Context): Boolean = context.packageManager.canRequestPackageInstalls()

    /** Tries to open the "Install unknown apps" screen. Returns false if the watch has none. */
    fun openInstallPermissionSettings(context: Context): Boolean {
        val intent = Intent(
            Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
            Uri.parse("package:${context.packageName}"),
        )
        return try {
            context.startActivity(intent)
            true
        } catch (e: ActivityNotFoundException) {
            false
        } catch (e: SecurityException) {
            false
        }
    }

    /** Blocking; call off the main thread. Result arrives in [InstallResultReceiver]. */
    fun install(context: Context, file: File, nodeId: String?) {
        val apkEntries = splitApkEntries(file)
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setInstallReason(PackageManager.INSTALL_REASON_USER)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                // Lets re-installs of apps we installed before skip the prompt where allowed.
                setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
            }
        }

        val sessionId = installer.createSession(params)
        try {
            installer.openSession(sessionId).use { session ->
                if (apkEntries == null) {
                    file.inputStream().use { input ->
                        session.openWrite("base.apk", 0, file.length()).use { out ->
                            input.copyTo(out)
                            session.fsync(out)
                        }
                    }
                } else {
                    ZipFile(file).use { zip ->
                        apkEntries.forEachIndexed { index, entryName ->
                            val entry = zip.getEntry(entryName)
                            val splitName = "${index}_" + entryName.substringAfterLast('/')
                            zip.getInputStream(entry).use { input ->
                                session.openWrite(splitName, 0, entry.size).use { out ->
                                    input.copyTo(out)
                                    session.fsync(out)
                                }
                            }
                        }
                    }
                }

                val callback = Intent(context, InstallResultReceiver::class.java)
                    .setPackage(context.packageName)
                    .putExtra(InstallResultReceiver.EXTRA_FILE_PATH, file.absolutePath)
                    .putExtra(InstallResultReceiver.EXTRA_NODE_ID, nodeId)
                // Must be mutable: the system adds the status extras to it.
                val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                    (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0)
                val pendingIntent = PendingIntent.getBroadcast(context, sessionId, callback, flags)
                session.commit(pendingIntent.intentSender)
            }
        } catch (e: Exception) {
            runCatching { installer.abandonSession(sessionId) }
            throw e
        }
    }

    /**
     * Returns null for a regular APK, or the list of .apk entries for a split bundle.
     */
    private fun splitApkEntries(file: File): List<String>? {
        try {
            ZipFile(file).use { zip ->
                if (zip.getEntry("AndroidManifest.xml") != null) return null
                val apks = zip.entries().asSequence()
                    .filter { !it.isDirectory && it.name.endsWith(".apk", ignoreCase = true) }
                    .map { it.name }
                    .toList()
                if (apks.isEmpty()) throw IOException("${file.name} is not an APK or APK bundle")
                return apks
            }
        } catch (e: ZipException) {
            throw IOException("${file.name} is not a valid APK (${e.message})", e)
        }
    }
}
