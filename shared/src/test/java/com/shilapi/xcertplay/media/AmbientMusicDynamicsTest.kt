package com.shilapi.xcertplay.media

import org.junit.Assert.*
import org.junit.Test

class AmbientMusicDynamicsTest {
    @Test fun recentMusicalPeakReachesUserCeilingAndSilenceKeepsFloor() {
        val brightness = AmbientMusicBrightness()
        assertEquals(1, brightness.level(0.0, 0, 6))
        assertEquals(6, brightness.level(0.2, 50, 6))
        assertTrue(brightness.level(0.03, 100, 6) < 6)
        assertEquals(1, brightness.level(0.0, 500, 6))
        assertEquals(3, brightness.level(0.4, 600, 3))
    }
    @Test fun veryWeakNoiseDoesNotNormalizeItselfToMaximumAndPeakDecays() {
        val brightness = AmbientMusicBrightness()
        assertEquals(1, brightness.level(0.001, 0, 6))
        assertEquals(6, brightness.level(0.5, 50, 6))
        assertTrue(brightness.level(0.05, 100, 6) < 6)
        assertEquals(6, brightness.level(0.05, 20_000, 6))
    }
    @Test fun samePeakFadesFasterAcrossThreePresetsAndEventuallyReachesFloor() {
        val levels = AmbientColorSpeed.entries.map { speed ->
            val brightness = AmbientMusicBrightness(speed)
            assertEquals(6, brightness.level(0.2, 0, 6))
            val at200 = brightness.level(0.0, 200, 6)
            assertEquals(1, brightness.level(0.0, 5000, 6))
            at200
        }
        assertEquals(listOf(4, 3, 1), levels)
    }
    @Test fun attackTimeDiffersButNeverExceedsCeilingOrAcceptsNanEnergy() {
        val rise = AmbientColorSpeed.entries.map { speed ->
            val brightness = AmbientMusicBrightness(speed)
            assertEquals(1, brightness.level(Double.NaN, 0, 6))
            val value = brightness.level(0.2, 50, 6)
            for (time in 100L..1000L step 50) assertTrue(brightness.level(0.8, time, 2) in 1..2)
            value
        }
        assertEquals(listOf(3, 4, 6), rise)
    }
    @Test fun zeroElapsedDoesNotAdvanceReleaseAndRepeatedSamplesDoNotMakeFakeTime() {
        val brightness = AmbientMusicBrightness(AmbientColorSpeed.STANDARD)
        assertEquals(6, brightness.level(0.2, 100, 6))
        repeat(10) { assertEquals(6, brightness.level(0.0, 100, 6)) }
        assertEquals(3, brightness.level(0.0, 300, 6))
    }
    @Test fun brightnessSpeedDoesNotChangeProductionColorBeatSequence() {
        val sequences = AmbientColorSpeed.entries.map { speed ->
            val brightness = AmbientMusicBrightness(speed)
            val detector = ambientMusicColorDetector(2, listOf(2, 8, 22))
            (0L..4000L step 50).map { time ->
                val rms = if (time % 500 == 0L) 0.5 else 0.01
                brightness.level(rms, time, 6)
                detector.color(AmbientColorMode.BEAT, time, rms)
            }
        }
        assertEquals(sequences[0], sequences[1])
        assertEquals(sequences[1], sequences[2])
        assertTrue(sequences[0].distinct().size > 1)
    }
    @Test fun fiftyMillisecondDetectionDoesNotSubmitHardwareAtTwentyHz() {
        val cadence = AmbientMusicApplyCadence()
        val allowed = (0L..1000L step 50).filter { cadence.trySubmit(it) }
        assertEquals(listOf(0L, 200L, 400L, 600L, 800L, 1000L), allowed)
        assertFalse(cadence.trySubmit(1000))
        cadence.reset()
        assertTrue(cadence.trySubmit(1000))
    }
}
