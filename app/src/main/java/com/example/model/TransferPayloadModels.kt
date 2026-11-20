package com.example.model

import kotlinx.serialization.Serializable

@Serializable
data class ApkFileEntry(
    val fileName: String,
    val sizeBytes: Long
)

@Serializable
data class ApkBundleMetadata(
    val transferId: String,
    val appName: String,
    val packageName: String,
    val versionName: String,
    val versionCode: Long = 1L,
    val totalSizeBytes: Long,
    val isSplitApk: Boolean,
    val files: List<ApkFileEntry>,
    val iconBase64: String? = null,
    val isGenericFile: Boolean = false,
    val mimeType: String? = null
)

@Serializable
data class ResumeFileOffset(
    val fileName: String,
    val receivedBytes: Long,
    val totalSizeBytes: Long
)

@Serializable
data class NearbyFileMapping(
    val payloadId: Long,
    val fileName: String,
    val fileSize: Long,
    val offsetBytes: Long = 0L,
    val restartedFromZero: Boolean = false
)

@Serializable
data class NearbyWireEnvelope(
    val kind: String, // "METADATA" | "FILE_MAP" | "ACCEPT" | "DECLINE" | "PAUSE" | "RESUME" | "CANCEL"
    val metadata: ApkBundleMetadata? = null,
    val fileMapping: NearbyFileMapping? = null,
    val reason: String? = null,
    val resumeOffsets: List<ResumeFileOffset>? = null
)
