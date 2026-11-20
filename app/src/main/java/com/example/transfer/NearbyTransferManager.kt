package com.example.transfer

import android.content.Context
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.annotation.StringRes
import com.example.R
import com.example.apk.ApkStorageManager
import com.example.model.ApkBundleMetadata
import com.example.model.ApkFileEntry
import com.example.model.DiscoveredSenderDevice
import com.example.model.ExtractedApkBundle
import com.example.model.NearbyFileMapping
import com.example.model.NearbyWireEnvelope
import com.example.model.PendingConnectionInfo
import com.example.model.ResumeFileOffset
import com.example.model.TransferProgressState
import com.example.service.NotificationHelper
import com.example.service.TransferForegroundService
import com.example.util.ConnectivityStatus
import com.example.util.FileTransferHelper
import com.example.util.FormatUtils
import com.example.util.LocaleHelper
import com.example.util.PermissionHelper
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.nearby.Nearby
import com.google.android.gms.nearby.connection.AdvertisingOptions
import com.google.android.gms.nearby.connection.ConnectionInfo
import com.google.android.gms.nearby.connection.ConnectionLifecycleCallback
import com.google.android.gms.nearby.connection.ConnectionResolution
import com.google.android.gms.nearby.connection.ConnectionsClient
import com.google.android.gms.nearby.connection.ConnectionsStatusCodes
import com.google.android.gms.nearby.connection.DiscoveredEndpointInfo
import com.google.android.gms.nearby.connection.DiscoveryOptions
import com.google.android.gms.nearby.connection.EndpointDiscoveryCallback
import com.google.android.gms.nearby.connection.Payload
import com.google.android.gms.nearby.connection.PayloadCallback
import com.google.android.gms.nearby.connection.PayloadTransferUpdate
import com.google.android.gms.nearby.connection.Strategy
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

sealed interface NearbySenderState {
    data object Idle : NearbySenderState
    data class WaitingForReceiver(
        val deviceName: String,
        val bundle: ExtractedApkBundle
    ) : NearbySenderState

    data class ConfirmConnection(
        val connection: PendingConnectionInfo,
        val bundle: ExtractedApkBundle
    ) : NearbySenderState

    data class WaitingReceiverAccept(
        val peerName: String,
        val bundle: ExtractedApkBundle
    ) : NearbySenderState

    data class Sending(
        val peerName: String,
        val bundle: ExtractedApkBundle,
        val progress: TransferProgressState,
        val isPaused: Boolean = false,
        val isWaitingWhilePaused: Boolean = false
    ) : NearbySenderState

    data class Success(
        val bundle: ExtractedApkBundle,
        val peerName: String,
        val durationMillis: Long
    ) : NearbySenderState

    data class Cancelled(
        val bundle: ExtractedApkBundle?,
        val peerName: String? = null
    ) : NearbySenderState

    data class Error(
        val reason: String,
        val bundle: ExtractedApkBundle?,
        val wasInterruptedMidTransfer: Boolean = false
    ) : NearbySenderState
}

sealed interface NearbyReceiverState {
    data object Idle : NearbyReceiverState
    data class Discovering(
        val devices: List<DiscoveredSenderDevice> = emptyList(),
        val qrTargetLabel: String? = null
    ) : NearbyReceiverState

    data class WaitingSenderApproval(
        val peerName: String,
        val authDigits: String
    ) : NearbyReceiverState

    data class IncomingOffer(
        val endpointId: String,
        val peerName: String,
        val metadata: ApkBundleMetadata
    ) : NearbyReceiverState

    data class Downloading(
        val peerName: String,
        val metadata: ApkBundleMetadata,
        val progress: TransferProgressState,
        val isPaused: Boolean = false,
        val isWaitingWhilePaused: Boolean = false,
        val statusNotice: String? = null
    ) : NearbyReceiverState

    data class Completed(
        val metadata: ApkBundleMetadata,
        val savedFiles: List<File>,
        val displayLocation: String,
        val peerName: String,
        val durationMillis: Long
    ) : NearbyReceiverState

    data class Cancelled(
        val peerName: String? = null
    ) : NearbyReceiverState

    data class Error(
        val reason: String,
        val interruptedRecord: InterruptedTransferRecord? = null
    ) : NearbyReceiverState
}

class NearbyTransferManager(
    private val context: Context,
    private val storageManager: ApkStorageManager,
    val resumeStore: InterruptedTransferStore = InterruptedTransferStore(context)
) {
    private fun str(@StringRes id: Int, vararg args: Any): String {
        val loc = LocaleHelper.localizedContext(context)
        return if (args.isEmpty()) loc.getString(id) else loc.getString(id, *args)
    }

    @Volatile
    private var clientInitialized = false

    private val connectionsClient: ConnectionsClient by lazy {
        clientInitialized = true
        Nearby.getConnectionsClient(context)
    }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val json = Json { ignoreUnknownKeys = true }

    private val _senderState = MutableStateFlow<NearbySenderState>(NearbySenderState.Idle)
    val senderState: StateFlow<NearbySenderState> = _senderState.asStateFlow()

    private val _receiverState = MutableStateFlow<NearbyReceiverState>(NearbyReceiverState.Idle)
    val receiverState: StateFlow<NearbyReceiverState> = _receiverState.asStateFlow()

    @Volatile
    var powerSaverEnabled: Boolean = false

    private val updateIntervalMs: Long
        get() = if (powerSaverEnabled) 1800L else 450L

    // Active sender session tracking
    private var activeBundle: ExtractedApkBundle? = null
    private var senderEndpointId: String? = null
    private var senderPeerName: String = "Receiver"
    private var senderStartTimeMillis: Long = 0L
    private var lastNotificationUpdateMillis: Long = 0L
    private var lastSenderUiUpdateMillis: Long = 0L
    private var lastReceiverUiUpdateMillis: Long = 0L
    private val outgoingPayloadBytes = ConcurrentHashMap<Long, Long>()
    private val outgoingPayloadBaseOffset = ConcurrentHashMap<Long, Long>()
    private val outgoingPayloadFileSize = ConcurrentHashMap<Long, Long>()
    private val outgoingPayloadCompleted = ConcurrentHashMap<Long, Boolean>()
    private val outgoingPayloadWaiters = ConcurrentHashMap<Long, CompletableDeferred<Boolean>>()
    private val outgoingOpenStreams = ConcurrentHashMap<Long, java.io.InputStream>()
    private val activeOutgoingPayloadIds = ConcurrentHashMap.newKeySet<Long>()
    private var requestedResumeOffsets: Map<String, ResumeFileOffset> = emptyMap()
    private var senderTransferJob: Job? = null
    private val senderSpeedTracker = FormatUtils.RollingTransferSpeedTracker()
    private val senderPausedSignal = MutableStateFlow(false)
    @Volatile
    private var senderPaused: Boolean = false
    @Volatile
    private var senderWaitingWhilePaused: Boolean = false
    @Volatile
    private var senderCancelledByUser: Boolean = false
    private var expectedOutgoingFileCount: Int = 0
    private var pendingOutgoingMetaPayloadId: Long = -1L

    // Active receiver session tracking
    private val discoveredDevicesMap = LinkedHashMap<String, DiscoveredSenderDevice>()
    private var receiverEndpointId: String? = null
    private var receiverPeerName: String = "Sender"
    private var pendingQrTargetCode: String? = null
    private var pendingQrTargetLabel: String? = null
    private var pendingResumePeerName: String? = null
    private var pendingQrLocalDeviceName: String = android.os.Build.MODEL
    private var activeResumeRecord: InterruptedTransferRecord? = null
    private var pendingMetadata: ApkBundleMetadata? = null
    private var receiverAcceptedOffer: Boolean = false
    private var receiverStartTimeMillis: Long = 0L
    private var receiverTimeoutJob: Job? = null
    private val incomingFileMappings = ConcurrentHashMap<Long, NearbyFileMapping>()
    private val incomingFilePayloads = ConcurrentHashMap<Long, Payload>()
    private val incomingStreamPayloads = ConcurrentHashMap<Long, Payload>()
    private val startedStreamPayloads = ConcurrentHashMap.newKeySet<Long>()
    private val incomingStreamJobs = ConcurrentHashMap<Long, Job>()
    private val incomingPayloadBytes = ConcurrentHashMap<Long, Long>()
    private val incomingCompletedPayloadIds = ConcurrentHashMap.newKeySet<Long>()
    private val activeIncomingPayloadIds = ConcurrentHashMap.newKeySet<Long>()
    private val plannedSafeFileNames = ConcurrentHashMap<String, String>()
    private val receiverSpeedTracker = FormatUtils.RollingTransferSpeedTracker()
    @Volatile
    private var receiverStatusNotice: String? = null
    @Volatile
    private var receiverPaused: Boolean = false
    @Volatile
    private var receiverWaitingWhilePaused: Boolean = false
    @Volatile
    private var receiverCancelledByUser: Boolean = false
    @Volatile
    private var receiverPreservePartialOnStop: Boolean = false
    private var receiverDestDir: File? = null
    private var exportToPublicDownloads: Boolean = true

    // ---------------- SENDER ----------------

    // Active sender advertised name
    private var activeAdvertisedName: String = android.os.Build.MODEL

    fun startAdvertising(deviceName: String, bundle: ExtractedApkBundle) {
        activeBundle = bundle
        val missingPerms = PermissionHelper.missingPermissions(
            context,
            PermissionHelper.getNearbyTransferPermissions()
        )
        if (missingPerms.isNotEmpty()) {
            Log.w(TAG, "Refusing startAdvertising: missing permissions $missingPerms")
            TransferForegroundService.stop(context)
            _senderState.value = NearbySenderState.Error(
                reason = str(R.string.error_nearby_missing_perms),
                bundle = bundle
            )
            return
        }

        val connectivity = PermissionHelper.checkConnectivity(context)
        if (!connectivity.isReadyForNearby) {
            Log.w(TAG, "Refusing startAdvertising: connectivity not ready ($connectivity)")
            TransferForegroundService.stop(context)
            _senderState.value = NearbySenderState.Error(
                reason = buildConnectivityError(connectivity),
                bundle = bundle
            )
            return
        }

        stopAll()
        activeBundle = bundle
        senderPeerName = "Receiver"
        senderPaused = false
        senderWaitingWhilePaused = false
        senderCancelledByUser = false
        senderPausedSignal.value = false
        lastSenderUiUpdateMillis = 0L
        senderSpeedTracker.reset()
        outgoingPayloadBytes.clear()
        outgoingPayloadBaseOffset.clear()
        outgoingPayloadFileSize.clear()
        outgoingPayloadCompleted.clear()
        outgoingPayloadWaiters.clear()
        closeAllOutgoingStreams()
        activeOutgoingPayloadIds.clear()
        requestedResumeOffsets = emptyMap()
        expectedOutgoingFileCount = bundle.files.size

        val pairCode = bundle.transferId.take(4).uppercase()
        val advertisedName = "$deviceName #$pairCode"
        activeAdvertisedName = advertisedName
        _senderState.value = NearbySenderState.WaitingForReceiver(advertisedName, bundle)

        val options = AdvertisingOptions.Builder()
            .setStrategy(STRATEGY)
            .build()

        try {
            connectionsClient.startAdvertising(
                advertisedName,
                SERVICE_ID,
                senderConnectionCallback,
                options
            ).addOnSuccessListener {
                Log.i(TAG, "Nearby advertising started as '$advertisedName' for ${bundle.appName}")
                TransferForegroundService.startOrUpdate(
                    context,
                    str(R.string.notification_sharing_title, bundle.appName),
                    str(R.string.notification_waiting_receiver),
                    indeterminate = true
                )
            }.addOnFailureListener { e ->
                Log.e(TAG, "Nearby startAdvertising failed", e)
                if (activeBundle?.transferId == bundle.transferId &&
                    _senderState.value is NearbySenderState.WaitingForReceiver
                ) {
                    TransferForegroundService.stop(context)
                    _senderState.value = NearbySenderState.Error(
                        reason = mapNearbyError(e, str(R.string.error_nearby_start_sharing)),
                        bundle = bundle
                    )
                }
            }
        } catch (se: SecurityException) {
            Log.e(TAG, "SecurityException in startAdvertising", se)
            TransferForegroundService.stop(context)
            _senderState.value = NearbySenderState.Error(
                reason = mapNearbyError(se, str(R.string.error_nearby_start_sharing)),
                bundle = bundle
            )
        } catch (ise: IllegalStateException) {
            Log.e(TAG, "IllegalStateException in startAdvertising", ise)
            TransferForegroundService.stop(context)
            _senderState.value = NearbySenderState.Error(
                reason = mapNearbyError(ise, str(R.string.error_nearby_start_sharing)),
                bundle = bundle
            )
        } catch (ae: ApiException) {
            Log.e(TAG, "ApiException in startAdvertising", ae)
            TransferForegroundService.stop(context)
            _senderState.value = NearbySenderState.Error(
                reason = mapNearbyError(ae, str(R.string.error_nearby_start_sharing)),
                bundle = bundle
            )
        } catch (e: Throwable) {
            Log.e(TAG, "Unexpected error in startAdvertising", e)
            TransferForegroundService.stop(context)
            _senderState.value = NearbySenderState.Error(
                reason = mapNearbyError(e, str(R.string.error_nearby_start_sharing)),
                bundle = bundle
            )
        }
    }

    fun acceptIncomingConnection(endpointId: String) {
        val bundle = activeBundle ?: return
        try {
            connectionsClient.acceptConnection(endpointId, senderPayloadCallback)
                .addOnSuccessListener {
                    Log.i(TAG, "Accepted Nearby connection from $endpointId")
                    _senderState.value = NearbySenderState.WaitingReceiverAccept(senderPeerName, bundle)
                }
                .addOnFailureListener { e ->
                    Log.e(TAG, "Failed to accept connection", e)
                    TransferForegroundService.stop(context)
                    _senderState.value = NearbySenderState.Error(
                        reason = mapNearbyError(e, str(R.string.error_nearby_accept_connection)),
                        bundle = bundle
                    )
                }
        } catch (se: SecurityException) {
            Log.e(TAG, "SecurityException in acceptIncomingConnection", se)
            TransferForegroundService.stop(context)
            _senderState.value = NearbySenderState.Error(
                reason = mapNearbyError(se, str(R.string.error_nearby_accept_connection)),
                bundle = bundle
            )
        } catch (ise: IllegalStateException) {
            Log.e(TAG, "IllegalStateException in acceptIncomingConnection", ise)
            TransferForegroundService.stop(context)
            _senderState.value = NearbySenderState.Error(
                reason = mapNearbyError(ise, str(R.string.error_nearby_accept_connection)),
                bundle = bundle
            )
        } catch (ae: ApiException) {
            Log.e(TAG, "ApiException in acceptIncomingConnection", ae)
            TransferForegroundService.stop(context)
            _senderState.value = NearbySenderState.Error(
                reason = mapNearbyError(ae, str(R.string.error_nearby_accept_connection)),
                bundle = bundle
            )
        } catch (e: Throwable) {
            Log.e(TAG, "Error in acceptIncomingConnection", e)
            TransferForegroundService.stop(context)
            _senderState.value = NearbySenderState.Error(
                reason = mapNearbyError(e, str(R.string.error_nearby_accept_connection)),
                bundle = bundle
            )
        }
    }

    fun rejectIncomingConnection(endpointId: String) {
        try {
            connectionsClient.rejectConnection(endpointId)
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to reject connection cleanly", t)
        }
        val bundle = activeBundle
        if (bundle != null) {
            _senderState.value = NearbySenderState.WaitingForReceiver(
                deviceName = activeAdvertisedName,
                bundle = bundle
            )
        }
    }

    fun setSenderPaused(paused: Boolean) {
        applySenderPausedState(paused = paused, notifyPeer = true)
    }

    private fun applySenderPausedState(paused: Boolean, notifyPeer: Boolean) {
        senderPaused = paused
        senderPausedSignal.value = paused
        if (!paused) {
            senderWaitingWhilePaused = false
            val sentBytes = outgoingPayloadBytes.values.sum()
            senderSpeedTracker.reset(System.currentTimeMillis(), sentBytes)
        }
        val endpoint = senderEndpointId
        if (notifyPeer && !endpoint.isNullOrBlank()) {
            sendControlEnvelope(endpoint, if (paused) "PAUSE" else "RESUME")
        }
        val current = _senderState.value
        if (current is NearbySenderState.Sending) {
            val waiting = paused && senderWaitingWhilePaused
            val updatedProgress = if (waiting) {
                current.progress.copy(speedBytesPerSec = 0L, etaSeconds = -1L)
            } else {
                current.progress
            }
            _senderState.value = current.copy(
                progress = updatedProgress,
                isPaused = paused,
                isWaitingWhilePaused = waiting
            )
        }
    }

    fun cancelActiveTransferBySender() {
        val bundle = activeBundle
        val peer = senderPeerName
        val endpoint = senderEndpointId
        senderCancelledByUser = true
        senderPaused = false
        senderWaitingWhilePaused = false
        senderPausedSignal.value = false
        senderTransferJob?.cancel()
        senderTransferJob = null
        completeAllOutgoingWaiters(false)

        if (clientInitialized) {
            activeOutgoingPayloadIds.toList().forEach { payloadId ->
                runCatching { connectionsClient.cancelPayload(payloadId) }
            }
            if (!endpoint.isNullOrBlank()) {
                sendControlEnvelope(endpoint, "CANCEL")
            }
        }
        activeOutgoingPayloadIds.clear()
        stopAll()
        _senderState.value = NearbySenderState.Cancelled(bundle = bundle, peerName = peer)
    }

    fun cancelSender() {
        senderCancelledByUser = false
        senderPaused = false
        senderWaitingWhilePaused = false
        senderPausedSignal.value = false
        senderTransferJob?.cancel()
        senderTransferJob = null
        completeAllOutgoingWaiters(false)
        stopAll()
        _senderState.value = NearbySenderState.Idle
    }

    private fun handleRemoteCancelOnSender() {
        val bundle = activeBundle
        val peer = senderPeerName
        senderCancelledByUser = true
        senderPaused = false
        senderWaitingWhilePaused = false
        senderPausedSignal.value = false
        senderTransferJob?.cancel()
        senderTransferJob = null
        completeAllOutgoingWaiters(false)
        if (clientInitialized) {
            activeOutgoingPayloadIds.toList().forEach { payloadId ->
                runCatching { connectionsClient.cancelPayload(payloadId) }
            }
        }
        activeOutgoingPayloadIds.clear()
        stopAll()
        _senderState.value = NearbySenderState.Cancelled(bundle = bundle, peerName = peer)
    }

    private fun completeAllOutgoingWaiters(result: Boolean) {
        outgoingPayloadWaiters.values.forEach { it.complete(result) }
        outgoingPayloadWaiters.clear()
        closeAllOutgoingStreams()
    }

    private fun closeAllOutgoingStreams() {
        outgoingOpenStreams.values.forEach { runCatching { it.close() } }
        outgoingOpenStreams.clear()
    }

    private fun sendControlEnvelope(endpointId: String, kind: String) {
        if (!clientInitialized) return
        val envelope = NearbyWireEnvelope(kind = kind)
        val payload = Payload.fromBytes(json.encodeToString(envelope).toByteArray(Charsets.UTF_8))
        runCatching {
            connectionsClient.sendPayload(endpointId, payload)
        }.onFailure {
            Log.w(TAG, "Failed to send control envelope $kind", it)
        }
    }

    private val senderConnectionCallback = object : ConnectionLifecycleCallback() {
        override fun onConnectionInitiated(endpointId: String, info: ConnectionInfo) {
            val bundle = activeBundle ?: return
            senderEndpointId = endpointId
            senderPeerName = info.endpointName
            Log.i(TAG, "Sender connection initiated by ${info.endpointName} ($endpointId)")
            _senderState.value = NearbySenderState.ConfirmConnection(
                connection = PendingConnectionInfo(
                    endpointId = endpointId,
                    deviceName = info.endpointName,
                    authDigits = info.authenticationDigits,
                    isIncoming = info.isIncomingConnection
                ),
                bundle = bundle
            )
        }

        override fun onConnectionResult(endpointId: String, result: ConnectionResolution) {
            val bundle = activeBundle ?: return
            when (result.status.statusCode) {
                ConnectionsStatusCodes.STATUS_OK -> {
                    Log.i(TAG, "Sender connected to $endpointId; sending metadata")
                    runCatching { connectionsClient.stopAdvertising() }
                    sendMetadataToReceiver(endpointId, bundle)
                }
                ConnectionsStatusCodes.STATUS_CONNECTION_REJECTED -> {
                    _senderState.value = NearbySenderState.Error(
                        reason = str(R.string.error_nearby_connection_declined, senderPeerName),
                        bundle = bundle
                    )
                    TransferForegroundService.stop(context)
                }
                else -> {
                    _senderState.value = NearbySenderState.Error(
                        reason = str(R.string.error_nearby_connection_failed, result.status.statusCode),
                        bundle = bundle
                    )
                    TransferForegroundService.stop(context)
                }
            }
        }

        override fun onDisconnected(endpointId: String) {
            Log.w(TAG, "Sender disconnected from $endpointId")
            if (senderCancelledByUser) return
            val current = _senderState.value
            if (current is NearbySenderState.Sending || current is NearbySenderState.WaitingReceiverAccept) {
                completeAllOutgoingWaiters(false)
                TransferForegroundService.stop(context)
                _senderState.value = NearbySenderState.Error(
                    reason = str(R.string.error_nearby_connection_lost_sender, senderPeerName),
                    bundle = activeBundle,
                    wasInterruptedMidTransfer = current is NearbySenderState.Sending
                )
            }
        }
    }

    private fun sendMetadataToReceiver(endpointId: String, bundle: ExtractedApkBundle) {
        val metadata = ApkBundleMetadata(
            transferId = bundle.transferId,
            appName = bundle.appName,
            packageName = bundle.packageName,
            versionName = bundle.versionName,
            versionCode = bundle.versionCode,
            totalSizeBytes = bundle.totalSizeBytes,
            isSplitApk = bundle.isSplitApk,
            files = bundle.files.map { ApkFileEntry(it.name, it.length()) },
            iconBase64 = bundle.iconBase64,
            isGenericFile = bundle.isGenericFile,
            mimeType = bundle.mimeType
        )
        val envelope = NearbyWireEnvelope(kind = "METADATA", metadata = metadata)
        val bytes = json.encodeToString(envelope).toByteArray(Charsets.UTF_8)
        val payload = Payload.fromBytes(bytes)
        pendingOutgoingMetaPayloadId = payload.id
        try {
            connectionsClient.sendPayload(endpointId, payload)
                .addOnFailureListener { e ->
                    Log.e(TAG, "Failed to send metadata", e)
                    TransferForegroundService.stop(context)
                    _senderState.value = NearbySenderState.Error(
                        reason = mapNearbyError(e, str(R.string.error_nearby_send_metadata)),
                        bundle = bundle
                    )
                }
        } catch (se: SecurityException) {
            Log.e(TAG, "SecurityException sending metadata", se)
            TransferForegroundService.stop(context)
            _senderState.value = NearbySenderState.Error(
                reason = mapNearbyError(se, str(R.string.error_nearby_send_metadata)),
                bundle = bundle
            )
        } catch (ise: IllegalStateException) {
            Log.e(TAG, "IllegalStateException sending metadata", ise)
            TransferForegroundService.stop(context)
            _senderState.value = NearbySenderState.Error(
                reason = mapNearbyError(ise, str(R.string.error_nearby_send_metadata)),
                bundle = bundle
            )
        } catch (ae: ApiException) {
            Log.e(TAG, "ApiException sending metadata", ae)
            TransferForegroundService.stop(context)
            _senderState.value = NearbySenderState.Error(
                reason = mapNearbyError(ae, str(R.string.error_nearby_send_metadata)),
                bundle = bundle
            )
        } catch (e: Throwable) {
            Log.e(TAG, "Error sending metadata", e)
            TransferForegroundService.stop(context)
            _senderState.value = NearbySenderState.Error(
                reason = mapNearbyError(e, str(R.string.error_nearby_send_metadata)),
                bundle = bundle
            )
        }
    }

    private fun startSendingApkPayloads(endpointId: String, bundle: ExtractedApkBundle) {
        senderStartTimeMillis = System.currentTimeMillis()
        lastSenderUiUpdateMillis = 0L
        senderCancelledByUser = false
        senderWaitingWhilePaused = false
        outgoingPayloadBytes.clear()
        outgoingPayloadBaseOffset.clear()
        outgoingPayloadFileSize.clear()
        outgoingPayloadCompleted.clear()
        completeAllOutgoingWaiters(false)
        activeOutgoingPayloadIds.clear()

        val plans = bundle.files.map { file ->
            file to NearbyResumeCodec.resolveSenderFilePlan(file, requestedResumeOffsets[file.name])
        }
        val initialOffsetTotal = plans.sumOf { (_, plan) -> plan.offsetBytes }
            .coerceAtMost(bundle.totalSizeBytes)
        val initialCompletedCount = plans.count { (_, plan) -> plan.alreadyCompleted }
        senderSpeedTracker.reset(senderStartTimeMillis, initialOffsetTotal)

        // Seed pre-completed or offset-resumed bytes so live stats count from resumed offset
        plans.forEachIndexed { idx, (file, plan) ->
            if (plan.alreadyCompleted) {
                val syntheticId = -10_000L - idx
                outgoingPayloadBytes[syntheticId] = file.length()
                outgoingPayloadBaseOffset[syntheticId] = file.length()
                outgoingPayloadFileSize[syntheticId] = file.length()
                outgoingPayloadCompleted[syntheticId] = true
            }
        }

        _senderState.value = NearbySenderState.Sending(
            peerName = senderPeerName,
            bundle = bundle,
            progress = TransferProgressState(
                bytesTransferred = initialOffsetTotal,
                totalBytes = bundle.totalSizeBytes,
                speedBytesPerSec = 0L,
                etaSeconds = -1L,
                completedFiles = initialCompletedCount,
                totalFiles = bundle.files.size,
                currentFileName = plans.firstOrNull { !it.second.alreadyCompleted }?.first?.name
                    ?: bundle.files.firstOrNull()?.name.orEmpty()
            ),
            isPaused = senderPaused,
            isWaitingWhilePaused = false
        )

        // Edge case: every file was already completely received on the receiver
        if (initialCompletedCount >= expectedOutgoingFileCount) {
            completeSenderTransfer(endpointId, bundle)
            return
        }

        senderTransferJob?.cancel()
        senderTransferJob = scope.launch {
            try {
                for ((file, plan) in plans) {
                    if (plan.alreadyCompleted) continue
                    if (senderCancelledByUser || _senderState.value !is NearbySenderState.Sending) break

                    if (senderPaused) {
                        senderWaitingWhilePaused = true
                        updateSenderProgress(bundle, forceEmit = true)
                        senderPausedSignal.first { !it || senderCancelledByUser }
                        senderWaitingWhilePaused = false
                        if (senderCancelledByUser || _senderState.value !is NearbySenderState.Sending) break
                        senderSpeedTracker.reset(
                            System.currentTimeMillis(),
                            outgoingPayloadBytes.values.sum()
                        )
                        updateSenderProgress(bundle, forceEmit = true)
                    }

                    val offsetStream = NearbyResumeCodec.openOffsetInputStream(file, plan.offsetBytes)
                    val streamPayload = Payload.fromStream(offsetStream)
                    outgoingOpenStreams[streamPayload.id] = offsetStream

                    val completion = CompletableDeferred<Boolean>()
                    outgoingPayloadWaiters[streamPayload.id] = completion
                    activeOutgoingPayloadIds.add(streamPayload.id)
                    outgoingPayloadBaseOffset[streamPayload.id] = plan.offsetBytes
                    outgoingPayloadFileSize[streamPayload.id] = file.length()
                    outgoingPayloadBytes[streamPayload.id] = plan.offsetBytes
                    outgoingPayloadCompleted[streamPayload.id] = false

                    val mapping = NearbyWireEnvelope(
                        kind = "FILE_MAP",
                        fileMapping = NearbyFileMapping(
                            payloadId = streamPayload.id,
                            fileName = file.name,
                            fileSize = file.length(),
                            offsetBytes = plan.offsetBytes,
                            restartedFromZero = plan.restartedFromZero
                        )
                    )
                    val mapPayload = Payload.fromBytes(
                        json.encodeToString(mapping).toByteArray(Charsets.UTF_8)
                    )
                    connectionsClient.sendPayload(endpointId, mapPayload)
                        .addOnFailureListener { e ->
                            Log.e(TAG, "Failed to send file map payload", e)
                            runCatching { outgoingOpenStreams.remove(streamPayload.id)?.close() }
                            completion.complete(false)
                            if (!senderCancelledByUser) {
                                TransferForegroundService.stop(context)
                                _senderState.value = NearbySenderState.Error(
                                    reason = mapNearbyError(e, str(R.string.error_nearby_send_file_meta)),
                                    bundle = bundle,
                                    wasInterruptedMidTransfer = true
                                )
                            }
                        }
                    delay(40)
                    if (senderCancelledByUser) {
                        runCatching { outgoingOpenStreams.remove(streamPayload.id)?.close() }
                        completion.complete(false)
                        break
                    }
                    connectionsClient.sendPayload(endpointId, streamPayload)
                        .addOnFailureListener { e ->
                            Log.e(TAG, "Failed to send APK stream payload", e)
                            activeOutgoingPayloadIds.remove(streamPayload.id)
                            runCatching { outgoingOpenStreams.remove(streamPayload.id)?.close() }
                            completion.complete(false)
                            if (!senderCancelledByUser) {
                                TransferForegroundService.stop(context)
                                _senderState.value = NearbySenderState.Error(
                                    reason = mapNearbyError(e, str(R.string.error_nearby_send_file, file.name)),
                                    bundle = bundle,
                                    wasInterruptedMidTransfer = true
                                )
                            }
                        }
                    Log.i(
                        TAG,
                        "Queued outgoing stream payload id=${streamPayload.id} name=${file.name} offset=${plan.offsetBytes}/${file.length()}"
                    )

                    val succeeded = completion.await()
                    runCatching { outgoingOpenStreams.remove(streamPayload.id)?.close() }
                    if (!succeeded || senderCancelledByUser) break
                }
            } catch (se: SecurityException) {
                if (!senderCancelledByUser) {
                    Log.e(TAG, "SecurityException queueing APK payloads", se)
                    TransferForegroundService.stop(context)
                    _senderState.value = NearbySenderState.Error(
                        reason = mapNearbyError(se, str(R.string.error_nearby_sending_files)),
                        bundle = bundle,
                        wasInterruptedMidTransfer = true
                    )
                }
            } catch (ise: IllegalStateException) {
                if (!senderCancelledByUser) {
                    Log.e(TAG, "IllegalStateException queueing APK payloads", ise)
                    TransferForegroundService.stop(context)
                    _senderState.value = NearbySenderState.Error(
                        reason = mapNearbyError(ise, str(R.string.error_nearby_sending_files)),
                        bundle = bundle,
                        wasInterruptedMidTransfer = true
                    )
                }
            } catch (ae: ApiException) {
                if (!senderCancelledByUser) {
                    Log.e(TAG, "ApiException queueing APK payloads", ae)
                    TransferForegroundService.stop(context)
                    _senderState.value = NearbySenderState.Error(
                        reason = mapNearbyError(ae, str(R.string.error_nearby_sending_files)),
                        bundle = bundle,
                        wasInterruptedMidTransfer = true
                    )
                }
            } catch (t: Throwable) {
                if (!senderCancelledByUser && t !is kotlinx.coroutines.CancellationException) {
                    Log.e(TAG, "Error queueing APK payloads", t)
                    TransferForegroundService.stop(context)
                    _senderState.value = NearbySenderState.Error(
                        reason = mapNearbyError(t, str(R.string.error_nearby_preparing_files)),
                        bundle = bundle,
                        wasInterruptedMidTransfer = true
                    )
                }
            }
        }
    }

    private fun completeSenderTransfer(endpointId: String, bundle: ExtractedApkBundle) {
        val elapsed = (System.currentTimeMillis() - senderStartTimeMillis).coerceAtLeast(500L)
        Log.i(TAG, "All ${bundle.files.size} APK file(s) sent in ${elapsed}ms")
        TransferForegroundService.stop(context)
        NotificationHelper.showResultNotification(
            context,
            NotificationHelper.NOTIFICATION_ID_RESULT,
            str(R.string.notification_sent_title, bundle.appName),
            str(
                R.string.notification_sent_body,
                FormatUtils.formatBytes(bundle.totalSizeBytes),
                senderPeerName
            )
        )
        runCatching { connectionsClient.disconnectFromEndpoint(endpointId) }
        _senderState.value = NearbySenderState.Success(
            bundle = bundle,
            peerName = senderPeerName,
            durationMillis = elapsed
        )
    }

    private val senderPayloadCallback = object : PayloadCallback() {
        override fun onPayloadReceived(endpointId: String, payload: Payload) {
            if (payload.type != Payload.Type.BYTES) return
            val raw = payload.asBytes()?.toString(Charsets.UTF_8) ?: return
            val envelope = runCatching { json.decodeFromString<NearbyWireEnvelope>(raw) }.getOrNull() ?: return
            val bundle = activeBundle ?: return
            when (envelope.kind) {
                "ACCEPT" -> {
                    requestedResumeOffsets = envelope.resumeOffsets
                        ?.associateBy { it.fileName }
                        .orEmpty()
                    Log.i(
                        TAG,
                        "Receiver accepted offer (resumeOffsets=${requestedResumeOffsets.size}); starting payloads"
                    )
                    startSendingApkPayloads(endpointId, bundle)
                }
                "DECLINE" -> {
                    Log.i(TAG, "Receiver declined offer")
                    stopAll()
                    _senderState.value = NearbySenderState.Error(
                        reason = str(R.string.error_nearby_receiver_declined_app, senderPeerName),
                        bundle = bundle,
                        wasInterruptedMidTransfer = false
                    )
                }
                "PAUSE" -> {
                    Log.i(TAG, "Receiver requested queue pause")
                    applySenderPausedState(paused = true, notifyPeer = false)
                }
                "RESUME" -> {
                    Log.i(TAG, "Receiver requested queue resume")
                    applySenderPausedState(paused = false, notifyPeer = false)
                }
                "CANCEL" -> {
                    Log.i(TAG, "Receiver cancelled active transfer")
                    handleRemoteCancelOnSender()
                }
            }
        }

        override fun onPayloadTransferUpdate(endpointId: String, update: PayloadTransferUpdate) {
            val bundle = activeBundle ?: return
            if (!outgoingPayloadBytes.containsKey(update.payloadId)) return

            val baseOffset = outgoingPayloadBaseOffset[update.payloadId] ?: 0L
            val fileSize = outgoingPayloadFileSize[update.payloadId] ?: 0L

            when (update.status) {
                PayloadTransferUpdate.Status.IN_PROGRESS -> {
                    if (senderCancelledByUser) return
                    val totalForFile = (baseOffset + update.bytesTransferred).let { sum ->
                        if (fileSize > 0L) sum.coerceAtMost(fileSize) else sum
                    }
                    outgoingPayloadBytes[update.payloadId] = totalForFile
                    updateSenderProgress(bundle)
                }
                PayloadTransferUpdate.Status.SUCCESS -> {
                    activeOutgoingPayloadIds.remove(update.payloadId)
                    runCatching { outgoingOpenStreams.remove(update.payloadId)?.close() }
                    outgoingPayloadWaiters.remove(update.payloadId)?.complete(true)
                    if (senderCancelledByUser) return

                    val completedFileBytes = when {
                        fileSize > 0L -> fileSize
                        update.totalBytes > 0L -> baseOffset + update.totalBytes
                        else -> outgoingPayloadBytes[update.payloadId] ?: baseOffset
                    }
                    outgoingPayloadBytes[update.payloadId] = completedFileBytes
                    outgoingPayloadCompleted[update.payloadId] = true
                    updateSenderProgress(bundle, forceEmit = true)

                    val completedCount = outgoingPayloadCompleted.values.count { it }
                    if (completedCount >= expectedOutgoingFileCount) {
                        completeSenderTransfer(endpointId, bundle)
                    }
                }
                PayloadTransferUpdate.Status.FAILURE -> {
                    activeOutgoingPayloadIds.remove(update.payloadId)
                    runCatching { outgoingOpenStreams.remove(update.payloadId)?.close() }
                    outgoingPayloadWaiters.remove(update.payloadId)?.complete(false)
                    if (senderCancelledByUser) return
                    TransferForegroundService.stop(context)
                    _senderState.value = NearbySenderState.Error(
                        reason = str(R.string.error_nearby_interrupted_sending, bundle.appName),
                        bundle = bundle,
                        wasInterruptedMidTransfer = true
                    )
                }
                PayloadTransferUpdate.Status.CANCELED -> {
                    activeOutgoingPayloadIds.remove(update.payloadId)
                    runCatching { outgoingOpenStreams.remove(update.payloadId)?.close() }
                    outgoingPayloadWaiters.remove(update.payloadId)?.complete(false)
                    if (senderCancelledByUser) return
                    TransferForegroundService.stop(context)
                    stopAll()
                    _senderState.value = NearbySenderState.Cancelled(
                        bundle = bundle,
                        peerName = senderPeerName
                    )
                }
            }
        }
    }

    private fun updateSenderProgress(bundle: ExtractedApkBundle, forceEmit: Boolean = false) {
        if (senderCancelledByUser) return
        val now = System.currentTimeMillis()
        val sentBytes = outgoingPayloadBytes.values.sum().coerceAtMost(bundle.totalSizeBytes)
        val rollingSpeed = if (senderWaitingWhilePaused) {
            0L
        } else {
            senderSpeedTracker.record(sentBytes, now)
        }
        val completedCount = outgoingPayloadCompleted.values.count { it }
        val minUiInterval = if (powerSaverEnabled) 1000L else FormatUtils.LIVE_STATS_UPDATE_INTERVAL_MILLIS
        if (!forceEmit &&
            completedCount < expectedOutgoingFileCount &&
            now - lastSenderUiUpdateMillis < minUiInterval
        ) {
            return
        }
        lastSenderUiUpdateMillis = now

        val elapsedMs = (now - senderStartTimeMillis).coerceAtLeast(1L)
        val remainingBytes = (bundle.totalSizeBytes - sentBytes).coerceAtLeast(0L)
        val effectiveSpeed = if (senderWaitingWhilePaused) 0L else rollingSpeed
        val etaSec = FormatUtils.computeEtaSeconds(remainingBytes, effectiveSpeed)
        val currentName = bundle.files.getOrNull(completedCount.coerceAtMost(bundle.files.lastIndex))?.name.orEmpty()

        val progress = TransferProgressState(
            bytesTransferred = sentBytes,
            totalBytes = bundle.totalSizeBytes,
            speedBytesPerSec = effectiveSpeed,
            etaSeconds = etaSec,
            currentFileName = currentName,
            completedFiles = completedCount,
            totalFiles = bundle.files.size,
            elapsedMillis = elapsedMs
        )
        _senderState.value = NearbySenderState.Sending(
            peerName = senderPeerName,
            bundle = bundle,
            progress = progress,
            isPaused = senderPaused,
            isWaitingWhilePaused = senderWaitingWhilePaused
        )

        if (now - lastNotificationUpdateMillis > updateIntervalMs) {
            lastNotificationUpdateMillis = now
            TransferForegroundService.startOrUpdate(
                context = context,
                title = str(R.string.notification_sending_progress, bundle.appName, progress.progressPercent),
                text = "${FormatUtils.formatBytes(sentBytes)} / ${FormatUtils.formatBytes(bundle.totalSizeBytes)} • ${FormatUtils.formatLiveSpeed(effectiveSpeed)}",
                progressPercent = progress.progressPercent,
                indeterminate = false
            )
        }
    }

    // ---------------- RECEIVER ----------------

    private val incomingFileBytesByName = ConcurrentHashMap<String, Long>()

    fun startDiscovery(exportToPublicDownloads: Boolean) {
        startDiscoveryInternal(
            exportToPublicDownloads = exportToPublicDownloads,
            resumeRecord = null,
            localDeviceName = null
        )
    }

    fun startResumeDiscovery(
        record: InterruptedTransferRecord,
        localDeviceName: String,
        exportToPublicDownloads: Boolean
    ) {
        startDiscoveryInternal(
            exportToPublicDownloads = exportToPublicDownloads,
            resumeRecord = record,
            localDeviceName = localDeviceName
        )
    }

    private fun startDiscoveryInternal(
        exportToPublicDownloads: Boolean,
        resumeRecord: InterruptedTransferRecord?,
        localDeviceName: String?
    ) {
        val missingPerms = PermissionHelper.missingPermissions(
            context,
            PermissionHelper.getNearbyTransferPermissions()
        )
        if (missingPerms.isNotEmpty()) {
            Log.w(TAG, "Refusing startDiscovery: missing permissions $missingPerms")
            _receiverState.value = NearbyReceiverState.Error(
                reason = str(R.string.error_nearby_missing_perms),
                interruptedRecord = resumeRecord
            )
            return
        }

        val connectivity = PermissionHelper.checkConnectivity(context)
        if (!connectivity.isReadyForNearby) {
            Log.w(TAG, "Refusing startDiscovery: connectivity not ready ($connectivity)")
            _receiverState.value = NearbyReceiverState.Error(
                reason = buildConnectivityError(connectivity),
                interruptedRecord = resumeRecord
            )
            return
        }

        stopAll()
        this.exportToPublicDownloads = exportToPublicDownloads
        activeResumeRecord = resumeRecord
        discoveredDevicesMap.clear()
        incomingFileMappings.clear()
        incomingFilePayloads.clear()
        incomingStreamPayloads.clear()
        startedStreamPayloads.clear()
        cancelIncomingStreamJobs()
        incomingPayloadBytes.clear()
        incomingFileBytesByName.clear()
        incomingCompletedPayloadIds.clear()
        activeIncomingPayloadIds.clear()
        plannedSafeFileNames.clear()
        receiverStatusNotice = null
        receiverPaused = false
        receiverWaitingWhilePaused = false
        receiverCancelledByUser = false
        receiverPreservePartialOnStop = false
        lastReceiverUiUpdateMillis = 0L
        receiverSpeedTracker.reset()
        pendingMetadata = null
        receiverAcceptedOffer = false

        val bannerLabel = if (resumeRecord != null) {
            pendingQrTargetCode = resumeRecord.senderPairCode
            pendingResumePeerName = resumeRecord.peerName
            if (!localDeviceName.isNullOrBlank()) {
                pendingQrLocalDeviceName = localDeviceName
            }
            val label = str(
                R.string.transfer_resume_reconnecting,
                resumeRecord.peerName,
                FormatUtils.formatTransferSize(resumeRecord.totalReceivedBytes)
            )
            pendingQrTargetLabel = label
            label
        } else {
            null
        }

        _receiverState.value = NearbyReceiverState.Discovering(
            devices = emptyList(),
            qrTargetLabel = bannerLabel
        )

        val options = DiscoveryOptions.Builder()
            .setStrategy(STRATEGY)
            .build()

        try {
            connectionsClient.startDiscovery(
                SERVICE_ID,
                receiverDiscoveryCallback,
                options
            ).addOnSuccessListener {
                Log.i(TAG, "Nearby discovery started")
            }.addOnFailureListener { e ->
                Log.e(TAG, "Nearby startDiscovery failed", e)
                _receiverState.value = NearbyReceiverState.Error(
                    reason = mapNearbyError(e, str(R.string.error_nearby_scan_failed)),
                    interruptedRecord = activeResumeRecord
                )
            }
        } catch (se: SecurityException) {
            Log.e(TAG, "SecurityException in startDiscovery", se)
            _receiverState.value = NearbyReceiverState.Error(
                reason = mapNearbyError(se, str(R.string.error_nearby_scan_failed)),
                interruptedRecord = activeResumeRecord
            )
        } catch (ise: IllegalStateException) {
            Log.e(TAG, "IllegalStateException in startDiscovery", ise)
            _receiverState.value = NearbyReceiverState.Error(
                reason = mapNearbyError(ise, str(R.string.error_nearby_scan_failed)),
                interruptedRecord = activeResumeRecord
            )
        } catch (ae: ApiException) {
            Log.e(TAG, "ApiException in startDiscovery", ae)
            _receiverState.value = NearbyReceiverState.Error(
                reason = mapNearbyError(ae, str(R.string.error_nearby_scan_failed)),
                interruptedRecord = activeResumeRecord
            )
        } catch (e: Throwable) {
            Log.e(TAG, "Unexpected error in startDiscovery", e)
            _receiverState.value = NearbyReceiverState.Error(
                reason = mapNearbyError(e, str(R.string.error_nearby_scan_failed)),
                interruptedRecord = activeResumeRecord
            )
        }
    }

    fun restoreInterruptedState(record: InterruptedTransferRecord) {
        activeResumeRecord = record
        receiverPeerName = record.peerName
        _receiverState.value = NearbyReceiverState.Error(
            reason = str(R.string.transfer_resume_restored_reason, record.metadata.appName),
            interruptedRecord = record
        )
    }

    fun clearInterruptedTransfer() {
        receiverCancelledByUser = false
        receiverAcceptedOffer = false
        activeResumeRecord = null
        pendingResumePeerName = null
        cancelIncomingStreamJobs()
        resumeStore.clear(deletePartialFiles = true)
        cleanupPartialReceiverFiles()
        stopAll()
        _receiverState.value = NearbyReceiverState.Idle
    }

    fun stopPreservingPartial() {
        receiverPreservePartialOnStop = true
        if (receiverAcceptedOffer && pendingMetadata != null && receiverDestDir != null) {
            persistReceiverInterruptedSnapshot()
        }
        cancelIncomingStreamJobs()
        stopAll()
    }

    fun connectToSender(localDeviceName: String, sender: DiscoveredSenderDevice) {
        receiverEndpointId = sender.endpointId
        receiverPeerName = sender.deviceName
        _receiverState.value = NearbyReceiverState.WaitingSenderApproval(
            peerName = sender.deviceName,
            authDigits = "..."
        )
        try {
            connectionsClient.requestConnection(
                localDeviceName,
                sender.endpointId,
                receiverConnectionCallback
            ).addOnFailureListener { e ->
                Log.e(TAG, "requestConnection failed", e)
                _receiverState.value = NearbyReceiverState.Error(
                    reason = mapNearbyError(e, str(R.string.error_nearby_connect_failed, sender.deviceName)),
                    interruptedRecord = activeResumeRecord
                )
            }
        } catch (se: SecurityException) {
            Log.e(TAG, "SecurityException in requestConnection", se)
            _receiverState.value = NearbyReceiverState.Error(
                reason = mapNearbyError(se, str(R.string.error_nearby_connect_failed, sender.deviceName)),
                interruptedRecord = activeResumeRecord
            )
        } catch (ise: IllegalStateException) {
            Log.e(TAG, "IllegalStateException in requestConnection", ise)
            _receiverState.value = NearbyReceiverState.Error(
                reason = mapNearbyError(ise, str(R.string.error_nearby_connect_failed, sender.deviceName)),
                interruptedRecord = activeResumeRecord
            )
        } catch (ae: ApiException) {
            Log.e(TAG, "ApiException in requestConnection", ae)
            _receiverState.value = NearbyReceiverState.Error(
                reason = mapNearbyError(ae, str(R.string.error_nearby_connect_failed, sender.deviceName)),
                interruptedRecord = activeResumeRecord
            )
        } catch (e: Throwable) {
            Log.e(TAG, "Error in requestConnection", e)
            _receiverState.value = NearbyReceiverState.Error(
                reason = mapNearbyError(e, str(R.string.error_nearby_connect_failed, sender.deviceName)),
                interruptedRecord = activeResumeRecord
            )
        }
    }

    fun acceptIncomingOffer() {
        val endpointId = receiverEndpointId ?: return
        val metadata = pendingMetadata ?: return

        try {
            storageManager.ensureSpaceOrThrow(metadata.totalSizeBytes)
        } catch (ioe: IOException) {
            declineIncomingOffer()
            _receiverState.value = NearbyReceiverState.Error(ioe.message ?: str(R.string.error_storage_full))
            return
        }

        receiverAcceptedOffer = true
        receiverPaused = false
        receiverWaitingWhilePaused = false
        receiverCancelledByUser = false
        receiverPreservePartialOnStop = false
        lastReceiverUiUpdateMillis = 0L
        receiverStartTimeMillis = System.currentTimeMillis()
        incomingFileBytesByName.clear()
        incomingPayloadBytes.clear()
        incomingCompletedPayloadIds.clear()
        startedStreamPayloads.clear()
        plannedSafeFileNames.clear()

        val destDir = storageManager.createDestinationDir(
            appName = metadata.appName,
            versionName = metadata.versionName,
            isSplitApk = metadata.isSplitApk,
            preserveExisting = true
        )
        receiverDestDir = destDir

        val savedRecord = (activeResumeRecord ?: resumeStore.loadValid())?.takeIf {
            it.mode == InterruptedTransferStore.MODE_NEARBY
        }
        val plan = NearbyResumeCodec.planReceiverFiles(metadata, destDir, savedRecord)
        plannedSafeFileNames.putAll(plan.safeNamesByOriginal)

        synchronized(savedFilesList) {
            savedFilesList.clear()
            savedFilesList.addAll(plan.alreadyCompletedFiles)
        }
        plan.resumeOffsets.forEach { offset ->
            incomingFileBytesByName[offset.fileName] = offset.receivedBytes
        }

        receiverStatusNotice = if (plan.hadInvalidPartial) {
            str(R.string.transfer_resume_file_changed_restart)
        } else {
            null
        }

        val initialBytes = plan.initialReceivedBytes.coerceAtMost(metadata.totalSizeBytes)
        receiverSpeedTracker.reset(receiverStartTimeMillis, initialBytes)

        val initialCompletedCount = plan.alreadyCompletedFiles.size
        val nextFileName = metadata.files.firstOrNull { !plan.completedFileNames.contains(it.fileName) }?.fileName
            ?: metadata.files.firstOrNull()?.fileName.orEmpty()

        _receiverState.value = NearbyReceiverState.Downloading(
            peerName = receiverPeerName,
            metadata = metadata,
            progress = TransferProgressState(
                bytesTransferred = initialBytes,
                totalBytes = metadata.totalSizeBytes,
                speedBytesPerSec = 0L,
                etaSeconds = -1L,
                completedFiles = initialCompletedCount,
                totalFiles = metadata.files.size,
                currentFileName = nextFileName
            ),
            isPaused = false,
            isWaitingWhilePaused = false,
            statusNotice = receiverStatusNotice
        )

        TransferForegroundService.startOrUpdate(
            context,
            str(R.string.notification_receiving_title, metadata.appName),
            str(R.string.notification_starting_download, receiverPeerName),
            progressPercent = if (metadata.totalSizeBytes > 0L) {
                ((initialBytes * 100L) / metadata.totalSizeBytes).toInt().coerceIn(0, 100)
            } else 0,
            indeterminate = false
        )

        val acceptEnvelope = NearbyWireEnvelope(
            kind = "ACCEPT",
            resumeOffsets = plan.resumeOffsets.takeIf { it.isNotEmpty() }
        )
        try {
            connectionsClient.sendPayload(
                endpointId,
                Payload.fromBytes(json.encodeToString(acceptEnvelope).toByteArray(Charsets.UTF_8))
            ).addOnFailureListener { e ->
                Log.e(TAG, "Failed to send ACCEPT", e)
                val interrupted = persistReceiverInterruptedSnapshot()
                TransferForegroundService.stop(context)
                _receiverState.value = NearbyReceiverState.Error(
                    reason = mapNearbyError(e, str(R.string.error_nearby_accept_lost)),
                    interruptedRecord = interrupted
                )
            }
        } catch (se: SecurityException) {
            Log.e(TAG, "SecurityException sending ACCEPT", se)
            val interrupted = persistReceiverInterruptedSnapshot()
            TransferForegroundService.stop(context)
            _receiverState.value = NearbyReceiverState.Error(
                reason = mapNearbyError(se, str(R.string.error_nearby_accept_failed)),
                interruptedRecord = interrupted
            )
        } catch (ise: IllegalStateException) {
            Log.e(TAG, "IllegalStateException sending ACCEPT", ise)
            val interrupted = persistReceiverInterruptedSnapshot()
            TransferForegroundService.stop(context)
            _receiverState.value = NearbyReceiverState.Error(
                reason = mapNearbyError(ise, str(R.string.error_nearby_accept_failed)),
                interruptedRecord = interrupted
            )
        } catch (ae: ApiException) {
            Log.e(TAG, "ApiException sending ACCEPT", ae)
            val interrupted = persistReceiverInterruptedSnapshot()
            TransferForegroundService.stop(context)
            _receiverState.value = NearbyReceiverState.Error(
                reason = mapNearbyError(ae, str(R.string.error_nearby_accept_failed)),
                interruptedRecord = interrupted
            )
        } catch (e: Throwable) {
            Log.e(TAG, "Error sending ACCEPT", e)
            val interrupted = persistReceiverInterruptedSnapshot()
            TransferForegroundService.stop(context)
            _receiverState.value = NearbyReceiverState.Error(
                reason = mapNearbyError(e, str(R.string.error_nearby_accept_lost)),
                interruptedRecord = interrupted
            )
        }

        // Edge case: all files were already completely present on disk
        if (initialCompletedCount >= metadata.files.size && metadata.files.isNotEmpty()) {
            scope.launch {
                val snapshot = synchronized(savedFilesList) { savedFilesList.toList() }
                completeReceiverTransfer(
                    metadata = metadata,
                    snapshotFiles = snapshot,
                    primarySafeName = snapshot.firstOrNull()?.name ?: metadata.appName
                )
            }
        }
    }

    fun declineIncomingOffer() {
        val endpointId = receiverEndpointId
        if (endpointId != null) {
            val decline = NearbyWireEnvelope(kind = "DECLINE")
            runCatching {
                connectionsClient.sendPayload(
                    endpointId,
                    Payload.fromBytes(json.encodeToString(decline).toByteArray(Charsets.UTF_8))
                )
            }.onFailure {
                Log.w(TAG, "Failed to send DECLINE payload", it)
            }
        }
        stopAll()
        _receiverState.value = NearbyReceiverState.Idle
    }

    fun setReceiverPaused(paused: Boolean) {
        applyReceiverPausedState(paused = paused, notifyPeer = true)
    }

    private fun applyReceiverPausedState(paused: Boolean, notifyPeer: Boolean) {
        receiverPaused = paused
        if (!paused) {
            receiverWaitingWhilePaused = false
            receiverSpeedTracker.reset(System.currentTimeMillis(), currentReceiverTransferredBytes())
        } else if (activeIncomingPayloadIds.isEmpty()) {
            receiverWaitingWhilePaused = true
        }
        val endpoint = receiverEndpointId
        if (notifyPeer && !endpoint.isNullOrBlank()) {
            sendControlEnvelope(endpoint, if (paused) "PAUSE" else "RESUME")
        }
        val current = _receiverState.value
        if (current is NearbyReceiverState.Downloading) {
            val waiting = paused && receiverWaitingWhilePaused
            val updatedProgress = if (waiting) {
                current.progress.copy(speedBytesPerSec = 0L, etaSeconds = -1L)
            } else {
                current.progress
            }
            _receiverState.value = current.copy(
                progress = updatedProgress,
                isPaused = paused,
                isWaitingWhilePaused = waiting
            )
        }
    }

    fun cancelActiveTransferByReceiver() {
        val peer = receiverPeerName
        val endpoint = receiverEndpointId
        receiverCancelledByUser = true
        receiverAcceptedOffer = false
        receiverPaused = false
        receiverWaitingWhilePaused = false
        activeResumeRecord = null
        cancelIncomingStreamJobs()
        if (clientInitialized) {
            activeIncomingPayloadIds.toList().forEach { payloadId ->
                runCatching { connectionsClient.cancelPayload(payloadId) }
            }
            if (!endpoint.isNullOrBlank()) {
                sendControlEnvelope(endpoint, "CANCEL")
            }
        }
        activeIncomingPayloadIds.clear()
        cleanupPartialReceiverFiles()
        resumeStore.clear(deletePartialFiles = true)
        stopAll()
        _receiverState.value = NearbyReceiverState.Cancelled(peerName = peer)
    }

    private fun handleRemoteCancelOnReceiver() {
        val peer = receiverPeerName
        val wasDownloading = _receiverState.value is NearbyReceiverState.Downloading
        receiverAcceptedOffer = false
        receiverPaused = false
        receiverWaitingWhilePaused = false
        cancelIncomingStreamJobs()
        if (clientInitialized) {
            activeIncomingPayloadIds.toList().forEach { payloadId ->
                runCatching { connectionsClient.cancelPayload(payloadId) }
            }
        }
        activeIncomingPayloadIds.clear()

        // If the OTHER side cancelled mid-transfer, preserve any .part bytes so user can Resume
        val interrupted = if (wasDownloading) persistReceiverInterruptedSnapshot() else null
        if (interrupted != null) {
            activeResumeRecord = interrupted
            stopAll()
            _receiverState.value = NearbyReceiverState.Error(
                reason = str(R.string.error_nearby_download_interrupted),
                interruptedRecord = interrupted
            )
        } else {
            receiverCancelledByUser = true
            cleanupPartialReceiverFiles()
            stopAll()
            _receiverState.value = NearbyReceiverState.Cancelled(peerName = peer)
        }
    }

    private fun handleReceiverInterrupted(reason: String) {
        if (receiverCancelledByUser || receiverPreservePartialOnStop) return
        receiverAcceptedOffer = false
        cancelIncomingStreamJobs()
        val interrupted = persistReceiverInterruptedSnapshot()
        if (interrupted != null) {
            activeResumeRecord = interrupted
        } else {
            // Nothing partial written on disk; clean empty staging safely
            closeIncomingPayloadHandles()
        }
        TransferForegroundService.stop(context)
        _receiverState.value = NearbyReceiverState.Error(
            reason = reason,
            interruptedRecord = interrupted ?: activeResumeRecord
        )
    }

    private fun persistReceiverInterruptedSnapshot(): InterruptedTransferRecord? {
        val metadata = pendingMetadata ?: return null
        val destDir = receiverDestDir ?: return null
        val fileRecords = metadata.files.map { entry ->
            val safeName = plannedSafeFileNames[entry.fileName]
                ?: if (metadata.isGenericFile) {
                    FileTransferHelper.sanitizeFileNamePreservingExtension(entry.fileName)
                } else {
                    FormatUtils.sanitizeFileName(entry.fileName.removeSuffix(".apk")) + ".apk"
                }
            val finalFile = File(destDir, safeName)
            val partFile = File(destDir, "$safeName.part")
            when {
                finalFile.exists() && entry.sizeBytes > 0L && finalFile.length() == entry.sizeBytes -> {
                    InterruptedFileRecord(
                        fileName = entry.fileName,
                        safeFileName = safeName,
                        totalSizeBytes = entry.sizeBytes,
                        receivedBytes = entry.sizeBytes,
                        completed = true
                    )
                }
                partFile.exists() -> {
                    InterruptedFileRecord(
                        fileName = entry.fileName,
                        safeFileName = safeName,
                        totalSizeBytes = entry.sizeBytes,
                        receivedBytes = partFile.length(),
                        completed = false
                    )
                }
                else -> {
                    InterruptedFileRecord(
                        fileName = entry.fileName,
                        safeFileName = safeName,
                        totalSizeBytes = entry.sizeBytes,
                        receivedBytes = 0L,
                        completed = false
                    )
                }
            }
        }
        val totalReceived = fileRecords.sumOf { it.receivedBytes }
        val allCompleted = fileRecords.isNotEmpty() && fileRecords.all { it.completed }
        if (totalReceived <= 0L || allCompleted) {
            return null
        }
        val record = InterruptedTransferRecord(
            mode = InterruptedTransferStore.MODE_NEARBY,
            peerName = receiverPeerName,
            senderPairCode = NearbyResumeCodec.extractPairCode(receiverPeerName),
            metadata = metadata,
            destDirPath = destDir.absolutePath,
            files = fileRecords,
            updatedAtMillis = System.currentTimeMillis()
        )
        resumeStore.save(record)
        return record
    }

    private fun closeIncomingPayloadHandles() {
        incomingFilePayloads.values.forEach { payload ->
            runCatching { payload.asFile()?.close() }
        }
        incomingFilePayloads.clear()
        incomingStreamPayloads.values.forEach { payload ->
            runCatching { payload.asStream()?.asInputStream()?.close() }
        }
        incomingStreamPayloads.clear()
        startedStreamPayloads.clear()
    }

    private fun cancelIncomingStreamJobs() {
        incomingStreamJobs.values.forEach { it.cancel() }
        incomingStreamJobs.clear()
        closeIncomingPayloadHandles()
    }

    private fun cleanupPartialReceiverFiles() {
        cancelIncomingStreamJobs()
        val destDir = receiverDestDir
        receiverDestDir = null
        if (destDir != null && destDir.exists()) {
            // Delete any .part files we planned, and only delete split-APK subfolders (never Downloads/APK Share root)
            plannedSafeFileNames.values.forEach { safeName ->
                val partFile = File(destDir, "$safeName.part")
                if (partFile.exists()) runCatching { partFile.delete() }
            }
            if (destDir.absolutePath != storageManager.getAppDownloadsRoot().absolutePath) {
                runCatching { destDir.deleteRecursively() }
            }
        }
        synchronized(savedFilesList) {
            savedFilesList.forEach { file ->
                if (file.exists()) runCatching { file.delete() }
            }
            savedFilesList.clear()
        }
    }

    fun cancelReceiver() {
        receiverCancelledByUser = false
        receiverPaused = false
        receiverWaitingWhilePaused = false
        receiverAcceptedOffer = false
        cleanupPartialReceiverFiles()
        stopAll()
        _receiverState.value = NearbyReceiverState.Idle
    }

    fun setQrPairTarget(localDeviceName: String, pairCode: String, deviceName: String?) {
        val cleanCode = pairCode.trim().uppercase()
        val label = if (!deviceName.isNullOrBlank()) "$deviceName (#$cleanCode)" else "#$cleanCode"
        pendingQrTargetCode = cleanCode
        pendingQrTargetLabel = label
        pendingQrLocalDeviceName = localDeviceName

        val existingMatch = discoveredDevicesMap.values.firstOrNull { device ->
            matchesQrTarget(device.deviceName, cleanCode, deviceName)
        }
        if (existingMatch != null) {
            Log.i(TAG, "Matched already-discovered sender via QR: ${existingMatch.deviceName}")
            pendingQrTargetCode = null
            pendingQrTargetLabel = null
            connectToSender(localDeviceName, existingMatch)
        } else {
            val current = _receiverState.value
            if (current is NearbyReceiverState.Discovering) {
                _receiverState.value = current.copy(qrTargetLabel = label)
            }
        }
    }

    fun clearQrPairTarget() {
        pendingQrTargetCode = null
        pendingQrTargetLabel = null
        pendingResumePeerName = null
        val current = _receiverState.value
        if (current is NearbyReceiverState.Discovering) {
            _receiverState.value = current.copy(qrTargetLabel = null)
        }
    }

    private fun matchesQrTarget(endpointName: String, targetCode: String?, targetName: String?): Boolean {
        if (!targetCode.isNullOrBlank() && endpointName.contains("#$targetCode", ignoreCase = true)) {
            return true
        }
        if (!targetName.isNullOrBlank()) {
            if (endpointName.equals(targetName, ignoreCase = true)) return true
            val epBase = NearbyResumeCodec.stripPairCode(endpointName)
            val targetBase = NearbyResumeCodec.stripPairCode(targetName)
            if (epBase.isNotBlank() && epBase.equals(targetBase, ignoreCase = true)) return true
        }
        return false
    }

    private val receiverDiscoveryCallback = object : EndpointDiscoveryCallback() {
        override fun onEndpointFound(endpointId: String, info: DiscoveredEndpointInfo) {
            Log.i(TAG, "Discovered sender: ${info.endpointName} ($endpointId)")
            val device = DiscoveredSenderDevice(endpointId, info.endpointName)
            discoveredDevicesMap[endpointId] = device

            val targetCode = pendingQrTargetCode
            val resumePeer = pendingResumePeerName
            if ((targetCode != null || resumePeer != null) &&
                matchesQrTarget(info.endpointName, targetCode, resumePeer ?: pendingQrTargetLabel)
            ) {
                Log.i(TAG, "Auto-connecting to matched sender: ${info.endpointName}")
                pendingQrTargetCode = null
                pendingQrTargetLabel = null
                pendingResumePeerName = null
                connectToSender(pendingQrLocalDeviceName, device)
                return
            }

            val current = _receiverState.value
            if (current is NearbyReceiverState.Discovering) {
                _receiverState.value = NearbyReceiverState.Discovering(
                    devices = discoveredDevicesMap.values.toList(),
                    qrTargetLabel = pendingQrTargetLabel
                )
            }
        }

        override fun onEndpointLost(endpointId: String) {
            discoveredDevicesMap.remove(endpointId)
            val current = _receiverState.value
            if (current is NearbyReceiverState.Discovering) {
                _receiverState.value = NearbyReceiverState.Discovering(
                    devices = discoveredDevicesMap.values.toList(),
                    qrTargetLabel = pendingQrTargetLabel
                )
            }
        }
    }

    private val receiverConnectionCallback = object : ConnectionLifecycleCallback() {
        override fun onConnectionInitiated(endpointId: String, info: ConnectionInfo) {
            receiverPeerName = info.endpointName
            _receiverState.value = NearbyReceiverState.WaitingSenderApproval(
                peerName = info.endpointName,
                authDigits = info.authenticationDigits
            )
            try {
                connectionsClient.acceptConnection(endpointId, receiverPayloadCallback)
                    .addOnFailureListener { e ->
                        Log.e(TAG, "Receiver acceptConnection failed", e)
                        _receiverState.value = NearbyReceiverState.Error(
                            reason = mapNearbyError(e, str(R.string.error_nearby_accept_connection_from, info.endpointName)),
                            interruptedRecord = activeResumeRecord
                        )
                    }
            } catch (se: SecurityException) {
                Log.e(TAG, "SecurityException in receiver acceptConnection", se)
                _receiverState.value = NearbyReceiverState.Error(
                    reason = mapNearbyError(se, str(R.string.error_nearby_accept_connection_from, info.endpointName)),
                    interruptedRecord = activeResumeRecord
                )
            } catch (ise: IllegalStateException) {
                Log.e(TAG, "IllegalStateException in receiver acceptConnection", ise)
                _receiverState.value = NearbyReceiverState.Error(
                    reason = mapNearbyError(ise, str(R.string.error_nearby_accept_connection_from, info.endpointName)),
                    interruptedRecord = activeResumeRecord
                )
            } catch (ae: ApiException) {
                Log.e(TAG, "ApiException in receiver acceptConnection", ae)
                _receiverState.value = NearbyReceiverState.Error(
                    reason = mapNearbyError(ae, str(R.string.error_nearby_accept_connection_from, info.endpointName)),
                    interruptedRecord = activeResumeRecord
                )
            } catch (e: Throwable) {
                Log.e(TAG, "Error in receiver acceptConnection", e)
                _receiverState.value = NearbyReceiverState.Error(
                    reason = mapNearbyError(e, str(R.string.error_nearby_accept_connection_from, info.endpointName)),
                    interruptedRecord = activeResumeRecord
                )
            }
        }

        override fun onConnectionResult(endpointId: String, result: ConnectionResolution) {
            if (result.status.statusCode == ConnectionsStatusCodes.STATUS_OK) {
                Log.i(TAG, "Receiver connected to $endpointId; waiting for metadata")
                runCatching { connectionsClient.stopDiscovery() }
                receiverTimeoutJob?.cancel()
                receiverTimeoutJob = scope.launch {
                    delay(20_000L)
                    if (pendingMetadata == null && _receiverState.value is NearbyReceiverState.WaitingSenderApproval) {
                        _receiverState.value = NearbyReceiverState.Error(
                            reason = str(R.string.error_nearby_sender_timeout),
                            interruptedRecord = activeResumeRecord
                        )
                    }
                }
            } else {
                val reason = if (result.status.statusCode == ConnectionsStatusCodes.STATUS_CONNECTION_REJECTED) {
                    str(R.string.error_nearby_sender_declined, receiverPeerName)
                } else {
                    str(R.string.error_nearby_connect_failed_code, receiverPeerName, result.status.statusCode)
                }
                _receiverState.value = NearbyReceiverState.Error(
                    reason = reason,
                    interruptedRecord = activeResumeRecord
                )
            }
        }

        override fun onDisconnected(endpointId: String) {
            Log.w(TAG, "Receiver disconnected from $endpointId")
            if (receiverCancelledByUser || receiverPreservePartialOnStop) return
            val current = _receiverState.value
            if (current is NearbyReceiverState.Downloading ||
                current is NearbyReceiverState.IncomingOffer ||
                current is NearbyReceiverState.WaitingSenderApproval
            ) {
                handleReceiverInterrupted(
                    str(R.string.error_nearby_connection_lost_receiver, receiverPeerName)
                )
            }
        }
    }

    private fun metadataMatchesActiveResume(
        incoming: ApkBundleMetadata,
        resumeRecord: InterruptedTransferRecord
    ): Boolean {
        val savedNames = resumeRecord.files.mapTo(HashSet()) { it.fileName }
        return incoming.appName == resumeRecord.metadata.appName &&
            incoming.files.any { savedNames.contains(it.fileName) }
    }

    private val receiverPayloadCallback = object : PayloadCallback() {
        override fun onPayloadReceived(endpointId: String, payload: Payload) {
            when (payload.type) {
                Payload.Type.BYTES -> {
                    val raw = payload.asBytes()?.toString(Charsets.UTF_8) ?: return
                    val envelope = runCatching { json.decodeFromString<NearbyWireEnvelope>(raw) }.getOrNull() ?: return
                    when (envelope.kind) {
                        "METADATA" -> {
                            receiverTimeoutJob?.cancel()
                            val meta = envelope.metadata ?: return
                            pendingMetadata = meta
                            Log.i(TAG, "Received metadata for ${meta.appName} (${meta.files.size} files)")
                            val resumeRec = activeResumeRecord
                            if (resumeRec != null && metadataMatchesActiveResume(meta, resumeRec)) {
                                // Seamlessly continue the interrupted transfer the user asked to resume
                                acceptIncomingOffer()
                            } else {
                                _receiverState.value = NearbyReceiverState.IncomingOffer(
                                    endpointId = endpointId,
                                    peerName = receiverPeerName,
                                    metadata = meta
                                )
                            }
                        }
                        "FILE_MAP" -> {
                            val mapping = envelope.fileMapping ?: return
                            incomingFileMappings[mapping.payloadId] = mapping
                            if (mapping.restartedFromZero) {
                                receiverStatusNotice = str(R.string.transfer_resume_file_changed_restart)
                            }
                            if (incomingStreamPayloads.containsKey(mapping.payloadId)) {
                                startStreamReceiveIfReady(mapping.payloadId)
                            } else if (incomingCompletedPayloadIds.contains(mapping.payloadId)) {
                                flushCompletedFileIfReady(mapping.payloadId)
                            }
                        }
                        "PAUSE" -> {
                            Log.i(TAG, "Sender paused queue")
                            applyReceiverPausedState(paused = true, notifyPeer = false)
                        }
                        "RESUME" -> {
                            Log.i(TAG, "Sender resumed queue")
                            applyReceiverPausedState(paused = false, notifyPeer = false)
                        }
                        "CANCEL" -> {
                            Log.i(TAG, "Sender cancelled active transfer")
                            handleRemoteCancelOnReceiver()
                        }
                    }
                }
                Payload.Type.STREAM -> {
                    if (receiverCancelledByUser) {
                        runCatching { payload.asStream()?.asInputStream()?.close() }
                        return
                    }
                    receiverWaitingWhilePaused = false
                    activeIncomingPayloadIds.add(payload.id)
                    incomingStreamPayloads[payload.id] = payload
                    if (incomingFileMappings.containsKey(payload.id)) {
                        startStreamReceiveIfReady(payload.id)
                    }
                }
                Payload.Type.FILE -> {
                    if (receiverCancelledByUser) {
                        runCatching { payload.asFile()?.close() }
                        return
                    }
                    receiverWaitingWhilePaused = false
                    activeIncomingPayloadIds.add(payload.id)
                    incomingFilePayloads[payload.id] = payload
                    incomingPayloadBytes[payload.id] = 0L
                }
            }
        }

        override fun onPayloadTransferUpdate(endpointId: String, update: PayloadTransferUpdate) {
            val metadata = pendingMetadata ?: return
            val isStream = incomingStreamPayloads.containsKey(update.payloadId) ||
                startedStreamPayloads.contains(update.payloadId)
            val isFile = incomingFilePayloads.containsKey(update.payloadId)
            if (!isStream && !isFile) return

            if (isStream) {
                // Stream progress & completion are driven directly by receiveStreamIntoPartFile()
                when (update.status) {
                    PayloadTransferUpdate.Status.FAILURE -> {
                        activeIncomingPayloadIds.remove(update.payloadId)
                        if (!receiverCancelledByUser && _receiverState.value is NearbyReceiverState.Downloading) {
                            handleReceiverInterrupted(str(R.string.error_nearby_download_interrupted))
                        }
                    }
                    PayloadTransferUpdate.Status.CANCELED -> {
                        activeIncomingPayloadIds.remove(update.payloadId)
                        if (!receiverCancelledByUser && _receiverState.value is NearbyReceiverState.Downloading) {
                            handleRemoteCancelOnReceiver()
                        }
                    }
                    else -> Unit
                }
                return
            }

            // Fallback Payload.Type.FILE handling (backward compatibility)
            when (update.status) {
                PayloadTransferUpdate.Status.IN_PROGRESS -> {
                    if (receiverCancelledByUser) return
                    val mapping = incomingFileMappings[update.payloadId]
                    if (mapping != null) {
                        incomingFileBytesByName[mapping.fileName] = update.bytesTransferred
                    } else {
                        incomingPayloadBytes[update.payloadId] = update.bytesTransferred
                    }
                    updateReceiverProgress(metadata)
                }
                PayloadTransferUpdate.Status.SUCCESS -> {
                    activeIncomingPayloadIds.remove(update.payloadId)
                    if (receiverCancelledByUser) {
                        runCatching { incomingFilePayloads.remove(update.payloadId)?.asFile()?.close() }
                        return
                    }
                    val finalBytes = update.totalBytes.coerceAtLeast(
                        incomingPayloadBytes[update.payloadId] ?: 0L
                    )
                    val mapping = incomingFileMappings[update.payloadId]
                    if (mapping != null) {
                        incomingFileBytesByName[mapping.fileName] = finalBytes
                    } else {
                        incomingPayloadBytes[update.payloadId] = finalBytes
                    }
                    incomingCompletedPayloadIds.add(update.payloadId)
                    if (receiverPaused && activeIncomingPayloadIds.isEmpty()) {
                        receiverWaitingWhilePaused = true
                    }
                    updateReceiverProgress(metadata, forceEmit = true)
                    flushCompletedFileIfReady(update.payloadId)
                }
                PayloadTransferUpdate.Status.FAILURE -> {
                    activeIncomingPayloadIds.remove(update.payloadId)
                    if (receiverCancelledByUser) return
                    handleReceiverInterrupted(str(R.string.error_nearby_download_interrupted))
                }
                PayloadTransferUpdate.Status.CANCELED -> {
                    activeIncomingPayloadIds.remove(update.payloadId)
                    if (receiverCancelledByUser) return
                    handleRemoteCancelOnReceiver()
                }
            }
        }
    }

    private val savedFilesList = mutableListOf<File>()

    @Synchronized
    private fun startStreamReceiveIfReady(payloadId: Long) {
        if (!receiverAcceptedOffer || receiverCancelledByUser) return
        if (!startedStreamPayloads.add(payloadId)) return
        val metadata = pendingMetadata ?: return
        val payload = incomingStreamPayloads[payloadId] ?: return
        val mapping = incomingFileMappings[payloadId] ?: return
        val destDir = receiverDestDir ?: return

        val job = scope.launch {
            try {
                val safeName = plannedSafeFileNames[mapping.fileName]
                    ?: if (metadata.isGenericFile) {
                        FileTransferHelper.sanitizeFileNamePreservingExtension(mapping.fileName)
                    } else {
                        FormatUtils.sanitizeFileName(mapping.fileName.removeSuffix(".apk")) + ".apk"
                    }
                plannedSafeFileNames[mapping.fileName] = safeName

                val inputStream = payload.asStream()?.asInputStream()
                    ?: throw IOException("Nearby stream unavailable")

                val outcome = inputStream.use { stream ->
                    NearbyResumeCodec.receiveStreamIntoPartFile(
                        input = stream,
                        destDir = destDir,
                        safeName = safeName,
                        expectedTotalSize = mapping.fileSize,
                        offsetBytes = mapping.offsetBytes,
                        restartedFromZero = mapping.restartedFromZero,
                        isCancelled = { receiverCancelledByUser || receiverPreservePartialOnStop },
                        onProgress = { totalFileBytes ->
                            incomingFileBytesByName[mapping.fileName] = totalFileBytes
                            updateReceiverProgress(metadata)
                        },
                        onPersistChunk = {
                            persistReceiverInterruptedSnapshot()
                        }
                    )
                }

                activeIncomingPayloadIds.remove(payloadId)
                incomingStreamPayloads.remove(payloadId)

                when (outcome) {
                    is StreamReceiveOutcome.Completed -> {
                        incomingFileBytesByName[mapping.fileName] = outcome.finalFile.length()
                        incomingCompletedPayloadIds.add(payloadId)
                        if (receiverPaused && activeIncomingPayloadIds.isEmpty()) {
                            receiverWaitingWhilePaused = true
                        }
                        val totalSaved: Int
                        val snapshotFiles: List<File>
                        synchronized(savedFilesList) {
                            if (savedFilesList.none { it.absolutePath == outcome.finalFile.absolutePath }) {
                                savedFilesList += outcome.finalFile
                            }
                            totalSaved = savedFilesList.size
                            snapshotFiles = savedFilesList.toList()
                        }
                        updateReceiverProgress(metadata, forceEmit = true)
                        if (totalSaved >= metadata.files.size && !receiverCancelledByUser) {
                            completeReceiverTransfer(metadata, snapshotFiles, safeName)
                        } else {
                            // Persist progress between split files
                            persistReceiverInterruptedSnapshot()
                        }
                    }
                    is StreamReceiveOutcome.Interrupted -> {
                        if (!receiverCancelledByUser && !receiverPreservePartialOnStop) {
                            handleReceiverInterrupted(str(R.string.error_nearby_download_interrupted))
                        }
                    }
                    is StreamReceiveOutcome.Cancelled -> Unit
                    is StreamReceiveOutcome.InvalidPartialRestart,
                    is StreamReceiveOutcome.CorruptOversized -> {
                        if (!receiverCancelledByUser) {
                            receiverStatusNotice = str(R.string.transfer_resume_file_changed_restart)
                            handleReceiverInterrupted(str(R.string.transfer_resume_file_changed_restart))
                        }
                    }
                }
            } catch (t: Throwable) {
                activeIncomingPayloadIds.remove(payloadId)
                incomingStreamPayloads.remove(payloadId)
                if (!receiverCancelledByUser && !receiverPreservePartialOnStop &&
                    t !is kotlinx.coroutines.CancellationException
                ) {
                    Log.e(TAG, "Error receiving Nearby stream", t)
                    handleReceiverInterrupted(t.message ?: str(R.string.error_nearby_download_interrupted))
                }
            } finally {
                incomingStreamJobs.remove(payloadId)
            }
        }
        incomingStreamJobs[payloadId] = job
    }

    @Synchronized
    private fun flushCompletedFileIfReady(payloadId: Long) {
        if (!receiverAcceptedOffer || receiverCancelledByUser) return
        val metadata = pendingMetadata ?: return
        val payload = incomingFilePayloads[payloadId] ?: return
        val mapping = incomingFileMappings[payloadId] ?: return
        val destDir = receiverDestDir ?: return

        scope.launch {
            var partFile: File? = null
            try {
                if (receiverCancelledByUser || !receiverAcceptedOffer) {
                    runCatching { payload.asFile()?.close() }
                    return@launch
                }
                val safeName = plannedSafeFileNames[mapping.fileName]
                    ?: if (metadata.isGenericFile) {
                        val clean = FileTransferHelper.sanitizeFileNamePreservingExtension(mapping.fileName)
                        val (base, ext) = FileTransferHelper.splitBaseAndExtension(clean)
                        FileTransferHelper.resolveUniqueGenericFileName(base, ext) { candidate ->
                            File(destDir, candidate).exists()
                        }
                    } else {
                        FormatUtils.sanitizeFileName(mapping.fileName.removeSuffix(".apk")) + ".apk"
                    }
                val tempPart = File(destDir, "$safeName.part")
                partFile = tempPart
                val outFile = File(destDir, safeName)
                val payloadFile = payload.asFile()
                val uri = payloadFile?.asUri()

                if (uri != null) {
                    context.contentResolver.openInputStream(uri)?.use { input ->
                        tempPart.outputStream().use { output ->
                            input.copyTo(output, bufferSize = 65536)
                        }
                    } ?: throw IOException("Unable to read received payload URI.")
                } else {
                    val pfd: ParcelFileDescriptor? = payloadFile?.asParcelFileDescriptor()
                    if (pfd != null) {
                        FileInputStream(pfd.fileDescriptor).use { input ->
                            tempPart.outputStream().use { output ->
                                input.copyTo(output, bufferSize = 65536)
                            }
                        }
                    } else {
                        @Suppress("DEPRECATION")
                        val legacyFile = payloadFile?.asJavaFile()
                            ?: throw IOException("Payload file unavailable.")
                        legacyFile.inputStream().use { input ->
                            tempPart.outputStream().use { output ->
                                input.copyTo(output, bufferSize = 65536)
                            }
                        }
                    }
                }
                runCatching { payloadFile?.close() }
                incomingFilePayloads.remove(payloadId)

                if (receiverCancelledByUser || !receiverAcceptedOffer) {
                    runCatching { tempPart.delete() }
                    return@launch
                }

                if (mapping.fileSize > 0L && tempPart.length() != mapping.fileSize) {
                    if (tempPart.length() > mapping.fileSize) {
                        runCatching { tempPart.delete() }
                    }
                    handleReceiverInterrupted(str(R.string.error_nearby_download_interrupted))
                    return@launch
                }

                if (outFile.exists()) outFile.delete()
                if (!tempPart.renameTo(outFile)) {
                    tempPart.copyTo(outFile, overwrite = true)
                    tempPart.delete()
                }
                partFile = null

                val totalSaved: Int
                val snapshotFiles: List<File>
                synchronized(savedFilesList) {
                    savedFilesList += outFile
                    totalSaved = savedFilesList.size
                    snapshotFiles = savedFilesList.toList()
                }

                if (totalSaved >= metadata.files.size && !receiverCancelledByUser) {
                    completeReceiverTransfer(metadata, snapshotFiles, safeName)
                }
            } catch (t: Throwable) {
                if (!receiverCancelledByUser) {
                    Log.e(TAG, "Error saving received APK file", t)
                    if (partFile != null && partFile.exists() && partFile.length() > mapping.fileSize) {
                        runCatching { partFile.delete() }
                    }
                    handleReceiverInterrupted(t.message ?: str(R.string.error_nearby_save_failed))
                }
            }
        }
    }

    private suspend fun completeReceiverTransfer(
        metadata: ApkBundleMetadata,
        snapshotFiles: List<File>,
        primarySafeName: String
    ) {
        resumeStore.clear(deletePartialFiles = false)
        activeResumeRecord = null
        val displayPath = storageManager.exportCopiesToPublicDownloads(
            files = snapshotFiles,
            appName = metadata.appName,
            versionName = metadata.versionName,
            isSplitApk = metadata.isSplitApk,
            enabled = exportToPublicDownloads,
            mimeType = if (metadata.isGenericFile) {
                metadata.mimeType ?: FileTransferHelper.guessMimeTypeFromFileName(primarySafeName)
            } else {
                null
            }
        )
        val elapsed = (System.currentTimeMillis() - receiverStartTimeMillis).coerceAtLeast(500L)
        TransferForegroundService.stop(context)
        NotificationHelper.showResultNotification(
            context,
            NotificationHelper.NOTIFICATION_ID_RESULT,
            str(R.string.notification_downloaded_title, metadata.appName),
            str(R.string.notification_downloaded_body, displayPath)
        )
        val endpoint = receiverEndpointId
        if (!endpoint.isNullOrBlank()) {
            runCatching { connectionsClient.disconnectFromEndpoint(endpoint) }
        }
        receiverDestDir = null
        _receiverState.value = NearbyReceiverState.Completed(
            metadata = metadata,
            savedFiles = snapshotFiles,
            displayLocation = displayPath,
            peerName = receiverPeerName,
            durationMillis = elapsed
        )
    }

    private fun currentReceiverTransferredBytes(): Long {
        val byNameSum = incomingFileBytesByName.values.sum()
        val byPayloadSum = incomingPayloadBytes.values.sum()
        return maxOf(byNameSum, byPayloadSum)
    }

    private fun updateReceiverProgress(metadata: ApkBundleMetadata, forceEmit: Boolean = false) {
        if (!receiverAcceptedOffer || receiverCancelledByUser) return
        val now = System.currentTimeMillis()
        val receivedBytes = currentReceiverTransferredBytes().coerceAtMost(metadata.totalSizeBytes)
        val rollingSpeed = if (receiverWaitingWhilePaused) {
            0L
        } else {
            receiverSpeedTracker.record(receivedBytes, now)
        }
        val completedCount = maxOf(
            incomingCompletedPayloadIds.size,
            synchronized(savedFilesList) { savedFilesList.size }
        )
        val minUiInterval = if (powerSaverEnabled) 1000L else FormatUtils.LIVE_STATS_UPDATE_INTERVAL_MILLIS
        if (!forceEmit &&
            completedCount < metadata.files.size &&
            now - lastReceiverUiUpdateMillis < minUiInterval
        ) {
            return
        }
        lastReceiverUiUpdateMillis = now

        val elapsedMs = (now - receiverStartTimeMillis).coerceAtLeast(1L)
        val remaining = (metadata.totalSizeBytes - receivedBytes).coerceAtLeast(0L)
        val effectiveSpeed = if (receiverWaitingWhilePaused) 0L else rollingSpeed
        val etaSec = FormatUtils.computeEtaSeconds(remaining, effectiveSpeed)
        val currentName = metadata.files.getOrNull(completedCount.coerceAtMost(metadata.files.lastIndex))?.fileName.orEmpty()

        val progress = TransferProgressState(
            bytesTransferred = receivedBytes,
            totalBytes = metadata.totalSizeBytes,
            speedBytesPerSec = effectiveSpeed,
            etaSeconds = etaSec,
            currentFileName = currentName,
            completedFiles = completedCount,
            totalFiles = metadata.files.size,
            elapsedMillis = elapsedMs
        )
        _receiverState.value = NearbyReceiverState.Downloading(
            peerName = receiverPeerName,
            metadata = metadata,
            progress = progress,
            isPaused = receiverPaused,
            isWaitingWhilePaused = receiverWaitingWhilePaused,
            statusNotice = receiverStatusNotice
        )

        if (now - lastNotificationUpdateMillis > updateIntervalMs) {
            lastNotificationUpdateMillis = now
            TransferForegroundService.startOrUpdate(
                context = context,
                title = str(
                    R.string.notification_downloading_progress,
                    metadata.appName,
                    progress.progressPercent
                ),
                text = "${FormatUtils.formatBytes(receivedBytes)} / ${FormatUtils.formatBytes(metadata.totalSizeBytes)} • ${FormatUtils.formatLiveSpeed(effectiveSpeed)}",
                progressPercent = progress.progressPercent,
                indeterminate = false
            )
        }
    }

    fun stopAll() {
        receiverTimeoutJob?.cancel()
        senderTransferJob?.cancel()
        senderTransferJob = null
        completeAllOutgoingWaiters(false)
        cancelIncomingStreamJobs()
        if (clientInitialized) {
            runCatching { connectionsClient.stopAdvertising() }
            runCatching { connectionsClient.stopDiscovery() }
            runCatching { connectionsClient.stopAllEndpoints() }
        }
        TransferForegroundService.stop(context)
        synchronized(savedFilesList) { savedFilesList.clear() }
        pendingQrTargetCode = null
        pendingQrTargetLabel = null
        pendingResumePeerName = null
        senderEndpointId = null
        receiverEndpointId = null
    }

    internal fun setSenderStateForTest(state: NearbySenderState) {
        _senderState.value = state
    }

    internal fun setReceiverStateForTest(state: NearbyReceiverState) {
        _receiverState.value = state
    }

    private fun buildConnectivityError(status: ConnectivityStatus): String {
        return when {
            !status.isBluetoothEnabled && !status.isWifiEnabled ->
                str(R.string.error_nearby_bt_and_wifi_off)
            !status.isBluetoothEnabled ->
                str(R.string.error_nearby_bt_off)
            else ->
                str(R.string.error_nearby_wifi_off)
        }
    }

    private fun mapNearbyError(e: Throwable, prefix: String): String {
        if (e is SecurityException) {
            return str(R.string.error_nearby_map_security, prefix)
        }
        if (e is IllegalStateException) {
            return str(R.string.error_nearby_map_illegal_state, prefix)
        }
        val msg = e.message.orEmpty()
        val statusCode = (e as? ApiException)?.statusCode
        return when {
            statusCode == ConnectionsStatusCodes.STATUS_BLUETOOTH_ERROR ||
                msg.contains("8007", ignoreCase = true) ||
                msg.contains("BLUETOOTH", ignoreCase = true) ->
                str(R.string.error_nearby_map_bluetooth, prefix)
            statusCode == 8034 ||
                msg.contains("8034", ignoreCase = true) ||
                msg.contains("MISSING_PERMISSION", ignoreCase = true) ->
                str(R.string.error_nearby_map_security, prefix)
            statusCode == 16 ||
                statusCode == ConnectionsStatusCodes.API_NOT_CONNECTED ||
                msg.contains("API_UNAVAILABLE", ignoreCase = true) ->
                str(R.string.error_nearby_map_unavailable, prefix)
            else -> str(
                R.string.error_nearby_map_generic,
                prefix,
                msg.ifBlank { str(R.string.error_nearby_map_fallback_hint) }
            )
        }
    }

    companion object {
        private const val TAG = "NearbyTransferMgr"
        const val SERVICE_ID = "com.apkshare.transfer"
        private val STRATEGY = Strategy.P2P_STAR
    }
}
