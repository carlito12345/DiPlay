package com.shilapi.xcertplay.network

import java.net.InetAddress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HotspotAddressPolicyTest {
    private fun address(text: String): InetAddress = InetAddress.getByName(text)

    @Test
    fun theReachableIpv4AddressWinsOverTheLinkLocalOneTheFrameworkListsFirst() {
        val linkLocal = address("fe80::e0dc:ffff:fe5b:c6d1")
        val ipv4 = address("192.168.49.1")

        assertEquals(ipv4, HotspotAddressPolicy.select(listOf(linkLocal, ipv4)))
    }

    @Test
    fun addressesAnIphoneCannotReachAreIgnored() {
        val ipv4 = address("192.168.49.1")

        assertEquals(
            ipv4,
            HotspotAddressPolicy.select(
                listOf(address("127.0.0.1"), address("0.0.0.0"), address("169.254.11.4"), ipv4),
            ),
        )
    }

    @Test
    fun linkLocalIpv6IsStillBetterThanNothingOnAFrameworkWithoutIpv4() {
        val linkLocal = address("fe80::e0dc:ffff:fe5b:c6d1")

        assertEquals(linkLocal, HotspotAddressPolicy.select(listOf(linkLocal)))
    }

    @Test
    fun theFirstIpv4AddressIsKeptWhenTheInterfaceHasSeveral() {
        val first = address("192.168.49.1")
        val second = address("192.168.49.2")

        assertEquals(first, HotspotAddressPolicy.select(listOf(first, second)))
    }

    @Test
    fun anEmptyInterfaceHasNoAddress() {
        assertNull(HotspotAddressPolicy.select(emptyList()))
    }

    @Test
    fun preferredIpv6PromotesTheLinkLocalAddressOverUsableIpv4() {
        val ipv4 = address("192.168.49.1")
        val linkLocal = address("fe80::e0dc:ffff:fe5b:c6d1")

        assertEquals(
            linkLocal,
            HotspotAddressPolicy.select(listOf(ipv4, linkLocal), HotspotAddressPolicy.FAMILY_IPV6),
        )
    }

    @Test
    fun preferredIpv6FallsBackToTheBaseOrderWhenTheInterfaceHasNoLinkLocal() {
        val ipv4 = address("192.168.49.1")

        assertEquals(
            ipv4,
            HotspotAddressPolicy.select(listOf(ipv4), HotspotAddressPolicy.FAMILY_IPV6),
        )
    }

    @Test
    fun preferredIpv4AndUnknownPreferencesKeepTheBaseOrder() {
        val linkLocal = address("fe80::e0dc:ffff:fe5b:c6d1")
        val ipv4 = address("192.168.49.1")

        assertEquals(
            ipv4,
            HotspotAddressPolicy.select(listOf(linkLocal, ipv4), HotspotAddressPolicy.FAMILY_IPV4),
        )
        assertEquals(ipv4, HotspotAddressPolicy.select(listOf(linkLocal, ipv4), "IPv9"))
        assertEquals(ipv4, HotspotAddressPolicy.select(listOf(linkLocal, ipv4), null))
    }

    @Test
    fun familyParsingAcceptsOnlyTheTwoPublishedFamilies() {
        assertEquals(HotspotAddressPolicy.FAMILY_IPV6, HotspotAddressPolicy.parseFamily("IPv6"))
        assertEquals(HotspotAddressPolicy.FAMILY_IPV4, HotspotAddressPolicy.parseFamily("IPv4"))
        assertNull(HotspotAddressPolicy.parseFamily("ipv6"))
        assertNull(HotspotAddressPolicy.parseFamily(null))
        assertEquals(
            HotspotAddressPolicy.FAMILY_IPV4,
            HotspotAddressPolicy.flipFamily(HotspotAddressPolicy.FAMILY_IPV6),
        )
        assertEquals(
            HotspotAddressPolicy.FAMILY_IPV6,
            HotspotAddressPolicy.flipFamily(HotspotAddressPolicy.FAMILY_IPV4),
        )
    }

    @Test
    fun theAlternateCandidateIsTheFirstUsableAddressOfTheOtherFamily() {
        val ipv4 = address("192.168.49.1")
        val secondIpv4 = address("192.168.49.9")
        val linkLocal = address("fe80::e0dc:ffff:fe5b:c6d1")

        assertEquals(
            linkLocal,
            HotspotAddressPolicy.alternateFamilyCandidate(listOf(ipv4, secondIpv4, linkLocal), ipv4),
        )
        assertEquals(
            ipv4,
            HotspotAddressPolicy.alternateFamilyCandidate(listOf(linkLocal, ipv4), linkLocal),
        )
    }

    @Test
    fun noAlternateCandidateWhenTheInterfaceHasOneFamilyOnly() {
        val ipv4 = address("192.168.49.1")

        assertNull(HotspotAddressPolicy.alternateFamilyCandidate(listOf(ipv4), ipv4))
        val linkLocal = address("fe80::e0dc:ffff:fe5b:c6d1")

        assertNull(HotspotAddressPolicy.alternateFamilyCandidate(listOf(linkLocal), linkLocal))
    }
}
