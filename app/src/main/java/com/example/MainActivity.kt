package com.example

import android.content.Context
import android.os.Bundle
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.rounded.Fingerprint
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.example.ui.components.LocalPowerSaverEnabled
import com.example.ui.screens.AppPickerScreen
import com.example.ui.screens.HistoryScreen
import com.example.ui.screens.HomeScreen
import com.example.ui.screens.ReceiverScreen
import com.example.ui.screens.SenderTransferScreen
import com.example.ui.screens.SettingsScreen
import com.example.ui.theme.ApkShareTheme
import com.example.ui.viewmodel.MainViewModel
import com.example.ui.viewmodel.ReceiverViewModel
import com.example.ui.viewmodel.SenderViewModel
import com.example.util.ApkShareHelper
import com.example.util.BiometricHelper
import com.example.util.LocaleHelper
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable

@Serializable
object HomeRoute

@Serializable
object HistoryRoute

@Serializable
object SettingsRoute

@Serializable
object AppPickerRoute

@Serializable
object SenderTransferRoute

@Serializable
object ReceiverRoute

class MainActivity : FragmentActivity() {

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleHelper.wrapBaseContext(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val container = (application as ApkShareApplication).container

        setContent {
            val mainViewModel: MainViewModel = viewModel(
                factory = MainViewModel.Factory(
                    settingsDataStore = container.settingsDataStore,
                    historyRepository = container.historyRepository,
                    appUpdateChecker = container.appUpdateChecker,
                    appUpdateDownloader = container.appUpdateDownloader
                )
            )
            val senderViewModel: SenderViewModel = viewModel(
                factory = SenderViewModel.Factory(
                    container.installedAppsRepository,
                    container.apkExtractor,
                    container.nearbyTransferManager,
                    container.hotspotController,
                    container.localHttpApkServer,
                    container.settingsDataStore,
                    container.historyRepository
                )
            )
            val receiverViewModel: ReceiverViewModel = viewModel(
                factory = ReceiverViewModel.Factory(
                    container.nearbyTransferManager,
                    container.httpApkDownloader,
                    container.apkInstaller,
                    container.settingsDataStore,
                    container.historyRepository
                )
            )

            val settings by mainViewModel.settings.collectAsStateWithLifecycle()
            val history by mainViewModel.history.collectAsStateWithLifecycle()
            val isLocked by mainViewModel.isLocked.collectAsStateWithLifecycle()
            val lockChecked by mainViewModel.lockChecked.collectAsStateWithLifecycle()
            val homeUpdatePrompt by mainViewModel.homeUpdatePrompt.collectAsStateWithLifecycle()
            val manualUpdateState by mainViewModel.manualUpdateState.collectAsStateWithLifecycle()
            val updateDownloadState by mainViewModel.updateDownloadState.collectAsStateWithLifecycle()

            LaunchedEffect(settings.powerSaverEnabled) {
                container.nearbyTransferManager.powerSaverEnabled = settings.powerSaverEnabled
                container.httpApkDownloader.powerSaverEnabled = settings.powerSaverEnabled
                container.localHttpApkServer.powerSaverEnabled = settings.powerSaverEnabled
            }

            LaunchedEffect(lockChecked, settings.language) {
                if (lockChecked) {
                    LocaleHelper.applyAppLocale(this@MainActivity, settings.language)
                }
            }

            LaunchedEffect(lockChecked, isLocked) {
                if (lockChecked && !isLocked) {
                    mainViewModel.checkForUpdatesAutomaticallyIfDue()
                }
            }

            val baseConfig = LocalConfiguration.current
            val (localizedContext, localizedConfig) = remember(settings.language, baseConfig) {
                LocaleHelper.createLocalizedComposeContext(
                    baseContext = this@MainActivity,
                    baseConfig = baseConfig,
                    languageCode = settings.language
                )
            }

            CompositionLocalProvider(
                LocalContext provides localizedContext,
                LocalConfiguration provides localizedConfig,
                LocalPowerSaverEnabled provides settings.powerSaverEnabled
            ) {
                ApkShareTheme(themePreference = settings.themePreference) {
                    when {
                        !lockChecked -> {
                            Box(
                                modifier = Modifier.fillMaxSize(),
                                contentAlignment = Alignment.Center
                            ) {
                                CircularProgressIndicator()
                            }
                        }

                        isLocked -> {
                            BiometricLockGate(
                                onRequestUnlock = { onError ->
                                    if (BiometricHelper.isBiometricAvailable(this@MainActivity)) {
                                        BiometricHelper.authenticate(
                                            activity = this@MainActivity,
                                            title = localizedContext.getString(R.string.biometric_prompt_title),
                                            subtitle = localizedContext.getString(R.string.biometric_prompt_subtitle),
                                            onSuccess = { mainViewModel.unlockApp() },
                                            onError = onError
                                        )
                                    } else {
                                        mainViewModel.unlockApp()
                                    }
                                }
                            )
                        }

                        else -> {
                            val shareScope = rememberCoroutineScope()
                            var sharingHistoryItemId by remember { mutableStateOf<Long?>(null) }

                            ApkShareNavHost(
                                mainViewModel = mainViewModel,
                                senderViewModel = senderViewModel,
                                receiverViewModel = receiverViewModel,
                                settings = settings,
                                history = history,
                                homeUpdatePrompt = homeUpdatePrompt,
                                manualUpdateState = manualUpdateState,
                                updateDownloadState = updateDownloadState,
                                sharingHistoryItemId = sharingHistoryItemId,
                                onShareHistoryItem = { item ->
                                    if (sharingHistoryItemId != null) return@ApkShareNavHost
                                    sharingHistoryItemId = item.id
                                    shareScope.launch {
                                        try {
                                            val resolved = ApkShareHelper.resolveHistoryShareableFile(
                                                context = localizedContext,
                                                item = item,
                                                storageManager = container.apkStorageManager,
                                                apkExtractor = container.apkExtractor
                                            )
                                            if (resolved == null) {
                                                Toast.makeText(
                                                    localizedContext,
                                                    localizedContext.getString(R.string.share_history_file_missing),
                                                    Toast.LENGTH_SHORT
                                                ).show()
                                            } else {
                                                val launched = ApkShareHelper.launchShareSheet(
                                                    context = localizedContext,
                                                    file = resolved.file,
                                                    appName = resolved.appName,
                                                    isSplitBundle = resolved.isSplitBundle
                                                )
                                                if (!launched) {
                                                    Toast.makeText(
                                                        localizedContext,
                                                        localizedContext.getString(R.string.share_no_app_error),
                                                        Toast.LENGTH_SHORT
                                                    ).show()
                                                }
                                            }
                                        } finally {
                                            sharingHistoryItemId = null
                                        }
                                    }
                                },
                                onLanguageSelected = { lang ->
                                    mainViewModel.updateLanguage(lang)
                                    LocaleHelper.applyAppLocale(this@MainActivity, lang)
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun BiometricLockGate(
    onRequestUnlock: ((String) -> Unit) -> Unit
) {
    var errorMessage by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        onRequestUnlock { msg -> errorMessage = msg }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .padding(24.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Icon(
                imageVector = Icons.Rounded.Fingerprint,
                contentDescription = stringResource(R.string.cd_locked),
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(64.dp)
            )
            Text(
                text = stringResource(R.string.biometric_locked_title),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = errorMessage ?: stringResource(R.string.biometric_locked_subtitle),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(4.dp))
            Button(
                onClick = {
                    errorMessage = null
                    onRequestUnlock { msg -> errorMessage = msg }
                },
                modifier = Modifier.testTag("unlock_biometric_button")
            ) {
                Text(stringResource(R.string.biometric_unlock_button))
            }
        }
    }
}

@Composable
private fun ApkShareNavHost(
    mainViewModel: MainViewModel,
    senderViewModel: SenderViewModel,
    receiverViewModel: ReceiverViewModel,
    settings: com.example.data.prefs.UserSettings,
    history: List<com.example.data.db.TransferHistoryEntity>,
    homeUpdatePrompt: com.example.update.GitHubReleaseInfo?,
    manualUpdateState: com.example.update.ManualUpdateCheckUiState,
    updateDownloadState: com.example.update.UpdateDownloadState,
    sharingHistoryItemId: Long?,
    onShareHistoryItem: (com.example.data.db.TransferHistoryEntity) -> Unit,
    onLanguageSelected: (String) -> Unit
) {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentDest = backStackEntry?.destination

    val isTopLevelTab = currentDest?.hasRoute(HomeRoute::class) == true ||
        currentDest?.hasRoute(HistoryRoute::class) == true ||
        currentDest?.hasRoute(SettingsRoute::class) == true

    val navigateTab: (Any) -> Unit = { route ->
        navController.navigate(route) {
            popUpTo(navController.graph.findStartDestination().id) {
                saveState = true
            }
            launchSingleTop = true
            restoreState = true
        }
    }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val useRail = maxWidth >= 600.dp

        Scaffold(
            contentWindowInsets = WindowInsets.safeDrawing,
            bottomBar = {
                if (isTopLevelTab && !useRail) {
                    NavigationBar {
                        NavigationBarItem(
                            selected = currentDest?.hasRoute(HomeRoute::class) == true,
                            onClick = { navigateTab(HomeRoute) },
                            icon = {
                                Icon(
                                    if (currentDest?.hasRoute(HomeRoute::class) == true) Icons.Filled.Home else Icons.Outlined.Home,
                                    contentDescription = stringResource(R.string.nav_home)
                                )
                            },
                            label = { Text(stringResource(R.string.nav_home)) },
                            modifier = Modifier.testTag("tab_home")
                        )
                        NavigationBarItem(
                            selected = currentDest?.hasRoute(HistoryRoute::class) == true,
                            onClick = { navigateTab(HistoryRoute) },
                            icon = {
                                Icon(
                                    if (currentDest?.hasRoute(HistoryRoute::class) == true) Icons.Filled.History else Icons.Outlined.History,
                                    contentDescription = stringResource(R.string.nav_history)
                                )
                            },
                            label = { Text(stringResource(R.string.nav_history)) },
                            modifier = Modifier.testTag("tab_history")
                        )
                        NavigationBarItem(
                            selected = currentDest?.hasRoute(SettingsRoute::class) == true,
                            onClick = { navigateTab(SettingsRoute) },
                            icon = {
                                Icon(
                                    if (currentDest?.hasRoute(SettingsRoute::class) == true) Icons.Filled.Settings else Icons.Outlined.Settings,
                                    contentDescription = stringResource(R.string.nav_settings)
                                )
                            },
                            label = { Text(stringResource(R.string.nav_settings)) },
                            modifier = Modifier.testTag("tab_settings")
                        )
                    }
                }
            }
        ) { innerPadding ->
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
            ) {
                if (isTopLevelTab && useRail) {
                    NavigationRail {
                        NavigationRailItem(
                            selected = currentDest?.hasRoute(HomeRoute::class) == true,
                            onClick = { navigateTab(HomeRoute) },
                            icon = {
                                Icon(
                                    if (currentDest?.hasRoute(HomeRoute::class) == true) Icons.Filled.Home else Icons.Outlined.Home,
                                    contentDescription = stringResource(R.string.nav_home)
                                )
                            },
                            label = { Text(stringResource(R.string.nav_home)) }
                        )
                        NavigationRailItem(
                            selected = currentDest?.hasRoute(HistoryRoute::class) == true,
                            onClick = { navigateTab(HistoryRoute) },
                            icon = {
                                Icon(
                                    if (currentDest?.hasRoute(HistoryRoute::class) == true) Icons.Filled.History else Icons.Outlined.History,
                                    contentDescription = stringResource(R.string.nav_history)
                                )
                            },
                            label = { Text(stringResource(R.string.nav_history)) }
                        )
                        NavigationRailItem(
                            selected = currentDest?.hasRoute(SettingsRoute::class) == true,
                            onClick = { navigateTab(SettingsRoute) },
                            icon = {
                                Icon(
                                    if (currentDest?.hasRoute(SettingsRoute::class) == true) Icons.Filled.Settings else Icons.Outlined.Settings,
                                    contentDescription = stringResource(R.string.nav_settings)
                                )
                            },
                            label = { Text(stringResource(R.string.nav_settings)) }
                        )
                    }
                }

                NavHost(
                    navController = navController,
                    startDestination = HomeRoute,
                    modifier = Modifier.weight(1f)
                ) {
                    composable<HomeRoute> {
                        HomeScreen(
                            deviceName = settings.deviceName,
                            recentTransfers = history,
                            onSendClick = { navController.navigate(AppPickerRoute) },
                            onReceiveClick = { navController.navigate(ReceiverRoute) },
                            onOpenHistory = { navigateTab(HistoryRoute) },
                            onOpenSettings = { navigateTab(SettingsRoute) },
                            availableUpdate = homeUpdatePrompt,
                            updateDownloadState = updateDownloadState,
                            onUpdateNow = { mainViewModel.startAppUpdate(it) },
                            onUpdateLater = { mainViewModel.deferUpdateForLater(it) },
                            onOpenInstallPermissionSettings = { mainViewModel.openInstallPermissionSettings() },
                            onRetryInstallUpdate = { mainViewModel.retryInstallDownloadedUpdate() },
                            onOpenUpdateBrowserFallback = { mainViewModel.openUpdateBrowserFallback(it) },
                            onDismissUpdateDownload = { mainViewModel.dismissUpdateDownloadUi() }
                        )
                    }

                    composable<HistoryRoute> {
                        HistoryScreen(
                            history = history,
                            onDeleteItem = { mainViewModel.deleteHistoryItem(it) },
                            onClearAll = { mainViewModel.clearHistory() },
                            onShareItem = onShareHistoryItem,
                            sharingItemId = sharingHistoryItemId
                        )
                    }

                    composable<SettingsRoute> {
                        SettingsScreen(
                            settings = settings,
                            onUpdateDeviceName = { mainViewModel.updateDeviceName(it) },
                            onUpdateTheme = { mainViewModel.updateThemePreference(it) },
                            onToggleBiometric = { mainViewModel.setBiometricLockEnabled(it) },
                            onTogglePublicDownloads = { mainViewModel.setExportToPublicDownloads(it) },
                            onTogglePowerSaver = { mainViewModel.setPowerSaverEnabled(it) },
                            onUpdateLanguage = onLanguageSelected,
                            manualUpdateState = manualUpdateState,
                            updateDownloadState = updateDownloadState,
                            onToggleNotifyUpdates = { mainViewModel.setNotifyAboutUpdates(it) },
                            onCheckForUpdates = { mainViewModel.checkForUpdatesManually() },
                            onStartUpdateDownload = { mainViewModel.startAppUpdate(it) },
                            onOpenInstallPermissionSettings = { mainViewModel.openInstallPermissionSettings() },
                            onRetryInstallUpdate = { mainViewModel.retryInstallDownloadedUpdate() },
                            onOpenUpdateBrowserFallback = { mainViewModel.openUpdateBrowserFallback(it) }
                        )
                    }

                    composable<AppPickerRoute> {
                        AppPickerScreen(
                            viewModel = senderViewModel,
                            onBack = { navController.popBackStack() },
                            onProceedToTransfer = { navController.navigate(SenderTransferRoute) }
                        )
                    }

                    composable<SenderTransferRoute> {
                        SenderTransferScreen(
                            viewModel = senderViewModel,
                            onDone = {
                                navController.popBackStack(HomeRoute, inclusive = false)
                            }
                        )
                    }

                    composable<ReceiverRoute> {
                        ReceiverScreen(
                            viewModel = receiverViewModel,
                            onDone = {
                                navController.popBackStack(HomeRoute, inclusive = false)
                            }
                        )
                    }
                }
            }
        }
    }
}
