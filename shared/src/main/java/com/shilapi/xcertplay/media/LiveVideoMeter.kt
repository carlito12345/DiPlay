package com.shilapi.xcertplay.media

/** Running totals of one decoder: frames received and shown, and the queue-to-output time of shown frames. */
data class LiveVideoCounters(
    val received: Long,
    val rendered: Long,
    val decodeNanos: Long,
    val decodeSamples: Long,
)

/** What the live FPS counter shows for the last interval; [decodeMillis] is -1 when no frame was timed. */
data class LiveVideoReading(val shownFps: Int, val receivedFps: Int, val decodeMillis: Int)

/**
 * Turns successive [LiveVideoCounters] into per-interval rates. The first sample, and one taken after the
 * totals went backwards (a new decoder), only sets the baseline.
 */
class LiveVideoMeter {
    private var last: LiveVideoCounters? = null
    private var lastNanos = 0L

    fun update(counters: LiveVideoCounters?, nowNanos: Long): LiveVideoReading? {
        val previous = last
        val elapsed = nowNanos - lastNanos
        last = counters
        lastNanos = nowNanos
        if (counters == null || previous == null || elapsed <= 0 ||
            counters.received < previous.received || counters.rendered < previous.rendered) return null
        val seconds = elapsed / 1e9
        val samples = counters.decodeSamples - previous.decodeSamples
        val decode = if (samples > 0) ((counters.decodeNanos - previous.decodeNanos) / samples / 1_000_000).toInt() else -1
        return LiveVideoReading(
            shownFps = Math.round((counters.rendered - previous.rendered) / seconds).toInt(),
            receivedFps = Math.round((counters.received - previous.received) / seconds).toInt(),
            decodeMillis = decode,
        )
    }

    fun reset() {
        last = null
        lastNanos = 0L
    }
}
