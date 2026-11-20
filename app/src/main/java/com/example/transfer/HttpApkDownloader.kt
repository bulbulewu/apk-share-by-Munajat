package com.example.transfer

import android.content.Context
import android.util.Log
import androidx.annotation.StringRes
import com.example.R
import com.example.apk.ApkStorageManager
import com.example.model.ApkBundleMetadata
import com.example.model.ApkFileEntry
import com.example.model.TransferProgressState
import com.example.service.NotificationHelper
import com.example.service.TransferForegroundService
import com.example.util.FileTransferHelper
import com.example.util.FormatUtils
import com.example.util.LocaleHelper
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
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
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
        val progress: TransferProgressState,
        val statusNotice: String? = null
    ) : HttpDownloadState

    data class Completed(
        val url: String,
        val metadata: ApkBundleMetadata,
        val savedFiles: List<File>,
        val displayLocation: String,
        val durationMillis: Long
    ) : HttpDownloadState

    data class Cancelled(
        val url: String
    ) : HttpDownloadState

    data class Error(
        val url: String,
        val reason: String,
        val interruptedRecord: InterruptedTransferRecord? = null
    ) : HttpDownloadState
}

class HttpApkDownloader(
    private val context: Context,
    private val storageManager: ApkStorageManager,
    val resumeStore: InterruptedTransferStore = InterruptedTransferStore(context)
) {
    private fun str(@StringRes id: Int, vararg args: Any): String {
        val loc = LocaleHelper.localizedContext(context)
        return if (args.isEmpty()) loc.getString(id) else loc.getString(id, *args)
    }

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
    @Volatile
    private var activeCall: Call? = null
    @Volatile
    private var activeUrl: String? = null
    @Volatile
    private var activeDestDir: File? = null
    @Volatile
    private var activePartFile: File? = null
    @Volatile
    private var cancelledByUser: Boolean = false
    @Volatile
    private var preserveOnCancel: Boolean = false

    fun startDownload(rawInputUrl: String, exportToPublicDownloads: Boolean) {
        val normalized = normalizeUrl(rawInputUrl)
        if (normalized == null) {
            _state.value = HttpDownloadState.Error(
                rawInputUrl,
                str(R.string.error_http_invalid_url)
            )
            return
        }

        cancelledByUser = false
        preserveOnCancel = false
        activeCall?.cancel()
        activeCall = null
        activeJob?.cancel()
        activeUrl = normalized
        activeJob = scope.launch {
            val startMs = System.currentTimeMillis()
            val speedTracker = FormatUtils.RollingTransferSpeedTracker()
            speedTracker.reset(startMs, 0L)
            _state.value = HttpDownloadState.Connecting(normalized)

            var currentMetadata: ApkBundleMetadata? = null
            var currentDestDir: File? = null
            val plannedSafeNames = LinkedHashMap<String, String>()
            var statusNotice: String? = null

            try {
                val savedRecord = resumeStore.loadValid()?.takeIf {
                    it.mode == InterruptedTransferStore.MODE_HOTSPOT
                }
                val baseUrl = extractBaseUrl(normalized)
                val manifest = fetchManifestIfAvailable(baseUrl)

                val metadata: ApkBundleMetadata
                val fileUrls: List<Pair<String, String>>

                if (manifest != null) {
                    metadata = manifest
                    fileUrls = manifest.files.mapIndexed { idx, entry ->
                        val endpoint = when {
                            manifest.isGenericFile && idx == 0 -> "$baseUrl/file"
                            !manifest.isSplitApk && idx == 0 -> "$baseUrl/app.apk"
                            else -> "$baseUrl/split/$idx/${entry.fileName}"
                        }
                        entry.fileName to endpoint
                    }
                } else {
                    val directUrl = if (normalized.endsWith("/")) "${normalized}app.apk" else normalized
                    val guessedName = directUrl.substringAfterLast('/').substringBefore('?')
                        .takeIf { it.endsWith(".apk", ignoreCase = true) } ?: "received_app.apk"
                    val contentLength = probeContentLength(directUrl)
                    metadata = ApkBundleMetadata(
                        transferId = savedRecord?.metadata?.transferId ?: "http_${System.currentTimeMillis()}",
                        appName = guessedName.removeSuffix(".apk"),
                        packageName = "",
                        versionName = "1.0",
                        totalSizeBytes = contentLength.coerceAtLeast(1L),
                        isSplitApk = false,
                        files = listOf(ApkFileEntry(guessedName, contentLength))
                    )
                    fileUrls = listOf(guessedName to directUrl)
                }

                currentMetadata = metadata
                storageManager.ensureSpaceOrThrow(metadata.totalSizeBytes)
                val destDir = storageManager.createDestinationDir(
                    appName = metadata.appName,
                    versionName = metadata.versionName,
                    isSplitApk = metadata.isSplitApk,
                    preserveExisting = true
                )
                currentDestDir = destDir
                activeDestDir = destDir

                // Resolve deterministic safe names matching any saved interrupted transfer
                fileUrls.forEach { (fileName, _) ->
                    val savedEntry = savedRecord?.files?.firstOrNull { it.fileName == fileName }
                    val safeName = if (savedEntry != null) {
                        savedEntry.safeFileName
                    } else if (metadata.isGenericFile) {
                        val clean = FileTransferHelper.sanitizeFileNamePreservingExtension(fileName)
                        if (File(destDir, "$clean.part").exists()) {
                            clean
                        } else {
                            val (base, ext) = FileTransferHelper.splitBaseAndExtension(clean)
                            FileTransferHelper.resolveUniqueGenericFileName(base, ext) { candidate ->
                                File(destDir, candidate).exists()
                            }
                        }
                    } else {
                        FormatUtils.sanitizeFileName(fileName.removeSuffix(".apk")) + ".apk"
                    }
                    plannedSafeNames[fileName] = safeName
                }

                // Pre-check completed files and validate existing .part files against sender identity
                var preCompletedBytes = 0L
                var prePartialBytes = 0L
                val alreadyCompletedIndices = HashSet<Int>()

                fileUrls.forEachIndexed { idx, (fileName, _) ->
                    val safeName = plannedSafeNames[fileName]!!
                    val expectedSize = metadata.files.getOrNull(idx)?.sizeBytes ?: 0L
                    val finalFile = File(destDir, safeName)
                    val partFile = File(destDir, "$safeName.part")
                    val savedEntry = savedRecord?.files?.firstOrNull { it.fileName == fileName }

                    val identityChanged = savedEntry != null &&
                        expectedSize > 0L &&
                        savedEntry.totalSizeBytes > 0L &&
                        savedEntry.totalSizeBytes != expectedSize

                    if (identityChanged) {
                        if (partFile.exists()) runCatching { partFile.delete() }
                        if (metadata.isSplitApk && finalFile.exists()) runCatching { finalFile.delete() }
                        statusNotice = str(R.string.transfer_resume_file_changed_restart)
                    } else if (finalFile.exists() && expectedSize > 0L && finalFile.length() == expectedSize &&
                        (metadata.isSplitApk || savedEntry?.completed == true)
                    ) {
                        alreadyCompletedIndices.add(idx)
                        preCompletedBytes += finalFile.length()
                    } else if (partFile.exists()) {
                        val existingLen = partFile.length()
                        if (expectedSize > 0L && existingLen > expectedSize) {
                            runCatching { partFile.delete() }
                            statusNotice = str(R.string.transfer_resume_file_changed_restart)
                        } else if (existingLen > 0L) {
                            prePartialBytes += existingLen
                        }
                    }
                }

                val initialBytes = (preCompletedBytes + prePartialBytes).coerceAtMost(metadata.totalSizeBytes)
                speedTracker.reset(startMs, initialBytes)

                TransferForegroundService.startOrUpdate(
                    context,
                    str(R.string.notification_downloading_title, metadata.appName),
                    str(R.string.notification_connecting_hotspot),
                    progressPercent = if (metadata.totalSizeBytes > 0L) {
                        ((initialBytes * 100L) / metadata.totalSizeBytes).toInt().coerceIn(0, 100)
                    } else 0,
                    indeterminate = false
                )

                var accumulatedCompletedBytes = 0L
                val downloadedFiles = mutableListOf<File>()
                var lastNotifMs = 0L
                var lastUiMs = 0L
                var completedCount = 0

                _state.value = HttpDownloadState.Downloading(
                    url = normalized,
                    metadata = metadata,
                    progress = TransferProgressState(
                        bytesTransferred = initialBytes,
                        totalBytes = metadata.totalSizeBytes,
                        speedBytesPerSec = 0L,
                        etaSeconds = -1L,
                        currentFileName = fileUrls.firstOrNull()?.first.orEmpty(),
                        completedFiles = alreadyCompletedIndices.size,
                        totalFiles = fileUrls.size
                    ),
                    statusNotice = statusNotice
                )

                fileUrls.forEachIndexed { idx, (fileName, fileUrl) ->
                    if (cancelledByUser) throw CancellationException("Download cancelled by user")

                    val safeName = plannedSafeNames[fileName]!!
                    val partFile = File(destDir, "$safeName.part")
                    val finalFile = File(destDir, safeName)
                    val entryExpectedSize = metadata.files.getOrNull(idx)?.sizeBytes ?: 0L

                    if (alreadyCompletedIndices.contains(idx) && finalFile.exists()) {
                        downloadedFiles += finalFile
                        accumulatedCompletedBytes += finalFile.length()
                        completedCount++
                        return@forEachIndexed
                    }

                    activePartFile = partFile

                    var attemptRestartFromZero = false
                    var finishedThisFile = false
                    while (!finishedThisFile) {
                        if (cancelledByUser) throw CancellationException("Download cancelled by user")

                        val existingBytes = if (!attemptRestartFromZero && partFile.exists()) {
                            partFile.length()
                        } else {
                            0L
                        }
                        val reqBuilder = Request.Builder().url(fileUrl)
                        if (existingBytes > 0L) {
                            reqBuilder.header("Range", "bytes=$existingBytes-")
                        }

                        val call = client.newCall(reqBuilder.build())
                        activeCall = call
                        var needRetryFromZero = false

                        call.execute().use { resp ->
                            if (resp.code == 416 && existingBytes > 0L && !attemptRestartFromZero) {
                                // Range not satisfiable (file shrunk/changed on sender) -> restart from 0
                                runCatching { partFile.delete() }
                                statusNotice = str(R.string.transfer_resume_file_changed_restart)
                                needRetryFromZero = true
                                return@use
                            }

                            if (!resp.isSuccessful && resp.code != 206) {
                                throw IOException("Server returned HTTP ${resp.code} for $fileName")
                            }

                            var appendMode = false
                            if (existingBytes > 0L && resp.code == 206) {
                                val rangeValid = validateContentRange(
                                    header = resp.header("Content-Range"),
                                    expectedStart = existingBytes,
                                    expectedTotal = entryExpectedSize
                                )
                                if (rangeValid) {
                                    appendMode = true
                                } else {
                                    runCatching { partFile.delete() }
                                    statusNotice = str(R.string.transfer_resume_file_changed_restart)
                                    needRetryFromZero = true
                                    return@use
                                }
                            } else if (existingBytes > 0L && resp.code == 200) {
                                // Server ignored Range header; restart this file from zero cleanly
                                appendMode = false
                                statusNotice = str(R.string.transfer_resume_server_no_range)
                            }

                            val body = resp.body ?: throw IOException("Empty response body from sender")
                            val bodyLen = body.contentLength()
                            val fileExpectedTotal = when {
                                appendMode && bodyLen > 0L -> existingBytes + bodyLen
                                !appendMode && bodyLen > 0L -> bodyLen
                                entryExpectedSize > 0L -> entryExpectedSize
                                else -> -1L
                            }
                            val overallTotal = metadata.totalSizeBytes.coerceAtLeast(
                                accumulatedCompletedBytes + fileExpectedTotal.coerceAtLeast(0L)
                            )

                            var currentFileBytes = if (appendMode) existingBytes else 0L
                            val nowAtStart = System.currentTimeMillis()
                            speedTracker.reset(nowAtStart, accumulatedCompletedBytes + currentFileBytes)

                            // Immediately reflect resumed offset and any notice in UI
                            _state.value = HttpDownloadState.Downloading(
                                url = normalized,
                                metadata = metadata,
                                progress = TransferProgressState(
                                    bytesTransferred = (accumulatedCompletedBytes + currentFileBytes)
                                        .coerceAtMost(overallTotal),
                                    totalBytes = overallTotal,
                                    speedBytesPerSec = 0L,
                                    etaSeconds = -1L,
                                    currentFileName = safeName,
                                    completedFiles = completedCount,
                                    totalFiles = fileUrls.size,
                                    elapsedMillis = (nowAtStart - startMs).coerceAtLeast(1L)
                                ),
                                statusNotice = statusNotice
                            )

                            var bytesSincePersist = 0L
                            body.byteStream().use { input ->
                                FileOutputStream(partFile, appendMode).use { output ->
                                    val buffer = ByteArray(65536)
                                    while (true) {
                                        if (cancelledByUser) {
                                            throw CancellationException("Download cancelled by user")
                                        }
                                        val read = input.read(buffer)
                                        if (read <= 0) break
                                        output.write(buffer, 0, read)
                                        currentFileBytes += read
                                        bytesSincePersist += read

                                        if (bytesSincePersist >= PERSIST_CHUNK_THRESHOLD_BYTES) {
                                            bytesSincePersist = 0L
                                            output.flush()
                                            persistInterruptedSnapshot(
                                                metadata = metadata,
                                                destDir = destDir,
                                                url = normalized,
                                                plannedSafeNames = plannedSafeNames
                                            )
                                        }

                                        val now = System.currentTimeMillis()
                                        val totalTransferred = (accumulatedCompletedBytes + currentFileBytes)
                                            .coerceAtMost(overallTotal)
                                        val rollingSpeed = speedTracker.record(totalTransferred, now)
                                        val uiInterval = if (powerSaverEnabled) {
                                            1000L
                                        } else {
                                            FormatUtils.LIVE_STATS_UPDATE_INTERVAL_MILLIS
                                        }
                                        val notifInterval = if (powerSaverEnabled) 1800L else 450L
                                        if (now - lastUiMs < uiInterval &&
                                            (fileExpectedTotal <= 0L || currentFileBytes < fileExpectedTotal)
                                        ) {
                                            continue
                                        }
                                        lastUiMs = now

                                        val elapsedMs = (now - startMs).coerceAtLeast(1L)
                                        val remaining = (overallTotal - totalTransferred).coerceAtLeast(0L)
                                        val etaSec = FormatUtils.computeEtaSeconds(remaining, rollingSpeed)

                                        val progress = TransferProgressState(
                                            bytesTransferred = totalTransferred,
                                            totalBytes = overallTotal,
                                            speedBytesPerSec = rollingSpeed,
                                            etaSeconds = etaSec,
                                            currentFileName = safeName,
                                            completedFiles = completedCount,
                                            totalFiles = fileUrls.size,
                                            elapsedMillis = elapsedMs
                                        )
                                        _state.value = HttpDownloadState.Downloading(
                                            url = normalized,
                                            metadata = metadata,
                                            progress = progress,
                                            statusNotice = statusNotice
                                        )

                                        if (now - lastNotifMs > notifInterval) {
                                            lastNotifMs = now
                                            TransferForegroundService.startOrUpdate(
                                                context,
                                                str(
                                                    R.string.notification_downloading_progress,
                                                    metadata.appName,
                                                    progress.progressPercent
                                                ),
                                                "${FormatUtils.formatBytes(totalTransferred)} / ${FormatUtils.formatBytes(overallTotal)} • ${FormatUtils.formatLiveSpeed(rollingSpeed)}",
                                                progressPercent = progress.progressPercent,
                                                indeterminate = false
                                            )
                                        }
                                    }
                                    output.flush()
                                }
                            }

                            if (cancelledByUser) {
                                throw CancellationException("Download cancelled by user")
                            }

                            val actualLen = partFile.length()
                            if (fileExpectedTotal > 0L && actualLen < fileExpectedTotal) {
                                // Connection ended prematurely; keep .part intact for Resume
                                throw IOException(str(R.string.error_http_download_failed))
                            }
                            if (fileExpectedTotal > 0L && actualLen > fileExpectedTotal) {
                                // Oversized/corrupt file — discard and fail safely
                                runCatching { partFile.delete() }
                                throw IOException(str(R.string.transfer_resume_file_changed_restart))
                            }

                            if (finalFile.exists()) finalFile.delete()
                            if (!partFile.renameTo(finalFile)) {
                                partFile.copyTo(finalFile, overwrite = true)
                                partFile.delete()
                            }
                            activePartFile = null
                            downloadedFiles += finalFile
                            accumulatedCompletedBytes += finalFile.length()
                            completedCount++
                            finishedThisFile = true
                        }
                        activeCall = null

                        if (needRetryFromZero) {
                            attemptRestartFromZero = true
                        }
                    }
                }

                if (cancelledByUser) {
                    throw CancellationException("Download cancelled by user")
                }

                resumeStore.clear(deletePartialFiles = false)

                val displayPath = storageManager.exportCopiesToPublicDownloads(
                    files = downloadedFiles,
                    appName = metadata.appName,
                    versionName = metadata.versionName,
                    isSplitApk = metadata.isSplitApk,
                    enabled = exportToPublicDownloads,
                    mimeType = if (metadata.isGenericFile) {
                        metadata.mimeType ?: FileTransferHelper.guessMimeTypeFromFileName(
                            downloadedFiles.firstOrNull()?.name ?: metadata.appName
                        )
                    } else {
                        null
                    }
                )
                activeDestDir = null
                val duration = (System.currentTimeMillis() - startMs).coerceAtLeast(500L)
                TransferForegroundService.stop(context)
                NotificationHelper.showResultNotification(
                    context,
                    NotificationHelper.NOTIFICATION_ID_RESULT,
                    str(R.string.notification_downloaded_title, metadata.appName),
                    str(R.string.notification_downloaded_body, displayPath)
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
                if (cancelledByUser) {
                    cleanupPartialDownloadFiles()
                    resumeStore.clear(deletePartialFiles = true)
                    _state.value = HttpDownloadState.Cancelled(normalized)
                } else if (preserveOnCancel) {
                    if (currentMetadata != null && currentDestDir != null) {
                        persistInterruptedSnapshot(
                            metadata = currentMetadata,
                            destDir = currentDestDir,
                            url = normalized,
                            plannedSafeNames = plannedSafeNames
                        )
                    }
                } else {
                    cleanupPartialDownloadFiles()
                    _state.value = HttpDownloadState.Idle
                }
                throw ce
            } catch (t: Throwable) {
                if (cancelledByUser) {
                    cleanupPartialDownloadFiles()
                    resumeStore.clear(deletePartialFiles = true)
                    TransferForegroundService.stop(context)
                    _state.value = HttpDownloadState.Cancelled(normalized)
                } else {
                    Log.e(TAG, "HTTP download failed", t)
                    TransferForegroundService.stop(context)
                    val interrupted = if (currentMetadata != null && currentDestDir != null) {
                        persistInterruptedSnapshot(
                            metadata = currentMetadata,
                            destDir = currentDestDir,
                            url = normalized,
                            plannedSafeNames = plannedSafeNames
                        )
                    } else {
                        resumeStore.loadValid()?.takeIf { it.mode == InterruptedTransferStore.MODE_HOTSPOT }
                    }
                    val friendly = when {
                        t.message?.contains("Failed to connect", ignoreCase = true) == true ||
                            t.message?.contains("timed out", ignoreCase = true) == true ->
                            str(R.string.error_http_cannot_reach, normalized)
                        else -> t.message ?: str(R.string.error_http_download_failed)
                    }
                    _state.value = HttpDownloadState.Error(
                        url = normalized,
                        reason = friendly,
                        interruptedRecord = interrupted
                    )
                }
            } finally {
                activeCall = null
            }
        }
    }

    private fun validateContentRange(
        header: String?,
        expectedStart: Long,
        expectedTotal: Long
    ): Boolean {
        if (header.isNullOrBlank()) return true
        // Format: "bytes <start>-<end>/<total>"
        val clean = header.trim()
        if (!clean.startsWith("bytes ", ignoreCase = true)) return false
        val rangeAndTotal = clean.substringAfter(" ").trim()
        val startStr = rangeAndTotal.substringBefore("-").trim()
        val totalStr = rangeAndTotal.substringAfter("/", "").trim()
        val parsedStart = startStr.toLongOrNull() ?: return false
        if (parsedStart != expectedStart) return false
        val parsedTotal = totalStr.toLongOrNull()
        if (parsedTotal != null && expectedTotal > 0L && parsedTotal != expectedTotal) {
            return false
        }
        return true
    }

    private fun persistInterruptedSnapshot(
        metadata: ApkBundleMetadata,
        destDir: File,
        url: String,
        plannedSafeNames: Map<String, String>
    ): InterruptedTransferRecord? {
        val fileRecords = metadata.files.map { entry ->
            val safeName = plannedSafeNames[entry.fileName]
                ?: if (metadata.isGenericFile) {
                    FileTransferHelper.sanitizeFileNamePreservingExtension(entry.fileName)
                } else {
                    FormatUtils.sanitizeFileName(entry.fileName.removeSuffix(".apk")) + ".apk"
                }
            val finalFile = File(destDir, safeName)
            val partFile = File(destDir, "$safeName.part")
            when {
                finalFile.exists() && entry.sizeBytes > 0L && finalFile.length() == entry.sizeBytes -> {
                    InterruptedFileRecord(
                        fileName = entry.fileName,
                        safeFileName = safeName,
                        totalSizeBytes = entry.sizeBytes,
                        receivedBytes = entry.sizeBytes,
                        completed = true
                    )
                }
                partFile.exists() -> {
                    InterruptedFileRecord(
                        fileName = entry.fileName,
                        safeFileName = safeName,
                        totalSizeBytes = entry.sizeBytes,
                        receivedBytes = partFile.length(),
                        completed = false
                    )
                }
                else -> {
                    InterruptedFileRecord(
                        fileName = entry.fileName,
                        safeFileName = safeName,
                        totalSizeBytes = entry.sizeBytes,
                        receivedBytes = 0L,
                        completed = false
                    )
                }
            }
        }
        val totalReceived = fileRecords.sumOf { it.receivedBytes }
        val allDone = fileRecords.isNotEmpty() && fileRecords.all { it.completed }
        if (totalReceived <= 0L || allDone) {
            return null
        }
        val record = InterruptedTransferRecord(
            mode = InterruptedTransferStore.MODE_HOTSPOT,
            peerName = "Wi-Fi Hotspot",
            hotspotUrl = url,
            metadata = metadata,
            destDirPath = destDir.absolutePath,
            files = fileRecords,
            updatedAtMillis = System.currentTimeMillis()
        )
        resumeStore.save(record)
        return record
    }

    fun restoreInterruptedState(record: InterruptedTransferRecord) {
        val url = record.hotspotUrl.orEmpty()
        activeUrl = url
        _state.value = HttpDownloadState.Error(
            url = url,
            reason = str(R.string.transfer_resume_restored_reason, record.metadata.appName),
            interruptedRecord = record
        )
    }

    fun clearInterruptedTransfer() {
        cancelledByUser = false
        preserveOnCancel = false
        activeCall?.cancel()
        activeCall = null
        activeJob?.cancel()
        activeJob = null
        resumeStore.clear(deletePartialFiles = true)
        cleanupPartialDownloadFiles()
        TransferForegroundService.stop(context)
        _state.value = HttpDownloadState.Idle
    }

    fun stopPreservingPartial() {
        cancelledByUser = false
        preserveOnCancel = true
        activeCall?.cancel()
        activeCall = null
        activeJob?.cancel()
        activeJob = null
        TransferForegroundService.stop(context)
    }

    fun cancelActiveDownload() {
        val url = activeUrl ?: when (val s = _state.value) {
            is HttpDownloadState.Connecting -> s.url
            is HttpDownloadState.Downloading -> s.url
            is HttpDownloadState.Cancelled -> s.url
            else -> null
        }
        cancelledByUser = true
        preserveOnCancel = false
        activeCall?.cancel()
        activeCall = null
        activeJob?.cancel()
        activeJob = null
        cleanupPartialDownloadFiles()
        resumeStore.clear(deletePartialFiles = true)
        TransferForegroundService.stop(context)
        _state.value = if (!url.isNullOrBlank()) {
            HttpDownloadState.Cancelled(url)
        } else {
            HttpDownloadState.Idle
        }
    }

    fun cancel() {
        cancelledByUser = false
        preserveOnCancel = false
        activeCall?.cancel()
        activeCall = null
        activeJob?.cancel()
        activeJob = null
        cleanupPartialDownloadFiles()
        TransferForegroundService.stop(context)
        _state.value = HttpDownloadState.Idle
    }

    fun reset() {
        cancel()
    }

    private fun cleanupPartialDownloadFiles() {
        val part = activePartFile
        activePartFile = null
        if (part != null && part.exists()) {
            runCatching { part.delete() }
        }
        val dir = activeDestDir
        activeDestDir = null
        if (dir != null && dir.exists()) {
            // Only delete split-APK subfolders (never delete the root APK Share folder)
            if (dir.absolutePath != storageManager.getAppDownloadsRoot().absolutePath) {
                runCatching { dir.deleteRecursively() }
            }
        }
    }

    internal fun setStateForTest(state: HttpDownloadState) {
        _state.value = state
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
        private const val PERSIST_CHUNK_THRESHOLD_BYTES = 256L * 1024L
    }
}
