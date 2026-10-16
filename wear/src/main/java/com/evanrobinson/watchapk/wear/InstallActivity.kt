package com.evanrobinson.watchapk.wear

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Shown when an APK has arrived; installs it (the system then asks the user to confirm). */
class InstallActivity : ComponentActivity() {

    private lateinit var fileName: TextView
    private lateinit var message: TextView
    private lateinit var primaryButton: Button
    private lateinit var closeButton: Button

    private var file: File? = null
    private var nodeId: String? = null
    private var autoStarted = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_install)
        fileName = findViewById(R.id.fileName)
        message = findViewById(R.id.message)
        primaryButton = findViewById(R.id.primaryButton)
        closeButton = findViewById(R.id.closeButton)
        closeButton.setOnClickListener { finish() }

        autoStarted = savedInstanceState?.getBoolean(KEY_AUTO_STARTED) ?: false
        InstallEvents.post(InstallEvents.State.Idle)
        loadIntent(intent)

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                InstallEvents.state.collect { render(it) }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        autoStarted = false
        InstallEvents.post(InstallEvents.State.Idle)
        loadIntent(intent)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(KEY_AUTO_STARTED, autoStarted)
    }

    override fun onResume() {
        super.onResume()
        // Re-check every time, e.g. after returning from the permission settings screen.
        if (InstallEvents.state.value == InstallEvents.State.Idle) render(InstallEvents.State.Idle)
    }

    private fun loadIntent(intent: Intent) {
        file = intent.getStringExtra(EXTRA_FILE_PATH)?.let(::File)
        nodeId = intent.getStringExtra(EXTRA_NODE_ID)
        file?.let { Notifications.cancel(this, it) }
        fileName.text = file?.name ?: ""
    }

    private fun render(state: InstallEvents.State) {
        val current = file
        when (state) {
            InstallEvents.State.Idle -> when {
                current == null || !current.exists() -> {
                    message.text = "This file is no longer available. Send it again from your phone."
                    primaryButton.visibility = View.GONE
                }

                !ApkInstaller.canInstall(this) -> {
                    message.text = getString(R.string.install_permission_missing)
                    primaryButton.visibility = View.VISIBLE
                    primaryButton.text = getString(R.string.allow_installs)
                    primaryButton.setOnClickListener {
                        if (!ApkInstaller.openInstallPermissionSettings(this)) {
                            message.text = getString(R.string.install_permission_adb)
                        }
                    }
                }

                else -> {
                    message.text = "Ready to install."
                    primaryButton.visibility = View.VISIBLE
                    primaryButton.text = getString(R.string.install)
                    primaryButton.setOnClickListener { startInstall(current) }
                    if (!autoStarted) {
                        autoStarted = true
                        startInstall(current)
                    }
                }
            }

            InstallEvents.State.Installing -> {
                message.text = "Installing… confirm on the next screen."
                primaryButton.visibility = View.GONE
            }

            is InstallEvents.State.Success -> {
                message.text = "Installed ✅"
                val launch = state.packageName?.let { packageManager.getLaunchIntentForPackage(it) }
                if (launch != null) {
                    primaryButton.visibility = View.VISIBLE
                    primaryButton.text = getString(R.string.open_app)
                    primaryButton.setOnClickListener {
                        startActivity(launch)
                        finish()
                    }
                } else {
                    primaryButton.visibility = View.GONE
                }
            }

            is InstallEvents.State.Failure -> {
                message.text = state.message
                primaryButton.visibility = View.GONE
            }
        }
    }

    private fun startInstall(target: File) {
        InstallEvents.post(InstallEvents.State.Installing)
        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) { ApkInstaller.install(applicationContext, target, nodeId) }
            } catch (e: Exception) {
                val reason = e.message ?: e.javaClass.simpleName
                target.delete()
                InstallEvents.post(InstallEvents.State.Failure(reason))
                PhoneReporter.send(this@InstallActivity, nodeId, "install failed: $reason")
            }
        }
    }

    companion object {
        private const val EXTRA_FILE_PATH = "file_path"
        private const val EXTRA_NODE_ID = "node_id"
        private const val KEY_AUTO_STARTED = "auto_started"

        fun intent(context: Context, file: File, nodeId: String?): Intent =
            Intent(context, InstallActivity::class.java)
                .putExtra(EXTRA_FILE_PATH, file.absolutePath)
                .putExtra(EXTRA_NODE_ID, nodeId)
    }
}
