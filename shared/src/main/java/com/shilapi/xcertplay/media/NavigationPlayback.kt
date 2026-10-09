package com.shilapi.xcertplay.media

/** A null stream means usage routing, fallback, or conflicting active legacy routes. */
data class NavigationPlaybackSnapshot(val active: Boolean, val legacyStreamType: Int?, val priorityVoiceActive: Boolean = false)

/** Foreground UI can poll this snapshot; it never requests focus or changes volume itself. */
object NavigationPlayback {
    private val tracker = NavigationPlaybackTracker()
    fun snapshot(): NavigationPlaybackSnapshot = tracker.snapshot()
    internal fun open() = tracker.open()
    internal fun played(token: NavigationPlaybackTracker.Token, streamType: Int?, bufferedMillis: Long, priorityVoice: Boolean = false) =
        tracker.played(token, streamType, bufferedMillis, priorityVoice)
    internal fun close(token: NavigationPlaybackTracker.Token) = tracker.close(token)
}

/** Per-renderer ownership prevents one navigation stream from clearing another stream. */
internal class NavigationPlaybackTracker(
    private val nowMillis: () -> Long = { System.nanoTime() / 1_000_000L },
    private val quietTailMillis: Long = 900L,
) {
    class Token internal constructor() { internal var closed = false }
    private data class Entry(val streamType: Int?, val untilMillis: Long, val priorityVoice: Boolean)
    private val entries = LinkedHashMap<Token, Entry>()

    fun open() = Token()

    @Synchronized
    fun played(token: Token, streamType: Int?, bufferedMillis: Long, priorityVoice: Boolean = false) {
        if (token.closed) return
        entries[token] = Entry(streamType, nowMillis() + bufferedMillis.coerceAtLeast(0L) + quietTailMillis, priorityVoice)
    }

    @Synchronized
    fun close(token: Token) {
        token.closed = true
        entries.remove(token)
    }

    @Synchronized
    fun snapshot(): NavigationPlaybackSnapshot {
        val now = nowMillis()
        entries.entries.removeAll { it.value.untilMillis <= now }
        val navigation = entries.values.filter { !it.priorityVoice }
        val routes = navigation.map { it.streamType }.distinct()
        return NavigationPlaybackSnapshot(navigation.isNotEmpty(), routes.singleOrNull(), entries.values.any { it.priorityVoice })
    }
}
