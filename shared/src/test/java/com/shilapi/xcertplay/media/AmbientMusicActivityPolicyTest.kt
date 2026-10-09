package com.shilapi.xcertplay.media

import org.junit.Assert.*
import org.junit.Test

class AmbientMusicActivityPolicyTest {
    @Test fun onlyMusicStreamsOwnTheLampEvenWhenGuidanceUsesTheMediaBus() {
        assertTrue(AmbientMusicActivityPolicy.acceptsAudio("media", 102))
        assertTrue(AmbientMusicActivityPolicy.acceptsAudio("", 102))
        for (type in listOf("compatibility", "default", "alert", "telephony", "speechRecognition")) {
            assertFalse(type, AmbientMusicActivityPolicy.acceptsAudio(type, 102))
        }
        assertFalse(AmbientMusicActivityPolicy.acceptsAudio("", 101))
    }

    @Test fun masterOffRestoresOemRegardlessOfBrightnessOrPlayback() {
        assertEquals(AmbientMusicTarget.RESTORE_OEM,
            AmbientMusicActivityPolicy.target(false, true, 0, false, false))
        assertEquals(AmbientMusicTarget.RESTORE_OEM,
            AmbientMusicActivityPolicy.target(false, true, 6, true, true))
    }

    @Test fun enabledZeroBrightnessRemainsAnExplicitLowestLevelIntent() {
        assertEquals(AmbientMusicTarget.LOWEST,
            AmbientMusicActivityPolicy.target(true, true, 0, true, true))
        assertEquals(AmbientMusicTarget.LOWEST,
            AmbientMusicActivityPolicy.target(true, true, 0, false, false))
    }

    @Test fun pausedOrMissingRendererHoldsLowestWhilePlayingSilenceStillFollowsEnvelope() {
        assertEquals(AmbientMusicTarget.LOWEST,
            AmbientMusicActivityPolicy.target(true, true, 6, true, false))
        assertEquals(AmbientMusicTarget.LOWEST,
            AmbientMusicActivityPolicy.target(true, true, 6, false, false))
        // RMS is intentionally not part of the activity decision: quiet PCM while playing
        // remains ordinary envelope input and resolves to the envelope's floor level.
        assertEquals(AmbientMusicTarget.FOLLOW_PLAYBACK,
            AmbientMusicActivityPolicy.target(true, true, 6, true, true))
    }

    @Test fun authoritativePhonePauseOverridesAudioTrackStillReportingPlaying() {
        val state = AmbientPhonePlaybackState()
        val owner = Any()
        state.claim(owner)
        assertTrue(state.update(owner, false))
        val effectivePlaying = true && state.current() != false
        assertEquals(AmbientMusicTarget.LOWEST,
            AmbientMusicActivityPolicy.target(true, true, 6, true, effectivePlaying))
        assertTrue(state.update(owner, true))
        assertEquals(AmbientMusicTarget.FOLLOW_PLAYBACK,
            AmbientMusicActivityPolicy.target(true, true, 6, true, true && state.current() != false))
    }

    @Test fun callbacksFromAnOldOwnerCannotChangeCurrentPlaybackState() {
        val state = AmbientPhonePlaybackState()
        val oldOwner = Any()
        val newOwner = Any()
        state.claim(oldOwner)
        assertTrue(state.update(oldOwner, true))
        state.claim(newOwner)
        assertFalse(state.update(oldOwner, false))
        assertNull(state.current())
        assertTrue(state.update(newOwner, false))
        assertFalse(state.release(oldOwner))
        assertEquals(false, state.current())
        assertTrue(state.release(newOwner))
        assertNull(state.current())
    }

    @Test fun rebindingTheSameControllerKeepsItsKnownPauseState() {
        val state = AmbientPhonePlaybackState()
        val owner = Any()
        state.claim(owner)
        assertTrue(state.update(owner, false))
        state.claim(owner)
        assertEquals(false, state.current())
    }

    @Test fun staticColorAlsoRespectsPauseAndOnlyLightsDuringMusicPlayback() {
        assertEquals(AmbientMusicTarget.LOWEST,
            AmbientMusicActivityPolicy.target(true, false, 4, false, false))
        assertEquals(AmbientMusicTarget.STATIC,
            AmbientMusicActivityPolicy.target(true, false, 4, true, true))
    }
}
