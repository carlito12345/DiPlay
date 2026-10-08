// carlito | One focus owner for media, guidance, calls and Siri, including microphone-only phases.
package com.shilapi.xcertplay.media

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Handler
import android.os.Looper
import java.io.Closeable

internal class AudioFocusCoordinator(
    context: Context?,
    private val enabled: Boolean,
    private val report: (String) -> Unit = {},
    private val factoryRouting: Boolean = false,
    private val unifiedMediaOutput: Boolean = false,
    private val onOwnershipChanged: (Boolean) -> Unit = {},
) : Closeable {
    private data class Entry(val channel: AudioChannel, val attributes: AudioAttributes)
    private val manager = context?.getSystemService(AudioManager::class.java)
    private val active = LinkedHashMap<AudioTrack, Entry>()
    private val captures = LinkedHashMap<AudioChannel, Entry>()
    private var request: AudioFocusRequest? = null
    private var requestedChannel: AudioChannel? = null
    // carlito | Capture and playback can share a role but require different focus attributes.
    private var requestedAttributes: AudioAttributes? = null
    private var requestGeneration = 0
    private var focusHeld = false
    private var focusVolume = 0f
    private var mediaAttributes: AudioAttributes? = null
    private var mediaSuppressed = false
    private var mediaPlaying: Boolean? = null
    private var externalCall = false
    private var nativeBluetoothPlaying = false
    private var ownership: Boolean? = null
    private var closed = false

    @Synchronized private fun onFocusChanged(generation: Int, change: Int) {
        if (closed || generation != requestGeneration) return
        runCatching { report("Audio: focus change=$change activeTracks=${active.size}") }
        when (change) {
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> focusVolume = DUCKED_VOLUME
            AudioManager.AUDIOFOCUS_GAIN -> { focusHeld = true; focusVolume = 1f }
            AudioManager.AUDIOFOCUS_LOSS, AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                focusHeld = false; focusVolume = 0f
                if (change == AudioManager.AUDIOFOCUS_LOSS) mediaSuppressed = true
            }
        }
        applyVolumes()
    }

    @Synchronized fun acquire(track: AudioTrack, channel: AudioChannel, attributes: AudioAttributes) {
        if (closed) return
        active[track] = Entry(channel, attributes)
        if (channel == AudioChannel.MEDIA) mediaAttributes = attributes
        refreshRequest()
    }

    @Synchronized fun release(track: AudioTrack) {
        if (active.remove(track) != null) refreshRequest()
    }

    @Synchronized fun onMediaPlaying(playing: Boolean) {
        if (closed) return
        val wasPlaying = mediaPlaying
        mediaPlaying = playing
        // A repeated Now Playing update must not steal focus from a newly selected native source.
        if (playing && wasPlaying != true) mediaSuppressed = false
        refreshRequest()
        if (playing && wasPlaying != true && !focusHeld && requestedChannel == AudioChannel.MEDIA) requestCurrentFocus()
    }

    @Synchronized fun setMicrophones(phone: Boolean, assistant: Boolean) {
        if (closed || !enabled) return
        captures.clear()
        if (phone) addCapture(AudioChannel.PHONE, AudioAttributes.USAGE_VOICE_COMMUNICATION)
        if (assistant) addCapture(AudioChannel.ASSISTANT, AudioAttributes.USAGE_ASSISTANT)
        refreshRequest()
    }
    @Synchronized fun captureAllowed(): Boolean = !closed && (!enabled || focusHeld && !externalCall)

    private fun addCapture(channel: AudioChannel, usage: Int) {
        // carlito | Input focus remains a voice request even when its output track uses MEDIA.
        val playbackAttributes = if (unifiedMediaOutput) null else active.values.firstOrNull { it.channel == channel }?.attributes
        captures[channel] = Entry(channel, playbackAttributes
            ?: AudioAttributes.Builder().setUsage(usage)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
    }

    // carlito | Connection alone is not proof of music. Keep navigation, calls, Siri and capture alive.
    @Synchronized fun setNativeBluetoothPlaying(playing: Boolean) {
        if (closed || nativeBluetoothPlaying == playing) return
        // carlito | This explicit Bluetooth mode promises CarPlay fallback even for an unchanged media stream.
        // Only a confirmed native-playing -> stopped transition retries; ordinary native-source focus loss stays suppressed.
        if (!playing) mediaSuppressed = false
        nativeBluetoothPlaying = playing
        refreshRequest()
        runCatching { report("Audio: native Bluetooth music playing=$playing") }
    }

    @Synchronized fun setExternalCall(active: Boolean) {
        if (closed || externalCall == active) return
        externalCall = active
        if (active) abandonRequest() else refreshRequest()
        applyVolumes()
        runCatching { report("Audio: external call owns route=$active") }
    }

    @Synchronized fun onCommunicationEnded() {
        if (closed) return
        abandonRequest()
        refreshRequest()
    }

    @Synchronized override fun close() {
        if (closed) return
        closed = true
        abandonRequest()
        active.clear(); captures.clear(); mediaAttributes = null
        publishOwnership(false)
    }

    private fun abandonRequest() {
        requestGeneration++
        request?.let { runCatching { manager?.abandonAudioFocusRequest(it) } }
        request = null; requestedChannel = null; requestedAttributes = null; focusHeld = false; focusVolume = 0f
    }

    private fun refreshRequest() {
        if (closed) return
        if (!enabled || manager == null) { applyVolumes(); return }
        if (externalCall) { applyVolumes(); return }
        val entries = if (unifiedMediaOutput) captures.values + active.values else active.values + captures.values
        val primary = entries.filter {
            it.channel != AudioChannel.NAVIGATION && (it.channel != AudioChannel.MEDIA || !nativeBluetoothPlaying && !mediaSuppressed && mediaPlaying != false)
        }.maxByOrNull { it.channel.priority() }
            ?: mediaAttributes?.takeIf { !nativeBluetoothPlaying && !mediaSuppressed && mediaPlaying != false }?.let { Entry(AudioChannel.MEDIA, it) }
            ?: active.values.firstOrNull { it.channel == AudioChannel.NAVIGATION }
        if (primary == null) { abandonRequest(); applyVolumes(); return }
        if (request != null && requestedChannel == primary.channel && requestedAttributes == primary.attributes) { applyVolumes(); return }
        abandonRequest()
        val generation = ++requestGeneration
        val gain = when (primary.channel) {
            AudioChannel.MEDIA -> AudioManager.AUDIOFOCUS_GAIN
            AudioChannel.PHONE, AudioChannel.RINGTONE -> AudioManager.AUDIOFOCUS_GAIN_TRANSIENT
            AudioChannel.ASSISTANT -> AudioManager.AUDIOFOCUS_GAIN_TRANSIENT
            AudioChannel.NAVIGATION -> if (factoryRouting) AudioManager.AUDIOFOCUS_GAIN_TRANSIENT
                else AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK
        }
        request = AudioFocusRequest.Builder(gain).setAudioAttributes(primary.attributes)
            .setOnAudioFocusChangeListener({ change -> onFocusChanged(generation, change) }, Handler(Looper.getMainLooper())).build()
        requestedChannel = primary.channel
        requestedAttributes = primary.attributes
        requestCurrentFocus()
    }

    private fun requestCurrentFocus() {
        if (externalCall || closed) return
        val current = request ?: return
        val result = runCatching { manager?.requestAudioFocus(current) }.getOrDefault(AudioManager.AUDIOFOCUS_REQUEST_FAILED)
        focusHeld = result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        focusVolume = if (focusHeld) 1f else 0f
        applyVolumes()
        runCatching { report("Audio: focus requested channel=$requestedChannel granted=$result activeTracks=${active.size}") }
    }

    private fun applyVolumes() {
        val navigation = active.values.any { it.channel == AudioChannel.NAVIGATION }
        active.forEach { (track, entry) ->
            if (!enabled || manager == null) {
                runCatching { track.setVolume(if (nativeBluetoothPlaying && entry.channel == AudioChannel.MEDIA) 0f else 1f) }
                return@forEach
            }
            val local = when {
                externalCall -> 0f
                entry.channel == AudioChannel.MEDIA && (nativeBluetoothPlaying || mediaSuppressed || mediaPlaying == false) -> 0f
                requestedChannel in setOf(AudioChannel.PHONE, AudioChannel.ASSISTANT, AudioChannel.RINGTONE) && entry.channel != requestedChannel -> 0f
                entry.channel == AudioChannel.MEDIA && navigation -> DUCKED_VOLUME
                else -> 1f
            }
            runCatching { track.setVolume(focusVolume * local) }
        }
        // Guidance alone overlays the original source; it does not disconnect Bluetooth music.
        publishOwnership(!closed && !externalCall && focusHeld && requestedChannel != null && requestedChannel != AudioChannel.NAVIGATION)
    }

    private fun publishOwnership(value: Boolean) {
        if (ownership == value) return
        ownership = value
        runCatching { onOwnershipChanged(value) }
    }

    private fun AudioChannel.priority() = when (this) {
        AudioChannel.PHONE -> 4
        AudioChannel.RINGTONE -> 3
        AudioChannel.ASSISTANT -> 2
        AudioChannel.MEDIA -> 1
        AudioChannel.NAVIGATION -> 0
    }
    private companion object { const val DUCKED_VOLUME = 0.2f }
}
