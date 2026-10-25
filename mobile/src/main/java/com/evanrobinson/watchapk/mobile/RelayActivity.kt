package com.evanrobinson.watchapk.mobile

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.text.format.Formatter
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.IntentCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.android.material.progressindicator.LinearProgressIndicator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.FileNotFoundException

/**
 * What the app shows inside Secure Folder (or a work profile): the watch can't be reached from
 * there, so this hands the shared files to the normal copy of the app, which sends them on.
 */
class RelayActivity : AppCompatActivity() {

    private class Item(val uri: Uri, val name: String, val size: Long)

    private lateinit var fileText: TextView
    private lateinit var statusText: TextView
    private lateinit var progress: LinearProgressIndicator
    private lateinit var sendButton: MaterialButton
    private lateinit var pickButton: MaterialButton

    private var items: List<Item> = emptyList()
    private var sending = false

    private val pick = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { picked ->
        if (picked.isNotEmpty()) select(picked, autoSend = true)
    }

    private val backBlocker = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() {}
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_relay)
        fileText = findViewById(R.id.relayFileText)
        statusText = findViewById(R.id.relayStatusText)
        progress = findViewById(R.id.relayProgress)
        sendButton = findViewById(R.id.relaySendButton)
        pickButton = findViewById(R.id.relayPickButton)
        onBackPressedDispatcher.addCallback(this, backBlocker)

        sendButton.setOnClickListener { send() }
        pickButton.setOnClickListener { pick.launch(arrayOf("*/*")) }
        handle(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (!sending) handle(intent)
    }

    private fun handle(intent: Intent?) {
        val shared: List<Uri> = when (intent?.action) {
            Intent.ACTION_SEND -> listOfNotNull(IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java))
            Intent.ACTION_SEND_MULTIPLE ->
                IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, Uri::class.java).orEmpty()
            Intent.ACTION_VIEW -> listOfNotNull(intent.data)
            else -> emptyList()
        }
        if (shared.isNotEmpty()) select(shared, autoSend = true)
    }

    private fun describe(uri: Uri): Item {
        var name = uri.lastPathSegment?.substringAfterLast('/') ?: "file"
        var size = -1L
        runCatching {
            contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)
                ?.use { c ->
                    if (c.moveToFirst()) {
                        c.getColumnIndex(OpenableColumns.DISPLAY_NAME).takeIf { it >= 0 }?.let { name = c.getString(it) ?: name }
                        c.getColumnIndex(OpenableColumns.SIZE).takeIf { it >= 0 && !c.isNull(it) }?.let { size = c.getLong(it) }
                    }
                }
        }
        return Item(uri, name, size)
    }

    private fun select(uris: List<Uri>, autoSend: Boolean) {
        items = uris.map(::describe)
        val total = items.sumOf { it.size.coerceAtLeast(0) }
        fileText.text = if (items.size == 1) {
            val item = items[0]
            item.name + if (item.size >= 0) " (${Formatter.formatShortFileSize(this, item.size)})" else ""
        } else {
            "${items.size} files (${Formatter.formatShortFileSize(this, total)})\n" +
                items.take(6).joinToString("\n") { "• ${it.name}" } +
                if (items.size > 6) "\n• …" else ""
        }
        sendButton.isEnabled = true
        if (autoSend) send()
    }

    private fun send() {
        val batch = items
        if (batch.isEmpty()) return
        setBusy(true)
        val total = batch.sumOf { it.size.coerceAtLeast(0) }
        lifecycleScope.launch {
            try {
                var doneBefore = 0L
                batch.forEachIndexed { index, item ->
                    statusText.text = if (batch.size == 1) {
                        getString(R.string.relay_sending)
                    } else {
                        getString(R.string.relay_sending_many, index + 1, batch.size)
                    }
                    withContext(Dispatchers.IO) {
                        val input = contentResolver.openInputStream(item.uri)
                            ?: throw FileNotFoundException("Can't open ${item.name}")
                        input.use {
                            var lastPercent = -1
                            SecureFolderRelay.send(index, batch.size, item.name, item.size, it) { done ->
                                if (total > 0) {
                                    val percent = ((doneBefore + done) * 100 / total).toInt()
                                    if (percent != lastPercent) {
                                        lastPercent = percent
                                        runOnUiThread { progress.setProgressCompat(percent, true) }
                                    }
                                }
                            }
                        }
                    }
                    doneBefore += item.size.coerceAtLeast(0)
                }
                progress.setProgressCompat(100, true)
                statusText.text = getString(R.string.relay_done)
            } catch (e: Exception) {
                statusText.text = getString(R.string.relay_unreachable, e.message ?: e.javaClass.simpleName)
            } finally {
                setBusy(false)
            }
        }
    }

    private fun setBusy(busy: Boolean) {
        sending = busy
        backBlocker.isEnabled = busy
        if (busy) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            progress.visibility = View.VISIBLE
            progress.setProgressCompat(0, false)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        sendButton.isEnabled = !busy && items.isNotEmpty()
        pickButton.isEnabled = !busy
    }
}
