// carlito | Numeric client and neighbor evidence must not turn unrelated networks into an AP.
package com.shilapi.xcertplay.network

import org.junit.Assert.*
import org.junit.Test
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress

class EcarxHotspotReaderTest {
    private val mac = "2a:11:22:33:44:55"

    @Test fun ordinaryClientIpv4ParsesWithoutDnsOrEscapingErrors() {
        assertEquals("192.168.15.7", EcarxHotspotReader.numericAddress(" 192.168.15.7 ")!!.hostAddress)
        assertTrue(EcarxHotspotReader.numericAddress("192.168.15.7") is Inet4Address)
        assertTrue(EcarxHotspotReader.numericAddress("fe80::1234") is Inet6Address)
    }

    @Test fun malformedOrHostnameClientsAreRejected() {
        for (value in listOf(null, "", "iphone.local", "256.168.15.7", "192.168.15", "1.2.3.4.5",
            "http://192.168.15.7", "192\\168\\15\\7", "fe80::1234%eth0;id")) {
            assertNull(value, EcarxHotspotReader.numericAddress(value))
        }
    }

    @Test fun subnetMatchingRejectsDefaultsAndDifferentSubnets() {
        val first = InetAddress.getByName("192.168.15.1").address
        val same = InetAddress.getByName("192.168.15.7").address
        val other = InetAddress.getByName("192.168.16.7").address
        assertTrue(EcarxHotspotReader.sameSubnet(first, same, 24))
        assertFalse(EcarxHotspotReader.sameSubnet(first, other, 24))
        for (prefix in listOf(-1, 0, 33)) assertFalse(EcarxHotspotReader.sameSubnet(first, same, prefix))
        assertFalse(EcarxHotspotReader.sameSubnet(first, InetAddress.getByName("fe80::1234").address, 24))
    }

    @Test fun clientMacIsNormalizedButPlaceholdersAndMulticastAreRejected() {
        assertEquals(mac, EcarxHotspotReader.normalizeMac(" 2A:11:22:33:44:55 "))
        for (value in listOf(null, "", "02:00:00:00:00:00", "00:00:00:00:00:00",
            "ff:ff:ff:ff:ff:ff", "2a-11-22-33-44-55", "2a:11:22:33:44")) {
            assertNull(EcarxHotspotReader.normalizeMac(value))
        }
    }

    @Test fun currentFactoryClientCanIdentifyAnIpv6InterfaceWithoutIpv4() {
        for (state in listOf("REACHABLE", "STALE", "DELAY", "PROBE", "PERMANENT")) {
            assertEquals("fe80::1234" to "eth0.11", EcarxHotspotReader.neighborEntry(
                "fe80::1234 dev eth0.11 lladdr $mac $state", setOf(mac)))
        }
    }

    @Test fun unrelatedFailedOrIncompleteNeighborsCannotIdentifyTheAp() {
        for (line in listOf("fe80::1234 dev eth0.11 lladdr $mac FAILED",
            "fe80::1234 dev eth0.11 INCOMPLETE", "fe80::1234 dev eth0.11 lladdr 12:34:56:78:ab:cd STALE",
            "2001:db8::1 dev eth0.11 lladdr $mac REACHABLE", "192.168.15.7 dev eth0.11 lladdr $mac REACHABLE",
            "fe80::1234 lladdr $mac STALE", "fe80::1234 dev eth0;id lladdr $mac STALE")) {
            assertNull(line, EcarxHotspotReader.neighborEntry(line, setOf(mac)))
        }
        assertNull(EcarxHotspotReader.neighborEntry("fe80::1234 dev eth0.11 lladdr $mac STALE", emptySet()))
    }
}
