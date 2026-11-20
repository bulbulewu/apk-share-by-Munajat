package com.example.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.data.db.TransferHistoryEntity
import com.example.data.db.TransferHistoryRepository
import com.example.data.prefs.SettingsDataStore
import com.example.installer.ApkInstaller
import com.example.installer.InstallResultBus
import com.example.installer.InstallState
import com.example.model.ApkBundleMetadata
import com.example.model.DiscoveredSenderDevice
import com.example.model.TransferDirection
import com.example.model.TransferMethod
import com.example.model.TransferStatus
import com.example.service.TransferForegroundService
import com.example.transfer.HttpApkDownloader
import com.example.transfer.HttpDownloadState
import com.example.transfer.InterruptedTransferRecord
import com.example.transfer.InterruptedTransferStore
import com.example.transfer.NearbyReceiverState
import com.example.transfer.NearbyTransferManager
import com.example.util.QrCodeUtils
import com.example.util.ScannedQrResult
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.io.File

enum class ReceiverMode {
    NEARBY,
    HOTSPOT_HTTP
}

data class ReceivedAppItem(
    val metadata: ApkBundleMetadata,
    val savedFiles: List<File>,
    val displayLocation: String,
    val durationMillis: Long
)

class ReceiverViewModel(
    private val nearbyManager: NearbyTransferManager,
    private val httpDownloader: HttpApkDownloader,
    private val apkInstaller: ApkInstaller,
    private val settingsDataStore: SettingsDataStore,
    private val historyRepository: TransferHistoryRepository
) : ViewModel() {

    private val _mode = MutableStateFlow(ReceiverMode.NEARBY)
    val mode: StateFlow<ReceiverMode> = _mode.asStateFlow()

    private val _canRequestInstalls = MutableStateFlow(apkInstaller.canRequestPackageInstalls())
    val canRequestInstalls: StateFlow<Boolean> = _canRequestInstalls.asStateFlow()

    private val _receivedApps = MutableStateFlow<List<ReceivedAppItem>>(emptyList())
    val receivedApps: StateFlow<List<ReceivedAppItem>> = _receivedApps.asStateFlow()

    val nearbyState: StateFlow<NearbyReceiverState> = nearbyManager.receiverState
    val httpDownloadState: StateFlow<HttpDownloadState> = httpDownloader.state
    val installState: StateFlow<InstallState> = InstallResultBus.state

    private var recordedTransferKeys = mutableSetOf<String>()
    private var lastHotspotUrl: String? = null
    private var discoveryJob: Job? = null

    init {
        restoreSavedInterruptedTransferIfAny()
        observeTransfersForHistory()
        observeNotificationCancellation()
    }

    private fun restoreSavedInterruptedTransferIfAny() {
        val currentNearby = nearbyManager.receiverState.value
        val currentHttp = httpDownloader.state.value
        if (currentNearby !is NearbyReceiverState.Idle || currentHttp !is HttpDownloadState.Idle) {
            return
        }
        val saved = nearbyManager.resumeStore.loadValid() ?: return
        if (saved.mode == InterruptedTransferStore.MODE_HOTSPOT) {
            _mode.value = ReceiverMode.HOTSPOT_HTTP
            lastHotspotUrl = saved.hotspotUrl
            httpDownloader.restoreInterruptedState(saved)
        } else {
            _mode.value = ReceiverMode.NEARBY
            nearbyManager.restoreInterruptedState(saved)
        }
    }

    private fun observeTransfersForHistory() {
        viewModelScope.launch {
            nearbyState.collect { state ->
                when (state) {
                    is NearbyReceiverState.Completed -> {
                        appendReceivedItem(
                            ReceivedAppItem(
                                metadata = state.metadata,
                                savedFiles = state.savedFiles,
                                displayLocation = state.displayLocation,
                                durationMillis = state.durationMillis
                            )
                        )
                        val key = "nearby_${state.metadata.transferId}"
                        if (recordedTransferKeys.add(key)) {
                            historyRepository.recordTransfer(
                                TransferHistoryEntity(
                                    appName = state.metadata.appName,
                                    packageName = state.metadata.packageName,
                                    versionName = state.metadata.versionName,
                                    sizeBytes = state.metadata.totalSizeBytes,
                                    timestampMillis = System.currentTimeMillis(),
                                    direction = TransferDirection.RECEIVED,
                                    method = TransferMethod.NEARBY,
                                    status = TransferStatus.SUCCESS,
                                    peerDeviceName = state.peerName,
                                    isSplitApk = state.metadata.isSplitApk,
                                    durationMillis = state.durationMillis,
                                    isGenericFile = state.metadata.isGenericFile,
                                    mimeType = state.metadata.mimeType,
                                    savedFilePath = state.savedFiles.firstOrNull()?.absolutePath
                                )
                            )
                        }
                    }
                    else -> Unit
                }
            }
        }
        viewModelScope.launch {
            httpDownloadState.collect { state ->
                when (state) {
                    is HttpDownloadState.Completed -> {
                        lastHotspotUrl = state.url
                        appendReceivedItem(
                            ReceivedAppItem(
                                metadata = state.metadata,
                                savedFiles = state.savedFiles,
                                displayLocation = state.displayLocation,
                                durationMillis = state.durationMillis
                            )
                        )
                        val key = "http_${state.metadata.transferId}"
                        if (recordedTransferKeys.add(key)) {
                            historyRepository.recordTransfer(
                                TransferHistoryEntity(
                                    appName = state.metadata.appName,
                                    packageName = state.metadata.packageName,
                                    versionName = state.metadata.versionName,
                                    sizeBytes = state.metadata.totalSizeBytes,
                                    timestampMillis = System.currentTimeMillis(),
                                    direction = TransferDirection.RECEIVED,
                                    method = TransferMethod.HOTSPOT_HTTP,
                                    status = TransferStatus.SUCCESS,
                                    peerDeviceName = "Wi-Fi Hotspot",
                                    isSplitApk = state.metadata.isSplitApk,
                                    durationMillis = state.durationMillis,
                                    isGenericFile = state.metadata.isGenericFile,
                                    mimeType = state.metadata.mimeType,
                                    savedFilePath = state.savedFiles.firstOrNull()?.absolutePath
                                )
                            )
                        }
                    }
                    else -> Unit
                }
            }
        }
    }

    private fun appendReceivedItem(item: ReceivedAppItem) {
        val current = _receivedApps.value
        if (current.none { it.metadata.transferId == item.metadata.transferId }) {
            _receivedApps.value = current + item
        }
    }

    private fun observeNotificationCancellation() {
        viewModelScope.launch {
            TransferForegroundService.cancelRequests.collect {
                cancelAll()
            }
        }
    }

    fun startNearbyDiscovery() {
        _mode.value = ReceiverMode.NEARBY
        httpDownloader.reset()
        InstallResultBus.reset()
        discoveryJob?.cancel()
        discoveryJob = viewModelScope.launch {
            val exportPublic = settingsDataStore.settings.first().exportToPublicDownloads
            nearbyManager.startDiscovery(exportPublic)
        }
    }

    fun connectToSender(device: DiscoveredSenderDevice) {
        viewModelScope.launch {
            val localName = settingsDataStore.settings.first().deviceName
            nearbyManager.connectToSender(localName, device)
        }
    }

    fun handleScannedQrInNearby(rawQr: String): Boolean {
        return when (val parsed = QrCodeUtils.parseQrScanResult(rawQr)) {
            is ScannedQrResult.NearbyPair -> {
                viewModelScope.launch {
                    val localName = settingsDataStore.settings.first().deviceName
                    nearbyManager.setQrPairTarget(localName, parsed.code, parsed.deviceName)
                }
                true
            }
            is ScannedQrResult.HotspotUrl -> {
                switchToHotspotMode()
                startHttpDownload(parsed.url)
                true
            }
            is ScannedQrResult.Unknown -> false
        }
    }

    fun clearQrPairTarget() {
        nearbyManager.clearQrPairTarget()
    }

    fun acceptIncomingOffer() {
        nearbyManager.acceptIncomingOffer()
    }

    fun declineIncomingOffer() {
        nearbyManager.declineIncomingOffer()
    }

    fun pauseNearbyTransfer() {
        nearbyManager.setReceiverPaused(true)
    }

    fun resumeNearbyTransfer() {
        nearbyManager.setReceiverPaused(false)
    }

    fun cancelOngoingNearbyTransfer() {
        discoveryJob?.cancel()
        nearbyManager.cancelActiveTransferByReceiver()
    }

    fun retryCancelledNearbyTransfer() {
        startNearbyDiscovery()
    }

    fun switchToHotspotMode() {
        discoveryJob?.cancel()
        nearbyManager.cancelReceiver()
        InstallResultBus.reset()
        _mode.value = ReceiverMode.HOTSPOT_HTTP
    }

    fun switchToNearbyMode() {
        discoveryJob?.cancel()
        nearbyManager.cancelReceiver()
        httpDownloader.reset()
        InstallResultBus.reset()
        // ReceiverScreen triggers startNearbyDiscovery() only once permissions and Bluetooth + Wi-Fi are ready.
        _mode.value = ReceiverMode.NEARBY
    }

    fun startHttpDownload(url: String) {
        _mode.value = ReceiverMode.HOTSPOT_HTTP
        lastHotspotUrl = url
        InstallResultBus.reset()
        viewModelScope.launch {
            val exportPublic = settingsDataStore.settings.first().exportToPublicDownloads
            httpDownloader.startDownload(url, exportPublic)
        }
    }

    fun cancelOngoingHotspotDownload() {
        httpDownloader.cancelActiveDownload()
    }

    fun retryCancelledHotspotDownload(url: String) {
        val target = url.ifBlank { lastHotspotUrl.orEmpty() }
        if (target.isNotBlank()) {
            startHttpDownload(target)
        } else {
            httpDownloader.reset()
        }
    }

    fun resumeInterruptedNearbyTransfer(record: InterruptedTransferRecord) {
        _mode.value = ReceiverMode.NEARBY
        httpDownloader.stopPreservingPartial()
        InstallResultBus.reset()
        discoveryJob?.cancel()
        discoveryJob = viewModelScope.launch {
            val settings = settingsDataStore.settings.first()
            nearbyManager.startResumeDiscovery(
                record = record,
                localDeviceName = settings.deviceName,
                exportToPublicDownloads = settings.exportToPublicDownloads
            )
        }
    }

    fun resumeInterruptedHotspotTransfer(record: InterruptedTransferRecord) {
        val target = record.hotspotUrl?.takeIf { it.isNotBlank() } ?: lastHotspotUrl.orEmpty()
        if (target.isNotBlank()) {
            startHttpDownload(target)
        }
    }

    fun clearInterruptedTransfer() {
        discoveryJob?.cancel()
        nearbyManager.clearInterruptedTransfer()
        httpDownloader.clearInterruptedTransfer()
        InstallResultBus.reset()
        lastHotspotUrl = null
    }

    fun receiveNextNearbyApp() {
        startNearbyDiscovery()
    }

    fun receiveNextHotspotApp() {
        val url = lastHotspotUrl
        if (!url.isNullOrBlank()) {
            startHttpDownload(url)
        } else {
            httpDownloader.reset()
            InstallResultBus.reset()
        }
    }

    fun refreshInstallPermissionAndStatus(expectedPackageName: String? = null) {
        _canRequestInstalls.value = apkInstaller.canRequestPackageInstalls()
        if (!expectedPackageName.isNullOrBlank()) {
            val currentInstall = installState.value
            if (currentInstall is InstallState.PendingUserConfirmation &&
                apkInstaller.isAppInstalled(expectedPackageName)
            ) {
                InstallResultBus.update(
                    InstallState.Installed(currentInstall.appName, expectedPackageName)
                )
            }
        }
    }

    fun openUnknownAppSourcesSettings() {
        apkInstaller.openUnknownAppSourcesSettings()
    }

    fun installReceivedApk(
        files: List<File>,
        appName: String,
        packageName: String,
        isSplitApk: Boolean
    ) {
        _canRequestInstalls.value = apkInstaller.canRequestPackageInstalls()
        if (!_canRequestInstalls.value) return
        viewModelScope.launch {
            apkInstaller.installApkFiles(files, appName, packageName, isSplitApk)
        }
    }

    fun openInstalledApp(packageName: String): Boolean {
        return apkInstaller.launchInstalledApp(packageName)
    }

    fun leavePreservingInterrupted() {
        discoveryJob?.cancel()
        val hasNearbyInterrupted = (nearbyState.value as? NearbyReceiverState.Error)?.interruptedRecord != null ||
            nearbyState.value is NearbyReceiverState.Downloading
        val hasHttpInterrupted = (httpDownloadState.value as? HttpDownloadState.Error)?.interruptedRecord != null ||
            httpDownloadState.value is HttpDownloadState.Downloading
        if (hasNearbyInterrupted || hasHttpInterrupted) {
            nearbyManager.stopPreservingPartial()
            httpDownloader.stopPreservingPartial()
            InstallResultBus.reset()
        } else {
            cancelAll()
        }
    }

    fun cancelAll() {
        discoveryJob?.cancel()
        nearbyManager.cancelReceiver()
        httpDownloader.cancel()
        InstallResultBus.reset()
        _receivedApps.value = emptyList()
        lastHotspotUrl = null
    }

    override fun onCleared() {
        super.onCleared()
        nearbyManager.stopPreservingPartial()
        httpDownloader.stopPreservingPartial()
    }

    class Factory(
        private val nearbyManager: NearbyTransferManager,
        private val httpDownloader: HttpApkDownloader,
        private val apkInstaller: ApkInstaller,
        private val settingsDataStore: SettingsDataStore,
        private val historyRepository: TransferHistoryRepository
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return ReceiverViewModel(
                nearbyManager,
                httpDownloader,
                apkInstaller,
                settingsDataStore,
                historyRepository
            ) as T
        }
    }
}
