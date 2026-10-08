package com.shilapi.xcertplay.media

import android.media.AudioFormat as AndroidAudioFormat
import android.media.AudioRecord
import android.media.AudioManager
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AudioEffect
import android.media.audiofx.NoiseSuppressor
import android.util.Log
import com.shilapi.xcertplay.airplay.AudioCodecKind
import com.shilapi.xcertplay.airplay.MicrophoneConfig
import com.shilapi.xcertplay.airplay.MicrophoneCounters
import com.shilapi.xcertplay.airplay.MicrophonePacketizer
import java.io.Closeable
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.Inet6Address
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Captures one PCM microphone stream and sends it back to the phone as sealed CarPlay RTP.
 *
 * The recorder runs only while the matching audio stream is active, so callers start this after
 * the first downlink audio packet and close it on stream teardown.
 */
internal class MicrophoneUplink(
    private val config: MicrophoneConfig,
    private val onDiagnostic: (String) -> Unit = {},
    private val factorySource: Int? = null,
    // carlito | Per-role input routing and counters survive focus-related capture restarts.
    private val preferredInput: AudioOutputDevice? = null,
    private val audioManager: AudioManager? = null,
    private val counters: MicrophoneCounters = MicrophoneCounters(),
) : Closeable {
    private val running = AtomicBoolean(false)
    private val stats = MicrophoneCaptureStats(config, report = { message ->
        Log.i(TAG, message)
        onDiagnostic(message)
    })
    @Volatile private var recorder: AudioRecord? = null
    @Volatile private var socket: DatagramSocket? = null
    @Volatile private var opusEncoder: OpusEncoder? = null
    @Volatile private var effects: List<AudioEffect> = emptyList()
    private var thread: Thread? = null

    fun start(): Boolean {
        if (!running.compareAndSet(false, true)) return true

        val channelMask = if (config.channels >= 2) {
            AndroidAudioFormat.CHANNEL_IN_STEREO
        } else {
            AndroidAudioFormat.CHANNEL_IN_MONO
        }
        val minBuffer = AudioRecord.getMinBufferSize(
            config.sampleRate,
            channelMask,
            AndroidAudioFormat.ENCODING_PCM_16BIT,
        )
        if (minBuffer <= 0) {
            Log.w(TAG, "microphone unavailable rate=${config.sampleRate} channels=${config.channels}")
            stats.failure(MicrophoneFailureStage.MIN_BUFFER, code = minBuffer)
            running.set(false)
            return false
        }

        val source = when {
            isPhoneAudio(config.audioType) -> MediaRecorder.AudioSource.VOICE_COMMUNICATION
            config.audioType.equals("speechrecognition", true) -> MediaRecorder.AudioSource.VOICE_RECOGNITION
            else -> MediaRecorder.AudioSource.MIC
        }
        val nextEncoder = if (config.codec == AudioCodecKind.OPUS) {
            OpusEncoder(config.bitrate ?: 48_000).takeIf { it.available }
        } else {
            null
        }
        if (config.codec == AudioCodecKind.OPUS && nextEncoder == null) {
            Log.w(TAG, "microphone Opus encoder is unavailable")
            stats.failure(MicrophoneFailureStage.ENCODER)
            running.set(false)
            return false
        }
        val bufferSize = maxOf(minBuffer * 2, config.frameBytes * 4)
        val nextRecorder = listOfNotNull(factorySource, source).distinct().firstNotNullOfOrNull { candidate ->
            var record: AudioRecord? = null
            var stage = MicrophoneFailureStage.RECORDER_CREATION
            try {
                val built = AudioRecord.Builder().setAudioSource(candidate)
                    .setAudioFormat(AndroidAudioFormat.Builder()
                        .setEncoding(AndroidAudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(config.sampleRate)
                        .setChannelMask(channelMask).build())
                    .setBufferSizeInBytes(bufferSize).build()
                record = built
                stage = MicrophoneFailureStage.RECORDER_INITIALIZATION
                if (built.state != AudioRecord.STATE_INITIALIZED) {
                    stats.failure(stage, code = built.state)
                    built.release()
                    null
                } else {
                    stage = MicrophoneFailureStage.RECORDING
                    applyInputDevice(built)
                    if (isPhoneAudio(config.audioType)) effects = voiceEffects(built.audioSessionId)
                    built.startRecording()
                    check(built.recordingState == AudioRecord.RECORDSTATE_RECORDING) { "Microphone did not start recording" }
                    built
                }
            } catch (error: Exception) {
                stats.failure(stage, error)
                val rejectedEffects = effects
                effects = emptyList()
                rejectedEffects.forEach(::releaseEffect)
                runCatching { record?.release() }
                Log.w(TAG, "microphone source $candidate unavailable", error)
                null
            }
        }
        if (nextRecorder == null) {
            Log.w(TAG, "microphone recorder failed to initialize")
            nextEncoder?.close()
            running.set(false)
            return false
        }

        val nextSocket = try {
            DatagramSocket(null).apply {
                reuseAddress = true
                // carlito | Match the negotiated peer: IPv4 capture must not require an IPv6 socket.
                val wildcard = if (config.host is Inet6Address) "::" else "0.0.0.0"
                bind(InetSocketAddress(InetAddress.getByName(wildcard), 0))
            }
        } catch (error: Exception) {
            Log.e(TAG, "microphone socket creation failed", error)
            stats.failure(MicrophoneFailureStage.SOCKET_CREATION, error)
            val failedEffects = effects
            effects = emptyList()
            failedEffects.forEach(::releaseEffect)
            nextRecorder.release()
            nextEncoder?.close()
            running.set(false)
            return false
        }

        recorder = nextRecorder
        socket = nextSocket
        opusEncoder = nextEncoder
        return try {
            stats.started(routeType(nextRecorder))
            thread = Thread({ capture(nextRecorder, nextSocket) }, "carplay-mic").apply {
                isDaemon = true
                start()
            }
            true
        } catch (error: Exception) {
            Log.e(TAG, "microphone recording failed", error)
            stats.failure(MicrophoneFailureStage.RECORDING, error)
            release()
            false
        }
    }

    private fun voiceEffects(sessionId: Int): List<AudioEffect> = listOfNotNull(
        enabledEffect("AEC") {
            if (AcousticEchoCanceler.isAvailable()) AcousticEchoCanceler.create(sessionId) else null
        },
        enabledEffect("NS") {
            if (NoiseSuppressor.isAvailable()) NoiseSuppressor.create(sessionId) else null
        },
    )

    // Advertised effects may still fail to initialize on a vendor ROM. Keep recording without them.
    private fun enabledEffect(name: String, create: () -> AudioEffect?): AudioEffect? {
        var effect: AudioEffect? = null
        try {
            effect = create()
            if (effect != null) {
                val status = effect.setEnabled(true)
                if (status == AudioEffect.SUCCESS && effect.enabled) {
                    Log.i(TAG, "microphone effect=$name enabled=true")
                    return effect
                }
                Log.w(TAG, "microphone effect=$name could not be enabled status=$status")
            } else {
                Log.i(TAG, "microphone effect=$name unavailable")
            }
        } catch (error: RuntimeException) {
            Log.w(TAG, "microphone effect=$name unavailable; continuing without it", error)
        }
        effect?.let(::releaseEffect)
        return null
    }

    private fun releaseEffect(effect: AudioEffect) {
        try {
            effect.release()
        } catch (error: RuntimeException) {
            Log.w(TAG, "microphone effect release failed", error)
        }
    }

    private fun capture(recorder: AudioRecord, socket: DatagramSocket) {
        val frame = ByteArray(config.frameBytes)
        val readBuffer = ByteArray(maxOf(frame.size, MIN_READ_BYTES))
        val routeInfo = { routeType(recorder) }
        var filled = 0
        try {
            while (running.get()) {
                stats.reading()
                val count = recorder.read(readBuffer, 0, readBuffer.size, AudioRecord.READ_BLOCKING)
                stats.read(count)
                if (count < 0) {
                    if (running.get()) {
                        Log.e(TAG, "microphone read failed code=$count")
                        stats.failure(MicrophoneFailureStage.READ, code = count)
                    }
                    return
                }
                if (count == 0) {
                    stats.flush(routeType = routeInfo)
                    continue
                }
                var offset = 0
                while (offset < count && running.get()) {
                    val copied = minOf(frame.size - filled, count - offset)
                    readBuffer.copyInto(frame, filled, offset, offset + copied)
                    filled += copied
                    offset += copied
                    if (filled == frame.size) {
                        sendFrame(socket, counters, frame)
                        filled = 0
                    }
                }
                stats.flush(routeType = routeInfo)
            }
        } catch (error: Exception) {
            if (running.get()) {
                Log.e(TAG, "microphone capture failed", error)
                stats.failure(MicrophoneFailureStage.CAPTURE, error)
            }
        } finally {
            stats.flush(ended = true, routeType = routeInfo)
            running.set(false)
            release()
        }
    }

    private fun sendFrame(socket: DatagramSocket, counters: MicrophoneCounters, frame: ByteArray) {
        val bodies = if (config.codec == AudioCodecKind.OPUS) {
            opusEncoder?.encode(frame).orEmpty()
        } else {
            listOf(MicrophonePacketizer.toWirePcm(frame))
        }
        stats.encoded(bodies.size, if (bodies.isEmpty()) 1 else bodies.count { it.isEmpty() })
        bodies.forEach { body ->
            sendPacket(
                socket = socket,
                counters = counters,
                body = body,
                samples = config.rtpSamplesPerPacket,
            )
        }
    }

    private fun sendPacket(
        socket: DatagramSocket,
        counters: MicrophoneCounters,
        body: ByteArray,
        samples: Int,
    ) {
        val packet = synchronized(counters) { MicrophonePacketizer.sealPacket(
            key = config.key,
            payloadType = config.payloadType,
            counters = counters,
            body = body,
            samples = samples,
        ) }
        try {
            socket.send(DatagramPacket(packet, packet.size, config.host, config.port))
            stats.sent()
        } catch (error: Exception) {
            stats.sendFailed()
            if (running.get()) throw error
        }
    }

    private fun routeType(recorder: AudioRecord): Int? = runCatching { recorder.routedDevice?.type }.getOrNull()

    fun refreshInputDevice() { recorder?.let(::applyInputDevice) }

    private fun applyInputDevice(record: AudioRecord) {
        runCatching {
            val device = preferredInput?.resolveInput(audioManager)
            val accepted = record.setPreferredDevice(device)
            if (!accepted) record.setPreferredDevice(null)
            if (preferredInput != null) onDiagnostic("Audio: microphone preferred=${device?.id} accepted=$accepted")
        }.onFailure { runCatching { record.setPreferredDevice(null) } }
    }

    override fun close() {
        if (!running.compareAndSet(true, false)) {
            release()
            return
        }
        try {
            recorder?.stop()
        } catch (_: Exception) {
            // Best effort; release below is authoritative.
        }
        try {
            socket?.close()
        } catch (_: Exception) {
            // Best effort.
        }
        thread?.let { worker ->
            try {
                worker.join(CLOSE_JOIN_MILLIS)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
            if (worker.isAlive) worker.interrupt()
        }
        release()
    }

    @Synchronized
    private fun release() {
        running.set(false)
        val currentEffects = effects
        effects = emptyList()
        currentEffects.forEach(::releaseEffect)
        val currentRecorder = recorder
        recorder = null
        try {
            currentRecorder?.release()
        } catch (_: Exception) {
            // Best effort.
        }
        val currentSocket = socket
        socket = null
        try {
            currentSocket?.close()
        } catch (_: Exception) {
            // Best effort.
        }
        val currentEncoder = opusEncoder
        opusEncoder = null
        currentEncoder?.close()
    }

    private companion object {
        const val TAG = "xcertplay-usb"
        const val MIN_READ_BYTES = 2_048
        const val CLOSE_JOIN_MILLIS = 500L
    }
}
