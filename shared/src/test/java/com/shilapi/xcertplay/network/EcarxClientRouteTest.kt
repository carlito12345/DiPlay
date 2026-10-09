// carlito | Cross-subnet AP support must require a current client's specific kernel path.
package com.shilapi.xcertplay.network

import org.junit.Assert.*
import org.junit.Test
import java.net.Inet4Address
import java.net.InetAddress

class EcarxClientRouteTest {
    private val peer = ip("192.168.15.3")
    private val source = ip("198.18.0.2")
    private fun ip(text: String) = InetAddress.getByName(text) as Inet4Address
    private fun route(text: String) = EcarxClientRoute.parseIpv4Route(text)!!
    private val resolved get() = route("192.168.15.3 via 198.18.0.1 dev eth0.11 table 100 src 198.18.0.2 uid 10000")
    private val specific get() = route("192.168.15.0/24 via 198.18.0.1 dev eth0.11 table 100 proto static")
    private fun decision(get: EcarxIpv4Route? = resolved, show: List<EcarxIpv4Route> = listOf(specific)) =
        EcarxClientRoute.routedDecision(peer, source, "eth0.11", 24, get, show)

    @Test fun provenKx11GatewayRouteMayCrossTheClientSubnet() {
        assertTrue(EcarxClientRoute.kx11Source(28, "KX11", "kx11_high", "eth0.11", source))
        assertEquals("verified_factory_gateway", decision())
        assertTrue(EcarxClientRoute.kx11Source(28, "KX11", "kx11_high", "eth0", ip("198.19.1.2")))
    }

    @Test fun sourceRangeAloneOrOtherModelsNeverAuthorizeEthernet() {
        for ((sdk, model, device, name, address) in listOf(
            listOf("29", "KX11", "kx11_high", "eth0", "198.18.0.2"),
            listOf("28", "L6", "l6", "eth0", "198.18.0.2"),
            listOf("28", "KX11", "other", "eth0", "198.18.0.2"),
            listOf("28", "KX11", "kx11_high", "rmnet0", "198.18.0.2"),
            listOf("28", "KX11", "kx11_high", "eth0", "192.168.15.1"),
            listOf("28", "KX11", "kx11_high", "eth0", "198.20.0.2"),
            listOf("28", "KX11", "kx11_high", "eth0;id", "198.18.0.2"))) {
            assertFalse(EcarxClientRoute.kx11Source(sdk.toInt(), model, device, name, ip(address)))
        }
        assertEquals("route_get_unreadable", decision(get = null))
        assertEquals("no_specific_client_route", decision(show = emptyList()))
    }

    @Test fun defaultRouteDoesNotBecomeEvidenceForTheFactoryClient() {
        assertNull(EcarxClientRoute.parseIpv4Route("default via 198.18.0.1 dev eth0.11 table 100"))
        assertNull(EcarxClientRoute.parseIpv4Route("0.0.0.0/0 via 198.18.0.1 dev eth0.11 table 100"))
        assertEquals("no_specific_client_route", decision(show = listOf(route("192.168.16.0/24 via 198.18.0.1 dev eth0.11 table 100"))))
    }

    @Test fun kernelSourceDestinationInterfaceAndGatewayMustAgree() {
        assertEquals("route_source_or_interface_mismatch", decision(get = resolved.copy(source = ip("198.18.0.3"))))
        assertEquals("route_source_or_interface_mismatch", decision(get = resolved.copy(interfaceName = "rmnet0")))
        assertEquals("route_destination_mismatch", decision(get = resolved.copy(destination = ip("192.168.15.4"))))
        assertEquals("cross_subnet_gateway_missing", decision(get = resolved.copy(gateway = null)))
        assertEquals("gateway_not_on_source_link", decision(get = resolved.copy(gateway = ip("192.168.15.1"))))
        assertEquals("specific_route_mismatch", decision(show = listOf(specific.copy(gateway = ip("198.18.0.9")))))
        assertEquals("specific_route_mismatch", decision(show = listOf(specific.copy(source = ip("198.18.0.9")))))
    }

    @Test fun policyTablesCannotBorrowEvidenceFromAnotherTable() {
        assertEquals("no_specific_client_route", decision(show = listOf(specific.copy(table = "200"))))
        val mainGet = route("192.168.15.3 via 198.18.0.1 dev eth0.11 src 198.18.0.2")
        val mainRoute = route("192.168.15.0/24 via 198.18.0.1 dev eth0.11 table 254")
        assertEquals("verified_factory_gateway", decision(mainGet, listOf(mainRoute)))
    }

    @Test fun conflictingLongestRoutesAreRejectedRatherThanChoosingFirst() {
        assertEquals("ambiguous_specific_client_routes", decision(show = listOf(specific, specific.copy(interfaceName = "eth0.3"))))
        assertEquals("specific_route_mismatch", decision(show = listOf(specific,
            route("192.168.15.3/32 via 198.18.0.1 dev eth0.3 table 100"))))
        assertEquals("verified_factory_gateway", decision(show = listOf(specific,
            route("192.168.0.0/16 via 198.18.0.9 dev eth0.3 table 100"))))
    }

    @Test fun malformedMultipathHostnameAndUnsafeInterfaceOutputIsRejected() {
        for (line in listOf("192.168.15.3 dev eth0.11 src invalid", "192.168.15.3 dev eth0.11 via router.local",
            "192.168.15.3 dev eth0.11 dev eth0.3", "192.168.15.0/24 nexthop via 198.18.0.1 dev eth0.11",
            "192.168.15.3 dev eth0;id src 198.18.0.2", "unreachable 192.168.15.0/24", "cache")) {
            assertNull(line, EcarxClientRoute.parseIpv4Route(line))
        }
        for (name in listOf("rmnet_data0", "ccmni0", "tun0", "tap0", "dummy0", "veth123", "sit0")) {
            assertTrue(name, EcarxClientRoute.unsafeInterface(name))
        }
        assertFalse(EcarxClientRoute.unsafeInterface("eth0.11"))
        assertFalse(EcarxClientRoute.unsafeInterface("wlan0"))
    }

    @Test fun addressClassificationSurvivesReportAddressRedaction() {
        assertEquals("benchmark_198_18_15", EcarxClientRoute.addressClass(source))
        assertEquals("private_ipv4", EcarxClientRoute.addressClass(peer))
        assertEquals("unspecified", EcarxClientRoute.addressClass(ip("0.0.0.0")))
        assertEquals("loopback", EcarxClientRoute.addressClass(ip("127.0.0.1")))
        assertEquals("other_ipv4", EcarxClientRoute.addressClass(ip("198.20.0.2")))
        assertEquals("ipv6_link_local", EcarxClientRoute.addressClass(InetAddress.getByName("fe80::1234")))
    }

    @Test fun onlyMultipathInTheResolvedTableCoveringThisClientBlocksThePath() {
        val hops = listOf("  nexthop via 198.18.0.1 dev eth0.11 weight 1",
            "  nexthop via 198.18.0.9 dev eth0.3 weight 1")
        assertTrue(EcarxClientRoute.matchingMultipathRoute(listOf("192.168.15.0/24 table 100") + hops, peer, "100"))
        assertTrue(EcarxClientRoute.matchingMultipathRoute(
            listOf("192.168.15.0/24 table 100 nexthop via 198.18.0.1 dev eth0.11 weight 1"), peer, "100"))
        assertFalse(EcarxClientRoute.matchingMultipathRoute(listOf("192.168.15.0/24 table 200") + hops, peer, "100"))
        assertFalse(EcarxClientRoute.matchingMultipathRoute(listOf("192.168.16.0/24 table 100") + hops, peer, "100"))
        assertFalse(EcarxClientRoute.matchingMultipathRoute(
            listOf("192.168.16.0/24 table 100") + hops + "192.168.15.0/24 via 198.18.0.1 dev eth0.11 table 100", peer, "100"))
        assertTrue(EcarxClientRoute.matchingMultipathRoute(listOf("192.168.15.0/24 table 254") + hops, peer, "main"))
    }
}
