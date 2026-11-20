package com.example.installer

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

class ApkInstaller(private val context: Context) {

    fun canRequestPackageInstalls(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.packageManager.canRequestPackageInstalls()
        } else {
            true
        }
    }

    fun openUnknownAppSourcesSettings() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val intent = Intent(
                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:${context.packageName}")
            ).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            runCatching {
                context.startActivity(intent)
            }.onFailure { err ->
                Log.e(TAG, "Failed to open unknown app sources settings", err)
            }
        }
    }

    fun isAppInstalled(packageName: String): Boolean {
        if (packageName.isBlank()) return false
        return runCatching {
            context.packageManager.getPackageInfo(packageName, 0)
            true
        }.getOrDefault(false)
    }

    fun launchInstalledApp(packageName: String): Boolean {
        if (packageName.isBlank()) return false
        val launchIntent = context.packageManager.getLaunchIntentForPackage(packageName) ?: return false
        launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return runCatching {
            context.startActivity(launchIntent)
            true
        }.getOrElse {
            Log.e(TAG, "Failed to launch $packageName", it)
            false
        }
    }

    /**
     * Installs either a single APK (via FileProvider + ACTION_VIEW)
     * or a Split APK bundle (via PackageInstaller.Session API).
     */
    suspend fun installApkFiles(
        apkFiles: List<File>,
        appName: String,
        packageName: String,
        isSplitApk: Boolean
    ) {
        if (apkFiles.isEmpty()) {
            InstallResultBus.update(InstallState.Failed(appName, "No APK files found to install."))
            return
        }
        val missing = apkFiles.firstOrNull { !it.exists() || it.length() <= 0L }
        if (missing != null) {
            InstallResultBus.update(
                InstallState.Failed(appName, "File not found or empty: ${missing.name}")
            )
            return
        }

        if (!canRequestPackageInstalls()) {
            InstallResultBus.update(
                InstallState.Failed(
                    appName,
                    "Permission required: allow Install unknown apps for APK Share."
                )
            )
            return
        }

        if (!isSplitApk && apkFiles.size == 1) {
            installSingleApkWithFileProvider(apkFiles.first(), appName)
        } else {
            installSplitBundleWithPackageInstaller(apkFiles, appName, packageName)
        }
    }

    private fun installSingleApkWithFileProvider(apkFile: File, appName: String) {
        try {
            InstallResultBus.update(
                InstallState.Installing(appName, "Opening Android system installer...")
            )
            val authority = "${context.packageName}.fileprovider"
            val contentUri = FileProvider.getUriForFile(context, authority, apkFile)
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(contentUri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            Log.i(TAG, "Launching ACTION_VIEW installer for ${apkFile.name} uri=$contentUri")
            context.startActivity(intent)
            InstallResultBus.update(InstallState.PendingUserConfirmation(appName))
        } catch (t: Throwable) {
            Log.e(TAG, "Single APK install failed for ${apkFile.name}", t)
            InstallResultBus.update(
                InstallState.Failed(
                    appName,
                    "Unable to launch system installer: ${t.message ?: "Unknown error"}"
                )
            )
        }
    }

    private suspend fun installSplitBundleWithPackageInstaller(
        apkFiles: List<File>,
        appName: String,
        packageName: String
    ) = withContext(Dispatchers.IO) {
        var session: PackageInstaller.Session? = null
        try {
            InstallResultBus.update(
                InstallState.Installing(
                    appName,
                    "Preparing ${apkFiles.size} APK splits for system installer..."
                )
            )
            val packageInstaller = context.packageManager.packageInstaller
            val totalSize = apkFiles.sumOf { it.length() }
            val params = PackageInstaller.SessionParams(
                PackageInstaller.SessionParams.MODE_FULL_INSTALL
            ).apply {
                if (packageName.isNotBlank()) {
                    setAppPackageName(packageName)
                }
                setSize(totalSize)
            }

            val sessionId = packageInstaller.createSession(params)
            session = packageInstaller.openSession(sessionId)

            apkFiles.forEachIndexed { index, file ->
                InstallResultBus.update(
                    InstallState.Installing(
                        appName,
                        "Writing split ${index + 1}/${apkFiles.size}: ${file.name}"
                    )
                )
                val entryName = file.name.ifBlank { "split_$index.apk" }
                session.openWrite(entryName, 0, file.length()).use { out ->
                    file.inputStream().use { input ->
                        input.copyTo(out, bufferSize = 65536)
                    }
                    session.fsync(out)
                }
            }

            val receiverIntent = Intent(context, InstallResultReceiver::class.java).apply {
                action = "com.example.action.INSTALL_COMPLETE_${sessionId}"
                putExtra(InstallResultReceiver.EXTRA_APP_NAME, appName)
                putExtra(InstallResultReceiver.EXTRA_PACKAGE_NAME, packageName)
            }
            val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
            } else {
                PendingIntent.FLAG_UPDATE_CURRENT
            }
            val pendingIntent = PendingIntent.getBroadcast(
                context,
                sessionId,
                receiverIntent,
                flags
            )

            Log.i(TAG, "Committing PackageInstaller sessionId=$sessionId for $packageName")
            session.commit(pendingIntent.intentSender)
        } catch (e: IOException) {
            Log.e(TAG, "IO failure writing split APKs", e)
            runCatching { session?.abandon() }
            InstallResultBus.update(
                InstallState.Failed(appName, "Failed to read/write split APKs: ${e.message}")
            )
        } catch (t: Throwable) {
            Log.e(TAG, "PackageInstaller session failed", t)
            runCatching { session?.abandon() }
            InstallResultBus.update(
                InstallState.Failed(
                    appName,
                    "Split APK install failed: ${t.message ?: "Unknown error"}"
                )
            )
        } finally {
            runCatching { session?.close() }
        }
    }

    companion object {
        private const val TAG = "ApkInstaller"
    }
}
