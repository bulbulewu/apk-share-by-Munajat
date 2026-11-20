package com.example

import android.Manifest
import android.app.Application
import android.bluetooth.BluetoothManager
import android.content.Context
import android.net.wifi.WifiManager
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import com.example.apk.ApkStorageManager
import com.example.model.ExtractedApkBundle
import com.example.transfer.HotspotController
import com.example.transfer.NearbyReceiverState
import com.example.transfer.NearbySenderState
import com.example.transfer.NearbyTransferManager
import com.example.ui.screens.ReceiverScreen
import com.example.ui.screens.SenderTransferScreen
import com.example.ui.theme.ApkShareTheme
import com.example.ui.viewmodel.ReceiverViewModel
import com.example.ui.viewmodel.SenderViewModel
import com.example.util.PermissionHelper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class NearbyPermissionFlowTest {

    @get:Rule
    val composeRule = createComposeRule()

    private lateinit var app: ApkShareApplication

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext<Application>() as ApkShareApplication
        app.container.nearbyTransferManager.stopAll()
        app.container.nearbyTransferManager.cancelSender()
        app.container.nearbyTransferManager.cancelReceiver()
    }

    @Test
    fun `api 36 nearby transfer permissions include bluetooth nearby wifi location and notifications`() {
        val perms = PermissionHelper.getNearbyTransferPermissions()
        assertTrue(perms.contains(Manifest.permission.BLUETOOTH_ADVERTISE))
        assertTrue(perms.contains(Manifest.permission.BLUETOOTH_CONNECT))
        assertTrue(perms.contains(Manifest.permission.BLUETOOTH_SCAN))
        assertTrue(perms.contains(Manifest.permission.NEARBY_WIFI_DEVICES))
        assertTrue(perms.contains(Manifest.permission.ACCESS_FINE_LOCATION))
        assertTrue(perms.contains(Manifest.permission.POST_NOTIFICATIONS))
    }

    @Test
    fun `api 36 hotspot permissions include nearby wifi location and notifications`() {
        val perms = PermissionHelper.getHotspotPermissions()
        assertTrue(perms.contains(Manifest.permission.NEARBY_WIFI_DEVICES))
        assertTrue(perms.contains(Manifest.permission.ACCESS_FINE_LOCATION))
        assertTrue(perms.contains(Manifest.permission.POST_NOTIFICATIONS))
    }

    @Test
    fun `nearby manager startAdvertising without permissions sets Error state and does not crash`() {
        val manager = NearbyTransferManager(app, ApkStorageManager(app))
        val dummyFile = File(app.cacheDir, "dummy.apk").apply { writeText("apk") }
        val bundle = ExtractedApkBundle(
            transferId = "abc12345",
            appName = "Test App",
            packageName = "com.test.app",
            versionName = "1.0",
            versionCode = 1L,
            files = listOf(dummyFile),
            totalSizeBytes = dummyFile.length(),
            isSplitApk = false,
            stagingDir = app.cacheDir,
            iconBase64 = null
        )

        manager.startAdvertising("Pixel Test", bundle)

        assertTrue(
            "Expected Error state when permissions missing, got ${manager.senderState.value}",
            manager.senderState.value is NearbySenderState.Error
        )
    }

    @Test
    fun `nearby manager startDiscovery without permissions sets Error state and does not crash`() {
        val manager = NearbyTransferManager(app, ApkStorageManager(app))

        manager.startDiscovery(exportToPublicDownloads = false)

        assertTrue(
            "Expected Error state when permissions missing, got ${manager.receiverState.value}",
            manager.receiverState.value is NearbyReceiverState.Error
        )
    }

    @Test
    fun `nearby manager startDiscovery with permissions granted but Bluetooth OFF sets Error state`() {
        grantAllNearbyPermissions()
        setRadios(bluetoothOn = false, wifiOn = true)

        val manager = NearbyTransferManager(app, ApkStorageManager(app))
        manager.startDiscovery(exportToPublicDownloads = false)

        val state = manager.receiverState.value
        assertTrue("Expected Error state when Bluetooth OFF, got $state", state is NearbyReceiverState.Error)
        assertTrue((state as NearbyReceiverState.Error).reason.contains("Bluetooth", ignoreCase = true))
    }

    @Test
    fun `hotspot controller without permissions does not crash and reports missing permissions`() {
        val controller = HotspotController(app)
        controller.startLocalHotspot()

        assertFalse(controller.hotspotInfo.value.isActive)
        assertTrue(controller.hotspotInfo.value.errorMessage != null)
    }

    @Test
    fun `receiver screen without permissions shows rationale with Grant and Settings buttons and never starts discovery`() {
        val viewModel = ReceiverViewModel(
            nearbyManager = app.container.nearbyTransferManager,
            httpDownloader = app.container.httpApkDownloader,
            apkInstaller = app.container.apkInstaller,
            settingsDataStore = app.container.settingsDataStore,
            historyRepository = app.container.historyRepository
        )

        composeRule.setContent {
            ApkShareTheme {
                ReceiverScreen(viewModel = viewModel, onDone = {})
            }
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("permission_rationale_card").assertIsDisplayed()
        composeRule.onNodeWithTag("grant_permissions_button").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("open_app_settings_button").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Grant permissions").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Open Settings").performScrollTo().assertIsDisplayed()
        assertEquals(NearbyReceiverState.Idle, viewModel.nearbyState.value)
    }

    @Test
    fun `receiver screen with permissions granted and Bluetooth OFF shows connectivity dialog and never starts discovery`() {
        grantAllNearbyPermissions()
        setRadios(bluetoothOn = false, wifiOn = true)

        val viewModel = ReceiverViewModel(
            nearbyManager = app.container.nearbyTransferManager,
            httpDownloader = app.container.httpApkDownloader,
            apkInstaller = app.container.apkInstaller,
            settingsDataStore = app.container.settingsDataStore,
            historyRepository = app.container.historyRepository
        )

        composeRule.setContent {
            ApkShareTheme {
                ReceiverScreen(viewModel = viewModel, onDone = {})
            }
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("connectivity_required_dialog").assertIsDisplayed()
        composeRule.onNodeWithTag("dialog_open_bluetooth_settings_button").assertIsDisplayed()
        assertEquals(NearbyReceiverState.Idle, viewModel.nearbyState.value)
    }

    @Test
    fun `sender transfer screen without permissions shows rationale and never starts advertising`() {
        val viewModel = SenderViewModel(
            appsRepository = app.container.installedAppsRepository,
            apkExtractor = app.container.apkExtractor,
            nearbyManager = app.container.nearbyTransferManager,
            hotspotController = app.container.hotspotController,
            httpServer = app.container.localHttpApkServer,
            settingsDataStore = app.container.settingsDataStore,
            historyRepository = app.container.historyRepository
        )

        composeRule.setContent {
            ApkShareTheme {
                SenderTransferScreen(viewModel = viewModel, onDone = {})
            }
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("permission_rationale_card").assertIsDisplayed()
        composeRule.onNodeWithTag("grant_permissions_button").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("open_app_settings_button").performScrollTo().assertIsDisplayed()
        assertEquals(NearbySenderState.Idle, viewModel.nearbyState.value)
    }

    @Test
    fun `sender transfer screen with permissions granted and Wi-Fi OFF shows connectivity dialog and never starts advertising`() {
        grantAllNearbyPermissions()
        setRadios(bluetoothOn = true, wifiOn = false)

        val viewModel = SenderViewModel(
            appsRepository = app.container.installedAppsRepository,
            apkExtractor = app.container.apkExtractor,
            nearbyManager = app.container.nearbyTransferManager,
            hotspotController = app.container.hotspotController,
            httpServer = app.container.localHttpApkServer,
            settingsDataStore = app.container.settingsDataStore,
            historyRepository = app.container.historyRepository
        )

        composeRule.setContent {
            ApkShareTheme {
                SenderTransferScreen(viewModel = viewModel, onDone = {})
            }
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("connectivity_required_dialog").assertIsDisplayed()
        composeRule.onNodeWithTag("dialog_open_wifi_settings_button").assertIsDisplayed()
        assertEquals(NearbySenderState.Idle, viewModel.nearbyState.value)
    }

    private fun grantAllNearbyPermissions() {
        shadowOf(app).grantPermissions(*PermissionHelper.getNearbyTransferPermissions().toTypedArray())
    }

    @Suppress("DEPRECATION")
    private fun setRadios(bluetoothOn: Boolean, wifiOn: Boolean) {
        val btManager = app.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        btManager?.adapter?.let { adapter ->
            shadowOf(adapter).setEnabled(bluetoothOn)
        }
        val wifiManager = app.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        wifiManager?.isWifiEnabled = wifiOn
    }
}
