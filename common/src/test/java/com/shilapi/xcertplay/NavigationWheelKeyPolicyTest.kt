package com.shilapi.xcertplay

import org.junit.Assert.*
import org.junit.Test

class NavigationWheelKeyPolicyTest {
    @Test fun clampsStream14LimitsAndRejectsInvalidReadbacks() {
        assertEquals(0, navigationWheelTarget(0, 0, 15, -1))
        assertEquals(15, navigationWheelTarget(15, 0, 15, 1))
        assertEquals(0, navigationWheelTarget(0, 0, 0, 1))
        assertEquals(8, navigationWheelTarget(7, 0, 15, 1))
        assertEquals(Int.MAX_VALUE, navigationWheelTarget(Int.MAX_VALUE, 0, Int.MAX_VALUE, 1))
        assertNull(navigationWheelTarget(-1, 0, 15, 1))
        assertNull(navigationWheelTarget(16, 0, 15, -1))
        assertNull(navigationWheelTarget(0, 0, -1, 1))
    }

    @Test fun handlesConfirmedDirectionsAndEachRepeatOnce() {
        for ((key, delta) in listOf(24 to 1, 291 to 1, 307 to 1, 25 to -1, 292 to -1, 308 to -1)) {
            val policy = NavigationWheelKeyPolicy()
            val steps = mutableListOf<Int>()
            for (repeat in 0..2) assertTrue(policy.onKey(0, key, 4, 100, repeat, true) { steps.add(it); true })
            assertEquals(listOf(delta, delta, delta), steps)
            assertTrue(policy.onKey(1, key, 4, 100, 0, true) { fail("UP must not adjust"); false })
            assertFalse(policy.onKey(1, key, 4, 100, 0, true) { false })
        }
    }

    @Test fun lostEligibilityKeepsPairWithoutFurtherWrites() {
        val policy = NavigationWheelKeyPolicy()
        assertTrue(policy.onKey(0, 291, 1, 10, 0, true) { true })
        assertTrue(policy.onKey(0, 291, 1, 10, 1, false) { fail("disabled repeat"); false })
        assertTrue(policy.onKey(1, 291, 1, 10, 0, false) { fail("disabled UP"); false })
        assertFalse(policy.onKey(0, 291, 1, 20, 0, false) { fail("disabled DOWN"); false })
    }

    @Test fun neverTakesOverUnconsumedPressOrUnrelatedKeys() {
        val policy = NavigationWheelKeyPolicy()
        assertFalse(policy.onKey(0, 292, 1, 10, 0, false) { true })
        assertFalse(policy.onKey(0, 292, 1, 10, 1, true) { fail("midpress capture"); false })
        assertFalse(policy.onKey(1, 292, 1, 10, 0, true) { false })
        for (key in listOf(26, 66, 85, 293, 306, 309)) {
            assertFalse(policy.onKey(0, key, 1, 30, 0, true) { fail("unrelated key"); false })
        }
    }

    @Test fun failedInitialAdjustmentPassesAndDeviceTimePairMustMatch() {
        val policy = NavigationWheelKeyPolicy()
        assertFalse(policy.onKey(0, 307, 1, 10, 0, true) { false })
        assertFalse(policy.onKey(1, 307, 1, 10, 0, true) { false })
        assertTrue(policy.onKey(0, 307, 1, 20, 0, true) { true })
        assertFalse(policy.onKey(1, 307, 2, 20, 0, true) { false })
        assertFalse(policy.onKey(1, 307, 1, 21, 0, true) { false })
        assertTrue(policy.onKey(1, 307, 1, 20, 0, false) { false })
    }

    @Test fun preApi28NeverCallsUnavailablePublicMinimum() {
        for (sdk in listOf(25, 26, 27)) {
            assertEquals(0, navigationWheelMinimum(5, sdk) { fail("unavailable API"); 9 })
            assertEquals(0, navigationWheelMinimum(14, sdk) { fail("private stream"); 9 })
        }
    }

    @Test fun respectsNonZeroMinimumAndPrivateNavigationMinimum() {
        assertEquals(2, navigationWheelTarget(2, 2, 10, -1))
        assertNull(navigationWheelTarget(1, 2, 10, 1))
        assertEquals(0, navigationWheelMinimum(14, 28) { fail("private stream must not use public validator"); 9 })
        assertEquals(2, navigationWheelMinimum(5, 28) { 2 })
    }
}
