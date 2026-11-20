package com.example.transfer

import com.example.model.ApkBundleMetadata
import com.example.model.ResumeFileOffset
import com.example.util.FileTransferHelper
import com.example.util.FormatUtils
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream

data class SenderFilePlan(
    val offsetBytes: Long,
    val alreadyCompleted: Boolean,
    val restartedFromZero: Boolean
)

data class ReceiverResumePlan(
    val safeNamesByOriginal: Map<String, String>,
    val alreadyCompletedFiles: List<File>,
    val completedFileNames: Set<String>,
    val resumeOffsets: List<ResumeFileOffset>,
    val initialReceivedBytes: Long,
    val hadInvalidPartial: Boolean
)

sealed interface StreamReceiveOutcome {
    data class Completed(val finalFile: File) : StreamReceiveOutcome
    data class Interrupted(val receivedBytes: Long) : StreamReceiveOutcome
    data object Cancelled : StreamReceiveOutcome
    data object InvalidPartialRestart : StreamReceiveOutcome
    data object CorruptOversized : StreamReceiveOutcome
}

/**
 * Pure, testable helpers for offset-based Nearby resume:
 * - Sender identity check & byte-offset seeking (sends only remaining bytes)
 * - Receiver `.part` reconciliation & corruption guard
 * - Incremental streaming directly into `.part` with append + final size verification before rename
 */
object NearbyResumeCodec {

    private const val STREAM_BUFFER_SIZE = 65536
    private const val PERSIST_INTERVAL_BYTES = 256L * 1024L

    fun resolveSenderFilePlan(file: File, requested: ResumeFileOffset?): SenderFilePlan {
        val fileLen = file.length()
        if (requested == null) {
            return SenderFilePlan(offsetBytes = 0L, alreadyCompleted = false, restartedFromZero = false)
        }
        // Identity check: same total size required before honoring offset
        if (requested.totalSizeBytes != fileLen || fileLen <= 0L) {
            return SenderFilePlan(offsetBytes = 0L, alreadyCompleted = false, restartedFromZero = true)
        }
        return when {
            requested.receivedBytes == fileLen -> {
                SenderFilePlan(offsetBytes = fileLen, alreadyCompleted = true, restartedFromZero = false)
            }
            requested.receivedBytes in 1L until fileLen -> {
                SenderFilePlan(
                    offsetBytes = requested.receivedBytes,
                    alreadyCompleted = false,
                    restartedFromZero = false
                )
            }
            requested.receivedBytes > fileLen -> {
                SenderFilePlan(offsetBytes = 0L, alreadyCompleted = false, restartedFromZero = true)
            }
            else -> {
                SenderFilePlan(offsetBytes = 0L, alreadyCompleted = false, restartedFromZero = false)
            }
        }
    }

    fun openOffsetInputStream(file: File, offsetBytes: Long): InputStream {
        val fis = FileInputStream(file)
        if (offsetBytes > 0L) {
            fis.channel.position(offsetBytes)
        }
        return fis
    }

    fun planReceiverFiles(
        metadata: ApkBundleMetadata,
        destDir: File,
        savedRecord: InterruptedTransferRecord?
    ): ReceiverResumePlan {
        val safeNames = LinkedHashMap<String, String>()
        val alreadyCompleted = mutableListOf<File>()
        val completedNames = HashSet<String>()
        val offsets = mutableListOf<ResumeFileOffset>()
        var initialBytes = 0L
        var hadInvalidPartial = false

        metadata.files.forEach { entry ->
            val savedEntry = savedRecord?.files?.firstOrNull { it.fileName == entry.fileName }
            val safeName = if (savedEntry != null) {
                savedEntry.safeFileName
            } else if (metadata.isGenericFile) {
                val clean = FileTransferHelper.sanitizeFileNamePreservingExtension(entry.fileName)
                if (File(destDir, "$clean.part").exists()) {
                    clean
                } else {
                    val (base, ext) = FileTransferHelper.splitBaseAndExtension(clean)
                    FileTransferHelper.resolveUniqueGenericFileName(base, ext) { candidate ->
                        File(destDir, candidate).exists()
                    }
                }
            } else {
                FormatUtils.sanitizeFileName(entry.fileName.removeSuffix(".apk")) + ".apk"
            }
            safeNames[entry.fileName] = safeName

            val finalFile = File(destDir, safeName)
            val partFile = File(destDir, "$safeName.part")

            val identityChanged = savedEntry != null &&
                entry.sizeBytes > 0L &&
                savedEntry.totalSizeBytes > 0L &&
                savedEntry.totalSizeBytes != entry.sizeBytes

            if (identityChanged) {
                if (partFile.exists()) runCatching { partFile.delete() }
                if (metadata.isSplitApk && finalFile.exists()) runCatching { finalFile.delete() }
                hadInvalidPartial = true
            } else if (finalFile.exists() && entry.sizeBytes > 0L && finalFile.length() == entry.sizeBytes &&
                (metadata.isSplitApk || savedEntry?.completed == true)
            ) {
                alreadyCompleted.add(finalFile)
                completedNames.add(entry.fileName)
                initialBytes += entry.sizeBytes
                offsets.add(
                    ResumeFileOffset(
                        fileName = entry.fileName,
                        receivedBytes = entry.sizeBytes,
                        totalSizeBytes = entry.sizeBytes
                    )
                )
            } else if (partFile.exists()) {
                val existingLen = partFile.length()
                if (entry.sizeBytes > 0L && existingLen > entry.sizeBytes) {
                    runCatching { partFile.delete() }
                    hadInvalidPartial = true
                } else if (existingLen > 0L && (entry.sizeBytes <= 0L || existingLen < entry.sizeBytes)) {
                    initialBytes += existingLen
                    offsets.add(
                        ResumeFileOffset(
                            fileName = entry.fileName,
                            receivedBytes = existingLen,
                            totalSizeBytes = entry.sizeBytes
                        )
                    )
                } else if (entry.sizeBytes > 0L && existingLen == entry.sizeBytes) {
                    // Partial file actually holds all bytes — finalize it immediately
                    if (finalFile.exists()) finalFile.delete()
                    val renamed = partFile.renameTo(finalFile) || run {
                        partFile.copyTo(finalFile, overwrite = true)
                        partFile.delete()
                        true
                    }
                    if (renamed && finalFile.length() == entry.sizeBytes) {
                        alreadyCompleted.add(finalFile)
                        completedNames.add(entry.fileName)
                        initialBytes += entry.sizeBytes
                        offsets.add(
                            ResumeFileOffset(
                                fileName = entry.fileName,
                                receivedBytes = entry.sizeBytes,
                                totalSizeBytes = entry.sizeBytes
                            )
                        )
                    }
                }
            }
        }

        return ReceiverResumePlan(
            safeNamesByOriginal = safeNames,
            alreadyCompletedFiles = alreadyCompleted,
            completedFileNames = completedNames,
            resumeOffsets = offsets,
            initialReceivedBytes = initialBytes.coerceAtMost(metadata.totalSizeBytes.coerceAtLeast(initialBytes)),
            hadInvalidPartial = hadInvalidPartial
        )
    }

    fun receiveStreamIntoPartFile(
        input: InputStream,
        destDir: File,
        safeName: String,
        expectedTotalSize: Long,
        offsetBytes: Long,
        restartedFromZero: Boolean,
        isCancelled: () -> Boolean,
        onProgress: (totalFileBytes: Long) -> Unit = {},
        onPersistChunk: () -> Unit = {}
    ): StreamReceiveOutcome {
        val tempPart = File(destDir, "$safeName.part")
        val outFile = File(destDir, safeName)

        val appendMode: Boolean
        val baseOffset: Long
        if (restartedFromZero || offsetBytes <= 0L) {
            if (tempPart.exists()) runCatching { tempPart.delete() }
            appendMode = false
            baseOffset = 0L
        } else {
            if (!tempPart.exists() ||
                tempPart.length() != offsetBytes ||
                (expectedTotalSize > 0L && offsetBytes >= expectedTotalSize)
            ) {
                if (tempPart.exists()) runCatching { tempPart.delete() }
                return StreamReceiveOutcome.InvalidPartialRestart
            }
            appendMode = true
            baseOffset = offsetBytes
        }

        var writtenThisStream = 0L
        var bytesSincePersist = 0L
        var streamError: Throwable? = null

        try {
            FileOutputStream(tempPart, appendMode).use { output ->
                val buffer = ByteArray(STREAM_BUFFER_SIZE)
                while (true) {
                    if (isCancelled()) {
                        break
                    }
                    val read = input.read(buffer)
                    if (read <= 0) break
                    output.write(buffer, 0, read)
                    writtenThisStream += read
                    bytesSincePersist += read
                    onProgress(baseOffset + writtenThisStream)
                    if (bytesSincePersist >= PERSIST_INTERVAL_BYTES) {
                        bytesSincePersist = 0L
                        output.flush()
                        onPersistChunk()
                    }
                }
                output.flush()
            }
        } catch (t: Throwable) {
            streamError = t
        }

        if (isCancelled()) {
            runCatching { tempPart.delete() }
            return StreamReceiveOutcome.Cancelled
        }

        val actualLen = if (tempPart.exists()) tempPart.length() else 0L
        if (streamError != null) {
            // Keep .part intact for Resume unless oversized/corrupt
            if (expectedTotalSize > 0L && actualLen > expectedTotalSize) {
                runCatching { tempPart.delete() }
                return StreamReceiveOutcome.CorruptOversized
            }
            return StreamReceiveOutcome.Interrupted(actualLen)
        }

        if (expectedTotalSize > 0L && actualLen < expectedTotalSize) {
            // Stream ended early — keep .part file intact for Resume
            return StreamReceiveOutcome.Interrupted(actualLen)
        }
        if (expectedTotalSize > 0L && actualLen > expectedTotalSize) {
            runCatching { tempPart.delete() }
            return StreamReceiveOutcome.CorruptOversized
        }

        if (outFile.exists()) outFile.delete()
        if (!tempPart.renameTo(outFile)) {
            tempPart.copyTo(outFile, overwrite = true)
            tempPart.delete()
        }
        return StreamReceiveOutcome.Completed(outFile)
    }

    fun extractPairCode(endpointName: String): String? {
        val hashIdx = endpointName.lastIndexOf('#')
        if (hashIdx == -1 || hashIdx >= endpointName.lastIndex) return null
        val candidate = endpointName.substring(hashIdx + 1).trim().uppercase()
        return candidate.takeIf { it.length in 3..8 && it.all { ch -> ch.isLetterOrDigit() } }
    }

    fun stripPairCode(endpointName: String): String {
        val hashIdx = endpointName.lastIndexOf('#')
        return if (hashIdx > 0) endpointName.substring(0, hashIdx).trim() else endpointName.trim()
    }
}
