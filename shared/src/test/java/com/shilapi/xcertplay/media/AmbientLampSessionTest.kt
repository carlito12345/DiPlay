package com.shilapi.xcertplay.media

import com.shilapi.xcertplay.hud.BydAmbientLightPolicy
import java.util.concurrent.CompletableFuture
import org.junit.Assert.*
import org.junit.Test

class AmbientLampSessionTest {
    private val original = BydAmbientLightPolicy.Snapshot(7, 23, 2, 5, 2)
    private val desired = Triple(3, 15, 6)
    private class Fake : AmbientLampSession.Client {
        var failed = false
        var seed: BydAmbientLightPolicy.Snapshot? = null
        val begin = CompletableFuture<BydAmbientLightPolicy.Snapshot>()
        val stopped = CompletableFuture<Unit>()
        val applies = mutableListOf<Pair<Triple<Int, Int, Int>, CompletableFuture<Unit>>>()
        var stops = 0
        var closed = false
        override fun unhealthy() = failed
        override fun begin(seed: BydAmbientLightPolicy.Snapshot?): CompletableFuture<BydAmbientLightPolicy.Snapshot> { this.seed = seed; return begin }
        override fun apply(desired: Triple<Int, Int, Int>) = CompletableFuture<Unit>().also { applies.add(desired to it) }
        override fun stop(): CompletableFuture<Unit> { stops++; return stopped }
        override fun close() { closed = true }
    }
    private class Harness {
        var time = 0L
        val callbacks = ArrayDeque<() -> Unit>()
        val clients = mutableListOf<Fake>()
        var applies = 0
        val engine = AmbientLampSession(
            factory = { Fake().also { clients.add(it) } },
            dispatch = { callbacks.addLast(it) }, now = { time }, applied = { applies++ },
        )
        fun drain() { while (callbacks.isNotEmpty()) callbacks.removeFirst()() }
    }
    private fun start(h: Harness): Fake {
        h.engine.setEnabled(true); h.engine.update(desired)
        return h.clients.last().also { it.begin.complete(original); h.drain(); h.engine.update(desired) }
    }
    @Test fun applyAndStopFailureRecoverWithoutSettingsSaveAndPreserveOriginalSnapshot() {
        val h = Harness(); val first = start(h)
        first.applies.last().second.completeExceptionally(IllegalStateException("transport")); h.drain()
        assertEquals(1, first.stops)
        h.time = 60_000; h.engine.update(desired) // Even past retry time: stop has not completed.
        assertEquals(1, h.clients.size)
        first.stopped.completeExceptionally(IllegalStateException("EOF")); h.drain()
        assertTrue(first.closed)
        h.time += 15_999; h.engine.update(desired); assertEquals(1, h.clients.size)
        h.time++; h.engine.update(desired)
        val second = h.clients.last()
        assertEquals(original, second.seed)
        second.begin.complete(original); h.drain(); h.engine.update(desired)
        second.applies.last().second.complete(Unit); h.drain()
        assertEquals(1, h.applies)
    }
    @Test fun unchangedDesiredStillChecksFailedClientBeforeDeduplication() {
        val h = Harness(); val first = start(h)
        first.applies.last().second.complete(Unit); h.drain()
        first.failed = true
        h.time = 500; h.engine.update(desired)
        assertEquals(1, first.stops)
        first.stopped.complete(Unit); h.drain()
        h.time += 4_999; h.engine.update(desired); assertEquals(1, h.clients.size)
        h.time++; h.engine.update(desired); assertEquals(2, h.clients.size)
    }
    @Test fun consecutiveBeginFailuresBackOffToThirtySecondsAndSuccessfulApplyResetsBackoff() {
        val h = Harness(); h.engine.setEnabled(true)
        for (delay in listOf(5_000L, 10_000L, 20_000L, 30_000L, 30_000L)) {
            h.engine.update(desired)
            val client = h.clients.last(); val count = h.clients.size
            client.begin.completeExceptionally(IllegalStateException("unavailable")); h.drain()
            client.stopped.complete(Unit); h.drain()
            h.time += delay - 1; h.engine.update(desired); assertEquals(count, h.clients.size)
            h.time++
        }
        h.engine.update(desired)
        val good = h.clients.last(); good.begin.complete(original); h.drain(); h.engine.update(desired)
        good.applies.last().second.complete(Unit); h.drain()
        good.failed = true; h.engine.update(desired); good.stopped.complete(Unit); h.drain()
        val count = h.clients.size
        h.time += 4_999; h.engine.update(desired); assertEquals(count, h.clients.size)
        h.time++; h.engine.update(desired); assertEquals(count + 1, h.clients.size)
    }
    @Test fun disablingCancelsRecoveryAndUnconfirmedSnapshotIsNotLost() {
        val h = Harness(); val first = start(h)
        h.engine.setEnabled(false)
        first.stopped.completeExceptionally(IllegalStateException("restore pending")); h.drain()
        h.time = 100_000; h.engine.update(desired)
        assertEquals(1, h.clients.size)
        h.engine.setEnabled(true); h.engine.update(desired)
        assertEquals(original, h.clients.last().seed)
    }
    @Test fun reenableCannotBypassUncertainStopLeaseGrace() {
        val h = Harness(); val first = start(h)
        h.engine.setEnabled(false)
        first.stopped.completeExceptionally(IllegalStateException("restore pending")); h.drain()
        h.engine.setEnabled(true)
        h.time = 15_999; h.engine.update(desired); assertEquals(1, h.clients.size)
        h.time = 16_000; h.engine.update(desired); assertEquals(2, h.clients.size)
    }
    @Test fun disabledWhileOldStopPendingNeverBeginsAndLateApplyDoesNotTouchNewOwner() {
        val h = Harness(); val first = start(h)
        val staleApply = first.applies.last().second
        first.failed = true; h.engine.update(desired)
        h.engine.setEnabled(false)
        first.stopped.complete(Unit); h.drain()
        h.time = 100_000; h.engine.update(desired); assertEquals(1, h.clients.size)
        h.engine.setEnabled(true); h.engine.update(desired)
        val second = h.clients.last(); second.begin.complete(original); h.drain(); h.engine.update(desired)
        staleApply.completeExceptionally(IllegalStateException("stale callback")); h.drain()
        assertEquals(0, second.stops)
        second.applies.last().second.complete(Unit); h.drain()
        assertEquals(1, h.applies)
    }
    @Test fun changedDesiredAndPauseMinimumStayPendingUntilSuccessfulApply() {
        val h = Harness(); val client = start(h)
        val minimum = Triple(3, 7, 1)
        h.time = 250; h.engine.update(minimum)
        assertEquals(1, client.applies.size) // Never overlap a device apply.
        client.applies.first().second.complete(Unit); h.drain()
        h.engine.update(minimum)
        assertEquals(minimum, client.applies.last().first)
    }
    @Test fun disableAfterConfirmedRestoreReleasesSnapshotForNextOemSession() {
        val h = Harness(); val first = start(h)
        first.failed = true; h.engine.update(desired)
        first.stopped.complete(Unit); h.drain()
        h.engine.setEnabled(false) // No active worker; OEM now owns lamp state.
        h.time = 1_000
        h.engine.setEnabled(true); h.engine.update(desired)
        val second = h.clients.last()
        assertNull(second.seed) // A later OEM change must be freshly snapshotted.
        val changedOem = original.copy(frontColor = 12, frontBrightness = 4)
        second.begin.complete(changedOem); h.drain(); h.engine.update(desired)
        second.failed = true; h.engine.update(desired); second.stopped.complete(Unit); h.drain()
        h.time += 5_000; h.engine.update(desired)
        assertEquals(changedOem, h.clients.last().seed)
    }
    @Test fun staleBeginCompletionCannotReplaceNewWorkerSnapshot() {
        val h = Harness(); h.engine.setEnabled(true); h.engine.update(desired)
        val old = h.clients.last()
        h.engine.setEnabled(false); old.stopped.complete(Unit); h.drain()
        h.engine.setEnabled(true); h.engine.update(desired)
        val fresh = h.clients.last()
        val freshOem = original.copy(backColor = 11)
        fresh.begin.complete(freshOem); h.drain(); h.engine.update(desired)
        old.begin.complete(original); h.drain()
        fresh.failed = true; h.engine.update(desired); fresh.stopped.complete(Unit); h.drain()
        h.time += 5_000; h.engine.update(desired)
        assertEquals(freshOem, h.clients.last().seed)
    }

}
