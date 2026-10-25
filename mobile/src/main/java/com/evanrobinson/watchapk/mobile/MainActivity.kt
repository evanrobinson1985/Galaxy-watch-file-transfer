package com.evanrobinson.watchapk.mobile

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.wear.remote.interactions.RemoteActivityHelper
import com.google.android.gms.wearable.CapabilityClient
import com.google.android.gms.wearable.ChannelClient
import com.google.android.gms.wearable.MessageClient
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.Node
import com.google.android.gms.wearable.Wearable
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.progressindicator.LinearProgressIndicator
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.BufferedOutputStream
import java.io.DataOutputStream
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream

class MainActivity : AppCompatActivity(), MessageClient.OnMessageReceivedListener {

    private lateinit var watchStatus: TextView
    private lateinit var fileText: TextView
    private lateinit var statusText: TextView
    private lateinit var findWatchButton: MaterialButton
    private lateinit var watchAppText: TextView
    private lateinit var updateWatchButton: MaterialButton
    private lateinit var pickButton: MaterialButton
    private lateinit var sendButton: MaterialButton
    private lateinit var installSwitch: MaterialSwitch
    private lateinit var installSection: View
    private lateinit var methodLabel: View
    private lateinit var methodGroup: MaterialButtonToggleGroup
    private lateinit var methodBluetooth: MaterialButton
    private lateinit var methodWifi: MaterialButton
    private lateinit var methodHint: TextView
    private lateinit var wifiProblemText: TextView
    private lateinit var progressGroup: View
    private lateinit var transferProgress: LinearProgressIndicator
    private lateinit var transferText: TextView
    private lateinit var installProgress: LinearProgressIndicator
    private lateinit var installText: TextView
    private lateinit var stopButton: MaterialButton
    private lateinit var relaySwitch: MaterialSwitch
    private lateinit var relayStatusText: TextView
    private lateinit var transferLabel: TextView

    /** Files chosen to send; the selected* fields below describe the first one / the total. */
    private data class Picked(val uri: Uri, val name: String, val size: Long)

    private var picked: List<Picked> = emptyList()

    /** Sending several files one after another. */
    private var queueJob: Job? = null
    private var queueActive = false
    private var watchFinished: CompletableDeferred<Boolean>? = null
    private var lastSendFailed = false

    private var selectedUri: Uri? = null
    private var selectedName: String = "app.apk"
    private var selectedSize: Long = -1
    private var targetNode: Node? = null
    private var sendJob: Job? = null

    /** Version of the watch app as reported by the watch; null until it answers. */
    private var watchVersionCode: Int? = null
    private var watchVersionName: String? = null

    /** True when the watch didn't answer the version request (watch app 2.0 or older). */
    private var watchVersionUnknown = false

    /** True while we are streaming the file. */
    private var sending = false

    /** True while the watch reports it is still receiving or installing. */
    private var watchBusy = false
    private var watchBusyTimeout: Job? = null

    /** Whether the watch's current step can still be stopped (not once Android's installer took over). */
    private var watchCancellable = false

    /** The user tapped Stop for the current transfer. */
    private var userCancelled = false

    private var currentChannel: ChannelClient.Channel? = null
    private var currentWifiServer: WifiSendServer? = null

    /** Waiting for the watch to join Wi-Fi and connect; its failure reports go to [wifiFailureReason]. */
    private var wifiConnecting = false
    private var wifiFailureReason = ""
    private var wifiWatchFailed = false

    /** We cancelled the watch's Wi-Fi attempt ourselves to fall back; don't show it as "Stopped". */
    private var suppressNextWatchCancel = false
    private var suppressCancelText = false

    /** The user picked a method for the current file, so don't change it automatically. */
    private var methodChosenByUser = false
    private var settingMethod = false

    /** For the speed / time-left readout. */
    private var transferStartedAt = 0L
    private var transferSize = -1L

    /** Once the watch reports receive progress, its numbers drive the transfer bar. */
    private var watchReportsReceive = false

    private val remoteActivityHelper by lazy { RemoteActivityHelper(this) }

    private val pickApk = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) setSelectedFiles(uris.map(::describe))
    }

    private val requestNotifications =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { startRelayIfEnabled() }

    private val backBlocker = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() {
            Toast.makeText(this@MainActivity, R.string.busy_toast, Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (SecureFolderRelay.isInSecondaryProfile(this)) {
            // Inside Secure Folder the watch can't be reached; hand files to the normal copy instead.
            startActivity(
                Intent(intent).setClass(this, RelayActivity::class.java)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
            )
            finish()
            return
        }
        setContentView(R.layout.activity_main)

        watchStatus = findViewById(R.id.watchStatus)
        fileText = findViewById(R.id.fileText)
        statusText = findViewById(R.id.statusText)
        findWatchButton = findViewById(R.id.findWatchButton)
        watchAppText = findViewById(R.id.watchAppText)
        updateWatchButton = findViewById(R.id.updateWatchButton)
        pickButton = findViewById(R.id.pickButton)
        sendButton = findViewById(R.id.sendButton)
        installSwitch = findViewById(R.id.installSwitch)
        installSection = findViewById(R.id.installSection)
        installSwitch.setOnCheckedChangeListener { _, _ -> updateSelectionUi() }
        methodLabel = findViewById(R.id.methodLabel)
        methodGroup = findViewById(R.id.methodGroup)
        methodBluetooth = findViewById(R.id.methodBluetooth)
        methodWifi = findViewById(R.id.methodWifi)
        methodHint = findViewById(R.id.methodHint)
        wifiProblemText = findViewById(R.id.wifiProblemText)
        methodGroup.addOnButtonCheckedListener { _, _, isChecked ->
            if (isChecked && !settingMethod) methodChosenByUser = true
            if (isChecked) updateSelectionUi()
        }
        progressGroup = findViewById(R.id.progressGroup)
        transferProgress = findViewById(R.id.transferProgress)
        transferText = findViewById(R.id.transferText)
        installProgress = findViewById(R.id.installProgress)
        installText = findViewById(R.id.installText)
        stopButton = findViewById(R.id.stopButton)
        relaySwitch = findViewById(R.id.relaySwitch)
        relayStatusText = findViewById(R.id.relayStatusText)
        transferLabel = findViewById(R.id.transferLabel)
        relaySwitch.isChecked = RelayService.isEnabled(this)
        relaySwitch.setOnCheckedChangeListener { _, checked ->
            RelayService.setEnabled(this, checked)
            renderRelayStatus(RelayService.status.value)
            if (!checked) {
                RelayService.stop(this)
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
            ) {
                // Needed for the "Tap to send to watch" notification.
                requestNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
            } else {
                startRelayIfEnabled()
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                RelayService.status.collect { renderRelayStatus(it) }
            }
        }
            }
        }
        startRelayIfEnabled()

        onBackPressedDispatcher.addCallback(this, backBlocker)
        findWatchButton.setOnClickListener { findWatch() }
        // APK bundles often come through with generic MIME types, so accept anything.
        pickButton.setOnClickListener { pickApk.launch(arrayOf("*/*")) }
        sendButton.setOnClickListener { sendSelectedFile() }
        updateWatchButton.setOnClickListener { sendWatchAppUpdate() }
        stopButton.setOnClickListener { stopTransfer() }

        // Listen for the whole lifetime of the screen so progress isn't missed while paused.
        Wearable.getMessageClient(this).addListener(this)

        handleIncomingIntent(intent)
        findWatch()
    }

    override fun onDestroy() {
        if (::relaySwitch.isInitialized) Wearable.getMessageClient(this).removeListener(this)
        super.onDestroy()
    }

    private fun renderRelayStatus(status: RelayService.Status) {
        if (!relaySwitch.isChecked) {
            relayStatusText.visibility = View.GONE
            return
        }
        relayStatusText.visibility = View.VISIBLE
        relayStatusText.text = when (status) {
            is RelayService.Status.Listening ->
                getString(R.string.relay_status_listening, status.addresses.joinToString())
            RelayService.Status.Starting -> getString(R.string.relay_status_starting)
            RelayService.Status.Off -> getString(R.string.relay_status_off)
            is RelayService.Status.Error -> getString(R.string.relay_status_error, status.message)
        }
    }

    private fun startRelayIfEnabled() {
        if (RelayService.isEnabled(this)) runCatching { RelayService.start(this) }
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
                runOnUiThread {
                    when {
                        wifiConnecting && text.startsWith("Receive failed") ->
                            wifiFailureReason = text.removePrefix("Receive failed: ").trimEnd('.')
                        suppressCancelText && text.startsWith("transfer stopped") -> suppressCancelText = false
                        else -> statusText.text = "Watch: $text"
                    }
                }
            }

            Protocol.VERSION_PATH -> {
                val parts = String(event.data, Charsets.UTF_8).split('|', limit = 2)
                val code = parts.getOrNull(0)?.toIntOrNull() ?: return
                runOnUiThread {
                    watchVersionCode = code
                    watchVersionName = parts.getOrNull(1)
                    watchVersionUnknown = false
                    renderWatchApp()
                }
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
                watchCancellable = true
                if (!wifiConnecting) {
                    setTransferProgress(percent, if (transferSize > 0) transferSize * percent / 100 else 0)
                }
                setWatchBusy(true)
            }

            Protocol.PHASE_INSTALLING -> {
                // Up to 80% is copying into the installer, which can still be stopped.
                watchCancellable = percent < 80
                setTransfer(100, getString(R.string.transfer_done))
                setInstall(percent, "$percent%")
                setWatchBusy(true)
            }

            Protocol.PHASE_WAITING_FOR_USER -> {
                watchCancellable = false
                setTransfer(100, getString(R.string.transfer_done))
                setInstall(percent, getString(R.string.confirm_on_watch))
                // Waiting on a tap on the watch; nothing for the phone to protect meanwhile.
                setWatchBusy(false)
            }

            Protocol.PHASE_DONE -> {
                watchFinished?.complete(true)
                watchCancellable = false
                setTransfer(100, getString(R.string.transfer_done))
                setInstall(100, getString(R.string.install_done))
                setWatchBusy(false)
            }

            Protocol.PHASE_FAILED -> {
                if (wifiConnecting) {
                    wifiWatchFailed = true
                    // The watch couldn't get on Wi-Fi. Give its reason a moment to arrive, then
                    // stop waiting for it to connect; sendToWatch falls back to Bluetooth.
                    lifecycleScope.launch {
                        delay(700)
                        currentWifiServer?.close()
                    }
                    return
                }
                watchFinished?.complete(false)
                watchCancellable = false
                installText.text = getString(R.string.failed)
                setWatchBusy(false)
            }

            Protocol.PHASE_CANCELLED -> {
                if (suppressNextWatchCancel) {
                    suppressNextWatchCancel = false
                    suppressCancelText = true
                    return
                }
                watchFinished?.complete(false)
                watchCancellable = false
                if (transferProgress.progress < 100) transferText.text = getString(R.string.stopped)
                installText.text = getString(R.string.stopped)
                statusText.text = if (userCancelled) "Stopped." else "Stopped on the watch."
                setWatchBusy(false)
            }
        }
    }

    /** "45% · 6.1 MB/s · 1:20 left" */
    private fun setTransferProgress(percent: Int, bytesDone: Long) {
        var label = "$percent%"
        val start = transferStartedAt
        if (start > 0 && bytesDone > 0) {
            val elapsedMs = SystemClock.elapsedRealtime() - start
            if (elapsedMs > 1_500) {
                val bytesPerSec = bytesDone * 1000 / elapsedMs
                label += " · ${Formatter.formatShortFileSize(this, bytesPerSec)}/s"
                if (transferSize > 0 && bytesPerSec > 0) {
                    val secs = (transferSize - bytesDone) / bytesPerSec
                    label += " · %d:%02d left".format(secs / 60, secs % 60)
                }
            }
        }
        setTransfer(percent, label)
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
        if (intent?.action == ACTION_RELAYED_FILE) {
            val files = RelayService.batchFiles(this)
            if (files.isNotEmpty()) {
                setSelectedFiles(files.map { Picked(Uri.fromFile(it), it.name, it.length()) })
                statusText.text = getString(R.string.relay_received)
            }
            return
        }
        val uris: List<Uri> = when (intent?.action) {
            Intent.ACTION_SEND -> listOfNotNull(IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java))
            Intent.ACTION_SEND_MULTIPLE ->
                IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, Uri::class.java).orEmpty()
            Intent.ACTION_VIEW -> listOfNotNull(intent.data)
            else -> emptyList()
        }
        if (uris.isNotEmpty()) setSelectedFiles(uris.map(::describe))
    }

    private fun describe(uri: Uri): Picked {
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

        return Picked(uri, name ?: uri.lastPathSegment?.substringAfterLast('/') ?: "file", size)
    }

    private fun setSelectedFiles(files: List<Picked>) {
        if (files.isEmpty()) return
        picked = files
        selectedUri = files[0].uri
        selectedName = files[0].name
        selectedSize = if (files.all { it.size >= 0 }) files.sumOf { it.size } else -1L

        val sizeText = if (selectedSize >= 0) " (${Formatter.formatShortFileSize(this, selectedSize)})" else ""
        fileText.text = if (files.size == 1) {
            "$selectedName$sizeText"
        } else {
            "${files.size} files$sizeText\n" + files.take(6).joinToString("\n") { "• ${it.name}" } +
                if (files.size > 6) "\n• …" else ""
        }

        installSwitch.isChecked = true
        methodChosenByUser = false
        statusText.text = ""
        updateSelectionUi()
    }

    private fun isApk(name: String): Boolean = name.lowercase().let {
        it.endsWith(".apk") || it.endsWith(".apks") || it.endsWith(".xapk")
    }

    /** APKs are installed (unless the switch is off); everything else is saved to the watch's Downloads. */
    private fun installSelected(): Boolean = installFor(selectedName)

    private fun installFor(name: String): Boolean = isApk(name) && installSwitch.isChecked

    /** Whether any chosen file will be saved rather than installed (needs watch app 5.0). */
    private fun needsSaving(): Boolean = picked.any { !installFor(it.name) }

    /** Saving non-APK files needs watch app 5.0; older ones only understand APK installs. */
    private fun watchCanSaveFiles(): Boolean = (watchVersionCode ?: 0) >= Protocol.MIN_WATCH_VERSION_FOR_FILES

    /** Wi-Fi needs watch app 6.0 and a file whose size we know up front. */
    private fun wifiAllowed(): Boolean =
        (watchVersionCode ?: 0) >= Protocol.MIN_WATCH_VERSION_FOR_WIFI && selectedSize > 0

    private fun setMethod(wifi: Boolean) {
        settingMethod = true
        methodGroup.check(if (wifi) R.id.methodWifi else R.id.methodBluetooth)
        settingMethod = false
    }

    private fun updateMethodUi() {
        val hasFile = selectedUri != null
        methodLabel.visibility = if (hasFile) View.VISIBLE else View.GONE
        methodGroup.visibility = methodLabel.visibility
        methodHint.visibility = methodLabel.visibility
        if (!hasFile) return

        val wifiOk = wifiAllowed()
        if (!wifiOk && methodGroup.checkedButtonId == R.id.methodWifi) setMethod(false)
        // Default: Wi-Fi for anything big enough to be slow over Bluetooth.
        if (!methodChosenByUser && wifiOk) setMethod(selectedSize >= WIFI_DEFAULT_THRESHOLD)

        val wifi = methodGroup.checkedButtonId == R.id.methodWifi
        methodHint.text = when {
            wifi -> getString(R.string.wifi_hint)
            (watchVersionCode ?: 0) < Protocol.MIN_WATCH_VERSION_FOR_WIFI ->
                getString(R.string.wifi_needs_update) + bluetoothEstimate()
            selectedSize <= 0 -> getString(R.string.wifi_needs_size)
            else -> getString(R.string.bluetooth_hint) + bluetoothEstimate()
        }
    }

    /** Rough time over Bluetooth, to show why Wi-Fi is worth it for big files. */
    private fun bluetoothEstimate(): String {
        if (selectedSize < WIFI_DEFAULT_THRESHOLD) return ""
        val minutes = (selectedSize / BLUETOOTH_BYTES_PER_SEC / 60).coerceAtLeast(1)
        return " " + getString(R.string.bluetooth_estimate, minutes)
    }

    private fun updateSelectionUi() {
        updateMethodUi()
        val hasFile = selectedUri != null
        installSwitch.visibility = if (hasFile && picked.any { isApk(it.name) }) View.VISIBLE else View.GONE
        sendButton.text = when {
            picked.size > 1 -> getString(R.string.send_many, picked.size)
            installSelected() -> getString(R.string.send)
            else -> getString(R.string.send_file)
        }
        if (hasFile && needsSaving() && targetNode != null && !watchCanSaveFiles()) {
            statusText.text = if (watchVersionCode == null && !watchVersionUnknown) {
                "Checking the watch app version…"
            } else {
                "Saving files needs watch app v5.0 or newer. Tap \"Update watch app\" first."
            }
        } else if (statusText.text.startsWith("Saving files needs") || statusText.text.startsWith("Checking the watch")) {
            statusText.text = ""
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
                if (node != null) requestWatchVersion(node)
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
            renderWatchApp()
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

    private fun requestWatchVersion(node: Node) {
        watchVersionCode = null
        watchVersionName = null
        watchVersionUnknown = false
        renderWatchApp()
        lifecycleScope.launch {
            try {
                Wearable.getMessageClient(this@MainActivity)
                    .sendMessage(node.id, Protocol.VERSION_REQUEST_PATH, ByteArray(0))
                    .await()
            } catch (e: Exception) {
                Log.w(TAG, "Version request failed", e)
            }
            // Watch apps before 3.0 don't answer.
            delay(VERSION_REPLY_TIMEOUT_MS)
            if (watchVersionCode == null) {
                watchVersionUnknown = true
                renderWatchApp()
            }
        }
    }

    private fun renderWatchApp() {
        val bundledCode = BuildConfig.WATCH_VERSION_CODE
        val bundledName = BuildConfig.WATCH_VERSION_NAME
        val code = watchVersionCode
        watchAppText.text = when {
            targetNode == null -> ""
            code != null && code < bundledCode -> "Watch app: v$watchVersionName. Update available: v$bundledName"
            code != null && code == bundledCode -> "Watch app: v$watchVersionName (up to date)"
            code != null -> "Watch app: v$watchVersionName (newer than this phone app)"
            watchVersionUnknown -> "Watch app: older than v$bundledName. Update available: v$bundledName"
            else -> "Watch app: checking version…"
        }
        updateWatchButton.text = if (code != null && code >= bundledCode) {
            getString(R.string.reinstall_watch_app, bundledName)
        } else {
            getString(R.string.update_watch_app_to, bundledName)
        }
        updateSelectionUi()
    }

    /** Something to stream to the watch: a picked file, or the watch app bundled in this app. */
    private class SendSource(
        val name: String,
        val size: Long,
        val install: Boolean,
        val open: () -> InputStream,
    )

    private fun sourceFor(file: Picked) = SendSource(file.name, file.size, installFor(file.name)) {
        contentResolver.openInputStream(file.uri) ?: throw FileNotFoundException("Can't open ${file.name}")
    }

    private fun sendSelectedFile() {
        val files = picked
        if (files.isEmpty()) return
        val useWifi = methodGroup.checkedButtonId == R.id.methodWifi && wifiAllowed()
        if (files.size == 1) {
            sendToWatch(sourceFor(files[0]), useWifi)
            return
        }

        // Several files: send one, wait for the watch to finish saving/installing it, then the next.
        queueActive = true
        userCancelled = false
        updateControls()
        queueJob = lifecycleScope.launch {
            var sent = 0
            try {
                for ((index, file) in files.withIndex()) {
                    transferLabel.text = getString(R.string.transfer_label_many, index + 1, files.size)
                    val finished = CompletableDeferred<Boolean>()
                    watchFinished = finished
                    lastSendFailed = false
                    sendToWatch(sourceFor(file), useWifi && file.size > 0)
                    sendJob?.join()
                    if (userCancelled || lastSendFailed) break
                    if (!finished.await()) break
                    sent++
                }
            } finally {
                watchFinished = null
                queueActive = false
                transferLabel.text = getString(R.string.transfer_label)
                if (sent == files.size) {
                    statusText.text = getString(R.string.sent_all, files.size)
                } else if (!userCancelled) {
                    statusText.text = getString(R.string.sent_some, sent, files.size) + "\n" + statusText.text
                }
                updateControls()
            }
        }
    }

    /** Pushes the watch app bundled in this phone app to the watch, which installs it over itself. */
    private fun sendWatchAppUpdate() {
        val size = try {
            assets.openFd(WATCH_APK_ASSET).use { it.length }
        } catch (e: IOException) {
            -1L
        }
        val name = "WatchApkInstaller-watch-${BuildConfig.WATCH_VERSION_NAME}.apk"
        sendToWatch(SendSource(name, size, install = true) { assets.open(WATCH_APK_ASSET) })
    }

    private fun sendToWatch(source: SendSource, useWifi: Boolean = false) {
        val node = targetNode ?: return

        sending = true
        userCancelled = false
        watchCancellable = false
        watchReportsReceive = false
        transferStartedAt = 0L
        transferSize = source.size
        progressGroup.visibility = View.VISIBLE
        installSection.visibility = if (source.install) View.VISIBLE else View.GONE
        setTransfer(0, getString(R.string.starting))
        setInstall(0, getString(R.string.waiting))
        updateControls()

        openWatchApp(node.id)

        sendJob = lifecycleScope.launch {
            try {
                var sentOverWifi = false
                if (useWifi) {
                    sentOverWifi = sendViaWifi(node, source)
                    if (!sentOverWifi && !userCancelled) {
                        statusText.text = "Wi‑Fi didn't work. Sending over Bluetooth instead…"
                        // Keep the reason on screen after the Bluetooth transfer replaces the status text.
                        wifiProblemText.text = getString(R.string.wifi_last_problem, wifiFailureReason)
                        wifiProblemText.visibility = View.VISIBLE
                        transferStartedAt = 0L
                    }
                }
                if (!sentOverWifi) sendViaBluetooth(node, source)

                statusText.text = if (source.install) {
                    "Sent ${source.name}. Installing on your watch…"
                } else {
                    "Sent ${source.name}. Saving on your watch…"
                }
                if (!watchReportsReceive) setTransfer(100, getString(R.string.transfer_done))
                setWatchBusy(true)
            } catch (e: Exception) {
                if (userCancelled) {
                    statusText.text = "Stopped."
                    transferText.text = getString(R.string.stopped)
                    installText.text = ""
                    watchBusy = false
                    watchCancellable = false
                } else {
                    // If the watch stopped it, its PHASE_CANCELLED message replaces this text.
                    lastSendFailed = true
                    statusText.text = "Transfer failed: ${e.message ?: e.javaClass.simpleName}"
                    transferText.text = getString(R.string.failed)
                }
            } finally {
                currentChannel?.let { runCatching { Wearable.getChannelClient(this@MainActivity).close(it) } }
                currentChannel = null
                currentWifiServer?.close()
                currentWifiServer = null
                wifiConnecting = false
                sending = false
                updateControls()
            }
        }
    }

    /** The original method: stream over the Bluetooth Data Layer. */
    private suspend fun sendViaBluetooth(node: Node, source: SendSource) {
        val name = source.name
        val size = source.size
        statusText.text = "Connecting to ${node.displayName}…"
        val channelClient = Wearable.getChannelClient(this)
        val path = if (source.install) Protocol.CHANNEL_PATH else Protocol.FILE_CHANNEL_PATH
        val channel = channelClient.openChannel(node.id, path).await()
        currentChannel = channel
        val output = channelClient.getOutputStream(channel).await()
        statusText.text = "Sending $name over Bluetooth…"

        withContext(Dispatchers.IO) {
            source.open().use { src ->
                DataOutputStream(BufferedOutputStream(output, BUFFER_SIZE)).use { out ->
                    out.writeInt(Protocol.MAGIC)
                    out.writeInt(Protocol.VERSION)
                    out.writeUTF(name)
                    out.writeLong(size)
                    pump(src, size) { buffer, n -> out.write(buffer, 0, n) }
                    out.flush()
                }
            }
        }
        // Closing the output stream signals end-of-file; the watch closes the channel, so
        // don't close it from our side (that could cut off data still in flight).
        currentChannel = null
    }

    /**
     * Fast method: the watch downloads the file from a one-shot server on this phone over the
     * local network. Returns false (without sending anything) if the watch couldn't connect, so
     * the caller can fall back to Bluetooth; [wifiFailureReason] then says why.
     */
    private suspend fun sendViaWifi(node: Node, source: SendSource): Boolean {
        val hosts = WifiSendServer.localAddresses()
        if (hosts.isEmpty()) {
            wifiFailureReason = "the phone isn't on Wi‑Fi or hotspot"
            return false
        }
        val server = withContext(Dispatchers.IO) { WifiSendServer() }
        currentWifiServer = server
        val offer = WifiOffer.create(source.name, source.size, source.install, hosts, server.port)

        wifiProblemText.visibility = View.GONE
        wifiConnecting = true
        wifiWatchFailed = false
        wifiFailureReason = "the watch didn't connect"
        statusText.text = "Asking the watch to join Wi‑Fi…"
        setTransfer(0, getString(R.string.joining_wifi))
        Wearable.getMessageClient(this)
            .sendMessage(node.id, Protocol.WIFI_OFFER_PATH, offer.toJson().toByteArray(Charsets.UTF_8))
            .await()

        val socket = withContext(Dispatchers.IO) {
            try {
                server.awaitWatch(offer.token, WIFI_ACCEPT_TIMEOUT_MS)
            } catch (e: IOException) {
                null
            }
        }
        wifiConnecting = false
        if (userCancelled) throw IOException("Stopped")
        if (socket == null) {
            if (!wifiWatchFailed) {
                // The watch is still trying; make it stop before we start over Bluetooth.
                suppressNextWatchCancel = true
                runCatching {
                    Wearable.getMessageClient(this).sendMessage(node.id, Protocol.CANCEL_PATH, ByteArray(0)).await()
                }
            }
            server.close()
            currentWifiServer = null
            return false
        }

        statusText.text = "Sending ${source.name} over Wi‑Fi…"
        withContext(Dispatchers.IO) {
            val out = BufferedOutputStream(socket.getOutputStream(), WIFI_BUFFER_SIZE)
            val cipher = offer.newCipher(encrypt = true)
            val mac = offer.newMac()
            val encrypted = ByteArray(WIFI_BUFFER_SIZE)
            val sent = source.open().use { src ->
                pump(src, source.size, WIFI_BUFFER_SIZE) { buffer, n ->
                    val len = cipher.update(buffer, 0, n, encrypted)
                    mac.update(encrypted, 0, len)
                    out.write(encrypted, 0, len)
                }
            }
            if (sent != source.size) throw IOException("The file changed while it was being sent")
            out.write(mac.doFinal())
            out.flush()
            // Wait for the watch's "got it" byte so we don't cut the connection early.
            runCatching { socket.getInputStream().read() }
        }
        return true
    }

    /** Copies [src] with progress updates; returns the number of bytes read. */
    private suspend fun kotlinx.coroutines.CoroutineScope.pump(
        src: InputStream,
        size: Long,
        bufferSize: Int = BUFFER_SIZE,
        write: (ByteArray, Int) -> Unit,
    ): Long {
        val buffer = ByteArray(bufferSize)
        var total = 0L
        var lastPercent = -1
        withContext(Dispatchers.Main) { if (transferStartedAt == 0L) transferStartedAt = SystemClock.elapsedRealtime() }
        while (true) {
            ensureActive()
            val read = src.read(buffer)
            if (read < 0) break
            write(buffer, read)
            total += read
            if (size > 0) {
                val percent = (total * 100 / size).toInt()
                if (percent != lastPercent) {
                    lastPercent = percent
                    val done = total
                    withContext(Dispatchers.Main) {
                        // The watch's own numbers are more accurate once they arrive.
                        if (!watchReportsReceive) setTransferProgress(percent, done)
                    }
                }
            }
        }
        return total
    }

    /**
     * Tells the watch to stop, then cuts the connection if we're still sending. Watch apps older
     * than 4.0 ignore the message, but closing the channel still stops the transfer.
     */
    private fun stopTransfer() {
        val node = targetNode ?: return
        userCancelled = true
        stopButton.isEnabled = false
        statusText.text = getString(R.string.stopping)
        lifecycleScope.launch {
            try {
                Wearable.getMessageClient(this@MainActivity)
                    .sendMessage(node.id, Protocol.CANCEL_PATH, ByteArray(0))
                    .await()
            } catch (e: Exception) {
                Log.w(TAG, "Couldn't send cancel to watch", e)
            }
            if (sending) {
                // Give the watch a moment to see the cancel before the channel closes.
                delay(500)
                currentChannel?.let { runCatching { Wearable.getChannelClient(this@MainActivity).close(it) } }
                currentWifiServer?.close()
                sendJob?.cancel()
            }
            queueJob?.cancel()
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
                // Older watch apps don't report "done"; assume it finished and move on.
                watchFinished?.complete(true)
                watchBusy = false
                updateControls()
            }
        }
        updateControls()
    }

    /** Keeps the screen on and blocks back while anything is in flight. */
    private fun updateControls() {
        val busy = sending || watchBusy || queueActive
        if (busy) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        backBlocker.isEnabled = busy
        val canStop = sending || queueActive || (watchBusy && watchCancellable)
        if (canStop && stopButton.visibility != View.VISIBLE) stopButton.isEnabled = true
        stopButton.visibility = if (canStop) View.VISIBLE else View.GONE
        pickButton.isEnabled = !busy
        updateWatchButton.isEnabled = !busy && targetNode != null
        findWatchButton.isEnabled = !busy
        sendButton.isEnabled = !busy && selectedUri != null && targetNode != null &&
            (!needsSaving() || watchCanSaveFiles())
        installSwitch.isEnabled = !busy
        methodBluetooth.isEnabled = !busy
        methodWifi.isEnabled = !busy && wifiAllowed()
    }

    companion object {
        const val ACTION_RELAYED_FILE = "com.evanrobinson.watchapk.RELAYED_FILE"
        private const val TAG = "WatchApkInstaller"
        private const val BUFFER_SIZE = 64 * 1024
        private const val WATCH_SILENCE_TIMEOUT_MS = 120_000L
        private const val VERSION_REPLY_TIMEOUT_MS = 4_000L
        private const val WIFI_ACCEPT_TIMEOUT_MS = 60_000
        private const val WIFI_BUFFER_SIZE = 256 * 1024
        private const val WIFI_DEFAULT_THRESHOLD = 10L * 1024 * 1024
        private const val BLUETOOTH_BYTES_PER_SEC = 150L * 1024
        private const val WATCH_APK_ASSET = "watch-app.apk"
    }
}
