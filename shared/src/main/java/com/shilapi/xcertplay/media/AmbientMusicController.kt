package com.shilapi.xcertplay.media

import android.content.Context
import android.util.Log
import com.shilapi.xcertplay.hud.BydAmbientLightClient
import com.shilapi.xcertplay.hud.BydAmbientLightPolicy
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/** The sole scheduler owns lamp I/O; audio writes only append small envelope metadata. */
object AmbientMusicController {
    private val executor = Executors.newSingleThreadScheduledExecutor { task ->
        Thread(task, "carplay-ambient-music").apply { isDaemon = true }
    }
    private val sequence = AtomicLong()
    private val activeSink = AtomicLong()
    private val activeRenderer = AtomicLong()
    private val phonePlayback = AmbientPhonePlaybackState()
    @Volatile private var settings = AmbientMusicSettings.Values()
    private var context: Context? = null
    private var envelope: AmbientMusicEnvelope? = null
    private var playback: (() -> Pair<Int, Boolean>)? = null
    private var allowed = false
    private var colors = AmbientMusicColorDetector()
    private var brightnessEnvelope = AmbientMusicBrightness(settings.speed)
    private var appliedCount = 0L
    private var lastLogMillis = 0L

    private val lampSession = AmbientLampSession(
        factory = {
            val real = BydAmbientLightClient(requireNotNull(context))
            object : AmbientLampSession.Client {
                override fun unhealthy(): Boolean {
                    val phase = real.status().phase
                    return phase == BydAmbientLightClient.Phase.FAILED || phase == BydAmbientLightClient.Phase.CLOSED
                }
                override fun begin(seed: BydAmbientLightPolicy.Snapshot?) = real.begin(seed?.let {
                    BydAmbientLightClient.State(it.frontColor, it.backColor, it.frontBrightness, it.backBrightness, it.area)
                }).thenApply { BydAmbientLightPolicy.Snapshot(it.frontColor, it.backColor, it.frontBrightness, it.backBrightness, it.area) }
                override fun apply(desired: Triple<Int, Int, Int>) = real.apply(desired.first, desired.second, desired.third).thenApply { Unit }
                override fun stop() = real.stop().thenApply { Unit }
                // stop's future completes only after closeTransport, including its failure path.
                override fun close() = real.close()
            }
        },
        dispatch = { executor.execute(it) },
        now = { android.os.SystemClock.uptimeMillis() },
        log = { Log.i("DiPlay-Ambient", it) },
        applied = { desired ->
            appliedCount++
            val now = android.os.SystemClock.uptimeMillis()
            if (now - lastLogMillis >= 5_000) {
                lastLogMillis = now
                Log.i("DiPlay-Ambient", "applied=$appliedCount area=${desired.first} color=${desired.second} brightness=${desired.third} colorMode=${settings.colorMode} estimatedBpm=${colors.estimatedBpm?.toInt() ?: 0}")
            }
        },
    )

    // The 20 Hz lamp update, scheduled only while the feature is on; turning it off restores the lamps by itself.
    private var ticking: ScheduledFuture<*>? = null

    /** On the executor, after [settings] changed. */
    private fun updateTicking() {
        if (!settings.enabled) {
            ticking?.cancel(false)
            ticking = null
        } else if (ticking == null) {
            ticking = executor.scheduleWithFixedDelay({ runCatching { tick() }.onFailure {
                Log.w("DiPlay-Ambient", "lamp update failed", it)
                lampSession.recover()
            } }, 50, 50, TimeUnit.MILLISECONDS)
        }
    }

    @Synchronized fun openSink(context: Context?): Long {
        if (context == null) return 0L
        val token = sequence.incrementAndGet()
        activeSink.set(token)
        activeRenderer.set(0L)
        executor.execute {
            if (activeSink.get() != token) return@execute
            this.context = context.applicationContext
            settings = AmbientMusicSettings.load(context)
            colors = ambientMusicColorDetector(settings.color, settings.selectedColors)
            brightnessEnvelope = AmbientMusicBrightness(settings.speed)
            allowed = settings.enabled; envelope = null; playback = null
            if (!settings.enabled) restoreOriginal()
            updateTicking()
        }
        return token
    }

    @Synchronized internal fun openRenderer(sink: Long, envelope: AmbientMusicEnvelope,
        playback: () -> Pair<Int, Boolean>): Long {
        if (sink == 0L || sink != activeSink.get()) return 0L
        val token = sequence.incrementAndGet()
        activeRenderer.set(token)
        executor.execute {
            if (activeSink.get() != sink || activeRenderer.get() != token) return@execute
            allowed = true
            this.envelope = envelope
            this.playback = playback
        }
        return token
    }

    internal fun wantsPcm(token: Long): Boolean = token != 0L && token == activeRenderer.get() && settings.enabled && settings.music

    /** Binds phone playback callbacks to the current CarPlay media owner. */
    fun claimPlaybackOwner(owner: Any) = phonePlayback.claim(owner)

    /** Stale callbacks from a replaced media owner are ignored. */
    fun phonePlaybackChanged(owner: Any, playing: Boolean): Boolean = phonePlayback.update(owner, playing)

    fun releasePlaybackOwner(owner: Any): Boolean = phonePlayback.release(owner)

    /** Checks existing authorization and lamp readback only; never asks for ADB or writes a lamp. */
    fun checkSupport(context: Context): java.util.concurrent.CompletableFuture<Boolean> {
        val result = java.util.concurrent.CompletableFuture<Boolean>()
        executor.execute {
            if (lampSession.isReady()) {
                result.complete(true)
                return@execute
            }
            val client = BydAmbientLightClient(context.applicationContext)
            client.read().handle { _, error -> error == null }.thenCompose { supported ->
                // Release the read-only worker before an enabled session can acquire its process lock.
                client.stop().handle { _, error ->
                    client.close()
                    supported && error == null
                }
            }.whenComplete { supported, error -> result.complete(error == null && supported == true) }
        }
        return result
    }

    fun settingsChanged(context: Context) {
        executor.execute {
            this.context = context.applicationContext
            settings = AmbientMusicSettings.load(context)
            colors = ambientMusicColorDetector(settings.color, settings.selectedColors)
            brightnessEnvelope = AmbientMusicBrightness(settings.speed)
            allowed = settings.enabled
            if (!settings.enabled) restoreOriginal()
            updateTicking()
        }
    }

    @Synchronized internal fun closeRenderer(token: Long) {
        if (token == 0L || !activeRenderer.compareAndSet(token, 0L)) return
        executor.execute {
            if (activeRenderer.get() != 0L) return@execute
            allowed = settings.enabled
            envelope = null; playback = null
            if (!settings.enabled) restoreOriginal()
        }
    }

    @Synchronized fun closeSink(token: Long) {
        if (token == 0L || !activeSink.compareAndSet(token, 0L)) return
        activeRenderer.set(0L)
        executor.execute {
            if (activeSink.get() != 0L) return@execute
            allowed = settings.enabled
            envelope = null; playback = null
            if (!settings.enabled) restoreOriginal()
        }
    }

    private fun tick() {
        val values = settings
        if (!allowed) return
        if (!values.enabled) { restoreOriginal(); return }
        if (context == null) return
        lampSession.setEnabled(true)
        val rendererActive = activeRenderer.get() != 0L && playback != null
        val playbackState = if (rendererActive && values.brightness > 0)
            playback?.invoke() else null
        val target = AmbientMusicActivityPolicy.target(
            enabled = values.enabled,
            music = values.music,
            brightness = values.brightness,
            rendererActive = rendererActive,
            playing = playbackState?.second == true && phonePlayback.current() != false
        )
        if (target == AmbientMusicTarget.RESTORE_OEM) { restoreOriginal(); return }
        val energy = if (target == AmbientMusicTarget.FOLLOW_PLAYBACK) {
            val (head, playing) = playbackState ?: return
            envelope?.playedWindow(head, playing) ?: AmbientMusicEnvelope.Energy(0.0, 0.0)
        } else AmbientMusicEnvelope.Energy(0.0, 0.0)
        val rms = energy.rms
        val nowMillis = android.os.SystemClock.uptimeMillis()
        val brightness = when (target) {
            AmbientMusicTarget.LOWEST -> 1
            AmbientMusicTarget.FOLLOW_PLAYBACK -> brightnessEnvelope.level(rms, nowMillis, values.brightness)
            else -> values.brightness
        }
        val area = if (target == AmbientMusicTarget.LOWEST) 3 else values.area
        val color = if (target == AmbientMusicTarget.FOLLOW_PLAYBACK && values.colorCycle)
            colors.color(values.colorMode, nowMillis, rms, energy.bassRms) else values.selectedColors.firstOrNull() ?: values.color
        lampSession.update(Triple(area, color, brightness))
    }

    private fun restoreOriginal() { lampSession.setEnabled(false) }
}
