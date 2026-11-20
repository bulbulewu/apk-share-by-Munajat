package com.example.ui.screens

import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.QrCode2
import androidx.compose.material.icons.rounded.RadioButtonUnchecked
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Send
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedIconButton
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.R
import com.example.model.InstalledAppInfo
import com.example.ui.components.InstalledAppIcon
import com.example.ui.theme.PrimaryBlue
import com.example.ui.viewmodel.AppSortOrder
import com.example.ui.viewmodel.AppsUiState
import com.example.ui.viewmodel.ExtractionUiState
import com.example.ui.viewmodel.SenderViewModel
import com.example.util.ApkShareHelper
import com.example.util.FormatUtils

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppPickerScreen(
    viewModel: SenderViewModel,
    onBack: () -> Unit,
    onProceedToTransfer: () -> Unit
) {
    val context = LocalContext.current
    val appsState by viewModel.appsUiState.collectAsStateWithLifecycle()
    val searchQuery by viewModel.searchQuery.collectAsStateWithLifecycle()
    val sortOrder by viewModel.sortOrder.collectAsStateWithLifecycle()
    val selectedPackageNames by viewModel.selectedPackageNames.collectAsStateWithLifecycle()
    val selectedApps by viewModel.selectedApps.collectAsStateWithLifecycle()
    val extractionState by viewModel.extractionState.collectAsStateWithLifecycle()
    val isSaving by viewModel.isSavingToDownloads.collectAsStateWithLifecycle()
    val isExtracting = extractionState is ExtractionUiState.Extracting
    val isBusy = isExtracting || isSaving

    val shareApp: (InstalledAppInfo) -> Unit = { targetApp ->
        viewModel.prepareAndShareApp(
            app = targetApp,
            onReadyToShare = { shareable ->
                val launched = ApkShareHelper.launchShareSheet(
                    context = context,
                    file = shareable.file,
                    appName = shareable.appName,
                    isSplitBundle = shareable.isSplitBundle
                )
                if (!launched) {
                    Toast.makeText(
                        context,
                        context.getString(R.string.share_no_app_error),
                        Toast.LENGTH_SHORT
                    ).show()
                }
            },
            onError = { errMsg ->
                Toast.makeText(
                    context,
                    errMsg ?: context.getString(R.string.share_failed_error, targetApp.appName),
                    Toast.LENGTH_SHORT
                ).show()
            }
        )
    }

    val shareSelectedApps: () -> Unit = {
        val targets = selectedApps
        if (targets.isNotEmpty()) {
            viewModel.prepareAndShareSelectedApps(
                apps = targets,
                onReadyToShare = { shareables ->
                    val launched = ApkShareHelper.launchShareMultipleSheet(
                        context = context,
                        items = shareables
                    )
                    if (!launched) {
                        Toast.makeText(
                            context,
                            context.getString(R.string.share_no_app_error),
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                },
                onError = { errMsg ->
                    val fallbackName = targets.firstOrNull()?.appName.orEmpty()
                    Toast.makeText(
                        context,
                        errMsg ?: context.getString(R.string.share_failed_error, fallbackName),
                        Toast.LENGTH_SHORT
                    ).show()
                }
            )
        }
    }

    val saveSelectedApps: () -> Unit = {
        val targets = selectedApps
        if (targets.isNotEmpty()) {
            viewModel.saveSelectedAppsToDownloads(
                context = context,
                apps = targets
            ) { savedCount, totalCount ->
                val toastMessage = when {
                    savedCount <= 0 -> context.getString(R.string.save_downloads_failed)
                    totalCount == 1 -> context.getString(R.string.save_downloads_success_single)
                    savedCount == totalCount -> context.getString(
                        R.string.save_downloads_success_multiple,
                        savedCount
                    )
                    else -> context.getString(
                        R.string.save_downloads_partial,
                        savedCount,
                        totalCount
                    )
                }
                Toast.makeText(context, toastMessage, Toast.LENGTH_SHORT).show()
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.picker_title), fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(
                        onClick = onBack,
                        modifier = Modifier
                            .minimumInteractiveComponentSize()
                            .testTag("picker_back_button")
                    ) {
                        Icon(
                            Icons.AutoMirrored.Rounded.ArrowBack,
                            contentDescription = stringResource(R.string.action_back)
                        )
                    }
                },
                actions = {
                    IconButton(
                        onClick = { viewModel.loadApps() },
                        modifier = Modifier
                            .minimumInteractiveComponentSize()
                            .testTag("refresh_apps_button")
                    ) {
                        Icon(Icons.Rounded.Refresh, contentDescription = stringResource(R.string.picker_refresh_cd))
                    }
                }
            )
        },
        bottomBar = {
            AnimatedVisibility(visible = selectedApps.isNotEmpty()) {
                if (selectedApps.isNotEmpty()) {
                    val totalSizeBytes = remember(selectedApps) {
                        selectedApps.sumOf { it.totalSizeBytes }
                    }
                    val singleApp = selectedApps.singleOrNull()
                    Surface(
                        shadowElevation = 10.dp,
                        tonalElevation = 4.dp,
                        color = MaterialTheme.colorScheme.surface
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 10.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = if (selectedApps.size == 1) {
                                            stringResource(R.string.picker_one_app_selected)
                                        } else {
                                            stringResource(R.string.picker_apps_selected_count, selectedApps.size)
                                        },
                                        style = MaterialTheme.typography.titleSmall,
                                        fontWeight = FontWeight.Bold,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.testTag("selected_apps_count_text")
                                    )
                                    val sizeLabel = if (singleApp != null) {
                                        if (singleApp.isSplitApk) {
                                            "${singleApp.appName} · " + stringResource(
                                                R.string.picker_splits_suffix,
                                                FormatUtils.formatBytes(singleApp.totalSizeBytes),
                                                singleApp.totalApkFileCount
                                            )
                                        } else {
                                            "${singleApp.appName} · ${FormatUtils.formatBytes(singleApp.totalSizeBytes)}"
                                        }
                                    } else {
                                        FormatUtils.formatBytes(totalSizeBytes)
                                    }
                                    Text(
                                        text = sizeLabel,
                                        style = MaterialTheme.typography.labelMedium,
                                        color = PrimaryBlue,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                                TextButton(
                                    onClick = {
                                        viewModel.clearExtractionError()
                                        viewModel.clearSelection()
                                    },
                                    enabled = !isBusy,
                                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                                    modifier = Modifier
                                        .minimumInteractiveComponentSize()
                                        .testTag("clear_selection_button")
                                ) {
                                    Text(
                                        text = stringResource(R.string.action_clear),
                                        fontWeight = FontWeight.SemiBold
                                    )
                                }
                            }
                            if (extractionState is ExtractionUiState.Error) {
                                val errorMsg = (extractionState as ExtractionUiState.Error).message
                                val fallbackName = selectedApps.firstOrNull()?.appName.orEmpty()
                                Text(
                                    text = errorMsg.ifBlank {
                                        stringResource(R.string.share_failed_error, fallbackName)
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error
                                )
                            }
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Button(
                                    onClick = {
                                        viewModel.prepareAndStartNearbyTransfer(onProceedToTransfer)
                                    },
                                    enabled = !isBusy,
                                    contentPadding = PaddingValues(horizontal = 12.dp),
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = PrimaryBlue,
                                        contentColor = Color.White
                                    ),
                                    modifier = Modifier
                                        .weight(1f)
                                        .height(50.dp)
                                        .testTag("send_selected_app_button")
                                ) {
                                    if (isBusy) {
                                        CircularProgressIndicator(
                                            modifier = Modifier.size(18.dp),
                                            strokeWidth = 2.dp,
                                            color = Color.White
                                        )
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text(
                                            text = if (isSaving) {
                                                stringResource(R.string.picker_saving_apk)
                                            } else {
                                                stringResource(R.string.picker_preparing_apk)
                                            },
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    } else {
                                        Icon(Icons.Rounded.Send, contentDescription = null, modifier = Modifier.size(18.dp))
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text(
                                            text = stringResource(R.string.picker_send_nearby),
                                            fontWeight = FontWeight.Bold,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                }
                                OutlinedButton(
                                    onClick = {
                                        viewModel.startHotspotModeDirect(onProceedToTransfer)
                                    },
                                    enabled = !isBusy,
                                    contentPadding = PaddingValues(horizontal = 10.dp),
                                    modifier = Modifier
                                        .height(50.dp)
                                        .testTag("send_hotspot_direct_button")
                                ) {
                                    Icon(
                                        Icons.Rounded.QrCode2,
                                        contentDescription = stringResource(R.string.picker_hotspot_cd),
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text(
                                        text = stringResource(R.string.picker_hotspot_button),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                                OutlinedIconButton(
                                    onClick = shareSelectedApps,
                                    enabled = !isBusy,
                                    modifier = Modifier
                                        .size(50.dp)
                                        .minimumInteractiveComponentSize()
                                        .testTag("share_selected_app_button")
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.Share,
                                        contentDescription = stringResource(R.string.share_apk_cd),
                                        tint = PrimaryBlue
                                    )
                                }
                                OutlinedIconButton(
                                    onClick = saveSelectedApps,
                                    enabled = !isBusy,
                                    modifier = Modifier
                                        .size(50.dp)
                                        .minimumInteractiveComponentSize()
                                        .testTag("save_selected_apps_button")
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.Download,
                                        contentDescription = stringResource(R.string.save_apk_cd),
                                        tint = PrimaryBlue
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentAlignment = Alignment.TopCenter
        ) {
            Column(
                modifier = Modifier
                    .widthIn(max = 760.dp)
                    .fillMaxSize()
            ) {
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { viewModel.updateSearchQuery(it) },
                    placeholder = { Text(stringResource(R.string.picker_search_hint)) },
                    leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
                    trailingIcon = {
                        if (searchQuery.isNotEmpty()) {
                            IconButton(onClick = { viewModel.updateSearchQuery("") }) {
                                Icon(Icons.Rounded.Close, contentDescription = stringResource(R.string.picker_clear_search_cd))
                            }
                        }
                    },
                    singleLine = true,
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 6.dp)
                        .testTag("search_apps_input")
                )

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    FilterChip(
                        selected = sortOrder == AppSortOrder.NAME,
                        onClick = { viewModel.updateSortOrder(AppSortOrder.NAME) },
                        label = { Text(stringResource(R.string.picker_sort_name)) }
                    )
                    FilterChip(
                        selected = sortOrder == AppSortOrder.SIZE,
                        onClick = { viewModel.updateSortOrder(AppSortOrder.SIZE) },
                        label = { Text(stringResource(R.string.picker_sort_largest)) }
                    )
                    FilterChip(
                        selected = sortOrder == AppSortOrder.INSTALL_DATE,
                        onClick = { viewModel.updateSortOrder(AppSortOrder.INSTALL_DATE) },
                        label = { Text(stringResource(R.string.picker_sort_newest)) }
                    )
                }

                Text(
                    text = stringResource(R.string.picker_single_select_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                )

                when (val state = appsState) {
                    is AppsUiState.Loading -> {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                CircularProgressIndicator()
                                Text(stringResource(R.string.picker_scanning_apps))
                            }
                        }
                    }

                    is AppsUiState.Error -> {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(24.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                Text(
                                    text = state.message,
                                    color = MaterialTheme.colorScheme.error
                                )
                                Button(onClick = { viewModel.loadApps() }) {
                                    Text(stringResource(R.string.action_retry))
                                }
                            }
                        }
                    }

                    is AppsUiState.Loaded -> {
                        if (state.apps.isEmpty()) {
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(24.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = if (searchQuery.isBlank()) {
                                        stringResource(R.string.picker_empty_apps)
                                    } else {
                                        stringResource(R.string.picker_empty_search, searchQuery)
                                    },
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        } else {
                            LazyColumn(
                                contentPadding = PaddingValues(
                                    start = 16.dp,
                                    end = 16.dp,
                                    top = 6.dp,
                                    bottom = 24.dp
                                ),
                                verticalArrangement = Arrangement.spacedBy(10.dp),
                                modifier = Modifier.fillMaxSize()
                            ) {
                                items(state.apps, key = { it.packageName }) { app ->
                                    val isSelected = selectedPackageNames.contains(app.packageName)
                                    AppListItemCard(
                                        app = app,
                                        isSelected = isSelected,
                                        isBusy = isBusy,
                                        loadIcon = { viewModel.loadIcon(app.packageName) },
                                        onClick = {
                                            viewModel.clearExtractionError()
                                            viewModel.toggleAppSelection(app)
                                        },
                                        onShareClick = { shareApp(app) }
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AppListItemCard(
    app: InstalledAppInfo,
    isSelected: Boolean,
    isBusy: Boolean,
    loadIcon: suspend () -> ImageBitmap?,
    onClick: () -> Unit,
    onShareClick: () -> Unit
) {
    var icon by remember(app.packageName) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(app.packageName) {
        icon = loadIcon()
    }

    Card(
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected) {
                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f)
            } else {
                MaterialTheme.colorScheme.surface
            }
        ),
        border = if (isSelected) BorderStroke(1.5.dp, PrimaryBlue) else BorderStroke(
            0.5.dp,
            MaterialTheme.colorScheme.outline.copy(alpha = 0.25f)
        ),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .testTag("app_item_${app.packageName}")
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            InstalledAppIcon(icon = icon, appName = app.appName, size = 48.dp)
            Spacer(modifier = Modifier.width(12.dp))
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = app.appName,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    if (app.isSplitApk) {
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = PrimaryBlue.copy(alpha = 0.14f)
                        ) {
                            Text(
                                text = stringResource(R.string.picker_split_badge, app.totalApkFileCount),
                                style = MaterialTheme.typography.labelSmall,
                                color = PrimaryBlue,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                    }
                }
                Text(
                    text = app.packageName,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = stringResource(
                        R.string.picker_app_meta,
                        app.versionName,
                        FormatUtils.formatBytes(app.totalSizeBytes),
                        FormatUtils.formatDate(app.installDateMillis)
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            IconButton(
                onClick = onShareClick,
                enabled = !isBusy,
                modifier = Modifier
                    .minimumInteractiveComponentSize()
                    .testTag("share_app_button_${app.packageName}")
            ) {
                Icon(
                    imageVector = Icons.Rounded.Share,
                    contentDescription = stringResource(R.string.share_app_cd, app.appName),
                    tint = PrimaryBlue
                )
            }
            Icon(
                imageVector = if (isSelected) {
                    Icons.Rounded.CheckCircle
                } else {
                    Icons.Rounded.RadioButtonUnchecked
                },
                contentDescription = if (isSelected) {
                    stringResource(R.string.picker_selected_cd)
                } else {
                    stringResource(R.string.picker_not_selected_cd)
                },
                tint = if (isSelected) PrimaryBlue else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .size(24.dp)
                    .testTag("app_select_indicator_${app.packageName}")
            )
        }
    }
}
