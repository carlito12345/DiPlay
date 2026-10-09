package com.shilapi.xcertplay

import android.Manifest
import android.os.Build
import com.shilapi.xcertplay.orchestration.WirelessHotspotMode

/**
 * Runtime permissions a wireless CarPlay start needs per Android release. Android 17 requires
 * [Manifest.permission.ACCESS_LOCAL_NETWORK] for local traffic, including the inbound AirPlay
 * listener. A ROM where link-local IPv6 happened to work without it does not establish a general
 * permission exemption for IPv6.
 */
internal object WirelessPermissions {
    fun required(hotspotMode: WirelessHotspotMode, sdkInt: Int): List<String> = when {
        hotspotMode == WirelessHotspotMode.EXISTING_WIFI ->
            if (sdkInt >= Build.VERSION_CODES.S) listOf(Manifest.permission.BLUETOOTH_CONNECT) else emptyList()
        sdkInt >= Build.VERSION_CODES.TIRAMISU -> listOf(
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.NEARBY_WIFI_DEVICES,
        )
        sdkInt >= Build.VERSION_CODES.S -> listOf(
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.ACCESS_FINE_LOCATION,
        )
        else -> listOf(
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.ACCESS_FINE_LOCATION,
        )
    }.let { permissions ->
        if (sdkInt >= Build.VERSION_CODES.CINNAMON_BUN) {
            permissions + Manifest.permission.ACCESS_LOCAL_NETWORK
        } else {
            permissions
        }
    }
}
