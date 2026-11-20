package com.example.util

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
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
    val isSplitBundle: Boolean,
    val mimeType: String? = null
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
        if (item.isGenericFile) {
            val resolvedFile = FileTransferHelper.resolveHistoryFile(context, item, storageManager)
                ?: return@withContext null
            return@withContext ShareableApkResult(
                file = resolvedFile,
                appName = item.appName,
                isSplitBundle = false,
                mimeType = item.mimeType ?: FileTransferHelper.guessMimeTypeFromFileName(resolvedFile.name)
            )
        }

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
        isSplitBundle: Boolean,
        mimeType: String? = null
    ): Intent {
        val loc = LocaleHelper.localizedContext(context)
        val uri = getFileProviderUri(context, file)
        val resolvedMimeType = mimeType?.takeIf { it.isNotBlank() } ?: mimeTypeFor(isSplitBundle)

        val sendIntent = Intent(Intent.ACTION_SEND).apply {
            type = resolvedMimeType
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(
                Intent.EXTRA_SUBJECT,
                if (mimeType != null) appName else loc.getString(R.string.share_subject_apk, appName)
            )
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

    /**
     * Builds the Android system share sheet chooser [Intent] for one or more APKs/bundles/files.
     * - 1 item: delegates to [createShareChooserIntent] (`Intent.ACTION_SEND`) unchanged.
     * - 2+ items: uses `Intent.ACTION_SEND_MULTIPLE` with `FileProvider` URIs, multi-item
     *   [ClipData], and [Intent.FLAG_GRANT_READ_URI_PERMISSION].
     */
    fun createMultipleShareChooserIntent(
        context: Context,
        items: List<ShareableApkResult>
    ): Intent {
        require(items.isNotEmpty()) { "At least one file is required to share" }
        if (items.size == 1) {
            val single = items.first()
            return createShareChooserIntent(
                context = context,
                file = single.file,
                appName = single.appName,
                isSplitBundle = single.isSplitBundle,
                mimeType = single.mimeType
            )
        }

        val loc = LocaleHelper.localizedContext(context)
        val uris = ArrayList<Uri>(items.size)
        items.forEach { item ->
            uris.add(getFileProviderUri(context, item.file))
        }

        val hasGenericFiles = items.any { it.mimeType != null }
        val mimeType = if (hasGenericFiles) {
            val mimes = items.map { it.mimeType ?: mimeTypeFor(it.isSplitBundle) }.distinct()
            if (mimes.size == 1) {
                mimes.first()
            } else {
                val topTypes = mimes.map { it.substringBefore('/') }.distinct()
                if (topTypes.size == 1 && topTypes.first().isNotBlank() && topTypes.first() != "*") {
                    "${topTypes.first()}/*"
                } else {
                    "*/*"
                }
            }
        } else {
            val allSingle = items.all { !it.isSplitBundle }
            val allSplit = items.all { it.isSplitBundle }
            when {
                allSingle -> MIME_TYPE_APK
                allSplit -> MIME_TYPE_APKS
                else -> "*/*"
            }
        }

        val combinedClipData = ClipData.newUri(context.contentResolver, items.first().file.name, uris.first()).apply {
            for (i in 1 until uris.size) {
                addItem(ClipData.Item(uris[i]))
            }
        }

        val subject = if (hasGenericFiles) {
            loc.getString(R.string.share_multiple_files_subject, items.size)
        } else {
            loc.getString(R.string.share_multiple_subject, items.size)
        }
        val chooserTitle = if (hasGenericFiles) {
            loc.getString(R.string.share_multiple_files_chooser_title, items.size)
        } else {
            loc.getString(R.string.share_multiple_chooser_title, items.size)
        }

        val sendIntent = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
            type = mimeType
            putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
            putExtra(Intent.EXTRA_SUBJECT, subject)
            clipData = combinedClipData
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }

        return Intent.createChooser(sendIntent, chooserTitle).apply {
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            if (!hasActivityContext(context)) {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        }
    }

    fun launchShareMultipleSheet(
        context: Context,
        items: List<ShareableApkResult>
    ): Boolean {
        if (items.isEmpty()) return false
        if (items.size == 1) {
            val single = items.first()
            return launchShareSheet(
                context = context,
                file = single.file,
                appName = single.appName,
                isSplitBundle = single.isSplitBundle,
                mimeType = single.mimeType
            )
        }
        return try {
            val chooser = createMultipleShareChooserIntent(context, items)
            context.startActivity(chooser)
            true
        } catch (anf: ActivityNotFoundException) {
            Log.w(TAG, "No share target app available for ${items.size} items", anf)
            false
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to launch system multi-share sheet for ${items.size} items", t)
            false
        }
    }

    /**
     * Generates a non-colliding file name in the form `<baseName>.<extension>`, or
     * `<baseName>_1.<extension>`, `<baseName>_2.<extension>`, ... when a file already exists.
     */
    fun resolveUniqueFileName(
        baseName: String,
        extension: String,
        exists: (String) -> Boolean
    ): String {
        val cleanBase = baseName.ifBlank { "App" }
        val cleanExt = extension.trimStart('.').ifBlank { "apk" }
        val initial = "$cleanBase.$cleanExt"
        if (!exists(initial)) return initial
        var counter = 1
        while (counter < 10_000) {
            val candidate = "${cleanBase}_${counter}.$cleanExt"
            if (!exists(candidate)) return candidate
            counter++
        }
        return "${cleanBase}_${System.currentTimeMillis()}.$cleanExt"
    }

    /**
     * Saves an extracted APK or `.apks` bundle into the public `Downloads/APK Share/` folder
     * using `MediaStore` on Android 10+ (zero permissions) so it appears immediately in the
     * phone's Files app. Adds `_1`, `_2`, ... suffix when a file with the same name exists.
     */
    suspend fun saveApkToPublicDownloads(
        context: Context,
        sourceFile: File,
        appName: String,
        versionName: String,
        isSplitBundle: Boolean
    ): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            if (!sourceFile.exists() || sourceFile.length() <= 0L) {
                throw IOException("Source APK file is missing: ${sourceFile.name}")
            }
            val safeName = FormatUtils.sanitizeFileName(appName)
            val safeVer = FormatUtils.sanitizeFileName(versionName)
            val baseName = "${safeName}_v${safeVer}"
            val extension = if (isSplitBundle) "apks" else "apk"
            val relativeSubFolder = "${Environment.DIRECTORY_DOWNLOADS}/APK Share"

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val resolver = context.contentResolver
                val existingNames = queryExistingDownloadNames(resolver, relativeSubFolder)
                val targetFileName = resolveUniqueFileName(baseName, extension) { candidate ->
                    candidate.lowercase() in existingNames
                }

                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, targetFileName)
                    put(MediaStore.Downloads.MIME_TYPE, mimeTypeFor(isSplitBundle))
                    put(MediaStore.Downloads.RELATIVE_PATH, relativeSubFolder)
                    put(MediaStore.Downloads.IS_PENDING, 1)
                }
                val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                    ?: throw IOException("Could not create Downloads entry for $targetFileName")
                try {
                    val outStream = resolver.openOutputStream(uri)
                        ?: throw IOException("Could not open Downloads output stream for $targetFileName")
                    outStream.use { out ->
                        sourceFile.inputStream().use { input ->
                            input.copyTo(out, bufferSize = 65536)
                        }
                    }
                    values.clear()
                    values.put(MediaStore.Downloads.IS_PENDING, 0)
                    resolver.update(uri, values, null, null)
                    Log.i(TAG, "Saved $targetFileName to $relativeSubFolder via MediaStore")
                    targetFileName
                } catch (t: Throwable) {
                    runCatching { resolver.delete(uri, null, null) }
                    throw t
                }
            } else {
                @Suppress("DEPRECATION")
                val publicDownloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                val targetDir = File(publicDownloads, "APK Share").apply { mkdirs() }
                if (!targetDir.exists() || !targetDir.isDirectory) {
                    throw IOException("Could not create Downloads/APK Share directory")
                }
                val targetFileName = resolveUniqueFileName(baseName, extension) { candidate ->
                    File(targetDir, candidate).exists()
                }
                val destFile = File(targetDir, targetFileName)
                sourceFile.inputStream().use { input ->
                    destFile.outputStream().use { out ->
                        input.copyTo(out, bufferSize = 65536)
                    }
                }
                runCatching {
                    MediaScannerConnection.scanFile(
                        context,
                        arrayOf(destFile.absolutePath),
                        arrayOf(mimeTypeFor(isSplitBundle)),
                        null
                    )
                }
                Log.i(TAG, "Saved $targetFileName to ${targetDir.absolutePath}")
                targetFileName
            }
        }
    }

    private fun queryExistingDownloadNames(
        resolver: android.content.ContentResolver,
        relativeSubFolder: String
    ): Set<String> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return emptySet()
        val names = mutableSetOf<String>()
        runCatching {
            val projection = arrayOf(MediaStore.Downloads.DISPLAY_NAME)
            val selection = "${MediaStore.Downloads.RELATIVE_PATH} LIKE ?"
            val selectionArgs = arrayOf("${relativeSubFolder}%")
            resolver.query(
                MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                projection,
                selection,
                selectionArgs,
                null
            )?.use { cursor ->
                val nameCol = cursor.getColumnIndex(MediaStore.Downloads.DISPLAY_NAME)
                if (nameCol >= 0) {
                    while (cursor.moveToNext()) {
                        val name = cursor.getString(nameCol)
                        if (!name.isNullOrBlank()) {
                            names.add(name.lowercase())
                        }
                    }
                }
            }
        }
        return names
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
        isSplitBundle: Boolean,
        mimeType: String? = null
    ): Boolean {
        return try {
            val chooser = createShareChooserIntent(context, file, appName, isSplitBundle, mimeType)
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
