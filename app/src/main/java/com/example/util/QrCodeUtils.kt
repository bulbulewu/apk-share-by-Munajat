package com.example.util

import android.graphics.Bitmap
import android.graphics.Color
import androidx.camera.core.ImageProxy
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.EncodeHintType
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import com.google.zxing.qrcode.QRCodeWriter
import java.net.URLDecoder
import java.net.URLEncoder

sealed interface ScannedQrResult {
    data class NearbyPair(val code: String, val deviceName: String?) : ScannedQrResult
    data class HotspotUrl(val url: String) : ScannedQrResult
    data class Unknown(val raw: String) : ScannedQrResult
}

object QrCodeUtils {

    fun buildNearbyPairQrContent(pairCode: String, deviceName: String): String {
        val cleanCode = pairCode.trim().uppercase()
        val encodedName = URLEncoder.encode(deviceName.trim(), "UTF-8")
        return "apkshare://pair?code=$cleanCode&name=$encodedName"
    }

    fun parseQrScanResult(rawText: String): ScannedQrResult {
        val trimmed = rawText.trim()
        if (trimmed.startsWith("apkshare://pair", ignoreCase = true)) {
            val query = trimmed.substringAfter('?', "")
            val params = query.split('&')
                .mapNotNull { part ->
                    val idx = part.indexOf('=')
                    if (idx <= 0) null
                    else part.substring(0, idx) to URLDecoder.decode(part.substring(idx + 1), "UTF-8")
                }
                .toMap()
            val code = params["code"]?.trim()?.uppercase().orEmpty()
            val name = params["name"]?.takeIf { it.isNotBlank() }
            if (code.isNotBlank()) {
                return ScannedQrResult.NearbyPair(code = code, deviceName = name)
            }
        }
        if (trimmed.startsWith("http://", ignoreCase = true) ||
            trimmed.startsWith("https://", ignoreCase = true)
        ) {
            return ScannedQrResult.HotspotUrl(trimmed)
        }
        return ScannedQrResult.Unknown(trimmed)
    }

    fun generateQrBitmap(content: String, sizePx: Int = 512): ImageBitmap? {
        if (content.isBlank()) return null
        return try {
            val hints = mapOf(
                EncodeHintType.MARGIN to 1
            )
            val matrix = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, sizePx, sizePx, hints)
            val bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
            for (x in 0 until sizePx) {
                for (y in 0 until sizePx) {
                    bitmap.setPixel(x, y, if (matrix[x, y]) Color.BLACK else Color.WHITE)
                }
            }
            bitmap.asImageBitmap()
        } catch (_: Throwable) {
            null
        }
    }

    fun decodeQrFromImageProxy(imageProxy: ImageProxy): String? {
        return try {
            val plane = imageProxy.planes.firstOrNull() ?: return null
            val buffer = plane.buffer
            val data = ByteArray(buffer.remaining())
            buffer.get(data)
            val width = imageProxy.width
            val height = imageProxy.height
            val source = PlanarYUVLuminanceSource(
                data,
                plane.rowStride,
                height,
                0,
                0,
                width.coerceAtMost(plane.rowStride),
                height,
                false
            )
            val binaryBitmap = BinaryBitmap(HybridBinarizer(source))
            val reader = QRCodeReader()
            val hints = mapOf(DecodeHintType.TRY_HARDER to true)
            reader.decode(binaryBitmap, hints).text
        } catch (_: Throwable) {
            null
        }
    }
}
