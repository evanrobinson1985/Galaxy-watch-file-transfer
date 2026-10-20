package com.evanrobinson.watchapk.wear

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import java.io.File

/**
 * Runs in the freshly installed version after the phone pushed a watch app update. The old
 * process was killed mid-install, so this is where we clean up and tell the phone it worked.
 */
class SelfUpdateReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return

        val pending = goAsync()
        Thread {
            try {
                File(context.filesDir, ApkReceiverService.INCOMING_DIR).listFiles()?.forEach { it.delete() }
                Notifications.showUpdated(context, BuildConfig.VERSION_NAME)
                PhoneReporter.announceUpdate(context)
            } finally {
                pending.finish()
            }
        }.start()
    }
}
