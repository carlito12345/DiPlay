package com.shilapi.xcertplay.network

import java.io.InterruptedIOException
import java.net.Inet6Address
import java.net.InetAddress
import org.junit.Assert.*
import org.junit.Test

class HotspotAddressRecoveryTest {
    private val ipv4 = InetAddress.getByName("192.168.49.1")
    private val unscoped = InetAddress.getByName("fe80::1234") as Inet6Address
    private val ipv6 = Inet6Address.getByAddress(null, unscoped.address, 7)
    private var now = 0L
    private var generation = 1
    private var live = listOf(ipv4)
    private var polls = 0

    private fun recovery(
        owner: Int = generation,
        afterPause: () -> Unit = {},
        afterSample: () -> Unit = {},
    ) = HotspotAddressRecovery(
        sample = { polls++; live.also { afterSample() } },
        cancelled = { owner != generation },
        pause = { millis -> now += millis; afterPause() },
        nowMillis = { now },
    )

    @Test fun delayedIpv6AtTimeoutIsPublishedAfterTheNextGroupAlsoDelaysIpv6() {
        var preference: String? = null
        val first = recovery()
        val initial = first.awaitCandidates(live, preference)
        val published = HotspotAddressPolicy.select(initial, preference)!!
        assertEquals(ipv4, published)
        assertEquals(0, polls)

        // Issue #464: framework group creation initially has IPv4 only; scoped IPv6 appears later.
        live = listOf(ipv4, ipv6)
        preference = first.nextFamily(published)
        assertEquals(HotspotAddressPolicy.FAMILY_IPV6, preference)

        generation++
        live = listOf(ipv4)
        val next = recovery(afterPause = { if (now >= 10_000) live = listOf(ipv4, ipv6) })
        val candidates = next.awaitCandidates(live, preference)
        assertEquals(10_000L, now)
        assertEquals(ipv6, HotspotAddressPolicy.select(candidates, preference))
        assertEquals(HotspotAddressPolicy.FAMILY_IPV6, preference)
    }

    @Test fun defaultAndIpv4PreferenceDoNotDelaySingleFamilyStartup() {
        for (preference in listOf(null, HotspotAddressPolicy.FAMILY_IPV4)) {
            assertEquals(live, recovery().awaitCandidates(live, preference))
        }
        assertEquals(0L, now)
        assertEquals(0, polls)
    }

    @Test fun missingPreferredIpv6HasABoundedWaitAndKeepsTheIpv4Fallback() {
        val candidates = recovery().awaitCandidates(live, HotspotAddressPolicy.FAMILY_IPV6)
        assertEquals(WirelessStartupPolicy.HOTSPOT_READY_MILLIS, now)
        assertEquals(ipv4, HotspotAddressPolicy.select(candidates, HotspotAddressPolicy.FAMILY_IPV6))
        assertNull(recovery().nextFamily(ipv4))
    }

    @Test fun unscopedIpv6DoesNotEndTheWaitOrBecomeAnAlternate() {
        live = listOf(ipv4, unscoped)
        val next = recovery(afterPause = { if (now >= 500) live = listOf(ipv4, unscoped, ipv6) })
        assertNull(next.nextFamily(ipv4))
        val candidates = next.awaitCandidates(live, HotspotAddressPolicy.FAMILY_IPV6)
        assertEquals(500L, now)
        assertEquals(ipv6, HotspotAddressPolicy.select(candidates, HotspotAddressPolicy.FAMILY_IPV6))
    }

    @Test fun disappearingAlternateIsNotRecoveredFromAnOldSnapshot() {
        live = listOf(ipv4, ipv6)
        val current = recovery()
        current.awaitCandidates(live, null)
        live = emptyList()
        assertNull(current.nextFamily(ipv4))
    }

    @Test fun aNewGenerationCancelsPreferredAddressPolling() {
        val current = recovery(afterPause = { generation++ })
        assertThrows(InterruptedIOException::class.java) {
            current.awaitCandidates(live, HotspotAddressPolicy.FAMILY_IPV6)
        }
        assertEquals(250L, now)
        assertEquals(0, polls)
        assertNull(current.nextFamily(ipv4))
    }

    @Test fun teardownDuringTheLiveTimeoutScanCannotChangeThePreference() {
        live = listOf(ipv4, ipv6)
        val current = recovery(afterSample = { generation++ })
        var preference: String? = null
        current.nextFamily(ipv4)?.let { preference = it }
        assertNull(preference)
    }

    @Test fun teardownDuringThePreferredAddressScanCannotPublishAnAddress() {
        val current = recovery(
            afterPause = { live = listOf(ipv4, ipv6) },
            afterSample = { generation++ },
        )
        assertThrows(InterruptedIOException::class.java) {
            current.awaitCandidates(live, HotspotAddressPolicy.FAMILY_IPV6)
        }
    }

    @Test fun ipv6StartupCanFlipBackWhenLiveIpv4IsAvailable() {
        live = listOf(ipv4, ipv6)
        val current = recovery()
        assertEquals(HotspotAddressPolicy.FAMILY_IPV4, current.nextFamily(ipv6))
        assertEquals(live, current.awaitCandidates(live, HotspotAddressPolicy.FAMILY_IPV6))
        assertEquals(0L, now)
    }
}
