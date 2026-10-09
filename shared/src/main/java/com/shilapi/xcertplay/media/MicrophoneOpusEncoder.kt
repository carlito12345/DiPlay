package com.shilapi.xcertplay.media

import java.io.Closeable

/** Encodes 20 ms chunks of 48 kHz mono PCM into raw Opus access units for the CarPlay microphone uplink. */
internal interface MicrophoneOpusEncoder : Closeable {
    /** Short implementation name for diagnostic reports. */
    val implementation: String

    val available: Boolean

    /** Encodes one 20 ms little-endian 16-bit PCM frame and returns the Opus access units it produced. */
    fun encode(pcm: ByteArray): List<ByteArray>
}

internal object MicrophoneOpusEncoders {
    /**
     * Android's MediaCodec Opus encoder exists only from Android 10 (API 29). Wireless CarPlay sends
     * calls and Siri as Opus, so older head units fall back to the bundled software encoder.
     */
    fun create(
        bitrate: Int,
        platform: (Int) -> MicrophoneOpusEncoder = { OpusEncoder(it) },
        software: (Int) -> MicrophoneOpusEncoder = { SoftwareOpusEncoder(it) },
    ): MicrophoneOpusEncoder? {
        val platformEncoder = platform(bitrate)
        if (platformEncoder.available) return platformEncoder
        platformEncoder.close()
        val softwareEncoder = software(bitrate)
        if (softwareEncoder.available) return softwareEncoder
        softwareEncoder.close()
        return null
    }
}
