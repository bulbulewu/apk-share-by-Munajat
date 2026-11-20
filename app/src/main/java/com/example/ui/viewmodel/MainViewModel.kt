package com.example.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.data.db.TransferHistoryEntity
import com.example.data.db.TransferHistoryRepository
import com.example.data.prefs.SettingsDataStore
import com.example.data.prefs.UserSettings
import com.example.ui.theme.ThemePreference
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class MainViewModel(
    private val settingsDataStore: SettingsDataStore,
    private val historyRepository: TransferHistoryRepository
) : ViewModel() {

    val settings: StateFlow<UserSettings> = settingsDataStore.settings.stateIn(
        scope = viewModelScope,
        started = SharingStarted.Eagerly,
        initialValue = UserSettings()
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

    fun deleteHistoryItem(id: Long) {
        viewModelScope.launch { historyRepository.deleteTransfer(id) }
    }

    fun clearHistory() {
        viewModelScope.launch { historyRepository.clearHistory() }
    }

    class Factory(
        private val settingsDataStore: SettingsDataStore,
        private val historyRepository: TransferHistoryRepository
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return MainViewModel(settingsDataStore, historyRepository) as T
        }
    }
}
