package com.evanrobinson.watchapk.wear

import android.app.PendingIntent
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipException
import java.util.zip.ZipFile

/**
 * Installs a received file with PackageInstaller. Supports a plain .apk as well as split
 * bundles (.apks / .xapk = a zip of several .apk files), which are installed as one session.
 *
 * Progress: copying into the session covers 0–80%, the system's own install steps 80–100%
 * (the same split PackageInstaller uses for setStagingProgress).
 */
object ApkInstaller {

    private const val STAGING_SHARE = 0.8f

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

    /** Blocking; call off the main thread. The result arrives in [InstallResultReceiver]. */
    fun install(context: Context, file: File, nodeId: String?) {
        val name = file.name
        val apkEntries = splitApkEntries(file)
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setInstallReason(PackageManager.INSTALL_REASON_USER)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                // Lets re-installs of apps we installed before skip the prompt where allowed.
                setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
            }
        }

        publish(context, nodeId, name, 0f)
        val sessionId = installer.createSession(params)
        var callback: PackageInstaller.SessionCallback? = null
        try {
            installer.openSession(sessionId).use { session ->
                val totalBytes = if (apkEntries == null) {
                    file.length()
                } else {
                    ZipFile(file).use { zip -> apkEntries.sumOf { zip.getEntry(it).size.coerceAtLeast(0) } }
                }
                var written = 0L
                val onBytes: (Int) -> Unit = { count ->
                    written += count
                    if (totalBytes > 0) {
                        val fraction = (written.toFloat() / totalBytes).coerceIn(0f, 1f)
                        session.setStagingProgress(fraction)
                        publish(context, nodeId, name, fraction * STAGING_SHARE)
                    }
                }

                if (apkEntries == null) {
                    file.inputStream().use { input ->
                        session.openWrite("base.apk", 0, file.length()).use { out ->
                            copy(input, out, onBytes)
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
                                    copy(input, out, onBytes)
                                    session.fsync(out)
                                }
                            }
                        }
                    }
                }
                publish(context, nodeId, name, STAGING_SHARE)

                // Follow the system's progress for the rest of the install.
                callback = object : PackageInstaller.SessionCallback() {
                    override fun onCreated(id: Int) {}
                    override fun onBadgingChanged(id: Int) {}
                    override fun onActiveChanged(id: Int, active: Boolean) {}
                    override fun onProgressChanged(id: Int, progress: Float) {
                        if (id == sessionId) publish(context, nodeId, name, progress, userConfirmed = true)
                    }
                    override fun onFinished(id: Int, success: Boolean) {
                        if (id == sessionId) installer.unregisterSessionCallback(this)
                    }
                }.also { installer.registerSessionCallback(it, Handler(Looper.getMainLooper())) }

                val resultIntent = Intent(context, InstallResultReceiver::class.java)
                    .setPackage(context.packageName)
                    .putExtra(InstallResultReceiver.EXTRA_FILE_PATH, file.absolutePath)
                    .putExtra(InstallResultReceiver.EXTRA_NODE_ID, nodeId)
                // Must be mutable: the system adds the status extras to it.
                val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                    (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0)
                val pendingIntent = PendingIntent.getBroadcast(context, sessionId, resultIntent, flags)
                session.commit(pendingIntent.intentSender)
            }
        } catch (e: Exception) {
            callback?.let { runCatching { installer.unregisterSessionCallback(it) } }
            runCatching { installer.abandonSession(sessionId) }
            throw e
        }
    }

    /**
     * Updates [TransferState] and the phone with install progress. Never goes backwards, and
     * does nothing once the install has already finished or failed.
     */
    @Synchronized
    fun publish(context: Context, nodeId: String?, name: String, fraction: Float, userConfirmed: Boolean = false) {
        val current = TransferState.status.value
        val previous = (current as? TransferState.Status.Installing)
        if (current !is TransferState.Status.Installing && current !is TransferState.Status.ReadyToInstall) return

        val value = maxOf(previous?.fraction ?: 0f, fraction.coerceIn(0f, 1f))
        // Progress moving past the staging part means the user already confirmed.
        val waiting = (previous?.waitingForUser ?: false) && !(userConfirmed && value > (previous?.fraction ?: 0f))
        if (previous != null && previous.waitingForUser == waiting &&
            (value * 100).toInt() == (previous.fraction * 100).toInt()
        ) {
            return
        }
        TransferState.set(TransferState.Status.Installing(name, value, waiting))
        val phase = if (waiting) Protocol.PHASE_WAITING_FOR_USER else Protocol.PHASE_INSTALLING
        PhoneReporter.progress(context, nodeId, phase, (value * 100).toInt())
    }

    private fun copy(input: InputStream, out: OutputStream, onBytes: (Int) -> Unit) {
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            out.write(buffer, 0, read)
            onBytes(read)
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
