package com.evanrobinson.watchapk.mobile

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.text.format.Formatter
import android.util.Log
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.concurrent.futures.await
import androidx.core.content.IntentCompat
import androidx.lifecycle.lifecycleScope
import androidx.wear.remote.interactions.RemoteActivityHelper
import com.google.android.gms.wearable.CapabilityClient
import com.google.android.gms.wearable.ChannelClient
import com.google.android.gms.wearable.MessageClient
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.Node
import com.google.android.gms.wearable.Wearable
import com.google.android.material.button.MaterialButton
import com.google.android.material.progressindicator.LinearProgressIndicator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.BufferedOutputStream
import java.io.DataOutputStream
import java.io.FileNotFoundException

class MainActivity : AppCompatActivity(), MessageClient.OnMessageReceivedListener {

    private lateinit var watchStatus: TextView
    private lateinit var fileText: TextView
    private lateinit var statusText: TextView
    private lateinit var findWatchButton: MaterialButton
    private lateinit var pickButton: MaterialButton
    private lateinit var sendButton: MaterialButton
    private lateinit var progressGroup: View
    private lateinit var transferProgress: LinearProgressIndicator
    private lateinit var transferText: TextView
    private lateinit var installProgress: LinearProgressIndicator
    private lateinit var installText: TextView

    private var selectedUri: Uri? = null
    private var selectedName: String = "app.apk"
    private var selectedSize: Long = -1
    private var targetNode: Node? = null
    private var sendJob: Job? = null

    /** True while we are streaming the file. */
    private var sending = false

    /** True while the watch reports it is still receiving or installing. */
    private var watchBusy = false
    private var watchBusyTimeout: Job? = null

    /** Once the watch reports receive progress, its numbers drive the transfer bar. */
    private var watchReportsReceive = false

    private val remoteActivityHelper by lazy { RemoteActivityHelper(this) }

    private val pickApk = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) setSelectedFile(uri)
    }

    private val backBlocker = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() {
            Toast.makeText(this@MainActivity, R.string.busy_toast, Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        watchStatus = findViewById(R.id.watchStatus)
        fileText = findViewById(R.id.fileText)
        statusText = findViewById(R.id.statusText)
        findWatchButton = findViewById(R.id.findWatchButton)
        pickButton = findViewById(R.id.pickButton)
        sendButton = findViewById(R.id.sendButton)
        progressGroup = findViewById(R.id.progressGroup)
        transferProgress = findViewById(R.id.transferProgress)
        transferText = findViewById(R.id.transferText)
        installProgress = findViewById(R.id.installProgress)
        installText = findViewById(R.id.installText)

        onBackPressedDispatcher.addCallback(this, backBlocker)
        findWatchButton.setOnClickListener { findWatch() }
        // APK bundles often come through with generic MIME types, so accept anything.
        pickButton.setOnClickListener { pickApk.launch(arrayOf("*/*")) }
        sendButton.setOnClickListener { sendToWatch() }

        // Listen for the whole lifetime of the screen so progress isn't missed while paused.
        Wearable.getMessageClient(this).addListener(this)

        handleIncomingIntent(intent)
        findWatch()
    }

    override fun onDestroy() {
        Wearable.getMessageClient(this).removeListener(this)
        super.onDestroy()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (!sending) handleIncomingIntent(intent)
    }

    /** Status text and progress updates sent back from the watch app. */
    override fun onMessageReceived(event: MessageEvent) {
        when (event.path) {
            Protocol.RESULT_PATH -> {
                val text = String(event.data, Charsets.UTF_8)
                runOnUiThread { statusText.text = "Watch: $text" }
            }

            Protocol.PROGRESS_PATH -> {
                if (event.data.size < 2) return
                val phase = event.data[0].toInt()
                val percent = (event.data[1].toInt() and 0xFF).coerceIn(0, 100)
                runOnUiThread { onWatchProgress(phase, percent) }
            }
        }
    }

    private fun onWatchProgress(phase: Int, percent: Int) {
        progressGroup.visibility = View.VISIBLE
        when (phase) {
            Protocol.PHASE_RECEIVING -> {
                watchReportsReceive = true
                setTransfer(percent, "$percent%")
                setWatchBusy(true)
            }

            Protocol.PHASE_INSTALLING -> {
                setTransfer(100, getString(R.string.transfer_done))
                setInstall(percent, "$percent%")
                setWatchBusy(true)
            }

            Protocol.PHASE_WAITING_FOR_USER -> {
                setTransfer(100, getString(R.string.transfer_done))
                setInstall(percent, getString(R.string.confirm_on_watch))
                // Waiting on a tap on the watch; nothing for the phone to protect meanwhile.
                setWatchBusy(false)
            }

            Protocol.PHASE_DONE -> {
                setTransfer(100, getString(R.string.transfer_done))
                setInstall(100, getString(R.string.install_done))
                setWatchBusy(false)
            }

            Protocol.PHASE_FAILED -> {
                installText.text = getString(R.string.failed)
                setWatchBusy(false)
            }
        }
    }

    private fun setTransfer(percent: Int, label: String) {
        transferProgress.setProgressCompat(percent, true)
        transferText.text = label
    }

    private fun setInstall(percent: Int, label: String) {
        installProgress.setProgressCompat(percent, true)
        installText.text = label
    }

    private fun handleIncomingIntent(intent: Intent?) {
        val uri = when (intent?.action) {
            Intent.ACTION_SEND -> IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
            Intent.ACTION_VIEW -> intent.data
            else -> null
        }
        if (uri != null) setSelectedFile(uri)
    }

    private fun setSelectedFile(uri: Uri) {
        var name: String? = null
        var size = -1L
        try {
            contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)
                ?.use { c ->
                    if (c.moveToFirst()) {
                        val nameIdx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        val sizeIdx = c.getColumnIndex(OpenableColumns.SIZE)
                        if (nameIdx >= 0) name = c.getString(nameIdx)
                        if (sizeIdx >= 0 && !c.isNull(sizeIdx)) size = c.getLong(sizeIdx)
                    }
                }
        } catch (e: Exception) {
            // Some providers don't support queries; fall back to the URI path.
        }

        selectedUri = uri
        selectedName = name ?: uri.lastPathSegment?.substringAfterLast('/') ?: "app.apk"
        selectedSize = size

        val sizeText = if (size >= 0) " (${Formatter.formatShortFileSize(this, size)})" else ""
        fileText.text = "$selectedName$sizeText"

        val lower = selectedName.lowercase()
        statusText.text = if (lower.endsWith(".apk") || lower.endsWith(".apks") || lower.endsWith(".xapk")) {
            ""
        } else {
            "Warning: this doesn't look like an .apk file, the watch may reject it."
        }
        updateControls()
    }

    private fun findWatch() {
        watchStatus.text = getString(R.string.searching)
        lifecycleScope.launch {
            try {
                val capable = Wearable.getCapabilityClient(this@MainActivity)
                    .getCapability(Protocol.WATCH_CAPABILITY, CapabilityClient.FILTER_REACHABLE)
                    .await()
                    .nodes
                val node = capable.firstOrNull { it.isNearby } ?: capable.firstOrNull()
                targetNode = node
                watchStatus.text = if (node != null) {
                    "Watch: ${node.displayName} ✅"
                } else {
                    val connected = Wearable.getNodeClient(this@MainActivity).connectedNodes.await()
                    if (connected.isNotEmpty()) {
                        "\"${connected.first().displayName}\" is connected, but Watch APK Installer isn't " +
                            "installed on it yet. Install the watch app first (see README)."
                    } else {
                        "No watch connected. Make sure your watch is paired in Galaxy Wearable and Bluetooth is on."
                    }
                }
            } catch (e: Exception) {
                targetNode = null
                watchStatus.text = "Couldn't reach the Wearable API: ${e.message}"
            }
            updateControls()
        }
    }

    /** Brings the watch app on screen so its progress ring is visible (best effort). */
    private fun openWatchApp(nodeId: String) {
        lifecycleScope.launch {
            try {
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(Protocol.OPEN_WATCH_APP_URI))
                    .addCategory(Intent.CATEGORY_BROWSABLE)
                withTimeoutOrNull(5_000) { remoteActivityHelper.startRemoteActivity(intent, nodeId).await() }
            } catch (e: Exception) {
                Log.w(TAG, "Couldn't open the watch app", e)
            }
        }
    }

    private fun sendToWatch() {
        val uri = selectedUri ?: return
        val node = targetNode ?: return
        val name = selectedName
        val size = selectedSize

        sending = true
        watchReportsReceive = false
        progressGroup.visibility = View.VISIBLE
        setTransfer(0, getString(R.string.starting))
        setInstall(0, getString(R.string.waiting))
        updateControls()

        openWatchApp(node.id)

        sendJob = lifecycleScope.launch {
            statusText.text = "Connecting to ${node.displayName}…"
            val channelClient = Wearable.getChannelClient(this@MainActivity)
            var channel: ChannelClient.Channel? = null
            try {
                channel = channelClient.openChannel(node.id, Protocol.CHANNEL_PATH).await()
                val output = channelClient.getOutputStream(channel).await()
                statusText.text = "Sending $name…"

                withContext(Dispatchers.IO) {
                    val input = contentResolver.openInputStream(uri)
                        ?: throw FileNotFoundException("Can't open $uri")
                    input.use { src ->
                        DataOutputStream(BufferedOutputStream(output, BUFFER_SIZE)).use { out ->
                            out.writeInt(Protocol.MAGIC)
                            out.writeInt(Protocol.VERSION)
                            out.writeUTF(name)
                            out.writeLong(size)

                            val buffer = ByteArray(BUFFER_SIZE)
                            var total = 0L
                            var lastPercent = -1
                            while (true) {
                                val read = src.read(buffer)
                                if (read < 0) break
                                out.write(buffer, 0, read)
                                total += read
                                if (size > 0) {
                                    val percent = (total * 100 / size).toInt()
                                    if (percent != lastPercent) {
                                        lastPercent = percent
                                        withContext(Dispatchers.Main) {
                                            // The watch's own numbers are more accurate once they arrive.
                                            if (!watchReportsReceive) setTransfer(percent, "$percent%")
                                        }
                                    }
                                }
                            }
                            out.flush()
                        }
                    }
                }
                // Closing the output stream signals end-of-file; the watch closes the channel.
                statusText.text = "Sent $name. Installing on your watch…"
                if (!watchReportsReceive) setTransfer(100, getString(R.string.transfer_done))
                setWatchBusy(true)
            } catch (e: Exception) {
                statusText.text = "Transfer failed: ${e.message ?: e.javaClass.simpleName}"
                transferText.text = getString(R.string.failed)
                channel?.let { runCatching { channelClient.close(it) } }
            } finally {
                sending = false
                updateControls()
            }
        }
    }

    /**
     * Marks the watch as working. If it goes quiet for a while (older watch app, or the watch
     * dropped the connection) we stop waiting so the screen can turn off again.
     */
    private fun setWatchBusy(busy: Boolean) {
        watchBusy = busy
        watchBusyTimeout?.cancel()
        if (busy) {
            watchBusyTimeout = lifecycleScope.launch {
                delay(WATCH_SILENCE_TIMEOUT_MS)
                watchBusy = false
                updateControls()
            }
        }
        updateControls()
    }

    /** Keeps the screen on and blocks back while anything is in flight. */
    private fun updateControls() {
        val busy = sending || watchBusy
        if (busy) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        backBlocker.isEnabled = busy
        pickButton.isEnabled = !busy
        findWatchButton.isEnabled = !busy
        sendButton.isEnabled = !busy && selectedUri != null && targetNode != null
    }

    private companion object {
        const val TAG = "WatchApkInstaller"
        const val BUFFER_SIZE = 64 * 1024
        const val WATCH_SILENCE_TIMEOUT_MS = 120_000L
    }
}
