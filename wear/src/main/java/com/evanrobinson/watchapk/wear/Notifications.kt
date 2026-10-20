package com.evanrobinson.watchapk.wear

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import java.io.File

object Notifications {
    private const val CHANNEL_ID = "incoming_apks"
    private const val PROGRESS_CHANNEL_ID = "transfer_progress"
    private const val RECEIVING_ID = 1
    private const val UPDATED_ID = 2
    private const val SAVED_ID = 3

    private var lastReceivingPercent = -1

    fun idFor(file: File): Int = file.name.hashCode()

    /** Ongoing progress notification, shown while receiving with the app off screen. */
    @Synchronized
    fun showReceiving(context: Context, name: String, percent: Int) {
        if (!canPost(context)) return
        // Notifications are rate limited, so only update every 5%.
        if (percent >= 0 && lastReceivingPercent >= 0 && percent < lastReceivingPercent + 5) return
        lastReceivingPercent = percent
        ensureChannels(context)

        val open = PendingIntent.getActivity(
            context,
            RECEIVING_ID,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = NotificationCompat.Builder(context, PROGRESS_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Receiving ${if (percent >= 0) "$percent%" else "…"}")
            .setContentText(name)
            .setProgress(100, percent.coerceAtLeast(0), percent < 0)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setContentIntent(open)
            .build()
        NotificationManagerCompat.from(context).notify(RECEIVING_ID, notification)
    }

    @Synchronized
    fun cancelReceiving(context: Context) {
        lastReceivingPercent = -1
        NotificationManagerCompat.from(context).cancel(RECEIVING_ID)
    }

    fun showReadyToInstall(context: Context, file: File, nodeId: String?) {
        if (!canPost(context)) return
        ensureChannels(context)

        val tap = PendingIntent.getActivity(
            context,
            idFor(file),
            MainActivity.installIntent(context, file, nodeId),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Tap to install")
            .setContentText(file.name)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_RECOMMENDATION)
            .setContentIntent(tap)
            .setAutoCancel(true)
            .build()
        NotificationManagerCompat.from(context).notify(idFor(file), notification)
    }

    fun showSaved(context: Context, name: String) {
        if (!canPost(context)) return
        ensureChannels(context)

        val open = PendingIntent.getActivity(
            context,
            SAVED_ID,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("File saved")
            .setContentText("$name → ${ReceivedFiles.FOLDER_LABEL}")
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        NotificationManagerCompat.from(context).notify(SAVED_ID, notification)
    }

    fun showUpdated(context: Context, versionName: String) {
        if (!canPost(context)) return
        ensureChannels(context)

        val open = PendingIntent.getActivity(
            context,
            UPDATED_ID,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(R.string.app_name) + " updated")
            .setContentText("Now on v$versionName")
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        NotificationManagerCompat.from(context).notify(UPDATED_ID, notification)
    }

    fun cancel(context: Context, file: File) {
        NotificationManagerCompat.from(context).cancel(idFor(file))
    }

    private fun canPost(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED

    private fun ensureChannels(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, context.getString(R.string.channel_name), NotificationManager.IMPORTANCE_HIGH),
            )
        }
        if (manager.getNotificationChannel(PROGRESS_CHANNEL_ID) == null) {
            manager.createNotificationChannel(
                NotificationChannel(
                    PROGRESS_CHANNEL_ID,
                    context.getString(R.string.progress_channel_name),
                    NotificationManager.IMPORTANCE_LOW,
                ),
            )
        }
    }
}
