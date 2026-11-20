package com.example.ui.viewmodel

import android.content.Context
import android.net.Uri
import androidx.compose.ui.graphics.ImageBitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.R
import com.example.apk.ApkExtractor
import com.example.apk.InstalledAppsRepository
import com.example.data.db.TransferHistoryEntity
import com.example.data.db.TransferHistoryRepository
import com.example.data.prefs.SettingsDataStore
import com.example.model.ExtractedApkBundle
import com.example.model.InstalledAppInfo
import com.example.model.PickedFileInfo
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
import com.example.transfer.PcShareItem
import com.example.util.ApkShareHelper
import com.example.util.FileTransferHelper
import com.example.util.LocaleHelper
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

enum class PickerTab {
    APPS,
    FILES
}

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
    val files: List<PickedFileInfo> = emptyList(),
    val currentIndex: Int = 0,
    val results: List<BatchTransferItemResult> = emptyList(),
    val isFinished: Boolean = false,
    val isPreparingNext: Boolean = false,
    val isPaused: Boolean = false,
    val isCancelled: Boolean = false
) {
    val isFileBatch: Boolean get() = files.isNotEmpty()
    val totalCount: Int get() = if (isFileBatch) files.size else apps.size
    val isBatch: Boolean get() = totalCount > 1
    val currentApp: InstalledAppInfo? get() = apps.getOrNull(currentIndex)
    val currentFile: PickedFileInfo? get() = files.getOrNull(currentIndex)
    val currentDisplayName: String
        get() = if (isFileBatch) {
            currentFile?.displayName.orEmpty()
        } else {
            currentApp?.appName.orEmpty()
        }
    val currentNumber: Int get() = (currentIndex + 1).coerceAtMost(totalCount.coerceAtLeast(1))
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

    private val _activeTab = MutableStateFlow(PickerTab.APPS)
    val activeTab: StateFlow<PickerTab> = _activeTab.asStateFlow()

    private val _pickedFiles = MutableStateFlow<List<PickedFileInfo>>(emptyList())
    val pickedFiles: StateFlow<List<PickedFileInfo>> = _pickedFiles.asStateFlow()

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

    private val _pcShareModeActive = MutableStateFlow(false)
    val pcShareModeActive: StateFlow<Boolean> = _pcShareModeActive.asStateFlow()

    private val _pcShareItems = MutableStateFlow<List<PcShareItem>>(emptyList())
    val pcShareItems: StateFlow<List<PcShareItem>> = _pcShareItems.asStateFlow()

    private val _batchQueue = MutableStateFlow(BatchTransferQueueState())
    val batchQueue: StateFlow<BatchTransferQueueState> = _batchQueue.asStateFlow()

    private val _isTransferPaused = MutableStateFlow(false)
    val isTransferPaused: StateFlow<Boolean> = _isTransferPaused.asStateFlow()

    val nearbyState: StateFlow<NearbySenderState> = nearbyManager.senderState
    val hotspotInfo: StateFlow<HotspotInfo> = hotspotController.hotspotInfo
    val httpServerStatus: StateFlow<HttpServerStatus> = httpServer.status

    private var recordedTransferId: String? = null
    private var handledBatchTransferKey: String? = null
    private var advertisingJob: Job? = null
    private var batchAdvanceJob: Job? = null
    private var activeFileAppContext: Context? = null

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
                    is NearbySenderState.Sending -> {
                        if (_isTransferPaused.value != state.isPaused) {
                            _isTransferPaused.value = state.isPaused
                        }
                    }
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
                                    durationMillis = state.durationMillis,
                                    isGenericFile = state.bundle.isGenericFile,
                                    mimeType = state.bundle.mimeType,
                                    savedFilePath = state.bundle.files.firstOrNull()?.absolutePath
                                )
                            )
                        }
                        val queue = _batchQueue.value
                        val batchKey = "ok_${state.bundle.transferId}"
                        if (queue.isBatch && !queue.isFinished && !queue.isCancelled && !_hotspotModeActive.value &&
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
                    is NearbySenderState.Cancelled -> {
                        batchAdvanceJob?.cancel()
                        advertisingJob?.cancel()
                        _isTransferPaused.value = false
                        val b = state.bundle
                        if (b != null && recordedTransferId != "${b.transferId}_cancel") {
                            recordedTransferId = "${b.transferId}_cancel"
                            historyRepository.recordTransfer(
                                TransferHistoryEntity(
                                    appName = b.appName,
                                    packageName = b.packageName,
                                    versionName = b.versionName,
                                    sizeBytes = b.totalSizeBytes,
                                    timestampMillis = System.currentTimeMillis(),
                                    direction = TransferDirection.SENT,
                                    method = TransferMethod.NEARBY,
                                    status = TransferStatus.CANCELLED,
                                    peerDeviceName = state.peerName ?: "Nearby Peer",
                                    isSplitApk = b.isSplitApk,
                                    isGenericFile = b.isGenericFile,
                                    mimeType = b.mimeType
                                )
                            )
                        }
                        if (_batchQueue.value.isBatch) {
                            _batchQueue.value = _batchQueue.value.copy(
                                isPaused = false,
                                isCancelled = true,
                                isPreparingNext = false
                            )
                        }
                    }
                    is NearbySenderState.Error -> {
                        val b = state.bundle
                        val queue = _batchQueue.value
                        if (state.wasInterruptedMidTransfer) {
                            // Pause batch queue at the interrupted item so sender can Retry/Resume
                            // without re-sending earlier completed items or skipping this one.
                            batchAdvanceJob?.cancel()
                            advertisingJob?.cancel()
                            _batchQueue.value = queue.copy(isPreparingNext = false)
                        } else {
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
                                        errorReason = state.reason,
                                        isGenericFile = b.isGenericFile,
                                        mimeType = b.mimeType
                                    )
                                )
                            }
                            val batchKey = "err_${b?.transferId ?: queue.currentIndex}"
                            if (b != null && queue.isBatch && !queue.isFinished && !queue.isCancelled && !_hotspotModeActive.value &&
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

    fun selectTab(tab: PickerTab) {
        _activeTab.value = tab
    }

    fun addPickedFiles(context: Context, uris: List<Uri>) {
        if (uris.isEmpty()) return
        val appContext = context.applicationContext
        viewModelScope.launch {
            val resolved = FileTransferHelper.resolvePickedFiles(appContext, uris)
            if (resolved.isNotEmpty()) {
                addPickedFileItems(resolved)
            }
        }
    }

    fun addPickedFileItems(items: List<PickedFileInfo>) {
        if (items.isEmpty()) return
        val existing = _pickedFiles.value
        val existingUris = existing.mapTo(HashSet()) { it.uriString }
        val merged = existing + items.filter { existingUris.add(it.uriString) }
        _pickedFiles.value = merged
    }

    fun removePickedFile(uriString: String) {
        _pickedFiles.value = _pickedFiles.value.filterNot { it.uriString == uriString }
    }

    fun clearPickedFiles() {
        _pickedFiles.value = emptyList()
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
            _isTransferPaused.value = false
            handledBatchTransferKey = null
            activeFileAppContext = null
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

    fun prepareAndStartNearbyFileTransfer(context: Context, onExtracted: () -> Unit) {
        val queueFiles = _pickedFiles.value
        if (queueFiles.isEmpty()) return
        activeFileAppContext = context.applicationContext
        viewModelScope.launch {
            batchAdvanceJob?.cancel()
            advertisingJob?.cancel()
            nearbyManager.cancelSender()
            _hotspotModeActive.value = false
            _isTransferPaused.value = false
            handledBatchTransferKey = null
            _batchQueue.value = BatchTransferQueueState(files = queueFiles, currentIndex = 0)

            val firstReady = extractNextAvailableInBatch(startIndex = 0, method = TransferMethod.NEARBY)
            if (firstReady != null) {
                recordedTransferId = null
                _extractionState.value = ExtractionUiState.Ready(firstReady)
                onExtracted()
            } else if (queueFiles.size > 1) {
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
        val queue = _batchQueue.value
        if (queue.isFileBatch) {
            return stageNextAvailableFileInBatch(startIndex, method)
        }

        val apps = queue.apps
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

    private suspend fun stageNextAvailableFileInBatch(
        startIndex: Int,
        method: TransferMethod
    ): ExtractedApkBundle? {
        val context = activeFileAppContext ?: return null
        val files = _batchQueue.value.files
        val isBatch = files.size > 1
        var index = startIndex
        while (index < files.size) {
            val fileInfo = files[index]
            _batchQueue.value = _batchQueue.value.copy(
                currentIndex = index,
                isPreparingNext = isBatch && index > 0
            )
            _extractionState.value = ExtractionUiState.Extracting(fileInfo.displayName)
            val result = FileTransferHelper.stagePickedFile(
                context = context,
                pickedFile = fileInfo,
                cleanPrevious = true
            )
            if (result.isSuccess) {
                _batchQueue.value = _batchQueue.value.copy(
                    currentIndex = index,
                    isPreparingNext = false
                )
                return result.getOrNull()
            } else {
                val errMsg = result.exceptionOrNull()?.message
                    ?: "Could not read ${fileInfo.displayName}."
                if (!isBatch) {
                    _extractionState.value = ExtractionUiState.Error(errMsg)
                    return null
                }
                val failure = BatchTransferItemResult(
                    appName = fileInfo.displayName,
                    packageName = "",
                    succeeded = false,
                    errorReason = errMsg
                )
                _batchQueue.value = _batchQueue.value.copy(
                    results = _batchQueue.value.results + failure
                )
                historyRepository.recordTransfer(
                    TransferHistoryEntity(
                        appName = fileInfo.displayName,
                        packageName = "",
                        versionName = "",
                        sizeBytes = fileInfo.sizeBytes,
                        timestampMillis = System.currentTimeMillis(),
                        direction = TransferDirection.SENT,
                        method = method,
                        status = TransferStatus.FAILED,
                        peerDeviceName = if (method == TransferMethod.HOTSPOT_HTTP) {
                            "Wi-Fi Hotspot Client"
                        } else {
                            "Nearby Peer"
                        },
                        isSplitApk = false,
                        errorReason = errMsg,
                        isGenericFile = true,
                        mimeType = fileInfo.mimeType
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

        if (nextIndex >= currentQueue.totalCount) {
            _isTransferPaused.value = false
            _batchQueue.value = currentQueue.copy(
                results = updatedResults,
                isFinished = true,
                isPreparingNext = false,
                isPaused = false
            )
            advertisingJob?.cancel()
            nearbyManager.cancelSender()
            return
        }

        if (_isTransferPaused.value) {
            // Queue is paused: current file finished cleanly, now wait before starting next item.
            batchAdvanceJob?.cancel()
            advertisingJob?.cancel()
            nearbyManager.cancelSender()
            _batchQueue.value = currentQueue.copy(
                currentIndex = nextIndex,
                results = updatedResults,
                isPaused = true,
                isPreparingNext = false
            )
            return
        }

        _batchQueue.value = currentQueue.copy(
            results = updatedResults,
            isPreparingNext = true,
            isPaused = false
        )

        batchAdvanceJob?.cancel()
        batchAdvanceJob = viewModelScope.launch {
            if (delayMillis > 0L) delay(delayMillis)
            if (_hotspotModeActive.value || _batchQueue.value.isFinished || _batchQueue.value.isCancelled) {
                return@launch
            }
            if (_isTransferPaused.value) {
                advertisingJob?.cancel()
                nearbyManager.cancelSender()
                _batchQueue.value = _batchQueue.value.copy(
                    currentIndex = nextIndex,
                    isPaused = true,
                    isPreparingNext = false
                )
                return@launch
            }

            val nextBundle = extractNextAvailableInBatch(
                startIndex = nextIndex,
                method = TransferMethod.NEARBY
            )
            if (nextBundle != null) {
                recordedTransferId = null
                _extractionState.value = ExtractionUiState.Ready(nextBundle)
                if (_isTransferPaused.value) {
                    _batchQueue.value = _batchQueue.value.copy(
                        isPaused = true,
                        isPreparingNext = false
                    )
                } else {
                    startNearbyAdvertising()
                }
            } else {
                advertisingJob?.cancel()
                nearbyManager.cancelSender()
                _extractionState.value = ExtractionUiState.Idle
                _isTransferPaused.value = false
                _batchQueue.value = _batchQueue.value.copy(
                    isFinished = true,
                    isPreparingNext = false,
                    isPaused = false
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

    fun prepareAndSharePickedFiles(
        context: Context,
        files: List<PickedFileInfo>,
        onReadyToShare: (List<ShareableApkResult>) -> Unit,
        onError: (String?) -> Unit
    ) {
        if (files.isEmpty()) return
        val appContext = context.applicationContext
        viewModelScope.launch {
            val prepared = mutableListOf<ShareableApkResult>()
            var lastError: String? = null
            var lastBundle: ExtractedApkBundle? = null

            files.forEachIndexed { index, fileInfo ->
                _extractionState.value = ExtractionUiState.Extracting(fileInfo.displayName)
                val staged = FileTransferHelper.stagePickedFile(
                    context = appContext,
                    pickedFile = fileInfo,
                    cleanPrevious = (index == 0)
                )
                staged.onSuccess { bundle ->
                    val stagedFile = bundle.files.firstOrNull()
                    if (stagedFile != null && stagedFile.exists()) {
                        lastBundle = bundle
                        prepared.add(
                            ShareableApkResult(
                                file = stagedFile,
                                appName = fileInfo.displayName,
                                isSplitBundle = false,
                                mimeType = bundle.mimeType ?: fileInfo.mimeType
                            )
                        )
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

    fun savePickedFilesToDownloads(
        context: Context,
        files: List<PickedFileInfo>,
        onFinished: (savedCount: Int, totalCount: Int) -> Unit
    ) {
        if (files.isEmpty() || _isSavingToDownloads.value) return
        val appContext = context.applicationContext
        viewModelScope.launch {
            _isSavingToDownloads.value = true
            var savedCount = 0
            try {
                for (fileInfo in files) {
                    _extractionState.value = ExtractionUiState.Extracting(fileInfo.displayName)
                    val staged = FileTransferHelper.stagePickedFile(
                        context = appContext,
                        pickedFile = fileInfo,
                        cleanPrevious = true
                    ).getOrNull() ?: continue
                    val stagedFile = staged.files.firstOrNull() ?: continue
                    val saved = FileTransferHelper.saveGenericFileToPublicDownloads(
                        context = appContext,
                        sourceFile = stagedFile,
                        displayName = fileInfo.displayName,
                        mimeType = staged.mimeType ?: fileInfo.mimeType
                    )
                    if (saved.isSuccess) {
                        savedCount++
                    }
                }
            } finally {
                _extractionState.value = ExtractionUiState.Idle
                _isSavingToDownloads.value = false
            }
            onFinished(savedCount, files.size)
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
        handledBatchTransferKey = null
        recordedTransferId = null
        startNearbyAdvertising()
    }

    fun pauseNearbyTransfer() {
        _isTransferPaused.value = true
        nearbyManager.setSenderPaused(true)
        val queue = _batchQueue.value
        if (queue.isBatch && !queue.isFinished && nearbyState.value !is NearbySenderState.Sending) {
            batchAdvanceJob?.cancel()
            advertisingJob?.cancel()
            nearbyManager.cancelSender()
            _batchQueue.value = queue.copy(isPaused = true, isPreparingNext = false)
        }
    }

    fun resumeNearbyTransfer() {
        _isTransferPaused.value = false
        nearbyManager.setSenderPaused(false)
        val queue = _batchQueue.value
        if (queue.isBatch && queue.isPaused && !queue.isFinished && nearbyState.value !is NearbySenderState.Sending) {
            val resumeIndex = queue.currentIndex
            _batchQueue.value = queue.copy(isPaused = false, isPreparingNext = true)
            batchAdvanceJob?.cancel()
            batchAdvanceJob = viewModelScope.launch {
                val nextBundle = extractNextAvailableInBatch(
                    startIndex = resumeIndex,
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
                        isPaused = false,
                        isPreparingNext = false
                    )
                }
            }
        }
    }

    fun cancelOngoingNearbyTransfer() {
        batchAdvanceJob?.cancel()
        advertisingJob?.cancel()
        _isTransferPaused.value = false
        if (_batchQueue.value.isBatch) {
            _batchQueue.value = _batchQueue.value.copy(
                isPaused = false,
                isCancelled = true,
                isPreparingNext = false
            )
        }
        nearbyManager.cancelActiveTransferBySender()
    }

    fun retryCancelledNearbyTransfer() {
        _isTransferPaused.value = false
        handledBatchTransferKey = null
        recordedTransferId = null
        val queue = _batchQueue.value
        if (queue.isBatch) {
            val startIdx = queue.currentIndex.coerceIn(0, (queue.totalCount - 1).coerceAtLeast(0))
            _batchQueue.value = queue.copy(
                isCancelled = false,
                isPaused = false,
                isFinished = false,
                isPreparingNext = true
            )
            batchAdvanceJob?.cancel()
            batchAdvanceJob = viewModelScope.launch {
                val bundle = extractNextAvailableInBatch(
                    startIndex = startIdx,
                    method = TransferMethod.NEARBY
                )
                if (bundle != null) {
                    _extractionState.value = ExtractionUiState.Ready(bundle)
                    startNearbyAdvertising()
                } else {
                    _batchQueue.value = _batchQueue.value.copy(
                        isFinished = true,
                        isPreparingNext = false
                    )
                }
            }
        } else {
            startNearbyAdvertising()
        }
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
            activeFileAppContext = null
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

    fun startHotspotFileModeDirect(context: Context, onReady: () -> Unit) {
        val queueFiles = _pickedFiles.value
        if (queueFiles.isEmpty()) return
        activeFileAppContext = context.applicationContext
        viewModelScope.launch {
            batchAdvanceJob?.cancel()
            advertisingJob?.cancel()
            nearbyManager.cancelSender()
            handledBatchTransferKey = null
            _batchQueue.value = BatchTransferQueueState(files = queueFiles, currentIndex = 0)

            val firstReady = extractNextAvailableInBatch(startIndex = 0, method = TransferMethod.HOTSPOT_HTTP)
            if (firstReady != null) {
                recordedTransferId = null
                _extractionState.value = ExtractionUiState.Ready(firstReady)
                _hotspotModeActive.value = true
                httpServer.start(firstReady, preferredPort = 8080)
                onReady()
            } else if (queueFiles.size > 1) {
                _hotspotModeActive.value = true
                _extractionState.value = ExtractionUiState.Idle
                _batchQueue.value = _batchQueue.value.copy(isFinished = true, isPreparingNext = false)
                onReady()
            }
        }
    }

    fun startPcShareModeDirect(context: Context, onReady: () -> Unit) {
        val queueApps = currentSelectedAppsSnapshot()
        val queueFiles = _pickedFiles.value
        if (queueApps.isEmpty() && queueFiles.isEmpty()) return

        val appContext = context.applicationContext
        val loc = LocaleHelper.localizedContext(context)
        viewModelScope.launch {
            batchAdvanceJob?.cancel()
            advertisingJob?.cancel()
            nearbyManager.cancelSender()
            httpServer.stop()
            hotspotController.stopLocalHotspot()
            _hotspotModeActive.value = false
            _pcShareModeActive.value = false
            _pcShareItems.value = emptyList()
            _isTransferPaused.value = false
            handledBatchTransferKey = null
            activeFileAppContext = null
            _batchQueue.value = BatchTransferQueueState()

            val stagedItems = mutableListOf<PcShareItem>()
            var cleanedOnce = false

            for (app in queueApps) {
                _extractionState.value = ExtractionUiState.Extracting(app.appName)
                val extractedResult = apkExtractor.extractBundle(app, cleanPrevious = !cleanedOnce)
                val bundle = extractedResult.getOrNull() ?: continue
                val shareable = runCatching { ApkShareHelper.prepareShareableFile(bundle) }.getOrNull()
                if (shareable != null && shareable.file.exists()) {
                    cleanedOnce = true
                    val subtitle = if (shareable.isSplitBundle) {
                        loc.getString(R.string.pc_share_split_apk_label, app.versionName)
                    } else {
                        loc.getString(R.string.pc_share_single_apk_label, app.versionName)
                    }
                    stagedItems += PcShareItem(
                        displayName = app.appName,
                        subtitle = subtitle,
                        file = shareable.file,
                        sizeBytes = shareable.file.length(),
                        mimeType = ApkShareHelper.mimeTypeFor(shareable.isSplitBundle),
                        isGenericFile = false,
                        isSplitBundle = shareable.isSplitBundle
                    )
                }
            }

            for (fileInfo in queueFiles) {
                _extractionState.value = ExtractionUiState.Extracting(fileInfo.displayName)
                val stagedResult = FileTransferHelper.stagePickedFile(
                    context = appContext,
                    pickedFile = fileInfo,
                    cleanPrevious = !cleanedOnce
                )
                val bundle = stagedResult.getOrNull() ?: continue
                val file = bundle.files.firstOrNull()
                if (file != null && file.exists()) {
                    cleanedOnce = true
                    val resolvedMime = bundle.mimeType ?: fileInfo.mimeType
                    stagedItems += PcShareItem(
                        displayName = fileInfo.displayName,
                        subtitle = resolvedMime,
                        file = file,
                        sizeBytes = file.length(),
                        mimeType = resolvedMime,
                        isGenericFile = true,
                        isSplitBundle = false
                    )
                }
            }

            if (stagedItems.isEmpty()) {
                _extractionState.value = ExtractionUiState.Error(
                    loc.getString(R.string.pc_share_error_none_staged)
                )
                return@launch
            }

            httpServer.startPcShare(
                items = stagedItems,
                pageSubtitle = loc.getString(R.string.pc_share_web_page_subtitle),
                downloadLabel = loc.getString(R.string.pc_share_web_download),
                safetyNote = loc.getString(R.string.pc_share_safety_note),
                preferredPort = 8080
            )
            _pcShareItems.value = stagedItems
            _pcShareModeActive.value = true
            _extractionState.value = ExtractionUiState.Idle
            onReady()
        }
    }

    fun stopPcShare() {
        httpServer.stop()
        hotspotController.stopLocalHotspot()
        _pcShareModeActive.value = false
        _pcShareItems.value = emptyList()
    }

    fun advanceHotspotBatchQueue(markCurrentSucceeded: Boolean) {
        val queue = _batchQueue.value
        val currentBundle = (_extractionState.value as? ExtractionUiState.Ready)?.bundle
        val currentApp = queue.currentApp
        val currentFile = queue.currentFile

        if (!queue.isBatch) {
            if (markCurrentSucceeded) {
                markHotspotTransferComplete()
            }
            return
        }

        viewModelScope.launch {
            if (markCurrentSucceeded && currentBundle != null) {
                recordHotspotHistoryNow(currentBundle, TransferStatus.SUCCESS, null)
            } else if (!markCurrentSucceeded) {
                if (queue.isFileBatch && currentFile != null) {
                    recordHotspotHistoryNow(
                        ExtractedApkBundle(
                            transferId = currentBundle?.transferId ?: "skip_f_${queue.currentIndex}",
                            appName = currentFile.displayName,
                            packageName = "",
                            versionName = "",
                            versionCode = 0L,
                            isSplitApk = false,
                            files = currentBundle?.files.orEmpty(),
                            totalSizeBytes = currentFile.sizeBytes,
                            stagingDir = currentBundle?.stagingDir ?: java.io.File(""),
                            iconBase64 = null,
                            isGenericFile = true,
                            mimeType = currentFile.mimeType
                        ),
                        TransferStatus.FAILED,
                        "Skipped"
                    )
                } else if (currentApp != null) {
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
            }

            val displayName = currentBundle?.appName ?: queue.currentDisplayName
            val itemResult = BatchTransferItemResult(
                appName = displayName,
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

            if (nextIndex >= queue.totalCount) {
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
            val displayName = queue.currentDisplayName.takeIf { it.isNotBlank() } ?: return
            val pkg = queue.currentApp?.packageName.orEmpty()
            handledBatchTransferKey = "skip_${queue.currentIndex}"
            advertisingJob?.cancel()
            nearbyManager.cancelSender()
            recordBatchResultAndAdvanceNearby(
                BatchTransferItemResult(
                    appName = displayName,
                    packageName = pkg,
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
                    errorReason = errorReason,
                    isGenericFile = bundle.isGenericFile,
                    mimeType = bundle.mimeType,
                    savedFilePath = bundle.files.firstOrNull()?.absolutePath
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
        _pcShareModeActive.value = false
        _pcShareItems.value = emptyList()
        _isTransferPaused.value = false
        handledBatchTransferKey = null
        activeFileAppContext = null
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
