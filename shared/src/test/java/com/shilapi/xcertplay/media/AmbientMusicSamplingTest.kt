package com.shilapi.xcertplay.media

import org.junit.Assert.*
import org.junit.Test

/** Uses played 20ms segments and the controller's real poll granularity, not ideal beat timestamps. */
class AmbientMusicSamplingTest {
    private fun estimate(pollMillis: Long): Double? {
        val envelope = AmbientMusicEnvelope()
        val detector = AmbientMusicColorDetector(speed = AmbientColorSpeed.FAST)
        for (time in 0L..6_000L step 20) {
            val pulse = time >= 100 && (time - 100) % 500 == 0L
            envelope.append(time * 48, 20 * 48L, if (pulse) 0.5 else 0.01)
        }
        for (time in pollMillis..6_000L step pollMillis) {
            detector.color(AmbientColorMode.TEMPO, time, envelope.playedWindowRms((time * 48).toInt(), true))
        }
        return detector.estimatedBpm
    }

    @Test fun current200msPollingCannotLockStable120BpmBetweenPolls() {
        assertNull(estimate(200))
    }

    @Test fun separate50msDetectionLocksSame120BpmSignal() {
        assertEquals(120.0, estimate(50)!!, 0.01)
    }
}
