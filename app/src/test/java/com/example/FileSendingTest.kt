package com.example

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Looper
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.apk.ApkExtractor
import com.example.apk.ApkStorageManager
import com.example.apk.InstalledAppsRepository
import com.example.data.db.AppDatabase
import com.example.data.db.TransferHistoryEntity
import com.example.data.db.TransferHistoryRepository
import com.example.data.prefs.SettingsDataStore
import com.example.model.ExtractedApkBundle
import com.example.model.PickedFileInfo
import com.example.model.TransferDirection
import com.example.model.TransferMethod
import com.example.model.TransferStatus
import com.example.transfer.HotspotController
import com.example.transfer.HttpApkDownloader
import com.example.transfer.HttpDownloadState
import com.example.transfer.LocalHttpApkServer
import com.example.transfer.NearbyTransferManager
import com.example.ui.screens.AppPickerScreen
import com.example.ui.screens.HistoryScreen
import com.example.ui.theme.ApkShareTheme
import com.example.ui.theme.ThemePreference
import com.example.ui.viewmodel.PickerTab
import com.example.ui.viewmodel.SenderViewModel
import com.example.util.ApkShareHelper
import com.example.util.FileTransferHelper
import com.example.util.LocaleHelper
import com.example.util.ShareableApkResult
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
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
import java.util.Random

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class FileSendingTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private var db: AppDatabase? = null
    private var httpServer: LocalHttpApkServer? = null
    private var httpDownloader: HttpApkDownloader? = null

    @Before
    fun setUp() {
        runCatching {
            val cacheField = androidx.core.content.FileProvider::class.java.getDeclaredField("sCache")
            cacheField.isAccessible = true
            (cacheField.get(null) as? MutableMap<*, *>)?.clear()
        }

        val context = ApplicationProvider.getApplicationContext<Context>()
        val outgoingRoot = File(context.cacheDir, "outgoing_apks").apply { mkdirs() }
        ShadowStatFs.registerStats(outgoingRoot.absolutePath, 65536, 65536, 65536)
        val receivedRoot = ApkStorageManager(context).getAppDownloadsRoot().apply { mkdirs() }
        ShadowStatFs.registerStats(receivedRoot.absolutePath, 65536, 65536, 65536)
    }

    @After
    fun tearDown() {
        httpDownloader?.cancel()
        httpServer?.stop()
        db?.close()
    }

    private fun createSenderViewModel(context: Context): SenderViewModel {
        val database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        db = database
        val server = LocalHttpApkServer()
        httpServer = server
        val storageManager = ApkStorageManager(context)
        return SenderViewModel(
            appsRepository = InstalledAppsRepository(context),
            apkExtractor = ApkExtractor(context),
            nearbyManager = NearbyTransferManager(context, storageManager),
            hotspotController = HotspotController(context),
            httpServer = server,
            settingsDataStore = SettingsDataStore(context),
            historyRepository = TransferHistoryRepository(database.transferHistoryDao())
        )
    }

    @Test
    fun `staging 2 photos and 1 video preserves exact raw bytes and batch queue continues past failure`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val srcDir = File(context.cacheDir, "file_batch_src").apply {
            deleteRecursively()
            mkdirs()
        }
        val rng = Random(42L)
        val photo1Bytes = ByteArray(12_345).also { rng.nextBytes(it) }
        val photo2Bytes = ByteArray(23_456).also { rng.nextBytes(it) }
        val videoBytes = ByteArray(98_765).also { rng.nextBytes(it) }

        val photo1 = File(srcDir, "IMG_001.jpg").apply { writeBytes(photo1Bytes) }
        val photo2 = File(srcDir, "IMG_002.jpg").apply { writeBytes(photo2Bytes) }
        val missingFile = File(srcDir, "deleted_doc.pdf") // intentionally does not exist
        val video1 = File(srcDir, "VID_001.mp4").apply { writeBytes(videoBytes) }

        val picked1 = PickedFileInfo(Uri.fromFile(photo1).toString(), "IMG_001.jpg", photo1.length(), "image/jpeg")
        val picked2 = PickedFileInfo(Uri.fromFile(photo2).toString(), "IMG_002.jpg", photo2.length(), "image/jpeg")
        val pickedMissing = PickedFileInfo(Uri.fromFile(missingFile).toString(), "deleted_doc.pdf", 4096L, "application/pdf")
        val pickedVideo = PickedFileInfo(Uri.fromFile(video1).toString(), "VID_001.mp4", video1.length(), "video/mp4")

        // Verify bit-exact staging with zero compression or resizing
        val staged1 = FileTransferHelper.stagePickedFile(context, picked1, cleanPrevious = true).getOrThrow()
        val staged2 = FileTransferHelper.stagePickedFile(context, picked2, cleanPrevious = false).getOrThrow()
        val stagedVideo = FileTransferHelper.stagePickedFile(context, pickedVideo, cleanPrevious = false).getOrThrow()

        assertTrue(staged1.isGenericFile)
        assertEquals("IMG_001.jpg", staged1.appName)
        assertEquals("image/jpeg", staged1.mimeType)
        assertEquals(photo1Bytes.size.toLong(), staged1.totalSizeBytes)
        assertArrayEquals(photo1Bytes, staged1.files.first().readBytes())

        assertEquals("IMG_002.jpg", staged2.appName)
        assertEquals(photo2Bytes.size.toLong(), staged2.totalSizeBytes)
        assertArrayEquals(photo2Bytes, staged2.files.first().readBytes())

        assertEquals("VID_001.mp4", stagedVideo.appName)
        assertEquals("video/mp4", stagedVideo.mimeType)
        assertEquals(videoBytes.size.toLong(), stagedVideo.totalSizeBytes)
        assertArrayEquals(videoBytes, stagedVideo.files.first().readBytes())

        // Run a 4-file batch queue with 1 missing file in the middle and verify continuation + summary
        val vm = createSenderViewModel(context)
        vm.addPickedFileItems(listOf(picked1, picked2, pickedMissing, pickedVideo))
        assertEquals(4, vm.pickedFiles.value.size)

        var readyCalled = false
        vm.startHotspotFileModeDirect(context) { readyCalled = true }
        waitUntil { readyCalled }

        assertTrue(vm.batchQueue.value.isFileBatch)
        assertEquals(4, vm.batchQueue.value.totalCount)
        assertEquals("IMG_001.jpg", vm.batchQueue.value.currentDisplayName)

        // Advance past photo1 -> photo2
        vm.advanceHotspotBatchQueue(markCurrentSucceeded = true)
        waitUntil { vm.batchQueue.value.currentIndex == 1 && !vm.batchQueue.value.isPreparingNext }
        assertEquals("IMG_002.jpg", vm.batchQueue.value.currentDisplayName)

        // Advance past photo2 -> pickedMissing fails automatically -> jumps straight to VID_001.mp4
        vm.advanceHotspotBatchQueue(markCurrentSucceeded = true)
        waitUntil { vm.batchQueue.value.currentIndex == 3 && !vm.batchQueue.value.isPreparingNext }
        assertEquals("VID_001.mp4", vm.batchQueue.value.currentDisplayName)
        assertEquals(2, vm.batchQueue.value.sentCount)
        assertEquals(1, vm.batchQueue.value.failedItems.size)
        assertEquals("deleted_doc.pdf", vm.batchQueue.value.failedItems.first().appName)

        // Complete video -> batch finishes with 3 sent and 1 failed
        vm.advanceHotspotBatchQueue(markCurrentSucceeded = true)
        waitUntil { vm.batchQueue.value.isFinished }
        assertEquals(3, vm.batchQueue.value.sentCount)
        assertEquals(1, vm.batchQueue.value.failedItems.size)
        assertEquals("deleted_doc.pdf", vm.batchQueue.value.failedItems.first().appName)
    }

    @Test
    fun `hotspot HTTP server and downloader transfer a PDF with exact filename and exact bytes`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val storageManager = ApkStorageManager(context)

        val srcDir = File(context.cacheDir, "hotspot_pdf_src").apply {
            deleteRecursively()
            mkdirs()
        }
        val pdfPayload = "%PDF-1.7\n1 0 obj\n<< /Type /Catalog >>\nendobj\n".toByteArray(Charsets.UTF_8) +
            ByteArray(32_768) { (it % 251).toByte() }
        val pdfFile = File(srcDir, "Invoice_2026.pdf").apply { writeBytes(pdfPayload) }

        val bundle = ExtractedApkBundle(
            transferId = "pdf001",
            appName = "Invoice_2026.pdf",
            packageName = "",
            versionName = "",
            versionCode = 0L,
            isSplitApk = false,
            files = listOf(pdfFile),
            totalSizeBytes = pdfFile.length(),
            stagingDir = srcDir,
            iconBase64 = null,
            isGenericFile = true,
            mimeType = "application/pdf"
        )

        val server = LocalHttpApkServer().also { httpServer = it }
        val port = server.start(bundle, preferredPort = 0)
        assertTrue("HTTP server must bind a valid port", port > 0)

        val downloader = HttpApkDownloader(context, storageManager).also { httpDownloader = it }
        downloader.startDownload(
            rawInputUrl = "http://127.0.0.1:$port/manifest.json",
            exportToPublicDownloads = false
        )

        waitUntil(timeoutMs = 8_000L) {
            downloader.state.value is HttpDownloadState.Completed || downloader.state.value is HttpDownloadState.Error
        }

        val finalState = downloader.state.value
        assertTrue("Expected Completed but got $finalState", finalState is HttpDownloadState.Completed)
        val completed = finalState as HttpDownloadState.Completed
        assertTrue(completed.metadata.isGenericFile)
        assertEquals("Invoice_2026.pdf", completed.metadata.appName)
        assertEquals("application/pdf", completed.metadata.mimeType)
        assertEquals(1, completed.savedFiles.size)

        val savedPdf = completed.savedFiles.first()
        assertTrue("Saved PDF must keep .pdf extension, not .apk", savedPdf.name.endsWith(".pdf"))
        assertFalse("Saved PDF must never have .apk appended", savedPdf.name.endsWith(".apk"))
        assertEquals(pdfPayload.size.toLong(), savedPdf.length())
        assertArrayEquals(pdfPayload, savedPdf.readBytes())
    }

    @Test
    fun `tapping received photo in History opens system viewer ACTION_VIEW via FileProvider`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val storageManager = ApkStorageManager(context)
        val downloadsRoot = storageManager.getAppDownloadsRoot().apply { mkdirs() }
        val receivedPhoto = File(downloadsRoot, "sunset_photo.jpg").apply {
            writeBytes(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(), 1, 2, 3, 4))
        }

        val entity = TransferHistoryEntity(
            id = 77L,
            appName = "sunset_photo.jpg",
            packageName = "",
            versionName = "",
            sizeBytes = receivedPhoto.length(),
            direction = TransferDirection.RECEIVED,
            method = TransferMethod.NEARBY,
            peerDeviceName = "Pixel 9",
            status = TransferStatus.SUCCESS,
            timestampMillis = System.currentTimeMillis(),
            isGenericFile = true,
            mimeType = "image/jpeg",
            savedFilePath = receivedPhoto.absolutePath
        )

        val resolvedFile = FileTransferHelper.resolveHistoryFile(context, entity, storageManager)
        assertNotNull(resolvedFile)
        assertEquals(receivedPhoto.absolutePath, resolvedFile!!.absolutePath)

        val viewIntent = FileTransferHelper.createViewFileIntent(context, resolvedFile, entity.mimeType)
        assertEquals(Intent.ACTION_VIEW, viewIntent.action)
        assertEquals("image/jpeg", viewIntent.type)
        assertNotNull(viewIntent.data)
        assertEquals("content", viewIntent.data!!.scheme)
        assertEquals("${context.packageName}.fileprovider", viewIntent.data!!.authority)
        assertTrue((viewIntent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION) != 0)

        var openedEntity: TransferHistoryEntity? = null
        composeTestRule.setContent {
            ApkShareTheme(themePreference = ThemePreference.LIGHT, dynamicColor = false) {
                HistoryScreen(
                    history = listOf(entity),
                    onDeleteItem = {},
                    onClearAll = {},
                    onOpenFileItem = { openedEntity = it }
                )
            }
        }

        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("sunset_photo.jpg").assertIsDisplayed()
        composeTestRule.onNodeWithTag("history_item_card_77").performClick()
        composeTestRule.waitForIdle()

        assertEquals(entity.id, openedEntity?.id)
    }

    @Test
    @Config(sdk = [36], qualifiers = "w411dp-h891dp")
    fun `AppPickerScreen Apps and Files tabs display picked files, allow removing, and localize to Bangla`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        LocaleHelper.applyAppLocale(context, LocaleHelper.LANGUAGE_BANGLA)
        val vm = createSenderViewModel(context)

        val srcDir = File(context.cacheDir, "ui_files_bn").apply { mkdirs() }
        val f1 = File(srcDir, "pic1.jpg").apply { writeBytes("jpg1".toByteArray()) }
        val f2 = File(srcDir, "pic2.png").apply { writeBytes("png2".toByteArray()) }
        val f3 = File(srcDir, "clip.mp4").apply { writeBytes("mp43".toByteArray()) }

        vm.addPickedFileItems(
            listOf(
                PickedFileInfo(Uri.fromFile(f1).toString(), "pic1.jpg", f1.length(), "image/jpeg"),
                PickedFileInfo(Uri.fromFile(f2).toString(), "pic2.png", f2.length(), "image/png"),
                PickedFileInfo(Uri.fromFile(f3).toString(), "clip.mp4", f3.length(), "video/mp4")
            )
        )

        composeTestRule.setContent {
            val baseContext = LocalContext.current
            val baseConfig = LocalConfiguration.current
            val (localizedContext, localizedConfig) = remember(baseContext, baseConfig) {
                LocaleHelper.createLocalizedComposeContext(
                    baseContext = baseContext,
                    baseConfig = baseConfig,
                    languageCode = LocaleHelper.LANGUAGE_BANGLA
                )
            }
            CompositionLocalProvider(
                LocalContext provides localizedContext,
                LocalConfiguration provides localizedConfig
            ) {
                ApkShareTheme(themePreference = ThemePreference.LIGHT, dynamicColor = false) {
                    AppPickerScreen(
                        viewModel = vm,
                        onBack = {},
                        onProceedToTransfer = {}
                    )
                }
            }
        }

        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("picker_tab_apps").assertIsDisplayed()
        composeTestRule.onNodeWithTag("picker_tab_files").assertIsDisplayed()

        // Switch to Files tab
        composeTestRule.onNodeWithTag("picker_tab_files").performClick()
        composeTestRule.waitForIdle()

        assertEquals(PickerTab.FILES, vm.activeTab.value)
        composeTestRule.onNodeWithText("pic1.jpg").assertIsDisplayed()
        composeTestRule.onNodeWithText("clip.mp4").assertIsDisplayed()
        composeTestRule.onNodeWithText("৩টি ফাইল নির্বাচিত").assertIsDisplayed()

        // Remove pic2.png (index 1) using its X button
        composeTestRule.onNodeWithTag("remove_picked_file_1").performClick()
        composeTestRule.waitForIdle()
        assertEquals(2, vm.pickedFiles.value.size)
        composeTestRule.onNodeWithText("২টি ফাইল নির্বাচিত").assertIsDisplayed()

        // Switch back to Apps tab cleanly
        composeTestRule.onNodeWithTag("picker_tab_apps").performClick()
        composeTestRule.waitForIdle()
        assertEquals(PickerTab.APPS, vm.activeTab.value)
    }

    @Test
    @Config(sdk = [28])
    fun `saveGenericFileToPublicDownloads and multi-file share preserve extensions and MIME types`() {
        runBlocking {
            val context = ApplicationProvider.getApplicationContext<Context>()
            LocaleHelper.applyAppLocale(context, LocaleHelper.LANGUAGE_ENGLISH)

            val srcDir = File(context.cacheDir, "generic_save_share").apply {
                deleteRecursively()
                mkdirs()
            }
            val outgoingDir = File(context.cacheDir, "outgoing_apks/test_share").apply {
                deleteRecursively()
                mkdirs()
            }
            val imgBytes = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8)
            val vidBytes = byteArrayOf(9, 10, 11, 12, 13, 14)
            val img1 = File(outgoingDir, "holiday.jpg").apply { writeBytes(imgBytes) }
            val img2 = File(outgoingDir, "beach.jpg").apply { writeBytes(imgBytes) }
            val vid1 = File(outgoingDir, "waves.mp4").apply { writeBytes(vidBytes) }

            @Suppress("DEPRECATION")
            val targetDir = File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                "APK Share"
            ).apply { deleteRecursively() }

            val saved1 = FileTransferHelper.saveGenericFileToPublicDownloads(context, img1, "holiday.jpg", "image/jpeg")
            val saved2 = FileTransferHelper.saveGenericFileToPublicDownloads(context, img1, "holiday.jpg", "image/jpeg")

            assertTrue(saved1.isSuccess)
            assertEquals("holiday.jpg", saved1.getOrThrow())
            assertTrue(saved2.isSuccess)
            assertEquals("holiday_1.jpg", saved2.getOrThrow())
            assertArrayEquals(imgBytes, File(targetDir, "holiday.jpg").readBytes())
            assertArrayEquals(imgBytes, File(targetDir, "holiday_1.jpg").readBytes())

            // Multi-file share of 2 JPEGs keeps exact image/jpeg MIME type; JPEG + PNG generalizes to image/*
            val photoShareables = listOf(
                ShareableApkResult(img1, "holiday.jpg", false, "image/jpeg"),
                ShareableApkResult(img2, "beach.jpg", false, "image/jpeg")
            )
            val photoChooser = ApkShareHelper.createMultipleShareChooserIntent(context, photoShareables)
            val photoInner = getParcelableExtraCompat(photoChooser, Intent.EXTRA_INTENT, Intent::class.java)
            assertNotNull(photoInner)
            assertEquals(Intent.ACTION_SEND_MULTIPLE, photoInner!!.action)
            assertEquals("image/jpeg", photoInner.type)

            val jpgAndPng = listOf(
                ShareableApkResult(img1, "holiday.jpg", false, "image/jpeg"),
                ShareableApkResult(img2, "beach.png", false, "image/png")
            )
            val jpgAndPngChooser = ApkShareHelper.createMultipleShareChooserIntent(context, jpgAndPng)
            val jpgAndPngInner = getParcelableExtraCompat(jpgAndPngChooser, Intent.EXTRA_INTENT, Intent::class.java)
            assertEquals("image/*", jpgAndPngInner?.type)

            // Mixed photo + video uses */* MIME type
            val mixedShareables = photoShareables + ShareableApkResult(vid1, "waves.mp4", false, "video/mp4")
            val mixedChooser = ApkShareHelper.createMultipleShareChooserIntent(context, mixedShareables)
            val mixedInner = getParcelableExtraCompat(mixedChooser, Intent.EXTRA_INTENT, Intent::class.java)
            assertNotNull(mixedInner)
            assertEquals("*/*", mixedInner!!.type)
            val uris = getParcelableArrayListExtraCompat(mixedInner, Intent.EXTRA_STREAM, Uri::class.java)
            assertEquals(3, uris?.size)

            srcDir.deleteRecursively()
        }
    }

    private suspend fun waitUntil(timeoutMs: Long = 5000L, condition: () -> Boolean) {
        val start = System.currentTimeMillis()
        while (!condition() && (System.currentTimeMillis() - start) < timeoutMs) {
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            delay(25L)
        }
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        assertTrue("Condition was not met within ${timeoutMs}ms", condition())
    }

    @Suppress("DEPRECATION")
    private fun <T> getParcelableExtraCompat(intent: Intent, name: String, clazz: Class<T>): T? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(name, clazz)
        } else {
            clazz.cast(intent.getParcelableExtra(name))
        }
    }

    @Suppress("DEPRECATION", "UNCHECKED_CAST")
    private fun <T : android.os.Parcelable> getParcelableArrayListExtraCompat(
        intent: Intent,
        name: String,
        clazz: Class<T>
    ): ArrayList<T>? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableArrayListExtra(name, clazz)
        } else {
            intent.getParcelableArrayListExtra(name)
        }
    }
}
