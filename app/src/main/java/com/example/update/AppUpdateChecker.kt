package com.example.update

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import com.example.BuildConfig
import com.example.data.prefs.SettingsDataStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

fun interface NetworkConnectivityChecker {
    fun hasInternetConnection(): Boolean
}

class AndroidNetworkConnectivityChecker(
    private val context: Context
) : NetworkConnectivityChecker {
    override fun hasInternetConnection(): Boolean {
        return try {
            val cm = context.applicationContext
                .getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                ?: return false
            val activeNetwork = cm.activeNetwork ?: return false
            val caps = cm.getNetworkCapabilities(activeNetwork) ?: return false
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                (
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                        caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) ||
                        caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) ||
                        caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
                    )
        } catch (_: Throwable) {
            false
        }
    }
}

fun interface GitHubReleaseFetcher {
    suspend fun fetchLatestReleaseJson(url: String): String?
}

class OkHttpGitHubReleaseFetcher(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .callTimeout(15, TimeUnit.SECONDS)
        .build()
) : GitHubReleaseFetcher {

    override suspend fun fetchLatestReleaseJson(url: String): String? = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url(url)
                .header("Accept", "application/vnd.github+json")
                .header("User-Agent", "APK-Share-Android/${BuildConfig.VERSION_NAME}")
                .get()
                .build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.w(TAG, "GitHub release check returned HTTP ${response.code}")
                    return@withContext null
                }
                response.body?.string()
            }
        } catch (t: Throwable) {
            Log.w(TAG, "GitHub release check failed silently: ${t.message}")
            null
        }
    }

    companion object {
        private const val TAG = "GitHubReleaseFetcher"
    }
}

class AppUpdateChecker(
    private val settingsDataStore: SettingsDataStore,
    private val networkChecker: NetworkConnectivityChecker,
    private val releaseFetcher: GitHubReleaseFetcher = OkHttpGitHubReleaseFetcher(),
    private val currentVersionProvider: () -> String = { BuildConfig.VERSION_NAME },
    private val clock: () -> Long = { System.currentTimeMillis() }
) {

    /**
     * Runs the automatic Home-screen update check:
     * - Does nothing if "Notify about new updates" is OFF.
     * - Runs at most once per 24 hours (persisted in DataStore).
     * - Runs only when internet is available.
     * - Suppresses versions previously skipped via "Later".
     * - Never throws or shows errors on failure/offline.
     */
    suspend fun checkAutomaticallyIfDue(): UpdateCheckResult {
        return try {
            val settings = settingsDataStore.settings.first()
            if (!settings.notifyAboutUpdates) {
                return UpdateCheckResult.Skipped
            }

            val now = clock()
            if (settings.lastUpdateCheckMillis > 0L &&
                (now - settings.lastUpdateCheckMillis) < UpdateConfig.ONE_DAY_MILLIS
            ) {
                return UpdateCheckResult.Skipped
            }

            if (!networkChecker.hasInternetConnection()) {
                return UpdateCheckResult.Offline
            }

            val rawJson = releaseFetcher.fetchLatestReleaseJson(UpdateConfig.LATEST_RELEASE_API_URL)
                ?: return UpdateCheckResult.Error("empty_or_failed_response")

            val release = GitHubReleaseParser.parse(rawJson)
                ?: return UpdateCheckResult.Error("invalid_release_payload")

            settingsDataStore.recordLastUpdateCheckMillis(now)

            val currentVersion = currentVersionProvider()
            if (!VersionComparator.isNewer(release.tagName, currentVersion)) {
                return UpdateCheckResult.UpToDate(release.normalizedVersion)
            }

            val skipped = VersionComparator.normalize(settings.skippedUpdateVersion)
            if (skipped.isNotBlank() && skipped == release.normalizedVersion) {
                return UpdateCheckResult.Skipped
            }

            UpdateCheckResult.UpdateAvailable(release)
        } catch (t: Throwable) {
            Log.w(TAG, "Automatic update check failed silently: ${t.message}")
            UpdateCheckResult.Error(t.message)
        }
    }

    /**
     * Runs an immediate user-initiated check from Settings, bypassing the 24-hour throttle
     * and any previously skipped version.
     */
    suspend fun checkManuallyNow(): UpdateCheckResult {
        return try {
            if (!networkChecker.hasInternetConnection()) {
                return UpdateCheckResult.Offline
            }

            val rawJson = releaseFetcher.fetchLatestReleaseJson(UpdateConfig.LATEST_RELEASE_API_URL)
                ?: return UpdateCheckResult.Error("empty_or_failed_response")

            val release = GitHubReleaseParser.parse(rawJson)
                ?: return UpdateCheckResult.Error("invalid_release_payload")

            settingsDataStore.recordLastUpdateCheckMillis(clock())

            val currentVersion = currentVersionProvider()
            if (VersionComparator.isNewer(release.tagName, currentVersion)) {
                UpdateCheckResult.UpdateAvailable(release)
            } else {
                UpdateCheckResult.UpToDate(release.normalizedVersion)
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Manual update check failed: ${t.message}")
            UpdateCheckResult.Error(t.message)
        }
    }

    suspend fun skipVersion(version: String) {
        val normalized = VersionComparator.normalize(version)
        if (normalized.isNotBlank()) {
            settingsDataStore.setSkippedUpdateVersion(normalized)
        }
    }

    companion object {
        private const val TAG = "AppUpdateChecker"
    }
}
