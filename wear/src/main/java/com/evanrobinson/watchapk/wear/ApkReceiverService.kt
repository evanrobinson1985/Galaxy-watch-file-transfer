package com.evanrobinson.watchapk.wear

import android.content.Intent
import android.os.PowerManager
import android.util.Log
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.ChannelClient
import com.google.android.gms.wearable.Wearable
import com.google.android.gms.wearable.WearableListenerService
import java.io.BufferedInputStream
import java.io.DataInputStream
import java.io.File
import java.io.IOException

/**
 * Receives APK files streamed from the phone app over the Wearable Data Layer.
 * Callbacks run on a background thread, so blocking I/O here is fine.
 */
class ApkReceiverService : WearableListenerService() {

    override fun onChannelOpened(channel: ChannelClient.Channel) {
        if (channel.path != Protocol.CHANNEL_PATH) return

        val client = Wearable.getChannelClient(this)
        val wakeLock = (getSystemService(POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "WatchApk:receive")
        wakeLock.acquire(10 * 60 * 1000L)
        try {
            val file = receive(client, channel)
            Log.i(TAG, "Received ${file.name} (${file.length()} bytes)")

            if (MainActivity.isVisible) {
                // The user is looking at the app, so jump straight to the install screen.
                startActivity(InstallActivity.intent(this, file, channel.nodeId).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                PhoneReporter.send(this, channel.nodeId, "received ${file.name}, confirm the install on your watch.")
            } else {
                Notifications.showReadyToInstall(this, file, channel.nodeId)
                PhoneReporter.send(this, channel.nodeId, "received ${file.name}. Tap the notification on your watch to install.")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Receive failed", e)
            PhoneReporter.send(this, channel.nodeId, "receive failed: ${e.message ?: e.javaClass.simpleName}")
        } finally {
            runCatching { Tasks.await(client.close(channel)) }
            if (wakeLock.isHeld) wakeLock.release()
        }
    }

    private fun receive(client: ChannelClient, channel: ChannelClient.Channel): File {
        val input = Tasks.await(client.getInputStream(channel))
        DataInputStream(BufferedInputStream(input, BUFFER_SIZE)).use { data ->
            if (data.readInt() != Protocol.MAGIC) throw IOException("Unexpected data from phone")
            val version = data.readInt()
            if (version != Protocol.VERSION) {
                throw IOException("Phone app protocol v$version doesn't match watch app v${Protocol.VERSION}; update both")
            }
            val name = sanitizeFileName(data.readUTF())
            val expectedSize = data.readLong()

            val dir = File(filesDir, INCOMING_DIR).apply { mkdirs() }
            val file = File(dir, name)
            try {
                file.outputStream().use { data.copyTo(it, BUFFER_SIZE) }
                if (expectedSize >= 0 && file.length() != expectedSize) {
                    throw IOException("Incomplete transfer (${file.length()} of $expectedSize bytes)")
                }
            } catch (e: Exception) {
                file.delete()
                throw e
            }
            return file
        }
    }

    private fun sanitizeFileName(raw: String): String {
        val cleaned = raw.substringAfterLast('/').substringAfterLast('\\')
            .replace(Regex("[^A-Za-z0-9._ -]"), "_")
            .trim()
            .trimStart('.')
        return cleaned.ifEmpty { "app.apk" }
    }

    companion object {
        private const val TAG = "ApkReceiverService"
        private const val BUFFER_SIZE = 64 * 1024
        const val INCOMING_DIR = "incoming"
    }
}
