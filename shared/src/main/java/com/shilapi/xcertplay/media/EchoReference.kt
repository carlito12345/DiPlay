package com.shilapi.xcertplay.media

import kotlin.math.abs
import kotlin.math.sign

/**
 * The far-end call audio as the car's speakers play it, for the microphone's echo canceller.
 *
 * The call renderer appends each PCM block after handing it to its AudioTrack, along with how much
 * audio is still queued ahead of the speaker. That anchors a speaker clock, so the microphone can ask
 * for the samples that were playing over the window it just captured.
 */
internal class EchoReference(val sampleRate: Int, capacityMillis: Int = 2_000) {
    private val ring = ShortArray(sampleRate * capacityMillis / 1000)
    private var written = 0L
    private var anchorSample = NOT_PLAYING
    private var anchorNs = 0L
    private var cursor = NOT_PLAYING
    private var settled = false
    private val errors = LongArray(ERROR_WINDOW)
    private var errorAt = 0
    private var errorCount = 0

    /**
     * [pcm] is 16-bit little-endian, interleaved over [channels]. [pendingFrames] is the audio still
     * queued ahead of the speaker after this block, or null while the track is not playing.
     */
    @Synchronized
    fun append(pcm: ByteArray, offset: Int, length: Int, channels: Int, pendingFrames: Long?, nowNs: Long) {
        val frameBytes = 2 * channels
        for (frame in 0 until length / frameBytes) {
            val base = offset + frame * frameBytes
            var sum = 0
            for (channel in 0 until channels) {
                val at = base + channel * 2
                sum += (pcm[at + 1].toInt() shl 8) or (pcm[at].toInt() and 0xff)
            }
            ring[(written % ring.size).toInt()] = (sum / channels).toShort()
            written++
        }
        if (pendingFrames == null) {
            anchorSample = NOT_PLAYING
        } else {
            anchorSample = written - pendingFrames.coerceIn(0L, written)
            anchorNs = nowNs
        }
    }

    @Synchronized
    fun reset() {
        written = 0
        anchorSample = NOT_PLAYING
        cursor = NOT_PLAYING
    }

    /**
     * Fills [out] with what the speaker played over the window ending at [endNs]; false when that was silence.
     *
     * The speaker clock estimate jitters by the playback head's update granularity (often 5-20 ms), and
     * an adaptive echo filter cannot converge on a reference that jumps by that much. So consecutive reads
     * continue contiguously from the previous window, and the estimate only steers that cursor: one snap
     * once its error has averaged out, a one-sample slew when it slowly drifts apart, and a full resync
     * when playback restarts or slips by more than [RESYNC_MILLIS].
     */
    @Synchronized
    fun read(out: ShortArray, endNs: Long): Boolean {
        if (anchorSample == NOT_PLAYING) {
            cursor = NOT_PLAYING
            out.fill(0)
            return false
        }
        val estimate = anchorSample + Math.floorDiv((endNs - anchorNs) * sampleRate, 1_000_000_000L) - out.size
        val start = steer(estimate)
        cursor = start + out.size
        val oldest = maxOf(0L, written - ring.size)
        var heard = false
        for (i in out.indices) {
            val sample = start + i
            out[i] = if (sample in oldest until written) {
                heard = true
                ring[(sample % ring.size).toInt()]
            } else {
                0
            }
        }
        return heard
    }

    private fun steer(estimate: Long): Long {
        if (cursor == NOT_PLAYING) {
            cursor = estimate
            settled = false
            errorCount = 0
            return cursor
        }
        errors[errorAt] = estimate - cursor
        errorAt = (errorAt + 1) % errors.size
        if (errorCount < errors.size) errorCount++
        if (errorCount == errors.size) {
            val mean = errors.sum() / errors.size
            when {
                abs(mean) > sampleRate * RESYNC_MILLIS / 1000L -> {
                    cursor = estimate
                    settled = false
                    errorCount = 0
                }
                !settled -> {
                    cursor += mean
                    settled = true
                    errorCount = 0
                }
                abs(mean) > sampleRate * SLEW_MILLIS / 1000L -> cursor += mean.sign
            }
        }
        return cursor
    }

    private companion object {
        const val NOT_PLAYING = Long.MIN_VALUE
        const val ERROR_WINDOW = 10
        const val SLEW_MILLIS = 5
        const val RESYNC_MILLIS = 20
    }
}
