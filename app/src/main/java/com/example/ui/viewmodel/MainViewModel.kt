package com.example.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.data.db.TransferHistoryEntity
import com.example.data.db.TransferHistoryRepository
import com.example.data.prefs.SettingsDataStore
import com.example.data.prefs.UserSettings
import com.example.ui.theme.ThemePreference
import com.example.update.AppUpdateChecker
import com.example.update.AppUpdateDownloader
import com.example.update.GitHubReleaseInfo
import com.example.update.ManualUpdateCheckUiState
import com.example.update.UpdateCheckResult
import com.example.update.UpdateConfig
import com.example.update.UpdateDownloadState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class MainViewModel(
    private val settingsDataStore: SettingsDataStore,
    private val historyRepository: TransferHistoryRepository,
    private val appUpdateChecker: AppUpdateChecker? = null,
    private val appUpdateDownloader: AppUpdateDownloader? = null
) : ViewModel() {

    val settings: StateFlow<UserSettings> = settingsDataStore.settings.stateIn(
        scope = viewModelScope,
        started = SharingStarted.Eagerly,
        initialValue = UserSettings(language = settingsDataStore.getCachedLanguage())
    )

    val history: StateFlow<List<TransferHistoryEntity>> = historyRepository.recentTransfers.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = emptyList()
    )

    private val _isLocked = MutableStateFlow(false)
    val isLocked: StateFlow<Boolean> = _isLocked.asStateFlow()

    private val _lockChecked = MutableStateFlow(false)
    val lockChecked: StateFlow<Boolean> = _lockChecked.asStateFlow()

    private val _homeUpdatePrompt = MutableStateFlow<GitHubReleaseInfo?>(null)
    val homeUpdatePrompt: StateFlow<GitHubReleaseInfo?> = _homeUpdatePrompt.asStateFlow()

    private val _manualUpdateState =
        MutableStateFlow<ManualUpdateCheckUiState>(ManualUpdateCheckUiState.Idle)
    val manualUpdateState: StateFlow<ManualUpdateCheckUiState> = _manualUpdateState.asStateFlow()

    val updateDownloadState: StateFlow<UpdateDownloadState> =
        appUpdateDownloader?.state ?: MutableStateFlow<UpdateDownloadState>(UpdateDownloadState.Idle).asStateFlow()

    private var hasAttemptedAutoCheck = false

    init {
        viewModelScope.launch {
            val initial = settingsDataStore.settings.first()
            _isLocked.value = initial.biometricLockEnabled
            _lockChecked.value = true
        }
    }

    fun unlockApp() {
        _isLocked.value = false
    }

    fun checkForUpdatesAutomaticallyIfDue() {
        val checker = appUpdateChecker ?: return
        if (hasAttemptedAutoCheck) return
        hasAttemptedAutoCheck = true
        viewModelScope.launch {
            when (val result = checker.checkAutomaticallyIfDue()) {
                is UpdateCheckResult.UpdateAvailable -> {
                    _homeUpdatePrompt.value = result.release
                }
                else -> {
                    // Silent on Offline, UpToDate, Skipped, or Error
                }
            }
        }
    }

    fun checkForUpdatesManually() {
        val checker = appUpdateChecker ?: return
        if (_manualUpdateState.value is ManualUpdateCheckUiState.Checking) return
        viewModelScope.launch {
            _manualUpdateState.value = ManualUpdateCheckUiState.Checking
            _manualUpdateState.value = when (val result = checker.checkManuallyNow()) {
                is UpdateCheckResult.UpdateAvailable -> {
                    ManualUpdateCheckUiState.UpdateAvailable(result.release)
                }
                is UpdateCheckResult.UpToDate -> ManualUpdateCheckUiState.UpToDate
                is UpdateCheckResult.Offline,
                is UpdateCheckResult.Error,
                is UpdateCheckResult.Skipped -> ManualUpdateCheckUiState.Failed
            }
        }
    }

    fun startAppUpdate(release: GitHubReleaseInfo) {
        _homeUpdatePrompt.value = null
        appUpdateDownloader?.startUpdateDownload(release, viewModelScope)
    }

    fun deferUpdateForLater(release: GitHubReleaseInfo) {
        _homeUpdatePrompt.value = null
        viewModelScope.launch {
            appUpdateChecker?.skipVersion(release.normalizedVersion)
        }
    }

    fun openInstallPermissionSettings() {
        appUpdateDownloader?.openInstallPermissionSettings()
    }

    fun retryInstallDownloadedUpdate() {
        appUpdateDownloader?.retryInstallAfterPermission(viewModelScope)
    }

    fun openUpdateBrowserFallback(url: String = UpdateConfig.RELEASES_PAGE_URL) {
        appUpdateDownloader?.openFallbackBrowser(url)
    }

    fun dismissUpdateDownloadUi() {
        appUpdateDownloader?.reset()
    }

    fun updateDeviceName(name: String) {
        viewModelScope.launch { settingsDataStore.updateDeviceName(name) }
    }

    fun updateThemePreference(theme: ThemePreference) {
        viewModelScope.launch { settingsDataStore.updateThemePreference(theme) }
    }

    fun setBiometricLockEnabled(enabled: Boolean) {
        viewModelScope.launch { settingsDataStore.setBiometricLockEnabled(enabled) }
    }

    fun setExportToPublicDownloads(enabled: Boolean) {
        viewModelScope.launch { settingsDataStore.setExportToPublicDownloads(enabled) }
    }

    fun setPowerSaverEnabled(enabled: Boolean) {
        viewModelScope.launch { settingsDataStore.setPowerSaverEnabled(enabled) }
    }

    fun updateLanguage(languageCode: String) {
        viewModelScope.launch { settingsDataStore.updateLanguage(languageCode) }
    }

    fun setNotifyAboutUpdates(enabled: Boolean) {
        if (!enabled) {
            _homeUpdatePrompt.value = null
        }
        viewModelScope.launch { settingsDataStore.setNotifyAboutUpdates(enabled) }
    }

    fun deleteHistoryItem(id: Long) {
        viewModelScope.launch { historyRepository.deleteTransfer(id) }
    }

    fun clearHistory() {
        viewModelScope.launch { historyRepository.clearHistory() }
    }

    class Factory(
        private val settingsDataStore: SettingsDataStore,
        private val historyRepository: TransferHistoryRepository,
        private val appUpdateChecker: AppUpdateChecker? = null,
        private val appUpdateDownloader: AppUpdateDownloader? = null
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return MainViewModel(
                settingsDataStore = settingsDataStore,
                historyRepository = historyRepository,
                appUpdateChecker = appUpdateChecker,
                appUpdateDownloader = appUpdateDownloader
            ) as T
        }
    }
}
