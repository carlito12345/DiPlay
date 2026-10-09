package com.shilapi.xcertplay

import org.junit.Assert.assertEquals
import org.junit.Test

class SidePanelTabIntentTest {
    private val slop = 8f

    @Test fun aTouchThatHasNotMovedIsUndecided() {
        assertEquals(SidePanelTabIntent.UNDECIDED, sidePanelTabIntent(0f, 0f, slop))
        assertEquals(SidePanelTabIntent.UNDECIDED, sidePanelTabIntent(7f, 7f, slop))
    }

    @Test fun movingInwardDragsThePanelOut() {
        assertEquals(SidePanelTabIntent.OPEN_PANEL, sidePanelTabIntent(10f, 2f, slop))
        assertEquals(SidePanelTabIntent.OPEN_PANEL, sidePanelTabIntent(8f, 8f, slop))
    }

    @Test fun movingAlongOrBackTowardsTheEdgeBelongsToCarPlay() {
        // A list scrolled near the edge, a mostly diagonal swipe, and a swipe towards the screen edge.
        assertEquals(SidePanelTabIntent.CARPLAY, sidePanelTabIntent(3f, 10f, slop))
        assertEquals(SidePanelTabIntent.CARPLAY, sidePanelTabIntent(9f, 12f, slop))
        assertEquals(SidePanelTabIntent.CARPLAY, sidePanelTabIntent(-10f, 0f, slop))
    }
}
