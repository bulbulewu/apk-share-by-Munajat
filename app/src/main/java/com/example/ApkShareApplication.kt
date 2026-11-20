package com.example

import android.app.Application
import com.example.apk.ApkExtractor
import com.example.apk.ApkStorageManager
import com.example.apk.InstalledAppsRepository
import com.example.data.db.AppDatabase
import com.example.data.db.TransferHistoryRepository
import com.example.data.prefs.SettingsDataStore
import com.example.installer.ApkInstaller
import com.example.service.NotificationHelper
import com.example.transfer.HotspotController
import com.example.transfer.HttpApkDownloader
import com.example.transfer.LocalHttpApkServer
import com.example.transfer.NearbyTransferManager
import com.example.update.AndroidNetworkConnectivityChecker
import com.example.update.AppUpdateChecker
import com.example.update.AppUpdateDownloader

class AppContainer(application: Application) {
    private val database = AppDatabase.getInstance(application)
    val historyRepository = TransferHistoryRepository(database.transferHistoryDao())
    val settingsDataStore = SettingsDataStore(application)
    val installedAppsRepository = InstalledAppsRepository(application)
    val apkExtractor = ApkExtractor(application)
    val apkStorageManager = ApkStorageManager(application)
    val apkInstaller = ApkInstaller(application)
    val nearbyTransferManager = NearbyTransferManager(application, apkStorageManager)
    val hotspotController = HotspotController(application)
    val localHttpApkServer = LocalHttpApkServer()
    val httpApkDownloader = HttpApkDownloader(application, apkStorageManager)
    val appUpdateChecker = AppUpdateChecker(
        settingsDataStore = settingsDataStore,
        networkChecker = AndroidNetworkConnectivityChecker(application)
    )
    val appUpdateDownloader = AppUpdateDownloader(application, apkInstaller)
}

class ApkShareApplication : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        NotificationHelper.createChannels(this)
        container = AppContainer(this)
    }
}
