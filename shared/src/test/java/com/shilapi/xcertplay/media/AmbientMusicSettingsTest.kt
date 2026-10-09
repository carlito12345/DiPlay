package com.shilapi.xcertplay.media

import android.content.Context
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class AmbientMusicSettingsTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private val prefs get() = context.getSharedPreferences("ambient_music", Context.MODE_PRIVATE)

    @Before fun clear() { prefs.edit().clear().commit() }

    @Test fun missingPreferencesKeepControlDisabled() {
        val values = AmbientMusicSettings.load(context)
        assertFalse(values.enabled)
        assertEquals(listOf(1), values.selectedColors)
        assertEquals(0, values.brightness)
        assertEquals(3, values.area)
    }

    @Test fun oldSingleColorSettingsSupplyMissingPaletteAndModes() {
        prefs.edit().putInt("color", 12).putInt("brightness", 4).commit()
        val values = AmbientMusicSettings.load(context)
        assertFalse(values.enabled)
        assertEquals(listOf(12), values.selectedColors)
        assertEquals(AmbientColorMode.BEAT, values.colorMode)
        assertEquals(AmbientColorSpeed.STANDARD, values.speed)
    }

    @Test fun malformedPreferencesUseSafeDefaultsAndValidBounds() {
        prefs.edit().putString("enabled", "true").putString("music", "false")
            .putInt("color", 90).putInt("brightness", -2).putInt("area", 8)
            .putInt("selectedColors", 8).putString("colorMode", "UNKNOWN")
            .putString("speed", "UNKNOWN").commit()
        val values = AmbientMusicSettings.load(context)
        assertFalse(values.enabled)
        assertTrue(values.music)
        assertEquals(listOf(31), values.selectedColors)
        assertEquals(0, values.brightness)
        assertEquals(3, values.area)
        assertEquals(AmbientColorMode.BEAT, values.colorMode)
        assertEquals(AmbientColorSpeed.STANDARD, values.speed)
    }
}
