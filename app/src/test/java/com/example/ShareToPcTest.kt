package com.example

import android.app.Application
import android.content.Context
import android.net.Uri
import android.os.Looper
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.apk.ApkExtractor
import com.example.apk.ApkStorageManager
import com.example.apk.InstalledAppsRepository
import com.example.data.db.AppDatabase
import com.example.data.db.TransferHistoryRepository
import com.example.data.prefs.SettingsDataStore
import com.example.model.InstalledAppInfo
import com.example.model.PickedFileInfo
import com.example.transfer.HotspotController
import com.example.transfer.LocalHttpApkServer
import com.example.transfer.NearbyTransferManager
import com.example.transfer.PcShareItem
import com.example.ui.screens.AppPickerScreen
import com.example.ui.screens.ShareToPcScreen
import com.example.ui.theme.ApkShareTheme
import com.example.ui.viewmodel.PickerTab
import com.example.ui.viewmodel.SenderViewModel
import com.example.util.FormatUtils
import com.example.util.LocaleHelper
import com.example.util.PermissionHelper
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
import java.net.HttpURLConnection
import java.net.URL
import java.util.Random

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ShareToPcTest {

    @get:Rule
    val composeRule = createComposeRule()

    private var db: AppDatabase? = null
    private var httpServer: LocalHttpApkServer? = null

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        LocaleHelper.saveLanguageSync(context, LocaleHelper.LANGUAGE_ENGLISH)
        val outgoingRoot = File(context.cacheDir, "outgoing_apks").apply { mkdirs() }
        ShadowStatFs.registerStats(outgoingRoot.absolutePath, 100_000, 80_000, 80_000)
        val receivedRoot = ApkStorageManager(context).getAppDownloadsRoot().apply { mkdirs() }
        ShadowStatFs.registerStats(receivedRoot.absolutePath, 100_000, 80_000, 80_000)
    }

    @After
    fun tearDown() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        LocaleHelper.saveLanguageSync(context, LocaleHelper.LANGUAGE_ENGLISH)
        httpServer?.stop()
        httpServer = null
        db?.close()
        db = null
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

    private fun deterministicBytes(size: Int, seed: Long): ByteArray {
        return ByteArray(size).also { Random(seed).nextBytes(it) }
    }

    private suspend fun waitUntil(timeoutMs: Long = 8_000L, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            if (condition()) return
            delay(40L)
        }
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        assertTrue("Condition timed out after ${timeoutMs}ms", condition())
    }

    @Test
    fun `selecting 2 apps and 1 photo serves all 3 on index page and downloads exact bytes`(): Unit = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val srcDir = File(context.cacheDir, "pc_share_src").apply {
            deleteRecursively()
            mkdirs()
        }

        val app1Bytes = deterministicBytes(32_000, seed = 101L)
        val app1Base = File(srcDir, "calc_base.apk").apply { writeBytes(app1Bytes) }
        val app1 = InstalledAppInfo(
            packageName = "com.test.calc",
            appName = "Quick Calc",
            versionName = "2.1",
            versionCode = 21L,
            totalSizeBytes = app1Base.length(),
            installDateMillis = 1_700_000_000_000L,
            baseApkPath = app1Base.absolutePath,
            splitApkPaths = emptyList()
        )

        val splitBaseBytes = deterministicBytes(24_000, seed = 202L)
        val splitConfigBytes = deterministicBytes(14_000, seed = 203L)
        val app2Base = File(srcDir, "notes_base.apk").apply { writeBytes(splitBaseBytes) }
        val app2Config = File(srcDir, "split_arm64.apk").apply { writeBytes(splitConfigBytes) }
        val app2 = InstalledAppInfo(
            packageName = "com.test.notes",
            appName = "Smart Notes",
            versionName = "3.4",
            versionCode = 34L,
            totalSizeBytes = app2Base.length() + app2Config.length(),
            installDateMillis = 1_700_000_100_000L,
            baseApkPath = app2Base.absolutePath,
            splitApkPaths = listOf(app2Config.absolutePath)
        )

        val photoBytes = deterministicBytes(48_500, seed = 303L)
        val photoFile = File(srcDir, "Vacation_Photo.jpg").apply { writeBytes(photoBytes) }
        val pickedPhoto = PickedFileInfo(
            uriString = Uri.fromFile(photoFile).toString(),
            displayName = "Vacation_Photo.jpg",
            sizeBytes = photoFile.length(),
            mimeType = "image/jpeg"
        )

        val vm = createSenderViewModel(context)
        vm.selectApp(app1)
        vm.selectApp(app2)
        vm.addPickedFileItems(listOf(pickedPhoto))

        var ready = false
        vm.startPcShareModeDirect(context) { ready = true }
        waitUntil { ready }

        assertTrue(vm.pcShareModeActive.value)
        assertEquals(3, vm.pcShareItems.value.size)

        val serverStatus = vm.httpServerStatus.value
        assertTrue("HTTP server must be running", serverStatus.isRunning)
        val port = serverStatus.port

        // Fetch "/" HTML index page (no login/password)
        val indexConn = (URL("http://127.0.0.1:$port/").openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 5000
            readTimeout = 5000
        }
        assertEquals(200, indexConn.responseCode)
        val html = indexConn.inputStream.bufferedReader(Charsets.UTF_8).readText()
        indexConn.disconnect()

        assertTrue(html.contains("Quick Calc"))
        assertTrue(html.contains("Smart Notes"))
        assertTrue(html.contains("Vacation_Photo.jpg"))
        assertTrue(html.contains("/item/0/"))
        assertTrue(html.contains("/item/1/"))
        assertTrue(html.contains("/item/2/"))
        assertTrue(html.contains("Local hotspot only"))
        assertTrue(html.contains(FormatUtils.formatBytes(vm.pcShareItems.value[0].sizeBytes)))
        assertTrue(html.contains(FormatUtils.formatBytes(vm.pcShareItems.value[2].sizeBytes)))

        // Download each item and compare exact bytes with staged files
        vm.pcShareItems.value.forEachIndexed { index, item ->
            val downloadConn = (URL("http://127.0.0.1:$port/item/$index/${item.file.name}").openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 5000
                readTimeout = 5000
            }
            assertEquals(200, downloadConn.responseCode)
            val disposition = downloadConn.getHeaderField("Content-Disposition").orEmpty()
            assertTrue("Expected filename in Content-Disposition, got: $disposition", disposition.contains(item.file.name))
            val downloadedBytes = downloadConn.inputStream.readBytes()
            downloadConn.disconnect()
            assertArrayEquals("Mismatch for item $index (${item.displayName})", item.file.readBytes(), downloadedBytes)
        }

        // Verify raw photo and single APK bytes explicitly
        val item0Conn = (URL("http://127.0.0.1:$port/item/0").openConnection() as HttpURLConnection)
        assertArrayEquals(app1Bytes, item0Conn.inputStream.readBytes())
        item0Conn.disconnect()

        val item2Conn = (URL("http://127.0.0.1:$port/item/2").openConnection() as HttpURLConnection)
        assertArrayEquals(photoBytes, item2Conn.inputStream.readBytes())
        item2Conn.disconnect()

        // Verify live status updated after serving
        val afterStatus = vm.httpServerStatus.value
        assertTrue("Expected bytesServed > 0", afterStatus.bytesServed > 0L)
        assertEquals("Vacation_Photo.jpg", afterStatus.currentFileName)
        assertEquals(0, afterStatus.activeClients)
        assertEquals(0L, afterStatus.speedBytesPerSec)

        // Stopping sharing turns off the HTTP server and clears PC share items
        vm.stopPcShare()
        assertFalse(vm.pcShareModeActive.value)
        assertFalse(vm.httpServerStatus.value.isRunning)
        assertTrue(vm.pcShareItems.value.isEmpty())
    }

    @Test
    fun `server supports HTTP Range 206 resume and tracks live status`() {
        val tempDir = File(ApplicationProvider.getApplicationContext<Context>().cacheDir, "pc_range_src").apply {
            deleteRecursively()
            mkdirs()
        }
        val payload = deterministicBytes(180_000, seed = 777L)
        val zipFile = File(tempDir, "archive_backup.zip").apply { writeBytes(payload) }

        val server = LocalHttpApkServer().also { httpServer = it }
        val port = server.startPcShare(
            items = listOf(
                PcShareItem(
                    displayName = "archive_backup.zip",
                    subtitle = "application/zip",
                    file = zipFile,
                    mimeType = "application/zip",
                    isGenericFile = true
                )
            ),
            preferredPort = 0
        )
        assertTrue(port > 0)

        val offset = 60_000
        val conn = (URL("http://127.0.0.1:$port/file").openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            setRequestProperty("Range", "bytes=$offset-")
            connectTimeout = 5000
            readTimeout = 5000
        }
        assertEquals(206, conn.responseCode)
        assertEquals("bytes $offset-${payload.size - 1}/${payload.size}", conn.getHeaderField("Content-Range"))
        val partial = conn.inputStream.readBytes()
        conn.disconnect()

        val expectedTail = payload.copyOfRange(offset, payload.size)
        assertArrayEquals(expectedTail, partial)

        assertEquals(zipFile.name, server.status.value.currentFileName)
        assertEquals(payload.size.toLong(), server.status.value.currentFileBytesServed)
        assertTrue(server.status.value.bytesServed >= expectedTail.size.toLong())

        // Out-of-range request returns 416
        val invalidConn = (URL("http://127.0.0.1:$port/item/0").openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            setRequestProperty("Range", "bytes=${payload.size + 10}-")
        }
        assertEquals(416, invalidConn.responseCode)
        invalidConn.disconnect()
    }

    @Test
    fun `appPicker shows Share to PC button and cross-tab indicator`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val srcDir = File(context.cacheDir, "picker_pc_src").apply {
            deleteRecursively()
            mkdirs()
        }
        val apkFile = File(srcDir, "sample.apk").apply { writeBytes(deterministicBytes(4096, 11L)) }
        val photoFile = File(srcDir, "pic.jpg").apply { writeBytes(deterministicBytes(2048, 22L)) }

        val vm = createSenderViewModel(context)
        vm.selectApp(
            InstalledAppInfo(
                packageName = "com.test.sample",
                appName = "Sample App",
                versionName = "1.0",
                versionCode = 1L,
                totalSizeBytes = apkFile.length(),
                installDateMillis = 1_700_000_000_000L,
                baseApkPath = apkFile.absolutePath,
                splitApkPaths = emptyList()
            )
        )
        vm.addPickedFileItems(
            listOf(
                PickedFileInfo(
                    uriString = Uri.fromFile(photoFile).toString(),
                    displayName = "pic.jpg",
                    sizeBytes = photoFile.length(),
                    mimeType = "image/jpeg"
                )
            )
        )

        var navigatedToPcShare = false
        composeRule.setContent {
            ApkShareTheme {
                AppPickerScreen(
                    viewModel = vm,
                    onBack = {},
                    onProceedToTransfer = {},
                    onProceedToPcShare = { navigatedToPcShare = true }
                )
            }
        }

        composeRule.waitForIdle()
        composeRule.onNodeWithTag("share_to_pc_button").assertIsDisplayed()
        composeRule.onNodeWithTag("picker_cross_tab_pc_note").assertIsDisplayed()

        // Switch to Files tab and confirm Share to PC button is also available there
        vm.selectTab(PickerTab.FILES)
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("share_to_pc_button").assertIsDisplayed()
        composeRule.onNodeWithTag("picker_cross_tab_pc_note").assertIsDisplayed()

        // Tapping Share to PC stages both items and triggers navigation callback
        composeRule.onNodeWithTag("share_to_pc_button").performClick()
        composeRule.waitUntil(timeoutMillis = 5_000L) {
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            navigatedToPcShare
        }
        assertTrue(vm.pcShareModeActive.value)
        assertEquals(2, vm.pcShareItems.value.size)
    }

    @Test
    fun `shareToPcScreen displays numbered steps safety note live status and Stop sharing stops server`(): Unit = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val app = ApplicationProvider.getApplicationContext<Application>()
        Shadows.shadowOf(app).grantPermissions(*PermissionHelper.getHotspotPermissions().toTypedArray())

        val srcDir = File(context.cacheDir, "pc_ui_src").apply {
            deleteRecursively()
            mkdirs()
        }
        val docBytes = deterministicBytes(8192, seed = 99L)
        val docFile = File(srcDir, "Report.pdf").apply { writeBytes(docBytes) }
        val picked = PickedFileInfo(
            uriString = Uri.fromFile(docFile).toString(),
            displayName = "Report.pdf",
            sizeBytes = docFile.length(),
            mimeType = "application/pdf"
        )

        val vm = createSenderViewModel(context)
        vm.addPickedFileItems(listOf(picked))
        var ready = false
        vm.startPcShareModeDirect(context) { ready = true }
        waitUntil { ready }

        var exitedScreen = false
        composeRule.setContent {
            ApkShareTheme {
                ShareToPcScreen(
                    viewModel = vm,
                    onDone = { exitedScreen = true }
                )
            }
        }

        composeRule.waitForIdle()
        composeRule.onNodeWithTag("pc_share_step_1_text").performScrollTo().assertTextContains("1.", substring = true)
        composeRule.onNodeWithTag("pc_share_step_2_text").performScrollTo().assertTextContains("2.", substring = true)
        composeRule.onNodeWithTag("pc_share_step_3_text").performScrollTo().assertTextContains("3.", substring = true)
        composeRule.onNodeWithTag("pc_share_url_text").performScrollTo().assertTextContains("http://", substring = true)
        composeRule.onNodeWithTag("pc_share_qr_image").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("pc_share_live_status_card").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("pc_share_safety_note_card").performScrollTo().assertIsDisplayed()

        // Download file while screen is active and verify live status text reflects download
        val port = vm.httpServerStatus.value.port
        val conn = (URL("http://127.0.0.1:$port/file").openConnection() as HttpURLConnection)
        assertArrayEquals(docBytes, conn.inputStream.readBytes())
        conn.disconnect()

        waitUntil { vm.httpServerStatus.value.bytesServed >= docBytes.size.toLong() }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("pc_share_live_file_status")
            .performScrollTo()
            .assertTextContains("Report.pdf", substring = true)

        // Click Stop sharing -> server stops and screen calls onDone
        composeRule.onNodeWithTag("pc_stop_sharing_button").performScrollTo().performClick()
        composeRule.waitForIdle()

        assertTrue(exitedScreen)
        assertFalse(vm.httpServerStatus.value.isRunning)
        assertFalse(vm.pcShareModeActive.value)
        assertTrue(vm.pcShareItems.value.isEmpty())
    }

    @Test
    fun `shareToPcScreen renders properly in Bangla localization`() {
        runBlocking {
            val context = ApplicationProvider.getApplicationContext<Context>()
            val app = ApplicationProvider.getApplicationContext<Application>()
            Shadows.shadowOf(app).grantPermissions(*PermissionHelper.getHotspotPermissions().toTypedArray())
            LocaleHelper.applyAppLocale(context, LocaleHelper.LANGUAGE_BANGLA)

            val srcDir = File(context.cacheDir, "pc_bn_src").apply {
                deleteRecursively()
                mkdirs()
            }
            val sampleFile = File(srcDir, "Notes.txt").apply { writeText("Hello PC") }
            val vm = createSenderViewModel(context)
            vm.addPickedFileItems(
                listOf(
                    PickedFileInfo(
                        uriString = Uri.fromFile(sampleFile).toString(),
                        displayName = "Notes.txt",
                        sizeBytes = sampleFile.length(),
                        mimeType = "text/plain"
                    )
                )
            )
            var ready = false
            vm.startPcShareModeDirect(context) { ready = true }
            waitUntil { ready }

            composeRule.setContent {
                val baseConfig = LocalConfiguration.current
                val (localizedCtx, localizedCfg) = remember(baseConfig) {
                    LocaleHelper.createLocalizedComposeContext(
                        baseContext = context,
                        baseConfig = baseConfig,
                        languageCode = LocaleHelper.LANGUAGE_BANGLA
                    )
                }
                CompositionLocalProvider(
                    LocalContext provides localizedCtx,
                    LocalConfiguration provides localizedCfg
                ) {
                    ApkShareTheme {
                        ShareToPcScreen(viewModel = vm, onDone = {})
                    }
                }
            }

            composeRule.waitForIdle()
            composeRule.onNodeWithTag("pc_share_step_1_text").performScrollTo().assertTextContains("১.", substring = true)
            composeRule.onNodeWithTag("pc_share_step_2_text").performScrollTo().assertTextContains("২.", substring = true)
            composeRule.onNodeWithTag("pc_share_step_3_text").performScrollTo().assertTextContains("৩.", substring = true)
            composeRule.onNodeWithTag("pc_stop_sharing_button").performScrollTo().assertTextContains("শেয়ারিং বন্ধ করুন")
        }
    }
}
