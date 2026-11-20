package com.example.data.prefs

import android.content.Context
import android.os.Build
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.example.ui.theme.ThemePreference
import com.example.util.LocaleHelper
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "apk_share_settings"
)

data class UserSettings(
    val deviceName: String = defaultDeviceLabel(),
    val themePreference: ThemePreference = ThemePreference.SYSTEM,
    val biometricLockEnabled: Boolean = false,
    val exportToPublicDownloads: Boolean = true,
    val powerSaverEnabled: Boolean = false,
    val language: String = LocaleHelper.LANGUAGE_SYSTEM,
    val notifyAboutUpdates: Boolean = true,
    val lastUpdateCheckMillis: Long = 0L,
    val skippedUpdateVersion: String = ""
)

fun defaultDeviceLabel(): String {
    val model = Build.MODEL?.trim().orEmpty()
    val manufacturer = Build.MANUFACTURER?.trim().orEmpty()
    return when {
        model.isBlank() -> "Android Phone"
        model.startsWith(manufacturer, ignoreCase = true) -> model
        manufacturer.isNotBlank() -> "$manufacturer $model"
        else -> model
    }
}

class SettingsDataStore(private val context: Context) {

    private object Keys {
        val DEVICE_NAME = stringPreferencesKey("device_name")
        val THEME_MODE = stringPreferencesKey("theme_mode")
        val BIOMETRIC_LOCK = booleanPreferencesKey("biometric_lock")
        val PUBLIC_DOWNLOADS = booleanPreferencesKey("public_downloads")
        val POWER_SAVER = booleanPreferencesKey("power_saver")
        val LANGUAGE = stringPreferencesKey("language")
        val NOTIFY_ABOUT_UPDATES = booleanPreferencesKey("notify_about_updates")
        val LAST_UPDATE_CHECK_MILLIS = longPreferencesKey("last_update_check_millis")
        val SKIPPED_UPDATE_VERSION = stringPreferencesKey("skipped_update_version")
    }

    fun getCachedLanguage(): String = LocaleHelper.getSavedLanguageSync(context)

    val settings: Flow<UserSettings> = context.settingsDataStore.data.map { prefs ->
        val rawTheme = prefs[Keys.THEME_MODE] ?: ThemePreference.SYSTEM.name
        val theme = runCatching { ThemePreference.valueOf(rawTheme) }
            .getOrDefault(ThemePreference.SYSTEM)
        val language = LocaleHelper.normalizeLanguageCode(prefs[Keys.LANGUAGE])
        UserSettings(
            deviceName = prefs[Keys.DEVICE_NAME]?.takeIf { it.isNotBlank() } ?: defaultDeviceLabel(),
            themePreference = theme,
            biometricLockEnabled = prefs[Keys.BIOMETRIC_LOCK] ?: false,
            exportToPublicDownloads = prefs[Keys.PUBLIC_DOWNLOADS] ?: true,
            powerSaverEnabled = prefs[Keys.POWER_SAVER] ?: false,
            language = language,
            notifyAboutUpdates = prefs[Keys.NOTIFY_ABOUT_UPDATES] ?: true,
            lastUpdateCheckMillis = prefs[Keys.LAST_UPDATE_CHECK_MILLIS] ?: 0L,
            skippedUpdateVersion = prefs[Keys.SKIPPED_UPDATE_VERSION].orEmpty()
        )
    }

    suspend fun updateDeviceName(name: String) {
        context.settingsDataStore.edit { prefs ->
            prefs[Keys.DEVICE_NAME] = name.trim().ifBlank { defaultDeviceLabel() }
        }
    }

    suspend fun updateThemePreference(theme: ThemePreference) {
        context.settingsDataStore.edit { prefs ->
            prefs[Keys.THEME_MODE] = theme.name
        }
    }

    suspend fun setBiometricLockEnabled(enabled: Boolean) {
        context.settingsDataStore.edit { prefs ->
            prefs[Keys.BIOMETRIC_LOCK] = enabled
        }
    }

    suspend fun setExportToPublicDownloads(enabled: Boolean) {
        context.settingsDataStore.edit { prefs ->
            prefs[Keys.PUBLIC_DOWNLOADS] = enabled
        }
    }

    suspend fun setPowerSaverEnabled(enabled: Boolean) {
        context.settingsDataStore.edit { prefs ->
            prefs[Keys.POWER_SAVER] = enabled
        }
    }

    suspend fun updateLanguage(languageCode: String) {
        val normalized = LocaleHelper.normalizeLanguageCode(languageCode)
        LocaleHelper.saveLanguageSync(context, normalized)
        context.settingsDataStore.edit { prefs ->
            prefs[Keys.LANGUAGE] = normalized
        }
    }

    suspend fun setNotifyAboutUpdates(enabled: Boolean) {
        context.settingsDataStore.edit { prefs ->
            prefs[Keys.NOTIFY_ABOUT_UPDATES] = enabled
        }
    }

    suspend fun recordLastUpdateCheckMillis(timestampMillis: Long) {
        context.settingsDataStore.edit { prefs ->
            prefs[Keys.LAST_UPDATE_CHECK_MILLIS] = timestampMillis
        }
    }

    suspend fun setSkippedUpdateVersion(version: String) {
        context.settingsDataStore.edit { prefs ->
            prefs[Keys.SKIPPED_UPDATE_VERSION] = version.trim()
        }
    }
}
