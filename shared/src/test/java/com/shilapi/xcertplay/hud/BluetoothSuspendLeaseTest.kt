package com.shilapi.xcertplay.hud

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

class BluetoothSuspendLeaseTest {
    private class Fixture : BluetoothRestoreJournal {
        @Volatile var radio: Boolean? = true
        @Volatile var recorded = false
        var commitAllowed = true
        var clearAllowed = true
        var commandAllowed = true
        var readFails = false
        var afterPersist: (() -> Unit)? = null
        var beforeCommand: ((Boolean) -> Unit)? = null
        val commands = mutableListOf<Boolean>()
        val events = mutableListOf<String>()
        override fun pending(): Boolean {
            if (readFails) throw IllegalStateException("journal unavailable")
            return recorded
        }
        override fun write(pending: Boolean): Boolean {
            events += "journal=$pending"
            if ((pending && !commitAllowed) || (!pending && !clearAllowed)) return false
            recorded = pending
            if (pending) afterPersist?.invoke()
            return true
        }
        fun lease(): BluetoothSuspendLease = BluetoothSuspendLease(this, { radio }, { enabled ->
            events += "radio=$enabled"
            commands += enabled
            beforeCommand?.invoke(enabled)
            if (commandAllowed) radio = enabled
        }, { enabled -> radio == enabled })
    }

    @Test fun cancellationBeforeDelayMakesOldWorkInert() {
        val f = Fixture(); val lease = f.lease(); val owner = Any()
        val ticket = lease.begin(owner)!!
        val release = lease.end(owner)!!
        assertFalse(lease.suspend(ticket))
        assertTrue(lease.restore(release))
        assertTrue(f.commands.isEmpty())
        assertFalse(f.recorded)
    }

    @Test fun repeatedSessionActiveDoesNotRestartItsDelay() {
        val lease = Fixture().lease(); val owner = Any()
        assertNotNull(lease.begin(owner))
        assertNull(lease.begin(owner))
    }

    @Test fun previouslyDisabledRadioIsNeverOwnedOrEnabled() {
        val f = Fixture().apply { radio = false }; val lease = f.lease(); val owner = Any()
        assertFalse(lease.suspend(lease.begin(owner)!!))
        assertTrue(lease.restore(lease.end(owner)!!))
        assertTrue(lease.restore(lease.beforeHandshake()))
        assertTrue(f.commands.isEmpty())
        assertFalse(f.recorded)
        assertFalse(lease.isSuspended())
    }

    @Test fun unknownInitialStateFailsClosed() {
        val f = Fixture().apply { radio = null }; val lease = f.lease()
        assertFalse(lease.suspend(lease.begin(Any())!!))
        assertTrue(f.commands.isEmpty())
        assertFalse(f.recorded)
    }

    @Test fun durableOriginalStateIsRecordedBeforeDisable() {
        val f = Fixture(); val lease = f.lease()
        f.beforeCommand = { enabled -> if (!enabled) assertTrue(f.recorded) }
        assertTrue(lease.suspend(lease.begin(Any())!!))
        assertEquals(listOf("journal=true", "radio=false"), f.events)
        assertTrue(lease.isSuspended())
    }

    @Test fun failedJournalCommitPreventsAnyRadioWrite() {
        val f = Fixture().apply { commitAllowed = false }; val lease = f.lease()
        assertFalse(lease.suspend(lease.begin(Any())!!))
        assertTrue(f.commands.isEmpty())
        assertFalse(f.recorded)
    }

    @Test fun unreadableJournalPreventsAnyRadioWrite() {
        val f = Fixture().apply { readFails = true }; val lease = f.lease()
        assertFalse(lease.suspend(lease.begin(Any())!!))
        assertTrue(f.commands.isEmpty())
        assertFalse(lease.isSuspended())
    }

    @Test fun cancellationAfterJournalButBeforeCommandDoesNotDisable() {
        val f = Fixture(); val lease = f.lease(); val owner = Any()
        f.afterPersist = { lease.end(owner) }
        assertFalse(lease.suspend(lease.begin(owner)!!))
        assertTrue(f.commands.isEmpty())
        assertFalse(f.recorded)
    }

    @Test fun cancellationDuringRunningDisableReturnsPromptlyAndCompensates() {
        val f = Fixture(); val lease = f.lease(); val owner = Any()
        val entered = CountDownLatch(1); val proceed = CountDownLatch(1)
        val result = AtomicReference<Boolean>()
        f.beforeCommand = { enabled -> if (!enabled) {
            entered.countDown(); assertTrue(proceed.await(5, TimeUnit.SECONDS))
        } }
        val ticket = lease.begin(owner)!!
        val disabling = thread { result.set(lease.suspend(ticket)) }
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            val cancelled = CountDownLatch(1)
            thread { assertNotNull(lease.end(owner)); cancelled.countDown() }
            assertTrue("cancel must not wait for the radio command", cancelled.await(1, TimeUnit.SECONDS))
        } finally { proceed.countDown(); disabling.join(5_000) }
        assertFalse(disabling.isAlive)
        assertEquals(false, result.get())
        assertEquals(listOf(false, true), f.commands)
        assertEquals(true, f.radio)
        assertFalse(f.recorded)
    }

    @Test fun shellSuccessWithoutActualStateChangeIsNotSuspended() {
        val f = Fixture().apply { commandAllowed = false }; val lease = f.lease()
        assertFalse(lease.suspend(lease.begin(Any())!!))
        assertFalse(lease.isSuspended())
        assertFalse(f.recorded) // Original ON state already holds, so no enable is needed.
        assertEquals(listOf(false), f.commands)
    }

    @Test fun interruptedDisableIsRecoveredByNewProcessJournal() {
        val f = Fixture(); val first = f.lease()
        assertTrue(first.suspend(first.begin(Any())!!))
        val restarted = f.lease()
        assertTrue(restarted.restore(restarted.recoveryOnAppOpen()!!))
        assertEquals(listOf(false, true), f.commands)
        assertFalse(f.recorded)
    }

    @Test fun crashAfterJournalBeforeDisableDoesNotIssueEnable() {
        val f = Fixture().apply { recorded = true; radio = true }; val restarted = f.lease()
        assertTrue(restarted.restore(restarted.recoveryOnAppOpen()!!))
        assertTrue(f.commands.isEmpty())
        assertFalse(f.recorded)
    }

    @Test fun failedRestoreRetainsEvidenceForNextOpening() {
        val f = Fixture(); val lease = f.lease(); val owner = Any()
        assertTrue(lease.suspend(lease.begin(owner)!!))
        f.commandAllowed = false
        assertFalse(lease.restore(lease.end(owner)!!))
        assertTrue(f.recorded)
        assertTrue(lease.isSuspended())
        f.commandAllowed = true
        val restarted = f.lease()
        assertTrue(restarted.restore(restarted.recoveryOnAppOpen()!!))
        assertFalse(f.recorded)
    }

    @Test fun unknownRecoveryStateRetainsJournalWithoutEnabling() {
        val f = Fixture().apply { recorded = true; radio = null }; val lease = f.lease()
        assertFalse(lease.restore(lease.recoveryOnAppOpen()!!))
        assertTrue(f.recorded)
        assertTrue(f.commands.isEmpty())
    }

    @Test fun failedJournalClearRetriesWithoutReEnablingAnAlreadyOnRadio() {
        val f = Fixture().apply { recorded = true; radio = false; clearAllowed = false }; val lease = f.lease()
        assertFalse(lease.restore(lease.recoveryOnAppOpen()!!))
        assertTrue(f.recorded)
        assertEquals(true, f.radio)
        f.clearAllowed = true
        assertTrue(lease.restore(lease.recoveryOnAppOpen()!!))
        assertEquals(listOf(true), f.commands)
        assertFalse(f.recorded)
    }

    @Test fun staleCloseAndRecoveryCannotReleaseNewControllerLease() {
        val f = Fixture(); val lease = f.lease(); val oldOwner = Any(); val newOwner = Any()
        val old = lease.begin(oldOwner)!!
        val staleRecovery = lease.end(oldOwner)!!
        val current = lease.begin(newOwner)!!
        assertNull(lease.end(oldOwner))
        assertFalse(lease.restore(staleRecovery))
        assertFalse(lease.suspend(old))
        assertTrue(lease.suspend(current))
        assertEquals(listOf(false), f.commands)
        assertTrue(lease.isSuspended())
    }

    @Test fun openingSettingsDuringLiveSessionPreservesItsLease() {
        val f = Fixture(); val lease = f.lease()
        assertTrue(lease.suspend(lease.begin(Any())!!))
        assertNull(lease.recoveryOnAppOpen())
        assertEquals(listOf(false), f.commands)
        assertTrue(lease.isSuspended())
    }

    @Test fun lifecycleRecoveryDoesNotInvalidateAnAlreadyQueuedHandshakeRecovery() {
        val f = Fixture().apply { recorded = true; radio = false }; val lease = f.lease()
        val handshake = lease.beforeHandshake()
        val lifecycle = lease.recoveryOnAppOpen()!!
        assertTrue("non-owning lifecycle cleanup must not retire the handshake", lease.restore(handshake))
        assertTrue(lease.restore(lifecycle))
        assertEquals(listOf(true), f.commands)
        assertFalse(f.recorded)
    }
}
