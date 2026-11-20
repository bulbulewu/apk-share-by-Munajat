package com.example.ui.screens

import android.app.Activity
import android.content.Intent
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Computer
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.StopCircle
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import com.example.ui.components.PermissionRationaleCard
import com.example.ui.components.TransferProgressBar
import com.example.ui.theme.PrimaryBlue
import com.example.ui.theme.SuccessGreen
import com.example.ui.theme.WarningAmber
import com.example.ui.viewmodel.SenderViewModel
import com.example.util.FormatUtils
import com.example.util.PermissionHelper
import com.example.util.QrCodeUtils

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShareToPcScreen(
    viewModel: SenderViewModel,
    onDone: () -> Unit
) {
    val context = LocalContext.current
    @Suppress("DEPRECATION")
    val clipboard = LocalClipboardManager.current

    val hotspotInfo by viewModel.hotspotInfo.collectAsStateWithLifecycle()
    val serverStatus by viewModel.httpServerStatus.collectAsStateWithLifecycle()
    val pcShareItems by viewModel.pcShareItems.collectAsStateWithLifecycle()

    val hotspotPermissions = remember { PermissionHelper.getHotspotPermissions() }
    var missingHotspotPerms by remember {
        mutableStateOf(PermissionHelper.missingPermissions(context, hotspotPermissions))
    }
    var hotspotPermRequested by remember { mutableStateOf(false) }
    var hotspotStarted by remember { mutableStateOf(false) }

    val hotspotPermissionLauncher = if (LocalActivityResultRegistryOwner.current != null) {
        rememberLauncherForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions()
        ) {
            missingHotspotPerms = PermissionHelper.missingPermissions(context, hotspotPermissions)
        }
    } else {
        null
    }

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        missingHotspotPerms = PermissionHelper.missingPermissions(context, hotspotPermissions)
    }

    LaunchedEffect(missingHotspotPerms) {
        if (missingHotspotPerms.isNotEmpty()) {
            if (!hotspotPermRequested && hotspotPermissionLauncher != null) {
                hotspotPermRequested = true
                hotspotPermissionLauncher.launch(missingHotspotPerms.toTypedArray())
            }
        } else if (!hotspotStarted) {
            hotspotStarted = true
            viewModel.startLocalHotspot()
        }
    }

    val stopAndExit: () -> Unit = {
        viewModel.stopPcShare()
        onDone()
    }

    BackHandler(onBack = stopAndExit)

    DisposableEffect(Unit) {
        onDispose {
            val activity = context as? Activity
            if (activity?.isChangingConfigurations != true) {
                viewModel.stopPcShare()
            }
        }
    }

    val serverUrl = "http://${hotspotInfo.ipAddress}:${serverStatus.port}/"
    val qrBitmap = remember(serverUrl) {
        QrCodeUtils.generateQrBitmap(serverUrl, 440)
    }

    val totalItemsSize = remember(pcShareItems) {
        pcShareItems.sumOf { it.sizeBytes.coerceAtLeast(0L) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = stringResource(R.string.pc_share_title),
                        fontWeight = FontWeight.Bold
                    )
                },
                navigationIcon = {
                    IconButton(
                        onClick = stopAndExit,
                        modifier = Modifier
                            .minimumInteractiveComponentSize()
                            .testTag("pc_share_back_button")
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
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
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Spacer(modifier = Modifier.height(4.dp))

                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = PrimaryBlue.copy(alpha = 0.1f),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Computer,
                            contentDescription = null,
                            tint = PrimaryBlue,
                            modifier = Modifier.size(28.dp)
                        )
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(
                                text = stringResource(R.string.pc_share_subtitle),
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium
                            )
                            Text(
                                text = if (pcShareItems.size == 1) {
                                    stringResource(
                                        R.string.pc_share_items_ready_single,
                                        FormatUtils.formatBytes(totalItemsSize)
                                    )
                                } else {
                                    stringResource(
                                        R.string.pc_share_items_ready_count,
                                        pcShareItems.size,
                                        FormatUtils.formatBytes(totalItemsSize)
                                    )
                                },
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = PrimaryBlue,
                                modifier = Modifier.testTag("pc_share_items_summary")
                            )
                        }
                    }
                }

                if (missingHotspotPerms.isNotEmpty()) {
                    PermissionRationaleCard(
                        missingPermissions = missingHotspotPerms,
                        onGrantClick = {
                            hotspotPermissionLauncher?.launch(missingHotspotPerms.toTypedArray())
                        },
                        title = stringResource(R.string.sender_hotspot_perms_title),
                        description = stringResource(R.string.sender_hotspot_perms_desc)
                    )
                }

                Card(
                    shape = RoundedCornerShape(18.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.65f)
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("pc_share_steps_card")
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = if (!hotspotInfo.ssid.isNullOrBlank()) {
                                    stringResource(R.string.pc_share_step_1_with_ssid, hotspotInfo.ssid!!)
                                } else {
                                    stringResource(R.string.pc_share_step_1_starting)
                                },
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("pc_share_step_1_text")
                            )
                            IconButton(
                                onClick = { viewModel.refreshHotspotIp() },
                                modifier = Modifier
                                    .minimumInteractiveComponentSize()
                                    .testTag("pc_refresh_ip_button")
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.Refresh,
                                    contentDescription = stringResource(R.string.sender_refresh_ip_cd)
                                )
                            }
                        }

                        if (!hotspotInfo.passphrase.isNullOrBlank()) {
                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = MaterialTheme.colorScheme.surface,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = stringResource(R.string.sender_hotspot_password_label),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                        Text(
                                            text = hotspotInfo.passphrase!!,
                                            style = MaterialTheme.typography.titleSmall,
                                            fontWeight = FontWeight.Bold,
                                            modifier = Modifier.testTag("pc_share_password_text")
                                        )
                                    }
                                    IconButton(
                                        onClick = {
                                            clipboard.setText(AnnotatedString(hotspotInfo.passphrase!!))
                                            Toast.makeText(
                                                context,
                                                context.getString(R.string.pc_share_password_copied_toast),
                                                Toast.LENGTH_SHORT
                                            ).show()
                                        },
                                        modifier = Modifier
                                            .minimumInteractiveComponentSize()
                                            .testTag("pc_copy_password_button")
                                    ) {
                                        Icon(
                                            imageVector = Icons.Rounded.ContentCopy,
                                            contentDescription = stringResource(R.string.pc_share_copy_password_cd)
                                        )
                                    }
                                }
                            }
                        }

                        Text(
                            text = hotspotInfo.statusMessage,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        if (!hotspotInfo.isActive) {
                            OutlinedButton(
                                onClick = {
                                    runCatching {
                                        context.startActivity(Intent(Settings.ACTION_WIRELESS_SETTINGS))
                                    }.onFailure {
                                        runCatching { context.startActivity(Intent(Settings.ACTION_SETTINGS)) }
                                    }
                                },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.Settings,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(stringResource(R.string.sender_open_hotspot_settings))
                            }
                        }

                        Text(
                            text = stringResource(R.string.pc_share_step_2),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.testTag("pc_share_step_2_text")
                        )

                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = MaterialTheme.colorScheme.surface,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = serverUrl,
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = PrimaryBlue,
                                    modifier = Modifier
                                        .weight(1f)
                                        .testTag("pc_share_url_text")
                                )
                                IconButton(
                                    onClick = {
                                        clipboard.setText(AnnotatedString(serverUrl))
                                        Toast.makeText(
                                            context,
                                            context.getString(R.string.sender_url_copied_toast),
                                            Toast.LENGTH_SHORT
                                        ).show()
                                    },
                                    modifier = Modifier
                                        .minimumInteractiveComponentSize()
                                        .testTag("pc_copy_url_button")
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.ContentCopy,
                                        contentDescription = stringResource(R.string.sender_copy_url_cd)
                                    )
                                }
                            }
                        }

                        if (qrBitmap != null) {
                            Box(
                                modifier = Modifier.fillMaxWidth(),
                                contentAlignment = Alignment.Center
                            ) {
                                Surface(
                                    shape = RoundedCornerShape(18.dp),
                                    color = Color.White,
                                    shadowElevation = 3.dp
                                ) {
                                    Image(
                                        bitmap = qrBitmap,
                                        contentDescription = stringResource(R.string.sender_hotspot_qr_cd),
                                        modifier = Modifier
                                            .size(190.dp)
                                            .padding(12.dp)
                                            .testTag("pc_share_qr_image")
                                    )
                                }
                            }
                        }

                        Text(
                            text = stringResource(R.string.pc_share_step_3),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.testTag("pc_share_step_3_text")
                        )
                    }
                }

                Card(
                    shape = RoundedCornerShape(18.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("pc_share_live_status_card")
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Text(
                            text = stringResource(R.string.pc_share_status_header),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold
                        )

                        val liveFileText = when {
                            serverStatus.activeClients > 0 && !serverStatus.currentFileName.isNullOrBlank() ->
                                stringResource(R.string.pc_share_status_downloading, serverStatus.currentFileName!!)
                            !serverStatus.currentFileName.isNullOrBlank() ->
                                stringResource(R.string.pc_share_status_last_file, serverStatus.currentFileName!!)
                            else -> stringResource(R.string.pc_share_status_waiting)
                        }

                        Text(
                            text = liveFileText,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium,
                            color = if (serverStatus.activeClients > 0) PrimaryBlue else MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.testTag("pc_share_live_file_status")
                        )

                        if (serverStatus.currentFileTotalBytes > 0L) {
                            val isFileDone = serverStatus.activeClients == 0 &&
                                serverStatus.currentFileBytesServed >= serverStatus.currentFileTotalBytes
                            TransferProgressBar(
                                bytesTransferred = serverStatus.currentFileBytesServed,
                                totalBytes = serverStatus.currentFileTotalBytes,
                                accentColor = if (isFileDone) SuccessGreen else PrimaryBlue,
                                directionLabel = stringResource(R.string.progress_direction_sent),
                                fileName = serverStatus.currentFileName.orEmpty(),
                                testTagName = "pc_share_progress_bar"
                            )
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = stringResource(
                                    R.string.pc_share_bytes_served,
                                    FormatUtils.formatTransferSize(serverStatus.bytesServed)
                                ),
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.testTag("pc_share_bytes_served_text")
                            )
                            Text(
                                text = stringResource(
                                    R.string.pc_share_speed_label,
                                    FormatUtils.formatLiveSpeed(serverStatus.speedBytesPerSec)
                                ),
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.SemiBold,
                                color = if (serverStatus.speedBytesPerSec > 0L) PrimaryBlue else MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.testTag("pc_share_speed_text")
                            )
                        }
                    }
                }

                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = WarningAmber.copy(alpha = 0.14f),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("pc_share_safety_note_card")
                ) {
                    Row(
                        modifier = Modifier.padding(14.dp),
                        verticalAlignment = Alignment.Top,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Security,
                            contentDescription = null,
                            tint = WarningAmber,
                            modifier = Modifier.size(20.dp)
                        )
                        Text(
                            text = stringResource(R.string.pc_share_safety_note),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface,
                            textAlign = TextAlign.Start
                        )
                    }
                }

                Button(
                    onClick = stopAndExit,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = Color.White
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp)
                        .minimumInteractiveComponentSize()
                        .testTag("pc_stop_sharing_button")
                ) {
                    Icon(
                        imageVector = Icons.Rounded.StopCircle,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = stringResource(R.string.pc_share_stop_button),
                        fontWeight = FontWeight.Bold
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))
            }
        }
    }
}
