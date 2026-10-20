package com.evanrobinson.watchapk.wear

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.text.format.Formatter
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.wear.widget.SwipeDismissFrameLayout
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * The watch app's only screen: waiting, receiving, installing and the result.
 *
 * While a transfer or install is running the screen stays on and the activity ignores every
 * way of leaving it that an app can intercept: swipe-to-dismiss, the back button, wrist
 * gestures, the rotary input and touches. (The home button always works; Android doesn't let
 * apps block it.)
 */
class MainActivity : ComponentActivity() {

    private lateinit var swipeRoot: SwipeDismissFrameLayout
    private lateinit var ring: ProgressBar
    private lateinit var title: TextView
    private lateinit var percent: TextView
    private lateinit var detail: TextView
    private lateinit var primaryButton: Button
    private lateinit var secondaryButton: Button
    private lateinit var stopButton: Button
    private lateinit var filesButton: Button

    private var locked = false

    /** Whether the current touch gesture started on the Stop button (the only thing tappable while locked). */
    private var touchOnStop = false

    /** Path of the file we already auto-started an install for, so we only do it once. */
    private var autoInstalledPath: String? = null

    private val requestNotifications =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {
            render(TransferState.status.value)
        }

    private val backBlocker = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() = showBusyToast()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        swipeRoot = findViewById(R.id.swipeRoot)
        ring = findViewById(R.id.ring)
        title = findViewById(R.id.title)
        percent = findViewById(R.id.percent)
        detail = findViewById(R.id.detail)
        primaryButton = findViewById(R.id.primaryButton)
        secondaryButton = findViewById(R.id.secondaryButton)
        stopButton = findViewById(R.id.stopButton)
        filesButton = findViewById(R.id.filesButton)
        filesButton.setOnClickListener { startActivity(Intent(this, FilesActivity::class.java)) }

        // Hold, not tap, so a stray touch or gesture can't stop a transfer.
        stopButton.setOnClickListener { Toast.makeText(this, R.string.hold_to_stop_hint, Toast.LENGTH_SHORT).show() }
        stopButton.setOnLongClickListener {
            if (TransferControl.requestCancel(TransferControl.BY_WATCH)) stopButton.isEnabled = false
            true
        }

        onBackPressedDispatcher.addCallback(this, backBlocker)
        // The theme turns off the system swipe-to-dismiss so we can switch it off while busy.
        swipeRoot.addCallback(object : SwipeDismissFrameLayout.Callback() {
            override fun onDismissed(layout: SwipeDismissFrameLayout) {
                layout.visibility = View.GONE
                finish()
            }
        })

        autoInstalledPath = savedInstanceState?.getString(KEY_AUTO_INSTALLED)
        handleIntent(intent)

        if (savedInstanceState == null && needsNotificationPermission()) {
            requestNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                TransferState.status.collect { render(it) }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(KEY_AUTO_INSTALLED, autoInstalledPath)
    }

    override fun onResume() {
        super.onResume()
        isVisible = true
        // Back from the system's confirmation screen: the user either confirmed (install
        // continues) or cancelled (the result receiver will report it).
        val status = TransferState.status.value
        if (status is TransferState.Status.Installing && status.waitingForUser) {
            TransferState.set(status.copy(waitingForUser = false))
        }
        render(TransferState.status.value)
    }

    override fun onPause() {
        isVisible = false
        super.onPause()
    }

    // --- Input lock while busy -------------------------------------------------------------

    @SuppressLint("ClickableViewAccessibility")
    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (locked) {
            if (ev.actionMasked == MotionEvent.ACTION_DOWN) touchOnStop = isOnStopButton(ev)
            return if (touchOnStop) super.dispatchTouchEvent(ev) else true
        }
        return super.dispatchTouchEvent(ev)
    }

    private fun isOnStopButton(ev: MotionEvent): Boolean {
        if (!stopButton.isShown || !stopButton.isEnabled) return false
        val rect = Rect()
        return stopButton.getGlobalVisibleRect(rect) && rect.contains(ev.rawX.toInt(), ev.rawY.toInt())
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        // Wrist gestures arrive as KEYCODE_NAVIGATE_* key events; the back button as KEYCODE_BACK.
        if (locked && event.keyCode != KeyEvent.KEYCODE_POWER) {
            if (event.keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP) showBusyToast()
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    override fun dispatchGenericMotionEvent(ev: MotionEvent): Boolean {
        // Rotary / bezel scrolling.
        if (locked) return true
        return super.dispatchGenericMotionEvent(ev)
    }

    private fun setLocked(lock: Boolean) {
        if (lock == locked) return
        locked = lock
        if (lock) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        backBlocker.isEnabled = lock
        swipeRoot.isSwipeable = !lock
    }

    private fun showBusyToast() {
        Toast.makeText(this, R.string.busy_toast, Toast.LENGTH_SHORT).show()
    }

    // --- UI --------------------------------------------------------------------------------

    private fun handleIntent(intent: Intent?) {
        val path = intent?.getStringExtra(EXTRA_FILE_PATH) ?: return
        val file = File(path)
        Notifications.cancel(this, file)
        if (!TransferState.status.value.busy && file.exists()) {
            TransferState.set(TransferState.Status.ReadyToInstall(file, intent.getStringExtra(EXTRA_NODE_ID)))
        }
    }

    private fun render(status: TransferState.Status) {
        setLocked(status.busy)
        primaryButton.visibility = View.GONE
        secondaryButton.visibility = View.GONE
        filesButton.visibility = if (status is TransferState.Status.Idle) View.VISIBLE else View.GONE
        if (status.cancellable) {
            if (stopButton.visibility != View.VISIBLE) stopButton.isEnabled = true
            stopButton.visibility = View.VISIBLE
        } else {
            stopButton.visibility = View.GONE
        }
        percent.visibility = View.GONE
        ring.visibility = View.GONE

        when (status) {
            TransferState.Status.Idle -> {
                title.text = getString(R.string.app_name)
                if (ApkInstaller.canInstall(this)) {
                    detail.text = getString(R.string.ready) + "\n\nv${BuildConfig.VERSION_NAME}"
                } else {
                    detail.text = getString(R.string.install_permission_missing)
                    showButton(primaryButton, R.string.allow_installs) { openInstallSettings() }
                }
                if (needsNotificationPermission()) {
                    showButton(secondaryButton, R.string.allow_notifications) {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            requestNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
                        }
                    }
                }
            }

            is TransferState.Status.Receiving -> {
                title.text = getString(R.string.receiving)
                // A new file is coming in (possibly with the same name as the last one).
                autoInstalledPath = null
                if (status.total > 0) {
                    showProgress(status.fraction)
                    detail.text = "${status.name}\n${size(status.bytes)} of ${size(status.total)}"
                } else {
                    ring.visibility = View.VISIBLE
                    ring.progress = 0
                    detail.text = "${status.name}\n${size(status.bytes)}"
                }
            }

            is TransferState.Status.ReadyToInstall -> {
                title.text = status.file.name
                when {
                    !status.file.exists() -> {
                        detail.text = getString(R.string.file_gone)
                        showButton(primaryButton, R.string.ok) { TransferState.set(TransferState.Status.Idle) }
                    }

                    !ApkInstaller.canInstall(this) -> {
                        detail.text = getString(R.string.install_permission_missing)
                        showButton(primaryButton, R.string.allow_installs) { openInstallSettings() }
                    }

                    autoInstalledPath != status.file.absolutePath -> {
                        autoInstalledPath = status.file.absolutePath
                        startInstall(status.file, status.nodeId)
                    }

                    else -> {
                        detail.text = getString(R.string.ready_to_install)
                        showButton(primaryButton, R.string.install) { startInstall(status.file, status.nodeId) }
                    }
                }
            }

            is TransferState.Status.Installing -> {
                title.text = getString(if (status.waitingForUser) R.string.confirm_install else R.string.installing)
                showProgress(status.fraction)
                detail.text = status.name
            }

            is TransferState.Status.Success -> {
                title.text = getString(R.string.installed)
                showProgress(1f)
                detail.text = status.packageName ?: status.name
                val launch = status.packageName?.let { packageManager.getLaunchIntentForPackage(it) }
                if (launch != null) {
                    showButton(primaryButton, R.string.open_app) {
                        TransferState.set(TransferState.Status.Idle)
                        startActivity(launch)
                        finish()
                    }
                }
                showButton(secondaryButton, R.string.done) { TransferState.set(TransferState.Status.Idle) }
            }

            is TransferState.Status.Saved -> {
                title.text = getString(R.string.saved)
                showProgress(1f)
                detail.text = "${status.name}\n${ReceivedFiles.FOLDER_LABEL}"
                showButton(primaryButton, R.string.open) {
                    if (!ReceivedFiles.open(this, status.uri, status.mime)) {
                        Toast.makeText(this, R.string.no_app_to_open, Toast.LENGTH_LONG).show()
                    }
                }
                showButton(secondaryButton, R.string.done) { TransferState.set(TransferState.Status.Idle) }
            }

            is TransferState.Status.Cancelled -> {
                title.text = getString(R.string.stopped)
                detail.text = status.message
                showButton(primaryButton, R.string.ok) { TransferState.set(TransferState.Status.Idle) }
            }

            is TransferState.Status.Failure -> {
                title.text = getString(R.string.failed)
                detail.text = status.message
                showButton(primaryButton, R.string.ok) { TransferState.set(TransferState.Status.Idle) }
            }
        }
    }

    private fun showProgress(fraction: Float) {
        val pct = (fraction * 100).toInt().coerceIn(0, 100)
        ring.visibility = View.VISIBLE
        ring.progress = (fraction * ring.max).toInt()
        percent.visibility = View.VISIBLE
        percent.text = "$pct%"
    }

    private fun showButton(button: Button, textRes: Int, onClick: () -> Unit) {
        button.visibility = View.VISIBLE
        button.text = getString(textRes)
        button.setOnClickListener { onClick() }
    }

    private fun size(bytes: Long): String = Formatter.formatShortFileSize(this, bytes)

    private fun openInstallSettings() {
        if (!ApkInstaller.openInstallPermissionSettings(this)) {
            detail.text = getString(R.string.install_permission_adb)
        }
    }

    private fun startInstall(file: File, nodeId: String?) {
        TransferState.set(TransferState.Status.Installing(file.name, 0f))
        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) { ApkInstaller.install(applicationContext, file, nodeId) }
            } catch (e: Exception) {
                file.delete()
                if (TransferControl.isCancelled) {
                    val message = TransferControl.cancelledMessage()
                    TransferState.set(TransferState.Status.Cancelled(message))
                    PhoneReporter.progress(this@MainActivity, nodeId, Protocol.PHASE_CANCELLED, 0)
                    PhoneReporter.send(this@MainActivity, nodeId, "install ${message.lowercase()}")
                    return@launch
                }
                val reason = e.message ?: e.javaClass.simpleName
                TransferState.set(TransferState.Status.Failure(reason))
                PhoneReporter.progress(this@MainActivity, nodeId, Protocol.PHASE_FAILED, 0)
                PhoneReporter.send(this@MainActivity, nodeId, "install failed: $reason")
            }
        }
    }

    private fun needsNotificationPermission(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED

    companion object {
        private const val EXTRA_FILE_PATH = "file_path"
        private const val EXTRA_NODE_ID = "node_id"
        private const val KEY_AUTO_INSTALLED = "auto_installed"

        /** Lets the receiver service know whether to post a notification or not. */
        @Volatile
        var isVisible = false
            private set

        fun installIntent(context: Context, file: File, nodeId: String?): Intent =
            Intent(context, MainActivity::class.java)
                .putExtra(EXTRA_FILE_PATH, file.absolutePath)
                .putExtra(EXTRA_NODE_ID, nodeId)
    }
}
