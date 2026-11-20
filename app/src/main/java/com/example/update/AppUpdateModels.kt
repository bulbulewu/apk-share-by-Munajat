package com.example.update

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

object UpdateConfig {
    const val REPO_OWNER = "bulbulewu"
    const val REPO_NAME = "apk-share-by-Munajat"
    const val LATEST_RELEASE_API_URL =
        "https://api.github.com/repos/$REPO_OWNER/$REPO_NAME/releases/latest"
    const val RELEASES_PAGE_URL =
        "https://github.com/$REPO_OWNER/$REPO_NAME/releases"
    const val ONE_DAY_MILLIS = 24L * 60L * 60L * 1000L
    const val DOWNLOADS_SUBFOLDER = "APK Share"
}

data class GitHubReleaseInfo(
    val tagName: String,
    val normalizedVersion: String,
    val apkDownloadUrl: String?,
    val apkFileName: String,
    val releasePageUrl: String
)

sealed interface UpdateCheckResult {
    data class UpdateAvailable(val release: GitHubReleaseInfo) : UpdateCheckResult
    data class UpToDate(val latestVersion: String) : UpdateCheckResult
    data object Skipped : UpdateCheckResult
    data object Offline : UpdateCheckResult
    data class Error(val reason: String? = null) : UpdateCheckResult
}

sealed interface ManualUpdateCheckUiState {
    data object Idle : ManualUpdateCheckUiState
    data object Checking : ManualUpdateCheckUiState
    data class UpdateAvailable(val release: GitHubReleaseInfo) : ManualUpdateCheckUiState
    data object UpToDate : ManualUpdateCheckUiState
    data object Failed : ManualUpdateCheckUiState
}

sealed interface UpdateDownloadState {
    data object Idle : UpdateDownloadState
    data class Downloading(
        val versionName: String,
        val progressPercent: Int,
        val downloadedBytes: Long,
        val totalBytes: Long
    ) : UpdateDownloadState

    data class PermissionRequired(
        val apkFile: File,
        val versionName: String,
        val releasePageUrl: String
    ) : UpdateDownloadState

    data class Installing(val versionName: String) : UpdateDownloadState
    data object FallbackOpened : UpdateDownloadState
}

object VersionComparator {

    fun normalize(raw: String): String {
        return raw.trim().removePrefix("v").removePrefix("V").trim()
    }

    /**
     * Compares two dot-separated version strings numerically part by part.
     * Examples: "v1.3" > "1.2", "1.10" > "1.2", "1.2.1" > "1.2", "v1.3" == "1.3.0".
     * Returns > 0 if [first] > [second], 0 if equal, < 0 if [first] < [second].
     */
    fun compareVersions(first: String, second: String): Int {
        val partsA = parseNumericParts(first)
        val partsB = parseNumericParts(second)
        if (partsA.isEmpty() && partsB.isEmpty()) return 0
        val maxLen = maxOf(partsA.size, partsB.size)
        for (i in 0 until maxLen) {
            val a = partsA.getOrElse(i) { 0L }
            val b = partsB.getOrElse(i) { 0L }
            if (a != b) return a.compareTo(b)
        }
        return 0
    }

    fun isNewer(latestTag: String, currentVersion: String): Boolean {
        val latestParts = parseNumericParts(latestTag)
        val currentParts = parseNumericParts(currentVersion)
        if (latestParts.isEmpty() || currentParts.isEmpty()) return false
        return compareVersions(latestTag, currentVersion) > 0
    }

    private fun parseNumericParts(raw: String): List<Long> {
        val cleaned = normalize(raw)
        if (cleaned.isBlank()) return emptyList()
        // Strip optional pre-release/build metadata suffix (e.g. "1.3.0-beta.1" -> "1.3.0")
        val core = cleaned.substringBefore('-').substringBefore('+')
        if (core.isBlank()) return emptyList()
        val segments = core.split('.')
        val result = ArrayList<Long>(segments.size)
        for (seg in segments) {
            val digits = seg.takeWhile { it.isDigit() }
            val parsed = digits.toLongOrNull() ?: return emptyList()
            result.add(parsed)
        }
        return result
    }
}

@Serializable
private data class GitHubReleaseDto(
    @SerialName("tag_name") val tagName: String? = null,
    @SerialName("html_url") val htmlUrl: String? = null,
    @SerialName("assets") val assets: List<GitHubAssetDto> = emptyList()
)

@Serializable
private data class GitHubAssetDto(
    @SerialName("name") val name: String? = null,
    @SerialName("browser_download_url") val browserDownloadUrl: String? = null
)

object GitHubReleaseParser {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
    }

    fun parse(rawJson: String): GitHubReleaseInfo? {
        if (rawJson.isBlank()) return null
        return runCatching {
            val dto = json.decodeFromString<GitHubReleaseDto>(rawJson)
            val tag = dto.tagName?.trim().orEmpty()
            val normalized = VersionComparator.normalize(tag)
            if (tag.isBlank() || normalized.isBlank()) return null

            val apkAsset = dto.assets.firstOrNull { asset ->
                val name = asset.name.orEmpty()
                val url = asset.browserDownloadUrl.orEmpty()
                name.endsWith(".apk", ignoreCase = true) && url.startsWith("https://", ignoreCase = true)
            } ?: dto.assets.firstOrNull { asset ->
                val name = asset.name.orEmpty()
                val url = asset.browserDownloadUrl.orEmpty()
                name.endsWith(".apk", ignoreCase = true) && url.startsWith("http://", ignoreCase = true)
            }

            val fileName = apkAsset?.name?.trim()?.takeIf { it.isNotBlank() }
                ?: "APK-Share-v$normalized.apk"
            val pageUrl = dto.htmlUrl?.trim()
                ?.takeIf { it.startsWith("https://", ignoreCase = true) }
                ?: UpdateConfig.RELEASES_PAGE_URL

            GitHubReleaseInfo(
                tagName = tag,
                normalizedVersion = normalized,
                apkDownloadUrl = apkAsset?.browserDownloadUrl?.trim()?.takeIf { it.isNotBlank() },
                apkFileName = fileName,
                releasePageUrl = pageUrl
            )
        }.getOrNull()
    }
}
