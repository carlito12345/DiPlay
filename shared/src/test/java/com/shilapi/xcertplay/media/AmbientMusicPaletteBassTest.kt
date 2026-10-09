package com.shilapi.xcertplay.media

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

class AmbientMusicPaletteBassTest {
    @Test fun analysisLeavesSuccessfulPcmBytesUnchangedAndHonorsTheirSlice() {
        val pcm = tone(100.0)
        val original = pcm.copyOf()
        assertTrue(AmbientMusicEnvelope.pcmRms(pcm, 200, 4096) > 0.0)
        assertTrue(AmbientMusicBassAnalyzer(48_000, 2).rms(pcm, 200, 4096) > 0.0)
        assertArrayEquals(original, pcm)
        assertEquals(0.0, AmbientMusicEnvelope.pcmRms(pcm, pcm.size - 1, 4), 0.0)
        assertEquals(0.0, AmbientMusicBassAnalyzer(48_000, 2).rms(pcm, pcm.size - 1, 4), 0.0)
    }

    private fun tone(hertz: Double): ByteArray {
        val bytes = ByteArray(48_000 * 4)
        for (frame in 0 until 48_000) {
            val sample = (sin(2 * PI * hertz * frame / 48_000) * 16_000).toInt()
            for (channel in 0..1) {
                val offset = frame * 4 + channel * 2
                bytes[offset] = sample.toByte(); bytes[offset + 1] = (sample shr 8).toByte()
            }
        }
        return bytes
    }
    @Test fun actual48kBandRespondsMoreTo100HzThanOneKilohertz() {
        val bass = AmbientMusicBassAnalyzer(48_000, 2)
        val treble = AmbientMusicBassAnalyzer(48_000, 2)
        val low = tone(100.0); val high = tone(1000.0)
        bass.rms(low, 0, low.size); treble.rms(high, 0, high.size)
        assertTrue(bass.rms(low, 0, low.size) > treble.rms(high, 0, high.size) * 3)
        assertEquals(0.0, AmbientMusicBassAnalyzer(48_000, 2).rms(ByteArray(4096), 0, 4096), 0.0)
    }
    @Test fun bassWindowNeverReadsFutureFrames() {
        val envelope = AmbientMusicEnvelope()
        envelope.append(0, 100, 0.01, 0.0)
        envelope.append(100, 100, 0.5, 0.3)
        assertEquals(0.0, envelope.playedWindow(50, true).bassRms, 0.0)
        assertEquals(0.3, envelope.playedWindow(110, true).bassRms, 0.0)
        assertEquals(0.0, envelope.playedWindow(150, false).bassRms, 0.0)
    }
    @Test fun speedCountsRealBeatsAndNeverLeavesSelectedColors() {
        for (speed in AmbientColorSpeed.entries) {
            val detector = AmbientMusicColorDetector(2, listOf(2, 8, 22), speed)
            var color = 2
            for (beat in 0..7) {
                color = detector.color(AmbientColorMode.BEAT, beat * 500L, 0.5)
                assertTrue(color in listOf(2, 8, 22))
                detector.color(AmbientColorMode.BEAT, beat * 500L + 200, 0.01)
            }
            assertEquals(listOf(2, 8, 22)[(8 / speed.beatsPerColor) % 3], color)
        }
    }
    @Test fun energyChangesOnlyWhenTargetChangesAndSpeedDeadlinePasses() {
        for (speed in AmbientColorSpeed.entries) {
            val detector = AmbientMusicColorDetector(2, listOf(2, 8, 22), speed)
            assertEquals(22, detector.color(AmbientColorMode.ENERGY, 0, 1.0))
            assertEquals(22, detector.color(AmbientColorMode.ENERGY, speed.energyIntervalMillis - 1, 0.01))
            assertEquals(2, detector.color(AmbientColorMode.ENERGY, speed.energyIntervalMillis, 0.01))
            assertEquals(2, detector.color(AmbientColorMode.ENERGY, speed.energyIntervalMillis * 10, 0.01))
        }
    }
    @Test fun emptyInvalidAndSinglePaletteAreSafe() {
        assertEquals(listOf(9), normalizeAmbientPalette(emptyList(), 9))
        assertEquals(listOf(2, 31), normalizeAmbientPalette(listOf(-1, 2, 2, 31, 32)))
        for (mode in AmbientColorMode.entries) {
            val detector = AmbientMusicColorDetector(9, listOf(9), AmbientColorSpeed.FAST)
            for (time in 0L..5000L step 100) assertEquals(9, detector.color(mode, time, if (time % 500 == 0L) 0.5 else 0.01, 0.2))
        }
    }
    @Test fun bassModeRejectsFullbandOnlyOnsetAndAcceptsLowBandPulse() {
        val detector = AmbientMusicColorDetector(2, listOf(2, 8), AmbientColorSpeed.FAST)
        assertEquals(2, detector.color(AmbientColorMode.BASS, 0, 0.5, 0.0))
        assertEquals(8, detector.color(AmbientColorMode.BASS, 500, 0.5, 0.2))
        assertEquals(8, detector.color(AmbientColorMode.BASS, 1000, 0.5, 0.2))
    }
}
