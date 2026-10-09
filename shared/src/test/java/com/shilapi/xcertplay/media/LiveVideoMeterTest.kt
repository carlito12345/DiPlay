package com.shilapi.xcertplay.media

import org.junit.Assert.*
import org.junit.Test

class LiveVideoMeterTest {
    private val second = 1_000_000_000L

    @Test fun ratesComeFromTheDifferenceBetweenTwoSamples() {
        val meter = LiveVideoMeter()
        assertNull("the first sample is the baseline", meter.update(LiveVideoCounters(100, 90, 900_000_000, 90), 10 * second))
        val reading = meter.update(LiveVideoCounters(130, 119, 1_190_000_000, 119), 10 * second + second / 2)
        assertEquals(LiveVideoReading(shownFps = 58, receivedFps = 60, decodeMillis = 10), reading)
    }

    @Test fun anIntervalWithoutShownFramesHasNoDecodeTime() {
        val meter = LiveVideoMeter()
        meter.update(LiveVideoCounters(10, 10, 100_000_000, 10), second)
        assertEquals(LiveVideoReading(0, 0, -1), meter.update(LiveVideoCounters(10, 10, 100_000_000, 10), 2 * second))
    }

    @Test fun aNewDecoderOrNoDecoderRestartsTheBaseline() {
        val meter = LiveVideoMeter()
        meter.update(LiveVideoCounters(500, 500, 0, 0), second)
        assertNull("totals went backwards", meter.update(LiveVideoCounters(5, 5, 0, 0), 2 * second))
        assertNotNull(meter.update(LiveVideoCounters(35, 35, 0, 0), 3 * second))
        assertNull(meter.update(null, 4 * second))
        assertNull(meter.update(LiveVideoCounters(40, 40, 0, 0), 5 * second))
    }
}
