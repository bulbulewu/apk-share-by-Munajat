package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.util.FormatUtils
import com.example.util.QrCodeUtils
import com.example.util.ScannedQrResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ExampleRobolectricTest {

    @Test
    fun `read app name from context`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val appName = context.getString(R.string.app_name)
        assertEquals("APK Share", appName)
    }

    @Test
    fun `format utils produce readable sizes and filenames`() {
        assertEquals("1.0 MB", FormatUtils.formatBytes(1024L * 1024L))
        assertEquals("My_Cool_App", FormatUtils.sanitizeFileName("My Cool App!"))
        assertTrue(FormatUtils.formatSpeed(5L * 1024L * 1024L).contains("5.0 MB/s"))
    }

    @Test
    fun `qr code builder and parser handle nearby device pairing and hotspot urls`() {
        val pairQr = QrCodeUtils.buildNearbyPairQrContent("a3f9", "Pixel 8 #A3F9")
        val parsedPair = QrCodeUtils.parseQrScanResult(pairQr)
        assertTrue(parsedPair is ScannedQrResult.NearbyPair)
        val nearby = parsedPair as ScannedQrResult.NearbyPair
        assertEquals("A3F9", nearby.code)
        assertEquals("Pixel 8 #A3F9", nearby.deviceName)

        val parsedHotspot = QrCodeUtils.parseQrScanResult("http://192.168.43.1:8080/app.apk")
        assertTrue(parsedHotspot is ScannedQrResult.HotspotUrl)
        assertEquals("http://192.168.43.1:8080/app.apk", (parsedHotspot as ScannedQrResult.HotspotUrl).url)
    }
}
