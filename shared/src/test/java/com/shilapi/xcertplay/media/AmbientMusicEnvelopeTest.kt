package com.shilapi.xcertplay.media

import org.junit.Assert.*
import org.junit.Test

class AmbientMusicEnvelopeTest {
    @Test fun latestValueCancellationDoesNotFailSession() {
        assertTrue(ambientApplyCancelled(java.util.concurrent.CancellationException("superseded")))
        assertTrue(ambientApplyCancelled(java.util.concurrent.CompletionException(java.util.concurrent.CancellationException())))
        assertFalse(ambientApplyCancelled(IllegalStateException("real apply failure")))
    }
    @Test fun shortAlreadyPlayedPeakSurvivesSlowLampPollWithoutFutureLeak() {
        val envelope = AmbientMusicEnvelope()
        envelope.append(0, 20, 0.0)
        envelope.append(20, 20, 0.9)
        envelope.append(40, 200, 0.01)
        assertEquals(0.0, envelope.playedWindowRms(0, true), 0.0)
        assertEquals(0.9, envelope.playedWindowRms(100, true), 0.0)
        assertEquals(0.01, envelope.playedWindowRms(120, true), 0.0)
        assertEquals(0.0, envelope.playedWindowRms(150, false), 0.0)
    }
    @Test fun queuedAudioDoesNotLightBeforePlaybackReachesIt() {
        val envelope = AmbientMusicEnvelope()
        envelope.append(48_000, 4_800, 1.0)
        assertEquals(1, envelope.level(0, true, 6))
        assertEquals(1, envelope.level(47_999, true, 6))
        assertEquals(6, envelope.level(48_000, true, 6))
        assertEquals(1, envelope.level(52_800, true, 6))
    }
    @Test fun silencePauseAndBrightnessCeiling() {
        val envelope = AmbientMusicEnvelope()
        envelope.append(0, 100, 1.0)
        assertEquals(1, envelope.level(0, false, 6))
        assertEquals(3, envelope.level(10, true, 3))
        assertEquals(1, envelope.level(10, true, 1))
    }
    @Test fun matchesCurrentPlayedSegmentRatherThanNewestWrittenAudio() {
        val envelope = AmbientMusicEnvelope()
        envelope.append(0, 100, 0.0)
        envelope.append(100, 100, 1.0)
        assertEquals(1, envelope.level(50, true, 6))
        assertEquals(6, envelope.level(110, true, 6))
    }
    @Test fun headWrapPreservesFrameTimeline() {
        val envelope = AmbientMusicEnvelope()
        envelope.append(0xffff_fff0L, 64, 1.0)
        assertEquals(6, envelope.level(-16, true, 6))
        assertEquals(6, envelope.level(16, true, 6))
        assertEquals(1, envelope.level(64, true, 6))
    }
    @Test fun rmsUsesSignedLittleEndianAndNeverRetainsInput() {
        val full = byteArrayOf(0, -128, -1, 127)
        assertTrue(AmbientMusicEnvelope.pcmRms(full, 0, 4) > 0.99)
        assertEquals(0.0, AmbientMusicEnvelope.pcmRms(ByteArray(4), 0, 4), 0.0)
        assertEquals(0.0, AmbientMusicEnvelope.pcmRms(full, 5, 4), 0.0)
        val envelope = AmbientMusicEnvelope()
        envelope.append(0, 100, AmbientMusicEnvelope.pcmRms(full, 0, 4))
        full.fill(0)
        assertTrue(envelope.level(0, true, 6) >= 5)
    }
}
