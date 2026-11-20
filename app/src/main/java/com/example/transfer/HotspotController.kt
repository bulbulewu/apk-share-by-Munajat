package com.example.transfer

import android.annotation.SuppressLint
import android.content.Context
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.example.util.PermissionHelper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.net.Inet4Address
import java.net.NetworkInterface

data class HotspotInfo(
    val isActive: Boolean = false,
    val ssid: String? = null,
    val passphrase: String? = null,
    val ipAddress: String = "192.168.43.1",
    val statusMessage: String = "Not started",
    val errorMessage: String? = null
)

class HotspotController(private val context: Context) {

    private val wifiManager: WifiManager? =
        context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager

    private var reservation: WifiManager.LocalOnlyHotspotReservation? = null

    private val _hotspotInfo = MutableStateFlow(HotspotInfo(ipAddress = detectHotspotIpAddress()))
    val hotspotInfo: StateFlow<HotspotInfo> = _hotspotInfo.asStateFlow()

    @SuppressLint("MissingPermission")
    fun startLocalHotspot() {
        val manager = wifiManager
        if (manager == null) {
            _hotspotInfo.value = HotspotInfo(
                isActive = false,
                ipAddress = detectHotspotIpAddress(),
                statusMessage = "Wi-Fi hardware unavailable; turn on Portable Hotspot manually.",
                errorMessage = "Wi-Fi manager unavailable"
            )
            return
        }

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            _hotspotInfo.value = HotspotInfo(
                isActive = false,
                ipAddress = detectHotspotIpAddress(),
                statusMessage = "Turn on Portable Wi-Fi Hotspot in system Settings.",
                errorMessage = null
            )
            return
        }

        val requiredPermissions = PermissionHelper.getHotspotPermissions()
        if (!PermissionHelper.hasAllPermissions(context, requiredPermissions)) {
            Log.w(TAG, "Refusing to start LocalOnlyHotspot: permissions not granted")
            _hotspotInfo.value = HotspotInfo(
                isActive = false,
                ipAddress = detectHotspotIpAddress(),
                statusMessage = "Grant Nearby Wi-Fi and Location permissions to auto-start hotspot, or enable Portable Hotspot manually in Settings.",
                errorMessage = "Missing hotspot permissions"
            )
            return
        }

        stopLocalHotspot()
        _hotspotInfo.value = HotspotInfo(
            isActive = false,
            ipAddress = detectHotspotIpAddress(),
            statusMessage = "Starting Wi-Fi hotspot..."
        )

        try {
            manager.startLocalOnlyHotspot(
                object : WifiManager.LocalOnlyHotspotCallback() {
                    override fun onStarted(res: WifiManager.LocalOnlyHotspotReservation) {
                        super.onStarted(res)
                        reservation = res
                        val (ssid, pass) = extractCredentials(res)
                        val detectedIp = detectHotspotIpAddress()
                        Log.i(TAG, "LocalOnlyHotspot started: ssid=$ssid ip=$detectedIp")
                        _hotspotInfo.value = HotspotInfo(
                            isActive = true,
                            ssid = ssid,
                            passphrase = pass,
                            ipAddress = detectedIp,
                            statusMessage = "Hotspot active! Receiver can join Wi-Fi & scan QR.",
                            errorMessage = null
                        )
                    }

                    override fun onStopped() {
                        super.onStopped()
                        Log.i(TAG, "LocalOnlyHotspot stopped")
                        _hotspotInfo.value = _hotspotInfo.value.copy(
                            isActive = false,
                            statusMessage = "Hotspot stopped"
                        )
                    }

                    override fun onFailed(reason: Int) {
                        super.onFailed(reason)
                        val reasonText = when (reason) {
                            ERROR_NO_CHANNEL -> "No free Wi-Fi channel available."
                            ERROR_TETHERING_DISALLOWED -> "Hotspot disabled by device policy."
                            ERROR_INCOMPATIBLE_MODE -> "Turn off Wi-Fi or existing hotspot first, or enable Portable Hotspot in Settings."
                            else -> "Could not auto-start hotspot (code $reason). You can enable Portable Hotspot manually in Settings."
                        }
                        Log.w(TAG, "LocalOnlyHotspot failed: $reasonText")
                        _hotspotInfo.value = HotspotInfo(
                            isActive = false,
                            ipAddress = detectHotspotIpAddress(),
                            statusMessage = reasonText,
                            errorMessage = reasonText
                        )
                    }
                },
                Handler(Looper.getMainLooper())
            )
        } catch (se: SecurityException) {
            Log.e(TAG, "Missing permission for startLocalOnlyHotspot", se)
            _hotspotInfo.value = HotspotInfo(
                isActive = false,
                ipAddress = detectHotspotIpAddress(),
                statusMessage = "Grant Nearby Wi-Fi / Location permission or open Hotspot Settings manually.",
                errorMessage = se.message
            )
        } catch (ise: IllegalStateException) {
            Log.e(TAG, "Invalid state for startLocalOnlyHotspot", ise)
            _hotspotInfo.value = HotspotInfo(
                isActive = false,
                ipAddress = detectHotspotIpAddress(),
                statusMessage = "Hotspot is busy or unavailable right now. Turn on Portable Hotspot manually in Settings.",
                errorMessage = ise.message
            )
        } catch (t: Throwable) {
            Log.e(TAG, "Error starting LocalOnlyHotspot", t)
            _hotspotInfo.value = HotspotInfo(
                isActive = false,
                ipAddress = detectHotspotIpAddress(),
                statusMessage = "Turn on Portable Hotspot manually in Settings; server stays ready.",
                errorMessage = t.message
            )
        }
    }

    fun refreshDetectedIp() {
        _hotspotInfo.value = _hotspotInfo.value.copy(ipAddress = detectHotspotIpAddress())
    }

    fun stopLocalHotspot() {
        runCatching { reservation?.close() }
        reservation = null
        _hotspotInfo.value = _hotspotInfo.value.copy(isActive = false)
    }

    @Suppress("DEPRECATION")
    private fun extractCredentials(res: WifiManager.LocalOnlyHotspotReservation): Pair<String?, String?> {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val softAp = res.softApConfiguration
                val ssid = softAp.wifiSsid?.toString()?.removeSurrounding("\"") ?: softAp.ssid
                ssid to softAp.passphrase
            } else {
                val cfg = res.wifiConfiguration
                cfg?.SSID?.removeSurrounding("\"") to cfg?.preSharedKey
            }
        } catch (_: Throwable) {
            null to null
        }
    }

    fun detectHotspotIpAddress(): String {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
            val candidates = mutableListOf<Pair<Int, String>>()
            for (iface in interfaces) {
                if (!iface.isUp || iface.isLoopback) continue
                val name = iface.name.lowercase()
                for (addr in iface.inetAddresses.toList()) {
                    if (addr is Inet4Address && !addr.isLoopbackAddress) {
                        val ip = addr.hostAddress ?: continue
                        val priority = when {
                            ip == "192.168.43.1" -> 0
                            name.contains("ap") || name.contains("swlan") || name.contains("softap") -> 1
                            ip.startsWith("192.168.43.") || ip.startsWith("192.168.49.") -> 2
                            name.contains("wlan") -> 3
                            else -> 4
                        }
                        candidates += priority to ip
                    }
                }
            }
            return candidates.minByOrNull { it.first }?.second ?: "192.168.43.1"
        } catch (_: Throwable) {
            return "192.168.43.1"
        }
    }

    companion object {
        private const val TAG = "HotspotController"
    }
}
