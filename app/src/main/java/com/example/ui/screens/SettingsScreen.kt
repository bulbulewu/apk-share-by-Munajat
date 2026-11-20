package com.example.ui.screens

import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.selection.selectable
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
import androidx.compose.material.icons.rounded.BatterySaver
import androidx.compose.material.icons.rounded.DarkMode
import androidx.compose.material.icons.rounded.Fingerprint
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.NotificationsActive
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material.icons.rounded.SystemUpdate
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.BuildConfig
import com.example.R
import com.example.data.prefs.UserSettings
import com.example.ui.theme.ThemePreference
import com.example.ui.theme.WarningAmber
import com.example.update.GitHubReleaseInfo
import com.example.update.ManualUpdateCheckUiState
import com.example.update.UpdateDownloadState
import com.example.util.BiometricHelper
import com.example.util.LocaleHelper

@Composable
fun SettingsScreen(
    settings: UserSettings,
    onUpdateDeviceName: (String) -> Unit,
    onUpdateTheme: (ThemePreference) -> Unit,
    onToggleBiometric: (Boolean) -> Unit,
    onTogglePublicDownloads: (Boolean) -> Unit,
    onTogglePowerSaver: (Boolean) -> Unit,
    onUpdateLanguage: (String) -> Unit = {},
    modifier: Modifier = Modifier,
    manualUpdateState: ManualUpdateCheckUiState = ManualUpdateCheckUiState.Idle,
    updateDownloadState: UpdateDownloadState = UpdateDownloadState.Idle,
    onToggleNotifyUpdates: (Boolean) -> Unit = {},
    onCheckForUpdates: () -> Unit = {},
    onStartUpdateDownload: (GitHubReleaseInfo) -> Unit = {},
    onOpenInstallPermissionSettings: () -> Unit = {},
    onRetryInstallUpdate: () -> Unit = {},
    onOpenUpdateBrowserFallback: (String) -> Unit = {}
) {
    val context = LocalContext.current
    var nameDraft by remember(settings.deviceName) { mutableStateOf(settings.deviceName) }
    val biometricSupported = remember { BiometricHelper.isBiometricAvailable(context) }

    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.TopCenter
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = 760.dp)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                text = stringResource(R.string.settings_title),
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold
            )

            // Device Name Card
            Card(
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.PhoneAndroid, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = stringResource(R.string.settings_device_name_title),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Text(
                        text = stringResource(R.string.settings_device_name_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedTextField(
                            value = nameDraft,
                            onValueChange = { nameDraft = it },
                            singleLine = true,
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier
                                .weight(1f)
                                .testTag("device_name_input")
                        )
                        Button(
                            onClick = {
                                onUpdateDeviceName(nameDraft)
                                Toast.makeText(
                                    context,
                                    context.getString(R.string.settings_device_name_saved_toast),
                                    Toast.LENGTH_SHORT
                                ).show()
                            },
                            modifier = Modifier.testTag("save_device_name_button")
                        ) {
                            Text(stringResource(R.string.action_save))
                        }
                    }
                }
            }

            // Language Card
            val languageOptions = listOf(
                LocaleHelper.LANGUAGE_SYSTEM to stringResource(R.string.settings_language_system),
                LocaleHelper.LANGUAGE_ENGLISH to stringResource(R.string.settings_language_english),
                LocaleHelper.LANGUAGE_BANGLA to stringResource(R.string.settings_language_bangla)
            )
            Card(
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                modifier = Modifier.testTag("language_settings_card")
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Rounded.Language,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = stringResource(R.string.settings_language_title),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Text(
                        text = stringResource(R.string.settings_language_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    languageOptions.forEach { (code, label) ->
                        val selected = settings.language == code
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .selectable(
                                    selected = selected,
                                    role = Role.RadioButton,
                                    onClick = { onUpdateLanguage(code) }
                                )
                                .minimumInteractiveComponentSize()
                                .padding(horizontal = 4.dp, vertical = 2.dp)
                                .testTag("language_option_$code"),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = selected,
                                onClick = null
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = label,
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal
                            )
                        }
                    }
                }
            }

            // Theme / Dark Mode Card
            val isCurrentlyDark = when (settings.themePreference) {
                ThemePreference.SYSTEM -> isSystemInDarkTheme()
                ThemePreference.LIGHT -> false
                ThemePreference.DARK -> true
            }
            Card(
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
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
                        Row(
                            modifier = Modifier.weight(1f),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Rounded.DarkMode, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                            Spacer(modifier = Modifier.width(10.dp))
                            Column {
                                Text(
                                    text = stringResource(R.string.settings_dark_mode_title),
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    text = stringResource(R.string.settings_dark_mode_desc),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        Switch(
                            checked = isCurrentlyDark,
                            onCheckedChange = { enabled ->
                                onUpdateTheme(if (enabled) ThemePreference.DARK else ThemePreference.LIGHT)
                            },
                            modifier = Modifier.testTag("dark_mode_switch")
                        )
                    }
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            Icons.Rounded.Palette,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        ThemePreference.entries.forEach { pref ->
                            FilterChip(
                                selected = settings.themePreference == pref,
                                onClick = { onUpdateTheme(pref) },
                                label = {
                                    Text(
                                        when (pref) {
                                            ThemePreference.SYSTEM -> stringResource(R.string.settings_theme_system)
                                            ThemePreference.LIGHT -> stringResource(R.string.settings_theme_light)
                                            ThemePreference.DARK -> stringResource(R.string.settings_theme_dark)
                                        }
                                    )
                                },
                                modifier = Modifier.testTag("theme_chip_${pref.name.lowercase()}")
                            )
                        }
                    }
                }
            }

            // Storage & Security Card
            Card(
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            modifier = Modifier.weight(1f),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Rounded.Folder, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                            Spacer(modifier = Modifier.width(10.dp))
                            Column {
                                Text(
                                    text = stringResource(R.string.settings_downloads_title),
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Text(
                                    text = stringResource(R.string.settings_downloads_desc),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        Switch(
                            checked = settings.exportToPublicDownloads,
                            onCheckedChange = onTogglePublicDownloads,
                            modifier = Modifier.testTag("public_downloads_switch")
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            modifier = Modifier.weight(1f),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Rounded.BatterySaver, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                            Spacer(modifier = Modifier.width(10.dp))
                            Column {
                                Text(
                                    text = stringResource(R.string.settings_power_saver_title),
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Text(
                                    text = stringResource(R.string.settings_power_saver_desc),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        Switch(
                            checked = settings.powerSaverEnabled,
                            onCheckedChange = onTogglePowerSaver,
                            modifier = Modifier.testTag("power_saver_switch")
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            modifier = Modifier.weight(1f),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Rounded.Fingerprint, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                            Spacer(modifier = Modifier.width(10.dp))
                            Column {
                                Text(
                                    text = stringResource(R.string.settings_biometric_title),
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Text(
                                    text = if (biometricSupported) {
                                        stringResource(R.string.settings_biometric_desc_supported)
                                    } else {
                                        stringResource(R.string.settings_biometric_desc_unsupported)
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        Switch(
                            checked = settings.biometricLockEnabled,
                            enabled = biometricSupported || settings.biometricLockEnabled,
                            onCheckedChange = onToggleBiometric,
                            modifier = Modifier.testTag("biometric_lock_switch")
                        )
                    }
                }
            }

            // Limitations Card (Mandatory per spec)
            Card(
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = WarningAmber.copy(alpha = 0.12f))
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Rounded.WarningAmber,
                            contentDescription = stringResource(R.string.limitations_title),
                            tint = WarningAmber
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = stringResource(R.string.limitations_title),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Text(
                        text = stringResource(R.string.limitations_body),
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }

            // App Updates Card
            Card(
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                modifier = Modifier.testTag("updates_settings_card")
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Rounded.SystemUpdate,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = stringResource(R.string.settings_updates_title),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    // Notify about new updates switch
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            modifier = Modifier.weight(1f),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                Icons.Rounded.NotificationsActive,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Column {
                                Text(
                                    text = stringResource(R.string.settings_notify_updates_title),
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Text(
                                    text = stringResource(R.string.settings_notify_updates_desc),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        Switch(
                            checked = settings.notifyAboutUpdates,
                            onCheckedChange = onToggleNotifyUpdates,
                            modifier = Modifier.testTag("notify_updates_switch")
                        )
                    }

                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))

                    // Check for updates row
                    val isChecking = manualUpdateState is ManualUpdateCheckUiState.Checking
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .clickable(enabled = !isChecking) { onCheckForUpdates() }
                            .minimumInteractiveComponentSize()
                            .padding(vertical = 4.dp)
                            .testTag("check_for_updates_row"),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .padding(end = 8.dp),
                            verticalArrangement = Arrangement.spacedBy(2.dp)
                        ) {
                            Text(
                                text = stringResource(R.string.settings_check_updates_title),
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                text = when (val state = manualUpdateState) {
                                    ManualUpdateCheckUiState.Idle ->
                                        stringResource(R.string.settings_check_updates_desc)
                                    ManualUpdateCheckUiState.Checking ->
                                        stringResource(R.string.update_checking)
                                    is ManualUpdateCheckUiState.UpdateAvailable ->
                                        stringResource(R.string.update_new_version_available, state.release.tagName)
                                    ManualUpdateCheckUiState.UpToDate ->
                                        stringResource(R.string.update_up_to_date)
                                    ManualUpdateCheckUiState.Failed ->
                                        stringResource(R.string.update_check_failed)
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = when (manualUpdateState) {
                                    is ManualUpdateCheckUiState.UpdateAvailable -> MaterialTheme.colorScheme.primary
                                    ManualUpdateCheckUiState.Failed -> MaterialTheme.colorScheme.error
                                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                                },
                                modifier = Modifier.testTag("check_for_updates_status_text")
                            )
                        }
                        if (isChecking) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(22.dp),
                                strokeWidth = 2.dp
                            )
                        } else {
                            OutlinedButton(
                                onClick = onCheckForUpdates,
                                modifier = Modifier.testTag("check_for_updates_button")
                            ) {
                                Text(stringResource(R.string.update_check_button))
                            }
                        }
                    }

                    // Inline Update action when a newer version is found
                    if (manualUpdateState is ManualUpdateCheckUiState.UpdateAvailable &&
                        updateDownloadState is UpdateDownloadState.Idle
                    ) {
                        Button(
                            onClick = { onStartUpdateDownload(manualUpdateState.release) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("settings_update_now_button")
                        ) {
                            Text(stringResource(R.string.update_action_update))
                        }
                    }

                    // Inline download progress or permission prompt if triggered from Settings
                    when (val dlState = updateDownloadState) {
                        is UpdateDownloadState.Downloading -> {
                            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text(
                                    text = if (dlState.progressPercent >= 0) {
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
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.primary
                                )
                                if (dlState.progressPercent >= 0) {
                                    LinearProgressIndicator(
                                        progress = { dlState.progressPercent / 100f },
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                } else {
                                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                                }
                            }
                        }

                        is UpdateDownloadState.Installing -> {
                            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text(
                                    text = stringResource(R.string.update_installing_status),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.primary
                                )
                                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                            }
                        }

                        is UpdateDownloadState.PermissionRequired -> {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(
                                    text = stringResource(R.string.update_permission_needed),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    OutlinedButton(onClick = onOpenInstallPermissionSettings) {
                                        Text(stringResource(R.string.update_action_allow_install))
                                    }
                                    Button(onClick = onRetryInstallUpdate) {
                                        Text(stringResource(R.string.update_action_install_now))
                                    }
                                }
                                TextButton(
                                    onClick = { onOpenUpdateBrowserFallback(dlState.releasePageUrl) }
                                ) {
                                    Text(stringResource(R.string.update_action_open_browser))
                                }
                            }
                        }

                        UpdateDownloadState.Idle,
                        UpdateDownloadState.FallbackOpened -> Unit
                    }
                }
            }

            // About Card
            Card(
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f))
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.Info, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = stringResource(R.string.settings_about_title),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Text(
                        text = stringResource(R.string.settings_about_version, BuildConfig.VERSION_NAME),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = stringResource(R.string.settings_about_engine),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}
