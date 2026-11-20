package com.example.util

import android.app.Activity
import android.app.LocaleManager
import android.content.Context
import android.content.ContextWrapper
import android.content.res.Configuration
import android.content.res.Resources
import android.os.Build
import android.os.LocaleList
import java.util.Locale

/**
 * Manages per-app language selection ("system", "en", "bn") without changing system language.
 * Uses Android's native per-app LocaleManager API on Android 13+ (API 33+, including API 36),
 * plus synchronized localized Context/Resources wrapping for immediate UI updates across all APIs.
 */
object LocaleHelper {

    const val LANGUAGE_SYSTEM = "system"
    const val LANGUAGE_ENGLISH = "en"
    const val LANGUAGE_BANGLA = "bn"

    private const val SYNC_PREFS_NAME = "apk_share_locale_sync"
    private const val KEY_LANGUAGE = "language"

    fun normalizeLanguageCode(code: String?): String {
        return when (code?.trim()?.lowercase(Locale.ROOT)) {
            LANGUAGE_ENGLISH -> LANGUAGE_ENGLISH
            LANGUAGE_BANGLA -> LANGUAGE_BANGLA
            else -> LANGUAGE_SYSTEM
        }
    }

    fun saveLanguageSync(context: Context, languageCode: String) {
        val normalized = normalizeLanguageCode(languageCode)
        runCatching {
            context.applicationContext
                .getSharedPreferences(SYNC_PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_LANGUAGE, normalized)
                .apply()
        }
    }

    fun getSavedLanguageSync(context: Context): String {
        val stored = runCatching {
            context.applicationContext
                .getSharedPreferences(SYNC_PREFS_NAME, Context.MODE_PRIVATE)
                .getString(KEY_LANGUAGE, LANGUAGE_SYSTEM)
        }.getOrNull()
        return normalizeLanguageCode(stored)
    }

    /**
     * Applies the chosen app-only locale using Android 13+ LocaleManager.setApplicationLocales
     * (guarded against redundant updates) and persists a synchronous mirror for pre-API 33 and
     * background/non-Compose string resolution.
     */
    fun applyAppLocale(context: Context, languageCode: String) {
        val normalized = normalizeLanguageCode(languageCode)
        saveLanguageSync(context, normalized)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            runCatching {
                val localeManager = context.getSystemService(LocaleManager::class.java) ?: return
                val targetList = when (normalized) {
                    LANGUAGE_ENGLISH -> LocaleList.forLanguageTags(LANGUAGE_ENGLISH)
                    LANGUAGE_BANGLA -> LocaleList.forLanguageTags(LANGUAGE_BANGLA)
                    else -> LocaleList.getEmptyLocaleList()
                }
                val currentList = localeManager.applicationLocales
                val same = if (currentList.isEmpty && targetList.isEmpty) {
                    true
                } else if (!currentList.isEmpty && !targetList.isEmpty) {
                    currentList.get(0)?.language.equals(targetList.get(0)?.language, ignoreCase = true)
                } else {
                    false
                }
                if (!same) {
                    localeManager.applicationLocales = targetList
                }
            }
        }
    }

    /**
     * Wraps base context for pre-Android 13 attachBaseContext() so Resources start in the saved language.
     */
    fun wrapBaseContext(base: Context): Context {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            return base
        }
        val saved = getSavedLanguageSync(base)
        if (saved == LANGUAGE_SYSTEM) return base
        val targetLocale = Locale.forLanguageTag(saved)
        val config = Configuration(base.resources.configuration)
        config.setLocale(targetLocale)
        config.setLayoutDirection(targetLocale)
        return base.createConfigurationContext(config)
    }

    /**
     * Returns a Context whose Resources resolve strings in the currently selected app language.
     * Safe for non-UI classes (managers, installers, ViewModels, BroadcastReceivers).
     */
    fun localizedContext(context: Context, languageOverride: String? = null): Context {
        val code = normalizeLanguageCode(languageOverride ?: getSavedLanguageSync(context))
        val baseConfig = context.resources.configuration
        val config = Configuration(baseConfig)
        if (code == LANGUAGE_SYSTEM) {
            val systemLocale = resolveSystemLocale(context)
            config.setLocales(LocaleList(systemLocale))
            config.setLayoutDirection(systemLocale)
        } else {
            val targetLocale = Locale.forLanguageTag(code)
            config.setLocales(LocaleList(targetLocale))
            config.setLayoutDirection(targetLocale)
        }
        return runCatching { context.createConfigurationContext(config) }.getOrDefault(context)
    }

    /**
     * Produces a Context + Configuration pair for Compose CompositionLocalProvider so switching
     * between System Default / English / বাংলা updates every Compose screen immediately without
     * breaking Activity delegation (startActivity, Toast, permissions).
     */
    fun createLocalizedComposeContext(
        baseContext: Context,
        baseConfig: Configuration,
        languageCode: String
    ): Pair<Context, Configuration> {
        val normalized = normalizeLanguageCode(languageCode)
        val newConfig = Configuration(baseConfig)
        if (normalized == LANGUAGE_SYSTEM) {
            val resolvedSystemLocales = resolveSystemLocales(baseContext)
            newConfig.setLocales(resolvedSystemLocales)
            if (!resolvedSystemLocales.isEmpty) {
                newConfig.setLayoutDirection(resolvedSystemLocales.get(0))
            }
        } else {
            val locale = Locale.forLanguageTag(normalized)
            newConfig.setLocales(LocaleList(locale))
            newConfig.setLayoutDirection(locale)
        }
        val localizedRes = runCatching {
            baseContext.createConfigurationContext(newConfig).resources
        }.getOrDefault(baseContext.resources)

        val wrappedContext: Context = if (baseContext is Activity) {
            LocalizedActivityContext(baseContext, localizedRes)
        } else {
            LocalizedContextWrapper(baseContext, localizedRes)
        }
        return wrappedContext to newConfig
    }

    fun resolveSystemLocales(context: Context): LocaleList {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val sysLocales = runCatching {
                context.getSystemService(LocaleManager::class.java)?.systemLocales
            }.getOrNull()
            if (sysLocales != null && !sysLocales.isEmpty) {
                return sysLocales
            }
        }
        val systemResLocales = runCatching {
            Resources.getSystem().configuration.locales
        }.getOrNull()
        if (systemResLocales != null && !systemResLocales.isEmpty) {
            return systemResLocales
        }
        val cfgLocales = context.resources.configuration.locales
        if (!cfgLocales.isEmpty) {
            return cfgLocales
        }
        return LocaleList(Locale.getDefault())
    }

    private fun resolveSystemLocale(context: Context): Locale {
        val list = resolveSystemLocales(context)
        return if (!list.isEmpty) list.get(0) else Locale.getDefault()
    }

    private class LocalizedActivityContext(
        base: Activity,
        private val localizedResources: Resources
    ) : ContextWrapper(base) {
        override fun getResources(): Resources = localizedResources
    }

    private class LocalizedContextWrapper(
        base: Context,
        private val localizedResources: Resources
    ) : ContextWrapper(base) {
        override fun getResources(): Resources = localizedResources
    }
}
