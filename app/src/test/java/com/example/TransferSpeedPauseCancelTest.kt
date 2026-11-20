package com.example

import android.content.Context
import android.os.Looper
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.lifecycle.ViewModelStore
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.apk.ApkExtractor
import com.example.apk.ApkStorageManager
import com.example.apk.InstalledAppsRepository
import com.example.data.db.AppDatabase
import com.example.data.db.TransferHistoryRepository
import com.example.data.prefs.SettingsDataStore
import com.example.installer.ApkInstaller
import com.example.model.ExtractedApkBundle
import com.example.model.InstalledAppInfo
import com.example.model.TransferProgressState
import com.example.transfer.HotspotController
import com.example.transfer.HttpApkDownloader
import com.example.transfer.HttpDownloadState
import com.example.transfer.LocalHttpApkServer
import com.example.transfer.NearbyReceiverState
import com.example.transfer.NearbySenderState
import com.example.transfer.NearbyTransferManager
import com.example.ui.components.CircularTransferProgress
import com.example.ui.components.TransferCancelledView
import com.example.ui.theme.ApkShareTheme
import com.example.ui.theme.PrimaryBlue
import com.example.ui.theme.SuccessGreen
import com.example.ui.viewmodel.ExtractionUiState
import com.example.ui.viewmodel.ReceiverViewModel
import com.example.ui.viewmodel.SenderViewModel
import com.example.util.FormatUtils
import com.example.util.LocaleHelper
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowStatFs
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.math.roundToLong

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class TransferSpeedPauseCancelTest {

    @get:Rule
    val composeRule = createComposeRule()

    private var db: AppDatabase? = null
    private var httpServer: LocalHttpApkServer? = null
    private var viewModelStore: ViewModelStore? = null

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val outgoingRoot = File(context.cacheDir, "outgoing_apks").apply { mkdirs() }
        ShadowStatFs.registerStats(outgoingRoot.absolutePath, 65536, 65536, 65536)
        val receivedRoot = File(context.filesDir, "received_apks").apply { mkdirs() }
        ShadowStatFs.registerStats(receivedRoot.absolutePath, 65536, 65536, 65536)
        val externalFiles = context.getExternalFilesDir(null)
        if (externalFiles != null) {
            externalFiles.mkdirs()
            ShadowStatFs.registerStats(externalFiles.absolutePath, 65536, 65536, 65536)
        }
    }

    @After
    fun tearDown() {
        viewModelStore?.clear()
        viewModelStore = null
        drainMainLooper()
        Thread.sleep(50)
        httpServer?.stop()
        db?.close()
    }

    @Test
    fun `rolling speed tracker computes 2-second window speed and formats speed, ETA, and amounts`() {
        val tracker = FormatUtils.RollingTransferSpeedTracker(windowMillis = 2_000L)
        val mb = 1024L * 1024L

        tracker.reset(nowMillis = 1_000L, initialBytes = 0L)
        // 4 MB in 1 second -> 4.0 MB/s
        val speed1 = tracker.record(cumulativeBytes = 4L * mb, nowMillis = 2_000L)
        assertEquals(4L * mb, speed1)
        assertEquals("4.0 MB/s", FormatUtils.formatLiveSpeed(speed1))

        // Next 2 seconds: only 2 MB more -> rolling 2s window drops older sample -> ~1.0 MB/s
        val speed2 = tracker.record(cumulativeBytes = 6L * mb, nowMillis = 4_000L)
        assertEquals(1L * mb, speed2)
        assertEquals("1.0 MB/s", FormatUtils.formatLiveSpeed(speed2))

        // Speed 0 or unknown -> "—"
        assertEquals("—", FormatUtils.formatLiveSpeed(0L))
        assertEquals("—", FormatUtils.formatLiveSpeed(-100L))
        assertEquals(-1L, FormatUtils.computeEtaSeconds(remainingBytes = 50L * mb, speedBytesPerSec = 0L))
        assertNull(FormatUtils.formatEtaClock(-1L))

        // 85 seconds remaining -> "01:25"
        val speed42 = (4.2 * mb).roundToLong()
        assertEquals("4.2 MB/s", FormatUtils.formatLiveSpeed(speed42))
        val etaSec = FormatUtils.computeEtaSeconds(remainingBytes = speed42 * 85L, speedBytesPerSec = speed42)
        assertEquals(85L, etaSec)
        assertEquals("01:25", FormatUtils.formatEtaClock(etaSec))

        // Transfer size formatting: MB >= 1 MB, KB < 1 MB
        assertEquals("45.2 MB", FormatUtils.formatTransferSize((45.2 * mb).roundToLong()))
        assertEquals("120.0 MB", FormatUtils.formatTransferSize(120L * mb))
        assertEquals("512.0 KB", FormatUtils.formatTransferSize(512L * 1024L))
    }

    @Test
    fun `circular transfer progress shows formatted amount, live speed, ETA, and dash when speed is zero`() {
        val mb = 1024L * 1024L
        val transferred = (45.2 * mb).roundToLong()
        val total = 120L * mb
        val speed42 = (4.2 * mb).roundToLong()

        val progress = TransferProgressState(
            bytesTransferred = transferred,
            totalBytes = total,
            speedBytesPerSec = speed42,
            etaSeconds = 85L,
            currentFileName = "LargeGame.apk",
            completedFiles = 0,
            totalFiles = 1
        )

        composeRule.setContent {
            ApkShareTheme {
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    CircularTransferProgress(
                        progress = progress,
                        accentColor = PrimaryBlue,
                        title = "Sending to Pixel 9",
                        subtitle = "Keep phones close together",
                        directionLabel = "Sent"
                    )
                }
            }
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("transfer_live_amount")
            .performScrollTo()
            .assertIsDisplayed()
            .assertTextEquals("45.2 MB of 120.0 MB · 37%")
        composeRule.onNodeWithTag("transfer_live_speed")
            .performScrollTo()
            .assertIsDisplayed()
            .assertTextEquals("4.2 MB/s")
        composeRule.onNodeWithTag("transfer_live_eta")
            .performScrollTo()
            .assertIsDisplayed()
            .assertTextEquals("01:25 left")
    }

    @Test
    fun `circular transfer progress shows dashes when speed is zero or paused and renders paused badge`() {
        val mb = 1024L * 1024L
        val progress = TransferProgressState(
            bytesTransferred = 10L * mb,
            totalBytes = 100L * mb,
            speedBytesPerSec = 0L,
            etaSeconds = -1L,
            currentFileName = "base.apk",
            completedFiles = 1,
            totalFiles = 3
        )

        composeRule.setContent {
            ApkShareTheme {
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    CircularTransferProgress(
                        progress = progress,
                        accentColor = SuccessGreen,
                        title = "Receiving BigApp",
                        subtitle = "From Sender",
                        directionLabel = "Received",
                        isPaused = true,
                        pausedDetail = "Paused — finishing current file before pausing queue"
                    )
                }
            }
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("transfer_paused_badge").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("transfer_live_speed").performScrollTo().assertTextEquals("—")
        composeRule.onNodeWithTag("transfer_live_eta").performScrollTo().assertTextEquals("—")
    }

    @Test
    fun `circular transfer progress renders Bangla ETA and paused strings in Bangla locale`() {
        val baseContext = ApplicationProvider.getApplicationContext<Context>()
        val (bnContext, bnConfig) = LocaleHelper.createLocalizedComposeContext(
            baseContext = baseContext,
            baseConfig = baseContext.resources.configuration,
            languageCode = LocaleHelper.LANGUAGE_BANGLA
        )
        val mb = 1024L * 1024L
        val progress = TransferProgressState(
            bytesTransferred = 20L * mb,
            totalBytes = 100L * mb,
            speedBytesPerSec = (5.0 * mb).roundToLong(),
            etaSeconds = 85L,
            currentFileName = "Demo.apk",
            completedFiles = 0,
            totalFiles = 1
        )

        composeRule.setContent {
            CompositionLocalProvider(
                LocalContext provides bnContext,
                LocalConfiguration provides bnConfig
            ) {
                ApkShareTheme {
                    Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                        CircularTransferProgress(
                            progress = progress,
                            accentColor = PrimaryBlue,
                            title = "পাঠানো হচ্ছে",
                            subtitle = "ফোন কাছাকাছি রাখুন",
                            directionLabel = "পাঠানো হয়েছে"
                        )
                    }
                }
            }
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("transfer_live_eta")
            .performScrollTo()
            .assertIsDisplayed()
            .assertTextContains("01:25", substring = true)
            .assertTextContains("বাকি", substring = true)
    }

    @Test
    fun `transfer cancelled view renders title and invokes try again callback`() {
        var retried = false
        var doneClicked = false

        composeRule.setContent {
            ApkShareTheme {
                TransferCancelledView(
                    accentColor = PrimaryBlue,
                    onTryAgain = { retried = true },
                    onDone = { doneClicked = true }
                )
            }
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("transfer_cancelled_title")
            .assertIsDisplayed()
            .assertTextEquals("Transfer cancelled")
        composeRule.onNodeWithTag("transfer_try_again_button")
            .assertIsDisplayed()
            .performClick()
        assertTrue(retried)

        composeRule.onNodeWithTag("transfer_cancelled_done_button").performClick()
        assertTrue(doneClicked)
    }

    @Test
    fun `batch queue pauses cleanly after current app finishes and resumes from next app without resending`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val tempDir = createTempDirectory("pause_batch_test").toFile()
        try {
            val database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
                .allowMainThreadQueries()
                .build()
            db = database
            val server = LocalHttpApkServer()
            httpServer = server
            val storageManager = ApkStorageManager(context)
            val nearbyManager = NearbyTransferManager(context, storageManager)

            val store = ViewModelStore()
            viewModelStore = store
            val viewModel = androidx.lifecycle.ViewModelProvider(
                store,
                SenderViewModel.Factory(
                    appsRepository = InstalledAppsRepository(context),
                    apkExtractor = ApkExtractor(context),
                    nearbyManager = nearbyManager,
                    hotspotController = HotspotController(context),
                    httpServer = server,
                    settingsDataStore = SettingsDataStore(context),
                    historyRepository = TransferHistoryRepository(database.transferHistoryDao())
                )
            )[SenderViewModel::class.java]

            val apk1 = File(tempDir, "app1.apk").apply { writeBytes(ByteArray(32 * 1024) { 1 }) }
            val apk2 = File(tempDir, "app2.apk").apply { writeBytes(ByteArray(32 * 1024) { 2 }) }
            val apk3 = File(tempDir, "app3.apk").apply { writeBytes(ByteArray(32 * 1024) { 3 }) }

            val apps = listOf(
                fakeApp("com.test.one", "App One", apk1),
                fakeApp("com.test.two", "App Two", apk2),
                fakeApp("com.test.three", "App Three", apk3)
            )
            apps.forEach { viewModel.toggleAppSelection(it) }

            var extractedReady = false
            viewModel.prepareAndStartNearbyTransfer { extractedReady = true }
            awaitCondition { extractedReady }

            val firstBundle = (viewModel.extractionState.value as ExtractionUiState.Ready).bundle
            assertEquals("App One", firstBundle.appName)

            // Simulate App One actively sending
            nearbyManager.setSenderStateForTest(
                NearbySenderState.Sending(
                    peerName = "Pixel 9",
                    bundle = firstBundle,
                    progress = TransferProgressState(
                        bytesTransferred = 16 * 1024L,
                        totalBytes = firstBundle.totalSizeBytes,
                        speedBytesPerSec = 1024L * 1024L,
                        etaSeconds = 1L,
                        currentFileName = "base.apk",
                        completedFiles = 0,
                        totalFiles = 1
                    )
                )
            )
            drainMainLooper()

            // Press Pause during App One -> App One finishes cleanly, queue waits before App Two
            viewModel.pauseNearbyTransfer()
            assertTrue(viewModel.isTransferPaused.value)
            assertTrue((nearbyManager.senderState.value as NearbySenderState.Sending).isPaused)

            // App One completes
            nearbyManager.setSenderStateForTest(
                NearbySenderState.Success(
                    bundle = firstBundle,
                    peerName = "Pixel 9",
                    durationMillis = 1200L
                )
            )
            awaitCondition { viewModel.batchQueue.value.isPaused }

            val pausedQueue = viewModel.batchQueue.value
            assertTrue(pausedQueue.isPaused)
            assertFalse(pausedQueue.isFinished)
            assertEquals(1, pausedQueue.currentIndex)
            assertEquals("App Two", pausedQueue.currentDisplayName)
            assertEquals(1, pausedQueue.results.size)
            assertEquals("App One", pausedQueue.results.first().appName)

            // Resume -> should extract and queue App Two without re-sending App One
            viewModel.resumeNearbyTransfer()
            awaitCondition {
                val ready = viewModel.extractionState.value as? ExtractionUiState.Ready
                ready?.bundle?.appName == "App Two"
            }

            val resumedQueue = viewModel.batchQueue.value
            assertFalse(resumedQueue.isPaused)
            assertEquals(1, resumedQueue.currentIndex)
            assertEquals(1, resumedQueue.results.size)
            assertEquals("App Two", (viewModel.extractionState.value as ExtractionUiState.Ready).bundle.appName)

            viewModel.cancelActiveTransfer()
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun `cancel active nearby transfer sets Cancelled state on sender and receiver and supports retry`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val tempDir = createTempDirectory("cancel_nearby_test").toFile()
        try {
            val database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
                .allowMainThreadQueries()
                .build()
            db = database
            val server = LocalHttpApkServer()
            httpServer = server
            val storageManager = ApkStorageManager(context)
            val nearbyManager = NearbyTransferManager(context, storageManager)

            val store = ViewModelStore()
            viewModelStore = store
            val senderVm = androidx.lifecycle.ViewModelProvider(
                store,
                SenderViewModel.Factory(
                    appsRepository = InstalledAppsRepository(context),
                    apkExtractor = ApkExtractor(context),
                    nearbyManager = nearbyManager,
                    hotspotController = HotspotController(context),
                    httpServer = server,
                    settingsDataStore = SettingsDataStore(context),
                    historyRepository = TransferHistoryRepository(database.transferHistoryDao())
                )
            )[SenderViewModel::class.java]
            val receiverVm = androidx.lifecycle.ViewModelProvider(
                store,
                ReceiverViewModel.Factory(
                    nearbyManager = nearbyManager,
                    httpDownloader = HttpApkDownloader(context, storageManager),
                    apkInstaller = ApkInstaller(context),
                    settingsDataStore = SettingsDataStore(context),
                    historyRepository = TransferHistoryRepository(database.transferHistoryDao())
                )
            )[ReceiverViewModel::class.java]

            val apk1 = File(tempDir, "app1.apk").apply { writeBytes(ByteArray(32 * 1024) { 4 }) }
            senderVm.toggleAppSelection(fakeApp("com.test.cancel", "CancelApp", apk1))

            var ready = false
            senderVm.prepareAndStartNearbyTransfer { ready = true }
            awaitCondition { ready }

            val bundle = (senderVm.extractionState.value as ExtractionUiState.Ready).bundle
            nearbyManager.setSenderStateForTest(
                NearbySenderState.Sending(
                    peerName = "Pixel 9",
                    bundle = bundle,
                    progress = TransferProgressState(
                        bytesTransferred = 8192L,
                        totalBytes = bundle.totalSizeBytes,
                        speedBytesPerSec = 512L * 1024L,
                        etaSeconds = 1L
                    )
                )
            )
            drainMainLooper()

            senderVm.cancelOngoingNearbyTransfer()
            drainMainLooper()
            assertTrue(senderVm.nearbyState.value is NearbySenderState.Cancelled)

            // Receiver cancel transitions cleanly to NearbyReceiverState.Cancelled
            receiverVm.cancelOngoingNearbyTransfer()
            drainMainLooper()
            assertTrue(receiverVm.nearbyState.value is NearbyReceiverState.Cancelled)

            senderVm.cancelActiveTransfer()
            receiverVm.cancelAll()
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun `hotspot http downloader cancels cleanly without leaving partial files and transitions to Cancelled`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val tempDir = createTempDirectory("hotspot_cancel_test").toFile()
        try {
            val payload = ByteArray(256 * 1024) { (it % 121).toByte() }
            val apkFile = File(tempDir, "LargeHotspotApp.apk").apply { writeBytes(payload) }
            val bundle = ExtractedApkBundle(
                transferId = "hs_cancel_1",
                appName = "LargeHotspotApp",
                packageName = "com.example.large",
                versionName = "1.0",
                versionCode = 1L,
                isSplitApk = false,
                files = listOf(apkFile),
                totalSizeBytes = apkFile.length(),
                stagingDir = tempDir,
                iconBase64 = null
            )

            val server = LocalHttpApkServer()
            httpServer = server
            val port = server.start(bundle, preferredPort = 0)
            assertTrue(port > 0)

            val storageManager = ApkStorageManager(context)
            val downloader = HttpApkDownloader(context, storageManager)
            val url = "http://127.0.0.1:$port/manifest.json"

            // First verify full loopback download completes cleanly
            downloader.startDownload(url, exportToPublicDownloads = false)
            awaitCondition(timeoutMs = 5_000L) {
                downloader.state.value is HttpDownloadState.Completed ||
                    downloader.state.value is HttpDownloadState.Error
            }
            val completed = downloader.state.value as? HttpDownloadState.Completed
            assertTrue("Expected Completed, got ${downloader.state.value}", completed != null)
            assertEquals(payload.size.toLong(), completed!!.savedFiles.first().length())

            // Now simulate an active download being cancelled and verify Cancelled state & zero .part files
            downloader.setStateForTest(HttpDownloadState.Connecting(url))
            downloader.cancelActiveDownload()
            val cancelled = downloader.state.value as? HttpDownloadState.Cancelled
            assertTrue("Expected HttpDownloadState.Cancelled", cancelled != null)
            assertEquals(url, cancelled!!.url)

            val leftoverParts = context.filesDir.walkTopDown().filter { it.name.endsWith(".part") }.toList()
            assertTrue("Expected zero leftover .part files, found $leftoverParts", leftoverParts.isEmpty())
        } finally {
            httpServer?.stop()
            tempDir.deleteRecursively()
        }
    }

    private fun fakeApp(pkg: String, name: String, file: File): InstalledAppInfo = InstalledAppInfo(
        packageName = pkg,
        appName = name,
        versionName = "1.0",
        versionCode = 1L,
        totalSizeBytes = file.length(),
        installDateMillis = System.currentTimeMillis(),
        baseApkPath = file.absolutePath,
        splitApkPaths = emptyList()
    )

    private fun drainMainLooper() {
        Shadows.shadowOf(Looper.getMainLooper()).idle()
    }

    private fun awaitCondition(timeoutMs: Long = 3_000L, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!condition() && System.currentTimeMillis() < deadline) {
            drainMainLooper()
            Thread.sleep(20)
        }
        drainMainLooper()
    }
}
