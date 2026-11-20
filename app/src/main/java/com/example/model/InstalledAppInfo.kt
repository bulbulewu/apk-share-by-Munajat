package com.example.model

import java.io.File

data class InstalledAppInfo(
    val packageName: String,
    val appName: String,
    val versionName: String,
    val versionCode: Long,
    val totalSizeBytes: Long,
    val installDateMillis: Long,
    val baseApkPath: String,
    val splitApkPaths: List<String>,
) {
    val isSplitApk: Boolean get() = splitApkPaths.isNotEmpty()
    val totalApkFileCount: Int get() = 1 + splitApkPaths.size
}

data class ExtractedApkBundle(
    val transferId: String,
    val appName: String,
    val packageName: String,
    val versionName: String,
    val versionCode: Long,
    val isSplitApk: Boolean,
    val files: List<File>,
    val totalSizeBytes: Long,
    val stagingDir: File,
    val iconBase64: String?
)
