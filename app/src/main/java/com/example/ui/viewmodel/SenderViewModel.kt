package com.example.ui.viewmodel

import androidx.compose.ui.graphics.ImageBitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.apk.ApkExtractor
import com.example.apk.InstalledAppsRepository
import com.example.data.db.TransferHistoryEntity
import com.example.data.db.TransferHistoryRepository
import com.example.data.prefs.SettingsDataStore
import com.example.model.ExtractedApkBundle
import com.example.model.InstalledAppInfo
import com.example.model.TransferDirection
import com.example.model.TransferMethod
import com.example.model.TransferStatus
import com.example.service.TransferForegroundService
import com.example.transfer.HotspotController
import com.example.transfer.HotspotInfo
import com.example.transfer.HttpServerStatus
import com.example.transfer.LocalHttpApkServer
import com.example.transfer.NearbySenderState
import com.example.transfer.NearbyTransferManager
import com.example.util.ApkShareHelper
import com.example.util.ShareableApkResult
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

enum class AppSortOrder {
    NAME,
    SIZE,
    INSTALL_DATE
}

sealed interface AppsUiState {
    data object Loading : AppsUiState
    data class Loaded(val apps: List<InstalledAppInfo>) : AppsUiState
    data class Error(val message: String) : AppsUiState
}

sealed interface ExtractionUiState {
    data object Idle : ExtractionUiState
    data class Extracting(val appName: String) : ExtractionUiState
    data class Ready(val bundle: ExtractedApkBundle) : ExtractionUiState
    data class Error(val message: String) : ExtractionUiState
}

class SenderViewModel(
    private val appsRepository: InstalledAppsRepository,
    private val apkExtractor: ApkExtractor,
    private val nearbyManager: NearbyTransferManager,
    private val hotspotController: HotspotController,
    private val httpServer: LocalHttpApkServer,
    private val settingsDataStore: SettingsDataStore,
    private val historyRepository: TransferHistoryRepository
) : ViewModel() {

    private val allApps = MutableStateFlow<List<InstalledAppInfo>>(emptyList())
    private val isLoadingApps = MutableStateFlow(true)
    private val appsLoadError = MutableStateFlow<String?>(null)

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    private val _sortOrder = MutableStateFlow(AppSortOrder.NAME)
    val sortOrder: StateFlow<AppSortOrder> = _sortOrder.asStateFlow()

    private val _selectedApp = MutableStateFlow<InstalledAppInfo?>(null)
    val selectedApp: StateFlow<InstalledAppInfo?> = _selectedApp.asStateFlow()

    private val _extractionState = MutableStateFlow<ExtractionUiState>(ExtractionUiState.Idle)
    val extractionState: StateFlow<ExtractionUiState> = _extractionState.asStateFlow()

    private val _hotspotModeActive = MutableStateFlow(false)
    val hotspotModeActive: StateFlow<Boolean> = _hotspotModeActive.asStateFlow()

    val nearbyState: StateFlow<NearbySenderState> = nearbyManager.senderState
    val hotspotInfo: StateFlow<HotspotInfo> = hotspotController.hotspotInfo
    val httpServerStatus: StateFlow<HttpServerStatus> = httpServer.status

    private var recordedTransferId: String? = null
    private var advertisingJob: Job? = null

    val appsUiState: StateFlow<AppsUiState> = combine(
        allApps,
        isLoadingApps,
        appsLoadError,
        _searchQuery,
        _sortOrder
    ) { apps, loading, error, query, sort ->
        when {
            loading -> AppsUiState.Loading
            error != null -> AppsUiState.Error(error)
            else -> {
                val filtered = if (query.isBlank()) {
                    apps
                } else {
                    val q = query.trim().lowercase()
                    apps.filter {
                        it.appName.lowercase().contains(q) ||
                            it.packageName.lowercase().contains(q)
                    }
                }
                val sorted = when (sort) {
                    AppSortOrder.NAME -> filtered.sortedBy { it.appName.lowercase() }
                    AppSortOrder.SIZE -> filtered.sortedByDescending { it.totalSizeBytes }
                    AppSortOrder.INSTALL_DATE -> filtered.sortedByDescending { it.installDateMillis }
                }
                AppsUiState.Loaded(sorted)
            }
        }
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = AppsUiState.Loading
    )

    init {
        loadApps()
        observeNearbyResultForHistory()
        observeNotificationCancellation()
    }

    private fun observeNearbyResultForHistory() {
        viewModelScope.launch {
            nearbyState.collect { state ->
                when (state) {
                    is NearbySenderState.Success -> {
                        if (recordedTransferId != state.bundle.transferId) {
                            recordedTransferId = state.bundle.transferId
                            historyRepository.recordTransfer(
                                TransferHistoryEntity(
                                    appName = state.bundle.appName,
                                    packageName = state.bundle.packageName,
                                    versionName = state.bundle.versionName,
                                    sizeBytes = state.bundle.totalSizeBytes,
                                    timestampMillis = System.currentTimeMillis(),
                                    direction = TransferDirection.SENT,
                                    method = TransferMethod.NEARBY,
                                    status = TransferStatus.SUCCESS,
                                    peerDeviceName = state.peerName,
                                    isSplitApk = state.bundle.isSplitApk,
                                    durationMillis = state.durationMillis
                                )
                            )
                        }
                    }
                    is NearbySenderState.Error -> {
                        val b = state.bundle
                        if (b != null && recordedTransferId != "${b.transferId}_err") {
                            recordedTransferId = "${b.transferId}_err"
                            historyRepository.recordTransfer(
                                TransferHistoryEntity(
                                    appName = b.appName,
                                    packageName = b.packageName,
                                    versionName = b.versionName,
                                    sizeBytes = b.totalSizeBytes,
                                    timestampMillis = System.currentTimeMillis(),
                                    direction = TransferDirection.SENT,
                                    method = TransferMethod.NEARBY,
                                    status = TransferStatus.FAILED,
                                    peerDeviceName = "Nearby Peer",
                                    isSplitApk = b.isSplitApk,
                                    errorReason = state.reason
                                )
                            )
                        }
                    }
                    else -> Unit
                }
            }
        }
    }

    private fun observeNotificationCancellation() {
        viewModelScope.launch {
            TransferForegroundService.cancelRequests.collect {
                cancelActiveTransfer()
            }
        }
    }

    fun loadApps() {
        viewModelScope.launch {
            isLoadingApps.value = true
            appsLoadError.value = null
            try {
                allApps.value = appsRepository.loadUserInstalledApps()
            } catch (t: Throwable) {
                appsLoadError.value = t.message ?: "Failed to load installed apps"
            } finally {
                isLoadingApps.value = false
            }
        }
    }

    suspend fun loadIcon(packageName: String): ImageBitmap? =
        appsRepository.loadAppIcon(packageName)

    fun updateSearchQuery(query: String) {
        _searchQuery.value = query
    }

    fun updateSortOrder(order: AppSortOrder) {
        _sortOrder.value = order
    }

    fun selectApp(app: InstalledAppInfo) {
        _selectedApp.value = if (_selectedApp.value?.packageName == app.packageName) null else app
    }

    fun prepareAndStartNearbyTransfer(onExtracted: () -> Unit) {
        val app = _selectedApp.value ?: return
        viewModelScope.launch {
            advertisingJob?.cancel()
            nearbyManager.cancelSender()
            _hotspotModeActive.value = false
            _extractionState.value = ExtractionUiState.Extracting(app.appName)
            val result = apkExtractor.extractBundle(app)
            result.onSuccess { bundle ->
                recordedTransferId = null
                _extractionState.value = ExtractionUiState.Ready(bundle)
                // Do NOT start Nearby advertising here; SenderTransferScreen starts it
                // only after all runtime permissions are granted and Bluetooth + Wi-Fi are ON.
                onExtracted()
            }.onFailure { err ->
                _extractionState.value = ExtractionUiState.Error(
                    err.message ?: "Could not extract APK for ${app.appName}."
                )
            }
        }
    }

    fun prepareAndShareApp(
        app: InstalledAppInfo,
        onReadyToShare: (ShareableApkResult) -> Unit,
        onError: (String?) -> Unit
    ) {
        viewModelScope.launch {
            _selectedApp.value = app
            _extractionState.value = ExtractionUiState.Extracting(app.appName)
            val extractedResult = apkExtractor.extractBundle(app)
            extractedResult.onSuccess { bundle ->
                val shareableResult = runCatching { ApkShareHelper.prepareShareableFile(bundle) }
                shareableResult.onSuccess { shareable ->
                    _extractionState.value = ExtractionUiState.Ready(bundle)
                    onReadyToShare(shareable)
                }.onFailure { err ->
                    val message = err.message
                    _extractionState.value = ExtractionUiState.Error(message.orEmpty())
                    onError(message)
                }
            }.onFailure { err ->
                val message = err.message
                _extractionState.value = ExtractionUiState.Error(message.orEmpty())
                onError(message)
            }
        }
    }

    fun startNearbyAdvertising() {
        val currentBundle = (_extractionState.value as? ExtractionUiState.Ready)?.bundle ?: return
        advertisingJob?.cancel()
        advertisingJob = viewModelScope.launch {
            _hotspotModeActive.value = false
            recordedTransferId = null
            val deviceName = settingsDataStore.settings.first().deviceName
            nearbyManager.startAdvertising(deviceName, currentBundle)
        }
    }

    fun retryNearbyAdvertising() {
        startNearbyAdvertising()
    }

    fun acceptNearbyConnection(endpointId: String) {
        nearbyManager.acceptIncomingConnection(endpointId)
    }

    fun rejectNearbyConnection(endpointId: String) {
        nearbyManager.rejectIncomingConnection(endpointId)
    }

    fun switchToHotspotFallback() {
        val bundle = (_extractionState.value as? ExtractionUiState.Ready)?.bundle ?: return
        advertisingJob?.cancel()
        nearbyManager.stopAll()
        _hotspotModeActive.value = true
        httpServer.start(bundle, preferredPort = 8080)
        // LocalOnlyHotspot is started from SenderTransferScreen after hotspot permissions are verified.
    }

    fun startHotspotModeDirect(onReady: () -> Unit) {
        val app = _selectedApp.value ?: return
        viewModelScope.launch {
            advertisingJob?.cancel()
            nearbyManager.cancelSender()
            _extractionState.value = ExtractionUiState.Extracting(app.appName)
            apkExtractor.extractBundle(app).onSuccess { bundle ->
                _extractionState.value = ExtractionUiState.Ready(bundle)
                _hotspotModeActive.value = true
                httpServer.start(bundle, preferredPort = 8080)
                // LocalOnlyHotspot is started from SenderTransferScreen after hotspot permissions are verified.
                onReady()
            }.onFailure { err ->
                _extractionState.value = ExtractionUiState.Error(
                    err.message ?: "Failed to extract APK"
                )
            }
        }
    }

    fun startLocalHotspot() {
        hotspotController.startLocalHotspot()
    }

    fun refreshHotspotIp() {
        hotspotController.refreshDetectedIp()
    }

    fun markHotspotTransferComplete() {
        val bundle = (_extractionState.value as? ExtractionUiState.Ready)?.bundle ?: return
        viewModelScope.launch {
            if (recordedTransferId != "${bundle.transferId}_http") {
                recordedTransferId = "${bundle.transferId}_http"
                historyRepository.recordTransfer(
                    TransferHistoryEntity(
                        appName = bundle.appName,
                        packageName = bundle.packageName,
                        versionName = bundle.versionName,
                        sizeBytes = bundle.totalSizeBytes,
                        timestampMillis = System.currentTimeMillis(),
                        direction = TransferDirection.SENT,
                        method = TransferMethod.HOTSPOT_HTTP,
                        status = TransferStatus.SUCCESS,
                        peerDeviceName = "Wi-Fi Hotspot Client",
                        isSplitApk = bundle.isSplitApk
                    )
                )
            }
        }
    }

    fun cancelActiveTransfer() {
        advertisingJob?.cancel()
        nearbyManager.cancelSender()
        httpServer.stop()
        hotspotController.stopLocalHotspot()
        _hotspotModeActive.value = false
    }

    fun clearExtractionError() {
        if (_extractionState.value is ExtractionUiState.Error) {
            _extractionState.value = ExtractionUiState.Idle
        }
    }

    override fun onCleared() {
        super.onCleared()
        cancelActiveTransfer()
    }

    class Factory(
        private val appsRepository: InstalledAppsRepository,
        private val apkExtractor: ApkExtractor,
        private val nearbyManager: NearbyTransferManager,
        private val hotspotController: HotspotController,
        private val httpServer: LocalHttpApkServer,
        private val settingsDataStore: SettingsDataStore,
        private val historyRepository: TransferHistoryRepository
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return SenderViewModel(
                appsRepository,
                apkExtractor,
                nearbyManager,
                hotspotController,
                httpServer,
                settingsDataStore,
                historyRepository
            ) as T
        }
    }
}
