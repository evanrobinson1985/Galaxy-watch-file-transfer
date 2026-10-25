package com.evanrobinson.watchapk.mobile

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.BufferedInputStream
import java.io.DataInputStream
import java.io.File
import java.io.IOException
import java.net.ServerSocket
import java.net.Socket

/**
 * Runs in the normal profile while "Allow sending from Secure Folder" is on, receiving files
 * from the Secure Folder copy of the app (see [SecureFolderRelay]). Each received batch shows a
 * "Tap to send to watch" notification that opens [MainActivity] with the files selected.
 */
class RelayService : Service() {

    sealed interface Status {
        data object Off : Status
        data object Starting : Status
        data class Listening(val addresses: List<String>) : Status
        data class Error(val message: String) : Status
    }

    private val servers = mutableListOf<ServerSocket>()
    private var started = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            setEnabled(this, false)
            stopSelf()
            return START_NOT_STICKY
        }
        ensureChannels(this)
        ServiceCompat.startForeground(
            this,
            ONGOING_ID,
            ongoingNotification(),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0,
        )
        if (!started) {
            started = true
            startServers()
        }
        return START_STICKY
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        // Android 15 limits data-sync services to 6 hours a day; opening the app restarts it.
        stopSelf()
    }

    override fun onDestroy() {
        synchronized(servers) { servers.forEach { runCatching { it.close() } } }
        _status.value = Status.Off
        super.onDestroy()
    }

    /** One listener per loopback address (127.0.0.1 and ::1). */
    private fun startServers() {
        val errors = mutableListOf<String>()
        for (address in SecureFolderRelay.LOOPBACKS) {
            try {
                val server = ServerSocket(SecureFolderRelay.PORT, 4, address)
                synchronized(servers) { servers += server }
                Thread({ serve(server) }, "SecureFolderRelay-${address.hostAddress}").start()
            } catch (e: IOException) {
                Log.e(TAG, "Couldn't listen on ${address.hostAddress}", e)
                errors += "${address.hostAddress}: ${e.message}"
            }
        }
        _status.value = if (servers.isEmpty()) {
            showMessage("Couldn't listen for Secure Folder files: ${errors.joinToString("; ")}")
            Status.Error(errors.joinToString("; "))
        } else {
            Status.Listening(servers.map { it.inetAddress.hostAddress ?: "?" })
        }
    }

    private fun serve(server: ServerSocket) {
        while (!server.isClosed) {
            val client = try {
                server.accept()
            } catch (e: IOException) {
                break
            }
            // One file at a time, whichever address it arrived on.
            synchronized(this) { client.use { handle(it) } }
        }
    }

    private fun handle(socket: Socket) {
        socket.soTimeout = 60_000
        val dir = File(cacheDir, RELAY_DIR)
        var file: File? = null
        try {
            val input = DataInputStream(BufferedInputStream(socket.getInputStream(), 256 * 1024))
            if (input.readInt() != SecureFolderRelay.MAGIC) throw IOException("Unexpected data")
            if (input.readInt() != SecureFolderRelay.VERSION) {
                throw IOException("The copy inside Secure Folder is a different version; update both")
            }
            val index = input.readInt()
            val count = input.readInt()
            val name = input.readUTF().substringAfterLast('/').replace(Regex("[^A-Za-z0-9._ -]"), "_")
                .trimStart('.').ifEmpty { "file" }
            val size = input.readLong()

            // A new batch replaces the previous one, so the cache doesn't fill up.
            if (index == 0) dir.deleteRecursively()
            dir.mkdirs()
            val target = uniqueFile(dir, name)
            file = target
            target.outputStream().use { out -> input.copyTo(out, 256 * 1024) }
            if (size >= 0 && target.length() != size) {
                throw IOException("Incomplete (${target.length()} of $size bytes)")
            }
            socket.getOutputStream().apply {
                write(1)
                flush()
            }
            if (index == count - 1) showReady(batchFiles(this))
        } catch (e: Exception) {
            Log.w(TAG, "Relay receive failed", e)
            file?.delete()
        }
    }

    private fun uniqueFile(dir: File, name: String): File {
        var candidate = File(dir, name)
        var n = 1
        while (candidate.exists()) {
            candidate = File(dir, name.substringBeforeLast('.', name) + " ($n)" +
                name.substringAfterLast('.', "").let { if (it.isEmpty() || it == name) "" else ".$it" })
            n++
        }
        return candidate
    }

    private fun ongoingNotification(): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, RelayService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, ONGOING_CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle(getString(R.string.relay_ongoing_title))
            .setContentText(getString(R.string.relay_ongoing_text))
            .setOngoing(true)
            .setContentIntent(open)
            .addAction(0, getString(R.string.relay_stop), stop)
            .build()
    }

    private fun showReady(files: List<File>) {
        if (!canNotify() || files.isEmpty()) return
        val open = PendingIntent.getActivity(
            this,
            2,
            Intent(this, MainActivity::class.java)
                .setAction(MainActivity.ACTION_RELAYED_FILE)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = NotificationCompat.Builder(this, READY_CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_upload_done)
            .setContentTitle(getString(R.string.relay_ready_title))
            .setContentText(if (files.size == 1) files[0].name else getString(R.string.relay_ready_many, files.size))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        NotificationManagerCompat.from(this).notify(READY_ID, notification)
    }

    private fun showMessage(text: String) {
        if (!canNotify()) return
        val notification = NotificationCompat.Builder(this, READY_CHANNEL)
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .build()
        NotificationManagerCompat.from(this).notify(READY_ID, notification)
    }

    private fun canNotify(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED

    companion object {
        private const val TAG = "RelayService"
        private const val ACTION_STOP = "com.evanrobinson.watchapk.STOP_RELAY"
        private const val ONGOING_CHANNEL = "relay_listening"
        private const val READY_CHANNEL = "relay_ready"
        private const val ONGOING_ID = 10
        private const val READY_ID = 11
        private const val PREFS = "relay"
        private const val KEY_ENABLED = "enabled"
        const val RELAY_DIR = "relay"

        private val _status = MutableStateFlow<Status>(Status.Off)
        val status: StateFlow<Status> = _status.asStateFlow()

        /** The last handed-over batch, oldest first. */
        fun batchFiles(context: Context): List<File> =
            File(context.cacheDir, RELAY_DIR).listFiles()?.filter { it.isFile }?.sortedBy { it.lastModified() }.orEmpty()

        fun isEnabled(context: Context): Boolean =
            context.getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean(KEY_ENABLED, false)

        fun setEnabled(context: Context, enabled: Boolean) {
            context.getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean(KEY_ENABLED, enabled).apply()
        }

        fun start(context: Context) {
            if (_status.value !is Status.Listening) _status.value = Status.Starting
            ContextCompat.startForegroundService(context, Intent(context, RelayService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, RelayService::class.java))
        }

        private fun ensureChannels(context: Context) {
            val manager = context.getSystemService(NotificationManager::class.java)
            if (manager.getNotificationChannel(ONGOING_CHANNEL) == null) {
                manager.createNotificationChannel(
                    NotificationChannel(
                        ONGOING_CHANNEL,
                        context.getString(R.string.relay_channel_listening),
                        NotificationManager.IMPORTANCE_MIN,
                    ),
                )
            }
            if (manager.getNotificationChannel(READY_CHANNEL) == null) {
                manager.createNotificationChannel(
                    NotificationChannel(
                        READY_CHANNEL,
                        context.getString(R.string.relay_channel_ready),
                        NotificationManager.IMPORTANCE_HIGH,
                    ),
                )
            }
        }
    }
}
