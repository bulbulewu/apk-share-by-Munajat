package com.example.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.ServiceCompat
import com.example.R
import com.example.util.LocaleHelper
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

class TransferForegroundService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        NotificationHelper.createChannels(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_CANCEL_FROM_NOTIFICATION -> {
                Log.i(TAG, "Cancel requested from notification")
                _cancelRequests.tryEmit(Unit)
                ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_START_OR_UPDATE -> {
                val loc = LocaleHelper.localizedContext(this)
                val title = intent.getStringExtra(EXTRA_TITLE)
                    ?: loc.getString(R.string.notification_default_title)
                val text = intent.getStringExtra(EXTRA_TEXT)
                    ?: loc.getString(R.string.notification_default_text)
                val progress = intent.getIntExtra(EXTRA_PROGRESS, 0)
                val indeterminate = intent.getBooleanExtra(EXTRA_INDETERMINATE, true)
                val notification = NotificationHelper.buildProgressNotification(
                    context = this,
                    title = title,
                    contentText = text,
                    progressPercent = progress,
                    indeterminate = indeterminate
                )
                try {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        ServiceCompat.startForeground(
                            this,
                            NotificationHelper.NOTIFICATION_ID_FOREGROUND,
                            notification,
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                            } else {
                                0
                            }
                        )
                    } else {
                        startForeground(NotificationHelper.NOTIFICATION_ID_FOREGROUND, notification)
                    }
                } catch (t: Throwable) {
                    Log.w(TAG, "Foreground service start warning: ${t.message}")
                }
            }
        }
        return START_NOT_STICKY
    }

    companion object {
        private const val TAG = "TransferFgs"
        const val ACTION_START_OR_UPDATE = "com.example.action.TRANSFER_START_UPDATE"
        const val ACTION_STOP = "com.example.action.TRANSFER_STOP"
        const val ACTION_CANCEL_FROM_NOTIFICATION = "com.example.action.TRANSFER_CANCEL"

        private const val EXTRA_TITLE = "extra_title"
        private const val EXTRA_TEXT = "extra_text"
        private const val EXTRA_PROGRESS = "extra_progress"
        private const val EXTRA_INDETERMINATE = "extra_indeterminate"

        private val _cancelRequests = MutableSharedFlow<Unit>(extraBufferCapacity = 4)
        val cancelRequests = _cancelRequests.asSharedFlow()

        fun startOrUpdate(
            context: Context,
            title: String,
            text: String,
            progressPercent: Int = 0,
            indeterminate: Boolean = true
        ) {
            val intent = Intent(context, TransferForegroundService::class.java).apply {
                action = ACTION_START_OR_UPDATE
                putExtra(EXTRA_TITLE, title)
                putExtra(EXTRA_TEXT, text)
                putExtra(EXTRA_PROGRESS, progressPercent)
                putExtra(EXTRA_INDETERMINATE, indeterminate)
            }
            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            }.onFailure {
                Log.w(TAG, "Unable to start foreground service: ${it.message}")
            }
        }

        fun stop(context: Context) {
            runCatching {
                context.stopService(Intent(context, TransferForegroundService::class.java))
            }
        }
    }
}
