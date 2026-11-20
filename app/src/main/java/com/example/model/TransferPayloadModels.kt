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
    val iconBase64: String? = null
)

@Serializable
data class NearbyFileMapping(
    val payloadId: Long,
    val fileName: String,
    val fileSize: Long
)

@Serializable
data class NearbyWireEnvelope(
    val kind: String, // "METADATA" | "FILE_MAP" | "DECLINE" | "CANCEL"
    val metadata: ApkBundleMetadata? = null,
    val fileMapping: NearbyFileMapping? = null,
    val reason: String? = null
)
