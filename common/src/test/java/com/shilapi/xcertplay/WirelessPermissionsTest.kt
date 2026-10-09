package com.shilapi.xcertplay

import android.Manifest
import com.shilapi.xcertplay.orchestration.WirelessHotspotMode
import org.junit.Assert.assertEquals
import org.junit.Test

class WirelessPermissionsTest {
    @Test fun hotspotModesRequestLocalNetworkOnlyOnAndroid17() {
        assertEquals(
            listOf(
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.NEARBY_WIFI_DEVICES,
                Manifest.permission.ACCESS_LOCAL_NETWORK,
            ),
            WirelessPermissions.required(WirelessHotspotMode.MANUAL, 37),
        )
        assertEquals(
            listOf(
                Manifest.permission.ACCESS_COARSE_LOCATION,
                Manifest.permission.ACCESS_FINE_LOCATION,
            ),
            WirelessPermissions.required(WirelessHotspotMode.MANUAL, 29),
        )
        assertEquals(
            listOf(
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.ACCESS_COARSE_LOCATION,
                Manifest.permission.ACCESS_FINE_LOCATION,
            ),
            WirelessPermissions.required(WirelessHotspotMode.MANUAL, 31),
        )
    }

    @Test fun existingWifiRequestsLocalNetworkOnlyOnAndroid17() {
        assertEquals(
            listOf(
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.ACCESS_LOCAL_NETWORK,
            ),
            WirelessPermissions.required(WirelessHotspotMode.EXISTING_WIFI, 37),
        )
        assertEquals(emptyList<String>(), WirelessPermissions.required(WirelessHotspotMode.EXISTING_WIFI, 29))
    }
}
