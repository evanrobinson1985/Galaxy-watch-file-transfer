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
import java.io.BufferedInputStream
import java.io.DataInputStream
import java.io.File
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket

/**
 * Runs in the normal profile while "Allow sending from Secure Folder" is on, receiving files
 * from the Secure Folder copy of the app (see [SecureFolderRelay]). Each received file shows a
 * "Tap to send to watch" notification that opens [MainActivity] with the file selected.
 */
class RelayService : Service() {

    @Volatile
    private var server: ServerSocket? = null
    private var thread: Thread? = null

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
        if (thread == null) {
            thread = Thread(::serve, "SecureFolderRelay").apply { start() }
        }
        return START_STICKY
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        // Android 15 limits data-sync services to 6 hours a day; opening the app restarts it.
        stopSelf()
    }

    override fun onDestroy() {
        runCatching { server?.close() }
        thread = null
        super.onDestroy()
    }

    private fun serve() {
        try {
            ServerSocket(SecureFolderRelay.PORT, 1, InetAddress.getLoopbackAddress()).use { s ->
                server = s
                while (!s.isClosed) {
                    val client = try {
                        s.accept()
                    } catch (e: IOException) {
                        break
                    }
                    client.use { handle(it) }
                }
            }
        } catch (e: IOException) {
            Log.e(TAG, "Relay server failed", e)
            showMessage("Couldn't listen for Secure Folder files: ${e.message}")
        }
    }

    private fun handle(socket: Socket) {
        socket.soTimeout = 60_000
        val dir = File(cacheDir, RELAY_DIR)
        var file: File? = null
        try {
            val input = DataInputStream(BufferedInputStream(socket.getInputStream(), 256 * 1024))
            if (input.readInt() != SecureFolderRelay.MAGIC || input.readInt() != SecureFolderRelay.VERSION) {
                throw IOException("Unexpected data")
            }
            val name = input.readUTF().substringAfterLast('/').replace(Regex("[^A-Za-z0-9._ -]"), "_")
                .trimStart('.').ifEmpty { "file" }
            val size = input.readLong()

            // Only keep the latest handed-over file, so the cache doesn't fill up.
            dir.deleteRecursively()
            dir.mkdirs()
            val target = File(dir, name)
            file = target
            target.outputStream().use { out -> input.copyTo(out, 256 * 1024) }
            if (size >= 0 && target.length() != size) {
                throw IOException("Incomplete (${target.length()} of $size bytes)")
            }
            socket.getOutputStream().apply {
                write(1)
                flush()
            }
            showReady(target)
        } catch (e: Exception) {
            Log.w(TAG, "Relay receive failed", e)
            file?.delete()
        }
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

    private fun showReady(file: File) {
        if (!canNotify()) return
        val open = PendingIntent.getActivity(
            this,
            2,
            Intent(this, MainActivity::class.java)
                .setAction(MainActivity.ACTION_RELAYED_FILE)
                .putExtra(MainActivity.EXTRA_RELAYED_PATH, file.absolutePath)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = NotificationCompat.Builder(this, READY_CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_upload_done)
            .setContentTitle(getString(R.string.relay_ready_title))
            .setContentText(file.name)
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

        fun isEnabled(context: Context): Boolean =
            context.getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean(KEY_ENABLED, false)

        fun setEnabled(context: Context, enabled: Boolean) {
            context.getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean(KEY_ENABLED, enabled).apply()
        }

        fun start(context: Context) {
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
