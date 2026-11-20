package com.example.util

import android.Manifest
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import android.net.wifi.WifiManager
import android.os.Build
import androidx.core.content.ContextCompat

data class ConnectivityStatus(
    val isBluetoothEnabled: Boolean,
    val isWifiEnabled: Boolean
) {
    val isReadyForNearby: Boolean
        get() = isBluetoothEnabled && isWifiEnabled
}

object PermissionHelper {

    fun getNearbyTransferPermissions(): List<String> {
        val list = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            list += Manifest.permission.BLUETOOTH_ADVERTISE
            list += Manifest.permission.BLUETOOTH_CONNECT
            list += Manifest.permission.BLUETOOTH_SCAN
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            list += Manifest.permission.NEARBY_WIFI_DEVICES
            list += Manifest.permission.POST_NOTIFICATIONS
        }
        // ACCESS_FINE_LOCATION is required on all Android versions including Android 12+, 13+, and 16
        // because Nearby Connections discovery still requires it on many devices.
        list += Manifest.permission.ACCESS_FINE_LOCATION
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            list += Manifest.permission.ACCESS_COARSE_LOCATION
        }
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
            list += Manifest.permission.WRITE_EXTERNAL_STORAGE
        }
        return list.distinct()
    }

    fun getHotspotPermissions(): List<String> {
        val list = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            list += Manifest.permission.NEARBY_WIFI_DEVICES
            list += Manifest.permission.POST_NOTIFICATIONS
        }
        list += Manifest.permission.ACCESS_FINE_LOCATION
        return list.distinct()
    }

    fun hasAllPermissions(context: Context, permissions: List<String>): Boolean {
        return permissions.all { perm ->
            ContextCompat.checkSelfPermission(context, perm) == PackageManager.PERMISSION_GRANTED
        }
    }

    fun missingPermissions(context: Context, permissions: List<String>): List<String> {
        return permissions.filter { perm ->
            ContextCompat.checkSelfPermission(context, perm) != PackageManager.PERMISSION_GRANTED
        }
    }

    fun hasCameraPermission(context: Context): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED
    }

    fun isBluetoothEnabled(context: Context): Boolean {
        return try {
            val manager = context.applicationContext
                .getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
            manager?.adapter?.isEnabled == true
        } catch (_: Throwable) {
            false
        }
    }

    fun isWifiEnabled(context: Context): Boolean {
        return try {
            val manager = context.applicationContext
                .getSystemService(Context.WIFI_SERVICE) as? WifiManager
            manager?.isWifiEnabled == true
        } catch (_: Throwable) {
            false
        }
    }

    fun checkConnectivity(context: Context): ConnectivityStatus {
        return ConnectivityStatus(
            isBluetoothEnabled = isBluetoothEnabled(context),
            isWifiEnabled = isWifiEnabled(context)
        )
    }

    @androidx.annotation.StringRes
    fun friendlyPermissionLabelRes(permission: String): Int? {
        return when (permission) {
            Manifest.permission.BLUETOOTH_ADVERTISE -> com.example.R.string.permission_label_bt_advertise
            Manifest.permission.BLUETOOTH_CONNECT -> com.example.R.string.permission_label_bt_connect
            Manifest.permission.BLUETOOTH_SCAN -> com.example.R.string.permission_label_bt_scan
            Manifest.permission.NEARBY_WIFI_DEVICES -> com.example.R.string.permission_label_nearby_wifi
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION -> com.example.R.string.permission_label_location
            Manifest.permission.POST_NOTIFICATIONS -> com.example.R.string.permission_label_notifications
            Manifest.permission.WRITE_EXTERNAL_STORAGE -> com.example.R.string.permission_label_storage
            Manifest.permission.CAMERA -> com.example.R.string.permission_label_camera
            else -> null
        }
    }

    fun friendlyPermissionLabel(permission: String): String {
        return when (permission) {
            Manifest.permission.BLUETOOTH_ADVERTISE -> "Bluetooth (make device visible to nearby phones)"
            Manifest.permission.BLUETOOTH_CONNECT -> "Bluetooth (connect to nearby phones)"
            Manifest.permission.BLUETOOTH_SCAN -> "Bluetooth (scan for nearby phones)"
            Manifest.permission.NEARBY_WIFI_DEVICES -> "Nearby Wi-Fi devices"
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION -> "Location (required by Android for offline Wi-Fi/Bluetooth discovery)"
            Manifest.permission.POST_NOTIFICATIONS -> "Transfer progress notifications"
            Manifest.permission.WRITE_EXTERNAL_STORAGE -> "Save APK to Downloads folder"
            Manifest.permission.CAMERA -> "Camera (for scanning QR codes)"
            else -> permission.substringAfterLast('.')
        }
    }
}
