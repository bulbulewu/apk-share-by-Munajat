package com.example.transfer

import android.annotation.SuppressLint
import android.content.Context
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.annotation.StringRes
import com.example.R
import com.example.util.LocaleHelper
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

    private fun str(@StringRes id: Int, vararg args: Any): String {
        val loc = LocaleHelper.localizedContext(context)
        return if (args.isEmpty()) loc.getString(id) else loc.getString(id, *args)
    }

    private val wifiManager: WifiManager? =
        context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager

    private var reservation: WifiManager.LocalOnlyHotspotReservation? = null

    private val _hotspotInfo = MutableStateFlow(
        HotspotInfo(
            ipAddress = detectHotspotIpAddress(),
            statusMessage = str(R.string.hotspot_status_not_started)
        )
    )
    val hotspotInfo: StateFlow<HotspotInfo> = _hotspotInfo.asStateFlow()

    @SuppressLint("MissingPermission")
    fun startLocalHotspot() {
        val manager = wifiManager
        if (manager == null) {
            val msg = str(R.string.hotspot_status_wifi_unavailable)
            _hotspotInfo.value = HotspotInfo(
                isActive = false,
                ipAddress = detectHotspotIpAddress(),
                statusMessage = msg,
                errorMessage = msg
            )
            return
        }

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            _hotspotInfo.value = HotspotInfo(
                isActive = false,
                ipAddress = detectHotspotIpAddress(),
                statusMessage = str(R.string.hotspot_status_manual_older_android),
                errorMessage = null
            )
            return
        }

        val requiredPermissions = PermissionHelper.getHotspotPermissions()
        if (!PermissionHelper.hasAllPermissions(context, requiredPermissions)) {
            Log.w(TAG, "Refusing to start LocalOnlyHotspot: permissions not granted")
            val msg = str(R.string.hotspot_status_missing_perms)
            _hotspotInfo.value = HotspotInfo(
                isActive = false,
                ipAddress = detectHotspotIpAddress(),
                statusMessage = msg,
                errorMessage = msg
            )
            return
        }

        stopLocalHotspot()
        _hotspotInfo.value = HotspotInfo(
            isActive = false,
            ipAddress = detectHotspotIpAddress(),
            statusMessage = str(R.string.hotspot_status_starting)
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
                            statusMessage = str(R.string.hotspot_status_active),
                            errorMessage = null
                        )
                    }

                    override fun onStopped() {
                        super.onStopped()
                        Log.i(TAG, "LocalOnlyHotspot stopped")
                        _hotspotInfo.value = _hotspotInfo.value.copy(
                            isActive = false,
                            statusMessage = str(R.string.hotspot_status_stopped)
                        )
                    }

                    override fun onFailed(reason: Int) {
                        super.onFailed(reason)
                        val reasonText = when (reason) {
                            ERROR_NO_CHANNEL -> str(R.string.hotspot_error_no_channel)
                            ERROR_TETHERING_DISALLOWED -> str(R.string.hotspot_error_disallowed)
                            ERROR_INCOMPATIBLE_MODE -> str(R.string.hotspot_error_incompatible)
                            else -> str(R.string.hotspot_error_generic, reason)
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
                statusMessage = str(R.string.hotspot_error_security),
                errorMessage = se.message
            )
        } catch (ise: IllegalStateException) {
            Log.e(TAG, "Invalid state for startLocalOnlyHotspot", ise)
            _hotspotInfo.value = HotspotInfo(
                isActive = false,
                ipAddress = detectHotspotIpAddress(),
                statusMessage = str(R.string.hotspot_error_busy),
                errorMessage = ise.message
            )
        } catch (t: Throwable) {
            Log.e(TAG, "Error starting LocalOnlyHotspot", t)
            _hotspotInfo.value = HotspotInfo(
                isActive = false,
                ipAddress = detectHotspotIpAddress(),
                statusMessage = str(R.string.hotspot_error_manual_ready),
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
