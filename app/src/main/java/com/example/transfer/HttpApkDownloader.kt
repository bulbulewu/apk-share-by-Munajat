package com.example.transfer

import android.content.Context
import android.util.Log
import com.example.apk.ApkStorageManager
import com.example.model.ApkBundleMetadata
import com.example.model.ApkFileEntry
import com.example.model.TransferProgressState
import com.example.service.NotificationHelper
import com.example.service.TransferForegroundService
import com.example.util.FormatUtils
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit

sealed interface HttpDownloadState {
    data object Idle : HttpDownloadState
    data class Connecting(val url: String) : HttpDownloadState
    data class Downloading(
        val url: String,
        val metadata: ApkBundleMetadata,
        val progress: TransferProgressState
    ) : HttpDownloadState

    data class Completed(
        val url: String,
        val metadata: ApkBundleMetadata,
        val savedFiles: List<File>,
        val displayLocation: String,
        val durationMillis: Long
    ) : HttpDownloadState

    data class Error(
        val url: String,
        val reason: String
    ) : HttpDownloadState
}

class HttpApkDownloader(
    private val context: Context,
    private val storageManager: ApkStorageManager
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val json = Json { ignoreUnknownKeys = true }
    private val client = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private val _state = MutableStateFlow<HttpDownloadState>(HttpDownloadState.Idle)
    val state: StateFlow<HttpDownloadState> = _state.asStateFlow()

    @Volatile
    var powerSaverEnabled: Boolean = false

    private var activeJob: Job? = null

    fun startDownload(rawInputUrl: String, exportToPublicDownloads: Boolean) {
        val normalized = normalizeUrl(rawInputUrl)
        if (normalized == null) {
            _state.value = HttpDownloadState.Error(
                rawInputUrl,
                "Invalid URL. Example: http://192.168.43.1:8080/app.apk"
            )
            return
        }

        activeJob?.cancel()
        activeJob = scope.launch {
            val startMs = System.currentTimeMillis()
            _state.value = HttpDownloadState.Connecting(normalized)
            try {
                val baseUrl = extractBaseUrl(normalized)
                val manifest = fetchManifestIfAvailable(baseUrl)

                val metadata: ApkBundleMetadata
                val fileUrls: List<Pair<String, String>>

                if (manifest != null) {
                    metadata = manifest
                    fileUrls = manifest.files.mapIndexed { idx, entry ->
                        val endpoint = if (!manifest.isSplitApk && idx == 0) {
                            "$baseUrl/app.apk"
                        } else {
                            "$baseUrl/split/$idx/${entry.fileName}"
                        }
                        entry.fileName to endpoint
                    }
                } else {
                    // Direct single APK link
                    val directUrl = if (normalized.endsWith("/")) "${normalized}app.apk" else normalized
                    val guessedName = directUrl.substringAfterLast('/').substringBefore('?')
                        .takeIf { it.endsWith(".apk", ignoreCase = true) } ?: "received_app.apk"
                    val contentLength = probeContentLength(directUrl)
                    metadata = ApkBundleMetadata(
                        transferId = "http_${System.currentTimeMillis()}",
                        appName = guessedName.removeSuffix(".apk"),
                        packageName = "",
                        versionName = "1.0",
                        totalSizeBytes = contentLength.coerceAtLeast(1L),
                        isSplitApk = false,
                        files = listOf(ApkFileEntry(guessedName, contentLength))
                    )
                    fileUrls = listOf(guessedName to directUrl)
                }

                storageManager.ensureSpaceOrThrow(metadata.totalSizeBytes)
                val destDir = storageManager.createDestinationDir(
                    metadata.appName,
                    metadata.versionName,
                    metadata.isSplitApk
                )

                TransferForegroundService.startOrUpdate(
                    context,
                    "Downloading ${metadata.appName}",
                    "Connecting via Wi-Fi Hotspot...",
                    progressPercent = 0,
                    indeterminate = false
                )

                var accumulatedCompletedBytes = 0L
                val downloadedFiles = mutableListOf<File>()
                var lastNotifMs = 0L
                var lastUiMs = 0L

                _state.value = HttpDownloadState.Downloading(
                    url = normalized,
                    metadata = metadata,
                    progress = TransferProgressState(
                        bytesTransferred = 0L,
                        totalBytes = metadata.totalSizeBytes,
                        currentFileName = fileUrls.firstOrNull()?.first.orEmpty(),
                        completedFiles = 0,
                        totalFiles = fileUrls.size
                    )
                )

                fileUrls.forEachIndexed { idx, (fileName, fileUrl) ->
                    val safeName = FormatUtils.sanitizeFileName(fileName.removeSuffix(".apk")) + ".apk"
                    val partFile = File(destDir, "$safeName.part")
                    val finalFile = File(destDir, safeName)

                    val existingBytes = if (partFile.exists()) partFile.length() else 0L
                    val reqBuilder = Request.Builder().url(fileUrl)
                    if (existingBytes > 0L) {
                        reqBuilder.header("Range", "bytes=$existingBytes-")
                    }

                    client.newCall(reqBuilder.build()).execute().use { resp ->
                        if (!resp.isSuccessful && resp.code != 206) {
                            throw IOException("Server returned HTTP ${resp.code} for $fileName")
                        }
                        val appendMode = resp.code == 206 && existingBytes > 0L
                        val body = resp.body ?: throw IOException("Empty response body from sender")
                        val bodyLen = body.contentLength()
                        val fileExpectedTotal = if (appendMode) existingBytes + bodyLen else bodyLen
                        val overallTotal = metadata.totalSizeBytes.coerceAtLeast(
                            accumulatedCompletedBytes + fileExpectedTotal
                        )

                        var currentFileBytes = if (appendMode) existingBytes else 0L
                        body.byteStream().use { input ->
                            FileOutputStream(partFile, appendMode).use { output ->
                                val buffer = ByteArray(65536)
                                while (true) {
                                    val read = input.read(buffer)
                                    if (read <= 0) break
                                    output.write(buffer, 0, read)
                                    currentFileBytes += read

                                    val now = System.currentTimeMillis()
                                    val uiInterval = if (powerSaverEnabled) 1000L else 200L
                                    val notifInterval = if (powerSaverEnabled) 1800L else 450L
                                    if (now - lastUiMs < uiInterval && currentFileBytes < fileExpectedTotal) {
                                        continue
                                    }
                                    lastUiMs = now

                                    val totalTransferred = (accumulatedCompletedBytes + currentFileBytes)
                                        .coerceAtMost(overallTotal)
                                    val elapsedMs = (now - startMs).coerceAtLeast(1L)
                                    val speed = (totalTransferred * 1000L) / elapsedMs
                                    val etaSec = if (speed > 0L) (overallTotal - totalTransferred).coerceAtLeast(0L) / speed else 0L

                                    val progress = TransferProgressState(
                                        bytesTransferred = totalTransferred,
                                        totalBytes = overallTotal,
                                        speedBytesPerSec = speed,
                                        etaSeconds = etaSec,
                                        currentFileName = safeName,
                                        completedFiles = idx,
                                        totalFiles = fileUrls.size,
                                        elapsedMillis = elapsedMs
                                    )
                                    _state.value = HttpDownloadState.Downloading(normalized, metadata, progress)

                                    if (now - lastNotifMs > notifInterval) {
                                        lastNotifMs = now
                                        TransferForegroundService.startOrUpdate(
                                            context,
                                            "Downloading ${metadata.appName} (${progress.progressPercent}%)",
                                            "${FormatUtils.formatBytes(totalTransferred)} / ${FormatUtils.formatBytes(overallTotal)} • ${FormatUtils.formatSpeed(speed)}",
                                            progressPercent = progress.progressPercent,
                                            indeterminate = false
                                        )
                                    }
                                }
                            }
                        }
                        if (finalFile.exists()) finalFile.delete()
                        if (!partFile.renameTo(finalFile)) {
                            partFile.copyTo(finalFile, overwrite = true)
                            partFile.delete()
                        }
                        downloadedFiles += finalFile
                        accumulatedCompletedBytes += finalFile.length()
                    }
                }

                val displayPath = storageManager.exportCopiesToPublicDownloads(
                    files = downloadedFiles,
                    appName = metadata.appName,
                    versionName = metadata.versionName,
                    isSplitApk = metadata.isSplitApk,
                    enabled = exportToPublicDownloads
                )
                val duration = (System.currentTimeMillis() - startMs).coerceAtLeast(500L)
                TransferForegroundService.stop(context)
                NotificationHelper.showResultNotification(
                    context,
                    NotificationHelper.NOTIFICATION_ID_RESULT,
                    "Downloaded ${metadata.appName}",
                    "Saved to $displayPath. Tap to install."
                )
                _state.value = HttpDownloadState.Completed(
                    url = normalized,
                    metadata = metadata,
                    savedFiles = downloadedFiles,
                    displayLocation = displayPath,
                    durationMillis = duration
                )
            } catch (ce: CancellationException) {
                TransferForegroundService.stop(context)
                _state.value = HttpDownloadState.Idle
                throw ce
            } catch (t: Throwable) {
                Log.e(TAG, "HTTP download failed", t)
                TransferForegroundService.stop(context)
                val friendly = when {
                    t.message?.contains("Failed to connect", ignoreCase = true) == true ||
                        t.message?.contains("timed out", ignoreCase = true) == true ->
                        "Cannot reach sender at $normalized. Make sure Phone B is connected to Phone A's Wi-Fi Hotspot."
                    else -> t.message ?: "Download failed. Tap Retry to resume."
                }
                _state.value = HttpDownloadState.Error(normalized, friendly)
            }
        }
    }

    fun cancel() {
        activeJob?.cancel()
        activeJob = null
        TransferForegroundService.stop(context)
        _state.value = HttpDownloadState.Idle
    }

    fun reset() {
        cancel()
    }

    private fun fetchManifestIfAvailable(baseUrl: String): ApkBundleMetadata? {
        return try {
            val req = Request.Builder().url("$baseUrl/manifest.json").build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return null
                val body = resp.body?.string() ?: return null
                json.decodeFromString<ApkBundleMetadata>(body)
            }
        } catch (_: Throwable) {
            null
        }
    }

    private fun probeContentLength(url: String): Long {
        return try {
            val req = Request.Builder().url(url).head().build()
            client.newCall(req).execute().use { resp ->
                resp.header("Content-Length")?.toLongOrNull() ?: 0L
            }
        } catch (_: Throwable) {
            0L
        }
    }

    private fun normalizeUrl(raw: String): String? {
        val trimmed = raw.trim()
        if (trimmed.isBlank()) return null
        val withScheme = if (trimmed.startsWith("http://", ignoreCase = true) ||
            trimmed.startsWith("https://", ignoreCase = true)
        ) {
            trimmed
        } else {
            "http://$trimmed"
        }
        return if (withScheme.contains(".")) withScheme else null
    }

    private fun extractBaseUrl(fullUrl: String): String {
        val schemeEnd = fullUrl.indexOf("://") + 3
        val pathStart = fullUrl.indexOf('/', startIndex = schemeEnd)
        return if (pathStart == -1) fullUrl else fullUrl.substring(0, pathStart)
    }

    companion object {
        private const val TAG = "HttpApkDownloader"
    }
}
