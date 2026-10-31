package ru.eluno.feature.commissioning

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.wifi.SupplicantState
import android.net.wifi.WifiManager

internal sealed interface CurrentWifiNetwork {
    class Connected(val ssid: String) : CurrentWifiNetwork
    data object PermissionRequired : CurrentWifiNetwork
    data object LocationDisabled : CurrentWifiNetwork
    data object Unavailable : CurrentWifiNetwork
}

/** Only removes Android's wrapping quotes; spaces and quotes inside an SSID are significant. */
internal fun wifiNetworkName(raw: String?): String? {
    if (raw == null || raw == "<unknown ssid>") return null
    return raw.removeSurrounding("\"").takeIf { it.isNotEmpty() }
}

@Suppress("DEPRECATION")
internal fun currentWifiNetwork(context: Context): CurrentWifiNetwork {
    if (context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
        return CurrentWifiNetwork.PermissionRequired
    }
    if (context.getSystemService(LocationManager::class.java)?.isLocationEnabled != true) {
        return CurrentWifiNetwork.LocationDisabled
    }
    return try {
        // One-shot read of the primary Wi-Fi connection, including when a VPN is the default network.
        // This API remains supported on 31+; no scan or continuous location/network observation is needed.
        val info = context.applicationContext.getSystemService(WifiManager::class.java)?.connectionInfo
        if (info?.supplicantState != SupplicantState.COMPLETED) return CurrentWifiNetwork.Unavailable
        val ssid = wifiNetworkName(info.ssid) ?: return CurrentWifiNetwork.Unavailable
        CurrentWifiNetwork.Connected(ssid)
    } catch (_: SecurityException) {
        CurrentWifiNetwork.PermissionRequired
    }
}
