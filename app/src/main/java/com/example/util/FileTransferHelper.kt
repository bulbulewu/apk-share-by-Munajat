package com.example.util

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.util.Log
import android.webkit.MimeTypeMap
import com.example.R
import com.example.apk.ApkStorageManager
import com.example.data.db.TransferHistoryEntity
import com.example.model.ExtractedApkBundle
import com.example.model.PickedFileInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.Locale
import java.util.UUID

/**
 * Handles SAF document picking metadata, bit-exact file staging (no compression/resizing),
 * saving generic files to `Downloads/APK Share/`, and opening received files via Android's
 * system viewer (`Intent.ACTION_VIEW` + `FileProvider`).
 */
object FileTransferHelper {

    private const val TAG = "FileTransferHelper"

    /**
     * Resolves display name, exact byte size, and MIME type for each URI returned by
     * `ActivityResultContracts.OpenMultipleDocuments()`.
     */
    suspend fun resolvePickedFiles(
        context: Context,
        uris: List<Uri>
    ): List<PickedFileInfo> = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        uris.mapNotNull { uri ->
            runCatching {
                runCatching {
                    resolver.takePersistableUriPermission(
                        uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION
                    )
                }

                var displayName: String? = null
                var sizeBytes = -1L

                resolver.query(
                    uri,
                    arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
                    null,
                    null,
                    null
                )?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val nameIdx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        if (nameIdx >= 0 && !cursor.isNull(nameIdx)) {
                            displayName = cursor.getString(nameIdx)
                        }
                        val sizeIdx = cursor.getColumnIndex(OpenableColumns.SIZE)
                        if (sizeIdx >= 0 && !cursor.isNull(sizeIdx)) {
                            sizeBytes = cursor.getLong(sizeIdx)
                        }
                    }
                }

                if (sizeBytes < 0L) {
                    sizeBytes = runCatching {
                        resolver.openAssetFileDescriptor(uri, "r")?.use { afd ->
                            afd.length.takeIf { it >= 0L } ?: 0L
                        } ?: 0L
                    }.getOrDefault(0L)
                }

                val rawName = displayName?.takeIf { it.isNotBlank() }
                    ?: uri.lastPathSegment?.substringAfterLast('/')?.takeIf { it.isNotBlank() }
                    ?: "file_${System.currentTimeMillis()}"
                val safeName = sanitizeFileNamePreservingExtension(rawName)

                val resolvedMime = resolveMimeType(context, uri, safeName)

                PickedFileInfo(
                    uriString = uri.toString(),
                    displayName = safeName,
                    sizeBytes = sizeBytes.coerceAtLeast(0L),
                    mimeType = resolvedMime
                )
            }.onFailure { err ->
                Log.w(TAG, "Failed to inspect picked URI $uri: ${err.message}")
            }.getOrNull()
        }
    }

    /**
     * Stages a picked document into `cacheDir/outgoing_apks/<transferId>/<safeName>` by copying
     * raw bytes verbatim with no compression, resizing, or modification.
     */
    suspend fun stagePickedFile(
        context: Context,
        pickedFile: PickedFileInfo,
        cleanPrevious: Boolean = true
    ): Result<ExtractedApkBundle> = withContext(Dispatchers.IO) {
        runCatching {
            val loc = LocaleHelper.localizedContext(context)
            val uri = Uri.parse(pickedFile.uriString)
            val outgoingRoot = File(context.cacheDir, "outgoing_apks").apply { mkdirs() }
            if (cleanPrevious) {
                outgoingRoot.listFiles()?.forEach { old -> old.deleteRecursively() }
            }

            if (pickedFile.sizeBytes > 0L) {
                val requiredBytes = pickedFile.sizeBytes + (10L * 1024L * 1024L)
                val statFs = StatFs(outgoingRoot.absolutePath)
                if (statFs.availableBytes < requiredBytes) {
                    throw IOException(
                        loc.getString(
                            R.string.error_not_enough_storage_file,
                            pickedFile.displayName,
                            FormatUtils.formatBytes(requiredBytes)
                        )
                    )
                }
            }

            val transferId = UUID.randomUUID().toString().take(8)
            val stagingDir = File(outgoingRoot, transferId).apply { mkdirs() }
            val safeName = sanitizeFileNamePreservingExtension(pickedFile.displayName)
            val destFile = File(stagingDir, safeName)

            val inputStream = context.contentResolver.openInputStream(uri)
                ?: throw IOException(loc.getString(R.string.error_cannot_read_file, pickedFile.displayName))

            inputStream.use { input ->
                destFile.outputStream().use { output ->
                    input.copyTo(output, bufferSize = 65536)
                }
            }

            if (!destFile.exists()) {
                throw IOException(loc.getString(R.string.error_cannot_read_file, pickedFile.displayName))
            }

            val actualSize = destFile.length()
            val mimeType = pickedFile.mimeType.ifBlank { guessMimeTypeFromFileName(safeName) }
            Log.i(TAG, "Staged file $safeName ($actualSize bytes, mime=$mimeType)")

            ExtractedApkBundle(
                transferId = transferId,
                appName = safeName,
                packageName = "",
                versionName = "",
                versionCode = 0L,
                isSplitApk = false,
                files = listOf(destFile),
                totalSizeBytes = actualSize,
                stagingDir = stagingDir,
                iconBase64 = null,
                isGenericFile = true,
                mimeType = mimeType
            )
        }
    }

    /**
     * Saves a generic file (photo, video, PDF, audio, etc.) verbatim into the public
     * `Downloads/APK Share/` folder via `MediaStore` on Android 10+ (zero permissions).
     * Never overwrites an existing file; appends `_1`, `_2`, ... before the extension.
     */
    suspend fun saveGenericFileToPublicDownloads(
        context: Context,
        sourceFile: File,
        displayName: String,
        mimeType: String?
    ): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            if (!sourceFile.exists()) {
                throw IOException("Source file is missing: ${sourceFile.name}")
            }
            val safeName = sanitizeFileNamePreservingExtension(displayName.ifBlank { sourceFile.name })
            val (baseName, extension) = splitBaseAndExtension(safeName)
            val effectiveMime = mimeType?.takeIf { it.isNotBlank() } ?: guessMimeTypeFromFileName(safeName)
            val relativeSubFolder = "${Environment.DIRECTORY_DOWNLOADS}/APK Share"

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val resolver = context.contentResolver
                val existingNames = queryExistingDownloadNames(resolver, relativeSubFolder)
                val targetFileName = resolveUniqueGenericFileName(baseName, extension) { candidate ->
                    candidate.lowercase(Locale.ROOT) in existingNames
                }

                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, targetFileName)
                    put(MediaStore.Downloads.MIME_TYPE, effectiveMime)
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
                val targetFileName = resolveUniqueGenericFileName(baseName, extension) { candidate ->
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
                        arrayOf(effectiveMime),
                        null
                    )
                }
                Log.i(TAG, "Saved $targetFileName to ${targetDir.absolutePath}")
                targetFileName
            }
        }
    }

    /**
     * Builds an `Intent.ACTION_VIEW` intent backed by the app's existing [androidx.core.content.FileProvider]
     * to open a received photo, video, PDF, audio, or other document with the system viewer app.
     */
    fun createViewFileIntent(
        context: Context,
        file: File,
        mimeType: String?
    ): Intent {
        val uri = ApkShareHelper.getFileProviderUri(context, file)
        val effectiveMime = mimeType?.takeIf { it.isNotBlank() && it != "*/*" }
            ?: guessMimeTypeFromFileName(file.name)
        return Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, effectiveMime)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            if (!hasActivityContext(context)) {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        }
    }

    fun openFileWithSystemViewer(
        context: Context,
        file: File,
        mimeType: String?
    ): Boolean {
        if (!file.exists() || !file.canRead()) return false
        return try {
            val viewIntent = createViewFileIntent(context, file, mimeType)
            context.startActivity(viewIntent)
            true
        } catch (anf: ActivityNotFoundException) {
            Log.w(TAG, "No viewer app found for ${file.name} ($mimeType)", anf)
            false
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to open file ${file.name}", t)
            false
        }
    }

    /**
     * Resolves the on-disk file for a generic [TransferHistoryEntity] entry so it can be viewed
     * or re-shared from History.
     */
    fun resolveHistoryFile(
        context: Context,
        item: TransferHistoryEntity,
        storageManager: ApkStorageManager
    ): File? {
        val directPath = item.savedFilePath
        if (!directPath.isNullOrBlank()) {
            val directFile = File(directPath)
            if (directFile.isFile && directFile.canRead()) {
                return directFile
            }
        }
        val downloadsRoot = storageManager.getAppDownloadsRoot()
        val exactCandidate = File(downloadsRoot, item.appName)
        if (exactCandidate.isFile && exactCandidate.canRead()) return exactCandidate

        val sanitizedCandidate = File(downloadsRoot, sanitizeFileNamePreservingExtension(item.appName))
        if (sanitizedCandidate.isFile && sanitizedCandidate.canRead()) return sanitizedCandidate

        val outgoingRoot = File(context.cacheDir, "outgoing_apks")
        val stagedDirs = outgoingRoot.listFiles()?.filter { it.isDirectory }.orEmpty()
        for (dir in stagedDirs) {
            val stagedFile = File(dir, sanitizeFileNamePreservingExtension(item.appName))
            if (stagedFile.isFile && stagedFile.canRead()) return stagedFile
        }
        return null
    }

    /**
     * Sanitizes a file name against path traversal and illegal filesystem characters while
     * preserving its original extension (e.g., `.jpg`, `.mp4`, `.pdf`, `.mp3`, `.zip`).
     */
    fun sanitizeFileNamePreservingExtension(rawName: String): String {
        val leaf = rawName.substringAfterLast('/').substringAfterLast('\\').trim()
        val cleaned = leaf
            .replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), "_")
            .replace("..", "_")
            .trim('.', ' ')
        return cleaned.ifBlank { "file_${System.currentTimeMillis()}" }.take(140)
    }

    fun splitBaseAndExtension(fileName: String): Pair<String, String> {
        val lastDot = fileName.lastIndexOf('.')
        return if (lastDot > 0 && lastDot < fileName.length - 1) {
            fileName.substring(0, lastDot) to fileName.substring(lastDot + 1)
        } else {
            fileName to ""
        }
    }

    fun resolveUniqueGenericFileName(
        baseName: String,
        extension: String,
        exists: (String) -> Boolean
    ): String {
        val cleanBase = baseName.ifBlank { "file" }
        val cleanExt = extension.trimStart('.')
        val initial = if (cleanExt.isNotEmpty()) "$cleanBase.$cleanExt" else cleanBase
        if (!exists(initial)) return initial
        var counter = 1
        while (counter < 10_000) {
            val candidate = if (cleanExt.isNotEmpty()) {
                "${cleanBase}_${counter}.$cleanExt"
            } else {
                "${cleanBase}_${counter}"
            }
            if (!exists(candidate)) return candidate
            counter++
        }
        val ts = System.currentTimeMillis()
        return if (cleanExt.isNotEmpty()) "${cleanBase}_${ts}.$cleanExt" else "${cleanBase}_${ts}"
    }

    fun resolveMimeType(context: Context, uri: Uri, fileName: String): String {
        val fromResolver = runCatching { context.contentResolver.getType(uri) }.getOrNull()
        if (!fromResolver.isNullOrBlank() && fromResolver != "*/*") {
            return fromResolver
        }
        return guessMimeTypeFromFileName(fileName)
    }

    fun guessMimeTypeFromFileName(fileName: String): String {
        val ext = fileName.substringAfterLast('.', "").lowercase(Locale.ROOT)
        if (ext.isBlank()) return "application/octet-stream"
        val fromMap = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)
        if (!fromMap.isNullOrBlank()) return fromMap
        return when (ext) {
            "jpg", "jpeg" -> "image/jpeg"
            "png" -> "image/png"
            "webp" -> "image/webp"
            "gif" -> "image/gif"
            "heic", "heif" -> "image/heif"
            "mp4", "m4v" -> "video/mp4"
            "mkv" -> "video/x-matroska"
            "mov" -> "video/quicktime"
            "webm" -> "video/webm"
            "mp3" -> "audio/mpeg"
            "m4a" -> "audio/mp4"
            "wav" -> "audio/wav"
            "ogg" -> "audio/ogg"
            "flac" -> "audio/flac"
            "pdf" -> "application/pdf"
            "zip" -> "application/zip"
            "apk" -> ApkShareHelper.MIME_TYPE_APK
            "txt" -> "text/plain"
            else -> "application/octet-stream"
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
                            names.add(name.lowercase(Locale.ROOT))
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
}
