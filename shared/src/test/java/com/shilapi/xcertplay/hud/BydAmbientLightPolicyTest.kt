package com.shilapi.xcertplay.hud

import org.junit.Assert.*
import org.junit.Test

class BydAmbientLightPolicyTest {
    private val token = "0123456789abcdef0123456789abcdef"

    @Test fun protocolAcceptsOnlyWhitelistedCommandsAndBounds() {
        assertEquals("minimum-stop", BydAmbientLightWorkerProtocol.parse("$token minimum-stop", token)?.op)
        assertNull(BydAmbientLightWorkerProtocol.parse("$token minimum-stop 1", token))
        assertNull(BydAmbientLightWorkerProtocol.parse("$token off", token))
        assertEquals("begin", BydAmbientLightWorkerProtocol.parse("$token begin", token)?.op)
        assertEquals(listOf(1, 16, 6), BydAmbientLightWorkerProtocol.parse("$token apply 1 16 6", token)?.args)
        assertNull(BydAmbientLightWorkerProtocol.parse("$token shell id", token))
        assertNull(BydAmbientLightWorkerProtocol.parse("$token apply 0 16 6", token))
        assertNull(BydAmbientLightWorkerProtocol.parse("$token apply 1 32 6", token))
        assertNull(BydAmbientLightWorkerProtocol.parse("$token apply 1 16 0", token))
        assertNull(BydAmbientLightWorkerProtocol.parse("${token.dropLast(1)}x read", token))
        assertNull(ByDProtocolLong(token))
    }

    @Test fun onlyLightingFeaturesAreWhitelisted() {
        assertEquals(9, BydAmbientLightPolicy.getNames.size)
        assertEquals(3, BydAmbientLightPolicy.setNames.size)
        assertFalse(BydAmbientLightPolicy.setNames.any { "SOURCE" in it })
        assertEquals(15_000L, BydAmbientLightPolicy.LEASE_MILLIS)
        assertEquals(50L, BydAmbientLightPolicy.READBACK_POLL_MILLIS)
    }

    @Test fun validatesSnapshotAndRequestedRawValues() {
        assertTrue(BydAmbientLightPolicy.validSnapshot(BydAmbientLightPolicy.Snapshot(16, 16, 6, 6, 3)))
        assertFalse(BydAmbientLightPolicy.validSnapshot(BydAmbientLightPolicy.Snapshot(0, 16, 6, 6, 3)))
        assertFalse(BydAmbientLightPolicy.validSnapshot(BydAmbientLightPolicy.Snapshot(16, 16, 0, 6, 3)))
        assertFalse(BydAmbientLightPolicy.validSnapshot(BydAmbientLightPolicy.Snapshot(16, 16, 6, 6, 4)))
        assertTrue(BydAmbientLightPolicy.validApply(2, 31, 1))
        assertFalse(BydAmbientLightPolicy.validApply(3, 32, 6))
    }

    @Test fun equalZoneSnapshotCanUseKnownAllBatchAndThenAreaOnly() {
        val s = BydAmbientLightPolicy.Snapshot(16, 16, 6, 6, 2)
        assertEquals(listOf(
            BydAmbientLightPolicy.RestoreStep(values = BydAmbientLightPolicy.Values(16, 6, 3)),
            BydAmbientLightPolicy.RestoreStep(areaOnly = 2),
        ), BydAmbientLightPolicy.restoreSteps(s))
    }

    @Test fun differentZoneSnapshotRestoresFrontThenBackThenAreaOnly() {
        val s = BydAmbientLightPolicy.Snapshot(7, 23, 2, 5, 3)
        assertEquals(listOf(
            BydAmbientLightPolicy.RestoreStep(values = BydAmbientLightPolicy.Values(7, 2, 1)),
            BydAmbientLightPolicy.RestoreStep(values = BydAmbientLightPolicy.Values(23, 5, 2)),
            BydAmbientLightPolicy.RestoreStep(areaOnly = 3),
        ), BydAmbientLightPolicy.restoreSteps(s))
    }

    @Test fun minimumKeepsCurrentColorsAndAreaInsteadOfRestoringSavedBrightness() {
        val current = BydAmbientLightPolicy.Snapshot(7, 23, 2, 5, 2)
        val target = BydAmbientLightPolicy.Snapshot(7, 23, 1, 1, 2)
        assertEquals(target, BydAmbientLightPolicy.minimumTarget(current))
        assertEquals(listOf(
            BydAmbientLightPolicy.RestoreStep(values = BydAmbientLightPolicy.Values(7, 1, 1)),
            BydAmbientLightPolicy.RestoreStep(values = BydAmbientLightPolicy.Values(23, 1, 2)),
            BydAmbientLightPolicy.RestoreStep(areaOnly = 2),
        ), BydAmbientLightPolicy.minimumSteps(current))
        assertEquals(BydAmbientLightPolicy.Snapshot(7, 23, 2, 5, 2), current)
    }

    @Test fun equalCurrentColorsStillUseTwoMinimumZoneWritesAndReturnToTheArea() {
        val current = BydAmbientLightPolicy.Snapshot(16, 16, 6, 6, 3)
        assertEquals(listOf(
            BydAmbientLightPolicy.RestoreStep(values = BydAmbientLightPolicy.Values(16, 1, 1)),
            BydAmbientLightPolicy.RestoreStep(values = BydAmbientLightPolicy.Values(16, 1, 2)),
            BydAmbientLightPolicy.RestoreStep(areaOnly = 3),
        ), BydAmbientLightPolicy.minimumSteps(current))
        val target = BydAmbientLightPolicy.minimumTarget(current)
        assertEquals(target, BydAmbientLightPolicy.minimumTarget(target))
        assertNotEquals(BydAmbientLightPolicy.restoreSteps(current), BydAmbientLightPolicy.minimumSteps(current))
    }

    @Test(expected = IllegalArgumentException::class)
    fun minimumCannotBypassSnapshotValidationWithUnsupportedZeroColor() {
        BydAmbientLightPolicy.minimumSteps(BydAmbientLightPolicy.Snapshot(0, 16, 6, 6, 3))
    }

    @Test fun seededBeginDoesNotRecaptureCurrentAppColorsAndOldBeginRemainsCompatible() {
        val seed = BydAmbientLightPolicy.Snapshot(7, 23, 2, 5, 2)
        val request = requireNotNull(BydAmbientLightWorkerProtocol.parse("$token begin 7 23 2 5 2", token))
        assertEquals(seed, BydAmbientLightWorkerProtocol.snapshotForBegin(request) { error("Must not read app-controlled current colors") })
        val old = requireNotNull(BydAmbientLightWorkerProtocol.parse("$token begin", token))
        assertEquals(seed, BydAmbientLightWorkerProtocol.snapshotForBegin(old) { seed })
        assertNull(BydAmbientLightWorkerProtocol.parse("$token begin 7 23 0 5 2", token))
        assertNull(BydAmbientLightWorkerProtocol.parse("$token begin 7 23 2 5 4", token))
        assertNull(BydAmbientLightWorkerProtocol.parse("$token begin 7 23 2 5", token))
        assertNull(BydAmbientLightWorkerProtocol.parse("${token.dropLast(1)}x begin 7 23 2 5 2", token))
    }

    private fun ByDProtocolLong(token: String) = BydAmbientLightWorkerProtocol.parse("$token ${"x".repeat(600)}", token)
}
