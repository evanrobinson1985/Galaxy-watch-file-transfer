package com.evanrobinson.watchapk.wear

import android.net.Uri
import android.os.PowerManager
import android.os.SystemClock
import android.text.format.Formatter
import android.util.Log
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.ChannelClient
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.Wearable
import com.google.android.gms.wearable.WearableListenerService
import java.io.BufferedInputStream
import java.io.DataInputStream
import java.io.EOFException
import java.io.File
import java.io.IOException
import java.security.MessageDigest

/**
 * Receives files from the phone app, either streamed over the Bluetooth Data Layer
 * (a ChannelClient channel) or fetched over Wi-Fi after a [Protocol.WIFI_OFFER_PATH] message.
 * Callbacks run on a background thread, so blocking I/O here is fine. The transfer keeps going
 * even if the user leaves the app.
 */
class ApkReceiverService : WearableListenerService() {

    override fun onMessageReceived(event: MessageEvent) {
        when (event.path) {
            Protocol.VERSION_REQUEST_PATH -> PhoneReporter.sendVersion(this, event.sourceNodeId)
            Protocol.WIFI_OFFER_PATH -> {
                val offer = try {
                    WifiOffer.fromJson(String(event.data, Charsets.UTF_8))
                } catch (e: Exception) {
                    Log.e(TAG, "Bad Wi-Fi offer", e)
                    return
                }
                receiveOverWifi(event.sourceNodeId, offer)
            }
        }
    }

    // --- Bluetooth (Data Layer channel) ----------------------------------------------------

    override fun onChannelOpened(channel: ChannelClient.Channel) {
        val saveToDownloads = when (channel.path) {
            Protocol.CHANNEL_PATH -> false
            Protocol.FILE_CHANNEL_PATH -> true
            else -> return
        }
        val client = Wearable.getChannelClient(this)
        runReceive(
            nodeId = channel.nodeId,
            onCancel = { client.close(channel) },
            cleanup = { runCatching { Tasks.await(client.close(channel)) } },
        ) {
            val input = Tasks.await(client.getInputStream(channel))
            DataInputStream(BufferedInputStream(input, BUFFER_SIZE)).use { data ->
                if (data.readInt() != Protocol.MAGIC) throw IOException("Unexpected data from phone")
                val version = data.readInt()
                if (version != Protocol.VERSION) {
                    throw IOException("Phone app protocol v$version doesn't match watch app v${Protocol.VERSION}; update both")
                }
                val name = sanitizeFileName(data.readUTF())
                val expectedSize = data.readLong()
                receiveBody(channel.nodeId, name, expectedSize, saveToDownloads, viaWifi = false) { buffer ->
                    data.read(buffer)
                }
            }
        }
    }

    // --- Wi-Fi -----------------------------------------------------------------------------

    private fun receiveOverWifi(nodeId: String, offer: WifiOffer) {
        val name = sanitizeFileName(offer.name)
        val link = WifiLink(this)
        runReceive(nodeId, onCancel = { link.abort() }, cleanup = { link.close() }) {
            TransferState.set(TransferState.Status.Receiving(name, 0, offer.size, viaWifi = true, connecting = true))
            PhoneReporter.progress(this, nodeId, Protocol.PHASE_RECEIVING, 0)
            checkFreeSpace(offer.size, offer.install)

            val network = link.requestNetwork(WIFI_JOIN_TIMEOUT_MS)
            TransferControl.throwIfCancelled()
            TransferState.set(
                TransferState.Status.Receiving(
                    name, 0, offer.size, viaWifi = true, connecting = true, connectingToPhone = true,
                ),
            )
            val socket = link.connect(network, offer.hosts, offer.port)
            socket.getOutputStream().apply {
                write(offer.token)
                flush()
            }

            val input = BufferedInputStream(socket.getInputStream(), BUFFER_SIZE)
            val cipher = offer.newCipher(encrypt = false)
            val mac = offer.newMac()
            var remaining = offer.size
            val plain = ByteArray(BUFFER_SIZE)

            val received = receiveBody(nodeId, name, offer.size, !offer.install, viaWifi = true, verify = {
                // The 32-byte MAC after the data proves nothing was altered or cut short.
                val expected = ByteArray(WifiOffer.MAC_LENGTH)
                var off = 0
                while (off < expected.size) {
                    val n = input.read(expected, off, expected.size - off)
                    if (n < 0) throw EOFException("Connection closed before the end of the file")
                    off += n
                }
                if (!MessageDigest.isEqual(expected, mac.doFinal())) {
                    throw IOException("The file was damaged in transit, please send it again")
                }
            }) { buffer ->
                if (remaining <= 0) return@receiveBody -1
                val n = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                if (n < 0) throw EOFException("Connection closed before the end of the file")
                remaining -= n
                mac.update(buffer, 0, n)
                // CTR keeps lengths equal, so decrypt in place via a scratch buffer.
                val out = cipher.update(buffer, 0, n, plain)
                System.arraycopy(plain, 0, buffer, 0, out)
                out
            }
            // Tell the phone we have everything so it can close its side.
            runCatching { socket.getOutputStream().apply { write(1); flush() } }
            received
        }
    }

    // --- Shared ----------------------------------------------------------------------------

    private sealed interface Received {
        data class ToInstall(val file: File) : Received
        data class ToDownloads(val name: String, val uri: Uri, val mime: String) : Received
    }

    /** Wraps one receive job with wake lock, cancel support and success/failure reporting. */
    private fun runReceive(
        nodeId: String,
        onCancel: () -> Unit,
        cleanup: () -> Unit,
        receive: () -> Received,
    ) {
        val wakeLock = (getSystemService(POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "WatchApk:receive")
        // Large files over Bluetooth can take a long time; released in finally.
        wakeLock.acquire(MAX_WAKE_LOCK_MS)
        TransferControl.begin(this, onCancel)
        try {
            val received = receive()
            Notifications.cancelReceiving(this)
            PhoneReporter.progress(this, nodeId, Protocol.PHASE_RECEIVING, 100)
            onReceived(nodeId, received)
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
            val message = e.message ?: e.javaClass.simpleName
            // The phone recognizes the "Receive failed:" prefix when deciding to fall back to Bluetooth.
            val reason = "Receive failed: $message"
            TransferState.set(TransferState.Status.Failure(message, wifiProblem = e is WifiProblemException))
            PhoneReporter.progress(this, nodeId, Protocol.PHASE_FAILED, 0)
            PhoneReporter.send(this, nodeId, reason)
        } finally {
            TransferControl.end()
            cleanup()
            if (wakeLock.isHeld) wakeLock.release()
        }
    }

    private fun onReceived(nodeId: String, received: Received) {
        when (received) {
            is Received.ToDownloads -> {
                Log.i(TAG, "Saved ${received.name} to Downloads")
                TransferState.set(TransferState.Status.Saved(received.name, received.uri, received.mime))
                PhoneReporter.progress(this, nodeId, Protocol.PHASE_DONE, 100)
                PhoneReporter.send(this, nodeId, "saved ${received.name} to ${ReceivedFiles.FOLDER_LABEL} ✅")
                if (!MainActivity.isVisible) Notifications.showSaved(this, received.name)
            }

            is Received.ToInstall -> {
                val file = received.file
                Log.i(TAG, "Received ${file.name} (${file.length()} bytes)")
                TransferState.set(TransferState.Status.ReadyToInstall(file, nodeId))
                if (MainActivity.isVisible) {
                    // MainActivity picks up ReadyToInstall and starts the install itself.
                    PhoneReporter.send(this, nodeId, "received ${file.name}, installing…")
                } else {
                    Notifications.showReadyToInstall(this, file, nodeId)
                    PhoneReporter.send(this, nodeId, "received ${file.name}. Tap the notification on your watch to install.")
                }
            }
        }
    }

    /**
     * Reads the file body with [read] (returns bytes put in the buffer, or -1 at the end) into
     * private storage (APKs to install) or Downloads, reporting progress as it goes.
     */
    private fun receiveBody(
        nodeId: String,
        name: String,
        expectedSize: Long,
        saveToDownloads: Boolean,
        viaWifi: Boolean,
        verify: () -> Unit = {},
        read: (ByteArray) -> Int,
    ): Received {
        checkFreeSpace(expectedSize, install = !saveToDownloads)
        val startedAt = SystemClock.elapsedRealtime()
        TransferState.set(TransferState.Status.Receiving(name, 0, expectedSize, viaWifi, startedAt = startedAt))
        PhoneReporter.progress(this, nodeId, Protocol.PHASE_RECEIVING, 0)

        // APKs go to private storage for installing; anything else straight into Downloads.
        val file = if (saveToDownloads) null else File(File(filesDir, INCOMING_DIR).apply { mkdirs() }, name)
        val uri = if (saveToDownloads) ReceivedFiles.create(this, name) else null
        var written = 0L
        try {
            (if (uri != null) ReceivedFiles.openOutput(this, uri) else file!!.outputStream()).use { out ->
                val buffer = ByteArray(BUFFER_SIZE)
                var lastPercent = -1
                var lastReportedBytes = 0L
                while (true) {
                    TransferControl.throwIfCancelled()
                    val n = read(buffer)
                    if (n < 0) break
                    out.write(buffer, 0, n)
                    written += n

                    val percent = if (expectedSize > 0) (written * 100 / expectedSize).toInt() else -1
                    val changed = if (expectedSize > 0) percent != lastPercent
                    else written - lastReportedBytes >= UNKNOWN_SIZE_REPORT_STEP
                    if (changed) {
                        lastPercent = percent
                        lastReportedBytes = written
                        TransferState.set(
                            TransferState.Status.Receiving(name, written, expectedSize, viaWifi, startedAt = startedAt),
                        )
                        if (percent >= 0) PhoneReporter.progress(this, nodeId, Protocol.PHASE_RECEIVING, percent)
                        if (!MainActivity.isVisible) Notifications.showReceiving(this, name, percent)
                    }
                }
            }
            if (expectedSize >= 0 && written != expectedSize) {
                throw IOException("Incomplete transfer ($written of $expectedSize bytes)")
            }
            verify()
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

    /** Fails early instead of filling the watch up halfway through a big file. */
    private fun checkFreeSpace(size: Long, install: Boolean) {
        if (size <= 0) return
        val free = filesDir.usableSpace
        // Installing needs room for the received file plus the installer's copy.
        val needed = (if (install) size * 2 else size) + SPACE_RESERVE
        if (free < needed) {
            throw IOException(
                "Not enough space on the watch: needs ${Formatter.formatShortFileSize(this, needed)}, " +
                    "${Formatter.formatShortFileSize(this, free)} free",
            )
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
        private const val SPACE_RESERVE = 50L * 1024 * 1024
        // Galaxy watches can take a while to switch Wi-Fi on while connected over Bluetooth.
        private const val WIFI_JOIN_TIMEOUT_MS = 35_000L
        private const val MAX_WAKE_LOCK_MS = 6 * 60 * 60 * 1000L
        const val INCOMING_DIR = "incoming"
    }
}
