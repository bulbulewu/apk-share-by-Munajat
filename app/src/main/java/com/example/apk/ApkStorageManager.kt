package com.example.apk

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.provider.MediaStore
import android.util.Log
import com.example.util.FormatUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

data class SavedApkResult(
    val files: List<File>,
    val displayFolderPath: String
)

class ApkStorageManager(private val context: Context) {

    fun getAppDownloadsRoot(): File {
        val base = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: context.filesDir
        return File(base, "APK Share").apply { mkdirs() }
    }

    fun getStagingReceiveDir(transferId: String): File {
        return File(context.cacheDir, "incoming_apks/${FormatUtils.sanitizeFileName(transferId)}")
            .apply { mkdirs() }
    }

    fun checkFreeSpaceFor(requiredBytes: Long): Boolean {
        return try {
            val root = getAppDownloadsRoot()
            val stat = StatFs(root.absolutePath)
            stat.availableBytes >= (requiredBytes + 8L * 1024L * 1024L)
        } catch (_: Throwable) {
            true
        }
    }

    fun createDestinationDir(appName: String, versionName: String, isSplitApk: Boolean): File {
        val root = getAppDownloadsRoot()
        return if (isSplitApk) {
            val folderName = "${FormatUtils.sanitizeFileName(appName)}_v${FormatUtils.sanitizeFileName(versionName)}"
            File(root, folderName).apply {
                if (exists()) deleteRecursively()
                mkdirs()
            }
        } else {
            root
        }
    }

    suspend fun exportCopiesToPublicDownloads(
        files: List<File>,
        appName: String,
        versionName: String,
        isSplitApk: Boolean,
        enabled: Boolean
    ): String = withContext(Dispatchers.IO) {
        val subFolder = if (isSplitApk) {
            "APK Share/${FormatUtils.sanitizeFileName(appName)}_v${FormatUtils.sanitizeFileName(versionName)}"
        } else {
            "APK Share"
        }
        val displayPath = "Download/$subFolder"

        if (!enabled) {
            return@withContext files.firstOrNull()?.parentFile?.absolutePath ?: displayPath
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            for (file in files) {
                try {
                    val values = ContentValues().apply {
                        put(MediaStore.Downloads.DISPLAY_NAME, file.name)
                        put(MediaStore.Downloads.MIME_TYPE, "application/vnd.android.package-archive")
                        put(
                            MediaStore.Downloads.RELATIVE_PATH,
                            "${Environment.DIRECTORY_DOWNLOADS}/$subFolder"
                        )
                        put(MediaStore.Downloads.IS_PENDING, 1)
                    }
                    val resolver = context.contentResolver
                    val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                    if (uri != null) {
                        resolver.openOutputStream(uri)?.use { out ->
                            file.inputStream().use { input ->
                                input.copyTo(out, bufferSize = 65536)
                            }
                        }
                        values.clear()
                        values.put(MediaStore.Downloads.IS_PENDING, 0)
                        resolver.update(uri, values, null, null)
                    }
                } catch (t: Throwable) {
                    Log.w(TAG, "Could not mirror ${file.name} to MediaStore Downloads: ${t.message}")
                }
            }
        } else {
            try {
                @Suppress("DEPRECATION")
                val publicDownloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                val targetDir = File(publicDownloads, subFolder).apply { mkdirs() }
                for (file in files) {
                    val dest = File(targetDir, file.name)
                    file.inputStream().use { input ->
                        dest.outputStream().use { out ->
                            input.copyTo(out, bufferSize = 65536)
                        }
                    }
                }
            } catch (t: Throwable) {
                Log.w(TAG, "Legacy public Downloads copy skipped: ${t.message}")
            }
        }

        displayPath
    }

    fun cleanupIncomingStaging(transferId: String) {
        runCatching {
            File(context.cacheDir, "incoming_apks/${FormatUtils.sanitizeFileName(transferId)}")
                .deleteRecursively()
        }
    }

    fun ensureSpaceOrThrow(requiredBytes: Long) {
        if (!checkFreeSpaceFor(requiredBytes)) {
            throw IOException(
                "Storage full: at least ${FormatUtils.formatBytes(requiredBytes)} free space is required."
            )
        }
    }

    companion object {
        private const val TAG = "ApkStorageManager"
    }
}
