package com.example

import android.content.Context
import android.os.Looper
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import com.example.data.db.AppDatabase
import com.example.data.db.TransferHistoryRepository
import com.example.data.prefs.SettingsDataStore
import com.example.ui.screens.SettingsScreen
import com.example.ui.theme.ApkShareTheme
import com.example.ui.theme.DarkBackground
import com.example.ui.theme.LightBackground
import com.example.ui.theme.ThemePreference
import com.example.ui.viewmodel.MainViewModel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class DarkModeSettingsTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun `dark mode toggle persists in DataStore and updates Material 3 color scheme`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val settingsStore = SettingsDataStore(context)
        val db = AppDatabase.getInstance(context)
        val historyRepo = TransferHistoryRepository(db.transferHistoryDao())

        runBlocking {
            settingsStore.updateThemePreference(ThemePreference.LIGHT)
        }

        val viewModel = MainViewModel(settingsStore, historyRepo)
        var observedBackground = Color.Unspecified

        composeTestRule.setContent {
            val settings by viewModel.settings.collectAsState()
            ApkShareTheme(themePreference = settings.themePreference) {
                observedBackground = MaterialTheme.colorScheme.background
                SettingsScreen(
                    settings = settings,
                    onUpdateDeviceName = { viewModel.updateDeviceName(it) },
                    onUpdateTheme = { viewModel.updateThemePreference(it) },
                    onToggleBiometric = { viewModel.setBiometricLockEnabled(it) },
                    onTogglePublicDownloads = { viewModel.setExportToPublicDownloads(it) },
                    onTogglePowerSaver = { viewModel.setPowerSaverEnabled(it) }
                )
            }
        }

        composeTestRule.waitUntil(timeoutMillis = 3000) {
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            observedBackground == LightBackground
        }
        composeTestRule.onNodeWithTag("dark_mode_switch").performScrollTo().assertIsOff()

        // Toggle Dark Mode ON
        composeTestRule.onNodeWithTag("dark_mode_switch").performClick()
        composeTestRule.waitUntil(timeoutMillis = 5000) {
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            observedBackground == DarkBackground
        }
        composeTestRule.onNodeWithTag("dark_mode_switch").assertIsOn()
        assertEquals(DarkBackground, observedBackground)

        runBlocking {
            assertEquals(ThemePreference.DARK, settingsStore.settings.first().themePreference)
        }

        // Toggle Dark Mode OFF -> back to Light
        composeTestRule.onNodeWithTag("dark_mode_switch").performClick()
        composeTestRule.waitUntil(timeoutMillis = 5000) {
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            observedBackground == LightBackground
        }
        composeTestRule.onNodeWithTag("dark_mode_switch").assertIsOff()
        assertEquals(LightBackground, observedBackground)

        runBlocking {
            assertEquals(ThemePreference.LIGHT, settingsStore.settings.first().themePreference)
        }
    }
}
