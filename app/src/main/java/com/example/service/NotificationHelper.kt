package com.example.service

import android.Manifest
import android.app.Notification
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
import com.example.MainActivity
import com.example.R
import com.example.util.LocaleHelper

object NotificationHelper {

    const val CHANNEL_PROGRESS = "apk_transfer_progress"
    const val CHANNEL_ALERTS = "apk_transfer_alerts"
    const val NOTIFICATION_ID_FOREGROUND = 1001
    const val NOTIFICATION_ID_RESULT = 1002
    const val NOTIFICATION_ID_INSTALL = 1003

    fun createChannels(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val loc = LocaleHelper.localizedContext(context)
            val manager = context.getSystemService(NotificationManager::class.java) ?: return
            val progressChannel = NotificationChannel(
                CHANNEL_PROGRESS,
                loc.getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = loc.getString(R.string.notification_channel_desc)
                setShowBadge(false)
            }
            val alertsChannel = NotificationChannel(
                CHANNEL_ALERTS,
                loc.getString(R.string.notification_alerts_channel_name),
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = loc.getString(R.string.notification_alerts_channel_desc)
            }
            manager.createNotificationChannel(progressChannel)
            manager.createNotificationChannel(alertsChannel)
        }
    }

    fun buildProgressNotification(
        context: Context,
        title: String,
        contentText: String,
        progressPercent: Int,
        indeterminate: Boolean
    ): Notification {
        val openAppIntent = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val cancelIntent = PendingIntent.getService(
            context,
            1,
            Intent(context, TransferForegroundService::class.java).apply {
                action = TransferForegroundService.ACTION_CANCEL_FROM_NOTIFICATION
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val loc = LocaleHelper.localizedContext(context)
        return NotificationCompat.Builder(context, CHANNEL_PROGRESS)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(title)
            .setContentText(contentText)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(100, progressPercent.coerceIn(0, 100), indeterminate)
            .setContentIntent(openAppIntent)
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                loc.getString(R.string.action_cancel),
                cancelIntent
            )
            .build()
    }

    fun showResultNotification(
        context: Context,
        notificationId: Int,
        title: String,
        message: String,
        isError: Boolean = false
    ) {
        if (!canPostNotifications(context)) return
        createChannels(context)
        val openAppIntent = PendingIntent.getActivity(
            context,
            notificationId,
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ALERTS)
            .setSmallIcon(
                if (isError) android.R.drawable.stat_notify_error
                else android.R.drawable.stat_sys_download_done
            )
            .setContentTitle(title)
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setAutoCancel(true)
            .setContentIntent(openAppIntent)
            .build()

        runCatching {
            NotificationManagerCompat.from(context).notify(notificationId, notification)
        }
    }

    fun canPostNotifications(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
    }
}
