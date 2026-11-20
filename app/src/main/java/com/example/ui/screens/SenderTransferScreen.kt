package com.example.ui.screens

import android.content.Intent
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
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
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Settings
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
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.R
import com.example.model.ExtractedApkBundle
import com.example.transfer.HotspotInfo
import com.example.transfer.HttpServerStatus
import com.example.transfer.NearbySenderState
import com.example.ui.components.Base64AppIcon
import com.example.ui.components.CircularTransferProgress
import com.example.ui.components.ConnectivityRequiredCard
import com.example.ui.components.ConnectivityRequiredDialog
import com.example.ui.components.PermissionRationaleCard
import com.example.ui.components.PulseRadarAnimation
import com.example.ui.components.TransferProgressBar
import com.example.ui.theme.PrimaryBlue
import com.example.ui.theme.SuccessGreen
import com.example.ui.viewmodel.BatchTransferQueueState
import com.example.ui.viewmodel.ExtractionUiState
import com.example.ui.viewmodel.SenderViewModel
import com.example.util.FormatUtils
import com.example.util.PermissionHelper
import com.example.util.QrCodeUtils

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SenderTransferScreen(
    viewModel: SenderViewModel,
    onDone: () -> Unit
) {
    val context = LocalContext.current
    val nearbyState by viewModel.nearbyState.collectAsStateWithLifecycle()
    val extractionState by viewModel.extractionState.collectAsStateWithLifecycle()
    val hotspotModeActive by viewModel.hotspotModeActive.collectAsStateWithLifecycle()
    val hotspotInfo by viewModel.hotspotInfo.collectAsStateWithLifecycle()
    val serverStatus by viewModel.httpServerStatus.collectAsStateWithLifecycle()
    val batchQueue by viewModel.batchQueue.collectAsStateWithLifecycle()

    val nearbyPermissions = remember { PermissionHelper.getNearbyTransferPermissions() }
    val hotspotPermissions = remember { PermissionHelper.getHotspotPermissions() }

    var missingNearbyPerms by remember {
        mutableStateOf(PermissionHelper.missingPermissions(context, nearbyPermissions))
    }
    var missingHotspotPerms by remember {
        mutableStateOf(PermissionHelper.missingPermissions(context, hotspotPermissions))
    }
    var nearbyPermRequested by remember { mutableStateOf(false) }
    var hotspotPermRequested by remember { mutableStateOf(false) }
    var connectivity by remember { mutableStateOf(PermissionHelper.checkConnectivity(context)) }
    var showConnectivityDialog by remember { mutableStateOf(false) }
    var hotspotStarted by remember { mutableStateOf(false) }

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

    val nearbyPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        missingNearbyPerms = PermissionHelper.missingPermissions(context, nearbyPermissions)
        connectivity = PermissionHelper.checkConnectivity(context)
        if (missingNearbyPerms.isEmpty() && !hotspotModeActive && !connectivity.isReadyForNearby) {
            showConnectivityDialog = true
        }
    }

    val hotspotPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        missingHotspotPerms = PermissionHelper.missingPermissions(context, hotspotPermissions)
    }

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        missingNearbyPerms = PermissionHelper.missingPermissions(context, nearbyPermissions)
        missingHotspotPerms = PermissionHelper.missingPermissions(context, hotspotPermissions)
        connectivity = PermissionHelper.checkConnectivity(context)
        if (!hotspotModeActive && missingNearbyPerms.isEmpty()) {
            showConnectivityDialog = !connectivity.isReadyForNearby
        }
    }

    LaunchedEffect(hotspotModeActive, missingNearbyPerms, connectivity, extractionState, batchQueue.isFinished) {
        if (!hotspotModeActive && !batchQueue.isFinished) {
            hotspotStarted = false
            when {
                missingNearbyPerms.isNotEmpty() -> {
                    if (!nearbyPermRequested) {
                        nearbyPermRequested = true
                        nearbyPermissionLauncher.launch(missingNearbyPerms.toTypedArray())
                    }
                }
                !connectivity.isReadyForNearby -> {
                    showConnectivityDialog = true
                }
                else -> {
                    showConnectivityDialog = false
                    if (extractionState is ExtractionUiState.Ready && nearbyState is NearbySenderState.Idle) {
                        viewModel.startNearbyAdvertising()
                    }
                }
            }
        }
    }

    LaunchedEffect(hotspotModeActive, missingHotspotPerms, batchQueue.isFinished) {
        if (hotspotModeActive && !batchQueue.isFinished) {
            showConnectivityDialog = false
            if (missingHotspotPerms.isNotEmpty()) {
                if (!hotspotPermRequested) {
                    hotspotPermRequested = true
                    hotspotPermissionLauncher.launch(missingHotspotPerms.toTypedArray())
                }
            } else if (!hotspotStarted) {
                hotspotStarted = true
                viewModel.startLocalHotspot()
            }
        }
    }

    val handleBack: () -> Unit = {
        viewModel.cancelActiveTransfer()
        onDone()
    }
    BackHandler(onBack = handleBack)

    if (!hotspotModeActive && missingNearbyPerms.isEmpty() && !connectivity.isReadyForNearby && showConnectivityDialog) {
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
                        text = if (hotspotModeActive) {
                            stringResource(R.string.sender_title_hotspot)
                        } else {
                            stringResource(R.string.sender_title_nearby)
                        },
                        fontWeight = FontWeight.Bold
                    )
                },
                navigationIcon = {
                    IconButton(
                        onClick = handleBack,
                        modifier = Modifier
                            .minimumInteractiveComponentSize()
                            .testTag("sender_transfer_back")
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

                if (batchQueue.isBatch && batchQueue.isFinished) {
                    BatchTransferSummaryView(
                        batchQueue = batchQueue,
                        onDone = handleBack
                    )
                } else {
                    if (batchQueue.isBatch) {
                        BatchQueueProgressBanner(batchQueue = batchQueue)
                    }

                    if (batchQueue.isBatch && batchQueue.isPreparingNext) {
                        Spacer(modifier = Modifier.height(16.dp))
                        CircularProgressIndicator()
                        Text(
                            text = stringResource(
                                R.string.batch_preparing_next,
                                batchQueue.currentNumber,
                                batchQueue.totalCount,
                                batchQueue.currentApp?.appName.orEmpty()
                            ),
                            style = MaterialTheme.typography.bodyMedium,
                            textAlign = TextAlign.Center
                        )
                    } else if (hotspotModeActive) {
                        if (missingHotspotPerms.isNotEmpty()) {
                            PermissionRationaleCard(
                                missingPermissions = missingHotspotPerms,
                                onGrantClick = {
                                    hotspotPermissionLauncher.launch(missingHotspotPerms.toTypedArray())
                                },
                                title = stringResource(R.string.sender_hotspot_perms_title),
                                description = stringResource(R.string.sender_hotspot_perms_desc)
                            )
                        }
                        val bundle = (extractionState as? ExtractionUiState.Ready)?.bundle
                        if (bundle != null) {
                            HotspotSenderView(
                                bundle = bundle,
                                hotspotInfo = hotspotInfo,
                                serverStatus = serverStatus,
                                batchQueue = batchQueue,
                                onRefreshIp = { viewModel.refreshHotspotIp() },
                                onOpenHotspotSettings = {
                                    runCatching {
                                        context.startActivity(Intent(Settings.ACTION_WIRELESS_SETTINGS))
                                    }.onFailure {
                                        runCatching { context.startActivity(Intent(Settings.ACTION_SETTINGS)) }
                                    }
                                },
                                onMarkSent = {
                                    if (batchQueue.isBatch) {
                                        viewModel.advanceHotspotBatchQueue(markCurrentSucceeded = true)
                                    } else {
                                        viewModel.markHotspotTransferComplete()
                                        Toast.makeText(
                                            context,
                                            context.getString(R.string.sender_saved_history_toast),
                                            Toast.LENGTH_SHORT
                                        ).show()
                                        handleBack()
                                    }
                                },
                                onSkipBatchItem = {
                                    viewModel.advanceHotspotBatchQueue(markCurrentSucceeded = false)
                                },
                                onCancel = handleBack
                            )
                        }
                    } else if (missingNearbyPerms.isNotEmpty()) {
                    PermissionRationaleCard(
                        missingPermissions = missingNearbyPerms,
                        onGrantClick = { nearbyPermissionLauncher.launch(missingNearbyPerms.toTypedArray()) }
                    )
                    OutlinedButton(
                        onClick = { viewModel.switchToHotspotFallback() },
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("switch_hotspot_from_perms")
                    ) {
                        Icon(Icons.Rounded.WifiTethering, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(stringResource(R.string.sender_try_hotspot_instead))
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
                        onClick = { viewModel.switchToHotspotFallback() },
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("switch_hotspot_from_connectivity")
                    ) {
                        Icon(Icons.Rounded.WifiTethering, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(stringResource(R.string.sender_try_hotspot_instead))
                    }
                } else {
                    when (val state = nearbyState) {
                        is NearbySenderState.Idle -> {
                            CircularProgressIndicator()
                            Text(stringResource(R.string.sender_starting_transfer))
                        }

                        is NearbySenderState.WaitingForReceiver -> {
                            var showPairQr by remember { mutableStateOf(true) }
                            val pairCode = remember(state.bundle.transferId) {
                                state.bundle.transferId.take(4).uppercase()
                            }
                            val pairQrContent = remember(pairCode, state.deviceName) {
                                QrCodeUtils.buildNearbyPairQrContent(pairCode, state.deviceName)
                            }
                            val pairQrBitmap = remember(pairQrContent) {
                                QrCodeUtils.generateQrBitmap(pairQrContent, 420)
                            }

                            BundleSummaryCard(state.bundle)
                            Spacer(modifier = Modifier.height(6.dp))
                            PulseRadarAnimation(
                                color = PrimaryBlue,
                                centerIcon = {
                                    Icon(
                                        imageVector = Icons.Rounded.WifiTethering,
                                        contentDescription = stringResource(R.string.sender_advertising_cd),
                                        tint = Color.White,
                                        modifier = Modifier.size(32.dp)
                                    )
                                }
                            )
                            Text(
                                text = stringResource(R.string.sender_waiting_title),
                                style = MaterialTheme.typography.headlineSmall,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = stringResource(R.string.sender_waiting_subtitle, state.deviceName),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center
                            )

                            if (pairQrBitmap != null) {
                                Card(
                                    shape = RoundedCornerShape(20.dp),
                                    colors = CardDefaults.cardColors(
                                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                                    ),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .testTag("sender_nearby_qr_card")
                                ) {
                                    Column(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(14.dp),
                                        horizontalAlignment = Alignment.CenterHorizontally,
                                        verticalArrangement = Arrangement.spacedBy(10.dp)
                                    ) {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Text(
                                                text = stringResource(R.string.sender_find_device_qr_title, pairCode),
                                                style = MaterialTheme.typography.titleSmall,
                                                fontWeight = FontWeight.Bold
                                            )
                                            TextButton(
                                                onClick = { showPairQr = !showPairQr },
                                                modifier = Modifier.testTag("toggle_sender_qr_button")
                                            ) {
                                                Text(
                                                    if (showPairQr) {
                                                        stringResource(R.string.sender_hide_qr)
                                                    } else {
                                                        stringResource(R.string.sender_show_qr)
                                                    }
                                                )
                                            }
                                        }
                                        if (showPairQr) {
                                            Surface(
                                                shape = RoundedCornerShape(16.dp),
                                                color = Color.White,
                                                shadowElevation = 2.dp
                                            ) {
                                                Image(
                                                    bitmap = pairQrBitmap,
                                                    contentDescription = stringResource(R.string.sender_qr_cd),
                                                    modifier = Modifier
                                                        .size(172.dp)
                                                        .padding(10.dp)
                                                )
                                            }
                                            Text(
                                                text = stringResource(R.string.sender_qr_hint),
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                textAlign = TextAlign.Center
                                            )
                                        }
                                    }
                                }
                            }

                            Spacer(modifier = Modifier.height(4.dp))
                            OutlinedButton(
                                onClick = { viewModel.switchToHotspotFallback() },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(48.dp)
                                    .testTag("try_hotspot_mode_button")
                            ) {
                                Icon(Icons.Rounded.WifiTethering, contentDescription = null)
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(stringResource(R.string.sender_try_hotspot_mode))
                            }
                            if (batchQueue.isBatch) {
                                TextButton(
                                    onClick = { viewModel.skipCurrentBatchItem() },
                                    modifier = Modifier.testTag("nearby_skip_app_button")
                                ) {
                                    Text(stringResource(R.string.batch_hotspot_skip_app))
                                }
                            }
                            TextButton(
                                onClick = handleBack,
                                modifier = Modifier.testTag("cancel_waiting_button")
                            ) {
                                Text(stringResource(R.string.action_cancel), color = MaterialTheme.colorScheme.error)
                            }
                        }

                        is NearbySenderState.ConfirmConnection -> {
                            BundleSummaryCard(state.bundle)
                            AlertDialog(
                                onDismissRequest = {
                                    viewModel.rejectNearbyConnection(state.connection.endpointId)
                                },
                                title = { Text(stringResource(R.string.sender_accept_receiver_title)) },
                                text = {
                                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Text(
                                            stringResource(
                                                R.string.sender_accept_receiver_body,
                                                state.connection.deviceName,
                                                state.bundle.appName
                                            )
                                        )
                                        Surface(
                                            shape = RoundedCornerShape(10.dp),
                                            color = MaterialTheme.colorScheme.surfaceVariant
                                        ) {
                                            Text(
                                                text = stringResource(
                                                    R.string.sender_pairing_code,
                                                    state.connection.authDigits
                                                ),
                                                style = MaterialTheme.typography.titleMedium,
                                                fontWeight = FontWeight.Bold,
                                                modifier = Modifier.padding(12.dp)
                                            )
                                        }
                                    }
                                },
                                confirmButton = {
                                    Button(
                                        onClick = { viewModel.acceptNearbyConnection(state.connection.endpointId) },
                                        modifier = Modifier.testTag("accept_connection_button")
                                    ) {
                                        Text(stringResource(R.string.action_accept))
                                    }
                                },
                                dismissButton = {
                                    TextButton(
                                        onClick = { viewModel.rejectNearbyConnection(state.connection.endpointId) },
                                        modifier = Modifier.testTag("decline_connection_button")
                                    ) {
                                        Text(stringResource(R.string.action_decline))
                                    }
                                }
                            )
                        }

                        is NearbySenderState.WaitingReceiverAccept -> {
                            BundleSummaryCard(state.bundle)
                            Spacer(modifier = Modifier.height(24.dp))
                            CircularProgressIndicator()
                            Text(
                                text = stringResource(R.string.sender_connected_to, state.peerName),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = stringResource(R.string.sender_waiting_receiver_accept, state.peerName),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            TextButton(onClick = handleBack) {
                                Text(stringResource(R.string.action_cancel), color = MaterialTheme.colorScheme.error)
                            }
                        }

                        is NearbySenderState.Sending -> {
                            BundleSummaryCard(state.bundle)
                            CircularTransferProgress(
                                progress = state.progress,
                                accentColor = PrimaryBlue,
                                title = stringResource(R.string.sender_sending_to, state.peerName),
                                subtitle = stringResource(R.string.sender_keep_phones_close),
                                directionLabel = stringResource(R.string.progress_direction_sent)
                            )
                            OutlinedButton(
                                onClick = handleBack,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(48.dp)
                                    .testTag("cancel_sending_button")
                            ) {
                                Text(stringResource(R.string.sender_cancel_transfer), color = MaterialTheme.colorScheme.error)
                            }
                        }

                        is NearbySenderState.Success -> {
                            Spacer(modifier = Modifier.height(16.dp))
                            Box(
                                modifier = Modifier
                                    .size(88.dp)
                                    .clip(CircleShape)
                                    .background(SuccessGreen.copy(alpha = 0.14f)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.CheckCircle,
                                    contentDescription = stringResource(R.string.sender_sent_cd),
                                    tint = SuccessGreen,
                                    modifier = Modifier.size(56.dp)
                                )
                            }
                            Text(
                                text = stringResource(R.string.sender_sent_success_title),
                                style = MaterialTheme.typography.headlineMedium,
                                fontWeight = FontWeight.Bold,
                                color = SuccessGreen
                            )
                            Text(
                                text = stringResource(
                                    R.string.sender_sent_delivered_to,
                                    state.bundle.appName,
                                    state.peerName
                                ),
                                style = MaterialTheme.typography.bodyLarge,
                                textAlign = TextAlign.Center
                            )
                            Card(
                                shape = RoundedCornerShape(18.dp),
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(
                                    modifier = Modifier.padding(16.dp),
                                    verticalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    DetailRow(
                                        stringResource(R.string.sender_detail_app),
                                        stringResource(
                                            R.string.sender_detail_app_value,
                                            state.bundle.appName,
                                            state.bundle.versionName
                                        )
                                    )
                                    DetailRow(
                                        stringResource(R.string.sender_detail_total_size),
                                        FormatUtils.formatBytes(state.bundle.totalSizeBytes)
                                    )
                                    DetailRow(
                                        stringResource(R.string.sender_detail_time_taken),
                                        FormatUtils.formatDurationMillis(state.durationMillis)
                                    )
                                    DetailRow(
                                        stringResource(R.string.sender_detail_package_type),
                                        if (state.bundle.isSplitApk) {
                                            stringResource(R.string.label_split_apk_bundle, state.bundle.files.size)
                                        } else {
                                            stringResource(R.string.label_single_apk)
                                        }
                                    )
                                }
                            }
                            Button(
                                onClick = handleBack,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(52.dp)
                                    .testTag("sent_done_button")
                            ) {
                                Text(stringResource(R.string.action_done), fontWeight = FontWeight.Bold)
                            }
                        }

                        is NearbySenderState.Error -> {
                            Spacer(modifier = Modifier.height(12.dp))
                            Icon(
                                imageVector = Icons.Rounded.ErrorOutline,
                                contentDescription = stringResource(R.string.cd_error),
                                tint = MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(64.dp)
                            )
                            Text(
                                text = stringResource(R.string.sender_transfer_failed_title),
                                style = MaterialTheme.typography.headlineSmall,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = state.reason,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center
                            )
                            Button(
                                onClick = {
                                    missingNearbyPerms = PermissionHelper.missingPermissions(context, nearbyPermissions)
                                    connectivity = PermissionHelper.checkConnectivity(context)
                                    when {
                                        missingNearbyPerms.isNotEmpty() -> {
                                            nearbyPermissionLauncher.launch(missingNearbyPerms.toTypedArray())
                                        }
                                        !connectivity.isReadyForNearby -> {
                                            showConnectivityDialog = true
                                        }
                                        else -> {
                                            viewModel.retryNearbyAdvertising()
                                        }
                                    }
                                },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(50.dp)
                                    .testTag("retry_nearby_sender_button")
                            ) {
                                Icon(Icons.Rounded.Refresh, contentDescription = null)
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(stringResource(R.string.sender_retry_nearby))
                            }
                            OutlinedButton(
                                onClick = { viewModel.switchToHotspotFallback() },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(50.dp)
                                    .testTag("fallback_hotspot_sender_button")
                            ) {
                                Icon(Icons.Rounded.WifiTethering, contentDescription = null)
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(stringResource(R.string.sender_try_hotspot_mode))
                            }
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
private fun HotspotSenderView(
    bundle: ExtractedApkBundle,
    hotspotInfo: HotspotInfo,
    serverStatus: HttpServerStatus,
    batchQueue: BatchTransferQueueState,
    onRefreshIp: () -> Unit,
    onOpenHotspotSettings: () -> Unit,
    onMarkSent: () -> Unit,
    onSkipBatchItem: () -> Unit,
    onCancel: () -> Unit
) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current

    val endpointPath = if (bundle.isSplitApk) "manifest.json" else "app.apk"
    val fullUrl = "http://${hotspotInfo.ipAddress}:${serverStatus.port}/$endpointPath"
    val displayApkUrl = "http://${hotspotInfo.ipAddress}:${serverStatus.port}/app.apk"
    val qrBitmap = remember(fullUrl) { QrCodeUtils.generateQrBitmap(fullUrl, 520) }

    BundleSummaryCard(bundle)

    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = if (hotspotInfo.isActive) {
                        stringResource(R.string.sender_hotspot_active)
                    } else {
                        stringResource(R.string.sender_hotspot_server_ready)
                    },
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = if (hotspotInfo.isActive) SuccessGreen else PrimaryBlue
                )
                IconButton(onClick = onRefreshIp) {
                    Icon(Icons.Rounded.Refresh, contentDescription = stringResource(R.string.sender_refresh_ip_cd))
                }
            }
            Text(
                text = hotspotInfo.statusMessage,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (!hotspotInfo.ssid.isNullOrBlank()) {
                DetailRow(stringResource(R.string.sender_hotspot_ssid_label), hotspotInfo.ssid)
            }
            if (!hotspotInfo.passphrase.isNullOrBlank()) {
                DetailRow(stringResource(R.string.sender_hotspot_password_label), hotspotInfo.passphrase)
            }
            if (!hotspotInfo.isActive) {
                OutlinedButton(
                    onClick = onOpenHotspotSettings,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Rounded.Settings, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(stringResource(R.string.sender_open_hotspot_settings))
                }
            }
        }
    }

    if (qrBitmap != null) {
        Surface(
            shape = RoundedCornerShape(22.dp),
            color = Color.White,
            shadowElevation = 4.dp
        ) {
            Image(
                bitmap = qrBitmap,
                contentDescription = stringResource(R.string.sender_hotspot_qr_cd),
                modifier = Modifier
                    .size(220.dp)
                    .padding(14.dp)
            )
        }
    }

    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.sender_download_url_port, serverStatus.port),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = displayApkUrl,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold
                )
            }
            IconButton(
                onClick = {
                    clipboard.setText(AnnotatedString(fullUrl))
                    Toast.makeText(
                        context,
                        context.getString(R.string.sender_url_copied_toast),
                        Toast.LENGTH_SHORT
                    ).show()
                },
                modifier = Modifier.testTag("copy_hotspot_url_button")
            ) {
                Icon(Icons.Rounded.ContentCopy, contentDescription = stringResource(R.string.sender_copy_url_cd))
            }
        }
    }

    val effectiveTotalBytes = serverStatus.totalBytes.takeIf { it > 0L } ?: bundle.totalSizeBytes
    Card(
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
        ),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("hotspot_transfer_progress_card")
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            TransferProgressBar(
                bytesTransferred = serverStatus.bytesServed,
                totalBytes = effectiveTotalBytes,
                accentColor = if (serverStatus.bytesServed >= effectiveTotalBytes && effectiveTotalBytes > 0L) SuccessGreen else PrimaryBlue,
                directionLabel = stringResource(R.string.progress_direction_sent),
                fileName = when {
                    serverStatus.activeClients > 0 -> stringResource(R.string.sender_hotspot_sending_app, bundle.appName)
                    serverStatus.bytesServed >= effectiveTotalBytes && effectiveTotalBytes > 0L -> stringResource(R.string.sender_hotspot_sent_app, bundle.appName)
                    serverStatus.bytesServed > 0L -> stringResource(R.string.sender_hotspot_paused_app, bundle.appName)
                    else -> stringResource(R.string.sender_hotspot_waiting_download)
                },
                testTagName = "hotspot_transfer_progress_bar"
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = serverStatus.lastEvent,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f)
                )
                if (serverStatus.speedBytesPerSec > 0L) {
                    Text(
                        text = FormatUtils.formatSpeed(serverStatus.speedBytesPerSec),
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = PrimaryBlue
                    )
                }
            }
        }
    }

    val hasMoreInBatch = batchQueue.isBatch && batchQueue.currentIndex < batchQueue.totalCount - 1
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        OutlinedButton(
            onClick = onCancel,
            modifier = Modifier.weight(1f)
        ) {
            Text(stringResource(R.string.sender_stop_server))
        }
        Button(
            onClick = onMarkSent,
            colors = ButtonDefaults.buttonColors(containerColor = SuccessGreen, contentColor = Color.White),
            modifier = Modifier
                .weight(1f)
                .testTag(if (hasMoreInBatch) "hotspot_next_app_button" else "hotspot_done_button")
        ) {
            Text(
                text = if (hasMoreInBatch) {
                    stringResource(
                        R.string.batch_hotspot_next_app,
                        batchQueue.currentNumber + 1,
                        batchQueue.totalCount
                    )
                } else {
                    stringResource(R.string.action_done)
                }
            )
        }
    }
    if (batchQueue.isBatch) {
        TextButton(
            onClick = onSkipBatchItem,
            modifier = Modifier.testTag("hotspot_skip_app_button")
        ) {
            Text(stringResource(R.string.batch_hotspot_skip_app))
        }
    }
}

@Composable
private fun BatchQueueProgressBanner(batchQueue: BatchTransferQueueState) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = PrimaryBlue.copy(alpha = 0.14f),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("batch_progress_banner")
    ) {
        Text(
            text = if (batchQueue.isPreparingNext) {
                stringResource(
                    R.string.batch_preparing_next,
                    batchQueue.currentNumber,
                    batchQueue.totalCount,
                    batchQueue.currentApp?.appName.orEmpty()
                )
            } else {
                stringResource(
                    R.string.batch_sending_progress,
                    batchQueue.currentNumber,
                    batchQueue.totalCount,
                    batchQueue.currentApp?.appName.orEmpty()
                )
            },
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            color = PrimaryBlue,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)
        )
    }
}

@Composable
private fun BatchTransferSummaryView(
    batchQueue: BatchTransferQueueState,
    onDone: () -> Unit
) {
    val sentCount = batchQueue.sentCount
    val totalCount = batchQueue.totalCount
    val failedItems = batchQueue.failedItems
    val anySucceeded = sentCount > 0

    Spacer(modifier = Modifier.height(16.dp))
    Box(
        modifier = Modifier
            .size(88.dp)
            .clip(CircleShape)
            .background(
                if (anySucceeded) SuccessGreen.copy(alpha = 0.14f)
                else MaterialTheme.colorScheme.errorContainer
            ),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = if (anySucceeded) Icons.Rounded.CheckCircle else Icons.Rounded.ErrorOutline,
            contentDescription = stringResource(
                if (anySucceeded) R.string.sender_sent_cd else R.string.cd_error
            ),
            tint = if (anySucceeded) SuccessGreen else MaterialTheme.colorScheme.error,
            modifier = Modifier.size(56.dp)
        )
    }

    Text(
        text = stringResource(R.string.batch_summary_title),
        style = MaterialTheme.typography.headlineMedium,
        fontWeight = FontWeight.Bold,
        color = if (anySucceeded) SuccessGreen else MaterialTheme.colorScheme.error,
        textAlign = TextAlign.Center
    )

    Text(
        text = if (anySucceeded) {
            stringResource(R.string.batch_summary_sent_count, sentCount, totalCount)
        } else {
            stringResource(R.string.batch_summary_all_failed)
        },
        style = MaterialTheme.typography.bodyLarge,
        textAlign = TextAlign.Center
    )

    if (failedItems.isNotEmpty()) {
        Card(
            shape = RoundedCornerShape(18.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.45f)
            ),
            modifier = Modifier
                .fillMaxWidth()
                .testTag("batch_summary_card")
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(
                    text = stringResource(R.string.batch_summary_failed_header),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.error
                )
                failedItems.forEach { item ->
                    val reason = item.errorReason?.takeIf { it.isNotBlank() }
                    Text(
                        text = if (reason != null) {
                            stringResource(R.string.batch_summary_failed_item, item.appName, reason)
                        } else {
                            stringResource(R.string.batch_summary_failed_item_simple, item.appName)
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer
                    )
                }
            }
        }
    } else {
        Surface(
            color = Color.Transparent,
            modifier = Modifier.testTag("batch_summary_card")
        ) {}
    }

    Button(
        onClick = onDone,
        modifier = Modifier
            .fillMaxWidth()
            .height(52.dp)
            .testTag("batch_done_button")
    ) {
        Text(stringResource(R.string.action_done), fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun BundleSummaryCard(bundle: ExtractedApkBundle) {
    Card(
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Base64AppIcon(iconBase64 = bundle.iconBase64, size = 48.dp)
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = bundle.appName,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = stringResource(
                        R.string.sender_bundle_meta,
                        bundle.versionName,
                        FormatUtils.formatBytes(bundle.totalSizeBytes)
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = if (bundle.isSplitApk) {
                        stringResource(R.string.label_split_apk_bundle, bundle.files.size)
                    } else {
                        bundle.files.firstOrNull()?.name.orEmpty()
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = PrimaryBlue
                )
            }
        }
    }
}

@Composable
private fun DetailRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold
        )
    }
}
