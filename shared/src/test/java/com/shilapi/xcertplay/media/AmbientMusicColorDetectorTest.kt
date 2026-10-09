package com.shilapi.xcertplay.media

import org.junit.Assert.*
import org.junit.Test

class AmbientMusicColorDetectorTest {
    @Test fun constantEnergyDoesNotInventPeriodicBeatsOrTempo() {
        for (mode in listOf(AmbientColorMode.BEAT, AmbientColorMode.TEMPO, AmbientColorMode.BASS, AmbientColorMode.SMART)) {
            val detector = AmbientMusicColorDetector(speed = AmbientColorSpeed.FAST)
            val first = detector.color(mode, 0, 0.3)
            for (time in 100L..10_000L step 100) assertEquals(first, detector.color(mode, time, 0.3))
            assertNull(detector.estimatedBpm)
        }
    }
    @Test fun silenceNeverChangesColor() {
        for (mode in AmbientColorMode.entries) {
            val detector = AmbientMusicColorDetector(13, speed = AmbientColorSpeed.FAST)
            for (time in 0L..10_000L step 100) assertEquals(13, detector.color(mode, time, 0.0))
        }
    }
    @Test fun energyMapsStrongAndQuietSoundWithHysteresis() {
        val detector = AmbientMusicColorDetector(speed = AmbientColorSpeed.FAST)
        val quiet = detector.color(AmbientColorMode.ENERGY, 0, 0.01)
        val strong = detector.color(AmbientColorMode.ENERGY, 400, 0.81)
        assertTrue(strong > quiet + 15)
        assertEquals(strong, detector.color(AmbientColorMode.ENERGY, 400, 0.8))
        assertEquals(31, detector.color(AmbientColorMode.ENERGY, 600, 1.0))
        assertTrue(detector.color(AmbientColorMode.ENERGY, 800, 0.01) < 8)
    }
    @Test fun beatRequiresEnergyRiseAndHonorsRefractory() {
        val detector = AmbientMusicColorDetector(31, speed = AmbientColorSpeed.FAST)
        assertEquals(1, detector.color(AmbientColorMode.BEAT, 0, 0.5))
        detector.color(AmbientColorMode.BEAT, 100, 0.01)
        assertEquals(1, detector.color(AmbientColorMode.BEAT, 200, 0.6))
        detector.color(AmbientColorMode.BEAT, 300, 0.01)
        assertEquals(2, detector.color(AmbientColorMode.BEAT, 400, 0.6))
    }
    @Test fun tempoFollowsMeasuredSpeedAfterThreeStableIntervals() {
        for (period in listOf(400L, 500L, 1000L)) {
            val detector = AmbientMusicColorDetector(speed = AmbientColorSpeed.FAST)
            for (time in 0L..period * 3 step 100) {
                detector.color(AmbientColorMode.TEMPO, time, if (time % period == 0L) 0.5 else 0.01)
                if (time < period * 3) assertNull(detector.estimatedBpm)
            }
            assertEquals(60_000.0 / period, detector.estimatedBpm!!, 0.01)
            val before = detector.color(AmbientColorMode.TEMPO, period * 4 - 1, 0.01)
            val predicted = detector.color(AmbientColorMode.TEMPO, period * 4, 0.01)
            assertNotEquals(before, predicted)
            detector.color(AmbientColorMode.TEMPO, period * 7, 0.01)
            assertNull(detector.estimatedBpm)
        }
    }
    @Test fun unstableIntervalsAndSilentGapDiscardTempo() {
        val detector = AmbientMusicColorDetector(speed = AmbientColorSpeed.FAST)
        for (time in 0L..2200L step 100) {
            detector.color(AmbientColorMode.TEMPO, time, if (time in listOf(0L, 400L, 1100L, 1600L, 2200L)) 0.5 else 0.01)
        }
        assertNull(detector.estimatedBpm)
        detector.color(AmbientColorMode.TEMPO, 2400, 0.0)
        assertNull(detector.estimatedBpm)
    }
}
