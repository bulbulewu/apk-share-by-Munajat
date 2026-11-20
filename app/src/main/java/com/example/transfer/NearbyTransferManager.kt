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
import com.example.model.TransferProgressState
import com.example.service.NotificationHelper
import com.example.service.TransferForegroundService
import com.example.util.ConnectivityStatus
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
        val progress: TransferProgressState
    ) : NearbySenderState

    data class Success(
        val bundle: ExtractedApkBundle,
        val peerName: String,
        val durationMillis: Long
    ) : NearbySenderState

    data class Error(
        val reason: String,
        val bundle: ExtractedApkBundle?
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
        val progress: TransferProgressState
    ) : NearbyReceiverState

    data class Completed(
        val metadata: ApkBundleMetadata,
        val savedFiles: List<File>,
        val displayLocation: String,
        val peerName: String,
        val durationMillis: Long
    ) : NearbyReceiverState

    data class Error(
        val reason: String
    ) : NearbyReceiverState
}

class NearbyTransferManager(
    private val context: Context,
    private val storageManager: ApkStorageManager
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
    private val outgoingPayloadCompleted = ConcurrentHashMap<Long, Boolean>()
    private var expectedOutgoingFileCount: Int = 0
    private var pendingOutgoingMetaPayloadId: Long = -1L

    // Active receiver session tracking
    private val discoveredDevicesMap = LinkedHashMap<String, DiscoveredSenderDevice>()
    private var receiverEndpointId: String? = null
    private var receiverPeerName: String = "Sender"
    private var pendingQrTargetCode: String? = null
    private var pendingQrTargetLabel: String? = null
    private var pendingQrLocalDeviceName: String = android.os.Build.MODEL
    private var pendingMetadata: ApkBundleMetadata? = null
    private var receiverAcceptedOffer: Boolean = false
    private var receiverStartTimeMillis: Long = 0L
    private var receiverTimeoutJob: Job? = null
    private val incomingFileMappings = ConcurrentHashMap<Long, NearbyFileMapping>()
    private val incomingFilePayloads = ConcurrentHashMap<Long, Payload>()
    private val incomingPayloadBytes = ConcurrentHashMap<Long, Long>()
    private val incomingCompletedPayloadIds = ConcurrentHashMap.newKeySet<Long>()
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
        outgoingPayloadBytes.clear()
        outgoingPayloadCompleted.clear()
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
                TransferForegroundService.stop(context)
                _senderState.value = NearbySenderState.Error(
                    reason = mapNearbyError(e, str(R.string.error_nearby_start_sharing)),
                    bundle = bundle
                )
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

    fun cancelSender() {
        val bundle = activeBundle
        stopAll()
        _senderState.value = if (bundle != null) NearbySenderState.Idle else NearbySenderState.Idle
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
            val current = _senderState.value
            if (current is NearbySenderState.Sending || current is NearbySenderState.WaitingReceiverAccept) {
                TransferForegroundService.stop(context)
                _senderState.value = NearbySenderState.Error(
                    reason = str(R.string.error_nearby_connection_lost_sender, senderPeerName),
                    bundle = activeBundle
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
            iconBase64 = bundle.iconBase64
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
        outgoingPayloadBytes.clear()
        outgoingPayloadCompleted.clear()
        _senderState.value = NearbySenderState.Sending(
            peerName = senderPeerName,
            bundle = bundle,
            progress = TransferProgressState(
                totalBytes = bundle.totalSizeBytes,
                totalFiles = bundle.files.size,
                currentFileName = bundle.files.firstOrNull()?.name.orEmpty()
            )
        )

        scope.launch {
            try {
                for (file in bundle.files) {
                    if (_senderState.value !is NearbySenderState.Sending) break
                    val filePayload = Payload.fromFile(file)
                    val mapping = NearbyWireEnvelope(
                        kind = "FILE_MAP",
                        fileMapping = NearbyFileMapping(
                            payloadId = filePayload.id,
                            fileName = file.name,
                            fileSize = file.length()
                        )
                    )
                    val mapPayload = Payload.fromBytes(
                        json.encodeToString(mapping).toByteArray(Charsets.UTF_8)
                    )
                    outgoingPayloadBytes[filePayload.id] = 0L
                    outgoingPayloadCompleted[filePayload.id] = false
                    connectionsClient.sendPayload(endpointId, mapPayload)
                        .addOnFailureListener { e ->
                            Log.e(TAG, "Failed to send file map payload", e)
                            TransferForegroundService.stop(context)
                            _senderState.value = NearbySenderState.Error(
                                reason = mapNearbyError(e, str(R.string.error_nearby_send_file_meta)),
                                bundle = bundle
                            )
                        }
                    delay(40)
                    connectionsClient.sendPayload(endpointId, filePayload)
                        .addOnFailureListener { e ->
                            Log.e(TAG, "Failed to send APK file payload", e)
                            TransferForegroundService.stop(context)
                            _senderState.value = NearbySenderState.Error(
                                reason = mapNearbyError(e, str(R.string.error_nearby_send_file, file.name)),
                                bundle = bundle
                            )
                        }
                    Log.i(TAG, "Queued outgoing file payload id=${filePayload.id} name=${file.name}")
                }
            } catch (se: SecurityException) {
                Log.e(TAG, "SecurityException queueing APK payloads", se)
                TransferForegroundService.stop(context)
                _senderState.value = NearbySenderState.Error(
                    reason = mapNearbyError(se, str(R.string.error_nearby_sending_files)),
                    bundle = bundle
                )
            } catch (ise: IllegalStateException) {
                Log.e(TAG, "IllegalStateException queueing APK payloads", ise)
                TransferForegroundService.stop(context)
                _senderState.value = NearbySenderState.Error(
                    reason = mapNearbyError(ise, str(R.string.error_nearby_sending_files)),
                    bundle = bundle
                )
            } catch (ae: ApiException) {
                Log.e(TAG, "ApiException queueing APK payloads", ae)
                TransferForegroundService.stop(context)
                _senderState.value = NearbySenderState.Error(
                    reason = mapNearbyError(ae, str(R.string.error_nearby_sending_files)),
                    bundle = bundle
                )
            } catch (t: Throwable) {
                Log.e(TAG, "Error queueing APK payloads", t)
                TransferForegroundService.stop(context)
                _senderState.value = NearbySenderState.Error(
                    reason = mapNearbyError(t, str(R.string.error_nearby_preparing_files)),
                    bundle = bundle
                )
            }
        }
    }

    private val senderPayloadCallback = object : PayloadCallback() {
        override fun onPayloadReceived(endpointId: String, payload: Payload) {
            if (payload.type != Payload.Type.BYTES) return
            val raw = payload.asBytes()?.toString(Charsets.UTF_8) ?: return
            val envelope = runCatching { json.decodeFromString<NearbyWireEnvelope>(raw) }.getOrNull() ?: return
            val bundle = activeBundle ?: return
            when (envelope.kind) {
                "ACCEPT" -> {
                    Log.i(TAG, "Receiver accepted offer; starting file payloads")
                    startSendingApkPayloads(endpointId, bundle)
                }
                "DECLINE" -> {
                    Log.i(TAG, "Receiver declined offer")
                    stopAll()
                    _senderState.value = NearbySenderState.Error(
                        reason = str(R.string.error_nearby_receiver_declined_app, senderPeerName),
                        bundle = bundle
                    )
                }
            }
        }

        override fun onPayloadTransferUpdate(endpointId: String, update: PayloadTransferUpdate) {
            val bundle = activeBundle ?: return
            if (!outgoingPayloadBytes.containsKey(update.payloadId)) return

            when (update.status) {
                PayloadTransferUpdate.Status.IN_PROGRESS -> {
                    outgoingPayloadBytes[update.payloadId] = update.bytesTransferred
                    updateSenderProgress(bundle)
                }
                PayloadTransferUpdate.Status.SUCCESS -> {
                    outgoingPayloadBytes[update.payloadId] = update.totalBytes.coerceAtLeast(
                        outgoingPayloadBytes[update.payloadId] ?: 0L
                    )
                    outgoingPayloadCompleted[update.payloadId] = true
                    updateSenderProgress(bundle)

                    val completedCount = outgoingPayloadCompleted.values.count { it }
                    if (completedCount >= expectedOutgoingFileCount) {
                        val elapsed = (System.currentTimeMillis() - senderStartTimeMillis).coerceAtLeast(500L)
                        Log.i(TAG, "All $completedCount APK file(s) sent in ${elapsed}ms")
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
                }
                PayloadTransferUpdate.Status.FAILURE -> {
                    TransferForegroundService.stop(context)
                    _senderState.value = NearbySenderState.Error(
                        reason = str(R.string.error_nearby_interrupted_sending, bundle.appName),
                        bundle = bundle
                    )
                }
                PayloadTransferUpdate.Status.CANCELED -> {
                    TransferForegroundService.stop(context)
                    _senderState.value = NearbySenderState.Error(
                        reason = str(R.string.error_nearby_cancelled),
                        bundle = bundle
                    )
                }
            }
        }
    }

    private fun updateSenderProgress(bundle: ExtractedApkBundle) {
        val now = System.currentTimeMillis()
        val completedCount = outgoingPayloadCompleted.values.count { it }
        if (completedCount < expectedOutgoingFileCount &&
            now - lastSenderUiUpdateMillis < (if (powerSaverEnabled) 1000L else 200L)
        ) {
            return
        }
        lastSenderUiUpdateMillis = now

        val sentBytes = outgoingPayloadBytes.values.sum().coerceAtMost(bundle.totalSizeBytes)
        val elapsedMs = (now - senderStartTimeMillis).coerceAtLeast(1L)
        val speed = (sentBytes * 1000L) / elapsedMs
        val remainingBytes = (bundle.totalSizeBytes - sentBytes).coerceAtLeast(0L)
        val etaSec = if (speed > 0L) remainingBytes / speed else 0L
        val currentName = bundle.files.getOrNull(completedCount.coerceAtMost(bundle.files.lastIndex))?.name.orEmpty()

        val progress = TransferProgressState(
            bytesTransferred = sentBytes,
            totalBytes = bundle.totalSizeBytes,
            speedBytesPerSec = speed,
            etaSeconds = etaSec,
            currentFileName = currentName,
            completedFiles = completedCount,
            totalFiles = bundle.files.size,
            elapsedMillis = elapsedMs
        )
        _senderState.value = NearbySenderState.Sending(senderPeerName, bundle, progress)

        if (now - lastNotificationUpdateMillis > updateIntervalMs) {
            lastNotificationUpdateMillis = now
            TransferForegroundService.startOrUpdate(
                context = context,
                title = str(R.string.notification_sending_progress, bundle.appName, progress.progressPercent),
                text = "${FormatUtils.formatBytes(sentBytes)} / ${FormatUtils.formatBytes(bundle.totalSizeBytes)} • ${FormatUtils.formatSpeed(speed)}",
                progressPercent = progress.progressPercent,
                indeterminate = false
            )
        }
    }

    // ---------------- RECEIVER ----------------

    fun startDiscovery(exportToPublicDownloads: Boolean) {
        val missingPerms = PermissionHelper.missingPermissions(
            context,
            PermissionHelper.getNearbyTransferPermissions()
        )
        if (missingPerms.isNotEmpty()) {
            Log.w(TAG, "Refusing startDiscovery: missing permissions $missingPerms")
            _receiverState.value = NearbyReceiverState.Error(
                str(R.string.error_nearby_missing_perms)
            )
            return
        }

        val connectivity = PermissionHelper.checkConnectivity(context)
        if (!connectivity.isReadyForNearby) {
            Log.w(TAG, "Refusing startDiscovery: connectivity not ready ($connectivity)")
            _receiverState.value = NearbyReceiverState.Error(
                buildConnectivityError(connectivity)
            )
            return
        }

        stopAll()
        this.exportToPublicDownloads = exportToPublicDownloads
        discoveredDevicesMap.clear()
        incomingFileMappings.clear()
        incomingFilePayloads.clear()
        incomingPayloadBytes.clear()
        incomingCompletedPayloadIds.clear()
        pendingMetadata = null
        receiverAcceptedOffer = false

        _receiverState.value = NearbyReceiverState.Discovering(emptyList())

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
                    mapNearbyError(e, str(R.string.error_nearby_scan_failed))
                )
            }
        } catch (se: SecurityException) {
            Log.e(TAG, "SecurityException in startDiscovery", se)
            _receiverState.value = NearbyReceiverState.Error(
                mapNearbyError(se, str(R.string.error_nearby_scan_failed))
            )
        } catch (ise: IllegalStateException) {
            Log.e(TAG, "IllegalStateException in startDiscovery", ise)
            _receiverState.value = NearbyReceiverState.Error(
                mapNearbyError(ise, str(R.string.error_nearby_scan_failed))
            )
        } catch (ae: ApiException) {
            Log.e(TAG, "ApiException in startDiscovery", ae)
            _receiverState.value = NearbyReceiverState.Error(
                mapNearbyError(ae, str(R.string.error_nearby_scan_failed))
            )
        } catch (e: Throwable) {
            Log.e(TAG, "Unexpected error in startDiscovery", e)
            _receiverState.value = NearbyReceiverState.Error(
                mapNearbyError(e, str(R.string.error_nearby_scan_failed))
            )
        }
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
                    mapNearbyError(e, str(R.string.error_nearby_connect_failed, sender.deviceName))
                )
            }
        } catch (se: SecurityException) {
            Log.e(TAG, "SecurityException in requestConnection", se)
            _receiverState.value = NearbyReceiverState.Error(
                mapNearbyError(se, str(R.string.error_nearby_connect_failed, sender.deviceName))
            )
        } catch (ise: IllegalStateException) {
            Log.e(TAG, "IllegalStateException in requestConnection", ise)
            _receiverState.value = NearbyReceiverState.Error(
                mapNearbyError(ise, str(R.string.error_nearby_connect_failed, sender.deviceName))
            )
        } catch (ae: ApiException) {
            Log.e(TAG, "ApiException in requestConnection", ae)
            _receiverState.value = NearbyReceiverState.Error(
                mapNearbyError(ae, str(R.string.error_nearby_connect_failed, sender.deviceName))
            )
        } catch (e: Throwable) {
            Log.e(TAG, "Error in requestConnection", e)
            _receiverState.value = NearbyReceiverState.Error(
                mapNearbyError(e, str(R.string.error_nearby_connect_failed, sender.deviceName))
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
        receiverStartTimeMillis = System.currentTimeMillis()
        receiverDestDir = storageManager.createDestinationDir(
            metadata.appName,
            metadata.versionName,
            metadata.isSplitApk
        )
        _receiverState.value = NearbyReceiverState.Downloading(
            peerName = receiverPeerName,
            metadata = metadata,
            progress = TransferProgressState(
                totalBytes = metadata.totalSizeBytes,
                totalFiles = metadata.files.size,
                currentFileName = metadata.files.firstOrNull()?.fileName.orEmpty()
            )
        )

        TransferForegroundService.startOrUpdate(
            context,
            str(R.string.notification_receiving_title, metadata.appName),
            str(R.string.notification_starting_download, receiverPeerName),
            progressPercent = 0,
            indeterminate = false
        )

        val acceptEnvelope = NearbyWireEnvelope(kind = "ACCEPT")
        try {
            connectionsClient.sendPayload(
                endpointId,
                Payload.fromBytes(json.encodeToString(acceptEnvelope).toByteArray(Charsets.UTF_8))
            ).addOnFailureListener { e ->
                Log.e(TAG, "Failed to send ACCEPT", e)
                TransferForegroundService.stop(context)
                _receiverState.value = NearbyReceiverState.Error(
                    mapNearbyError(e, str(R.string.error_nearby_accept_lost))
                )
            }
        } catch (se: SecurityException) {
            Log.e(TAG, "SecurityException sending ACCEPT", se)
            TransferForegroundService.stop(context)
            _receiverState.value = NearbyReceiverState.Error(
                mapNearbyError(se, str(R.string.error_nearby_accept_failed))
            )
        } catch (ise: IllegalStateException) {
            Log.e(TAG, "IllegalStateException sending ACCEPT", ise)
            TransferForegroundService.stop(context)
            _receiverState.value = NearbyReceiverState.Error(
                mapNearbyError(ise, str(R.string.error_nearby_accept_failed))
            )
        } catch (ae: ApiException) {
            Log.e(TAG, "ApiException sending ACCEPT", ae)
            TransferForegroundService.stop(context)
            _receiverState.value = NearbyReceiverState.Error(
                mapNearbyError(ae, str(R.string.error_nearby_accept_failed))
            )
        } catch (e: Throwable) {
            Log.e(TAG, "Error sending ACCEPT", e)
            TransferForegroundService.stop(context)
            _receiverState.value = NearbyReceiverState.Error(
                mapNearbyError(e, str(R.string.error_nearby_accept_lost))
            )
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

    fun cancelReceiver() {
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
        val current = _receiverState.value
        if (current is NearbyReceiverState.Discovering) {
            _receiverState.value = current.copy(qrTargetLabel = null)
        }
    }

    private fun matchesQrTarget(endpointName: String, targetCode: String, targetName: String?): Boolean {
        if (endpointName.contains("#$targetCode", ignoreCase = true)) return true
        if (!targetName.isNullOrBlank() && endpointName.equals(targetName, ignoreCase = true)) return true
        return false
    }

    private val receiverDiscoveryCallback = object : EndpointDiscoveryCallback() {
        override fun onEndpointFound(endpointId: String, info: DiscoveredEndpointInfo) {
            Log.i(TAG, "Discovered sender: ${info.endpointName} ($endpointId)")
            val device = DiscoveredSenderDevice(endpointId, info.endpointName)
            discoveredDevicesMap[endpointId] = device

            val targetCode = pendingQrTargetCode
            if (targetCode != null && matchesQrTarget(info.endpointName, targetCode, pendingQrTargetLabel)) {
                Log.i(TAG, "Auto-connecting to QR-matched sender: ${info.endpointName}")
                pendingQrTargetCode = null
                pendingQrTargetLabel = null
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
            // Receiver automatically confirms transport channel while awaiting Sender approval + app offer dialog
            try {
                connectionsClient.acceptConnection(endpointId, receiverPayloadCallback)
                    .addOnFailureListener { e ->
                        Log.e(TAG, "Receiver acceptConnection failed", e)
                        _receiverState.value = NearbyReceiverState.Error(
                            mapNearbyError(e, str(R.string.error_nearby_accept_connection_from, info.endpointName))
                        )
                    }
            } catch (se: SecurityException) {
                Log.e(TAG, "SecurityException in receiver acceptConnection", se)
                _receiverState.value = NearbyReceiverState.Error(
                    mapNearbyError(se, str(R.string.error_nearby_accept_connection_from, info.endpointName))
                )
            } catch (ise: IllegalStateException) {
                Log.e(TAG, "IllegalStateException in receiver acceptConnection", ise)
                _receiverState.value = NearbyReceiverState.Error(
                    mapNearbyError(ise, str(R.string.error_nearby_accept_connection_from, info.endpointName))
                )
            } catch (ae: ApiException) {
                Log.e(TAG, "ApiException in receiver acceptConnection", ae)
                _receiverState.value = NearbyReceiverState.Error(
                    mapNearbyError(ae, str(R.string.error_nearby_accept_connection_from, info.endpointName))
                )
            } catch (e: Throwable) {
                Log.e(TAG, "Error in receiver acceptConnection", e)
                _receiverState.value = NearbyReceiverState.Error(
                    mapNearbyError(e, str(R.string.error_nearby_accept_connection_from, info.endpointName))
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
                            str(R.string.error_nearby_sender_timeout)
                        )
                    }
                }
            } else {
                val reason = if (result.status.statusCode == ConnectionsStatusCodes.STATUS_CONNECTION_REJECTED) {
                    str(R.string.error_nearby_sender_declined, receiverPeerName)
                } else {
                    str(R.string.error_nearby_connect_failed_code, receiverPeerName, result.status.statusCode)
                }
                _receiverState.value = NearbyReceiverState.Error(reason)
            }
        }

        override fun onDisconnected(endpointId: String) {
            Log.w(TAG, "Receiver disconnected from $endpointId")
            val current = _receiverState.value
            if (current is NearbyReceiverState.Downloading || current is NearbyReceiverState.IncomingOffer || current is NearbyReceiverState.WaitingSenderApproval) {
                TransferForegroundService.stop(context)
                _receiverState.value = NearbyReceiverState.Error(
                    str(R.string.error_nearby_connection_lost_receiver, receiverPeerName)
                )
            }
        }
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
                            _receiverState.value = NearbyReceiverState.IncomingOffer(
                                endpointId = endpointId,
                                peerName = receiverPeerName,
                                metadata = meta
                            )
                        }
                        "FILE_MAP" -> {
                            val mapping = envelope.fileMapping ?: return
                            incomingFileMappings[mapping.payloadId] = mapping
                            // If file already completed before mapping arrived, flush it now
                            if (incomingCompletedPayloadIds.contains(mapping.payloadId)) {
                                flushCompletedFileIfReady(mapping.payloadId)
                            }
                        }
                    }
                }
                Payload.Type.FILE -> {
                    incomingFilePayloads[payload.id] = payload
                    incomingPayloadBytes[payload.id] = 0L
                }
            }
        }

        override fun onPayloadTransferUpdate(endpointId: String, update: PayloadTransferUpdate) {
            val metadata = pendingMetadata ?: return
            if (!incomingFilePayloads.containsKey(update.payloadId)) return

            when (update.status) {
                PayloadTransferUpdate.Status.IN_PROGRESS -> {
                    incomingPayloadBytes[update.payloadId] = update.bytesTransferred
                    updateReceiverProgress(metadata)
                }
                PayloadTransferUpdate.Status.SUCCESS -> {
                    incomingPayloadBytes[update.payloadId] = update.totalBytes.coerceAtLeast(
                        incomingPayloadBytes[update.payloadId] ?: 0L
                    )
                    incomingCompletedPayloadIds.add(update.payloadId)
                    updateReceiverProgress(metadata)
                    flushCompletedFileIfReady(update.payloadId)
                }
                PayloadTransferUpdate.Status.FAILURE -> {
                    TransferForegroundService.stop(context)
                    _receiverState.value = NearbyReceiverState.Error(
                        str(R.string.error_nearby_download_interrupted)
                    )
                }
                PayloadTransferUpdate.Status.CANCELED -> {
                    TransferForegroundService.stop(context)
                    _receiverState.value = NearbyReceiverState.Error(str(R.string.error_nearby_cancelled))
                }
            }
        }
    }

    private val savedFilesList = mutableListOf<File>()

    @Synchronized
    private fun flushCompletedFileIfReady(payloadId: Long) {
        if (!receiverAcceptedOffer) return
        val metadata = pendingMetadata ?: return
        val payload = incomingFilePayloads[payloadId] ?: return
        val mapping = incomingFileMappings[payloadId] ?: return
        val destDir = receiverDestDir ?: return

        scope.launch {
            try {
                val safeName = FormatUtils.sanitizeFileName(mapping.fileName.removeSuffix(".apk")) + ".apk"
                val outFile = File(destDir, safeName)
                val payloadFile = payload.asFile()
                val uri = payloadFile?.asUri()

                if (uri != null) {
                    context.contentResolver.openInputStream(uri)?.use { input ->
                        outFile.outputStream().use { output ->
                            input.copyTo(output, bufferSize = 65536)
                        }
                    } ?: throw IOException("Unable to read received payload URI.")
                } else {
                    val pfd: ParcelFileDescriptor? = payloadFile?.asParcelFileDescriptor()
                    if (pfd != null) {
                        FileInputStream(pfd.fileDescriptor).use { input ->
                            outFile.outputStream().use { output ->
                                input.copyTo(output, bufferSize = 65536)
                            }
                        }
                    } else {
                        @Suppress("DEPRECATION")
                        val legacyFile = payloadFile?.asJavaFile()
                            ?: throw IOException("Payload file unavailable.")
                        legacyFile.inputStream().use { input ->
                            outFile.outputStream().use { output ->
                                input.copyTo(output, bufferSize = 65536)
                            }
                        }
                    }
                }
                runCatching { payloadFile?.close() }
                incomingFilePayloads.remove(payloadId)

                val totalSaved: Int
                val snapshotFiles: List<File>
                synchronized(savedFilesList) {
                    savedFilesList += outFile
                    totalSaved = savedFilesList.size
                    snapshotFiles = savedFilesList.toList()
                }

                if (totalSaved >= metadata.files.size) {
                    val displayPath = storageManager.exportCopiesToPublicDownloads(
                        files = snapshotFiles,
                        appName = metadata.appName,
                        versionName = metadata.versionName,
                        isSplitApk = metadata.isSplitApk,
                        enabled = exportToPublicDownloads
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
                    _receiverState.value = NearbyReceiverState.Completed(
                        metadata = metadata,
                        savedFiles = snapshotFiles,
                        displayLocation = displayPath,
                        peerName = receiverPeerName,
                        durationMillis = elapsed
                    )
                }
            } catch (t: Throwable) {
                Log.e(TAG, "Error saving received APK file", t)
                TransferForegroundService.stop(context)
                _receiverState.value = NearbyReceiverState.Error(
                    t.message ?: str(R.string.error_nearby_save_failed)
                )
            }
        }
    }

    private fun updateReceiverProgress(metadata: ApkBundleMetadata) {
        if (!receiverAcceptedOffer) return
        val now = System.currentTimeMillis()
        val completedCount = incomingCompletedPayloadIds.size
        if (completedCount < metadata.files.size &&
            now - lastReceiverUiUpdateMillis < (if (powerSaverEnabled) 1000L else 200L)
        ) {
            return
        }
        lastReceiverUiUpdateMillis = now

        val receivedBytes = incomingPayloadBytes.values.sum().coerceAtMost(metadata.totalSizeBytes)
        val elapsedMs = (now - receiverStartTimeMillis).coerceAtLeast(1L)
        val speed = (receivedBytes * 1000L) / elapsedMs
        val remaining = (metadata.totalSizeBytes - receivedBytes).coerceAtLeast(0L)
        val etaSec = if (speed > 0L) remaining / speed else 0L
        val currentName = metadata.files.getOrNull(completedCount.coerceAtMost(metadata.files.lastIndex))?.fileName.orEmpty()

        val progress = TransferProgressState(
            bytesTransferred = receivedBytes,
            totalBytes = metadata.totalSizeBytes,
            speedBytesPerSec = speed,
            etaSeconds = etaSec,
            currentFileName = currentName,
            completedFiles = completedCount,
            totalFiles = metadata.files.size,
            elapsedMillis = elapsedMs
        )
        _receiverState.value = NearbyReceiverState.Downloading(receiverPeerName, metadata, progress)

        if (now - lastNotificationUpdateMillis > updateIntervalMs) {
            lastNotificationUpdateMillis = now
            TransferForegroundService.startOrUpdate(
                context = context,
                title = str(
                    R.string.notification_downloading_progress,
                    metadata.appName,
                    progress.progressPercent
                ),
                text = "${FormatUtils.formatBytes(receivedBytes)} / ${FormatUtils.formatBytes(metadata.totalSizeBytes)} • ${FormatUtils.formatSpeed(speed)}",
                progressPercent = progress.progressPercent,
                indeterminate = false
            )
        }
    }

    fun stopAll() {
        receiverTimeoutJob?.cancel()
        if (clientInitialized) {
            runCatching { connectionsClient.stopAdvertising() }
            runCatching { connectionsClient.stopDiscovery() }
            runCatching { connectionsClient.stopAllEndpoints() }
        }
        TransferForegroundService.stop(context)
        synchronized(savedFilesList) { savedFilesList.clear() }
        pendingQrTargetCode = null
        pendingQrTargetLabel = null
        senderEndpointId = null
        receiverEndpointId = null
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
