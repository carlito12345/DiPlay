package com.shilapi.xcertplay.network

import java.io.InterruptedIOException
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress

internal data class HotspotInterfaceSnapshot(
    val name: String,
    val index: Int,
    val up: Boolean,
    val addresses: List<InetAddress>,
    val wireless: Boolean,
)

internal data class HotspotNetworkSnapshot(
    val interfaces: List<HotspotInterfaceSnapshot>,
    val apInterfaces: Set<String>?,
    val wifiUpstreams: Set<String>?,
    val defaultInterface: String?,
    val consistent: Boolean = true,
    val apEnabled: Boolean? = true,
    // carlito | Only routes to clients reported by the factory AP can supplement Android ownership.
    val vendorHostAddresses: Map<String, InetAddress> = emptyMap(),
)

internal data class HotspotSelection(val name: String, val index: Int, val address: InetAddress,
    val factoryRoute: Boolean = false) {
    fun sameAddress(other: HotspotSelection): Boolean = name == other.name && index == other.index &&
        address.address.contentEquals(other.address.address) &&
        (address as? Inet6Address)?.scopeId == (other.address as? Inet6Address)?.scopeId &&
        factoryRoute == other.factoryRoute
}

internal fun selectHotspotInterface(snapshot: HotspotNetworkSnapshot, log: (String) -> Unit): HotspotSelection? {
    if (!snapshot.consistent) {
        log("hotspot sample rejected: network_changed=${!snapshot.consistent} apEnabled=${snapshot.apEnabled}")
        return null
    }
    return snapshot.interfaces.mapNotNull { iface ->
        val owned = snapshot.apInterfaces?.contains(iface.name) == true
        val upstream = snapshot.wifiUpstreams?.contains(iface.name) == true
        val vendorAddress = snapshot.vendorHostAddresses[iface.name]?.takeIf { host ->
            iface.addresses.any { it.address.contentEquals(host.address) } &&
                (host is Inet4Address && !host.isAnyLocalAddress && !host.isLoopbackAddress &&
                    !host.isMulticastAddress && !host.isLinkLocalAddress ||
                    host is Inet6Address && host.isLinkLocalAddress && host.scopeId == iface.index)
        }
        // carlito | Match the working APK's IPv4-first policy before checking AP ownership.
        // Factory subnets such as 198.18/15 must not disappear from the candidate list.
        val address = vendorAddress ?: wirelessHostAddress(iface.addresses, iface.index)
        val reason = when {
            !iface.up || iface.index <= 0 -> "interface_down"
            address == null -> "address_unavailable"
            // carlito | A reported AP client can overlap the STA subnet; observed ownership wins.
            upstream && !owned -> "wifi_upstream"
            vendorAddress != null -> "ecarx_client_route"
            snapshot.apEnabled == false -> "android_ap_off"
            owned -> "platform_ap"
            snapshot.apInterfaces != null -> "not_platform_ap"
            upstream -> "wifi_upstream"
            snapshot.defaultInterface == iface.name -> "default_network_without_ap_evidence"
            snapshot.wifiUpstreams == null -> "upstream_unobservable"
            !iface.wireless -> "no_ap_evidence"
            else -> "wireless_non_upstream"
        }
        log("hotspot candidate iface=${iface.name} index=${iface.index} " +
            "family=${if (address is Inet6Address) "IPv6" else if (address != null) "IPv4" else "none"} " +
            // carlito | Selection filters must not conceal factory/non-private IPv4 in redacted reports.
            "availableFamilies=${iface.addresses.map { if (it is Inet4Address) "IPv4" else "IPv6" }.distinct().sorted().joinToString("+").ifEmpty { "none" }} " +
            "ipv4Classes=${iface.addresses.filterIsInstance<Inet4Address>().map(EcarxClientRoute::addressClass).distinct().sorted().joinToString("+").ifEmpty { "none" }} " +
            "scope=${(address as? Inet6Address)?.scopeId ?: 0} evidence=$reason " +
            "ap=${snapshot.apInterfaces?.let { if (owned) "yes" else "no" } ?: "unobservable"} " +
            "defaultConflict=${owned && (upstream || snapshot.defaultInterface == iface.name)}")
        val priority = when (reason) {
            "ecarx_client_route" -> 150
            "platform_ap" -> 100
            "wireless_non_upstream" -> 0
            else -> return@mapNotNull null
        }
        priority to HotspotSelection(iface.name, iface.index, address!!, reason == "ecarx_client_route")
    }.sortedWith(compareByDescending<Pair<Int, HotspotSelection>> { it.first }.thenBy { it.second.name })
        .firstOrNull()?.second
}

internal class ManualHotspotReadiness(
    private val sample: () -> HotspotNetworkSnapshot,
    private val cancelled: () -> Boolean,
    private val pause: (Long) -> Unit,
    private val nowMillis: () -> Long = { System.nanoTime() / 1_000_000 },
    private val log: (String) -> Unit = {},
) {
    fun await(timeoutMillis: Long): HotspotSelection {
        val deadline = nowMillis() + timeoutMillis
        var previous: HotspotSelection? = null
        var stable = 0
        while (true) {
            if (cancelled()) throw InterruptedIOException("Hotspot readiness cancelled")
            if (nowMillis() >= deadline) throw WirelessStartupException(
                WirelessStartupFailure.HOTSPOT_NOT_READY, "Hotspot network is not ready",
            )
            val selected = selectHotspotInterface(sample(), log)
            if (cancelled()) throw InterruptedIOException("Hotspot readiness cancelled")
            stable = if (selected != null && previous?.sameAddress(selected) == true) stable + 1 else 1
            previous = selected
            if (selected != null && stable >= WirelessStartupPolicy.STABLE_SAMPLES && nowMillis() < deadline) {
                log("hotspot interface confirmed iface=${selected.name} index=${selected.index} atMs=${nowMillis()}")
                return selected
            }
            pause(minOf(WirelessStartupPolicy.INTERFACE_POLL_MILLIS, (deadline - nowMillis()).coerceAtLeast(1)))
        }
    }
}
