package com.shilapi.xcertplay.media

import com.shilapi.xcertplay.hud.BydAmbientLightPolicy
import java.util.concurrent.CompletableFuture

/** Serialized lamp lifecycle used by the real controller and fake-client tests. No audio or timer thread here. */
internal class AmbientLampSession(
    private val factory: () -> Client,
    private val dispatch: (() -> Unit) -> Unit,
    private val now: () -> Long,
    private val log: (String) -> Unit = {},
    private val applied: (Triple<Int, Int, Int>) -> Unit = {},
) {
    interface Client {
        fun unhealthy(): Boolean
        fun begin(seed: BydAmbientLightPolicy.Snapshot?): CompletableFuture<BydAmbientLightPolicy.Snapshot>
        fun apply(desired: Triple<Int, Int, Int>): CompletableFuture<Unit>
        fun stop(): CompletableFuture<Unit>
        /** Called after stop completes; must close stdin/transport before returning control to future retries. */
        fun close()
    }
    private var enabled = false
    private var client: Client? = null
    private var ready = false
    private var applying = false
    private var stopping = false
    private var failures = 0
    private var retryAt = 0L
    private var original: BydAmbientLightPolicy.Snapshot? = null
    private var restoreUnconfirmed = false
    private var lastApplied: Triple<Int, Int, Int>? = null
    private val cadence = AmbientMusicApplyCadence()

    /** Called on the owning controller executor; an active worker need not be probed again. */
    fun isReady(): Boolean = ready && !stopping && client?.unhealthy() == false

    fun setEnabled(value: Boolean) {
        if (enabled == value) return
        enabled = value
        if (!value) {
            if (client == null && !stopping && !restoreUnconfirmed) {
                original = null; failures = 0; retryAt = now()
            }
            stopOwned(false)
        } else {
            // An unconfirmed previous stop retains its snapshot and its safety deadline.
            if (!stopping && original == null && failures == 0) retryAt = now()
        }
    }

    /** Called before desired-value deduplication, even if music stays on one color indefinitely. */
    fun update(desired: Triple<Int, Int, Int>) {
        if (!enabled || stopping) return
        val owned = client
        if (owned != null && owned.unhealthy()) { failOwned("inactive"); return }
        if (owned == null) {
            if (now() < retryAt) return
            val opening = try { factory() } catch (_: Throwable) { scheduleRetry(false); return }
            client = opening
            ready = false
            log("lamp begin retry=$failures seeded=${original != null}")
            val started = try { opening.begin(original) } catch (error: Throwable) {
                CompletableFuture<BydAmbientLightPolicy.Snapshot>().also { it.completeExceptionally(error) }
            }
            started.whenComplete { snapshot, error -> dispatch {
                if (client !== opening || stopping) return@dispatch
                if (error != null || snapshot == null || !BydAmbientLightPolicy.validSnapshot(snapshot)) {
                    failOwned("begin")
                } else {
                    if (original == null) original = snapshot
                    ready = true
                    // Reset backoff only after an actual apply succeeds, not a begin/apply failure loop.
                    log("lamp session ready")
                }
            } }
            return
        }
        if (!ready || applying || lastApplied == desired || !cadence.trySubmit(now())) return
        applying = true
        val future = try { owned.apply(desired) } catch (error: Throwable) {
            CompletableFuture<Unit>().also { it.completeExceptionally(error) }
        }
        future.whenComplete { _, error -> dispatch {
            if (client !== owned || stopping) return@dispatch
            applying = false
            if (error == null) {
                lastApplied = desired
                failures = 0
                applied(desired)
            } else if (!ambientApplyCancelled(error)) failOwned("apply")
        } }
    }

    fun recover() { if (enabled) failOwned("controller") }

    private fun failOwned(reason: String) {
        log("lamp $reason failed; recovery scheduled")
        stopOwned(true)
    }

    private fun scheduleRetry(uncertainStop: Boolean) {
        val backoff = when (failures.coerceAtMost(3)) { 0 -> 5_000L; 1 -> 10_000L; 2 -> 20_000L; else -> 30_000L }
        failures = (failures + 1).coerceAtMost(4)
        val delay = maxOf(backoff, if (uncertainStop) BydAmbientLightPolicy.LEASE_MILLIS + 1_000 else 0)
        retryAt = now() + delay
        log("lamp retry in ${delay}ms enabled=$enabled restorePending=$uncertainStop")
    }

    private fun stopOwned(recover: Boolean) {
        if (stopping) return
        val old = client ?: return
        stopping = true
        client = null // All old begin/apply callbacks immediately lose ownership.
        ready = false; applying = false; lastApplied = null; cadence.reset()
        val stopped = try { old.stop() } catch (error: Throwable) {
            CompletableFuture<Unit>().also { it.completeExceptionally(error) }
        }
        stopped.whenComplete { _, error -> dispatch {
            var uncertain = error != null
            try { old.close() } catch (_: Throwable) { uncertain = true }
            stopping = false
            restoreUnconfirmed = uncertain
            if (recover || uncertain) scheduleRetry(uncertain)
            else { failures = 0; retryAt = now() }
            if (!enabled && !uncertain) original = null
            // Disabled means restoration only. Never start a new worker/apply until re-enabled.
            log("lamp stop complete confirmed=${!uncertain} enabled=$enabled")
        } }
    }
}
