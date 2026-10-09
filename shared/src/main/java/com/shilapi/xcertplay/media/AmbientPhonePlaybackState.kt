package com.shilapi.xcertplay.media

/** Identity-bound phone playback signal; null means no authoritative phone state is known. */
internal class AmbientPhonePlaybackState {
    private var owner: Any? = null
    private var playing: Boolean? = null

    @Synchronized fun claim(nextOwner: Any) {
        if (owner === nextOwner) return
        owner = nextOwner
        playing = null
    }

    @Synchronized fun update(source: Any, isPlaying: Boolean): Boolean {
        if (owner !== source) return false
        playing = isPlaying
        return true
    }

    @Synchronized fun release(source: Any): Boolean {
        if (owner !== source) return false
        owner = null
        playing = null
        return true
    }

    @Synchronized fun current(): Boolean? = playing
}
