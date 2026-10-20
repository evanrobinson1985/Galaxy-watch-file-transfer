package com.evanrobinson.watchapk.wear

import android.net.Uri
import android.os.PowerManager
import android.util.Log
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.ChannelClient
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.Wearable
import com.google.android.gms.wearable.WearableListenerService
import java.io.BufferedInputStream
import java.io.DataInputStream
import java.io.File
import java.io.IOException

/**
 * Receives APK files streamed from the phone app over the Wearable Data Layer.
 * Callbacks run on a background thread, so blocking I/O here is fine. The transfer
 * keeps going even if the user leaves the app.
 */
class ApkReceiverService : WearableListenerService() {

    override fun onMessageReceived(event: MessageEvent) {
        if (event.path == Protocol.VERSION_REQUEST_PATH) {
            PhoneReporter.sendVersion(this, event.sourceNodeId)
        }
    }

    override fun onChannelOpened(channel: ChannelClient.Channel) {
        val saveToDownloads = when (channel.path) {
            Protocol.CHANNEL_PATH -> false
            Protocol.FILE_CHANNEL_PATH -> true
            else -> return
        }

        val client = Wearable.getChannelClient(this)
        val nodeId = channel.nodeId
        val wakeLock = (getSystemService(POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "WatchApk:receive")
        wakeLock.acquire(10 * 60 * 1000L)
        TransferControl.begin(this, channel)
        try {
            val received = receive(client, channel, saveToDownloads)
            Notifications.cancelReceiving(this)
            PhoneReporter.progress(this, nodeId, Protocol.PHASE_RECEIVING, 100)

            if (received is Received.ToDownloads) {
                Log.i(TAG, "Saved ${received.name} to Downloads")
                TransferState.set(TransferState.Status.Saved(received.name, received.uri, received.mime))
                PhoneReporter.progress(this, nodeId, Protocol.PHASE_DONE, 100)
                PhoneReporter.send(this, nodeId, "saved ${received.name} to ${ReceivedFiles.FOLDER_LABEL} ✅")
                if (!MainActivity.isVisible) Notifications.showSaved(this, received.name)
                return
            }
            val file = (received as Received.ToInstall).file
            Log.i(TAG, "Received ${file.name} (${file.length()} bytes)")
            TransferState.set(TransferState.Status.ReadyToInstall(file, nodeId))

            if (MainActivity.isVisible) {
                // MainActivity picks up ReadyToInstall and starts the install itself.
                PhoneReporter.send(this, nodeId, "received ${file.name}, installing…")
            } else {
                Notifications.showReadyToInstall(this, file, nodeId)
                PhoneReporter.send(this, nodeId, "received ${file.name}. Tap the notification on your watch to install.")
            }
        } catch (e: Exception) {
            Notifications.cancelReceiving(this)
            if (TransferControl.isCancelled) {
                val message = TransferControl.cancelledMessage()
                Log.i(TAG, message)
                TransferState.set(TransferState.Status.Cancelled(message))
                PhoneReporter.progress(this, nodeId, Protocol.PHASE_CANCELLED, 0)
                PhoneReporter.send(this, nodeId, "transfer ${message.lowercase()}")
                return
            }
            Log.e(TAG, "Receive failed", e)
            val reason = "Receive failed: ${e.message ?: e.javaClass.simpleName}"
            Notifications.cancelReceiving(this)
            TransferState.set(TransferState.Status.Failure(reason))
            PhoneReporter.progress(this, nodeId, Protocol.PHASE_FAILED, 0)
            PhoneReporter.send(this, nodeId, reason)
        } finally {
            TransferControl.end()
            runCatching { Tasks.await(client.close(channel)) }
            if (wakeLock.isHeld) wakeLock.release()
        }
    }

    private sealed interface Received {
        data class ToInstall(val file: File) : Received
        data class ToDownloads(val name: String, val uri: Uri, val mime: String) : Received
    }

    private fun receive(client: ChannelClient, channel: ChannelClient.Channel, saveToDownloads: Boolean): Received {
        val input = Tasks.await(client.getInputStream(channel))
        DataInputStream(BufferedInputStream(input, BUFFER_SIZE)).use { data ->
            if (data.readInt() != Protocol.MAGIC) throw IOException("Unexpected data from phone")
            val version = data.readInt()
            if (version != Protocol.VERSION) {
                throw IOException("Phone app protocol v$version doesn't match watch app v${Protocol.VERSION}; update both")
            }
            val name = sanitizeFileName(data.readUTF())
            val expectedSize = data.readLong()

            TransferState.set(TransferState.Status.Receiving(name, 0, expectedSize))
            PhoneReporter.progress(this, channel.nodeId, Protocol.PHASE_RECEIVING, 0)

            // APKs go to private storage for installing; anything else straight into Downloads.
            val file = if (saveToDownloads) null else File(File(filesDir, INCOMING_DIR).apply { mkdirs() }, name)
            val uri = if (saveToDownloads) ReceivedFiles.create(this, name) else null
            var written = 0L
            try {
                (if (uri != null) ReceivedFiles.openOutput(this, uri) else file!!.outputStream()).use { out ->
                    val buffer = ByteArray(BUFFER_SIZE)
                    var total = 0L
                    var lastPercent = -1
                    var lastReportedBytes = 0L
                    while (true) {
                        TransferControl.throwIfCancelled()
                        val read = data.read(buffer)
                        if (read < 0) break
                        out.write(buffer, 0, read)
                        total += read
                        written = total

                        val percent = if (expectedSize > 0) (total * 100 / expectedSize).toInt() else -1
                        val changed = if (expectedSize > 0) percent != lastPercent
                        else total - lastReportedBytes >= UNKNOWN_SIZE_REPORT_STEP
                        if (changed) {
                            lastPercent = percent
                            lastReportedBytes = total
                            TransferState.set(TransferState.Status.Receiving(name, total, expectedSize))
                            if (percent >= 0) {
                                PhoneReporter.progress(this, channel.nodeId, Protocol.PHASE_RECEIVING, percent)
                            }
                            if (!MainActivity.isVisible) Notifications.showReceiving(this, name, percent)
                        }
                    }
                }
                if (expectedSize >= 0 && written != expectedSize) {
                    throw IOException("Incomplete transfer ($written of $expectedSize bytes)")
                }
            } catch (e: Exception) {
                file?.delete()
                uri?.let { ReceivedFiles.delete(this, it) }
                throw e
            }
            if (uri != null) {
                ReceivedFiles.publish(this, uri)
                return Received.ToDownloads(name, uri, ReceivedFiles.mimeFor(name))
            }
            return Received.ToInstall(file!!)
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
        private const val UNKNOWN_SIZE_REPORT_STEP = 512 * 1024L
        const val INCOMING_DIR = "incoming"
    }
}
