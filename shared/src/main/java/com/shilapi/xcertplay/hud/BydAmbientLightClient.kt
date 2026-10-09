package com.shilapi.xcertplay.hud

import android.content.Context
import android.util.Log
import com.shilapi.xcertplay.adb.AdbKeys
import com.shilapi.xcertplay.adb.LocalAdb
import java.security.SecureRandom
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/** Async API for UI/music callers. All ADB startup, reads, writes and restore waits run on its executor. */
internal class BydAmbientLightClient(context: Context) : AutoCloseable {
    data class State(val frontColor: Int, val backColor: Int, val frontBrightness: Int, val backBrightness: Int, val area: Int)
    enum class Phase { IDLE, STARTING, ACTIVE, RESTORING, FAILED, CLOSED }
    data class Status(val phase: Phase, val generation: Long, val lastError: String? = null)

    private val app = context.applicationContext
    private val executor = ScheduledThreadPoolExecutor(1) { r -> Thread(r, "diplay-ambient-light").apply { isDaemon = true } }
    private val generation = AtomicLong(0)
    @Volatile private var status = Status(Phase.IDLE, 0)
    private var adb: LocalAdb? = null
    private var stream: LocalAdb.InteractiveShell? = null
    private var token: String? = null
    private var keepAlive: ScheduledFuture<*>? = null
    private var pending: Pending? = null
    private var lastApplyAtNanos = 0L
    @Volatile private var closed = false

    private data class Pending(val generation: Long, val area: Int, val color: Int, val brightness: Int, val future: CompletableFuture<State>)

    fun status(): Status = status

    fun read(): CompletableFuture<State> = submit {
        val ticket = generation.get()
        ensure(ticket)
        val result = parseState(exchange("read")?.takeIf { it.startsWith("state=") }?.removePrefix("state=") ?: error("worker-read-failed"))
        check(ticket == generation.get()) { "stale-generation" }
        result
    }

    /** Takes the sole restoration snapshot for this session; it is never refreshed by later applies. */
    fun begin(restoreSeed: State? = null): CompletableFuture<State> = submit {
        require(restoreSeed == null || BydAmbientLightPolicy.validSnapshot(restoreSeed.snapshot())) { "invalid-restore-seed" }
        check(status.phase == Phase.IDLE || status.phase == Phase.FAILED) { "session-already-active" }
        val ticket = generation.incrementAndGet()
        status = Status(Phase.STARTING, ticket)
        try {
            ensure(ticket)
            val command = restoreSeed?.let { "begin ${it.frontColor} ${it.backColor} ${it.frontBrightness} ${it.backBrightness} ${it.area}" } ?: "begin"
            val result = parseState(exchange(command)?.takeIf { it.startsWith("begun=") }?.removePrefix("begun=") ?: error("worker-begin-failed"))
            status = Status(Phase.ACTIVE, ticket)
            keepAlive?.cancel(false)
            keepAlive = executor.scheduleWithFixedDelay({
                if (generation.get() == ticket && status.phase == Phase.ACTIVE) {
                    try {
                        if (exchange("ping") != "ready") fail(ticket, "worker-heartbeat-failed")
                    } catch (error: Throwable) {
                        fail(ticket, "worker-heartbeat-exception:" + error.javaClass.simpleName)
                    }
                }
            }, 5, 5, TimeUnit.SECONDS)
            result
        } catch (t: Throwable) {
            fail(ticket, safe(t)); throw t
        }
    }

    /** Latest-value queue, capped at 5 Hz. A replaced apply completes with CancellationException. */
    fun apply(area: Int, color: Int, rawBrightness: Int): CompletableFuture<State> {
        require(BydAmbientLightPolicy.validApply(area, color, rawBrightness))
        val result = CompletableFuture<State>()
        val ticket = generation.get()
        executor.execute {
            if (closed || status.phase != Phase.ACTIVE || ticket != generation.get()) {
                result.completeExceptionally(IllegalStateException("ambient-session-not-active")); return@execute
            }
            pending?.future?.completeExceptionally(java.util.concurrent.CancellationException("superseded-by-newer-apply"))
            pending = Pending(ticket, area, color, rawBrightness, result)
            val elapsed = System.nanoTime() - lastApplyAtNanos
            val delayNanos = (200_000_000L - elapsed).coerceAtLeast(0)
            executor.schedule({ flushApply(ticket) }, delayNanos, TimeUnit.NANOSECONDS)
        }
        return result
    }

    /** Restoration is never coalesced or dropped; queued cosmetic applies are discarded first. */
    fun stop(): CompletableFuture<State?> = submit {
        val ticket = generation.get()
        pending?.future?.completeExceptionally(java.util.concurrent.CancellationException("session-stopping")); pending = null
        keepAlive?.cancel(false); keepAlive = null
        if (status.phase != Phase.ACTIVE && status.phase != Phase.FAILED) {
            closeTransport(); status = Status(Phase.IDLE, ticket); return@submit null
        }
        status = Status(Phase.RESTORING, ticket)
        try {
            val response = exchange("stop", 10000) ?: error("worker-restore-failed")
            check(response.startsWith("restored=") || response == "no-snapshot") { response }
            val restored = if (response.startsWith("restored=")) parseState(response.removePrefix("restored=")) else null
            closeTransport()
            status = Status(Phase.IDLE, ticket)
            restored
        } catch (t: Throwable) {
            closeTransport() // EOF makes the worker attempt its saved snapshot in finally.
            status = Status(Phase.FAILED, ticket, safe(t))
            throw t
        }
    }

    /** Ends a begun session at raw brightness 1, preserving its current colors and area. */
    fun stopAtMinimum(): CompletableFuture<State?> {
        val ticket = generation.get()
        return submit {
            check(ticket == generation.get()) { "stale-generation" }
            pending?.future?.completeExceptionally(java.util.concurrent.CancellationException("session-stopping"))
            pending = null
            keepAlive?.cancel(false)
            keepAlive = null
            if (status.phase != Phase.ACTIVE && status.phase != Phase.FAILED) {
                closeTransport()
                status = Status(Phase.IDLE, ticket)
                return@submit null
            }
            status = Status(Phase.RESTORING, ticket)
            try {
                val response = exchange("minimum-stop", 10000) ?: error("worker-minimum-failed")
                check(response.startsWith("minimum=")) { response }
                val minimum = parseState(response.removePrefix("minimum="))
                check(minimum.frontBrightness == 1 && minimum.backBrightness == 1) { "minimum-not-confirmed" }
                check(ticket == generation.get()) { "stale-generation" }
                closeTransport()
                status = Status(Phase.IDLE, ticket)
                minimum
            } catch (t: Throwable) {
                // An uncommitted minimum transaction retains the worker's original restore snapshot.
                closeTransport()
                if (ticket == generation.get()) status = Status(Phase.FAILED, ticket, safe(t))
                throw t
            }
        }
    }

    /** Invalidates outstanding work without restarting. EOF invokes the worker's restore-on-close path. */
    fun cancelGeneration(): CompletableFuture<Unit> {
        val next = generation.incrementAndGet()
        return submit {
        pending?.future?.completeExceptionally(java.util.concurrent.CancellationException("stale-generation")); pending = null
        keepAlive?.cancel(false); keepAlive = null
        val wasActive = status.phase == Phase.ACTIVE || status.phase == Phase.RESTORING
        if (wasActive) {
            status = Status(Phase.RESTORING, next)
            runCatching { exchange("stop") }
        }
        closeTransport()
        status = Status(Phase.IDLE, next)
        Unit
        }
    }

    private fun flushApply(ticket: Long) {
        val command = pending ?: return
        if (command.generation != ticket || ticket != generation.get() || status.phase != Phase.ACTIVE) {
            pending = null
            command.future.completeExceptionally(java.util.concurrent.CancellationException("stale-generation"))
            return
        }
        pending = null
        try {
            val result = exchange("apply ${command.area} ${command.color} ${command.brightness}")
                ?.takeIf { it.startsWith("applied=") }?.removePrefix("applied=") ?: error("worker-apply-failed")
            lastApplyAtNanos = System.nanoTime()
            if (ticket == generation.get()) command.future.complete(parseState(result))
            else command.future.completeExceptionally(java.util.concurrent.CancellationException("stale-generation"))
        } catch (t: Throwable) {
            command.future.completeExceptionally(t)
            fail(ticket, safe(t))
        }
    }

    private fun <T> submit(block: () -> T): CompletableFuture<T> {
        val f = CompletableFuture<T>()
        if (closed) { f.completeExceptionally(IllegalStateException("client-closed")); return f }
        executor.execute { try { f.complete(block()) } catch (t: Throwable) { f.completeExceptionally(t) } }
        return f
    }

    private fun ensure(ticket: Long) {
        check(ticket == generation.get() && !closed) { "stale-generation" }
        if (stream != null) return
        val client = LocalAdb(AdbKeys.load(app)); adb = client
        check(client.connect(mayAsk = false) == LocalAdb.Access.READY) { "adb-unavailable" }
        val next = ByteArray(16).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }
        token = next
        val apk = "'" + app.applicationInfo.sourceDir.replace("'", "'\\''") + "'"
        stream = client.openShell("CLASSPATH=$apk app_process /system/bin ${BydAmbientLightTool::class.java.name} --stdin $next")
        check(stream != null) { "worker-launch-failed" }
        check(exchange("ping", 10000) == "ready") { "worker-not-ready" }
    }

    private fun exchange(command: String, timeout: Int = 5000): String? =
        token?.let { stream?.exchangeBounded("$it $command", timeout) }

    private fun fail(ticket: Long, reason: String) {
        if (generation.get() != ticket) return
        keepAlive?.cancel(false); keepAlive = null
        status = Status(Phase.FAILED, ticket, reason)
        runCatching { closeTransport() }
        Log.w("DiPlay-Ambient", "worker failed: $reason")
    }

    private fun closeTransport() {
        val oldStream = stream; stream = null
        val oldAdb = adb; adb = null
        token = null
        try { oldStream?.close() } finally { oldAdb?.close() }
    }

    private fun parseState(value: String): State {
        val fields = value.trim().split(',').map { it.toInt() }
        require(fields.size == 5)
        val s = State(fields[0], fields[1], fields[2], fields[3], fields[4])
        check(BydAmbientLightPolicy.validSnapshot(BydAmbientLightPolicy.Snapshot(s.frontColor, s.backColor, s.frontBrightness, s.backBrightness, s.area)))
        return s
    }

    private fun State.snapshot() = BydAmbientLightPolicy.Snapshot(frontColor, backColor, frontBrightness, backBrightness, area)

    private fun safe(t: Throwable): String = (t.cause ?: t).let { "${it.javaClass.simpleName}:${it.message.orEmpty()}" }.take(140)

    override fun close() {
        if (closed) return
        generation.incrementAndGet()
        // Graceful stop is queued before the executor is shut down; all API methods remain nonblocking.
        stop().whenComplete { _, _ ->
            closed = true
            status = Status(Phase.CLOSED, generation.get())
            executor.shutdown()
        }
    }
}
