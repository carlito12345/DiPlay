package com.shilapi.xcertplay

import org.junit.Assert.assertEquals
import org.junit.Test

class FpsCounterOverlayTest {
    @Test fun theColourFollowsTheShownFrameRate() {
        assertEquals(0xFF4CD964.toInt(), fpsCounterColor(60))
        assertEquals(0xFF4CD964.toInt(), fpsCounterColor(50))
        assertEquals(0xFFFFCC00.toInt(), fpsCounterColor(49))
        assertEquals(0xFFFFCC00.toInt(), fpsCounterColor(30))
        assertEquals(0xFFFF3B30.toInt(), fpsCounterColor(29))
        assertEquals(0xFFFF3B30.toInt(), fpsCounterColor(0))
    }
}
