package com.example.ui.viewmodel

import android.content.Context
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
import kotlinx.coroutines.delay
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

data class BatchTransferItemResult(
    val appName: String,
    val packageName: String,
    val succeeded: Boolean,
    val errorReason: String? = null
)

data class BatchTransferQueueState(
    val apps: List<InstalledAppInfo> = emptyList(),
    val currentIndex: Int = 0,
    val results: List<BatchTransferItemResult> = emptyList(),
    val isFinished: Boolean = false,
    val isPreparingNext: Boolean = false
) {
    val totalCount: Int get() = apps.size
    val isBatch: Boolean get() = apps.size > 1
    val currentApp: InstalledAppInfo? get() = apps.getOrNull(currentIndex)
    val currentNumber: Int get() = (currentIndex + 1).coerceAtMost(apps.size.coerceAtLeast(1))
    val sentCount: Int get() = results.count { it.succeeded }
    val failedItems: List<BatchTransferItemResult> get() = results.filter { !it.succeeded }
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

    private val _selectedPackageNames = MutableStateFlow<Set<String>>(emptySet())
    val selectedPackageNames: StateFlow<Set<String>> = _selectedPackageNames.asStateFlow()

    private val selectedAppSnapshots = mutableMapOf<String, InstalledAppInfo>()

    val selectedApps: StateFlow<List<InstalledAppInfo>> = combine(
        allApps,
        _selectedPackageNames
    ) { apps, selectedPkgs ->
        if (selectedPkgs.isEmpty()) {
            emptyList()
        } else {
            val byPkg = apps.associateBy { it.packageName }
            selectedPkgs.mapNotNull { pkg -> byPkg[pkg] ?: selectedAppSnapshots[pkg] }
        }
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.Eagerly,
        initialValue = emptyList()
    )

    private val _extractionState = MutableStateFlow<ExtractionUiState>(ExtractionUiState.Idle)
    val extractionState: StateFlow<ExtractionUiState> = _extractionState.asStateFlow()

    private val _isSavingToDownloads = MutableStateFlow(false)
    val isSavingToDownloads: StateFlow<Boolean> = _isSavingToDownloads.asStateFlow()

    private val _hotspotModeActive = MutableStateFlow(false)
    val hotspotModeActive: StateFlow<Boolean> = _hotspotModeActive.asStateFlow()

    private val _batchQueue = MutableStateFlow(BatchTransferQueueState())
    val batchQueue: StateFlow<BatchTransferQueueState> = _batchQueue.asStateFlow()

    val nearbyState: StateFlow<NearbySenderState> = nearbyManager.senderState
    val hotspotInfo: StateFlow<HotspotInfo> = hotspotController.hotspotInfo
    val httpServerStatus: StateFlow<HttpServerStatus> = httpServer.status

    private var recordedTransferId: String? = null
    private var handledBatchTransferKey: String? = null
    private var advertisingJob: Job? = null
    private var batchAdvanceJob: Job? = null

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
                        val queue = _batchQueue.value
                        val batchKey = "ok_${state.bundle.transferId}"
                        if (queue.isBatch && !queue.isFinished && !_hotspotModeActive.value &&
                            handledBatchTransferKey != batchKey
                        ) {
                            handledBatchTransferKey = batchKey
                            recordBatchResultAndAdvanceNearby(
                                BatchTransferItemResult(
                                    appName = state.bundle.appName,
                                    packageName = state.bundle.packageName,
                                    succeeded = true
                                ),
                                delayMillis = 500L
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
                        val queue = _batchQueue.value
                        val batchKey = "err_${b?.transferId ?: queue.currentIndex}"
                        if (b != null && queue.isBatch && !queue.isFinished && !_hotspotModeActive.value &&
                            handledBatchTransferKey != batchKey
                        ) {
                            handledBatchTransferKey = batchKey
                            recordBatchResultAndAdvanceNearby(
                                BatchTransferItemResult(
                                    appName = b.appName,
                                    packageName = b.packageName,
                                    succeeded = false,
                                    errorReason = state.reason
                                ),
                                delayMillis = 600L
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

    fun toggleAppSelection(app: InstalledAppInfo) {
        val updated = LinkedHashSet(_selectedPackageNames.value)
        if (updated.contains(app.packageName)) {
            updated.remove(app.packageName)
            selectedAppSnapshots.remove(app.packageName)
        } else {
            updated.add(app.packageName)
            selectedAppSnapshots[app.packageName] = app
        }
        _selectedPackageNames.value = updated
    }

    fun selectApp(app: InstalledAppInfo) {
        toggleAppSelection(app)
    }

    fun clearSelection() {
        selectedAppSnapshots.clear()
        _selectedPackageNames.value = emptySet()
    }

    private fun currentSelectedAppsSnapshot(): List<InstalledAppInfo> {
        val selectedPkgs = _selectedPackageNames.value
        if (selectedPkgs.isEmpty()) return emptyList()
        val byPkg = allApps.value.associateBy { it.packageName }
        return selectedPkgs.mapNotNull { pkg -> byPkg[pkg] ?: selectedAppSnapshots[pkg] }
    }

    fun prepareAndStartNearbyTransfer(onExtracted: () -> Unit) {
        val queueApps = currentSelectedAppsSnapshot()
        if (queueApps.isEmpty()) return
        viewModelScope.launch {
            batchAdvanceJob?.cancel()
            advertisingJob?.cancel()
            nearbyManager.cancelSender()
            _hotspotModeActive.value = false
            handledBatchTransferKey = null
            _batchQueue.value = BatchTransferQueueState(apps = queueApps, currentIndex = 0)

            val firstReady = extractNextAvailableInBatch(startIndex = 0, method = TransferMethod.NEARBY)
            if (firstReady != null) {
                recordedTransferId = null
                _extractionState.value = ExtractionUiState.Ready(firstReady)
                // Do NOT start Nearby advertising here; SenderTransferScreen starts it
                // only after all runtime permissions are granted and Bluetooth + Wi-Fi are ON.
                onExtracted()
            } else if (queueApps.size > 1) {
                // Every app in the multi-app batch failed extraction; show the summary screen.
                _extractionState.value = ExtractionUiState.Idle
                _batchQueue.value = _batchQueue.value.copy(isFinished = true, isPreparingNext = false)
                onExtracted()
            }
        }
    }

    private suspend fun extractNextAvailableInBatch(
        startIndex: Int,
        method: TransferMethod
    ): ExtractedApkBundle? {
        val apps = _batchQueue.value.apps
        val isBatch = apps.size > 1
        var index = startIndex
        while (index < apps.size) {
            val app = apps[index]
            _batchQueue.value = _batchQueue.value.copy(
                currentIndex = index,
                isPreparingNext = isBatch && index > 0
            )
            _extractionState.value = ExtractionUiState.Extracting(app.appName)
            val result = apkExtractor.extractBundle(app)
            if (result.isSuccess) {
                _batchQueue.value = _batchQueue.value.copy(
                    currentIndex = index,
                    isPreparingNext = false
                )
                return result.getOrNull()
            } else {
                val errMsg = result.exceptionOrNull()?.message
                    ?: "Could not extract APK for ${app.appName}."
                if (!isBatch) {
                    _extractionState.value = ExtractionUiState.Error(errMsg)
                    return null
                }
                // In batch mode, record failure and continue with remaining apps.
                val failure = BatchTransferItemResult(
                    appName = app.appName,
                    packageName = app.packageName,
                    succeeded = false,
                    errorReason = errMsg
                )
                _batchQueue.value = _batchQueue.value.copy(
                    results = _batchQueue.value.results + failure
                )
                historyRepository.recordTransfer(
                    TransferHistoryEntity(
                        appName = app.appName,
                        packageName = app.packageName,
                        versionName = app.versionName,
                        sizeBytes = app.totalSizeBytes,
                        timestampMillis = System.currentTimeMillis(),
                        direction = TransferDirection.SENT,
                        method = method,
                        status = TransferStatus.FAILED,
                        peerDeviceName = if (method == TransferMethod.HOTSPOT_HTTP) {
                            "Wi-Fi Hotspot Client"
                        } else {
                            "Nearby Peer"
                        },
                        isSplitApk = app.isSplitApk,
                        errorReason = errMsg
                    )
                )
                index++
            }
        }
        return null
    }

    private fun recordBatchResultAndAdvanceNearby(
        result: BatchTransferItemResult,
        delayMillis: Long
    ) {
        val currentQueue = _batchQueue.value
        val updatedResults = currentQueue.results + result
        val nextIndex = currentQueue.currentIndex + 1

        if (nextIndex >= currentQueue.apps.size) {
            _batchQueue.value = currentQueue.copy(
                results = updatedResults,
                isFinished = true,
                isPreparingNext = false
            )
            advertisingJob?.cancel()
            nearbyManager.cancelSender()
            return
        }

        _batchQueue.value = currentQueue.copy(
            results = updatedResults,
            isPreparingNext = true
        )

        batchAdvanceJob?.cancel()
        batchAdvanceJob = viewModelScope.launch {
            if (delayMillis > 0L) delay(delayMillis)
            if (_hotspotModeActive.value || _batchQueue.value.isFinished) return@launch

            val nextBundle = extractNextAvailableInBatch(
                startIndex = nextIndex,
                method = TransferMethod.NEARBY
            )
            if (nextBundle != null) {
                recordedTransferId = null
                _extractionState.value = ExtractionUiState.Ready(nextBundle)
                startNearbyAdvertising()
            } else {
                advertisingJob?.cancel()
                nearbyManager.cancelSender()
                _extractionState.value = ExtractionUiState.Idle
                _batchQueue.value = _batchQueue.value.copy(
                    isFinished = true,
                    isPreparingNext = false
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

    fun prepareAndShareSelectedApps(
        apps: List<InstalledAppInfo>,
        onReadyToShare: (List<ShareableApkResult>) -> Unit,
        onError: (String?) -> Unit
    ) {
        if (apps.isEmpty()) return
        if (apps.size == 1) {
            prepareAndShareApp(
                app = apps.first(),
                onReadyToShare = { single -> onReadyToShare(listOf(single)) },
                onError = onError
            )
            return
        }

        viewModelScope.launch {
            val prepared = mutableListOf<ShareableApkResult>()
            var lastError: String? = null
            var lastBundle: ExtractedApkBundle? = null

            apps.forEachIndexed { index, app ->
                _extractionState.value = ExtractionUiState.Extracting(app.appName)
                val extractedResult = apkExtractor.extractBundle(app, cleanPrevious = (index == 0))
                extractedResult.onSuccess { bundle ->
                    lastBundle = bundle
                    val shareableResult = runCatching { ApkShareHelper.prepareShareableFile(bundle) }
                    shareableResult.onSuccess { shareable ->
                        prepared.add(shareable)
                    }.onFailure { err ->
                        lastError = err.message
                    }
                }.onFailure { err ->
                    lastError = err.message
                }
            }

            if (prepared.isNotEmpty()) {
                val bundle = lastBundle
                _extractionState.value = if (bundle != null) {
                    ExtractionUiState.Ready(bundle)
                } else {
                    ExtractionUiState.Idle
                }
                onReadyToShare(prepared)
            } else {
                _extractionState.value = ExtractionUiState.Error(lastError.orEmpty())
                onError(lastError)
            }
        }
    }

    fun saveSelectedAppsToDownloads(
        context: Context,
        apps: List<InstalledAppInfo>,
        onFinished: (savedCount: Int, totalCount: Int) -> Unit
    ) {
        if (apps.isEmpty() || _isSavingToDownloads.value) return
        viewModelScope.launch {
            _isSavingToDownloads.value = true
            var savedCount = 0
            try {
                for (app in apps) {
                    _extractionState.value = ExtractionUiState.Extracting(app.appName)
                    val extracted = apkExtractor.extractBundle(app, cleanPrevious = true).getOrNull()
                        ?: continue
                    val shareable = runCatching {
                        ApkShareHelper.prepareShareableFile(extracted)
                    }.getOrNull() ?: continue
                    val saved = ApkShareHelper.saveApkToPublicDownloads(
                        context = context,
                        sourceFile = shareable.file,
                        appName = app.appName,
                        versionName = app.versionName,
                        isSplitBundle = shareable.isSplitBundle
                    )
                    if (saved.isSuccess) {
                        savedCount++
                    }
                }
            } finally {
                _extractionState.value = ExtractionUiState.Idle
                _isSavingToDownloads.value = false
            }
            onFinished(savedCount, apps.size)
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
        batchAdvanceJob?.cancel()
        advertisingJob?.cancel()
        nearbyManager.stopAll()
        _hotspotModeActive.value = true
        httpServer.start(bundle, preferredPort = 8080)
        // LocalOnlyHotspot is started from SenderTransferScreen after hotspot permissions are verified.
    }

    fun startHotspotModeDirect(onReady: () -> Unit) {
        val queueApps = currentSelectedAppsSnapshot()
        if (queueApps.isEmpty()) return
        viewModelScope.launch {
            batchAdvanceJob?.cancel()
            advertisingJob?.cancel()
            nearbyManager.cancelSender()
            handledBatchTransferKey = null
            _batchQueue.value = BatchTransferQueueState(apps = queueApps, currentIndex = 0)

            val firstReady = extractNextAvailableInBatch(startIndex = 0, method = TransferMethod.HOTSPOT_HTTP)
            if (firstReady != null) {
                recordedTransferId = null
                _extractionState.value = ExtractionUiState.Ready(firstReady)
                _hotspotModeActive.value = true
                httpServer.start(firstReady, preferredPort = 8080)
                // LocalOnlyHotspot is started from SenderTransferScreen after hotspot permissions are verified.
                onReady()
            } else if (queueApps.size > 1) {
                _hotspotModeActive.value = true
                _extractionState.value = ExtractionUiState.Idle
                _batchQueue.value = _batchQueue.value.copy(isFinished = true, isPreparingNext = false)
                onReady()
            }
        }
    }

    fun advanceHotspotBatchQueue(markCurrentSucceeded: Boolean) {
        val queue = _batchQueue.value
        val currentBundle = (_extractionState.value as? ExtractionUiState.Ready)?.bundle
        val currentApp = queue.currentApp

        if (!queue.isBatch) {
            if (markCurrentSucceeded) {
                markHotspotTransferComplete()
            }
            return
        }

        viewModelScope.launch {
            if (markCurrentSucceeded && currentBundle != null) {
                recordHotspotHistoryNow(currentBundle, TransferStatus.SUCCESS, null)
            } else if (!markCurrentSucceeded && currentApp != null) {
                recordHotspotHistoryNow(
                    ExtractedApkBundle(
                        transferId = currentBundle?.transferId ?: "skip_${queue.currentIndex}",
                        appName = currentApp.appName,
                        packageName = currentApp.packageName,
                        versionName = currentApp.versionName,
                        versionCode = currentApp.versionCode,
                        isSplitApk = currentApp.isSplitApk,
                        files = currentBundle?.files.orEmpty(),
                        totalSizeBytes = currentApp.totalSizeBytes,
                        stagingDir = currentBundle?.stagingDir ?: java.io.File(""),
                        iconBase64 = currentBundle?.iconBase64
                    ),
                    TransferStatus.FAILED,
                    "Skipped"
                )
            }

            val itemResult = BatchTransferItemResult(
                appName = currentBundle?.appName ?: currentApp?.appName.orEmpty(),
                packageName = currentBundle?.packageName ?: currentApp?.packageName.orEmpty(),
                succeeded = markCurrentSucceeded,
                errorReason = if (markCurrentSucceeded) null else "Skipped"
            )
            val updatedResults = if (itemResult.appName.isNotBlank()) {
                queue.results + itemResult
            } else {
                queue.results
            }
            val nextIndex = queue.currentIndex + 1

            if (nextIndex >= queue.apps.size) {
                httpServer.stop()
                hotspotController.stopLocalHotspot()
                _batchQueue.value = queue.copy(
                    results = updatedResults,
                    isFinished = true,
                    isPreparingNext = false
                )
                return@launch
            }

            _batchQueue.value = queue.copy(
                results = updatedResults,
                isPreparingNext = true
            )

            // Keep LocalOnlyHotspot alive while switching the served bundle on port 8080.
            val nextBundle = extractNextAvailableInBatch(
                startIndex = nextIndex,
                method = TransferMethod.HOTSPOT_HTTP
            )
            if (nextBundle != null) {
                recordedTransferId = null
                _extractionState.value = ExtractionUiState.Ready(nextBundle)
                httpServer.start(nextBundle, preferredPort = 8080)
            } else {
                httpServer.stop()
                hotspotController.stopLocalHotspot()
                _extractionState.value = ExtractionUiState.Idle
                _batchQueue.value = _batchQueue.value.copy(
                    isFinished = true,
                    isPreparingNext = false
                )
            }
        }
    }

    fun skipCurrentBatchItem() {
        val queue = _batchQueue.value
        if (!queue.isBatch || queue.isFinished) return
        if (_hotspotModeActive.value) {
            advanceHotspotBatchQueue(markCurrentSucceeded = false)
        } else {
            val currentApp = queue.currentApp ?: return
            handledBatchTransferKey = "skip_${queue.currentIndex}"
            advertisingJob?.cancel()
            nearbyManager.cancelSender()
            recordBatchResultAndAdvanceNearby(
                BatchTransferItemResult(
                    appName = currentApp.appName,
                    packageName = currentApp.packageName,
                    succeeded = false,
                    errorReason = "Skipped"
                ),
                delayMillis = 0L
            )
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
            recordHotspotHistoryNow(bundle, TransferStatus.SUCCESS, null)
        }
    }

    private suspend fun recordHotspotHistoryNow(
        bundle: ExtractedApkBundle,
        status: TransferStatus,
        errorReason: String?
    ) {
        val key = "${bundle.transferId}_http_${status.name}"
        if (recordedTransferId != key) {
            recordedTransferId = key
            historyRepository.recordTransfer(
                TransferHistoryEntity(
                    appName = bundle.appName,
                    packageName = bundle.packageName,
                    versionName = bundle.versionName,
                    sizeBytes = bundle.totalSizeBytes,
                    timestampMillis = System.currentTimeMillis(),
                    direction = TransferDirection.SENT,
                    method = TransferMethod.HOTSPOT_HTTP,
                    status = status,
                    peerDeviceName = "Wi-Fi Hotspot Client",
                    isSplitApk = bundle.isSplitApk,
                    errorReason = errorReason
                )
            )
        }
    }

    fun cancelActiveTransfer() {
        batchAdvanceJob?.cancel()
        advertisingJob?.cancel()
        nearbyManager.cancelSender()
        httpServer.stop()
        hotspotController.stopLocalHotspot()
        _hotspotModeActive.value = false
        handledBatchTransferKey = null
        _batchQueue.value = BatchTransferQueueState()
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
