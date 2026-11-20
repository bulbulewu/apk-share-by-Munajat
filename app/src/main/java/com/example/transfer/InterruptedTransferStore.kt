package com.example.transfer

import android.content.Context
import android.util.Log
import com.example.model.ApkBundleMetadata
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

@Serializable
data class InterruptedFileRecord(
    val fileName: String,
    val safeFileName: String,
    val totalSizeBytes: Long,
    val receivedBytes: Long,
    val completed: Boolean = false
)

@Serializable
data class InterruptedTransferRecord(
    val mode: String, // "NEARBY" | "HOTSPOT_HTTP"
    val peerName: String,
    val senderPairCode: String? = null,
    val hotspotUrl: String? = null,
    val metadata: ApkBundleMetadata,
    val destDirPath: String,
    val files: List<InterruptedFileRecord>,
    val updatedAtMillis: Long = System.currentTimeMillis()
) {
    val totalReceivedBytes: Long
        get() = files.sumOf { rec ->
            if (rec.totalSizeBytes > 0L) {
                rec.receivedBytes.coerceIn(0L, rec.totalSizeBytes)
            } else {
                rec.receivedBytes.coerceAtLeast(0L)
            }
        }.coerceAtMost(metadata.totalSizeBytes.coerceAtLeast(0L))

    val totalSizeBytes: Long
        get() = metadata.totalSizeBytes.coerceAtLeast(
            files.sumOf { it.totalSizeBytes.coerceAtLeast(0L) }
        )
}

/**
 * Persists metadata for an interrupted Nearby or Hotspot transfer alongside its `.part` files
 * so transfers can survive disconnects and full app restarts.
 */
class InterruptedTransferStore(private val context: Context) {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private val stateFile: File
        get() = File(context.filesDir, STATE_FILE_NAME)

    @Synchronized
    fun save(record: InterruptedTransferRecord) {
        try {
            val target = stateFile
            target.parentFile?.mkdirs()
            val tmp = File(target.parentFile, "$STATE_FILE_NAME.tmp")
            tmp.writeText(json.encodeToString(record), Charsets.UTF_8)
            if (target.exists()) target.delete()
            if (!tmp.renameTo(target)) {
                tmp.copyTo(target, overwrite = true)
                tmp.delete()
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to persist interrupted transfer state: ${t.message}")
        }
    }

    @Synchronized
    fun loadRaw(): InterruptedTransferRecord? {
        val file = stateFile
        if (!file.exists()) return null
        return try {
            json.decodeFromString<InterruptedTransferRecord>(file.readText(Charsets.UTF_8))
        } catch (t: Throwable) {
            Log.w(TAG, "Corrupt interrupted transfer state, clearing: ${t.message}")
            runCatching { file.delete() }
            null
        }
    }

    /**
     * Loads the saved interrupted transfer and reconciles each file's [InterruptedFileRecord.receivedBytes]
     * against the actual `.part` / completed files on disk.
     *
     * Returns `null` (and cleans up stale state) if no partial data remains or if the transfer
     * is already completely present on disk.
     */
    @Synchronized
    fun loadValid(): InterruptedTransferRecord? {
        val raw = loadRaw() ?: return null
        val destDir = File(raw.destDirPath)
        if (!destDir.exists()) {
            clear(deletePartialFiles = false)
            return null
        }

        val refreshedFiles = raw.files.map { fileRec ->
            val finalFile = File(destDir, fileRec.safeFileName)
            val partFile = File(destDir, "${fileRec.safeFileName}.part")
            when {
                finalFile.exists() && fileRec.totalSizeBytes > 0L && finalFile.length() == fileRec.totalSizeBytes -> {
                    fileRec.copy(receivedBytes = fileRec.totalSizeBytes, completed = true)
                }
                partFile.exists() -> {
                    val partLen = partFile.length()
                    if (fileRec.totalSizeBytes > 0L && partLen > fileRec.totalSizeBytes) {
                        // Oversized / corrupt partial file — delete it safely
                        runCatching { partFile.delete() }
                        fileRec.copy(receivedBytes = 0L, completed = false)
                    } else {
                        fileRec.copy(receivedBytes = partLen, completed = false)
                    }
                }
                else -> {
                    fileRec.copy(receivedBytes = 0L, completed = false)
                }
            }
        }

        val hasAnyPartialOrCompleted = refreshedFiles.any { it.receivedBytes > 0L }
        val allCompleted = refreshedFiles.isNotEmpty() && refreshedFiles.all { it.completed }
        if (!hasAnyPartialOrCompleted || allCompleted) {
            clear(deletePartialFiles = false)
            return null
        }

        val refreshed = raw.copy(files = refreshedFiles)
        if (refreshed != raw) {
            save(refreshed)
        }
        return refreshed
    }

    @Synchronized
    fun clear(deletePartialFiles: Boolean = true) {
        if (deletePartialFiles) {
            val existing = loadRaw()
            if (existing != null) {
                val destDir = File(existing.destDirPath)
                if (destDir.exists()) {
                    existing.files.forEach { fileRec ->
                        val partFile = File(destDir, "${fileRec.safeFileName}.part")
                        if (partFile.exists()) {
                            runCatching { partFile.delete() }
                        }
                        if (existing.metadata.isSplitApk) {
                            val finalFile = File(destDir, fileRec.safeFileName)
                            if (finalFile.exists()) {
                                runCatching { finalFile.delete() }
                            }
                        }
                    }
                    if (existing.metadata.isSplitApk) {
                        val remaining = destDir.listFiles()
                        if (remaining == null || remaining.isEmpty()) {
                            runCatching { destDir.delete() }
                        }
                    }
                }
            }
        }
        runCatching { stateFile.delete() }
    }

    companion object {
        const val MODE_NEARBY = "NEARBY"
        const val MODE_HOTSPOT = "HOTSPOT_HTTP"
        private const val STATE_FILE_NAME = "interrupted_transfer_state.json"
        private const val TAG = "InterruptedTransferStore"
    }
}
