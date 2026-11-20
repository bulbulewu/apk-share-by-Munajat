package com.example

import com.example.model.ExtractedApkBundle
import com.example.transfer.LocalHttpApkServer
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import kotlin.io.path.createTempDirectory

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class LocalHttpApkServerTest {

    @Test
    fun `serves manifest and supports range resume for split apk bundle`() {
        val tempDir = createTempDirectory("apk_server_test").toFile()
        try {
            val baseFile = File(tempDir, "base.apk").apply {
                writeBytes("HELLO_BASE_APK_CONTENT".toByteArray())
            }
            val splitFile = File(tempDir, "split_config.arm64.apk").apply {
                writeBytes("SPLIT_ARM64_PAYLOAD".toByteArray())
            }
            val bundle = ExtractedApkBundle(
                transferId = "test123",
                appName = "DemoApp",
                packageName = "com.demo.app",
                versionName = "2.1.0",
                versionCode = 21L,
                isSplitApk = true,
                files = listOf(baseFile, splitFile),
                totalSizeBytes = baseFile.length() + splitFile.length(),
                stagingDir = tempDir,
                iconBase64 = null
            )

            val server = LocalHttpApkServer()
            val port = server.start(bundle, preferredPort = 0)
            assertTrue(port > 0)

            val client = OkHttpClient()
            try {
                client.newCall(Request.Builder().url("http://127.0.0.1:$port/manifest.json").build())
                    .execute().use { resp ->
                        assertEquals(200, resp.code)
                        val json = resp.body?.string().orEmpty()
                        assertTrue(json.contains("\"appName\":\"DemoApp\""))
                        assertTrue(json.contains("\"isSplitApk\":true"))
                    }

                client.newCall(
                    Request.Builder()
                        .url("http://127.0.0.1:$port/split/1/split_config.arm64.apk")
                        .header("Range", "bytes=6-")
                        .build()
                ).execute().use { resp ->
                    assertEquals(206, resp.code)
                    assertEquals("ARM64_PAYLOAD", resp.body?.string())
                }
            } finally {
                server.stop()
            }
        } finally {
            tempDir.deleteRecursively()
        }
    }
}
