package com.shilapi.xcertplay.hud

/** A durable promise to restore a radio observed ON before our disable command. */
internal interface BluetoothRestoreJournal {
    fun pending(): Boolean
    fun write(pending: Boolean): Boolean
}

/** Cancellation stays fast even when an already-issued radio command is blocked. */
internal class BluetoothSuspendLease(
    private val journal: BluetoothRestoreJournal,
    private val readEnabled: () -> Boolean?,
    private val requestEnabled: (Boolean) -> Unit,
    private val awaitEnabled: (Boolean) -> Boolean,
) {
    private val stateLock = Any()
    private val commandLock = Any()
    private var owner: Any? = null
    private var generation = 0L

    fun begin(requester: Any): Long? = synchronized(stateLock) {
        if (owner === requester) return null
        owner = requester
        ++generation
    }

    fun end(requester: Any): Long? = synchronized(stateLock) {
        if (owner !== requester) return null
        owner = null
        ++generation
    }

    /** Opening settings during a live session must not release that session's lease. */
    fun recoveryOnAppOpen(): Long? = synchronized(stateLock) {
        if (owner != null) return null
        // Non-owning cleanup has the same recovery goal as an already queued handshake.
        // Reuse its ticket so a late old-controller close cannot make that handshake fail.
        generation
    }

    /** A new wireless handshake also retires an older pending disable. */
    fun beforeHandshake(): Long = synchronized(stateLock) {
        owner = null
        ++generation
    }

    fun isSuspended(): Boolean = pending() == true && enabled() == false

    fun suspend(ticket: Long): Boolean = synchronized(commandLock) {
        if (!current(ticket)) return false
        // Reconcile an interrupted older lease before observing this session's original state.
        if (!restoreOwned()) return false
        if (!current(ticket) || enabled() != true) return false
        // commit(), not apply(): process death after the command must leave recovery evidence.
        if (!runCatching { journal.write(true) }.getOrDefault(false)) return false
        if (!current(ticket)) {
            restoreOwned()
            return false
        }
        runCatching { requestEnabled(false) }
        val disabled = runCatching { awaitEnabled(false) }.getOrDefault(false)
        if (!disabled || !current(ticket)) {
            // A cancelled command may already have executed. Compensate while still serialized.
            restoreOwned()
            return false
        }
        true
    }

    fun restore(ticket: Long): Boolean = synchronized(commandLock) {
        if (!currentRecovery(ticket)) return false
        restoreOwned()
    }

    private fun current(ticket: Long): Boolean = synchronized(stateLock) {
        generation == ticket && owner != null
    }

    private fun currentRecovery(ticket: Long): Boolean = synchronized(stateLock) {
        generation == ticket && owner == null
    }

    private fun pending(): Boolean? = runCatching { journal.pending() }.getOrNull()
    private fun enabled(): Boolean? = runCatching { readEnabled() }.getOrNull()

    private fun restoreOwned(): Boolean {
        val owned = pending() ?: return false
        if (!owned) return true
        when (enabled()) {
            true -> Unit // It never turned off, or somebody already restored the original state.
            false -> {
                runCatching { requestEnabled(true) }
                if (!runCatching { awaitEnabled(true) }.getOrDefault(false)) return false
            }
            null -> return false // Unknown state is not permission to enable a user's radio.
        }
        return runCatching { journal.write(false) }.getOrDefault(false)
    }
}
