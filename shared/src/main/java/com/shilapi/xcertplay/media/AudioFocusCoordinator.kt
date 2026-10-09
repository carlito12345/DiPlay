// carlito | One focus owner for media, guidance, calls and Siri, including microphone-only phases.
package com.shilapi.xcertplay.media

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.shilapi.xcertplay.compat.AudioFocusRequestCompat
import java.io.Closeable

internal class AudioFocusCoordinator(
    context: Context?,
    private val enabled: Boolean,
    private val muteMediaOnTransientLoss: Boolean = true,
    private val report: (String) -> Unit = {},
    private val factoryRouting: Boolean = false,
    private val unifiedMediaOutput: Boolean = false,
    private val onOwnershipChanged: (Boolean) -> Unit = {},
) : Closeable {
    private data class Entry(val channel: AudioChannel, val attributes: AudioAttributes, var appliedVolume: Float = 1f) {
        val music = channel == AudioChannel.MEDIA && attributes.contentType != AudioAttributes.CONTENT_TYPE_SPEECH
    }
    private val manager = context?.getSystemService(AudioManager::class.java)
    private val active = LinkedHashMap<AudioTrack, Entry>()
    private val captures = LinkedHashMap<AudioChannel, Entry>()
    private var request: AudioFocusRequestCompat? = null
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
    // carlito | Speech arbitration follows PCM playback, never a permanently negotiated stream.
    private val speechDeadlines = LinkedHashMap<AudioTrack, Long>()
    private var speechPlaying = false
    private var speechListener: ((Boolean) -> Unit)? = null
    private val vehicleRouting = factoryRouting || unifiedMediaOutput
    private var currentListener = listenerFor(requestGeneration)
    internal val listener: AudioManager.OnAudioFocusChangeListener get() = currentListener

    private fun listenerFor(generation: Int) = AudioManager.OnAudioFocusChangeListener { change ->
        onFocusChanged(generation, change)
    }

    fun onExternalFocusChange(change: Int) {
        val current = synchronized(this) { currentListener }
        current.onAudioFocusChange(change)
    }

    @Synchronized private fun onFocusChanged(generation: Int, change: Int) {
        if (closed || !enabled || generation != requestGeneration || request == null || active.isEmpty() && captures.isEmpty()) {
            // carlito | Explain discarded callbacks without changing the current focus owner.
            runCatching { report("Audio: focus change=$change dropped stale=${generation != requestGeneration} noRequest=${request == null} activeTracks=${active.size}") }
            return
        }
        runCatching { report("Audio: focus change=$change activeTracks=${active.size}") }
        if (!vehicleRouting) {
            when (change) {
                AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> focusVolume = DUCKED_VOLUME
                AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> if (muteMediaOnTransientLoss) focusVolume = 0f
                AudioManager.AUDIOFOCUS_GAIN -> { focusHeld = true; focusVolume = 1f }
            }
            applyVolumes()
            return
        }
        when (change) {
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> focusVolume = DUCKED_VOLUME
            AudioManager.AUDIOFOCUS_GAIN -> { focusHeld = true; focusVolume = 1f }
            AudioManager.AUDIOFOCUS_LOSS, AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                focusHeld = false
                if (change == AudioManager.AUDIOFOCUS_LOSS || muteMediaOnTransientLoss) focusVolume = 0f
                if (change == AudioManager.AUDIOFOCUS_LOSS) mediaSuppressed = true
            }
        }
        applyVolumes()
    }

    @Synchronized fun acquire(track: AudioTrack, channel: AudioChannel, attributes: AudioAttributes) {
        if (closed) return
        if (!vehicleRouting && channel == AudioChannel.NAVIGATION) return
        active[track] = Entry(channel, attributes)
        if (active.getValue(track).music) mediaAttributes = attributes
        refreshRequest()
    }

    @Synchronized fun release(track: AudioTrack) {
        speechDeadlines.remove(track)
        refreshSpeechPlayback()
        if (active.remove(track) != null) {
            if (!vehicleRouting && active.isEmpty()) mediaAttributes = null
            refreshRequest()
        }
    }

    @Synchronized fun setSpeechPlaybackListener(listener: ((Boolean) -> Unit)?) {
        speechListener = listener
    }

    @Synchronized fun onSpeechPlayback(track: AudioTrack, bufferedMillis: Long) {
        if (closed) return
        speechDeadlines[track] = SystemClock.elapsedRealtime() + bufferedMillis.coerceAtLeast(0L) + 900L
        refreshSpeechPlayback()
    }

    @Synchronized fun refreshSpeechPlayback() {
        if (closed) return
        val now = SystemClock.elapsedRealtime()
        speechDeadlines.entries.removeAll { it.value <= now }
        val playing = speechDeadlines.isNotEmpty()
        if (playing == speechPlaying) return
        speechPlaying = playing
        applyVolumes()
        runCatching { report("Audio: priority speech playing=$playing") }
        runCatching { speechListener?.invoke(playing) }
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
        if (assistant) addCapture(AudioChannel.ASSISTANT,
            if (Build.VERSION.SDK_INT >= 26) AudioAttributes.USAGE_ASSISTANT else AudioAttributes.USAGE_MEDIA)
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
        speechDeadlines.clear(); speechPlaying = false; speechListener = null
        publishOwnership(false)
    }

    private fun abandonRequest() {
        requestGeneration++
        request?.let { current -> runCatching { manager?.let(current::abandon) } }
        request = null; requestedChannel = null; requestedAttributes = null; focusHeld = false; focusVolume = 0f
    }

    private fun refreshRequest() {
        if (closed) return
        if (!enabled || manager == null) { applyVolumes(); return }
        if (externalCall) { applyVolumes(); return }
        if (!vehicleRouting) {
            refreshStandardRequest()
            return
        }
        val entries = if (unifiedMediaOutput) captures.values + active.values else active.values + captures.values
        val primary = entries.filter {
            it.channel != AudioChannel.NAVIGATION && (!it.music || !nativeBluetoothPlaying && !mediaSuppressed && mediaPlaying != false)
        }.maxByOrNull { it.channel.priority() * 2 + if (it.channel == AudioChannel.MEDIA && !it.music) 1 else 0 }
            ?: mediaAttributes?.takeIf { !nativeBluetoothPlaying && !mediaSuppressed && mediaPlaying != false }?.let { Entry(AudioChannel.MEDIA, it) }
            ?: active.values.firstOrNull { it.channel == AudioChannel.NAVIGATION }
        if (primary == null) { abandonRequest(); applyVolumes(); return }
        if (request != null && requestedChannel == primary.channel && requestedAttributes == primary.attributes) { applyVolumes(); return }
        abandonRequest()
        val generation = ++requestGeneration
        val gain = when (primary.channel) {
            AudioChannel.MEDIA -> if (primary.music) AudioManager.AUDIOFOCUS_GAIN else AudioManager.AUDIOFOCUS_GAIN_TRANSIENT
            AudioChannel.PHONE, AudioChannel.RINGTONE -> AudioManager.AUDIOFOCUS_GAIN_TRANSIENT
            AudioChannel.ASSISTANT -> AudioManager.AUDIOFOCUS_GAIN_TRANSIENT
            AudioChannel.NAVIGATION -> if (factoryRouting) AudioManager.AUDIOFOCUS_GAIN_TRANSIENT
                else AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK
        }
        currentListener = listenerFor(generation)
        request = AudioFocusRequestCompat(gain, primary.attributes, currentListener, Handler(Looper.getMainLooper()))
        requestedChannel = primary.channel
        requestedAttributes = primary.attributes
        requestCurrentFocus()
    }

    private fun requestCurrentFocus() {
        if (externalCall || closed) return
        val current = request ?: return
        val result = runCatching { manager?.let(current::request) }.getOrDefault(AudioManager.AUDIOFOCUS_REQUEST_FAILED)
        focusHeld = result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        focusVolume = if (focusHeld) 1f else 0f
        applyVolumes()
        runCatching { report("Audio: focus requested channel=$requestedChannel granted=$result activeTracks=${active.size}") }
    }

    private fun applyVolumes() {
        if (!vehicleRouting) {
            active.forEach { (track, entry) ->
                val volume = if (speechPlaying && entry.music) 0f else if (enabled && manager != null && entry.music) focusVolume else 1f
                if (entry.appliedVolume != volume) {
                    val applied = runCatching { track.setStereoVolume(volume, volume) == AudioTrack.SUCCESS }.getOrDefault(false)
                    if (applied) entry.appliedVolume = volume
                }
            }
            return
        }
        val navigation = active.values.any { it.channel == AudioChannel.NAVIGATION }
        active.forEach { (track, entry) ->
            if (!enabled || manager == null) {
                runCatching { track.setVolume(if (entry.music && (nativeBluetoothPlaying || speechPlaying)) 0f else 1f) }
                return@forEach
            }
            val local = when {
                externalCall -> 0f
                entry.music && (speechPlaying || nativeBluetoothPlaying || mediaSuppressed || mediaPlaying == false) -> 0f
                requestedChannel in setOf(AudioChannel.PHONE, AudioChannel.ASSISTANT, AudioChannel.RINGTONE) && entry.channel != requestedChannel -> 0f
                entry.music && navigation -> DUCKED_VOLUME
                else -> 1f
            }
            runCatching { track.setVolume(focusVolume * local) }
        }
        // Guidance alone overlays the original source; it does not disconnect Bluetooth music.
        publishOwnership(!closed && !externalCall && focusHeld && requestedChannel != null && requestedChannel != AudioChannel.NAVIGATION)
    }

    /** Upstream focus policy remains the default; explicit vehicle routing keeps its ownership policy. */
    private fun refreshStandardRequest() {
        val primary = active.values.maxByOrNull { entry -> when (entry.channel) {
            AudioChannel.MEDIA -> 4
            AudioChannel.PHONE, AudioChannel.RINGTONE -> 3
            AudioChannel.ASSISTANT -> 2
            AudioChannel.NAVIGATION -> 0
        } }
        if (primary == null) { abandonRequest(); focusVolume = 1f; return }
        if (request != null && requestedChannel == primary.channel) { applyVolumes(); return }
        // A refused replacement must retain the existing transient mute until a real gain arrives.
        val previousVolume = focusVolume
        abandonRequest()
        val gain = when (primary.channel) {
            AudioChannel.MEDIA -> AudioManager.AUDIOFOCUS_GAIN
            AudioChannel.PHONE, AudioChannel.RINGTONE -> AudioManager.AUDIOFOCUS_GAIN_TRANSIENT
            AudioChannel.ASSISTANT -> AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK
            AudioChannel.NAVIGATION -> return
        }
        currentListener = listenerFor(requestGeneration)
        val next = AudioFocusRequestCompat(gain, primary.attributes, currentListener, Handler(Looper.getMainLooper()))
        request = next; requestedChannel = primary.channel
        val result = manager?.let(next::request)
        focusHeld = result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        focusVolume = if (focusHeld) 1f else previousVolume
        applyVolumes()
        runCatching { report("Audio: focus requested channel=$requestedChannel granted=$result activeTracks=${active.size}") }
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
