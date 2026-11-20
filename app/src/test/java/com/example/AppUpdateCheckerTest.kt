package com.example

import android.content.Context
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import com.example.data.prefs.SettingsDataStore
import com.example.data.prefs.UserSettings
import com.example.ui.screens.HomeScreen
import com.example.ui.screens.SettingsScreen
import com.example.ui.theme.ApkShareTheme
import com.example.update.AppUpdateChecker
import com.example.update.GitHubReleaseInfo
import com.example.update.GitHubReleaseParser
import com.example.update.ManualUpdateCheckUiState
import com.example.update.UpdateCheckResult
import com.example.update.UpdateConfig
import com.example.update.VersionComparator
import com.example.util.LocaleHelper
import kotlinx.coroutines.flow.first
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
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class AppUpdateCheckerTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private lateinit var context: Context
    private lateinit var settingsDataStore: SettingsDataStore

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        settingsDataStore = SettingsDataStore(context)
        runBlocking {
            settingsDataStore.setNotifyAboutUpdates(true)
            settingsDataStore.recordLastUpdateCheckMillis(0L)
            settingsDataStore.setSkippedUpdateVersion("")
        }
    }

    @Test
    fun `version comparison compares semantic parts numerically and strips leading v`() {
        assertTrue(VersionComparator.isNewer("v9.9", "1.3"))
        assertTrue(VersionComparator.isNewer("v1.3", "1.2"))
        assertTrue(VersionComparator.isNewer("1.10", "1.2"))
        assertTrue(VersionComparator.isNewer("v1.2.1", "1.2"))
        assertTrue(VersionComparator.isNewer("V2.0", "1.9.9"))

        assertFalse(VersionComparator.isNewer("v1.3", "1.3"))
        assertFalse(VersionComparator.isNewer("1.3.0", "1.3"))
        assertFalse(VersionComparator.isNewer("v1.2", "1.3"))
        assertFalse(VersionComparator.isNewer("", "1.3"))
        assertFalse(VersionComparator.isNewer("invalid", "1.3"))
    }

    @Test
    fun `fake API response with v9_9 reports update available with APK asset URL`() = runBlocking {
        val fakeJson = buildFakeReleaseJson(
            tagName = "v9.9",
            apkName = "APK-Share-v9.9.apk",
            downloadUrl = "https://github.com/bulbulewu/apk-share-by-Munajat/releases/download/v9.9/APK-Share-v9.9.apk"
        )
        val checker = AppUpdateChecker(
            settingsDataStore = settingsDataStore,
            networkChecker = { true },
            releaseFetcher = { fakeJson },
            currentVersionProvider = { "1.3" },
            clock = { 1_000_000_000L }
        )

        val result = checker.checkAutomaticallyIfDue()
        assertTrue("Expected UpdateAvailable, got $result", result is UpdateCheckResult.UpdateAvailable)
        val release = (result as UpdateCheckResult.UpdateAvailable).release
        assertEquals("v9.9", release.tagName)
        assertEquals("9.9", release.normalizedVersion)
        assertEquals("APK-Share-v9.9.apk", release.apkFileName)
        assertEquals(
            "https://github.com/bulbulewu/apk-share-by-Munajat/releases/download/v9.9/APK-Share-v9.9.apk",
            release.apkDownloadUrl
        )
    }

    @Test
    fun `fake API response equal to current version reports up to date`() = runBlocking {
        val fakeJson = buildFakeReleaseJson(
            tagName = "v1.3",
            apkName = "APK-Share-v1.3.apk",
            downloadUrl = "https://github.com/bulbulewu/apk-share-by-Munajat/releases/download/v1.3/APK-Share-v1.3.apk"
        )
        val checker = AppUpdateChecker(
            settingsDataStore = settingsDataStore,
            networkChecker = { true },
            releaseFetcher = { fakeJson },
            currentVersionProvider = { "1.3" },
            clock = { 1_000_000_000L }
        )

        val result = checker.checkAutomaticallyIfDue()
        assertTrue("Expected UpToDate, got $result", result is UpdateCheckResult.UpToDate)
        assertEquals("1.3", (result as UpdateCheckResult.UpToDate).latestVersion)
    }

    @Test
    fun `no internet skips API call silently without crashing`() = runBlocking {
        val fetchCount = AtomicInteger(0)
        val checker = AppUpdateChecker(
            settingsDataStore = settingsDataStore,
            networkChecker = { false },
            releaseFetcher = {
                fetchCount.incrementAndGet()
                buildFakeReleaseJson("v9.9", "APK-Share-v9.9.apk", "https://example.com/app.apk")
            },
            currentVersionProvider = { "1.3" }
        )

        val result = checker.checkAutomaticallyIfDue()
        assertEquals(UpdateCheckResult.Offline, result)
        assertEquals("Fetcher must not be called when offline", 0, fetchCount.get())
    }

    @Test
    fun `failed HTTP request or malformed JSON returns error silently without crashing`() = runBlocking {
        val failedChecker = AppUpdateChecker(
            settingsDataStore = settingsDataStore,
            networkChecker = { true },
            releaseFetcher = { null },
            currentVersionProvider = { "1.3" }
        )
        assertTrue(failedChecker.checkAutomaticallyIfDue() is UpdateCheckResult.Error)

        val throwingChecker = AppUpdateChecker(
            settingsDataStore = settingsDataStore,
            networkChecker = { true },
            releaseFetcher = { throw RuntimeException("Simulated network timeout") },
            currentVersionProvider = { "1.3" }
        )
        assertTrue(throwingChecker.checkAutomaticallyIfDue() is UpdateCheckResult.Error)

        val malformedChecker = AppUpdateChecker(
            settingsDataStore = settingsDataStore,
            networkChecker = { true },
            releaseFetcher = { "{not-valid-json" },
            currentVersionProvider = { "1.3" }
        )
        assertTrue(malformedChecker.checkAutomaticallyIfDue() is UpdateCheckResult.Error)
        assertNull(GitHubReleaseParser.parse("{not-valid-json"))
    }

    @Test
    fun `daily throttle, skipped version, and notify toggle OFF are enforced via DataStore`() = runBlocking {
        val fetchCount = AtomicInteger(0)
        var now = 2_000_000_000L
        var latestTag = "v9.9"

        val checker = AppUpdateChecker(
            settingsDataStore = settingsDataStore,
            networkChecker = { true },
            releaseFetcher = {
                fetchCount.incrementAndGet()
                buildFakeReleaseJson(
                    tagName = latestTag,
                    apkName = "APK-Share-$latestTag.apk",
                    downloadUrl = "https://github.com/bulbulewu/apk-share-by-Munajat/releases/download/$latestTag/APK-Share-$latestTag.apk"
                )
            },
            currentVersionProvider = { "1.3" },
            clock = { now }
        )

        // 1) First auto-check finds v9.9
        assertTrue(checker.checkAutomaticallyIfDue() is UpdateCheckResult.UpdateAvailable)
        assertEquals(1, fetchCount.get())

        // 2) Second auto-check 1 hour later is throttled (at most once per day)
        now += 60L * 60L * 1000L
        assertEquals(UpdateCheckResult.Skipped, checker.checkAutomaticallyIfDue())
        assertEquals(1, fetchCount.get())

        // 3) User taps "Later" for v9.9 -> persisted; even after 25 hours, v9.9 stays suppressed
        checker.skipVersion("v9.9")
        assertEquals("9.9", settingsDataStore.settings.first().skippedUpdateVersion)
        now += UpdateConfig.ONE_DAY_MILLIS + 1000L
        assertEquals(UpdateCheckResult.Skipped, checker.checkAutomaticallyIfDue())
        assertEquals(2, fetchCount.get())

        // 4) Next day when v10.0 is released, it notifies again
        latestTag = "v10.0"
        now += UpdateConfig.ONE_DAY_MILLIS + 1000L
        val nextResult = checker.checkAutomaticallyIfDue()
        assertTrue(nextResult is UpdateCheckResult.UpdateAvailable)
        assertEquals("v10.0", (nextResult as UpdateCheckResult.UpdateAvailable).release.tagName)

        // 5) When "Notify about new updates" is turned OFF, never checks network
        settingsDataStore.setNotifyAboutUpdates(false)
        now += UpdateConfig.ONE_DAY_MILLIS + 1000L
        val beforeCalls = fetchCount.get()
        assertEquals(UpdateCheckResult.Skipped, checker.checkAutomaticallyIfDue())
        assertEquals("Network must not be called when notify toggle is OFF", beforeCalls, fetchCount.get())
    }

    @Test
    fun `home screen update dialog displays version and triggers Update or Later in English and Bangla`() {
        val fakeRelease = GitHubReleaseInfo(
            tagName = "v9.9",
            normalizedVersion = "9.9",
            apkDownloadUrl = "https://github.com/bulbulewu/apk-share-by-Munajat/releases/download/v9.9/APK-Share-v9.9.apk",
            apkFileName = "APK-Share-v9.9.apk",
            releasePageUrl = UpdateConfig.RELEASES_PAGE_URL
        )

        var updatedRelease: GitHubReleaseInfo? = null
        var deferredRelease: GitHubReleaseInfo? = null

        composeTestRule.setContent {
            val baseConfig = LocalConfiguration.current
            val (bnCtx, bnCfg) = remember(baseConfig) {
                LocaleHelper.createLocalizedComposeContext(
                    baseContext = context,
                    baseConfig = baseConfig,
                    languageCode = LocaleHelper.LANGUAGE_BANGLA
                )
            }
            CompositionLocalProvider(
                LocalContext provides bnCtx,
                LocalConfiguration provides bnCfg
            ) {
                ApkShareTheme {
                    HomeScreen(
                        deviceName = "Pixel 9",
                        recentTransfers = emptyList(),
                        onSendClick = {},
                        onReceiveClick = {},
                        onOpenHistory = {},
                        onOpenSettings = {},
                        availableUpdate = fakeRelease,
                        onUpdateNow = { updatedRelease = it },
                        onUpdateLater = { deferredRelease = it }
                    )
                }
            }
        }

        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("update_dialog_title")
            .assertIsDisplayed()
            .assertTextContains("নতুন সংস্করণ উপলব্ধ")
        composeTestRule.onNodeWithTag("update_dialog_message")
            .assertIsDisplayed()
            .assertTextContains("v9.9", substring = true)

        composeTestRule.onNodeWithTag("update_dialog_confirm_button")
            .assertIsDisplayed()
            .performClick()
        assertNotNull(updatedRelease)
        assertEquals("v9.9", updatedRelease?.tagName)

        composeTestRule.onNodeWithTag("update_dialog_later_button")
            .assertIsDisplayed()
            .performClick()
        assertNotNull(deferredRelease)
        assertEquals("v9.9", deferredRelease?.tagName)
    }

    @Test
    fun `settings screen shows Notify switch and Check for updates states`() {
        val fakeRelease = GitHubReleaseInfo(
            tagName = "v9.9",
            normalizedVersion = "9.9",
            apkDownloadUrl = "https://github.com/bulbulewu/apk-share-by-Munajat/releases/download/v9.9/APK-Share-v9.9.apk",
            apkFileName = "APK-Share-v9.9.apk",
            releasePageUrl = UpdateConfig.RELEASES_PAGE_URL
        )

        var checkTriggered = false
        var notifyToggledTo: Boolean? = null
        var downloadTriggered: GitHubReleaseInfo? = null

        composeTestRule.setContent {
            val baseConfig = LocalConfiguration.current
            val (enCtx, enCfg) = remember(baseConfig) {
                LocaleHelper.createLocalizedComposeContext(
                    baseContext = context,
                    baseConfig = baseConfig,
                    languageCode = LocaleHelper.LANGUAGE_ENGLISH
                )
            }
            CompositionLocalProvider(
                LocalContext provides enCtx,
                LocalConfiguration provides enCfg
            ) {
                ApkShareTheme {
                    SettingsScreen(
                        settings = UserSettings(notifyAboutUpdates = true),
                        onUpdateDeviceName = {},
                        onUpdateTheme = {},
                        onToggleBiometric = {},
                        onTogglePublicDownloads = {},
                        onTogglePowerSaver = {},
                        manualUpdateState = ManualUpdateCheckUiState.UpdateAvailable(fakeRelease),
                        onToggleNotifyUpdates = { notifyToggledTo = it },
                        onCheckForUpdates = { checkTriggered = true },
                        onStartUpdateDownload = { downloadTriggered = it }
                    )
                }
            }
        }

        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("notify_updates_switch")
            .performScrollTo()
            .assertIsDisplayed()
            .assertIsOn()
            .performClick()
        assertEquals(false, notifyToggledTo)

        composeTestRule.onNodeWithTag("check_for_updates_row")
            .performScrollTo()
            .assertTextContains("New version v9.9 available", substring = true)

        composeTestRule.onNodeWithTag("check_for_updates_status_text", useUnmergedTree = true)
            .assertTextContains("New version v9.9 available")

        composeTestRule.onNodeWithTag("check_for_updates_button")
            .performScrollTo()
            .performClick()
        assertTrue(checkTriggered)

        composeTestRule.onNodeWithTag("settings_update_now_button")
            .performScrollTo()
            .assertIsDisplayed()
            .performClick()
        assertEquals("v9.9", downloadTriggered?.tagName)
    }

    private fun buildFakeReleaseJson(
        tagName: String,
        apkName: String,
        downloadUrl: String
    ): String {
        return """
            {
              "tag_name": "$tagName",
              "html_url": "https://github.com/bulbulewu/apk-share-by-Munajat/releases/tag/$tagName",
              "assets": [
                {
                  "name": "checksums.txt",
                  "browser_download_url": "https://example.com/checksums.txt"
                },
                {
                  "name": "$apkName",
                  "browser_download_url": "$downloadUrl"
                }
              ]
            }
        """.trimIndent()
    }
}
