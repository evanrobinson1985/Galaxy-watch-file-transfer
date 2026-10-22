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
 * there, so this hands the shared file to the normal copy of the app, which sends it on.
 */
class RelayActivity : AppCompatActivity() {

    private lateinit var fileText: TextView
    private lateinit var statusText: TextView
    private lateinit var progress: LinearProgressIndicator
    private lateinit var sendButton: MaterialButton
    private lateinit var pickButton: MaterialButton

    private var uri: Uri? = null
    private var name = "file"
    private var size = -1L
    private var sending = false

    private val pick = registerForActivityResult(ActivityResultContracts.OpenDocument()) { picked ->
        if (picked != null) select(picked, autoSend = true)
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
        val shared = when (intent?.action) {
            Intent.ACTION_SEND -> IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
            Intent.ACTION_VIEW -> intent.data
            else -> null
        }
        if (shared != null) select(shared, autoSend = true)
    }

    private fun select(picked: Uri, autoSend: Boolean) {
        uri = picked
        name = "file"
        size = -1L
        runCatching {
            contentResolver.query(picked, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)
                ?.use { c ->
                    if (c.moveToFirst()) {
                        c.getColumnIndex(OpenableColumns.DISPLAY_NAME).takeIf { it >= 0 }?.let { name = c.getString(it) ?: name }
                        c.getColumnIndex(OpenableColumns.SIZE).takeIf { it >= 0 && !c.isNull(it) }?.let { size = c.getLong(it) }
                    }
                }
        }
        val sizeText = if (size >= 0) " (${Formatter.formatShortFileSize(this, size)})" else ""
        fileText.text = "$name$sizeText"
        sendButton.isEnabled = true
        if (autoSend) send()
    }

    private fun send() {
        val source = uri ?: return
        setBusy(true)
        statusText.text = getString(R.string.relay_sending)
        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val input = contentResolver.openInputStream(source) ?: throw FileNotFoundException("Can't open $name")
                    input.use {
                        var lastPercent = -1
                        SecureFolderRelay.send(name, size, it) { done ->
                            if (size > 0) {
                                val percent = (done * 100 / size).toInt()
                                if (percent != lastPercent) {
                                    lastPercent = percent
                                    runOnUiThread { progress.setProgressCompat(percent, true) }
                                }
                            }
                        }
                    }
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
        sendButton.isEnabled = !busy && uri != null
        pickButton.isEnabled = !busy
    }
}
