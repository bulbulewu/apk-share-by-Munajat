package com.example

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertRangeInfoEquals
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import com.example.model.ExtractedApkBundle
import com.example.model.TransferProgressState
import com.example.transfer.LocalHttpApkServer
import com.example.ui.components.CircularTransferProgress
import com.example.ui.components.TransferProgressBar
import com.example.ui.theme.ApkShareTheme
import com.example.ui.theme.PrimaryBlue
import com.example.ui.theme.SuccessGreen
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import kotlin.io.path.createTempDirectory

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class TransferProgressBarTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `transfer progress bar updates real-time progress semantics and byte labels`() {
        val total = 10L * 1024L * 1024L // 10 MB
        val bytesState = mutableStateOf(2560L * 1024L) // 2.5 MB = 25%

        composeRule.setContent {
            ApkShareTheme {
                TransferProgressBar(
                    bytesTransferred = bytesState.value,
                    totalBytes = total,
                    accentColor = PrimaryBlue,
                    directionLabel = "Sent",
                    fileName = "DemoApp_v1.0.apk"
                )
            }
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("transfer_progress_bar")
            .assertIsDisplayed()
            .assertRangeInfoEquals(ProgressBarRangeInfo(0.25f, 0f..1f))
        composeRule.onNodeWithTag("transfer_progress_bar_percent")
            .assertTextContains("25%")
        composeRule.onNodeWithTag("transfer_progress_bar_bytes")
            .assertTextContains("Sent", substring = true)

        // Advance transfer to 100%
        bytesState.value = total
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("transfer_progress_bar")
            .assertRangeInfoEquals(ProgressBarRangeInfo(1f, 0f..1f))
        composeRule.onNodeWithTag("transfer_progress_bar_percent")
            .assertTextContains("100%")
        composeRule.onNodeWithText("Complete").assertIsDisplayed()
    }

    @Test
    fun `circular transfer progress renders linear progress bar for active receiving transfer`() {
        val total = 20L * 1024L * 1024L
        val progress = TransferProgressState(
            bytesTransferred = 10L * 1024L * 1024L,
            totalBytes = total,
            speedBytesPerSec = 5L * 1024L * 1024L,
            etaSeconds = 2L,
            currentFileName = "base.apk",
            completedFiles = 1,
            totalFiles = 2
        )

        composeRule.setContent {
            ApkShareTheme {
                CircularTransferProgress(
                    progress = progress,
                    accentColor = SuccessGreen,
                    title = "Receiving DemoApp",
                    subtitle = "From Pixel 9",
                    directionLabel = "Received"
                )
            }
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("transfer_progress_bar")
            .assertIsDisplayed()
            .assertRangeInfoEquals(ProgressBarRangeInfo(0.5f, 0f..1f))
        composeRule.onNodeWithTag("transfer_progress_bar_percent")
            .assertTextContains("50%")
        composeRule.onNodeWithTag("transfer_progress_bar_bytes")
            .assertTextContains("Received", substring = true)
    }

    @Test
    fun `local http apk server tracks bytesServed totalBytes and 100 percent progress`() {
        val tempDir = createTempDirectory("apk_progress_test").toFile()
        try {
            val payload = ByteArray(128 * 1024) { (it % 127).toByte() }
            val apkFile = File(tempDir, "sample.apk").apply { writeBytes(payload) }
            val bundle = ExtractedApkBundle(
                transferId = "prog1",
                appName = "SampleApp",
                packageName = "com.sample.app",
                versionName = "1.0",
                versionCode = 1L,
                isSplitApk = false,
                files = listOf(apkFile),
                totalSizeBytes = apkFile.length(),
                stagingDir = tempDir,
                iconBase64 = null
            )

            val server = LocalHttpApkServer()
            val port = server.start(bundle, preferredPort = 0)
            assertTrue(port > 0)
            assertEquals(apkFile.length(), server.status.value.totalBytes)
            assertEquals(0, server.status.value.progressPercent)

            val client = OkHttpClient()
            try {
                client.newCall(Request.Builder().url("http://127.0.0.1:$port/app.apk").build())
                    .execute().use { resp ->
                        assertEquals(200, resp.code)
                        val downloaded = resp.body?.bytes()
                        assertEquals(payload.size, downloaded?.size)
                    }
                // Wait briefly for server thread to finalize status update
                val deadline = System.currentTimeMillis() + 3000L
                while (server.status.value.bytesServed < apkFile.length() && System.currentTimeMillis() < deadline) {
                    Thread.sleep(25)
                }
                assertEquals(apkFile.length(), server.status.value.bytesServed)
                assertEquals(100, server.status.value.progressPercent)
            } finally {
                server.stop()
            }
        } finally {
            tempDir.deleteRecursively()
        }
    }
}
