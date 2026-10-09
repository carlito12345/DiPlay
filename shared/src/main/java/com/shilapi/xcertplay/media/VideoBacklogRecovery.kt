package com.shilapi.xcertplay.media

/** Recover a growing input backlog, while allowing delayed bursts and static tail frames to finish. */
internal class VideoBacklogRecovery {
    private var episodeStartNs: Long? = null
    private var initialSpanNs = 0L
    private var initialPendingFrames = 0
    private var lastObservedNs: Long? = null

    fun reset() {
        episodeStartNs = null
        initialSpanNs = 0L
        initialPendingFrames = 0
        lastObservedNs = null
    }

    fun observe(nowNs: Long, currentReceivedNs: Long, pendingFrames: Int,
        newestPendingReceivedNs: Long?): Boolean {
        if (pendingFrames <= 0 || newestPendingReceivedNs == null ||
            currentReceivedNs > nowNs || newestPendingReceivedNs > nowNs ||
            newestPendingReceivedNs < currentReceivedNs ||
            lastObservedNs?.let { nowNs < it } == true) {
            reset()
            return false
        }
        lastObservedNs = nowNs
        val ageNs = nowNs - currentReceivedNs
        val spanNs = newestPendingReceivedNs - currentReceivedNs
        if (ageNs <= LOW_AGE_NS || spanNs <= LOW_SPAN_NS) {
            reset()
            return false
        }
        if (ageNs >= HARD_AGE_NS && spanNs >= SOFT_SPAN_NS) {
            reset()
            return true
        }
        if (ageNs <= SOFT_AGE_NS || spanNs < SOFT_SPAN_NS) return false
        val start = episodeStartNs
        if (start == null) {
            episodeStartNs = nowNs
            initialSpanNs = spanNs
            initialPendingFrames = pendingFrames
            return false
        }
        val growing = spanNs - initialSpanNs >= SPAN_GROWTH_NS ||
            pendingFrames - initialPendingFrames >= FRAME_GROWTH
        if (nowNs - start >= GRACE_NS && growing) {
            reset()
            return true
        }
        return false
    }

    private companion object {
        const val SOFT_AGE_NS = 250_000_000L
        const val SOFT_SPAN_NS = 100_000_000L
        const val LOW_AGE_NS = 150_000_000L
        const val LOW_SPAN_NS = 50_000_000L
        const val HARD_AGE_NS = 1_500_000_000L
        const val GRACE_NS = 750_000_000L
        const val SPAN_GROWTH_NS = 100_000_000L
        const val FRAME_GROWTH = 4
    }
}
