package com.example.ui.screens

import android.Manifest
import android.content.Intent
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.Launch
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.InstallMobile
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.QrCodeScanner
import androidx.compose.material.icons.rounded.Radar
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.WifiTethering
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.R
import com.example.installer.InstallState
import com.example.model.ApkBundleMetadata
import com.example.transfer.HttpDownloadState
import com.example.transfer.InterruptedTransferRecord
import com.example.transfer.NearbyReceiverState
import com.example.ui.components.Base64AppIcon
import com.example.ui.components.CircularTransferProgress
import com.example.ui.components.ConnectivityRequiredCard
import com.example.ui.components.ConnectivityRequiredDialog
import com.example.ui.components.PermissionRationaleCard
import com.example.ui.components.PulseRadarAnimation
import com.example.ui.components.QrCodeScannerView
import com.example.ui.components.TransferCancelledView
import com.example.ui.components.UnknownSourcesPermissionCard
import com.example.ui.theme.PrimaryBlue
import com.example.ui.theme.SuccessGreen
import com.example.ui.viewmodel.ReceivedAppItem
import com.example.ui.viewmodel.ReceiverMode
import com.example.ui.viewmodel.ReceiverViewModel
import com.example.util.FileTransferHelper
import com.example.util.FormatUtils
import com.example.util.PermissionHelper
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReceiverScreen(
    viewModel: ReceiverViewModel,
    onDone: () -> Unit
) {
    val context = LocalContext.current
    val mode by viewModel.mode.collectAsStateWithLifecycle()
    val nearbyState by viewModel.nearbyState.collectAsStateWithLifecycle()
    val httpState by viewModel.httpDownloadState.collectAsStateWithLifecycle()
    val installState by viewModel.installState.collectAsStateWithLifecycle()
    val canRequestInstalls by viewModel.canRequestInstalls.collectAsStateWithLifecycle()
    val receivedApps by viewModel.receivedApps.collectAsStateWithLifecycle()

    val requiredPermissions = remember { PermissionHelper.getNearbyTransferPermissions() }
    var missingPerms by remember {
        mutableStateOf(PermissionHelper.missingPermissions(context, requiredPermissions))
    }
    var permRequested by remember { mutableStateOf(false) }
    var connectivity by remember { mutableStateOf(PermissionHelper.checkConnectivity(context)) }
    var showConnectivityDialog by remember { mutableStateOf(false) }

    val openBluetoothSettings: () -> Unit = {
        runCatching { context.startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) }.onFailure {
            runCatching { context.startActivity(Intent(Settings.ACTION_SETTINGS)) }
        }
    }
    val openWifiSettings: () -> Unit = {
        runCatching { context.startActivity(Intent(Settings.ACTION_WIFI_SETTINGS)) }.onFailure {
            runCatching { context.startActivity(Intent(Settings.ACTION_SETTINGS)) }
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        missingPerms = PermissionHelper.missingPermissions(context, requiredPermissions)
        connectivity = PermissionHelper.checkConnectivity(context)
        if (missingPerms.isEmpty() && mode == ReceiverMode.NEARBY && !connectivity.isReadyForNearby) {
            showConnectivityDialog = true
        }
    }

    val completedPackageName = when {
        nearbyState is NearbyReceiverState.Completed -> (nearbyState as NearbyReceiverState.Completed).metadata.packageName
        httpState is HttpDownloadState.Completed -> (httpState as HttpDownloadState.Completed).metadata.packageName
        else -> null
    }

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        viewModel.refreshInstallPermissionAndStatus(completedPackageName)
        missingPerms = PermissionHelper.missingPermissions(context, requiredPermissions)
        connectivity = PermissionHelper.checkConnectivity(context)
        if (mode == ReceiverMode.NEARBY && missingPerms.isEmpty()) {
            showConnectivityDialog = !connectivity.isReadyForNearby
        }
    }

    val isNearbyIdle = nearbyState is NearbyReceiverState.Idle
    LaunchedEffect(mode, missingPerms, connectivity, isNearbyIdle) {
        if (mode == ReceiverMode.NEARBY) {
            when {
                missingPerms.isNotEmpty() -> {
                    if (!permRequested) {
                        permRequested = true
                        permissionLauncher.launch(missingPerms.toTypedArray())
                    }
                }
                !connectivity.isReadyForNearby -> {
                    showConnectivityDialog = true
                }
                else -> {
                    showConnectivityDialog = false
                    if (nearbyState is NearbyReceiverState.Idle) {
                        viewModel.startNearbyDiscovery()
                    }
                }
            }
        } else {
            showConnectivityDialog = false
        }
    }

    val handleBack: () -> Unit = {
        viewModel.leavePreservingInterrupted()
        onDone()
    }
    BackHandler(onBack = handleBack)

    if (mode == ReceiverMode.NEARBY && missingPerms.isEmpty() && !connectivity.isReadyForNearby && showConnectivityDialog) {
        ConnectivityRequiredDialog(
            isBluetoothEnabled = connectivity.isBluetoothEnabled,
            isWifiEnabled = connectivity.isWifiEnabled,
            onOpenBluetoothSettings = openBluetoothSettings,
            onOpenWifiSettings = openWifiSettings,
            onRetry = {
                connectivity = PermissionHelper.checkConnectivity(context)
                if (connectivity.isReadyForNearby) {
                    showConnectivityDialog = false
                }
            },
            onDismiss = { showConnectivityDialog = false }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = if (mode == ReceiverMode.HOTSPOT_HTTP) {
                            stringResource(R.string.receiver_title_hotspot)
                        } else {
                            stringResource(R.string.receiver_title_nearby)
                        },
                        fontWeight = FontWeight.Bold
                    )
                },
                navigationIcon = {
                    IconButton(
                        onClick = handleBack,
                        modifier = Modifier
                            .minimumInteractiveComponentSize()
                            .testTag("receiver_back_button")
                    ) {
                        Icon(
                            Icons.AutoMirrored.Rounded.ArrowBack,
                            contentDescription = stringResource(R.string.action_back)
                        )
                    }
                }
            )
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 20.dp),
            contentAlignment = Alignment.TopCenter
        ) {
            Column(
                modifier = Modifier
                    .widthIn(max = 680.dp)
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Spacer(modifier = Modifier.height(4.dp))

                if (mode == ReceiverMode.HOTSPOT_HTTP) {
                    HotspotReceiverContent(
                        httpState = httpState,
                        installState = installState,
                        canRequestInstalls = canRequestInstalls,
                        receivedApps = receivedApps,
                        onStartDownload = { url -> viewModel.startHttpDownload(url) },
                        onResumeInterruptedHotspot = { rec -> viewModel.resumeInterruptedHotspotTransfer(rec) },
                        onClearInterruptedTransfer = {
                            viewModel.clearInterruptedTransfer()
                            Toast.makeText(
                                context,
                                context.getString(R.string.transfer_resume_cleared_toast),
                                Toast.LENGTH_SHORT
                            ).show()
                        },
                        onCancelActiveDownload = { viewModel.cancelOngoingHotspotDownload() },
                        onRetryCancelledDownload = { url -> viewModel.retryCancelledHotspotDownload(url) },
                        onCancelDownload = { viewModel.cancelAll() },
                        onSwitchToNearby = { viewModel.switchToNearbyMode() },
                        onOpenUnknownSources = { viewModel.openUnknownAppSourcesSettings() },
                        onInstall = { files, meta ->
                            viewModel.installReceivedApk(files, meta.appName, meta.packageName, meta.isSplitApk)
                        },
                        onReceiveNextApp = { viewModel.receiveNextHotspotApp() },
                        onOpenInstalled = { pkg ->
                            if (!viewModel.openInstalledApp(pkg)) {
                                Toast.makeText(
                                    context,
                                    context.getString(R.string.receiver_no_launcher_toast),
                                    Toast.LENGTH_SHORT
                                ).show()
                            }
                        },
                        onDone = handleBack
                    )
                } else if (missingPerms.isNotEmpty()) {
                    PermissionRationaleCard(
                        missingPermissions = missingPerms,
                        onGrantClick = { permissionLauncher.launch(missingPerms.toTypedArray()) }
                    )
                    OutlinedButton(
                        onClick = { viewModel.switchToHotspotMode() },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp)
                            .testTag("receiver_hotspot_from_perms")
                    ) {
                        Icon(Icons.Rounded.WifiTethering, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(stringResource(R.string.receiver_use_hotspot_instead))
                    }
                } else if (!connectivity.isReadyForNearby) {
                    ConnectivityRequiredCard(
                        isBluetoothEnabled = connectivity.isBluetoothEnabled,
                        isWifiEnabled = connectivity.isWifiEnabled,
                        onOpenBluetoothSettings = openBluetoothSettings,
                        onOpenWifiSettings = openWifiSettings,
                        onRetry = {
                            connectivity = PermissionHelper.checkConnectivity(context)
                            showConnectivityDialog = !connectivity.isReadyForNearby
                        }
                    )
                    OutlinedButton(
                        onClick = { viewModel.switchToHotspotMode() },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp)
                            .testTag("receiver_hotspot_from_connectivity")
                    ) {
                        Icon(Icons.Rounded.WifiTethering, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(stringResource(R.string.receiver_use_hotspot_instead))
                    }
                } else {
                    when (val state = nearbyState) {
                        is NearbyReceiverState.Idle -> {
                            CircularProgressIndicator()
                            Text(stringResource(R.string.receiver_initializing))
                        }

                        is NearbyReceiverState.Discovering -> {
                            var showNearbyQrScanner by remember { mutableStateOf(false) }
                            var hasCameraPerm by remember {
                                mutableStateOf(PermissionHelper.hasCameraPermission(context))
                            }
                            val nearbyCameraLauncher = rememberLauncherForActivityResult(
                                ActivityResultContracts.RequestPermission()
                            ) { granted ->
                                hasCameraPerm = granted
                                if (granted) {
                                    showNearbyQrScanner = true
                                } else {
                                    Toast.makeText(
                                        context,
                                        context.getString(R.string.receiver_camera_perm_nearby_toast),
                                        Toast.LENGTH_SHORT
                                    ).show()
                                }
                            }

                            if (showNearbyQrScanner && hasCameraPerm) {
                                QrCodeScannerView(
                                    onQrDetected = { raw ->
                                        showNearbyQrScanner = false
                                        val handled = viewModel.handleScannedQrInNearby(raw)
                                        if (!handled) {
                                            Toast.makeText(
                                                context,
                                                context.getString(R.string.receiver_invalid_qr_toast),
                                                Toast.LENGTH_SHORT
                                            ).show()
                                        }
                                    }
                                )
                                OutlinedButton(
                                    onClick = { showNearbyQrScanner = false },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .testTag("close_nearby_qr_scanner_button")
                                ) {
                                    Text(stringResource(R.string.receiver_cancel_qr_scan))
                                }
                            } else {
                                PulseRadarAnimation(
                                    color = SuccessGreen,
                                    centerIcon = {
                                        Icon(
                                            imageVector = Icons.Rounded.Radar,
                                            contentDescription = stringResource(R.string.receiver_scanning_cd),
                                            tint = Color.White,
                                            modifier = Modifier.size(32.dp)
                                        )
                                    }
                                )
                            }

                            Text(
                                text = stringResource(R.string.receiver_scanning_title),
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold,
                                textAlign = TextAlign.Center
                            )
                            Text(
                                text = stringResource(R.string.receiver_scanning_subtitle),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center
                            )

                            if (!state.qrTargetLabel.isNullOrBlank()) {
                                Surface(
                                    shape = RoundedCornerShape(14.dp),
                                    color = SuccessGreen.copy(alpha = 0.14f),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            modifier = Modifier.weight(1f)
                                        ) {
                                            CircularProgressIndicator(
                                                modifier = Modifier.size(18.dp),
                                                strokeWidth = 2.dp,
                                                color = SuccessGreen
                                            )
                                            Spacer(modifier = Modifier.width(10.dp))
                                            Text(
                                                text = stringResource(R.string.receiver_finding_device, state.qrTargetLabel),
                                                style = MaterialTheme.typography.bodySmall,
                                                fontWeight = FontWeight.SemiBold,
                                                color = SuccessGreen
                                            )
                                        }
                                        TextButton(onClick = { viewModel.clearQrPairTarget() }) {
                                            Text(stringResource(R.string.action_clear))
                                        }
                                    }
                                }
                            } else if (!showNearbyQrScanner) {
                                Button(
                                    onClick = {
                                        if (hasCameraPerm) {
                                            showNearbyQrScanner = true
                                        } else {
                                            nearbyCameraLauncher.launch(Manifest.permission.CAMERA)
                                        }
                                    },
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = SuccessGreen,
                                        contentColor = Color.White
                                    ),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(50.dp)
                                        .testTag("nearby_scan_qr_button")
                                ) {
                                    Icon(Icons.Rounded.QrCodeScanner, contentDescription = null)
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(stringResource(R.string.receiver_scan_qr_find_device), fontWeight = FontWeight.Bold)
                                }
                            }

                            if (state.devices.isEmpty()) {
                                Surface(
                                    shape = RoundedCornerShape(16.dp),
                                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text(
                                        text = stringResource(R.string.receiver_no_senders_found),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        textAlign = TextAlign.Center,
                                        modifier = Modifier.padding(18.dp)
                                    )
                                }
                            } else {
                                Column(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    state.devices.forEach { device ->
                                        Card(
                                            shape = RoundedCornerShape(18.dp),
                                            colors = CardDefaults.cardColors(
                                                containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.4f)
                                            ),
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .clickable { viewModel.connectToSender(device) }
                                                .testTag("discovered_device_${device.endpointId}")
                                        ) {
                                            Row(
                                                modifier = Modifier.padding(16.dp),
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Box(
                                                    modifier = Modifier
                                                        .size(44.dp)
                                                        .clip(CircleShape)
                                                        .background(SuccessGreen),
                                                    contentAlignment = Alignment.Center
                                                ) {
                                                    Icon(
                                                        Icons.Rounded.PhoneAndroid,
                                                        contentDescription = null,
                                                        tint = Color.White
                                                    )
                                                }
                                                Spacer(modifier = Modifier.width(12.dp))
                                                Column(modifier = Modifier.weight(1f)) {
                                                    Text(
                                                        text = device.deviceName,
                                                        style = MaterialTheme.typography.titleMedium,
                                                        fontWeight = FontWeight.Bold
                                                    )
                                                    Text(
                                                        text = stringResource(R.string.receiver_tap_to_connect),
                                                        style = MaterialTheme.typography.bodySmall,
                                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                            }

                            Spacer(modifier = Modifier.height(6.dp))
                            OutlinedButton(
                                onClick = { viewModel.switchToHotspotMode() },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(50.dp)
                                    .testTag("receiver_try_hotspot_button")
                            ) {
                                Icon(Icons.Rounded.QrCodeScanner, contentDescription = null)
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(stringResource(R.string.receiver_try_hotspot_qr_url))
                            }
                        }

                        is NearbyReceiverState.WaitingSenderApproval -> {
                            Spacer(modifier = Modifier.height(24.dp))
                            CircularProgressIndicator(color = SuccessGreen)
                            Text(
                                text = stringResource(R.string.receiver_waiting_approval_title),
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = stringResource(
                                    R.string.receiver_waiting_approval_body,
                                    state.peerName,
                                    state.authDigits
                                ),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center
                            )
                            TextButton(onClick = handleBack) {
                                Text(stringResource(R.string.action_cancel), color = MaterialTheme.colorScheme.error)
                            }
                        }

                        is NearbyReceiverState.IncomingOffer -> {
                            AlertDialog(
                                onDismissRequest = { viewModel.declineIncomingOffer() },
                                title = {
                                    Text(
                                        stringResource(
                                            if (state.metadata.isGenericFile) {
                                                R.string.receiver_incoming_file_title
                                            } else {
                                                R.string.receiver_incoming_offer_title
                                            }
                                        )
                                    )
                                },
                                text = {
                                    Column(
                                        verticalArrangement = Arrangement.spacedBy(12.dp),
                                        horizontalAlignment = Alignment.CenterHorizontally
                                    ) {
                                        if (state.metadata.isGenericFile) {
                                            Box(
                                                modifier = Modifier
                                                    .size(64.dp)
                                                    .clip(RoundedCornerShape(16.dp))
                                                    .background(SuccessGreen.copy(alpha = 0.14f)),
                                                contentAlignment = Alignment.Center
                                            ) {
                                                Icon(
                                                    imageVector = iconForMimeType(
                                                        state.metadata.mimeType,
                                                        state.metadata.appName
                                                    ),
                                                    contentDescription = null,
                                                    tint = SuccessGreen,
                                                    modifier = Modifier.size(34.dp)
                                                )
                                            }
                                        } else {
                                            Base64AppIcon(
                                                iconBase64 = state.metadata.iconBase64,
                                                size = 64.dp,
                                                tint = SuccessGreen
                                            )
                                        }
                                        Text(
                                            text = state.metadata.appName,
                                            style = MaterialTheme.typography.titleLarge,
                                            fontWeight = FontWeight.Bold
                                        )
                                        Text(
                                            text = if (state.metadata.isGenericFile) {
                                                val sizeText = FormatUtils.formatBytes(state.metadata.totalSizeBytes)
                                                val mime = state.metadata.mimeType
                                                if (!mime.isNullOrBlank()) "$sizeText • $mime" else sizeText
                                            } else {
                                                stringResource(
                                                    R.string.sender_bundle_meta,
                                                    state.metadata.versionName,
                                                    FormatUtils.formatBytes(state.metadata.totalSizeBytes)
                                                )
                                            },
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                        if (state.metadata.isSplitApk) {
                                            Surface(
                                                shape = RoundedCornerShape(8.dp),
                                                color = PrimaryBlue.copy(alpha = 0.14f)
                                            ) {
                                                Text(
                                                    text = stringResource(
                                                        R.string.label_split_apk_bundle,
                                                        state.metadata.files.size
                                                    ),
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = PrimaryBlue,
                                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                                )
                                            }
                                        }
                                        Text(
                                            text = stringResource(R.string.receiver_from_peer, state.peerName),
                                            style = MaterialTheme.typography.bodySmall
                                        )
                                    }
                                },
                                confirmButton = {
                                    Button(
                                        onClick = { viewModel.acceptIncomingOffer() },
                                        colors = ButtonDefaults.buttonColors(
                                            containerColor = SuccessGreen,
                                            contentColor = Color.White
                                        ),
                                        modifier = Modifier.testTag("accept_incoming_apk_button")
                                    ) {
                                        Text(stringResource(R.string.action_accept))
                                    }
                                },
                                dismissButton = {
                                    TextButton(
                                        onClick = { viewModel.declineIncomingOffer() },
                                        modifier = Modifier.testTag("decline_incoming_apk_button")
                                    ) {
                                        Text(stringResource(R.string.action_decline))
                                    }
                                }
                            )
                        }

                        is NearbyReceiverState.Downloading -> {
                            val pausedDetail = when {
                                !state.isPaused -> null
                                state.isWaitingWhilePaused -> stringResource(R.string.transfer_paused_waiting_next_file)
                                else -> stringResource(R.string.transfer_paused_finishing_current)
                            }
                            CircularTransferProgress(
                                progress = state.progress,
                                accentColor = SuccessGreen,
                                title = stringResource(R.string.receiver_receiving_app, state.metadata.appName),
                                subtitle = stringResource(R.string.receiver_from_peer_short, state.peerName),
                                directionLabel = stringResource(R.string.progress_direction_received),
                                isPaused = state.isPaused,
                                pausedDetail = pausedDetail
                            )
                            if (!state.statusNotice.isNullOrBlank()) {
                                Surface(
                                    shape = RoundedCornerShape(12.dp),
                                    color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.7f),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .testTag("transfer_resume_notice_banner")
                                ) {
                                    Text(
                                        text = state.statusNotice,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                                        textAlign = TextAlign.Center,
                                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)
                                    )
                                }
                            }
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                if (state.isPaused) {
                                    Button(
                                        onClick = { viewModel.resumeNearbyTransfer() },
                                        colors = ButtonDefaults.buttonColors(
                                            containerColor = SuccessGreen,
                                            contentColor = Color.White
                                        ),
                                        modifier = Modifier
                                            .weight(1f)
                                            .height(48.dp)
                                            .testTag("pause_resume_receiver_button")
                                    ) {
                                        Icon(
                                            imageVector = Icons.Rounded.PlayArrow,
                                            contentDescription = null,
                                            modifier = Modifier.size(20.dp)
                                        )
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text(
                                            text = stringResource(R.string.transfer_action_resume),
                                            fontWeight = FontWeight.Bold
                                        )
                                    }
                                } else {
                                    OutlinedButton(
                                        onClick = { viewModel.pauseNearbyTransfer() },
                                        modifier = Modifier
                                            .weight(1f)
                                            .height(48.dp)
                                            .testTag("pause_resume_receiver_button")
                                    ) {
                                        Icon(
                                            imageVector = Icons.Rounded.Pause,
                                            contentDescription = null,
                                            modifier = Modifier.size(20.dp)
                                        )
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text(stringResource(R.string.transfer_action_pause))
                                    }
                                }
                                OutlinedButton(
                                    onClick = { viewModel.cancelOngoingNearbyTransfer() },
                                    modifier = Modifier
                                        .weight(1f)
                                        .height(48.dp)
                                        .testTag("cancel_downloading_button")
                                ) {
                                    Text(
                                        text = stringResource(R.string.action_cancel),
                                        color = MaterialTheme.colorScheme.error
                                    )
                                }
                            }
                        }

                        is NearbyReceiverState.Completed -> {
                            DownloadCompleteInstallSection(
                                metadata = state.metadata,
                                savedFiles = state.savedFiles,
                                displayLocation = state.displayLocation,
                                durationMillis = state.durationMillis,
                                installState = installState,
                                canRequestInstalls = canRequestInstalls,
                                receivedApps = receivedApps,
                                onOpenUnknownSources = { viewModel.openUnknownAppSourcesSettings() },
                                onInstallClick = {
                                    viewModel.installReceivedApk(
                                        files = state.savedFiles,
                                        appName = state.metadata.appName,
                                        packageName = state.metadata.packageName,
                                        isSplitApk = state.metadata.isSplitApk
                                    )
                                },
                                onInstallReceivedItem = { item ->
                                    viewModel.installReceivedApk(
                                        files = item.savedFiles,
                                        appName = item.metadata.appName,
                                        packageName = item.metadata.packageName,
                                        isSplitApk = item.metadata.isSplitApk
                                    )
                                },
                                onReceiveNextApp = { viewModel.receiveNextNearbyApp() },
                                onOpenAppClick = {
                                    if (!viewModel.openInstalledApp(state.metadata.packageName)) {
                                        Toast.makeText(
                                            context,
                                            context.getString(R.string.receiver_no_launcher_icon_toast),
                                            Toast.LENGTH_SHORT
                                        ).show()
                                    }
                                },
                                onDone = handleBack
                            )
                        }

                        is NearbyReceiverState.Cancelled -> {
                            TransferCancelledView(
                                accentColor = SuccessGreen,
                                onTryAgain = {
                                    missingPerms = PermissionHelper.missingPermissions(context, requiredPermissions)
                                    connectivity = PermissionHelper.checkConnectivity(context)
                                    when {
                                        missingPerms.isNotEmpty() -> {
                                            permissionLauncher.launch(missingPerms.toTypedArray())
                                        }
                                        !connectivity.isReadyForNearby -> {
                                            showConnectivityDialog = true
                                        }
                                        else -> {
                                            viewModel.retryCancelledNearbyTransfer()
                                        }
                                    }
                                },
                                onDone = handleBack
                            )
                        }

                        is NearbyReceiverState.Error -> {
                            val interrupted = state.interruptedRecord
                            Spacer(modifier = Modifier.height(12.dp))
                            Icon(
                                imageVector = Icons.Rounded.ErrorOutline,
                                contentDescription = stringResource(R.string.cd_error),
                                tint = MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(64.dp)
                            )
                            if (interrupted != null) {
                                Text(
                                    text = stringResource(
                                        R.string.transfer_interrupted_progress,
                                        FormatUtils.formatTransferSize(interrupted.totalReceivedBytes),
                                        FormatUtils.formatTransferSize(interrupted.totalSizeBytes)
                                    ),
                                    style = MaterialTheme.typography.titleLarge,
                                    fontWeight = FontWeight.Bold,
                                    textAlign = TextAlign.Center,
                                    modifier = Modifier.testTag("interrupted_progress_text")
                                )
                                Text(
                                    text = interrupted.metadata.appName,
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    textAlign = TextAlign.Center
                                )
                            } else {
                                Text(
                                    text = stringResource(R.string.receiver_transfer_interrupted_title),
                                    style = MaterialTheme.typography.headlineSmall,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                            Text(
                                text = state.reason,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center
                            )
                            if (interrupted != null) {
                                Button(
                                    onClick = {
                                        missingPerms = PermissionHelper.missingPermissions(context, requiredPermissions)
                                        connectivity = PermissionHelper.checkConnectivity(context)
                                        when {
                                            missingPerms.isNotEmpty() -> {
                                                permissionLauncher.launch(missingPerms.toTypedArray())
                                            }
                                            !connectivity.isReadyForNearby -> {
                                                showConnectivityDialog = true
                                            }
                                            else -> {
                                                viewModel.resumeInterruptedNearbyTransfer(interrupted)
                                            }
                                        }
                                    },
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = SuccessGreen,
                                        contentColor = Color.White
                                    ),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(50.dp)
                                        .testTag("resume_interrupted_nearby_button")
                                ) {
                                    Icon(Icons.Rounded.PlayArrow, contentDescription = null)
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = stringResource(R.string.transfer_resume_button),
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                                TextButton(
                                    onClick = {
                                        viewModel.clearInterruptedTransfer()
                                        Toast.makeText(
                                            context,
                                            context.getString(R.string.transfer_resume_cleared_toast),
                                            Toast.LENGTH_SHORT
                                        ).show()
                                    },
                                    modifier = Modifier
                                        .minimumInteractiveComponentSize()
                                        .testTag("clear_interrupted_nearby_button")
                                ) {
                                    Text(
                                        text = stringResource(R.string.transfer_action_clear_partial),
                                        color = MaterialTheme.colorScheme.error
                                    )
                                }
                            } else {
                                Button(
                                    onClick = {
                                        missingPerms = PermissionHelper.missingPermissions(context, requiredPermissions)
                                        connectivity = PermissionHelper.checkConnectivity(context)
                                        when {
                                            missingPerms.isNotEmpty() -> {
                                                permissionLauncher.launch(missingPerms.toTypedArray())
                                            }
                                            !connectivity.isReadyForNearby -> {
                                                showConnectivityDialog = true
                                            }
                                            else -> {
                                                viewModel.startNearbyDiscovery()
                                            }
                                        }
                                    },
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = SuccessGreen,
                                        contentColor = Color.White
                                    ),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(50.dp)
                                        .testTag("retry_nearby_receiver_button")
                                ) {
                                    Icon(Icons.Rounded.Refresh, contentDescription = null)
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(stringResource(R.string.sender_retry_nearby))
                                }
                            }
                            OutlinedButton(
                                onClick = { viewModel.switchToHotspotMode() },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(50.dp)
                            ) {
                                Icon(Icons.Rounded.QrCodeScanner, contentDescription = null)
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(stringResource(R.string.sender_try_hotspot_mode))
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))
            }
        }
    }
}

@Composable
private fun HotspotReceiverContent(
    httpState: HttpDownloadState,
    installState: InstallState,
    canRequestInstalls: Boolean,
    receivedApps: List<ReceivedAppItem>,
    onStartDownload: (String) -> Unit,
    onResumeInterruptedHotspot: (InterruptedTransferRecord) -> Unit,
    onClearInterruptedTransfer: () -> Unit,
    onCancelActiveDownload: () -> Unit,
    onRetryCancelledDownload: (String) -> Unit,
    onCancelDownload: () -> Unit,
    onSwitchToNearby: () -> Unit,
    onOpenUnknownSources: () -> Unit,
    onInstall: (List<File>, ApkBundleMetadata) -> Unit,
    onReceiveNextApp: () -> Unit,
    onOpenInstalled: (String) -> Unit,
    onDone: () -> Unit
) {
    val context = LocalContext.current
    var urlInput by remember { mutableStateOf("http://192.168.43.1:8080/app.apk") }
    var showScanner by remember { mutableStateOf(false) }
    var hasCameraPerm by remember { mutableStateOf(PermissionHelper.hasCameraPermission(context)) }

    val cameraLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasCameraPerm = granted
        if (granted) {
            showScanner = true
        } else {
            Toast.makeText(
                context,
                context.getString(R.string.receiver_camera_perm_qr_toast),
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    when (httpState) {
        is HttpDownloadState.Idle -> {
            Card(
                shape = RoundedCornerShape(18.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = stringResource(R.string.receiver_hotspot_connect_title),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = stringResource(R.string.receiver_hotspot_connect_steps),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            if (showScanner && hasCameraPerm) {
                QrCodeScannerView(
                    onQrDetected = { scanned ->
                        showScanner = false
                        urlInput = scanned
                        onStartDownload(scanned)
                    }
                )
                TextButton(onClick = { showScanner = false }) {
                    Text(stringResource(R.string.receiver_hide_scanner))
                }
            } else {
                Button(
                    onClick = {
                        if (hasCameraPerm) {
                            showScanner = true
                        } else {
                            cameraLauncher.launch(Manifest.permission.CAMERA)
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = SuccessGreen, contentColor = Color.White),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp)
                        .testTag("scan_qr_button")
                ) {
                    Icon(Icons.Rounded.QrCodeScanner, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(stringResource(R.string.receiver_scan_sender_qr), fontWeight = FontWeight.Bold)
                }
            }

            OutlinedTextField(
                value = urlInput,
                onValueChange = { urlInput = it },
                label = { Text(stringResource(R.string.receiver_sender_url_label)) },
                placeholder = { Text("http://192.168.43.1:8080/app.apk") },
                singleLine = true,
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("hotspot_url_input")
            )

            Button(
                onClick = { onStartDownload(urlInput) },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(50.dp)
                    .testTag("download_from_url_button")
            ) {
                Icon(Icons.Rounded.Download, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text(stringResource(R.string.receiver_download_apk_button), fontWeight = FontWeight.Bold)
            }

            TextButton(onClick = onSwitchToNearby) {
                Text(stringResource(R.string.receiver_switch_to_nearby))
            }
        }

        is HttpDownloadState.Connecting -> {
            Spacer(modifier = Modifier.height(24.dp))
            CircularProgressIndicator(color = SuccessGreen)
            Text(stringResource(R.string.receiver_connecting_to, httpState.url))
            TextButton(
                onClick = onCancelActiveDownload,
                modifier = Modifier.testTag("cancel_hotspot_download_button")
            ) {
                Text(stringResource(R.string.action_cancel), color = MaterialTheme.colorScheme.error)
            }
        }

        is HttpDownloadState.Downloading -> {
            CircularTransferProgress(
                progress = httpState.progress,
                accentColor = SuccessGreen,
                title = stringResource(R.string.receiver_downloading_app, httpState.metadata.appName),
                subtitle = stringResource(R.string.receiver_via_hotspot_http),
                directionLabel = stringResource(R.string.progress_direction_received)
            )
            if (!httpState.statusNotice.isNullOrBlank()) {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.7f),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("hotspot_resume_notice_banner")
                ) {
                    Text(
                        text = httpState.statusNotice,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)
                    )
                }
            }
            OutlinedButton(
                onClick = onCancelActiveDownload,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .testTag("cancel_hotspot_download_button")
            ) {
                Text(stringResource(R.string.receiver_cancel_download), color = MaterialTheme.colorScheme.error)
            }
        }

        is HttpDownloadState.Completed -> {
            DownloadCompleteInstallSection(
                metadata = httpState.metadata,
                savedFiles = httpState.savedFiles,
                displayLocation = httpState.displayLocation,
                durationMillis = httpState.durationMillis,
                installState = installState,
                canRequestInstalls = canRequestInstalls,
                receivedApps = receivedApps,
                onOpenUnknownSources = onOpenUnknownSources,
                onInstallClick = { onInstall(httpState.savedFiles, httpState.metadata) },
                onInstallReceivedItem = { item -> onInstall(item.savedFiles, item.metadata) },
                onReceiveNextApp = onReceiveNextApp,
                onOpenAppClick = { onOpenInstalled(httpState.metadata.packageName) },
                onDone = onDone
            )
        }

        is HttpDownloadState.Cancelled -> {
            TransferCancelledView(
                accentColor = SuccessGreen,
                onTryAgain = { onRetryCancelledDownload(httpState.url) },
                onDone = onCancelDownload
            )
        }

        is HttpDownloadState.Error -> {
            val interrupted = httpState.interruptedRecord
            Icon(
                imageVector = Icons.Rounded.ErrorOutline,
                contentDescription = stringResource(R.string.receiver_download_error_cd),
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.size(64.dp)
            )
            if (interrupted != null) {
                Text(
                    text = stringResource(
                        R.string.transfer_interrupted_progress,
                        FormatUtils.formatTransferSize(interrupted.totalReceivedBytes),
                        FormatUtils.formatTransferSize(interrupted.totalSizeBytes)
                    ),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.testTag("hotspot_interrupted_progress_text")
                )
                Text(
                    text = interrupted.metadata.appName,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.Center
                )
            } else {
                Text(
                    text = stringResource(R.string.receiver_download_failed_title),
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold
                )
            }
            Text(
                text = httpState.reason,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
            Button(
                onClick = {
                    if (interrupted != null) {
                        onResumeInterruptedHotspot(interrupted)
                    } else {
                        onStartDownload(httpState.url)
                    }
                },
                colors = ButtonDefaults.buttonColors(containerColor = SuccessGreen, contentColor = Color.White),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(50.dp)
                    .testTag("resume_interrupted_hotspot_button")
            ) {
                Icon(
                    imageVector = if (interrupted != null) Icons.Rounded.PlayArrow else Icons.Rounded.Refresh,
                    contentDescription = null
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = stringResource(R.string.receiver_resume_retry_download),
                    fontWeight = FontWeight.Bold
                )
            }
            if (interrupted != null) {
                TextButton(
                    onClick = onClearInterruptedTransfer,
                    modifier = Modifier
                        .minimumInteractiveComponentSize()
                        .testTag("clear_interrupted_hotspot_button")
                ) {
                    Text(
                        text = stringResource(R.string.transfer_action_clear_partial),
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
            OutlinedButton(
                onClick = onCancelDownload,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(stringResource(R.string.receiver_edit_url_scan_again))
            }
        }
    }
}

@Composable
private fun DownloadCompleteInstallSection(
    metadata: ApkBundleMetadata,
    savedFiles: List<File>,
    displayLocation: String,
    durationMillis: Long,
    installState: InstallState,
    canRequestInstalls: Boolean,
    receivedApps: List<ReceivedAppItem>,
    onOpenUnknownSources: () -> Unit,
    onInstallClick: () -> Unit,
    onInstallReceivedItem: (ReceivedAppItem) -> Unit,
    onReceiveNextApp: () -> Unit,
    onOpenAppClick: () -> Unit,
    onDone: () -> Unit
) {
    val context = LocalContext.current
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Box(
            modifier = Modifier
                .size(84.dp)
                .clip(CircleShape)
                .background(SuccessGreen.copy(alpha = 0.14f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Rounded.CheckCircle,
                contentDescription = stringResource(R.string.receiver_download_complete_cd),
                tint = SuccessGreen,
                modifier = Modifier.size(52.dp)
            )
        }

        Text(
            text = stringResource(R.string.receiver_download_complete_title),
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            color = SuccessGreen
        )

        Card(
            shape = RoundedCornerShape(18.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier.padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (metadata.isGenericFile) {
                    Box(
                        modifier = Modifier
                            .size(54.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .background(SuccessGreen.copy(alpha = 0.14f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = iconForMimeType(metadata.mimeType, metadata.appName),
                            contentDescription = null,
                            tint = SuccessGreen,
                            modifier = Modifier.size(30.dp)
                        )
                    }
                } else {
                    Base64AppIcon(iconBase64 = metadata.iconBase64, size = 54.dp, tint = SuccessGreen)
                }
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        text = metadata.appName,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = if (metadata.isGenericFile) {
                            stringResource(
                                R.string.receiver_file_complete_meta,
                                FormatUtils.formatBytes(metadata.totalSizeBytes),
                                FormatUtils.formatDurationMillis(durationMillis)
                            )
                        } else {
                            stringResource(
                                R.string.receiver_complete_meta,
                                metadata.versionName,
                                FormatUtils.formatBytes(metadata.totalSizeBytes),
                                FormatUtils.formatDurationMillis(durationMillis)
                            )
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Rounded.FolderOpen,
                            contentDescription = null,
                            modifier = Modifier.size(14.dp),
                            tint = PrimaryBlue
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = displayLocation,
                            style = MaterialTheme.typography.labelSmall,
                            color = PrimaryBlue
                        )
                    }
                    if (!metadata.isGenericFile && metadata.isSplitApk) {
                        Text(
                            text = stringResource(R.string.receiver_split_installer_note, savedFiles.size),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }

        if (metadata.isGenericFile) {
            if (savedFiles.isNotEmpty()) {
                Button(
                    onClick = {
                        val opened = FileTransferHelper.openFileWithSystemViewer(
                            context = context,
                            file = savedFiles.first(),
                            mimeType = metadata.mimeType
                        )
                        if (!opened) {
                            Toast.makeText(
                                context,
                                context.getString(R.string.receiver_no_viewer_toast),
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = SuccessGreen,
                        contentColor = Color.White
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp)
                        .testTag("open_received_file_button")
                ) {
                    Icon(Icons.AutoMirrored.Rounded.Launch, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        stringResource(R.string.receiver_open_file_button),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        } else {
            if (!canRequestInstalls) {
                UnknownSourcesPermissionCard(onOpenSettings = onOpenUnknownSources)
            }

            when (installState) {
                is InstallState.Idle -> {
                    Button(
                        onClick = onInstallClick,
                        enabled = canRequestInstalls,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = SuccessGreen,
                            contentColor = Color.White
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(56.dp)
                            .testTag("install_apk_button")
                    ) {
                        Icon(Icons.Rounded.InstallMobile, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            stringResource(R.string.receiver_install_app_button),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                is InstallState.Installing -> {
                    CircularProgressIndicator(color = SuccessGreen)
                    Text(
                        text = installState.detail,
                        style = MaterialTheme.typography.bodyMedium,
                        textAlign = TextAlign.Center
                    )
                }

                is InstallState.PendingUserConfirmation -> {
                    Text(
                        text = stringResource(R.string.receiver_confirm_system_install),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )
                    OutlinedButton(
                        onClick = onInstallClick,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(stringResource(R.string.receiver_tap_install_again))
                    }
                }

                is InstallState.Installed -> {
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = SuccessGreen.copy(alpha = 0.14f),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = stringResource(R.string.receiver_installed_success_title),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = SuccessGreen,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(14.dp)
                        )
                    }
                    if (metadata.packageName.isNotBlank()) {
                        Button(
                            onClick = onOpenAppClick,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = PrimaryBlue,
                                contentColor = Color.White
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(54.dp)
                                .testTag("open_installed_app_button")
                        ) {
                            Icon(Icons.AutoMirrored.Rounded.Launch, contentDescription = null)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(stringResource(R.string.receiver_installed_open_app), fontWeight = FontWeight.Bold)
                        }
                    }
                }

                is InstallState.Failed -> {
                    Surface(
                        shape = RoundedCornerShape(14.dp),
                        color = MaterialTheme.colorScheme.errorContainer,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = installState.reason,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            modifier = Modifier.padding(14.dp)
                        )
                    }
                    Button(
                        onClick = onInstallClick,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = SuccessGreen,
                            contentColor = Color.White
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(52.dp)
                    ) {
                        Text(stringResource(R.string.receiver_retry_install), fontWeight = FontWeight.Bold)
                    }
                }
            }
        }

        OutlinedButton(
            onClick = onReceiveNextApp,
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp)
                .testTag("receiver_receive_next_button")
        ) {
            Icon(Icons.Rounded.Download, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                stringResource(
                    if (metadata.isGenericFile) {
                        R.string.receiver_receive_next_file
                    } else {
                        R.string.receiver_receive_next_app
                    }
                ),
                fontWeight = FontWeight.SemiBold
            )
        }

        if (receivedApps.size > 1) {
            val hasFiles = receivedApps.any { it.metadata.isGenericFile }
            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("receiver_batch_list_card")
            ) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = stringResource(
                            if (hasFiles) {
                                R.string.receiver_received_files_batch_title
                            } else {
                                R.string.receiver_received_batch_title
                            },
                            receivedApps.size
                        ),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
                    )
                    receivedApps.forEach { item ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = item.metadata.appName,
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Text(
                                    text = if (item.metadata.isGenericFile) {
                                        FormatUtils.formatBytes(item.metadata.totalSizeBytes)
                                    } else {
                                        "v${item.metadata.versionName} · ${FormatUtils.formatBytes(item.metadata.totalSizeBytes)}"
                                    },
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            if (item.metadata.isGenericFile) {
                                TextButton(
                                    onClick = {
                                        val file = item.savedFiles.firstOrNull()
                                        val opened = file != null && FileTransferHelper.openFileWithSystemViewer(
                                            context = context,
                                            file = file,
                                            mimeType = item.metadata.mimeType
                                        )
                                        if (!opened) {
                                            Toast.makeText(
                                                context,
                                                context.getString(R.string.receiver_no_viewer_toast),
                                                Toast.LENGTH_SHORT
                                            ).show()
                                        }
                                    },
                                    enabled = item.savedFiles.isNotEmpty(),
                                    modifier = Modifier.testTag("open_batch_file_${item.metadata.appName}")
                                ) {
                                    Text(stringResource(R.string.receiver_open_short))
                                }
                            } else {
                                TextButton(
                                    onClick = { onInstallReceivedItem(item) },
                                    enabled = canRequestInstalls,
                                    modifier = Modifier.testTag("install_batch_item_${item.metadata.packageName}")
                                ) {
                                    Text(stringResource(R.string.receiver_install_short))
                                }
                            }
                        }
                    }
                }
            }
        }

        TextButton(onClick = onDone) {
            Text(stringResource(R.string.receiver_back_to_home))
        }
    }
}
