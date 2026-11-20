package com.example.update

import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.util.Log
import android.widget.Toast
import androidx.core.content.FileProvider
import com.example.R
import com.example.installer.ApkInstaller
import com.example.installer.InstallResultBus
import com.example.installer.InstallState
import com.example.util.FormatUtils
import com.example.util.LocaleHelper
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Downloads a release APK via Android [DownloadManager] into `Downloads/APK Share`,
 * tracks real-time progress, installs automatically via the existing [ApkInstaller],
 * and opens the GitHub releases page in the browser if download or install fails.
 */
class AppUpdateDownloader(
    private val context: Context,
    private val apkInstaller: ApkInstaller
) {

    private val _state = MutableStateFlow<UpdateDownloadState>(UpdateDownloadState.Idle)
    val state: StateFlow<UpdateDownloadState> = _state.asStateFlow()

    private var monitorJob: Job? = null

    fun startUpdateDownload(
        release: GitHubReleaseInfo,
        scope: CoroutineScope
    ) {
        val downloadUrl = release.apkDownloadUrl
        if (downloadUrl.isNullOrBlank()) {
            triggerBrowserFallback(release.releasePageUrl)
            return
        }

        monitorJob?.cancel()
        monitorJob = scope.launch {
            val localizedCtx = LocaleHelper.localizedContext(context)
            val safeFileName = sanitizeApkFileName(release.apkFileName, release.normalizedVersion)

            val downloadManager = context.getSystemService(Context.DOWNLOAD_SERVICE) as? DownloadManager
            if (downloadManager == null) {
                triggerBrowserFallback(release.releasePageUrl)
                return@launch
            }

            val downloadId = enqueueDownload(
                downloadManager = downloadManager,
                downloadUrl = downloadUrl,
                fileName = safeFileName,
                displayTitle = localizedCtx.getString(R.string.app_name),
                displayDescription = localizedCtx.getString(
                    R.string.update_notification_desc,
                    release.tagName
                )
            )

            if (downloadId < 0L) {
                triggerBrowserFallback(release.releasePageUrl)
                return@launch
            }

            _state.value = UpdateDownloadState.Downloading(
                versionName = release.tagName,
                progressPercent = 0,
                downloadedBytes = 0L,
                totalBytes = -1L
            )

            pollDownloadUntilFinished(
                downloadManager = downloadManager,
                downloadId = downloadId,
                safeFileName = safeFileName,
                release = release
            )
        }
    }

    fun retryInstallAfterPermission(scope: CoroutineScope) {
        val current = _state.value as? UpdateDownloadState.PermissionRequired ?: return
        scope.launch {
            installDownloadedApk(
                apkFile = current.apkFile,
                versionName = current.versionName,
                releasePageUrl = current.releasePageUrl
            )
        }
    }

    fun openInstallPermissionSettings() {
        apkInstaller.openUnknownAppSourcesSettings()
    }

    fun openFallbackBrowser(url: String = UpdateConfig.RELEASES_PAGE_URL) {
        triggerBrowserFallback(url)
    }

    fun reset() {
        monitorJob?.cancel()
        monitorJob = null
        _state.value = UpdateDownloadState.Idle
    }

    private fun enqueueDownload(
        downloadManager: DownloadManager,
        downloadUrl: String,
        fileName: String,
        displayTitle: String,
        displayDescription: String
    ): Long {
        val uri = runCatching { Uri.parse(downloadUrl) }.getOrNull() ?: return -1L
        val subPath = "${UpdateConfig.DOWNLOADS_SUBFOLDER}/$fileName"

        // Primary path: public Downloads/APK Share folder
        val publicAttempt = runCatching {
            val request = buildDownloadRequest(uri, displayTitle, displayDescription).apply {
                setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, subPath)
            }
            downloadManager.enqueue(request)
        }
        if (publicAttempt.isSuccess) {
            return publicAttempt.getOrDefault(-1L)
        }
        Log.w(TAG, "Public Downloads/APK Share enqueue failed, retrying app downloads dir", publicAttempt.exceptionOrNull())

        // Secondary fallback (legacy devices without WRITE_EXTERNAL_STORAGE)
        val appDirAttempt = runCatching {
            val request = buildDownloadRequest(uri, displayTitle, displayDescription).apply {
                setDestinationInExternalFilesDir(context, Environment.DIRECTORY_DOWNLOADS, subPath)
            }
            downloadManager.enqueue(request)
        }
        if (appDirAttempt.isSuccess) {
            return appDirAttempt.getOrDefault(-1L)
        }
        Log.e(TAG, "DownloadManager enqueue failed completely", appDirAttempt.exceptionOrNull())
        return -1L
    }

    private fun buildDownloadRequest(
        uri: Uri,
        displayTitle: String,
        displayDescription: String
    ): DownloadManager.Request {
        return DownloadManager.Request(uri).apply {
            setTitle(displayTitle)
            setDescription(displayDescription)
            setMimeType("application/vnd.android.package-archive")
            setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            setAllowedOverMetered(true)
            setAllowedOverRoaming(true)
        }
    }

    private suspend fun pollDownloadUntilFinished(
        downloadManager: DownloadManager,
        downloadId: Long,
        safeFileName: String,
        release: GitHubReleaseInfo
    ) {
        try {
            while (kotlin.coroutines.coroutineContext.isActive) {
                val snapshot = withContext(Dispatchers.IO) {
                    queryDownloadStatus(downloadManager, downloadId)
                }

                if (snapshot == null) {
                    Log.w(TAG, "Download $downloadId disappeared from DownloadManager")
                    triggerBrowserFallback(release.releasePageUrl)
                    return
                }

                when (snapshot.status) {
                    DownloadManager.STATUS_SUCCESSFUL -> {
                        val installableFile = withContext(Dispatchers.IO) {
                            resolveInstallableApkFile(
                                downloadManager = downloadManager,
                                downloadId = downloadId,
                                localUriString = snapshot.localUri,
                                safeFileName = safeFileName
                            )
                        }
                        if (installableFile == null) {
                            Log.e(TAG, "Could not resolve downloaded APK file for downloadId=$downloadId")
                            triggerBrowserFallback(release.releasePageUrl)
                            return
                        }
                        installDownloadedApk(
                            apkFile = installableFile,
                            versionName = release.tagName,
                            releasePageUrl = release.releasePageUrl
                        )
                        return
                    }

                    DownloadManager.STATUS_FAILED -> {
                        Log.e(TAG, "DownloadManager reported STATUS_FAILED (reason=${snapshot.reason})")
                        triggerBrowserFallback(release.releasePageUrl)
                        return
                    }

                    else -> {
                        val percent = if (snapshot.totalBytes > 0L) {
                            ((snapshot.downloadedBytes * 100L) / snapshot.totalBytes)
                                .toInt()
                                .coerceIn(0, 100)
                        } else {
                            -1
                        }
                        _state.value = UpdateDownloadState.Downloading(
                            versionName = release.tagName,
                            progressPercent = percent,
                            downloadedBytes = snapshot.downloadedBytes.coerceAtLeast(0L),
                            totalBytes = snapshot.totalBytes
                        )
                    }
                }

                delay(400L)
            }
        } catch (ce: CancellationException) {
            throw ce
        } catch (t: Throwable) {
            Log.e(TAG, "Error monitoring update download", t)
            triggerBrowserFallback(release.releasePageUrl)
        }
    }

    private suspend fun installDownloadedApk(
        apkFile: File,
        versionName: String,
        releasePageUrl: String
    ) {
        if (!apkInstaller.canRequestPackageInstalls()) {
            _state.value = UpdateDownloadState.PermissionRequired(
                apkFile = apkFile,
                versionName = versionName,
                releasePageUrl = releasePageUrl
            )
            apkInstaller.openUnknownAppSourcesSettings()
            return
        }

        _state.value = UpdateDownloadState.Installing(versionName)
        InstallResultBus.reset()
        val appName = LocaleHelper.localizedContext(context).getString(R.string.app_name)
        apkInstaller.installApkFiles(
            apkFiles = listOf(apkFile),
            appName = appName,
            packageName = context.packageName,
            isSplitApk = false
        )

        val installOutcome = InstallResultBus.state.value
        if (installOutcome is InstallState.Failed) {
            Log.e(TAG, "ApkInstaller failed for update APK: ${installOutcome.reason}")
            triggerBrowserFallback(releasePageUrl)
        } else {
            _state.value = UpdateDownloadState.Idle
        }
    }

    private fun resolveInstallableApkFile(
        downloadManager: DownloadManager,
        downloadId: Long,
        localUriString: String?,
        safeFileName: String
    ): File? {
        val authority = "${context.packageName}.fileprovider"

        // 1) Try direct File path from DownloadManager if readable and accepted by FileProvider
        if (!localUriString.isNullOrBlank()) {
            val parsedUri = runCatching { Uri.parse(localUriString) }.getOrNull()
            if (parsedUri?.scheme.equals("file", ignoreCase = true)) {
                val path = parsedUri?.path
                if (!path.isNullOrBlank()) {
                    val directFile = File(path)
                    if (directFile.isFile && directFile.canRead() && directFile.length() > 0L) {
                        val providerOk = runCatching {
                            FileProvider.getUriForFile(context, authority, directFile)
                        }.isSuccess
                        if (providerOk) return directFile
                    }
                }
            }
        }

        // 2) Copy safely via DownloadManager's content URI / ParcelFileDescriptor into cacheDir/app_updates
        val updateCacheDir = File(context.cacheDir, "app_updates").apply { mkdirs() }
        val cachedFile = File(updateCacheDir, safeFileName)

        val contentUri = runCatching { downloadManager.getUriForDownloadedFile(downloadId) }.getOrNull()
        if (contentUri != null) {
            val copied = runCatching {
                context.contentResolver.openInputStream(contentUri)?.use { input ->
                    cachedFile.outputStream().use { out ->
                        input.copyTo(out, bufferSize = 65536)
                    }
                }
                cachedFile.isFile && cachedFile.length() > 0L
            }.getOrDefault(false)
            if (copied) return cachedFile
        }

        val pfdCopied = runCatching {
            downloadManager.openDownloadedFile(downloadId)?.use { pfd ->
                android.os.ParcelFileDescriptor.AutoCloseInputStream(pfd).use { input ->
                    cachedFile.outputStream().use { out ->
                        input.copyTo(out, bufferSize = 65536)
                    }
                }
            }
            cachedFile.isFile && cachedFile.length() > 0L
        }.getOrDefault(false)

        return if (pfdCopied) cachedFile else null
    }

    private fun triggerBrowserFallback(releasePageUrl: String) {
        _state.value = UpdateDownloadState.FallbackOpened
        openReleasePageInBrowser(context, releasePageUrl)
        _state.value = UpdateDownloadState.Idle
    }

    private data class DownloadSnapshot(
        val status: Int,
        val reason: Int,
        val downloadedBytes: Long,
        val totalBytes: Long,
        val localUri: String?
    )

    private fun queryDownloadStatus(
        downloadManager: DownloadManager,
        downloadId: Long
    ): DownloadSnapshot? {
        val query = DownloadManager.Query().setFilterById(downloadId)
        downloadManager.query(query)?.use { cursor ->
            if (!cursor.moveToFirst()) return null
            val statusIdx = cursor.getColumnIndex(DownloadManager.COLUMN_STATUS)
            val reasonIdx = cursor.getColumnIndex(DownloadManager.COLUMN_REASON)
            val downloadedIdx = cursor.getColumnIndex(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)
            val totalIdx = cursor.getColumnIndex(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)
            val localUriIdx = cursor.getColumnIndex(DownloadManager.COLUMN_LOCAL_URI)
            return DownloadSnapshot(
                status = if (statusIdx >= 0) cursor.getInt(statusIdx) else DownloadManager.STATUS_FAILED,
                reason = if (reasonIdx >= 0) cursor.getInt(reasonIdx) else 0,
                downloadedBytes = if (downloadedIdx >= 0) cursor.getLong(downloadedIdx) else 0L,
                totalBytes = if (totalIdx >= 0) cursor.getLong(totalIdx) else -1L,
                localUri = if (localUriIdx >= 0) cursor.getString(localUriIdx) else null
            )
        }
        return null
    }

    private fun sanitizeApkFileName(rawName: String, version: String): String {
        val base = rawName.removeSuffix(".apk").removeSuffix(".APK")
        val safe = FormatUtils.sanitizeFileName(base.ifBlank { "APK-Share-v$version" })
        return "$safe.apk"
    }

    companion object {
        private const val TAG = "AppUpdateDownloader"

        fun openReleasePageInBrowser(
            context: Context,
            url: String = UpdateConfig.RELEASES_PAGE_URL
        ): Boolean {
            val targetUrl = url.takeIf { it.startsWith("https://", ignoreCase = true) }
                ?: UpdateConfig.RELEASES_PAGE_URL
            val localizedCtx = LocaleHelper.localizedContext(context)
            runCatching {
                Toast.makeText(
                    localizedCtx,
                    localizedCtx.getString(R.string.update_fallback_browser_toast),
                    Toast.LENGTH_LONG
                ).show()
            }
            return runCatching {
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(targetUrl)).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
                true
            }.getOrElse { err ->
                Log.e(TAG, "Failed to open GitHub releases page in browser", err)
                false
            }
        }
    }
}
