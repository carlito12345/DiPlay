package com.shilapi.xcertplay.media

import kotlin.math.PI
import kotlin.math.sqrt

/** Two one-pole low-passes form a 40–180Hz band estimate at the actual PCM sample rate. */
internal class AmbientMusicBassAnalyzer(sampleRate: Int, private val channels: Int) {
    private val alpha180 = 1.0 - kotlin.math.exp(-2.0 * PI * 180 / sampleRate.coerceAtLeast(1))
    private val alpha40 = 1.0 - kotlin.math.exp(-2.0 * PI * 40 / sampleRate.coerceAtLeast(1))
    private var low180 = 0.0
    private var low40 = 0.0
    fun rms(data: ByteArray, offset: Int, length: Int): Double {
        val count = channels.coerceAtLeast(1)
        val frameBytes = count * 2
        if (offset < 0 || length < frameBytes || offset > data.size - length) return 0.0
        var square = 0.0
        var frames = 0
        var i = offset
        while (i + frameBytes <= offset + length) {
            var sample = 0.0
            for (channel in 0 until count) {
                val p = i + channel * 2
                sample += ((data[p].toInt() and 255) or (data[p + 1].toInt() shl 8)).toShort().toDouble() / 32768.0
            }
            sample /= count
            low180 += alpha180 * (sample - low180)
            low40 += alpha40 * (sample - low40)
            val band = low180 - low40
            square += band * band
            frames++
            i += frameBytes
        }
        return if (frames == 0) 0.0 else sqrt(square / frames)
    }
}
