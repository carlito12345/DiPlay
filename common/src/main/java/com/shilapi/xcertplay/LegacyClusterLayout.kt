package com.shilapi.xcertplay

import kotlin.math.roundToInt

/** A complete 8:3 map inside the app's projection canvas; never changes the vehicle's layers. */
internal object LegacyClusterLayout {
    data class Settings(val horizontal: Int = 50, val vertical: Int = 50, val widthPercent: Int = 50)
    data class Plan(val left: Int, val top: Int, val width: Int, val height: Int)
    private const val ASPECT = 8.0 / 3.0
    private fun step(value: Int, minimum: Int) = ((value.coerceIn(minimum, 100) + 2) / 5) * 5
    fun sanitize(settings: Settings) = Settings(step(settings.horizontal, 0), step(settings.vertical, 0), step(settings.widthPercent, 25))

    fun plan(canvasWidth: Int, canvasHeight: Int, settings: Settings = Settings()): Plan? {
        if (canvasWidth <= 0 || canvasHeight <= 0) return null
        val value = sanitize(settings)
        val height = (canvasWidth * (value.widthPercent / 100.0) / ASPECT).roundToInt().coerceIn(1, canvasHeight)
        val width = (height * ASPECT).roundToInt().coerceIn(1, canvasWidth)
        val left = ((canvasWidth - width) * (value.horizontal / 100.0)).roundToInt()
        val top = ((canvasHeight - height) * (value.vertical / 100.0)).roundToInt()
        return Plan(left, top, width, height)
    }
}
