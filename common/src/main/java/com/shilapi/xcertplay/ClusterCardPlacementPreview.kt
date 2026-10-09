package com.shilapi.xcertplay

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.View
import com.shilapi.xcertplay.airplay.ClusterTurnCardOverlay
import com.shilapi.xcertplay.host.R
import kotlin.math.min

/** A proportional placement sketch: one diagram per card so each position reads alone. */
internal class ClusterCardPlacementPreview(context: Context, private val mode: Mode = Mode.FULL) : View(context) {
    enum class Mode { FULL, SMALL }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(160, 171, 193)
        style = Paint.Style.STROKE
        strokeWidth = resources.displayMetrics.density
    }
    private val full = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(51, 139, 255) }
    private val small = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(255, 179, 71) }
    private val smallWindow = Paint().apply { color = Color.argb(38, 255, 179, 71) }
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 13f * resources.displayMetrics.scaledDensity
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val density = resources.displayMetrics.density
        val margin = 14f * density
        val legendHeight = 28f * density
        val scale = min((width - margin * 2) / 1920f,
            (height - margin * 2 - legendHeight) / 720f).coerceAtLeast(0f)
        if (scale <= 0f) return
        val panelWidth = 1920f * scale
        val panelHeight = 720f * scale
        val left = (width - panelWidth) / 2f
        val top = margin + legendHeight
        canvas.drawRoundRect(RectF(left, top, left + panelWidth, top + panelHeight),
            7f * density, 7f * density, stroke)
        fun card(x: Int, y: Int, size: Int, paint: Paint) {
            val rect = ClusterTurnCardOverlay.card(1920, 720, x, y, size)
            canvas.drawRoundRect(RectF(left + rect.left * scale, top + rect.top * scale,
                left + (rect.left + rect.width) * scale, top + (rect.top + rect.height) * scale),
                5f * density, 5f * density, paint)
        }
        if (mode == Mode.FULL) {
            card(AirPlayPersistence.loadClusterTurnCardOverlayXPercent(context),
                AirPlayPersistence.loadClusterTurnCardOverlayYPercent(context),
                AirPlayPersistence.loadClusterTurnCardOverlaySizePercent(context), full)
            canvas.drawCircle(left + 6f * density, margin + 10f * density, 4f * density, full)
            canvas.drawText(context.getString(R.string.card_preview_full), left + 16f * density,
                margin + 14f * density, label)
        } else {
            // The small-window navi strip sits on the right of the panel; the small card's
            // percents place it inside that window, so the sketch shows the window itself.
            val windowLeft = left + panelWidth * 0.55f
            val windowWidth = panelWidth * 0.45f
            canvas.drawRect(RectF(windowLeft, top, left + panelWidth, top + panelHeight), smallWindow)
            val wx = (AirPlayPersistence.loadClusterSmallWindowCardXPercent(context) / 100f * windowWidth)
            val wy = (AirPlayPersistence.loadClusterSmallWindowCardYPercent(context) / 100f * panelHeight)
            val cardH = ClusterTurnCardOverlay.card(1000, 1000, 50, 50,
                AirPlayPersistence.loadClusterSmallWindowCardSizePercent(context)).height * (windowWidth / 1000f)
            val cardW = cardH * 2.64f
            canvas.drawRoundRect(RectF(windowLeft + wx - cardW / 2, top + wy - cardH / 2,
                windowLeft + wx + cardW / 2, top + wy + cardH / 2), 5f * density, 5f * density, small)
            canvas.drawCircle(left + 6f * density, margin + 10f * density, 4f * density, small)
            canvas.drawText(context.getString(R.string.card_preview_small), left + 16f * density,
                margin + 14f * density, label)
        }
    }
}
