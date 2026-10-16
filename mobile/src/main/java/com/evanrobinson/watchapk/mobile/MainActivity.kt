package com.evanrobinson.watchapk.mobile

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.text.format.Formatter
import android.view.View
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.IntentCompat
import androidx.lifecycle.lifecycleScope
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
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
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
    private lateinit var progress: LinearProgressIndicator

    private var selectedUri: Uri? = null
    private var selectedName: String = "app.apk"
    private var selectedSize: Long = -1
    private var targetNode: Node? = null
    private var sendJob: Job? = null

    private val pickApk = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) setSelectedFile(uri)
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
        progress = findViewById(R.id.progress)

        findWatchButton.setOnClickListener { findWatch() }
        // APK bundles often come through with generic MIME types, so accept anything.
        pickButton.setOnClickListener { pickApk.launch(arrayOf("*/*")) }
        sendButton.setOnClickListener { sendToWatch() }

        handleIncomingIntent(intent)
        findWatch()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIncomingIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        Wearable.getMessageClient(this).addListener(this)
    }

    override fun onPause() {
        Wearable.getMessageClient(this).removeListener(this)
        super.onPause()
    }

    /** Status reports sent back from the watch app. */
    override fun onMessageReceived(event: MessageEvent) {
        if (event.path != Protocol.RESULT_PATH) return
        val text = String(event.data, Charsets.UTF_8)
        runOnUiThread { statusText.text = "Watch: $text" }
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
        updateSendEnabled()
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
            updateSendEnabled()
        }
    }

    private fun updateSendEnabled() {
        sendButton.isEnabled = selectedUri != null && targetNode != null && sendJob?.isActive != true
    }

    private fun sendToWatch() {
        val uri = selectedUri ?: return
        val node = targetNode ?: return
        val name = selectedName
        val size = selectedSize

        sendJob = lifecycleScope.launch {
            setBusy(true)
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
                                            progress.setProgressCompat(percent, true)
                                        }
                                    }
                                }
                            }
                            out.flush()
                        }
                    }
                }
                // Closing the output stream signals end-of-file; the watch closes the channel.
                statusText.text = "Sent $name. Look at your watch to confirm the install."
            } catch (e: Exception) {
                statusText.text = "Transfer failed: ${e.message ?: e.javaClass.simpleName}"
                channel?.let { runCatching { channelClient.close(it) } }
            } finally {
                setBusy(false)
            }
        }
        updateSendEnabled()
    }

    private fun setBusy(busy: Boolean) {
        // Only switch indeterminate mode while hidden; the indicator dislikes switching while shown.
        progress.visibility = View.INVISIBLE
        if (busy) {
            progress.isIndeterminate = selectedSize <= 0
            progress.setProgressCompat(0, false)
            progress.visibility = View.VISIBLE
        }
        pickButton.isEnabled = !busy
        findWatchButton.isEnabled = !busy
        sendButton.isEnabled = !busy && selectedUri != null && targetNode != null
    }

    private companion object {
        const val BUFFER_SIZE = 64 * 1024
    }
}
