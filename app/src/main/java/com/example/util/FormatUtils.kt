package com.example.util

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.util.Base64
import java.io.ByteArrayOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object FormatUtils {

    fun formatBytes(bytes: Long): String {
        if (bytes <= 0L) return "0 B"
        val kb = 1024.0
        val mb = kb * 1024.0
        val gb = mb * 1024.0
        return when {
            bytes >= gb -> String.format(Locale.US, "%.2f GB", bytes / gb)
            bytes >= mb -> String.format(Locale.US, "%.1f MB", bytes / mb)
            bytes >= kb -> String.format(Locale.US, "%.1f KB", bytes / kb)
            else -> "$bytes B"
        }
    }

    fun formatSpeed(bytesPerSec: Long): String {
        if (bytesPerSec <= 0L) return "0.0 MB/s"
        val mbPerSec = bytesPerSec.toDouble() / (1024.0 * 1024.0)
        return if (mbPerSec >= 0.1) {
            String.format(Locale.US, "%.1f MB/s", mbPerSec)
        } else {
            val kbPerSec = bytesPerSec.toDouble() / 1024.0
            String.format(Locale.US, "%.0f KB/s", kbPerSec)
        }
    }

    fun formatEta(seconds: Long): String {
        if (seconds <= 0L) return "Calculating..."
        val mins = seconds / 60L
        val secs = seconds % 60L
        return when {
            mins >= 60L -> {
                val hrs = mins / 60L
                val remMins = mins % 60L
                "${hrs}h ${remMins}m"
            }
            mins > 0L -> "${mins}m ${secs}s"
            else -> "${secs}s"
        }
    }

    fun formatDurationMillis(millis: Long): String {
        val totalSec = (millis / 1000L).coerceAtLeast(1L)
        val mins = totalSec / 60L
        val secs = totalSec % 60L
        return if (mins > 0L) "${mins}m ${secs}s" else "${secs}s"
    }

    fun formatDate(millis: Long): String {
        if (millis <= 0L) return "Unknown"
        val sdf = SimpleDateFormat("MMM dd, yyyy", Locale.getDefault())
        return sdf.format(Date(millis))
    }

    fun formatDateTime(millis: Long): String {
        if (millis <= 0L) return "Unknown"
        val sdf = SimpleDateFormat("MMM dd, HH:mm", Locale.getDefault())
        return sdf.format(Date(millis))
    }

    fun sanitizeFileName(raw: String): String {
        return raw.replace(Regex("[^a-zA-Z0-9._-]"), "_")
            .replace(Regex("_+"), "_")
            .trim('_')
            .ifBlank { "App" }
    }

    fun drawableToBase64Png(drawable: Drawable?, sizePx: Int = 96): String? {
        if (drawable == null) return null
        return try {
            val bitmap = if (drawable is BitmapDrawable && drawable.bitmap != null) {
                Bitmap.createScaledBitmap(drawable.bitmap, sizePx, sizePx, true)
            } else {
                Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888).also { bmp ->
                    val canvas = Canvas(bmp)
                    drawable.setBounds(0, 0, canvas.width, canvas.height)
                    drawable.draw(canvas)
                }
            }
            val out = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.PNG, 90, out)
            Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
        } catch (_: Throwable) {
            null
        }
    }

    fun base64ToBitmap(base64: String?): Bitmap? {
        if (base64.isNullOrBlank()) return null
        return try {
            val bytes = Base64.decode(base64, Base64.DEFAULT)
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        } catch (_: Throwable) {
            null
        }
    }
}
