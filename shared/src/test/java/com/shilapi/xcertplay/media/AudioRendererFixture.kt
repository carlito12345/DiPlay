package com.shilapi.xcertplay.media

import com.shilapi.xcertplay.airplay.AudioFormat

// carlito | Share the private renderer constructor contract across codec and buffer fixtures.
internal fun audioRendererFixture(format: AudioFormat, bufferMillis: Int): Any {
    val type = Class.forName("com.shilapi.xcertplay.media.AudioRenderer")
    return type.declaredConstructors.single { !it.isSynthetic }.apply { isAccessible = true }
        .newInstance(format, false, false, 0, 0, AudioFocusCoordinator(null, false, false),
            0, bufferMillis, { _: String -> }, null, null, null, AudioOutputRoutes(), false,
            { _: Any -> }, 0L, false, null, false)
}
