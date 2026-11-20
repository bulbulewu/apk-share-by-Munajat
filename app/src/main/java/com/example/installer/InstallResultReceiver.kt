package com.example.installer

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import android.util.Log
import com.example.service.NotificationHelper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

sealed interface InstallState {
    data object Idle : InstallState
    data class Installing(val appName: String, val detail: String) : InstallState
    data class PendingUserConfirmation(val appName: String) : InstallState
    data class Installed(val appName: String, val packageName: String) : InstallState
    data class Failed(val appName: String, val reason: String) : InstallState
}

object InstallResultBus {
    private val _state = MutableStateFlow<InstallState>(InstallState.Idle)
    val state: StateFlow<InstallState> = _state.asStateFlow()

    fun update(newState: InstallState) {
        _state.value = newState
    }

    fun reset() {
        _state.value = InstallState.Idle
    }
}

class InstallResultReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        if (intent == null) return
        val appName = intent.getStringExtra(EXTRA_APP_NAME) ?: "App"
        val packageName = intent.getStringExtra(EXTRA_PACKAGE_NAME).orEmpty()
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, -999)
        val rawMsg = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE).orEmpty()

        Log.i(TAG, "Install callback for $packageName ($appName): status=$status msg=$rawMsg")

        when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirmIntent: Intent? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(Intent.EXTRA_INTENT)
                }
                if (confirmIntent != null) {
                    InstallResultBus.update(InstallState.PendingUserConfirmation(appName))
                    confirmIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    runCatching {
                        context.startActivity(confirmIntent)
                    }.onFailure { err ->
                        Log.e(TAG, "Failed to launch system install dialog", err)
                        InstallResultBus.update(
                            InstallState.Failed(
                                appName,
                                "Could not open Android installer prompt: ${err.message}"
                            )
                        )
                    }
                } else {
                    InstallResultBus.update(
                        InstallState.Failed(appName, "Android installer prompt was missing.")
                    )
                }
            }

            PackageInstaller.STATUS_SUCCESS -> {
                InstallResultBus.update(InstallState.Installed(appName, packageName))
                NotificationHelper.showResultNotification(
                    context = context,
                    notificationId = NotificationHelper.NOTIFICATION_ID_INSTALL,
                    title = "Installed $appName",
                    message = "Tap to return to APK Share and open the app."
                )
            }

            PackageInstaller.STATUS_FAILURE_ABORTED -> {
                val reason = "Installation was cancelled by user."
                InstallResultBus.update(InstallState.Failed(appName, reason))
            }

            PackageInstaller.STATUS_FAILURE_BLOCKED -> {
                val reason = "Install blocked by device security or Play Protect. $rawMsg".trim()
                InstallResultBus.update(InstallState.Failed(appName, reason))
                NotificationHelper.showResultNotification(
                    context,
                    NotificationHelper.NOTIFICATION_ID_INSTALL,
                    "Install Blocked: $appName",
                    reason,
                    isError = true
                )
            }

            PackageInstaller.STATUS_FAILURE_CONFLICT -> {
                val reason = "App conflicts with an existing installed version (different signature). Uninstall the old version first and try again."
                InstallResultBus.update(InstallState.Failed(appName, reason))
            }

            PackageInstaller.STATUS_FAILURE_INCOMPATIBLE -> {
                val reason = "This APK is not compatible with this phone's CPU or Android version."
                InstallResultBus.update(InstallState.Failed(appName, reason))
            }

            PackageInstaller.STATUS_FAILURE_INVALID -> {
                val reason = "Invalid or corrupted APK file. Try transferring again."
                InstallResultBus.update(InstallState.Failed(appName, reason))
            }

            PackageInstaller.STATUS_FAILURE_STORAGE -> {
                val reason = "Not enough free storage on this phone to install $appName."
                InstallResultBus.update(InstallState.Failed(appName, reason))
            }

            else -> {
                val reason = if (rawMsg.isNotBlank()) "Install failed: $rawMsg" else "Install failed (code $status)."
                InstallResultBus.update(InstallState.Failed(appName, reason))
                NotificationHelper.showResultNotification(
                    context,
                    NotificationHelper.NOTIFICATION_ID_INSTALL,
                    "Install Failed: $appName",
                    reason,
                    isError = true
                )
            }
        }
    }

    companion object {
        private const val TAG = "InstallResultReceiver"
        const val EXTRA_APP_NAME = "extra_app_name"
        const val EXTRA_PACKAGE_NAME = "extra_package_name"
    }
}
