package com.evanrobinson.watchapk.wear

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.util.Log
import android.widget.Toast
import androidx.core.content.IntentCompat
import java.io.File

class InstallResultReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        val nodeId = intent.getStringExtra(EXTRA_NODE_ID)
        val file = intent.getStringExtra(EXTRA_FILE_PATH)?.let(::File)

        when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirm = IntentCompat.getParcelableExtra(intent, Intent.EXTRA_INTENT, Intent::class.java)
                if (confirm == null) {
                    fail(context, nodeId, file, "Installer didn't provide a confirmation screen")
                    return
                }
                try {
                    // InstallActivity is in the foreground, so we're allowed to launch this.
                    context.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                } catch (e: Exception) {
                    fail(context, nodeId, file, "Couldn't show install confirmation: ${e.message}")
                }
            }

            PackageInstaller.STATUS_SUCCESS -> {
                val pkg = intent.getStringExtra(PackageInstaller.EXTRA_PACKAGE_NAME)
                file?.delete()
                InstallEvents.post(InstallEvents.State.Success(pkg))
                Toast.makeText(context, "Installed", Toast.LENGTH_SHORT).show()
                PhoneReporter.send(context, nodeId, "installed ${pkg ?: file?.name ?: "app"} ✅")
            }

            else -> {
                val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
                fail(context, nodeId, file, describe(status) + (message?.let { " ($it)" } ?: ""))
            }
        }
    }

    private fun fail(context: Context, nodeId: String?, file: File?, reason: String) {
        Log.w(TAG, "Install failed: $reason")
        file?.delete()
        InstallEvents.post(InstallEvents.State.Failure(reason))
        PhoneReporter.send(context, nodeId, "install failed: $reason")
    }

    private fun describe(status: Int): String = when (status) {
        PackageInstaller.STATUS_FAILURE_ABORTED -> "Install cancelled"
        PackageInstaller.STATUS_FAILURE_BLOCKED -> "Install blocked by the system"
        PackageInstaller.STATUS_FAILURE_CONFLICT ->
            "Conflicts with an installed app (different signature? uninstall the old one first)"
        PackageInstaller.STATUS_FAILURE_INCOMPATIBLE -> "App isn't compatible with this watch"
        PackageInstaller.STATUS_FAILURE_INVALID -> "Invalid or corrupt APK"
        PackageInstaller.STATUS_FAILURE_STORAGE -> "Not enough storage on the watch"
        else -> "Install failed"
    }

    companion object {
        private const val TAG = "InstallResultReceiver"
        const val EXTRA_FILE_PATH = "file_path"
        const val EXTRA_NODE_ID = "node_id"
    }
}
