package com.example.apk

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.os.Build
import android.util.Log
import android.util.LruCache
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.content.pm.PackageInfoCompat
import com.example.model.InstalledAppInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

class InstalledAppsRepository(private val context: Context) {

    private val iconCache = LruCache<String, ImageBitmap>(120)

    suspend fun loadUserInstalledApps(): List<InstalledAppInfo> = withContext(Dispatchers.IO) {
        val pm = context.packageManager
        val packages: List<PackageInfo> = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.getInstalledPackages(PackageManager.PackageInfoFlags.of(0L))
            } else {
                @Suppress("DEPRECATION")
                pm.getInstalledPackages(0)
            }
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to query installed packages", t)
            emptyList()
        }

        val result = ArrayList<InstalledAppInfo>(packages.size)
        for (pkg in packages) {
            val appInfo = pkg.applicationInfo ?: continue
            val isSystem = (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0
            val isUpdatedSystem = (appInfo.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0
            if (isSystem || isUpdatedSystem) continue

            val sourceDir = appInfo.sourceDir
            if (sourceDir.isNullOrBlank()) continue
            val baseFile = File(sourceDir)
            if (!baseFile.exists() || !baseFile.canRead()) continue

            val splitPaths = appInfo.splitSourceDirs
                ?.filter { !it.isNullOrBlank() && File(it).exists() }
                ?: emptyList()

            val totalBytes = baseFile.length() + splitPaths.sumOf { File(it).length() }
            val label = try {
                pm.getApplicationLabel(appInfo).toString().ifBlank { pkg.packageName }
            } catch (_: Throwable) {
                pkg.packageName
            }

            result += InstalledAppInfo(
                packageName = pkg.packageName,
                appName = label,
                versionName = pkg.versionName?.takeIf { it.isNotBlank() } ?: "1.0",
                versionCode = PackageInfoCompat.getLongVersionCode(pkg),
                totalSizeBytes = totalBytes,
                installDateMillis = pkg.firstInstallTime,
                baseApkPath = sourceDir,
                splitApkPaths = splitPaths
            )
        }

        result.sortedBy { it.appName.lowercase() }
    }

    suspend fun loadAppIcon(packageName: String): ImageBitmap? = withContext(Dispatchers.IO) {
        iconCache.get(packageName)?.let { return@withContext it }
        return@withContext try {
            val drawable = context.packageManager.getApplicationIcon(packageName)
            val bitmap = if (drawable is BitmapDrawable && drawable.bitmap != null) {
                Bitmap.createScaledBitmap(drawable.bitmap, 96, 96, true)
            } else {
                Bitmap.createBitmap(96, 96, Bitmap.Config.ARGB_8888).also { bmp ->
                    val canvas = Canvas(bmp)
                    drawable.setBounds(0, 0, canvas.width, canvas.height)
                    drawable.draw(canvas)
                }
            }
            bitmap.asImageBitmap().also { iconCache.put(packageName, it) }
        } catch (t: Throwable) {
            Log.w(TAG, "Unable to load icon for $packageName: ${t.message}")
            null
        }
    }

    fun isPackageInstalled(packageName: String): Boolean {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.packageManager.getPackageInfo(
                    packageName,
                    PackageManager.PackageInfoFlags.of(0L)
                )
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.getPackageInfo(packageName, 0)
            }
            true
        } catch (_: Throwable) {
            false
        }
    }

    companion object {
        private const val TAG = "InstalledAppsRepo"
    }
}
