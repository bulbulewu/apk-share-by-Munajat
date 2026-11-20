package com.example

import android.app.LocaleManager
import android.content.Context
import android.os.LocaleList
import android.os.Looper
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import com.example.apk.ApkStorageManager
import com.example.data.db.AppDatabase
import com.example.data.db.TransferHistoryRepository
import com.example.data.prefs.SettingsDataStore
import com.example.transfer.HttpApkDownloader
import com.example.transfer.HttpDownloadState
import com.example.ui.screens.HistoryScreen
import com.example.ui.screens.HomeScreen
import com.example.ui.screens.SettingsScreen
import com.example.ui.theme.ApkShareTheme
import com.example.ui.viewmodel.MainViewModel
import com.example.util.LocaleHelper
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class MultiLanguageSupportTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun `english and bangla string resources have identical keys and matching format specifiers`() {
        val enFile = resolveResourceFile("src/main/res/values/strings.xml")
        val bnFile = resolveResourceFile("src/main/res/values-bn/strings.xml")

        assertTrue("English strings.xml should exist at ${enFile.absolutePath}", enFile.exists())
        assertTrue("Bangla strings.xml should exist at ${bnFile.absolutePath}", bnFile.exists())

        val enStrings = parseStringsXml(enFile)
        val bnStrings = parseStringsXml(bnFile)

        assertTrue("Expected comprehensive English string resources", enStrings.size >= 250)
        assertEquals(
            "values/strings.xml and values-bn/strings.xml must contain the exact same string keys",
            enStrings.keys,
            bnStrings.keys
        )

        val formatRegex = Regex("%(\\d+\\$)?[sd]")
        val banglaCharRegex = Regex("[\\u0980-\\u09FF]")
        var banglaCount = 0

        for ((key, enValue) in enStrings) {
            val bnValue = bnStrings[key].orEmpty()
            assertTrue("Bangla string '$key' must not be blank", bnValue.isNotBlank())

            val enPlaceholders = formatRegex.findAll(enValue).map { it.value }.sorted().toList()
            val bnPlaceholders = formatRegex.findAll(bnValue).map { it.value }.sorted().toList()
            assertEquals(
                "Format placeholders mismatch for key '$key': en='$enValue' bn='$bnValue'",
                enPlaceholders,
                bnPlaceholders
            )

            if (enPlaceholders.isNotEmpty()) {
                val sampleArgs: Array<Any> = enPlaceholders.indices.map<Int, Any> { idx ->
                    val token = formatRegex.findAll(enValue)
                        .firstOrNull { it.value.startsWith("%${idx + 1}$") }?.value ?: "%s"
                    if (token.endsWith("d")) 42 else "sample"
                }.toTypedArray()
                // Ensure neither English nor Bangla throws UnknownFormatConversionException at runtime
                String.format(enValue, *sampleArgs)
                String.format(bnValue, *sampleArgs)
            }

            if (banglaCharRegex.containsMatchIn(bnValue)) {
                banglaCount++
            }
        }

        // Almost every string (aside from brand/language name 'English' or 'v%1$s') should contain Bangla script
        assertTrue(
            "Expected vast majority of values-bn strings in Bangla script (found $banglaCount / ${bnStrings.size})",
            banglaCount >= bnStrings.size - 10
        )
    }

    @Test
    fun `switching between Bangla, English, and System Default updates UI immediately, sets per-app locale, and persists`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val settingsStore = SettingsDataStore(context)
        val db = AppDatabase.getInstance(context)
        val historyRepo = TransferHistoryRepository(db.transferHistoryDao())

        runBlocking {
            settingsStore.updateLanguage(LocaleHelper.LANGUAGE_SYSTEM)
        }
        LocaleHelper.applyAppLocale(context, LocaleHelper.LANGUAGE_SYSTEM)

        val viewModel = MainViewModel(settingsStore, historyRepo)

        composeTestRule.setContent {
            val settings by viewModel.settings.collectAsState()
            val baseConfig = LocalConfiguration.current
            val (localizedCtx, localizedCfg) = remember(settings.language, baseConfig) {
                LocaleHelper.createLocalizedComposeContext(
                    baseContext = context,
                    baseConfig = baseConfig,
                    languageCode = settings.language
                )
            }
            CompositionLocalProvider(
                LocalContext provides localizedCtx,
                LocalConfiguration provides localizedCfg
            ) {
                ApkShareTheme(themePreference = settings.themePreference) {
                    SettingsScreen(
                        settings = settings,
                        onUpdateDeviceName = { viewModel.updateDeviceName(it) },
                        onUpdateTheme = { viewModel.updateThemePreference(it) },
                        onToggleBiometric = { viewModel.setBiometricLockEnabled(it) },
                        onTogglePublicDownloads = { viewModel.setExportToPublicDownloads(it) },
                        onTogglePowerSaver = { viewModel.setPowerSaverEnabled(it) },
                        onUpdateLanguage = { lang ->
                            viewModel.updateLanguage(lang)
                            LocaleHelper.applyAppLocale(context, lang)
                        }
                    )
                }
            }
        }

        composeTestRule.waitForIdle()
        // Default English system starts in English
        composeTestRule.onNodeWithText("Settings & About").assertIsDisplayed()
        composeTestRule.onNodeWithTag("language_option_system").performScrollTo().assertIsSelected()

        // 1) Switch to Bangla (বাংলা)
        composeTestRule.onNodeWithTag("language_option_bn").performScrollTo().performClick()
        composeTestRule.waitUntil(timeoutMillis = 5000) {
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            runCatching {
                composeTestRule.onNodeWithText("ভাষা").assertExists()
                true
            }.getOrDefault(false)
        }
        composeTestRule.onNodeWithTag("language_option_bn").performScrollTo().assertIsSelected()
        composeTestRule.onNodeWithText("ভাষা").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithText("সেটিংস ও পরিচিতি").performScrollTo().assertIsDisplayed()

        // Verify DataStore + per-app LocaleManager persisted "bn"
        runBlocking {
            assertEquals(LocaleHelper.LANGUAGE_BANGLA, settingsStore.settings.first().language)
        }
        val localeManager = context.getSystemService(LocaleManager::class.java)
        assertEquals("bn", localeManager.applicationLocales.toLanguageTags())

        // Verify close & reopen persistence: fresh ViewModel immediately starts in Bangla
        val reopenedViewModel = MainViewModel(settingsStore, historyRepo)
        assertEquals(LocaleHelper.LANGUAGE_BANGLA, reopenedViewModel.settings.value.language)
        val reopenedLocalized = LocaleHelper.localizedContext(context)
        assertEquals("পাঠান", reopenedLocalized.getString(R.string.send_title))
        assertEquals("গ্রহণ করুন", reopenedLocalized.getString(R.string.receive_title))

        // Verify non-UI error messages also localize to Bangla
        val downloader = HttpApkDownloader(context, ApkStorageManager(context))
        downloader.startDownload("not-a-valid-url", exportToPublicDownloads = false)
        val bnErr = downloader.state.value as HttpDownloadState.Error
        assertTrue(
            "Expected Bangla invalid URL error, got: ${bnErr.reason}",
            bnErr.reason.contains("সঠিক URL নয়")
        )

        // 2) Switch to English
        composeTestRule.onNodeWithTag("language_option_en").performScrollTo().performClick()
        composeTestRule.waitUntil(timeoutMillis = 5000) {
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            runCatching {
                composeTestRule.onNodeWithText("Language").assertExists()
                true
            }.getOrDefault(false)
        }
        composeTestRule.onNodeWithTag("language_option_en").performScrollTo().assertIsSelected()
        composeTestRule.onNodeWithText("Settings & About").performScrollTo().assertIsDisplayed()
        runBlocking {
            assertEquals(LocaleHelper.LANGUAGE_ENGLISH, settingsStore.settings.first().language)
        }
        assertEquals("en", localeManager.applicationLocales.toLanguageTags())

        // 3) Switch back to System Default
        composeTestRule.onNodeWithTag("language_option_system").performScrollTo().performClick()
        composeTestRule.waitUntil(timeoutMillis = 5000) {
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            runBlocking { settingsStore.settings.first().language == LocaleHelper.LANGUAGE_SYSTEM }
        }
        composeTestRule.onNodeWithTag("language_option_system").performScrollTo().assertIsSelected()
        composeTestRule.onNodeWithText("Settings & About").performScrollTo().assertIsDisplayed()
        assertTrue(
            "System Default should clear per-app applicationLocales",
            localeManager.applicationLocales.isEmpty
        )
    }

    @Test
    fun `home and history screens render in Bangla when language is bn`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        LocaleHelper.applyAppLocale(context, LocaleHelper.LANGUAGE_BANGLA)

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
                    HomeScreen(
                        deviceName = "Pixel 9",
                        recentTransfers = emptyList(),
                        onSendClick = {},
                        onReceiveClick = {},
                        onOpenHistory = {},
                        onOpenSettings = {}
                    )
                }
            }
        }

        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("পাঠান").assertIsDisplayed()
        composeTestRule.onNodeWithText("গ্রহণ করুন").assertIsDisplayed()
        composeTestRule.onNodeWithText("ব্যবহারের নিয়ম (৩টি সহজ ধাপ)").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `history screen empty state renders in Bangla when language is bn`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        LocaleHelper.applyAppLocale(context, LocaleHelper.LANGUAGE_BANGLA)

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
                        history = emptyList(),
                        onDeleteItem = {},
                        onClearAll = {}
                    )
                }
            }
        }

        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("ট্রান্সফার ইতিহাস").assertIsDisplayed()
        composeTestRule.onNodeWithText("এখনো কোনো ট্রান্সফার হয়নি").assertIsDisplayed()
    }

    @Test
    @Config(sdk = [36], qualifiers = "bn")
    fun `system default follows phone Bangla language when system locale is Bangla`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val localeManager = context.getSystemService(LocaleManager::class.java)
        LocaleHelper.applyAppLocale(context, LocaleHelper.LANGUAGE_SYSTEM)

        val sysCtx = LocaleHelper.localizedContext(context, LocaleHelper.LANGUAGE_SYSTEM)
        assertEquals("পাঠান", sysCtx.getString(R.string.send_title))
        assertEquals("গ্রহণ করুন", sysCtx.getString(R.string.receive_title))
        assertEquals("সেটিংস ও পরিচিতি", sysCtx.getString(R.string.settings_title))

        // Overriding to English still switches ONLY the app to English
        val enCtx = LocaleHelper.localizedContext(context, LocaleHelper.LANGUAGE_ENGLISH)
        assertEquals("SEND", enCtx.getString(R.string.send_title))
        assertEquals("Settings & About", enCtx.getString(R.string.settings_title))
        assertTrue(
            "System Default should leave applicationLocales empty",
            localeManager.applicationLocales.isEmpty
        )
    }

    private fun resolveResourceFile(relativeFromApp: String): File {
        val direct = File(relativeFromApp)
        if (direct.exists()) return direct
        val fromRoot = File("app/$relativeFromApp")
        if (fromRoot.exists()) return fromRoot
        return direct
    }

    private fun parseStringsXml(file: File): Map<String, String> {
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
        val nodes = doc.getElementsByTagName("string")
        val map = LinkedHashMap<String, String>()
        for (i in 0 until nodes.length) {
            val element = nodes.item(i)
            val nameAttr = element.attributes?.getNamedItem("name")?.nodeValue ?: continue
            map[nameAttr] = element.textContent ?: ""
        }
        return map
    }
}
