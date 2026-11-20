package com.example.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.SystemUpdate
import androidx.compose.material.icons.rounded.WifiOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.data.db.TransferHistoryEntity
import com.example.model.TransferDirection
import com.example.model.TransferStatus
import com.example.ui.theme.PrimaryBlue
import com.example.ui.theme.PrimaryBlueDark
import com.example.ui.theme.SuccessGreen
import com.example.ui.theme.SuccessGreenDark
import com.example.update.GitHubReleaseInfo
import com.example.update.UpdateDownloadState
import com.example.util.FormatUtils

@Composable
fun HomeScreen(
    deviceName: String,
    recentTransfers: List<TransferHistoryEntity>,
    onSendClick: () -> Unit,
    onReceiveClick: () -> Unit,
    onOpenHistory: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
    availableUpdate: GitHubReleaseInfo? = null,
    updateDownloadState: UpdateDownloadState = UpdateDownloadState.Idle,
    onUpdateNow: (GitHubReleaseInfo) -> Unit = {},
    onUpdateLater: (GitHubReleaseInfo) -> Unit = {},
    onOpenInstallPermissionSettings: () -> Unit = {},
    onRetryInstallUpdate: () -> Unit = {},
    onOpenUpdateBrowserFallback: (String) -> Unit = {},
    onDismissUpdateDownload: () -> Unit = {}
) {
    val scrollState = rememberScrollState()

    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.TopCenter
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = 760.dp)
                .fillMaxSize()
                .verticalScroll(scrollState)
                .padding(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp)
        ) {
            // Top App Identity Row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = stringResource(R.string.app_name),
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.WifiOff,
                            contentDescription = stringResource(R.string.home_offline_mode_cd),
                            tint = SuccessGreen,
                            modifier = Modifier.size(14.dp)
                        )
                        Text(
                            text = stringResource(R.string.home_offline_header, deviceName),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                Row {
                    IconButton(
                        onClick = onOpenHistory,
                        modifier = Modifier
                            .minimumInteractiveComponentSize()
                            .testTag("home_history_button")
                    ) {
                        Icon(Icons.Rounded.History, contentDescription = stringResource(R.string.history_title))
                    }
                    IconButton(
                        onClick = onOpenSettings,
                        modifier = Modifier
                            .minimumInteractiveComponentSize()
                            .testTag("home_settings_button")
                    ) {
                        Icon(Icons.Rounded.Settings, contentDescription = stringResource(R.string.nav_settings))
                    }
                }
            }

            // Hero Artwork Banner
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(148.dp)
                    .clip(RoundedCornerShape(24.dp))
            ) {
                Image(
                    painter = painterResource(id = R.drawable.img_transfer_hero),
                    contentDescription = stringResource(R.string.home_hero_cd),
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            Brush.horizontalGradient(
                                colors = listOf(
                                    Color(0xD90F172A),
                                    Color(0x660F172A),
                                    Color(0x330F172A)
                                )
                            )
                        )
                        .padding(18.dp)
                ) {
                    Column(
                        modifier = Modifier.align(Alignment.CenterStart),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = Color.White.copy(alpha = 0.16f)
                        ) {
                            Text(
                                text = stringResource(R.string.home_hero_badge),
                                style = MaterialTheme.typography.labelSmall,
                                color = Color.White,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                            )
                        }
                        Text(
                            text = stringResource(R.string.home_hero_headline),
                            style = MaterialTheme.typography.titleLarge,
                            color = Color.White,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }

            // 2 BIG BUTTONS: SEND & RECEIVE (Adaptive Row/Column)
            BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
                val wide = maxWidth >= 520.dp
                if (wide) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        BigActionCard(
                            title = stringResource(R.string.send_title),
                            subtitle = stringResource(R.string.send_subtitle),
                            icon = Icons.Rounded.ArrowUpward,
                            gradient = Brush.linearGradient(listOf(PrimaryBlue, PrimaryBlueDark)),
                            onClick = onSendClick,
                            modifier = Modifier
                                .weight(1f)
                                .testTag("send_button")
                        )
                        BigActionCard(
                            title = stringResource(R.string.receive_title),
                            subtitle = stringResource(R.string.receive_subtitle),
                            icon = Icons.Rounded.ArrowDownward,
                            gradient = Brush.linearGradient(listOf(SuccessGreen, SuccessGreenDark)),
                            onClick = onReceiveClick,
                            modifier = Modifier
                                .weight(1f)
                                .testTag("receive_button")
                        )
                    }
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        BigActionCard(
                            title = stringResource(R.string.send_title),
                            subtitle = stringResource(R.string.send_subtitle),
                            icon = Icons.Rounded.ArrowUpward,
                            gradient = Brush.linearGradient(listOf(PrimaryBlue, PrimaryBlueDark)),
                            onClick = onSendClick,
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("send_button")
                        )
                        BigActionCard(
                            title = stringResource(R.string.receive_title),
                            subtitle = stringResource(R.string.receive_subtitle),
                            icon = Icons.Rounded.ArrowDownward,
                            gradient = Brush.linearGradient(listOf(SuccessGreen, SuccessGreenDark)),
                            onClick = onReceiveClick,
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("receive_button")
                        )
                    }
                }
            }

            // How to Use (3 Steps)
            Card(
                shape = RoundedCornerShape(22.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)
                )
            ) {
                Column(
                    modifier = Modifier.padding(18.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.PhoneAndroid,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            text = stringResource(R.string.how_to_use_title),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    StepRow(
                        number = "1",
                        title = stringResource(R.string.step_1_title),
                        desc = stringResource(R.string.step_1_desc),
                        badgeColor = PrimaryBlue
                    )
                    StepRow(
                        number = "2",
                        title = stringResource(R.string.step_2_title),
                        desc = stringResource(R.string.step_2_desc),
                        badgeColor = Color(0xFF0EA5E9)
                    )
                    StepRow(
                        number = "3",
                        title = stringResource(R.string.step_3_title),
                        desc = stringResource(R.string.step_3_desc),
                        badgeColor = SuccessGreen
                    )
                }
            }

            // Recent Transfer Summary Strip
            if (recentTransfers.isNotEmpty()) {
                val latest = recentTransfers.first()
                val directionLabel = when (latest.direction) {
                    TransferDirection.SENT -> stringResource(R.string.direction_sent)
                    TransferDirection.RECEIVED -> stringResource(R.string.direction_received)
                }
                val statusLabel = when (latest.status) {
                    TransferStatus.SUCCESS -> stringResource(R.string.status_success)
                    TransferStatus.FAILED -> stringResource(R.string.status_failed)
                    TransferStatus.CANCELLED -> stringResource(R.string.status_cancelled)
                }
                Card(
                    onClick = onOpenHistory,
                    shape = RoundedCornerShape(18.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = stringResource(R.string.home_last_transfer_label, directionLabel),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Text(
                                text = stringResource(
                                    R.string.home_last_transfer_app,
                                    latest.appName,
                                    FormatUtils.formatBytes(latest.sizeBytes)
                                ),
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                text = stringResource(
                                    R.string.home_last_transfer_status,
                                    statusLabel,
                                    FormatUtils.formatDateTime(latest.timestampMillis)
                                ),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Icon(
                            imageVector = Icons.AutoMirrored.Rounded.ArrowForward,
                            contentDescription = stringResource(R.string.home_view_history_cd)
                        )
                    }
                }
            }

            // Quick Limitations Note
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .clickable { onOpenSettings() }
                    .padding(horizontal = 4.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    imageVector = Icons.Rounded.Info,
                    contentDescription = stringResource(R.string.limitations_title),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp)
                )
                Text(
                    text = stringResource(R.string.home_limitations_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(modifier = Modifier.height(12.dp))
        }
    }

    if (availableUpdate != null && updateDownloadState is UpdateDownloadState.Idle) {
        val dialogTitle = stringResource(R.string.update_dialog_title)
        val dialogMessage = stringResource(R.string.update_dialog_message, availableUpdate.tagName)
        val updateLabel = stringResource(R.string.update_action_update)
        val laterLabel = stringResource(R.string.update_action_later)
        AlertDialog(
            onDismissRequest = { onUpdateLater(availableUpdate) },
            icon = {
                Icon(
                    imageVector = Icons.Rounded.SystemUpdate,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
            },
            title = {
                Text(
                    text = dialogTitle,
                    modifier = Modifier.testTag("update_dialog_title")
                )
            },
            text = {
                Text(
                    text = dialogMessage,
                    modifier = Modifier.testTag("update_dialog_message")
                )
            },
            confirmButton = {
                Button(
                    onClick = { onUpdateNow(availableUpdate) },
                    modifier = Modifier.testTag("update_dialog_confirm_button")
                ) {
                    Text(updateLabel)
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { onUpdateLater(availableUpdate) },
                    modifier = Modifier.testTag("update_dialog_later_button")
                ) {
                    Text(laterLabel)
                }
            }
        )
    }

    when (val dlState = updateDownloadState) {
        is UpdateDownloadState.Downloading -> {
            val downloadingTitle = stringResource(R.string.update_downloading_title)
            val progressText = if (dlState.progressPercent >= 0) {
                stringResource(
                    R.string.update_downloading_progress,
                    dlState.versionName,
                    dlState.progressPercent
                )
            } else {
                stringResource(
                    R.string.update_downloading_starting,
                    dlState.versionName
                )
            }
            AlertDialog(
                onDismissRequest = {},
                icon = {
                    Icon(
                        imageVector = Icons.Rounded.SystemUpdate,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary
                    )
                },
                title = { Text(downloadingTitle) },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(
                            text = progressText,
                            style = MaterialTheme.typography.bodyMedium
                        )
                        if (dlState.progressPercent >= 0) {
                            LinearProgressIndicator(
                                progress = { dlState.progressPercent / 100f },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("update_download_progress_bar")
                            )
                        } else {
                            LinearProgressIndicator(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("update_download_progress_bar")
                            )
                        }
                    }
                },
                confirmButton = {}
            )
        }

        is UpdateDownloadState.Installing -> {
            val downloadingTitle = stringResource(R.string.update_downloading_title)
            val installingStatus = stringResource(R.string.update_installing_status)
            AlertDialog(
                onDismissRequest = {},
                icon = {
                    Icon(
                        imageVector = Icons.Rounded.SystemUpdate,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary
                    )
                },
                title = { Text(downloadingTitle) },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(
                            text = installingStatus,
                            style = MaterialTheme.typography.bodyMedium
                        )
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    }
                },
                confirmButton = {}
            )
        }

        is UpdateDownloadState.PermissionRequired -> {
            val dialogTitle = stringResource(R.string.update_dialog_title)
            val permissionText = stringResource(R.string.update_permission_needed)
            val openBrowserLabel = stringResource(R.string.update_action_open_browser)
            val installNowLabel = stringResource(R.string.update_action_install_now)
            val allowInstallLabel = stringResource(R.string.update_action_allow_install)
            AlertDialog(
                onDismissRequest = onDismissUpdateDownload,
                icon = {
                    Icon(
                        imageVector = Icons.Rounded.SystemUpdate,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary
                    )
                },
                title = { Text(dialogTitle) },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(
                            text = permissionText,
                            style = MaterialTheme.typography.bodyMedium
                        )
                        TextButton(
                            onClick = { onOpenUpdateBrowserFallback(dlState.releasePageUrl) }
                        ) {
                            Text(openBrowserLabel)
                        }
                    }
                },
                confirmButton = {
                    Button(onClick = onRetryInstallUpdate) {
                        Text(installNowLabel)
                    }
                },
                dismissButton = {
                    TextButton(onClick = onOpenInstallPermissionSettings) {
                        Text(allowInstallLabel)
                    }
                }
            )
        }

        UpdateDownloadState.Idle,
        UpdateDownloadState.FallbackOpened -> Unit
    }
}

@Composable
private fun BigActionCard(
    title: String,
    subtitle: String,
    icon: ImageVector,
    gradient: Brush,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier
            .height(126.dp)
            .clip(RoundedCornerShape(24.dp))
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(24.dp),
        shadowElevation = 6.dp
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(gradient)
                .padding(20.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxSize(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.headlineMedium,
                        color = Color.White,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodyLarge,
                        color = Color.White.copy(alpha = 0.9f)
                    )
                }
                Box(
                    modifier = Modifier
                        .size(60.dp)
                        .clip(CircleShape)
                        .background(Color.White.copy(alpha = 0.22f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = title,
                        tint = Color.White,
                        modifier = Modifier.size(34.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun StepRow(
    number: String,
    title: String,
    desc: String,
    badgeColor: Color
) {
    Row(
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Box(
            modifier = Modifier
                .size(28.dp)
                .clip(CircleShape)
                .background(badgeColor.copy(alpha = 0.16f)),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = number,
                style = MaterialTheme.typography.labelLarge,
                color = badgeColor,
                fontWeight = FontWeight.Bold
            )
        }
        Column {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = desc,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
