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
import androidx.compose.material.icons.rounded.PhoneAndroid
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.installer.InstallState
import com.example.model.ApkBundleMetadata
import com.example.transfer.HttpDownloadState
import com.example.transfer.NearbyReceiverState
import com.example.ui.components.Base64AppIcon
import com.example.ui.components.CircularTransferProgress
import com.example.ui.components.ConnectivityRequiredCard
import com.example.ui.components.ConnectivityRequiredDialog
import com.example.ui.components.PermissionRationaleCard
import com.example.ui.components.PulseRadarAnimation
import com.example.ui.components.QrCodeScannerView
import com.example.ui.components.UnknownSourcesPermissionCard
import com.example.ui.theme.PrimaryBlue
import com.example.ui.theme.SuccessGreen
import com.example.ui.viewmodel.ReceiverMode
import com.example.ui.viewmodel.ReceiverViewModel
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

    LaunchedEffect(mode, missingPerms, connectivity) {
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
        viewModel.cancelAll()
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
                        text = if (mode == ReceiverMode.HOTSPOT_HTTP) "Receive via Hotspot" else "Receive App",
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
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back")
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
                        onStartDownload = { url -> viewModel.startHttpDownload(url) },
                        onCancelDownload = { viewModel.cancelAll() },
                        onSwitchToNearby = { viewModel.switchToNearbyMode() },
                        onOpenUnknownSources = { viewModel.openUnknownAppSourcesSettings() },
                        onInstall = { files, meta ->
                            viewModel.installReceivedApk(files, meta.appName, meta.packageName, meta.isSplitApk)
                        },
                        onOpenInstalled = { pkg ->
                            if (!viewModel.openInstalledApp(pkg)) {
                                Toast.makeText(context, "App has no launcher activity", Toast.LENGTH_SHORT).show()
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
                        Text("Use Wi-Fi Hotspot QR / URL Instead")
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
                        Text("Use Wi-Fi Hotspot QR / URL Instead")
                    }
                } else {
                    when (val state = nearbyState) {
                        is NearbyReceiverState.Idle -> {
                            CircularProgressIndicator()
                            Text("Initializing receiver...")
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
                                        "Camera permission is needed to scan the sender's QR code",
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
                                                "Not a valid APK Share QR code",
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
                                    Text("Cancel QR Scan")
                                }
                            } else {
                                PulseRadarAnimation(
                                    color = SuccessGreen,
                                    centerIcon = {
                                        Icon(
                                            imageVector = Icons.Rounded.Radar,
                                            contentDescription = "Scanning",
                                            tint = Color.White,
                                            modifier = Modifier.size(32.dp)
                                        )
                                    }
                                )
                            }

                            Text(
                                text = "Scanning for nearby Senders...",
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold,
                                textAlign = TextAlign.Center
                            )
                            Text(
                                text = "Tap a device below once it appears, or scan Phone A's QR code to connect automatically.",
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
                                                text = "Finding ${state.qrTargetLabel}...",
                                                style = MaterialTheme.typography.bodySmall,
                                                fontWeight = FontWeight.SemiBold,
                                                color = SuccessGreen
                                            )
                                        }
                                        TextButton(onClick = { viewModel.clearQrPairTarget() }) {
                                            Text("Clear")
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
                                    Text("Scan QR to Find Device", fontWeight = FontWeight.Bold)
                                }
                            }

                            if (state.devices.isEmpty()) {
                                Surface(
                                    shape = RoundedCornerShape(16.dp),
                                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text(
                                        text = "No sender phones found yet. Make sure Phone A has selected an app and is on the Waiting screen.",
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
                                                        text = "Tap to connect and receive app",
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
                                Text("Try Wi-Fi Hotspot Mode (QR / URL)")
                            }
                        }

                        is NearbyReceiverState.WaitingSenderApproval -> {
                            Spacer(modifier = Modifier.height(24.dp))
                            CircularProgressIndicator(color = SuccessGreen)
                            Text(
                                text = "Waiting for sender approval...",
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "Ask \"${state.peerName}\" to tap Accept on their screen (Pairing code: ${state.authDigits})",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center
                            )
                            TextButton(onClick = handleBack) {
                                Text("Cancel", color = MaterialTheme.colorScheme.error)
                            }
                        }

                        is NearbyReceiverState.IncomingOffer -> {
                            AlertDialog(
                                onDismissRequest = { viewModel.declineIncomingOffer() },
                                title = { Text("Incoming App Transfer") },
                                text = {
                                    Column(
                                        verticalArrangement = Arrangement.spacedBy(12.dp),
                                        horizontalAlignment = Alignment.CenterHorizontally
                                    ) {
                                        Base64AppIcon(
                                            iconBase64 = state.metadata.iconBase64,
                                            size = 64.dp,
                                            tint = SuccessGreen
                                        )
                                        Text(
                                            text = state.metadata.appName,
                                            style = MaterialTheme.typography.titleLarge,
                                            fontWeight = FontWeight.Bold
                                        )
                                        Text(
                                            text = "v${state.metadata.versionName} · ${FormatUtils.formatBytes(state.metadata.totalSizeBytes)}",
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                        if (state.metadata.isSplitApk) {
                                            Surface(
                                                shape = RoundedCornerShape(8.dp),
                                                color = PrimaryBlue.copy(alpha = 0.14f)
                                            ) {
                                                Text(
                                                    text = "Split APK Bundle (${state.metadata.files.size} files)",
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = PrimaryBlue,
                                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                                )
                                            }
                                        }
                                        Text(
                                            text = "From: ${state.peerName}",
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
                                        Text("Accept")
                                    }
                                },
                                dismissButton = {
                                    TextButton(
                                        onClick = { viewModel.declineIncomingOffer() },
                                        modifier = Modifier.testTag("decline_incoming_apk_button")
                                    ) {
                                        Text("Decline")
                                    }
                                }
                            )
                        }

                        is NearbyReceiverState.Downloading -> {
                            CircularTransferProgress(
                                progress = state.progress,
                                accentColor = SuccessGreen,
                                title = "Receiving ${state.metadata.appName}",
                                subtitle = "From ${state.peerName}",
                                directionLabel = "Received"
                            )
                            OutlinedButton(
                                onClick = handleBack,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(48.dp)
                                    .testTag("cancel_downloading_button")
                            ) {
                                Text("Cancel", color = MaterialTheme.colorScheme.error)
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
                                onOpenUnknownSources = { viewModel.openUnknownAppSourcesSettings() },
                                onInstallClick = {
                                    viewModel.installReceivedApk(
                                        files = state.savedFiles,
                                        appName = state.metadata.appName,
                                        packageName = state.metadata.packageName,
                                        isSplitApk = state.metadata.isSplitApk
                                    )
                                },
                                onOpenAppClick = {
                                    if (!viewModel.openInstalledApp(state.metadata.packageName)) {
                                        Toast.makeText(context, "App has no launcher icon", Toast.LENGTH_SHORT).show()
                                    }
                                },
                                onDone = handleBack
                            )
                        }

                        is NearbyReceiverState.Error -> {
                            Spacer(modifier = Modifier.height(12.dp))
                            Icon(
                                imageVector = Icons.Rounded.ErrorOutline,
                                contentDescription = "Error",
                                tint = MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(64.dp)
                            )
                            Text(
                                text = "Transfer Interrupted",
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
                                Text("Retry Nearby")
                            }
                            OutlinedButton(
                                onClick = { viewModel.switchToHotspotMode() },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(50.dp)
                            ) {
                                Icon(Icons.Rounded.QrCodeScanner, contentDescription = null)
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Try Wi-Fi Hotspot Mode")
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
    onStartDownload: (String) -> Unit,
    onCancelDownload: () -> Unit,
    onSwitchToNearby: () -> Unit,
    onOpenUnknownSources: () -> Unit,
    onInstall: (List<File>, ApkBundleMetadata) -> Unit,
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
            Toast.makeText(context, "Camera permission is needed to scan QR codes", Toast.LENGTH_SHORT).show()
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
                        text = "Connect to Phone A's Wi-Fi Hotspot",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "1. Join Phone A's hotspot in Wi-Fi settings.\n2. Scan Phone A's QR code or enter the URL below.",
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
                    Text("Hide Scanner")
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
                    Text("Scan Sender QR Code", fontWeight = FontWeight.Bold)
                }
            }

            OutlinedTextField(
                value = urlInput,
                onValueChange = { urlInput = it },
                label = { Text("Sender URL") },
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
                Text("Download APK", fontWeight = FontWeight.Bold)
            }

            TextButton(onClick = onSwitchToNearby) {
                Text("Switch back to Nearby Discovery")
            }
        }

        is HttpDownloadState.Connecting -> {
            Spacer(modifier = Modifier.height(24.dp))
            CircularProgressIndicator(color = SuccessGreen)
            Text("Connecting to ${httpState.url}...")
            TextButton(onClick = onCancelDownload) {
                Text("Cancel", color = MaterialTheme.colorScheme.error)
            }
        }

        is HttpDownloadState.Downloading -> {
            CircularTransferProgress(
                progress = httpState.progress,
                accentColor = SuccessGreen,
                title = "Downloading ${httpState.metadata.appName}",
                subtitle = "Via Wi-Fi Hotspot HTTP",
                directionLabel = "Received"
            )
            OutlinedButton(
                onClick = onCancelDownload,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Cancel Download", color = MaterialTheme.colorScheme.error)
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
                onOpenUnknownSources = onOpenUnknownSources,
                onInstallClick = { onInstall(httpState.savedFiles, httpState.metadata) },
                onOpenAppClick = { onOpenInstalled(httpState.metadata.packageName) },
                onDone = onDone
            )
        }

        is HttpDownloadState.Error -> {
            Icon(
                imageVector = Icons.Rounded.ErrorOutline,
                contentDescription = "Download error",
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.size(64.dp)
            )
            Text(
                text = "Download Failed",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = httpState.reason,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
            Button(
                onClick = { onStartDownload(httpState.url) },
                colors = ButtonDefaults.buttonColors(containerColor = SuccessGreen, contentColor = Color.White),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(50.dp)
            ) {
                Icon(Icons.Rounded.Refresh, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text("Resume / Retry Download")
            }
            OutlinedButton(
                onClick = onCancelDownload,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Edit URL / Scan Again")
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
    onOpenUnknownSources: () -> Unit,
    onInstallClick: () -> Unit,
    onOpenAppClick: () -> Unit,
    onDone: () -> Unit
) {
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
                contentDescription = "Download complete",
                tint = SuccessGreen,
                modifier = Modifier.size(52.dp)
            )
        }

        Text(
            text = "Download Complete!",
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
                Base64AppIcon(iconBase64 = metadata.iconBase64, size = 54.dp, tint = SuccessGreen)
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        text = metadata.appName,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "v${metadata.versionName} · ${FormatUtils.formatBytes(metadata.totalSizeBytes)} · ${FormatUtils.formatDurationMillis(durationMillis)}",
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
                    if (metadata.isSplitApk) {
                        Text(
                            text = "Split APK Bundle (${savedFiles.size} files) — installs via PackageInstaller",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }

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
                    Text("INSTALL APP", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
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
                    text = "Confirm installation in the Android system dialog...",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
                OutlinedButton(
                    onClick = onInstallClick,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Tap Install Again")
                }
            }

            is InstallState.Installed -> {
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = SuccessGreen.copy(alpha = 0.14f),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = "Installed Successfully!",
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
                        Text("Installed Successfully - Open App", fontWeight = FontWeight.Bold)
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
                    Text("Retry Install", fontWeight = FontWeight.Bold)
                }
            }
        }

        TextButton(onClick = onDone) {
            Text("Back to Home")
        }
    }
}
