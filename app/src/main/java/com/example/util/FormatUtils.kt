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

    const val UNKNOWN_METRIC = "—"
    const val SPEED_WINDOW_MILLIS = 2_000L
    const val LIVE_STATS_UPDATE_INTERVAL_MILLIS = 500L

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

    /**
     * Formats transfer amounts as MB with 1 decimal (e.g. "45.2 MB"), or KB with 1 decimal
     * when under 1 MB (e.g. "512.0 KB").
     */
    fun formatTransferSize(bytes: Long): String {
        val safe = bytes.coerceAtLeast(0L)
        val kb = 1024.0
        val mb = kb * 1024.0
        return if (safe >= mb) {
            String.format(Locale.US, "%.1f MB", safe / mb)
        } else {
            String.format(Locale.US, "%.1f KB", safe / kb)
        }
    }

    /**
     * Formats live transfer speed in MB/s with 1 decimal (e.g. "4.2 MB/s"),
     * returning "—" when speed is 0 or unknown.
     */
    fun formatLiveSpeed(bytesPerSec: Long): String {
        if (bytesPerSec <= 0L) return UNKNOWN_METRIC
        val mbPerSec = bytesPerSec.toDouble() / (1024.0 * 1024.0)
        return String.format(Locale.US, "%.1f MB/s", mbPerSec)
    }

    /**
     * Computes estimated seconds remaining given remaining bytes and current speed.
     * Returns -1L when speed is 0 or unknown.
     */
    fun computeEtaSeconds(remainingBytes: Long, speedBytesPerSec: Long): Long {
        if (speedBytesPerSec <= 0L) return -1L
        val safeRemaining = remainingBytes.coerceAtLeast(0L)
        if (safeRemaining == 0L) return 0L
        return (safeRemaining / speedBytesPerSec).coerceAtLeast(1L)
    }

    /**
     * Formats estimated time remaining as mm:ss (e.g. "01:25"), or null when unknown.
     */
    fun formatEtaClock(etaSeconds: Long): String? {
        if (etaSeconds < 0L) return null
        val mins = etaSeconds / 60L
        val secs = etaSeconds % 60L
        return String.format(Locale.US, "%02d:%02d", mins, secs)
    }

    fun formatSpeed(bytesPerSec: Long): String {
        return formatLiveSpeed(bytesPerSec)
    }

    fun formatEta(seconds: Long): String {
        return formatEtaClock(seconds) ?: UNKNOWN_METRIC
    }

    /**
     * Computes live transfer speed (bytes/sec) from bytes transferred over a rolling time window
     * (default 2 seconds) without any extra network calls.
     */
    class RollingTransferSpeedTracker(
        private val windowMillis: Long = SPEED_WINDOW_MILLIS
    ) {
        private val samples = ArrayDeque<Pair<Long, Long>>()

        @Synchronized
        fun reset(nowMillis: Long = System.currentTimeMillis(), initialBytes: Long = 0L) {
            samples.clear()
            samples.addLast(nowMillis to initialBytes.coerceAtLeast(0L))
        }

        @Synchronized
        fun record(cumulativeBytes: Long, nowMillis: Long = System.currentTimeMillis()): Long {
            val safeBytes = cumulativeBytes.coerceAtLeast(0L)
            if (samples.isEmpty()) {
                samples.addLast(nowMillis to safeBytes)
                return 0L
            }
            val last = samples.last()
            if (nowMillis < last.first) {
                return computeCurrentSpeedLocked(nowMillis)
            }
            if (nowMillis == last.first) {
                samples.removeLast()
                samples.addLast(nowMillis to maxOf(last.second, safeBytes))
            } else {
                samples.addLast(nowMillis to maxOf(last.second, safeBytes))
            }

            val cutoff = nowMillis - windowMillis
            while (samples.size >= 2 && samples[1].first <= cutoff) {
                samples.removeFirst()
            }
            return computeCurrentSpeedLocked(nowMillis)
        }

        @Synchronized
        fun currentSpeed(nowMillis: Long = System.currentTimeMillis()): Long {
            if (samples.size < 2) return 0L
            val last = samples.last()
            if (nowMillis - last.first > windowMillis) {
                return 0L
            }
            return computeCurrentSpeedLocked(nowMillis)
        }

        private fun computeCurrentSpeedLocked(nowMillis: Long): Long {
            if (samples.size < 2) return 0L
            val oldest = samples.first()
            val newest = samples.last()
            val deltaBytes = (newest.second - oldest.second).coerceAtLeast(0L)
            val deltaMs = (newest.first - oldest.first)
            if (deltaBytes <= 0L || deltaMs <= 0L) return 0L
            if (nowMillis - newest.first > windowMillis) return 0L
            return (deltaBytes * 1000L) / deltaMs
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
