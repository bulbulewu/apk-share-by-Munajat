package com.example.apk

import android.content.Context
import android.os.StatFs
import android.util.Log
import com.example.R
import com.example.model.ExtractedApkBundle
import com.example.model.InstalledAppInfo
import com.example.util.FormatUtils
import com.example.util.LocaleHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.UUID

class ApkExtractor(private val context: Context) {

    suspend fun extractBundle(app: InstalledAppInfo): Result<ExtractedApkBundle> =
        withContext(Dispatchers.IO) {
            runCatching {
                val loc = LocaleHelper.localizedContext(context)
                val baseSource = File(app.baseApkPath)
                if (!baseSource.exists() || !baseSource.canRead()) {
                    throw IOException(loc.getString(R.string.error_cannot_read_apk, app.appName))
                }

                val outgoingRoot = File(context.cacheDir, "outgoing_apks").apply { mkdirs() }
                // Clean previous staged bundles to keep cache compact
                outgoingRoot.listFiles()?.forEach { old ->
                    old.deleteRecursively()
                }

                val requiredBytes = app.totalSizeBytes + (10L * 1024L * 1024L)
                val statFs = StatFs(outgoingRoot.absolutePath)
                if (statFs.availableBytes < requiredBytes) {
                    throw IOException(
                        loc.getString(
                            R.string.error_not_enough_storage_prepare,
                            app.appName,
                            FormatUtils.formatBytes(requiredBytes)
                        )
                    )
                }

                val transferId = UUID.randomUUID().toString().take(8)
                val stagingDir = File(outgoingRoot, transferId).apply { mkdirs() }
                val copiedFiles = mutableListOf<File>()

                if (!app.isSplitApk) {
                    val safeName = FormatUtils.sanitizeFileName(app.appName)
                    val safeVer = FormatUtils.sanitizeFileName(app.versionName)
                    val targetName = "${safeName}_v${safeVer}.apk"
                    val destFile = File(stagingDir, targetName)
                    baseSource.inputStream().use { input ->
                        destFile.outputStream().use { output ->
                            input.copyTo(output, bufferSize = 65536)
                        }
                    }
                    copiedFiles += destFile
                    Log.i(TAG, "Extracted single APK: ${destFile.name} (${destFile.length()} bytes)")
                } else {
                    // Split APKs: copy base.apk + all splitSourceDirs together
                    val baseDest = File(stagingDir, "base.apk")
                    baseSource.inputStream().use { input ->
                        baseDest.outputStream().use { output ->
                            input.copyTo(output, bufferSize = 65536)
                        }
                    }
                    copiedFiles += baseDest

                    val usedNames = mutableSetOf("base.apk")
                    app.splitApkPaths.forEachIndexed { idx, splitPath ->
                        val splitSrc = File(splitPath)
                        if (!splitSrc.exists() || !splitSrc.canRead()) {
                            throw IOException(loc.getString(R.string.error_missing_split_part, splitSrc.name))
                        }
                        var fileName = splitSrc.name
                        if (!fileName.endsWith(".apk", ignoreCase = true)) {
                            fileName = "split_$idx.apk"
                        }
                        if (!usedNames.add(fileName)) {
                            fileName = "split_${idx}_$fileName"
                            usedNames.add(fileName)
                        }
                        val splitDest = File(stagingDir, fileName)
                        splitSrc.inputStream().use { input ->
                            splitDest.outputStream().use { output ->
                                input.copyTo(output, bufferSize = 65536)
                            }
                        }
                        copiedFiles += splitDest
                    }
                    Log.i(
                        TAG,
                        "Extracted split APK bundle for ${app.appName}: ${copiedFiles.size} files, total ${app.totalSizeBytes} bytes"
                    )
                }

                val iconBase64 = try {
                    val drawable = context.packageManager.getApplicationIcon(app.packageName)
                    FormatUtils.drawableToBase64Png(drawable)
                } catch (_: Throwable) {
                    null
                }

                val actualTotal = copiedFiles.sumOf { it.length() }
                ExtractedApkBundle(
                    transferId = transferId,
                    appName = app.appName,
                    packageName = app.packageName,
                    versionName = app.versionName,
                    versionCode = app.versionCode,
                    isSplitApk = app.isSplitApk,
                    files = copiedFiles,
                    totalSizeBytes = actualTotal,
                    stagingDir = stagingDir,
                    iconBase64 = iconBase64
                )
            }
        }

    fun clearStaging() {
        runCatching {
            File(context.cacheDir, "outgoing_apks").deleteRecursively()
        }
    }

    companion object {
        private const val TAG = "ApkExtractor"
    }
}
