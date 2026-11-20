package com.example.ui.components

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.util.Log
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Android
import androidx.compose.material.icons.rounded.BluetoothDisabled
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.WifiOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.R
import com.example.model.TransferProgressState
import com.example.ui.theme.PrimaryBlue
import com.example.ui.theme.SuccessGreen
import com.example.ui.theme.WarningAmber
import com.example.util.FormatUtils
import com.example.util.PermissionHelper
import com.example.util.QrCodeUtils
import java.util.concurrent.Executors

val LocalPowerSaverEnabled = staticCompositionLocalOf { false }

@Composable
fun TransferProgressBar(
    bytesTransferred: Long,
    totalBytes: Long,
    accentColor: Color,
    directionLabel: String,
    modifier: Modifier = Modifier,
    fileName: String = "",
    testTagName: String = "transfer_progress_bar"
) {
    val powerSaver = LocalPowerSaverEnabled.current
    val clampedTransferred = bytesTransferred.coerceAtLeast(0L).let {
        if (totalBytes > 0L) it.coerceAtMost(totalBytes) else it
    }
    val rawFraction = if (totalBytes > 0L) {
        (clampedTransferred.toDouble() / totalBytes.toDouble()).toFloat().coerceIn(0f, 1f)
    } else {
        0f
    }
    val animatedFraction by animateFloatAsState(
        targetValue = rawFraction,
        animationSpec = tween(durationMillis = if (powerSaver) 0 else 240, easing = FastOutSlowInEasing),
        label = "linear_transfer_progress"
    )
    val displayFraction = if (powerSaver) rawFraction else animatedFraction
    val percent = (rawFraction * 100f).toInt().coerceIn(0, 100)
    val remainingBytes = (totalBytes - clampedTransferred).coerceAtLeast(0L)
    val trackColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.22f)

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = fileName.ifBlank { "APK Transfer Progress" },
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Surface(
                shape = RoundedCornerShape(999.dp),
                color = accentColor.copy(alpha = 0.14f)
            ) {
                Text(
                    text = "$percent%",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = accentColor,
                    modifier = Modifier
                        .padding(horizontal = 10.dp, vertical = 3.dp)
                        .testTag("${testTagName}_percent")
                )
            }
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(12.dp)
                .clip(RoundedCornerShape(999.dp))
                .background(trackColor)
                .semantics {
                    progressBarRangeInfo = ProgressBarRangeInfo(
                        current = rawFraction,
                        range = 0f..1f
                    )
                    contentDescription = "$percent percent $directionLabel"
                }
                .testTag(testTagName)
        ) {
            if (displayFraction > 0f) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(displayFraction.coerceIn(0.01f, 1f))
                        .fillMaxSize()
                        .clip(RoundedCornerShape(999.dp))
                        .background(
                            Brush.horizontalGradient(
                                colors = listOf(
                                    accentColor,
                                    accentColor.copy(alpha = 0.78f)
                                )
                            )
                        )
                )
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = if (totalBytes > 0L) {
                    "${FormatUtils.formatBytes(clampedTransferred)} / ${FormatUtils.formatBytes(totalBytes)} $directionLabel"
                } else {
                    "${FormatUtils.formatBytes(clampedTransferred)} $directionLabel"
                },
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.testTag("${testTagName}_bytes")
            )
            if (totalBytes > 0L) {
                Text(
                    text = if (remainingBytes == 0L) "Complete" else "${FormatUtils.formatBytes(remainingBytes)} left",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (remainingBytes == 0L) SuccessGreen else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
fun CircularTransferProgress(
    progress: TransferProgressState,
    accentColor: Color,
    title: String,
    subtitle: String,
    modifier: Modifier = Modifier,
    directionLabel: String = "Transferred"
) {
    val powerSaver = LocalPowerSaverEnabled.current
    val animatedFraction by animateFloatAsState(
        targetValue = progress.progressFraction,
        animationSpec = tween(durationMillis = if (powerSaver) 0 else 260),
        label = "transfer_progress"
    )
    val sweepFraction = if (powerSaver) progress.progressFraction else animatedFraction
    val trackColor = MaterialTheme.colorScheme.surfaceVariant

    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = subtitle,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
        if (powerSaver) {
            Spacer(modifier = Modifier.height(8.dp))
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = SuccessGreen.copy(alpha = 0.14f)
            ) {
                Text(
                    text = "Power Saver Active · Reduced update rate",
                    style = MaterialTheme.typography.labelSmall,
                    color = SuccessGreen,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                )
            }
        }
        Spacer(modifier = Modifier.height(20.dp))

        Box(
            modifier = Modifier.size(188.dp),
            contentAlignment = Alignment.Center
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val strokeWidth = 15.dp.toPx()
                drawArc(
                    color = trackColor,
                    startAngle = -90f,
                    sweepAngle = 360f,
                    useCenter = false,
                    style = Stroke(width = strokeWidth, cap = StrokeCap.Round)
                )
                drawArc(
                    color = accentColor,
                    startAngle = -90f,
                    sweepAngle = 360f * sweepFraction,
                    useCenter = false,
                    style = Stroke(width = strokeWidth, cap = StrokeCap.Round)
                )
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = "${progress.progressPercent}%",
                    style = MaterialTheme.typography.displaySmall,
                    fontWeight = FontWeight.Bold,
                    color = accentColor
                )
                Text(
                    text = "${progress.completedFiles.coerceAtMost(progress.totalFiles)}/${progress.totalFiles} APK(s)",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        Spacer(modifier = Modifier.height(18.dp))

        Surface(
            shape = RoundedCornerShape(18.dp),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                TransferProgressBar(
                    bytesTransferred = progress.bytesTransferred,
                    totalBytes = progress.totalBytes,
                    accentColor = accentColor,
                    directionLabel = directionLabel,
                    fileName = progress.currentFileName,
                    testTagName = "transfer_progress_bar"
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    MetricPill(
                        label = "Speed",
                        value = FormatUtils.formatSpeed(progress.speedBytesPerSec)
                    )
                    MetricPill(
                        label = "Files",
                        value = "${progress.completedFiles.coerceAtMost(progress.totalFiles)} / ${progress.totalFiles}"
                    )
                    MetricPill(
                        label = "ETA",
                        value = FormatUtils.formatEta(progress.etaSeconds)
                    )
                }
            }
        }
    }
}

@Composable
private fun MetricPill(label: String, value: String) {
    Column {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}

@Composable
fun PulseRadarAnimation(
    color: Color,
    centerIcon: @Composable () -> Unit,
    modifier: Modifier = Modifier
) {
    val powerSaver = LocalPowerSaverEnabled.current

    Box(
        modifier = modifier.size(164.dp),
        contentAlignment = Alignment.Center
    ) {
        if (powerSaver) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val maxRadius = size.minDimension / 2f
                drawCircle(
                    color = color.copy(alpha = 0.08f),
                    radius = maxRadius * 0.88f
                )
                drawCircle(
                    color = color.copy(alpha = 0.16f),
                    radius = maxRadius * 0.62f
                )
            }
        } else {
            val transition = rememberInfiniteTransition(label = "radar")
            val pulse1 by transition.animateFloat(
                initialValue = 0.2f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(
                    animation = tween(1800, easing = FastOutSlowInEasing),
                    repeatMode = RepeatMode.Restart
                ),
                label = "pulse1"
            )
            val pulse2 by transition.animateFloat(
                initialValue = 0.2f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(
                    animation = tween(1800, delayMillis = 600, easing = FastOutSlowInEasing),
                    repeatMode = RepeatMode.Restart
                ),
                label = "pulse2"
            )

            Canvas(modifier = Modifier.fillMaxSize()) {
                val maxRadius = size.minDimension / 2f
                drawCircle(
                    color = color.copy(alpha = (1f - pulse1) * 0.35f),
                    radius = maxRadius * pulse1
                )
                drawCircle(
                    color = color.copy(alpha = (1f - pulse2) * 0.35f),
                    radius = maxRadius * pulse2
                )
                drawCircle(
                    color = color.copy(alpha = 0.14f),
                    radius = maxRadius * 0.44f
                )
            }
        }
        Surface(
            modifier = Modifier.size(68.dp),
            shape = CircleShape,
            color = color,
            shadowElevation = 6.dp
        ) {
            Box(contentAlignment = Alignment.Center) {
                centerIcon()
            }
        }
    }
}

@Composable
fun Base64AppIcon(
    iconBase64: String?,
    size: Dp = 52.dp,
    tint: Color = PrimaryBlue
) {
    val bitmap = remember(iconBase64) {
        FormatUtils.base64ToBitmap(iconBase64)?.asImageBitmap()
    }
    if (bitmap != null) {
        Image(
            bitmap = bitmap,
            contentDescription = "App icon",
            modifier = Modifier
                .size(size)
                .clip(RoundedCornerShape(14.dp))
        )
    } else {
        Box(
            modifier = Modifier
                .size(size)
                .clip(RoundedCornerShape(14.dp))
                .background(tint.copy(alpha = 0.12f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Rounded.Android,
                contentDescription = "App icon",
                tint = tint,
                modifier = Modifier.size(size * 0.6f)
            )
        }
    }
}

@Composable
fun InstalledAppIcon(
    icon: ImageBitmap?,
    appName: String,
    size: Dp = 48.dp
) {
    if (icon != null) {
        Image(
            bitmap = icon,
            contentDescription = "$appName icon",
            modifier = Modifier
                .size(size)
                .clip(RoundedCornerShape(12.dp))
        )
    } else {
        Box(
            modifier = Modifier
                .size(size)
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Rounded.Android,
                contentDescription = "$appName icon",
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.size(size * 0.58f)
            )
        }
    }
}

@Composable
fun PermissionRationaleCard(
    missingPermissions: List<String>,
    onGrantClick: () -> Unit,
    modifier: Modifier = Modifier,
    title: String = "Permissions Needed for Offline Transfer",
    description: String = "APK Share works 100% offline. Android requires all of these permissions before discovering or sharing with nearby phones:"
) {
    val context = LocalContext.current
    Card(
        modifier = modifier
            .fillMaxWidth()
            .testTag("permission_rationale_card"),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f)
        ),
        shape = RoundedCornerShape(20.dp)
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Rounded.Security,
                    contentDescription = "Permissions required",
                    tint = PrimaryBlue,
                    modifier = Modifier.size(28.dp)
                )
                Spacer(modifier = Modifier.width(12.dp))
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            }
            Text(
                text = description,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            missingPermissions.forEach { perm ->
                Text(
                    text = "• ${PermissionHelper.friendlyPermissionLabel(perm)}",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Button(
                    onClick = onGrantClick,
                    modifier = Modifier
                        .weight(1f)
                        .testTag("grant_permissions_button")
                ) {
                    Text("Grant permissions")
                }
                OutlinedButton(
                    onClick = {
                        val intent = Intent(
                            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            Uri.parse("package:${context.packageName}")
                        )
                        runCatching { context.startActivity(intent) }.onFailure {
                            runCatching { context.startActivity(Intent(Settings.ACTION_SETTINGS)) }
                        }
                    },
                    modifier = Modifier.testTag("open_app_settings_button")
                ) {
                    Text("Open Settings")
                }
            }
        }
    }
}

@Composable
fun ConnectivityRequiredDialog(
    isBluetoothEnabled: Boolean,
    isWifiEnabled: Boolean,
    onOpenBluetoothSettings: () -> Unit,
    onOpenWifiSettings: () -> Unit,
    onRetry: () -> Unit,
    onDismiss: () -> Unit
) {
    val title = when {
        !isBluetoothEnabled && !isWifiEnabled -> "Turn On Bluetooth & Wi-Fi"
        !isBluetoothEnabled -> "Turn On Bluetooth"
        else -> "Turn On Wi-Fi"
    }
    val message = when {
        !isBluetoothEnabled && !isWifiEnabled ->
            "Nearby offline transfer needs both Bluetooth and Wi-Fi turned ON to discover phones and transfer APKs at high speed (no internet needed)."
        !isBluetoothEnabled ->
            "Nearby offline transfer needs Bluetooth turned ON to discover and pair with the other phone."
        else ->
            "Nearby offline transfer needs Wi-Fi turned ON for high-speed offline transfer (no internet connection is required)."
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.testTag("connectivity_required_dialog"),
        icon = {
            Icon(
                imageVector = if (!isBluetoothEnabled) Icons.Rounded.BluetoothDisabled else Icons.Rounded.WifiOff,
                contentDescription = title,
                tint = WarningAmber
            )
        },
        title = {
            Text(text = title, fontWeight = FontWeight.Bold)
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodyMedium
                )
                if (!isBluetoothEnabled && !isWifiEnabled) {
                    OutlinedButton(
                        onClick = onOpenBluetoothSettings,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("dialog_open_bluetooth_settings_button")
                    ) {
                        Text("Open Bluetooth Settings")
                    }
                    OutlinedButton(
                        onClick = onOpenWifiSettings,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("dialog_open_wifi_settings_button")
                    ) {
                        Text("Open Wi-Fi Settings")
                    }
                }
            }
        },
        confirmButton = {
            if (!isBluetoothEnabled && isWifiEnabled) {
                Button(
                    onClick = onOpenBluetoothSettings,
                    modifier = Modifier.testTag("dialog_open_bluetooth_settings_button")
                ) {
                    Text("Bluetooth Settings")
                }
            } else if (isBluetoothEnabled && !isWifiEnabled) {
                Button(
                    onClick = onOpenWifiSettings,
                    modifier = Modifier.testTag("dialog_open_wifi_settings_button")
                ) {
                    Text("Wi-Fi Settings")
                }
            } else {
                Button(
                    onClick = onRetry,
                    modifier = Modifier.testTag("dialog_retry_connectivity_button")
                ) {
                    Text("Check Again")
                }
            }
        },
        dismissButton = {
            if (!isBluetoothEnabled && !isWifiEnabled) {
                TextButton(
                    onClick = onDismiss,
                    modifier = Modifier.testTag("dialog_dismiss_connectivity_button")
                ) {
                    Text("Close")
                }
            } else {
                TextButton(
                    onClick = onRetry,
                    modifier = Modifier.testTag("dialog_recheck_connectivity_button")
                ) {
                    Text("Check Again")
                }
            }
        }
    )
}

@Composable
fun ConnectivityRequiredCard(
    isBluetoothEnabled: Boolean,
    isWifiEnabled: Boolean,
    onOpenBluetoothSettings: () -> Unit,
    onOpenWifiSettings: () -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier
) {
    val title = when {
        !isBluetoothEnabled && !isWifiEnabled -> "Bluetooth and Wi-Fi are Off"
        !isBluetoothEnabled -> "Bluetooth is Off"
        else -> "Wi-Fi is Off"
    }
    val description = when {
        !isBluetoothEnabled && !isWifiEnabled ->
            "Turn on both Bluetooth and Wi-Fi to start Nearby offline transfer. No internet connection is needed."
        !isBluetoothEnabled ->
            "Turn on Bluetooth to discover nearby phones offline."
        else ->
            "Turn on Wi-Fi for high-speed offline APK transfer (no internet needed)."
    }

    Card(
        modifier = modifier
            .fillMaxWidth()
            .testTag("connectivity_required_card"),
        colors = CardDefaults.cardColors(
            containerColor = WarningAmber.copy(alpha = 0.14f)
        ),
        shape = RoundedCornerShape(20.dp)
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = if (!isBluetoothEnabled) Icons.Rounded.BluetoothDisabled else Icons.Rounded.WifiOff,
                    contentDescription = title,
                    tint = WarningAmber,
                    modifier = Modifier.size(28.dp)
                )
                Spacer(modifier = Modifier.width(12.dp))
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            }
            Text(
                text = description,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (!isBluetoothEnabled) {
                Button(
                    onClick = onOpenBluetoothSettings,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("open_bluetooth_settings_button")
                ) {
                    Icon(Icons.Rounded.Settings, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Open Bluetooth Settings")
                }
            }
            if (!isWifiEnabled) {
                Button(
                    onClick = onOpenWifiSettings,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("open_wifi_settings_button")
                ) {
                    Icon(Icons.Rounded.Settings, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Open Wi-Fi Settings")
                }
            }
            OutlinedButton(
                onClick = onRetry,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("retry_connectivity_button")
            ) {
                Icon(Icons.Rounded.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text("Check Again")
            }
        }
    }
}

@Composable
fun UnknownSourcesPermissionCard(
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = WarningAmber.copy(alpha = 0.14f)
        ),
        shape = RoundedCornerShape(18.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Rounded.Shield,
                    contentDescription = "Install unknown apps permission",
                    tint = WarningAmber
                )
                Spacer(modifier = Modifier.width(10.dp))
                Text(
                    text = "Allow Installing Apps",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold
                )
            }
            Text(
                text = stringResource(R.string.unknown_sources_explanation),
                style = MaterialTheme.typography.bodyMedium
            )
            Button(
                onClick = onOpenSettings,
                colors = ButtonDefaults.buttonColors(containerColor = WarningAmber, contentColor = Color.White),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("open_unknown_sources_settings_button")
            ) {
                Icon(Icons.Rounded.Settings, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text(stringResource(R.string.open_settings))
            }
        }
    }
}

@Composable
fun QrCodeScannerView(
    onQrDetected: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val cameraExecutor = remember { Executors.newSingleThreadExecutor() }
    var alreadyDetected by remember { mutableStateOf(false) }

    DisposableEffect(Unit) {
        onDispose {
            cameraExecutor.shutdown()
        }
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(260.dp)
            .clip(RoundedCornerShape(20.dp))
            .border(2.dp, SuccessGreen, RoundedCornerShape(20.dp))
    ) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                val previewView = PreviewView(ctx)
                val cameraProviderFuture = ProcessCameraProvider.getInstance(ctx)
                cameraProviderFuture.addListener({
                    try {
                        val cameraProvider = cameraProviderFuture.get()
                        val preview = Preview.Builder().build().also {
                            it.surfaceProvider = previewView.surfaceProvider
                        }
                        val analysis = ImageAnalysis.Builder()
                            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                            .build()
                            .also { imageAnalysis ->
                                imageAnalysis.setAnalyzer(cameraExecutor) { imageProxy ->
                                    if (!alreadyDetected) {
                                        val text = QrCodeUtils.decodeQrFromImageProxy(imageProxy)
                                        if (!text.isNullOrBlank()) {
                                            alreadyDetected = true
                                            ContextCompat.getMainExecutor(ctx).execute {
                                                onQrDetected(text)
                                            }
                                        }
                                    }
                                    imageProxy.close()
                                }
                            }
                        cameraProvider.unbindAll()
                        cameraProvider.bindToLifecycle(
                            lifecycleOwner,
                            CameraSelector.DEFAULT_BACK_CAMERA,
                            preview,
                            analysis
                        )
                    } catch (t: Throwable) {
                        Log.e("QrScanner", "CameraX bind failed", t)
                    }
                }, ContextCompat.getMainExecutor(ctx))
                previewView
            }
        )

        Box(
            modifier = Modifier
                .align(Alignment.Center)
                .size(170.dp)
                .border(2.dp, Color.White.copy(alpha = 0.85f), RoundedCornerShape(16.dp))
        )
        Surface(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(10.dp),
            shape = RoundedCornerShape(10.dp),
            color = Color.Black.copy(alpha = 0.65f)
        ) {
            Text(
                text = "Point camera at Phone A's QR code",
                color = Color.White,
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
            )
        }
    }
}
