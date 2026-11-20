package com.example

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.example.apk.ApkExtractor
import com.example.apk.ApkStorageManager
import com.example.data.db.TransferHistoryEntity
import com.example.model.InstalledAppInfo
import com.example.model.TransferDirection
import com.example.model.TransferMethod
import com.example.model.TransferStatus
import com.example.ui.screens.HistoryScreen
import com.example.ui.theme.ApkShareTheme
import com.example.util.ApkShareHelper
import com.example.util.LocaleHelper
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowStatFs
import java.io.File
import java.util.zip.ZipFile

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class SystemShareApkTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Before
    fun setUp() {
        // FileProvider caches PathStrategy instances in a static map keyed by authority.
        // In Robolectric each test gets a new temp cacheDir, so clear the static cache per test.
        runCatching {
            val cacheField = androidx.core.content.FileProvider::class.java.getDeclaredField("sCache")
            cacheField.isAccessible = true
            (cacheField.get(null) as? MutableMap<*, *>)?.clear()
        }

        val context = ApplicationProvider.getApplicationContext<Context>()
        val outgoingRoot = File(context.cacheDir, "outgoing_apks").apply { mkdirs() }
        // Provide simulated free disk space for Robolectric's ShadowStatFs (256 MB)
        ShadowStatFs.registerStats(outgoingRoot.absolutePath, 65536, 65536, 65536)
    }

    @Test
    fun `single APK extraction produces FileProvider ACTION_SEND chooser with package-archive MIME and read grant`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        LocaleHelper.applyAppLocale(context, LocaleHelper.LANGUAGE_ENGLISH)

        val srcDir = File(context.cacheDir, "test_single_src").apply {
            deleteRecursively()
            mkdirs()
        }
        val baseApk = File(srcDir, "base.apk").apply { writeBytes("PK\u0003\u0004single-apk-bytes".toByteArray()) }

        val appInfo = InstalledAppInfo(
            packageName = "com.demo.coolapp",
            appName = "CoolApp",
            versionName = "2.1.0",
            versionCode = 21L,
            totalSizeBytes = baseApk.length(),
            installDateMillis = System.currentTimeMillis(),
            baseApkPath = baseApk.absolutePath,
            splitApkPaths = emptyList()
        )

        val extractor = ApkExtractor(context)
        val shareable = runBlocking {
            val bundle = extractor.extractBundle(appInfo).getOrThrow()
            ApkShareHelper.prepareShareableFile(bundle)
        }

        assertFalse("Single APK should not be marked split", shareable.isSplitBundle)
        assertTrue("Single APK file should end with .apk", shareable.file.name.endsWith(".apk"))
        assertTrue("Single APK file must exist", shareable.file.exists())

        val chooser = ApkShareHelper.createShareChooserIntent(
            context = context,
            file = shareable.file,
            appName = shareable.appName,
            isSplitBundle = shareable.isSplitBundle
        )

        assertEquals(Intent.ACTION_CHOOSER, chooser.action)
        assertEquals("Share CoolApp via…", chooser.getCharSequenceExtra(Intent.EXTRA_TITLE)?.toString())
        assertTrue(
            "Chooser must include FLAG_GRANT_READ_URI_PERMISSION",
            (chooser.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION) != 0
        )

        val inner = getParcelableExtraCompat(chooser, Intent.EXTRA_INTENT, Intent::class.java)
        assertNotNull("Chooser must wrap ACTION_SEND intent", inner)
        assertEquals(Intent.ACTION_SEND, inner!!.action)
        assertEquals(ApkShareHelper.MIME_TYPE_APK, inner.type)
        assertTrue(
            "Inner ACTION_SEND must include FLAG_GRANT_READ_URI_PERMISSION",
            (inner.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION) != 0
        )

        val streamUri = getParcelableExtraCompat(inner, Intent.EXTRA_STREAM, Uri::class.java)
        assertNotNull("EXTRA_STREAM URI must be present", streamUri)
        assertEquals("content", streamUri!!.scheme)
        assertEquals("${context.packageName}.fileprovider", streamUri.authority)
        assertNotNull("ClipData must be attached for Android 10+ URI grant propagation", inner.clipData)
        assertEquals(streamUri, inner.clipData!!.getItemAt(0).uri)
    }

    @Test
    fun `split APK extraction bundles all splits into valid root-level apks archive with zip MIME`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        LocaleHelper.applyAppLocale(context, LocaleHelper.LANGUAGE_ENGLISH)

        val srcDir = File(context.cacheDir, "test_split_src").apply {
            deleteRecursively()
            mkdirs()
        }
        val baseApk = File(srcDir, "base.apk").apply { writeText("BASE_APK_CONTENT") }
        val armSplit = File(srcDir, "split_config.arm64_v8a.apk").apply { writeText("ARM64_SPLIT_CONTENT") }
        val enSplit = File(srcDir, "split_config.en.apk").apply { writeText("EN_SPLIT_CONTENT") }

        val appInfo = InstalledAppInfo(
            packageName = "com.demo.splitapp",
            appName = "SplitApp",
            versionName = "4.0",
            versionCode = 40L,
            totalSizeBytes = baseApk.length() + armSplit.length() + enSplit.length(),
            installDateMillis = System.currentTimeMillis(),
            baseApkPath = baseApk.absolutePath,
            splitApkPaths = listOf(armSplit.absolutePath, enSplit.absolutePath)
        )

        val extractor = ApkExtractor(context)
        val shareable = runBlocking {
            val bundle = extractor.extractBundle(appInfo).getOrThrow()
            ApkShareHelper.prepareShareableFile(bundle)
        }

        assertTrue("Split bundle should be flagged as split", shareable.isSplitBundle)
        assertTrue("Split bundle must have .apks extension", shareable.file.name.endsWith(".apks"))
        assertTrue("Split .apks file must exist", shareable.file.exists())

        // Verify ZIP structure has base.apk and split APKs at root with intact contents
        ZipFile(shareable.file).use { zip ->
            val entries = zip.entries().toList().associate { it.name to zip.getInputStream(it).bufferedReader().readText() }
            assertEquals(setOf("base.apk", "split_config.arm64_v8a.apk", "split_config.en.apk"), entries.keys)
            assertEquals("BASE_APK_CONTENT", entries["base.apk"])
            assertEquals("ARM64_SPLIT_CONTENT", entries["split_config.arm64_v8a.apk"])
            assertEquals("EN_SPLIT_CONTENT", entries["split_config.en.apk"])
        }

        val chooser = ApkShareHelper.createShareChooserIntent(
            context = context,
            file = shareable.file,
            appName = shareable.appName,
            isSplitBundle = shareable.isSplitBundle
        )
        val inner = getParcelableExtraCompat(chooser, Intent.EXTRA_INTENT, Intent::class.java)!!
        assertEquals(ApkShareHelper.MIME_TYPE_APKS, inner.type)
        val streamUri = getParcelableExtraCompat(inner, Intent.EXTRA_STREAM, Uri::class.java)!!
        assertEquals("${context.packageName}.fileprovider", streamUri.authority)
    }

    @Test
    fun `history share resolves existing received single and split APKs and returns null cleanly when deleted`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val storageManager = ApkStorageManager(context)
        val extractor = ApkExtractor(context)
        val root = storageManager.getAppDownloadsRoot().apply {
            deleteRecursively()
            mkdirs()
        }

        // 1) Existing received single APK
        val singleFile = File(root, "NotePad_v1.5.apk").apply { writeText("NOTEPAD_APK") }
        val receivedSingleItem = TransferHistoryEntity(
            id = 1L,
            appName = "NotePad",
            packageName = "com.demo.notepad",
            versionName = "1.5",
            sizeBytes = singleFile.length(),
            timestampMillis = System.currentTimeMillis(),
            direction = TransferDirection.RECEIVED,
            method = TransferMethod.NEARBY,
            status = TransferStatus.SUCCESS,
            peerDeviceName = "Phone A",
            isSplitApk = false
        )

        val resolvedSingle = runBlocking {
            ApkShareHelper.resolveHistoryShareableFile(context, receivedSingleItem, storageManager, extractor)
        }
        assertNotNull("Existing received single APK should resolve", resolvedSingle)
        assertEquals(singleFile.absolutePath, resolvedSingle!!.file.absolutePath)
        assertFalse(resolvedSingle.isSplitBundle)

        // 2) Delete file -> should return null safely instead of crashing
        assertTrue(singleFile.delete())
        val afterDelete = runBlocking {
            ApkShareHelper.resolveHistoryShareableFile(context, receivedSingleItem, storageManager, extractor)
        }
        assertNull("Deleted received APK must return null cleanly", afterDelete)

        // 3) Existing received split APK folder -> bundles into .apks archive
        val splitDir = File(root, "MapsLite_v3.2").apply { mkdirs() }
        File(splitDir, "base.apk").writeText("MAPS_BASE")
        File(splitDir, "split_xxhdpi.apk").writeText("MAPS_XXHDPI")
        val receivedSplitItem = TransferHistoryEntity(
            id = 2L,
            appName = "MapsLite",
            packageName = "com.demo.mapslite",
            versionName = "3.2",
            sizeBytes = 1024L,
            timestampMillis = System.currentTimeMillis(),
            direction = TransferDirection.RECEIVED,
            method = TransferMethod.HOTSPOT_HTTP,
            status = TransferStatus.SUCCESS,
            peerDeviceName = "Phone A",
            isSplitApk = true
        )

        val resolvedSplit = runBlocking {
            ApkShareHelper.resolveHistoryShareableFile(context, receivedSplitItem, storageManager, extractor)
        }
        assertNotNull("Existing received split APK folder should bundle and resolve", resolvedSplit)
        assertTrue(resolvedSplit!!.isSplitBundle)
        assertTrue(resolvedSplit.file.name.endsWith(".apks"))
        ZipFile(resolvedSplit.file).use { zip ->
            val names = zip.entries().toList().map { it.name }.toSet()
            assertEquals(setOf("base.apk", "split_xxhdpi.apk"), names)
        }
    }

    @Test
    fun `history screen displays Share button in Bangla and invokes share callback`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        LocaleHelper.applyAppLocale(context, LocaleHelper.LANGUAGE_BANGLA)

        val successItem = TransferHistoryEntity(
            id = 10L,
            appName = "CoolApp",
            packageName = "com.demo.coolapp",
            versionName = "1.0",
            sizeBytes = 2048L,
            timestampMillis = System.currentTimeMillis(),
            direction = TransferDirection.RECEIVED,
            method = TransferMethod.NEARBY,
            status = TransferStatus.SUCCESS,
            peerDeviceName = "Pixel 9",
            isSplitApk = false
        )
        val failedItem = successItem.copy(
            id = 11L,
            appName = "FailedApp",
            status = TransferStatus.FAILED
        )

        var sharedItem: TransferHistoryEntity? = null

        composeTestRule.setContent {
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
                    HistoryScreen(
                        history = listOf(successItem, failedItem),
                        onDeleteItem = {},
                        onClearAll = {},
                        onShareItem = { sharedItem = it }
                    )
                }
            }
        }

        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("history_share_button_10")
            .assertIsDisplayed()
            .assertContentDescriptionEquals("CoolApp শেয়ার করুন")
            .performClick()

        assertEquals(10L, sharedItem?.id)
        composeTestRule.onNodeWithTag("history_share_button_11").assertDoesNotExist()

        // Verify Bangla chooser title & missing-file message
        val dummyFile = File(context.cacheDir, "outgoing_apks/bn_test.apk").apply {
            parentFile?.mkdirs()
            writeText("apk")
        }
        val bnChooser = ApkShareHelper.createShareChooserIntent(
            context = context,
            file = dummyFile,
            appName = "CoolApp",
            isSplitBundle = false
        )
        assertEquals("CoolApp শেয়ার করুন…", bnChooser.getCharSequenceExtra(Intent.EXTRA_TITLE)?.toString())

        val bnCtx = LocaleHelper.localizedContext(context, LocaleHelper.LANGUAGE_BANGLA)
        assertEquals(
            "এই ডিভাইসে APK ফাইলটি আর পাওয়া যাচ্ছে না।",
            bnCtx.getString(R.string.share_history_file_missing)
        )
        assertEquals("APK শেয়ার করুন", bnCtx.getString(R.string.share_apk_cd))
    }

    @Suppress("DEPRECATION")
    private fun <T> getParcelableExtraCompat(intent: Intent, name: String, clazz: Class<T>): T? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(name, clazz)
        } else {
            intent.getParcelableExtra(name)
        }
    }
}
