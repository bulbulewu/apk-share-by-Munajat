package com.example.util

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.core.content.FileProvider
import androidx.core.content.pm.PackageInfoCompat
import com.example.R
import com.example.apk.ApkExtractor
import com.example.apk.ApkStorageManager
import com.example.data.db.TransferHistoryEntity
import com.example.model.ExtractedApkBundle
import com.example.model.InstalledAppInfo
import com.example.model.TransferDirection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

data class ShareableApkResult(
    val file: File,
    val appName: String,
    val isSplitBundle: Boolean
)

/**
 * Prepares extracted single APK or split APK (.apks) files and launches the Android
 * system share sheet (WhatsApp, Telegram, Bluetooth, Gmail, Drive, etc.) using the
 * app's existing FileProvider.
 */
object ApkShareHelper {

    private const val TAG = "ApkShareHelper"
    const val MIME_TYPE_APK = "application/vnd.android.package-archive"
    const val MIME_TYPE_APKS = "application/zip"

    /**
     * Converts an [ExtractedApkBundle] produced by [ApkExtractor] into a single shareable file:
     * - Single APK: returns the extracted `<AppName>_v<Ver>.apk` file directly.
     * - Split APK: packages `base.apk` and all split `.apk` files at the root of a standard
     *   `<AppName>_v<Ver>.apks` ZIP archive in `bundle.stagingDir` (covered by FileProvider's
     *   `<cache-path>`).
     */
    suspend fun prepareShareableFile(bundle: ExtractedApkBundle): ShareableApkResult =
        withContext(Dispatchers.IO) {
            if (!bundle.isSplitApk && bundle.files.size == 1) {
                val singleFile = bundle.files.first()
                if (!singleFile.exists() || singleFile.length() <= 0L) {
                    throw IOException("Extracted APK file is missing: ${singleFile.name}")
                }
                return@withContext ShareableApkResult(
                    file = singleFile,
                    appName = bundle.appName,
                    isSplitBundle = false
                )
            }

            val apksFile = buildSplitApksArchive(
                apkFiles = bundle.files,
                outputDir = bundle.stagingDir,
                appName = bundle.appName,
                versionName = bundle.versionName
            )
            ShareableApkResult(
                file = apksFile,
                appName = bundle.appName,
                isSplitBundle = true
            )
        }

    /**
     * Packages all split APK files (`base.apk` + config splits) at the archive root of a
     * standard `.apks` bundle so standard split-APK installers on the receiving phone can install it.
     */
    suspend fun buildSplitApksArchive(
        apkFiles: List<File>,
        outputDir: File,
        appName: String,
        versionName: String
    ): File = withContext(Dispatchers.IO) {
        val validFiles = apkFiles.filter { it.exists() && it.length() > 0L }
        if (validFiles.isEmpty()) {
            throw IOException("No valid APK files to bundle")
        }
        outputDir.mkdirs()
        val safeName = FormatUtils.sanitizeFileName(appName)
        val safeVer = FormatUtils.sanitizeFileName(versionName)
        val archiveFile = File(outputDir, "${safeName}_v${safeVer}.apks")

        val usedEntryNames = mutableSetOf<String>()
        ZipOutputStream(BufferedOutputStream(FileOutputStream(archiveFile))).use { zipOut ->
            zipOut.setLevel(Deflater.BEST_SPEED)
            validFiles.forEachIndexed { index, apkFile ->
                var entryName = apkFile.name.ifBlank { "split_$index.apk" }
                if (!entryName.endsWith(".apk", ignoreCase = true)) {
                    entryName = "$entryName.apk"
                }
                if (!usedEntryNames.add(entryName)) {
                    entryName = "split_${index}_$entryName"
                    usedEntryNames.add(entryName)
                }
                zipOut.putNextEntry(ZipEntry(entryName))
                apkFile.inputStream().use { input ->
                    input.copyTo(zipOut, bufferSize = 65536)
                }
                zipOut.closeEntry()
            }
        }
        Log.i(TAG, "Built split bundle ${archiveFile.name} (${validFiles.size} APKs, ${archiveFile.length()} bytes)")
        archiveFile
    }

    /**
     * Resolves a shareable APK or `.apks` file for a [TransferHistoryEntity] if it still exists
     * on the device. Returns `null` if the file was deleted / app is no longer installed.
     */
    suspend fun resolveHistoryShareableFile(
        context: Context,
        item: TransferHistoryEntity,
        storageManager: ApkStorageManager,
        apkExtractor: ApkExtractor
    ): ShareableApkResult? = withContext(Dispatchers.IO) {
        val safeName = FormatUtils.sanitizeFileName(item.appName)
        val safeVer = FormatUtils.sanitizeFileName(item.versionName)
        val expectedSingleName = "${safeName}_v${safeVer}.apk"
        val expectedFolderName = "${safeName}_v${safeVer}"
        val expectedApksName = "${safeName}_v${safeVer}.apks"

        if (item.direction == TransferDirection.RECEIVED) {
            val downloadsRoot = storageManager.getAppDownloadsRoot()
            if (item.isSplitApk) {
                val splitDir = File(downloadsRoot, expectedFolderName)
                val splitApks = splitDir.listFiles()
                    ?.filter { it.isFile && it.name.endsWith(".apk", ignoreCase = true) && it.length() > 0L }
                    ?.sortedBy { if (it.name.equals("base.apk", ignoreCase = true)) "" else it.name }
                    .orEmpty()
                if (splitApks.isNotEmpty()) {
                    val shareDir = File(context.cacheDir, "outgoing_apks/history_share").apply {
                        deleteRecursively()
                        mkdirs()
                    }
                    val archive = buildSplitApksArchive(
                        apkFiles = splitApks,
                        outputDir = shareDir,
                        appName = item.appName,
                        versionName = item.versionName
                    )
                    return@withContext ShareableApkResult(
                        file = archive,
                        appName = item.appName,
                        isSplitBundle = true
                    )
                }
            }

            val singleCandidates = listOf(
                File(downloadsRoot, expectedSingleName),
                File(downloadsRoot, "${safeName}.apk")
            )
            val existingSingle = singleCandidates.firstOrNull { it.isFile && it.length() > 0L }
            if (existingSingle != null) {
                return@withContext ShareableApkResult(
                    file = existingSingle,
                    appName = item.appName,
                    isSplitBundle = false
                )
            }
            return@withContext null
        }

        // SENT item: check staged cache first, then re-extract if app is still installed
        val outgoingRoot = File(context.cacheDir, "outgoing_apks")
        val subDirs = outgoingRoot.listFiles()?.filter { it.isDirectory }.orEmpty()
        for (dir in subDirs) {
            if (item.isSplitApk) {
                val existingApks = File(dir, expectedApksName)
                if (existingApks.isFile && existingApks.length() > 0L) {
                    return@withContext ShareableApkResult(
                        file = existingApks,
                        appName = item.appName,
                        isSplitBundle = true
                    )
                }
            } else {
                val existingSingle = File(dir, expectedSingleName)
                if (existingSingle.isFile && existingSingle.length() > 0L) {
                    return@withContext ShareableApkResult(
                        file = existingSingle,
                        appName = item.appName,
                        isSplitBundle = false
                    )
                }
            }
        }

        val installedInfo = loadInstalledAppByPackage(context, item.packageName) ?: return@withContext null
        val extracted = apkExtractor.extractBundle(installedInfo).getOrNull() ?: return@withContext null
        return@withContext runCatching { prepareShareableFile(extracted) }.getOrNull()
    }

    fun getFileProviderUri(context: Context, file: File): Uri {
        val authority = "${context.packageName}.fileprovider"
        return FileProvider.getUriForFile(context, authority, file)
    }

    fun mimeTypeFor(isSplitBundle: Boolean): String =
        if (isSplitBundle) MIME_TYPE_APKS else MIME_TYPE_APK

    /**
     * Builds the Android system share sheet chooser [Intent] backed by [FileProvider] with
     * [Intent.FLAG_GRANT_READ_URI_PERMISSION] and [ClipData] propagation for Android 10–16.
     */
    fun createShareChooserIntent(
        context: Context,
        file: File,
        appName: String,
        isSplitBundle: Boolean
    ): Intent {
        val loc = LocaleHelper.localizedContext(context)
        val uri = getFileProviderUri(context, file)
        val mimeType = mimeTypeFor(isSplitBundle)

        val sendIntent = Intent(Intent.ACTION_SEND).apply {
            type = mimeType
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, loc.getString(R.string.share_subject_apk, appName))
            clipData = ClipData.newUri(context.contentResolver, file.name, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }

        val chooserTitle = loc.getString(R.string.share_chooser_title, appName)
        return Intent.createChooser(sendIntent, chooserTitle).apply {
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            if (!hasActivityContext(context)) {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        }
    }

    private fun hasActivityContext(context: Context): Boolean {
        var current: Context? = context
        while (current is android.content.ContextWrapper) {
            if (current is Activity) return true
            current = current.baseContext
        }
        return current is Activity
    }

    fun launchShareSheet(
        context: Context,
        file: File,
        appName: String,
        isSplitBundle: Boolean
    ): Boolean {
        return try {
            val chooser = createShareChooserIntent(context, file, appName, isSplitBundle)
            context.startActivity(chooser)
            true
        } catch (anf: ActivityNotFoundException) {
            Log.w(TAG, "No share target app available for ${file.name}", anf)
            false
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to launch system share sheet for ${file.name}", t)
            false
        }
    }

    private fun loadInstalledAppByPackage(context: Context, packageName: String): InstalledAppInfo? {
        if (packageName.isBlank()) return null
        return runCatching {
            val pm = context.packageManager
            val pkg = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(0L))
            } else {
                @Suppress("DEPRECATION")
                pm.getPackageInfo(packageName, 0)
            }
            val appInfo = pkg.applicationInfo ?: return null
            val sourceDir = appInfo.sourceDir ?: return null
            val baseFile = File(sourceDir)
            if (!baseFile.exists() || !baseFile.canRead()) return null
            val splitPaths = appInfo.splitSourceDirs
                ?.filter { !it.isNullOrBlank() && File(it).exists() }
                ?: emptyList()
            val totalBytes = baseFile.length() + splitPaths.sumOf { File(it).length() }
            val label = runCatching {
                pm.getApplicationLabel(appInfo).toString().ifBlank { packageName }
            }.getOrDefault(packageName)

            InstalledAppInfo(
                packageName = packageName,
                appName = label,
                versionName = pkg.versionName?.takeIf { it.isNotBlank() } ?: "1.0",
                versionCode = PackageInfoCompat.getLongVersionCode(pkg),
                totalSizeBytes = totalBytes,
                installDateMillis = pkg.firstInstallTime,
                baseApkPath = sourceDir,
                splitApkPaths = splitPaths
            )
        }.getOrNull()
    }
}
