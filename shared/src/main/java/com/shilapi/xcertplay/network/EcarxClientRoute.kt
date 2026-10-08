// carlito | Validate a current factory client's route; never infer an AP from an idle Ethernet address.
package com.shilapi.xcertplay.network

import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress

internal data class EcarxIpv4Route(
    val destination: Inet4Address,
    val prefix: Int,
    val interfaceName: String,
    val source: Inet4Address?,
    val gateway: Inet4Address?,
    val table: String,
)

internal object EcarxClientRoute {
    fun addressClass(address: InetAddress?): String = when {
        address == null -> "unknown"
        address.isAnyLocalAddress -> "unspecified"
        address.isLoopbackAddress -> "loopback"
        address.isMulticastAddress -> "multicast"
        address is Inet4Address && benchmarkSource(address) -> "benchmark_198_18_15"
        address is Inet4Address && address.isLinkLocalAddress -> "ipv4_link_local"
        address is Inet4Address && address.isSiteLocalAddress -> "private_ipv4"
        address is Inet4Address -> "other_ipv4"
        address is Inet6Address && address.isLinkLocalAddress -> "ipv6_link_local"
        else -> "other_ipv6"
    }

    fun unsafeInterface(name: String): Boolean =
        Regex("^(rmnet|ccmni|pdp|wwan|tun|tap|dummy|veth|sit).*", RegexOption.IGNORE_CASE).containsMatchIn(name)

    fun kx11Source(sdk: Int, model: String, device: String, name: String, source: InetAddress): Boolean =
        sdk == 28 && model.equals("KX11", true) && device.startsWith("kx11", true) &&
            Regex("eth[0-9]+(\\.[0-9]+)?", RegexOption.IGNORE_CASE).matches(name) && benchmarkSource(source)

    // Route-get and route-show use the same table identity; aliases must not hide a mismatch.
    private fun table(value: String): String = when (value) {
        "254" -> "main"
        "255" -> "local"
        "253" -> "default"
        else -> value
    }

    private fun destination(text: String): Pair<Inet4Address, Int>? {
        val parts = text.split('/')
        if (parts.size !in 1..2) return null
        val address = EcarxHotspotReader.numericAddress(parts[0]) as? Inet4Address ?: return null
        val prefix = if (parts.size == 1) 32 else parts[1].toIntOrNull() ?: return null
        return if (prefix in 1..32) address to prefix else null
    }

    // carlito | Route-show emits a header followed by indented nexthops. An unrelated policy
    // table or destination must not disable this client's otherwise unambiguous factory route.
    fun matchingMultipathRoute(lines: List<String>, peer: Inet4Address, resolvedTable: String): Boolean {
        var matchingHeader = false
        for (line in lines) {
            val fields = line.trim().split(Regex("\\s+"))
            if (fields.firstOrNull() == "nexthop") {
                if (matchingHeader) return true
                continue
            }
            val target = fields.firstOrNull()?.let(::destination)
            val tableIndex = fields.indexOf("table")
            val rowTable = if (tableIndex < 0) "main" else fields.getOrNull(tableIndex + 1)
            matchingHeader = target != null && rowTable != null && table(rowTable) == table(resolvedTable) &&
                EcarxHotspotReader.sameSubnet(target.first.address, peer.address, target.second)
            if (matchingHeader && "nexthop" in fields) return true
        }
        return false
    }

    fun parseIpv4Route(line: String): EcarxIpv4Route? {
        val fields = line.trim().split(Regex("\\s+"))
        if (fields.isEmpty() || "nexthop" in fields || fields.first() == "default") return null
        val (destination, prefix) = destination(fields.first()) ?: return null
        fun field(key: String): String? {
            val positions = fields.indices.filter { fields[it] == key }
            return if (positions.size == 1) fields.getOrNull(positions.single() + 1) else null
        }
        if (listOf("dev", "src", "via", "table").any { key -> fields.count { it == key } > 1 }) return null
        val name = field("dev")?.takeIf { it.matches(Regex("[a-zA-Z0-9_.-]+")) } ?: return null
        val source = field("src")?.let { EcarxHotspotReader.numericAddress(it) as? Inet4Address }
        val gateway = field("via")?.let { EcarxHotspotReader.numericAddress(it) as? Inet4Address }
        if ("src" in fields && source == null || "via" in fields && gateway == null) return null
        val routeTable = if ("table" in fields) {
            field("table")?.takeIf { it.matches(Regex("[a-zA-Z0-9_.-]+")) } ?: return null
        } else "main"
        return EcarxIpv4Route(destination, prefix, name, source, gateway, table(routeTable))
    }

    // A specific destination route is required: a cellular/default route is not AP evidence.
    fun routedDecision(peer: Inet4Address, source: Inet4Address, name: String, sourcePrefix: Int,
                       route: EcarxIpv4Route?, routes: List<EcarxIpv4Route>): String {
        if (route == null) return "route_get_unreadable"
        if (route.prefix != 32 || route.destination != peer) return "route_destination_mismatch"
        if (route.interfaceName != name || route.source != source) return "route_source_or_interface_mismatch"
        val gateway = route.gateway ?: return "cross_subnet_gateway_missing"
        if (gateway.isAnyLocalAddress || gateway.isLoopbackAddress || gateway.isMulticastAddress ||
            !EcarxHotspotReader.sameSubnet(source.address, gateway.address, sourcePrefix)) return "gateway_not_on_source_link"
        val matches = routes.filter {
            it.table == route.table && EcarxHotspotReader.sameSubnet(it.destination.address, peer.address, it.prefix)
        }
        val longest = matches.maxOfOrNull { it.prefix } ?: return "no_specific_client_route"
        val selected = matches.filter { it.prefix == longest }.distinct()
        if (selected.size != 1) return "ambiguous_specific_client_routes"
        val specific = selected.single()
        if (specific.interfaceName != name || specific.gateway != gateway ||
            specific.source != null && specific.source != source) return "specific_route_mismatch"
        return "verified_factory_gateway"
    }

    private fun benchmarkSource(address: InetAddress): Boolean = address is Inet4Address &&
        (address.address[0].toInt() and 255) == 198 && (address.address[1].toInt() and 254) == 18
}
