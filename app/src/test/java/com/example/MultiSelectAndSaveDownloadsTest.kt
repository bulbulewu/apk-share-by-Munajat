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
import com.example.data.db.TransferHistoryRepository
import com.example.data.prefs.SettingsDataStore
import com.example.model.InstalledAppInfo
import com.example.transfer.HotspotController
import com.example.transfer.LocalHttpApkServer
import com.example.transfer.NearbyTransferManager
import com.example.ui.screens.AppPickerScreen
import com.example.ui.theme.ApkShareTheme
import com.example.ui.theme.ThemePreference
import com.example.ui.viewmodel.AppSortOrder
import com.example.ui.viewmodel.SenderViewModel
import com.example.util.ApkShareHelper
import com.example.util.LocaleHelper
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.After
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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class MultiSelectAndSaveDownloadsTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private var db: AppDatabase? = null
    private var httpServer: LocalHttpApkServer? = null

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
    }

    @After
    fun tearDown() {
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

    private fun fakeApp(
        pkg: String,
        name: String,
        version: String = "1.2",
        file: File,
        splits: List<File> = emptyList()
    ): InstalledAppInfo = InstalledAppInfo(
        packageName = pkg,
        appName = name,
        versionName = version,
        versionCode = 12L,
        totalSizeBytes = file.length() + splits.sumOf { it.length() },
        installDateMillis = System.currentTimeMillis(),
        baseApkPath = file.absolutePath,
        splitApkPaths = splits.map { it.absolutePath }
    )

    @Test
    fun `multi-select toggles apps, clears all, and survives search and sort changes`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val vm = createSenderViewModel(context)
        val tmpDir = File(context.cacheDir, "multi_select_test").apply { mkdirs() }
        val apkA = File(tmpDir, "a.apk").apply { writeBytes("apkA".toByteArray()) }
        val apkB = File(tmpDir, "b.apk").apply { writeBytes("apkB".toByteArray()) }
        val apkC = File(tmpDir, "c.apk").apply { writeBytes("apkC".toByteArray()) }

        val appA = fakeApp("com.test.alpha", "Alpha", "1.0", apkA)
        val appB = fakeApp("com.test.beta", "Beta", "2.0", apkB)
        val appC = fakeApp("com.test.gamma", "Gamma", "3.0", apkC)

        vm.toggleAppSelection(appA)
        vm.toggleAppSelection(appB)
        vm.toggleAppSelection(appC)
        assertEquals(setOf("com.test.alpha", "com.test.beta", "com.test.gamma"), vm.selectedPackageNames.value)

        // Toggle appB off
        vm.toggleAppSelection(appB)
        assertEquals(setOf("com.test.alpha", "com.test.gamma"), vm.selectedPackageNames.value)

        // Search and sort must NOT clear selection
        vm.updateSearchQuery("Alpha")
        vm.updateSortOrder(AppSortOrder.SIZE)
        vm.updateSortOrder(AppSortOrder.INSTALL_DATE)
        vm.updateSearchQuery("")
        assertEquals(setOf("com.test.alpha", "com.test.gamma"), vm.selectedPackageNames.value)

        // Clear deselects all
        vm.clearSelection()
        assertTrue(vm.selectedPackageNames.value.isEmpty())
    }

    @Test
    fun `multi-share uses ACTION_SEND_MULTIPLE with all FileProvider URIs and single-share keeps ACTION_SEND`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        LocaleHelper.applyAppLocale(context, LocaleHelper.LANGUAGE_ENGLISH)

        val srcDir = File(context.cacheDir, "multi_share_src").apply {
            deleteRecursively()
            mkdirs()
        }
        val base1 = File(srcDir, "one.apk").apply { writeBytes("PK\u0003\u0004one".toByteArray()) }
        val base2 = File(srcDir, "two_base.apk").apply { writeBytes("PK\u0003\u0004two_base".toByteArray()) }
        val split2 = File(srcDir, "split_arm64.apk").apply { writeBytes("PK\u0003\u0004two_split".toByteArray()) }

        val app1 = fakeApp("com.demo.one", "AppOne", "1.0", base1)
        val app2 = fakeApp("com.demo.two", "AppTwo", "2.5", base2, listOf(split2))

        val extractor = ApkExtractor(context)
        val shareables = runBlocking {
            val b1 = extractor.extractBundle(app1, cleanPrevious = true).getOrThrow()
            val s1 = ApkShareHelper.prepareShareableFile(b1)
            val b2 = extractor.extractBundle(app2, cleanPrevious = false).getOrThrow()
            val s2 = ApkShareHelper.prepareShareableFile(b2)
            listOf(s1, s2)
        }

        assertTrue("First extracted file must still exist after second extraction", shareables[0].file.exists())
        assertTrue("Second extracted archive must exist", shareables[1].file.exists())

        // Single item still uses ACTION_SEND
        val singleChooser = ApkShareHelper.createMultipleShareChooserIntent(context, listOf(shareables[0]))
        val singleInner = getParcelableExtraCompat(singleChooser, Intent.EXTRA_INTENT, Intent::class.java)
        assertNotNull(singleInner)
        assertEquals(Intent.ACTION_SEND, singleInner!!.action)

        // Multiple items (mixed single + split) use ACTION_SEND_MULTIPLE with all URIs & ClipData
        val multiChooser = ApkShareHelper.createMultipleShareChooserIntent(context, shareables)
        assertEquals(Intent.ACTION_CHOOSER, multiChooser.action)
        assertEquals("Share 2 apps via…", multiChooser.getCharSequenceExtra(Intent.EXTRA_TITLE)?.toString())
        assertTrue((multiChooser.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION) != 0)

        val multiInner = getParcelableExtraCompat(multiChooser, Intent.EXTRA_INTENT, Intent::class.java)
        assertNotNull(multiInner)
        assertEquals(Intent.ACTION_SEND_MULTIPLE, multiInner!!.action)
        assertEquals("*/*", multiInner.type)
        assertTrue((multiInner.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION) != 0)

        val streamUris = getParcelableArrayListExtraCompat(multiInner, Intent.EXTRA_STREAM, Uri::class.java)
        assertNotNull(streamUris)
        assertEquals(2, streamUris!!.size)
        streamUris.forEach { uri ->
            assertEquals("content", uri.scheme)
            assertEquals("${context.packageName}.fileprovider", uri.authority)
        }
        assertNotNull(multiInner.clipData)
        assertEquals(2, multiInner.clipData!!.itemCount)
    }

    @Test
    fun `resolveUniqueFileName appends numeric suffixes instead of overwriting`() {
        val existing = mutableSetOf<String>()

        val first = ApkShareHelper.resolveUniqueFileName("MyApp_v1.2", "apk") { it in existing }
        assertEquals("MyApp_v1.2.apk", first)
        existing.add(first)

        val second = ApkShareHelper.resolveUniqueFileName("MyApp_v1.2", "apk") { it in existing }
        assertEquals("MyApp_v1.2_1.apk", second)
        existing.add(second)

        val third = ApkShareHelper.resolveUniqueFileName("MyApp_v1.2", "apk") { it in existing }
        assertEquals("MyApp_v1.2_2.apk", third)

        val splitName = ApkShareHelper.resolveUniqueFileName("Game_v3.0", "apks") { false }
        assertEquals("Game_v3.0.apks", splitName)
    }

    @Test
    @Config(sdk = [28])
    fun `saveApkToPublicDownloads saves single apk and split apks into Downloads APK Share without overwriting`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val srcDir = File(context.cacheDir, "save_dl_src").apply {
            deleteRecursively()
            mkdirs()
        }
        val singleFile = File(srcDir, "sample.apk").apply { writeBytes("APK_CONTENT_V1".toByteArray()) }
        val splitArchive = File(srcDir, "split.apks").apply { writeBytes("PK\u0003\u0004SPLIT".toByteArray()) }

        @Suppress("DEPRECATION")
        val targetDir = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            "APK Share"
        ).apply { deleteRecursively() }

        val saved1 = runBlocking {
            ApkShareHelper.saveApkToPublicDownloads(context, singleFile, "Notes App", "1.2", isSplitBundle = false)
        }
        val saved2 = runBlocking {
            ApkShareHelper.saveApkToPublicDownloads(context, singleFile, "Notes App", "1.2", isSplitBundle = false)
        }
        val savedSplit = runBlocking {
            ApkShareHelper.saveApkToPublicDownloads(context, splitArchive, "Big Game", "4.0", isSplitBundle = true)
        }

        assertTrue("First save must succeed", saved1.isSuccess)
        assertEquals("Notes_App_v1.2.apk", saved1.getOrThrow())
        assertTrue("Second save must succeed with _1 suffix", saved2.isSuccess)
        assertEquals("Notes_App_v1.2_1.apk", saved2.getOrThrow())
        assertTrue("Split save must succeed with .apks extension", savedSplit.isSuccess)
        assertEquals("Big_Game_v4.0.apks", savedSplit.getOrThrow())

        assertTrue(File(targetDir, "Notes_App_v1.2.apk").exists())
        assertTrue(File(targetDir, "Notes_App_v1.2_1.apk").exists())
        assertTrue(File(targetDir, "Big_Game_v4.0.apks").exists())
    }

    @Test
    fun `batch queue continues past a failing app and reports accurate summary`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val vm = createSenderViewModel(context)
        val srcDir = File(context.cacheDir, "batch_queue_src").apply {
            deleteRecursively()
            mkdirs()
        }
        val apk1 = File(srcDir, "first.apk").apply { writeBytes("PK\u0003\u0004first".toByteArray()) }
        val missingApk = File(srcDir, "missing.apk") // intentionally does not exist
        val apk3 = File(srcDir, "third.apk").apply { writeBytes("PK\u0003\u0004third".toByteArray()) }

        val app1 = fakeApp("com.batch.one", "FirstApp", "1.0", apk1)
        val app2 = fakeApp("com.batch.two", "BrokenApp", "1.0", missingApk)
        val app3 = fakeApp("com.batch.three", "ThirdApp", "1.0", apk3)

        vm.toggleAppSelection(app1)
        vm.toggleAppSelection(app2)
        vm.toggleAppSelection(app3)

        var readyCalled = false
        vm.startHotspotModeDirect { readyCalled = true }

        // Wait for extraction of app1
        waitUntil { readyCalled }
        assertEquals(3, vm.batchQueue.value.totalCount)
        assertEquals(0, vm.batchQueue.value.currentIndex)
        assertFalse(vm.batchQueue.value.isFinished)

        // Advance from app1 -> app2 fails extraction automatically -> queue must jump to app3!
        vm.advanceHotspotBatchQueue(markCurrentSucceeded = true)
        waitUntil { vm.batchQueue.value.currentIndex == 2 && !vm.batchQueue.value.isPreparingNext }

        assertEquals("ThirdApp", vm.batchQueue.value.currentApp?.appName)
        assertEquals(1, vm.batchQueue.value.sentCount)
        assertEquals(1, vm.batchQueue.value.failedItems.size)
        assertEquals("BrokenApp", vm.batchQueue.value.failedItems.first().appName)

        // Finish app3 -> queue finishes with 2 sent and 1 failed
        vm.advanceHotspotBatchQueue(markCurrentSucceeded = true)
        waitUntil { vm.batchQueue.value.isFinished }

        assertEquals(2, vm.batchQueue.value.sentCount)
        assertEquals(1, vm.batchQueue.value.failedItems.size)
        assertEquals("BrokenApp", vm.batchQueue.value.failedItems.first().appName)
    }

    @Test
    fun `AppPickerScreen shows multi-select count, Clear, Share, and Save buttons in Bangla`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        LocaleHelper.applyAppLocale(context, LocaleHelper.LANGUAGE_BANGLA)
        val vm = createSenderViewModel(context)

        val srcDir = File(context.cacheDir, "ui_bn_src").apply { mkdirs() }
        val apk1 = File(srcDir, "u1.apk").apply { writeBytes("one".toByteArray()) }
        val apk2 = File(srcDir, "u2.apk").apply { writeBytes("two".toByteArray()) }
        vm.toggleAppSelection(fakeApp("com.bn.one", "AppOne", "1.0", apk1))
        vm.toggleAppSelection(fakeApp("com.bn.two", "AppTwo", "2.0", apk2))

        composeTestRule.setContent {
            val localizedContext = remember { LocaleHelper.localizedContext(context) }
            CompositionLocalProvider(
                LocalContext provides localizedContext,
                LocalConfiguration provides localizedContext.resources.configuration
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
        composeTestRule.onNodeWithTag("selected_apps_count_text").assertIsDisplayed()
        composeTestRule.onNodeWithText("২টি অ্যাপ নির্বাচিত").assertIsDisplayed()
        composeTestRule.onNodeWithTag("clear_selection_button").assertIsDisplayed()
        composeTestRule.onNodeWithTag("share_selected_app_button").assertIsDisplayed()
        composeTestRule.onNodeWithTag("save_selected_apps_button").assertIsDisplayed()

        // Tapping Clear empties selection
        composeTestRule.onNodeWithTag("clear_selection_button").performClick()
        composeTestRule.waitForIdle()
        assertTrue(vm.selectedPackageNames.value.isEmpty())
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
