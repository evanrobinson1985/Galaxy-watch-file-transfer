package com.evanrobinson.watchapk.mobile

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.text.format.Formatter
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.progressindicator.LinearProgressIndicator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/**
 * GitHub (sideload) edition: updates itself from GitHub Releases, bundles the watch app so the
 * phone can update it over the air, and includes the Secure Folder bridge.
 */
object Edition {
    const val IS_PLAY = false

    private const val WATCH_APK_ASSET = "watch-app.apk"

    // --- Secure Folder ---------------------------------------------------------------------

    /** Inside Secure Folder the watch can't be reached; hand the launch to the relay screen. */
    fun redirectSecondaryProfile(activity: Activity): Boolean {
        if (!SecureFolderRelay.isInSecondaryProfile(activity)) return false
        activity.startActivity(
            Intent(activity.intent).setClass(activity, RelayActivity::class.java)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
        )
        activity.finish()
        return true
    }

    fun setUpSecureFolderBridge(activity: AppCompatActivity, switch: MaterialSwitch, hint: View, status: TextView) {
        fun startIfEnabled() {
            if (RelayService.isEnabled(activity)) runCatching { RelayService.start(activity) }
        }

        fun render(state: RelayService.Status) {
            if (!switch.isChecked) {
                status.visibility = View.GONE
                return
            }
            status.visibility = View.VISIBLE
            status.text = when (state) {
                is RelayService.Status.Listening ->
                    activity.getString(R.string.relay_status_listening, state.addresses.joinToString())
                RelayService.Status.Starting -> activity.getString(R.string.relay_status_starting)
                RelayService.Status.Off -> activity.getString(R.string.relay_status_off)
                is RelayService.Status.Error -> activity.getString(R.string.relay_status_error, state.message)
            }
        }

        val requestNotifications =
            activity.registerForActivityResult(ActivityResultContracts.RequestPermission()) { startIfEnabled() }

        switch.isChecked = RelayService.isEnabled(activity)
        switch.setOnCheckedChangeListener { _, checked ->
            RelayService.setEnabled(activity, checked)
            render(RelayService.status.value)
            if (!checked) {
                RelayService.stop(activity)
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                activity.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
            ) {
                // Needed for the "Tap to send to watch" notification.
                requestNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
            } else {
                startIfEnabled()
            }
        }
        activity.lifecycleScope.launch {
            activity.repeatOnLifecycle(Lifecycle.State.STARTED) {
                RelayService.status.collect { render(it) }
            }
        }
        startIfEnabled()
    }

    fun relayedFiles(context: Context): List<File> = RelayService.batchFiles(context)

    // --- Watch app bundled in this phone app ------------------------------------------------

    fun bundledWatchApp(context: Context): BundledApk? {
        val size = try {
            context.assets.openFd(WATCH_APK_ASSET).use { it.length }
        } catch (e: IOException) {
            -1L
        }
        return BundledApk("WatchApkInstaller-watch-${BuildConfig.WATCH_VERSION_NAME}.apk", size) {
            context.assets.open(WATCH_APK_ASSET)
        }
    }

    // --- Updates from GitHub Releases -------------------------------------------------------

    fun setUpAppUpdates(activity: AppCompatActivity, button: MaterialButton, isBusy: () -> Boolean) {
        val controller = UpdateController(activity, isBusy)
        button.text = activity.getString(R.string.check_updates, BuildConfig.VERSION_NAME)
        button.setOnClickListener { controller.check(manual = true) }
        activity.lifecycle.addObserver(controller)
        if (Updater.shouldAutoCheck(activity)) controller.check(manual = false)
    }

    private class UpdateController(
        private val activity: AppCompatActivity,
        private val isBusy: () -> Boolean,
    ) : DefaultLifecycleObserver {

        /** Waiting for the user to allow installs from this app before updating it. */
        private var pendingRelease: Updater.Release? = null

        override fun onResume(owner: LifecycleOwner) {
            // Back from "Install unknown apps" settings: carry on if it's now allowed.
            val release = pendingRelease
            if (release != null && activity.packageManager.canRequestPackageInstalls()) startUpdate(release)
        }

        fun check(manual: Boolean) {
            activity.lifecycleScope.launch {
                val release = try {
                    withContext(Dispatchers.IO) { Updater.fetchLatest(activity.applicationContext) }
                } catch (e: Exception) {
                    if (manual) toast(activity.getString(R.string.update_check_failed, e.message ?: e.javaClass.simpleName))
                    return@launch
                }
                if (release != null && release.versionCode > BuildConfig.VERSION_CODE) {
                    showDialog(release)
                } else if (manual) {
                    toast(activity.getString(R.string.up_to_date, BuildConfig.VERSION_NAME))
                }
            }
        }

        private fun showDialog(release: Updater.Release) {
            // Don't interrupt a transfer; the next check will ask again.
            if (activity.isFinishing || isBusy()) return
            val notes = release.notes.take(600).trim()
            MaterialAlertDialogBuilder(activity)
                .setTitle(R.string.update_title)
                .setMessage(
                    activity.getString(R.string.update_message, release.versionName, BuildConfig.VERSION_NAME) +
                        if (notes.isNotEmpty()) "\n\n$notes" else "",
                )
                .setPositiveButton(R.string.update_now) { _, _ -> startUpdate(release) }
                .setNegativeButton(R.string.remind_later) { _, _ -> Updater.remindLater(activity) }
                .setCancelable(false)
                .show()
        }

        private fun startUpdate(release: Updater.Release) {
            if (!activity.packageManager.canRequestPackageInstalls()) {
                pendingRelease = release
                MaterialAlertDialogBuilder(activity)
                    .setTitle(R.string.update_title)
                    .setMessage(R.string.update_allow_installs)
                    .setPositiveButton(R.string.open_settings) { _, _ ->
                        runCatching {
                            activity.startActivity(
                                Intent(
                                    Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                                    Uri.parse("package:${activity.packageName}"),
                                ),
                            )
                        }
                    }
                    .setNegativeButton(android.R.string.cancel) { _, _ -> pendingRelease = null }
                    .show()
                return
            }
            pendingRelease = null

            val view = activity.layoutInflater.inflate(R.layout.dialog_update_progress, null)
            val bar = view.findViewById<LinearProgressIndicator>(R.id.updateProgress)
            val label = view.findViewById<TextView>(R.id.updateProgressText)
            var cancelled = false
            val dialog = MaterialAlertDialogBuilder(activity)
                .setTitle(activity.getString(R.string.update_downloading, release.versionName))
                .setView(view)
                .setCancelable(false)
                .setNegativeButton(android.R.string.cancel) { _, _ -> cancelled = true }
                .show()

            activity.lifecycleScope.launch {
                try {
                    val apk = File(activity.cacheDir, "update/WatchApkInstaller-phone.apk")
                    withContext(Dispatchers.IO) {
                        var lastPercent = -1
                        Updater.download(release, apk, { cancelled }) { done, total ->
                            val percent = if (total > 0) (done * 100 / total).toInt() else 0
                            if (percent != lastPercent) {
                                lastPercent = percent
                                activity.runOnUiThread {
                                    bar.setProgressCompat(percent, true)
                                    label.text = activity.getString(
                                        R.string.update_progress,
                                        Formatter.formatShortFileSize(activity, done),
                                        Formatter.formatShortFileSize(activity, total.coerceAtLeast(done)),
                                    )
                                }
                            }
                        }
                    }
                    label.text = activity.getString(R.string.update_installing)
                    withContext(Dispatchers.IO) { Updater.install(activity.applicationContext, apk) }
                    dialog.dismiss()
                } catch (e: Exception) {
                    dialog.dismiss()
                    if (!cancelled) toast(activity.getString(R.string.update_failed, e.message ?: e.javaClass.simpleName))
                }
            }
        }

        private fun toast(text: String) = Toast.makeText(activity, text, Toast.LENGTH_LONG).show()
    }
}
