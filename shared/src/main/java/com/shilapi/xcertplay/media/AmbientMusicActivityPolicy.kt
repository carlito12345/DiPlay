package com.shilapi.xcertplay.media

/** Decides whether enabled ambient control follows playback, holds its lowest level, or restores OEM state. */
internal enum class AmbientMusicTarget { RESTORE_OEM, LOWEST, STATIC, FOLLOW_PLAYBACK }

internal object AmbientMusicActivityPolicy {
    /** Guidance routed onto the automotive media bus must not replace the music lamp owner. */
    fun acceptsAudio(audioType: String, payloadType: Int): Boolean =
        AudioChannelMapper.map(audioType, payloadType, AudioChannelMappingMode.MOBILE_COMPATIBLE).channel == AudioChannel.MEDIA

    fun target(enabled: Boolean, music: Boolean, brightness: Int, rendererActive: Boolean, playing: Boolean): AmbientMusicTarget = when {
        !enabled -> AmbientMusicTarget.RESTORE_OEM
        brightness <= 0 -> AmbientMusicTarget.LOWEST
        !rendererActive || !playing -> AmbientMusicTarget.LOWEST
        !music -> AmbientMusicTarget.STATIC
        else -> AmbientMusicTarget.FOLLOW_PLAYBACK
    }
}
