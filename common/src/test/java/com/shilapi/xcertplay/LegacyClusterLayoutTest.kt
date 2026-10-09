package com.shilapi.xcertplay

import org.junit.Assert.*
import org.junit.Test

class LegacyClusterLayoutTest {
    @Test fun measuredCanvasKeepsTheDefaultMapInTheTransparentCentre() {
        assertEquals(LegacyClusterLayout.Plan(480, 180, 960, 360), LegacyClusterLayout.plan(1920, 720))
    }
    @Test fun rejectsUnavailableDimensionsAndKeepsTheMapInsideItsCanvas() {
        assertNull(LegacyClusterLayout.plan(0, 720))
        assertNull(LegacyClusterLayout.plan(1920, 0))
        val plan = LegacyClusterLayout.plan(800, 300, LegacyClusterLayout.Settings(100, 100, 100))!!
        assertTrue(plan.left >= 0 && plan.top >= 0)
        assertTrue(plan.left + plan.width <= 800 && plan.top + plan.height <= 300)
    }
}
