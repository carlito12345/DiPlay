package com.shilapi.xcertplay

import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.view.View
import android.widget.TextView
import com.shilapi.xcertplay.host.R
import com.shilapi.xcertplay.media.LiveVideoCounters
import com.shilapi.xcertplay.media.LiveVideoMeter
import com.shilapi.xcertplay.media.LiveVideoReading

/** The counter's colour for [shownFps]: green from 50 fps, yellow from 30, red below. */
internal fun fpsCounterColor(shownFps: Int): Int = when {
    shownFps >= 50 -> 0xFF4CD964.toInt()
    shownFps >= 30 -> 0xFFFFCC00.toInt()
    else -> 0xFFFF3B30.toInt()
}

/**
 * A game-style FPS counter over the CarPlay picture (the Diagnostics setting): frames shown on screen per
 * second, frames received from the iPhone per second, and the average decode time, refreshed twice a
 * second. It never takes touches, so CarPlay under it keeps working.
 */
internal class FpsCounterOverlay(
    context: Context,
    private val handler: Handler,
    private val counters: () -> LiveVideoCounters?,
) {
    private val meter = LiveVideoMeter()
    private val density = context.resources.displayMetrics.density

    val view = TextView(context).apply {
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        textSize = 14f
        setLineSpacing(0f, 1.1f)
        val horizontal = (10 * density).toInt()
        val vertical = (6 * density).toInt()
        setPadding(horizontal, vertical, horizontal, vertical)
        background = GradientDrawable().apply {
            setColor(0x99000000.toInt())
            cornerRadius = 8 * density
        }
        isClickable = false
        isFocusable = false
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        visibility = View.GONE
    }

    private val tick = object : Runnable {
        override fun run() {
            show(meter.update(counters(), System.nanoTime()))
            handler.postDelayed(this, INTERVAL_MS)
        }
    }

    fun start() {
        handler.removeCallbacks(tick)
        meter.reset()
        show(null)
        view.visibility = View.VISIBLE
        handler.post(tick)
    }

    fun stop() {
        handler.removeCallbacks(tick)
        view.visibility = View.GONE
    }

    private fun show(reading: LiveVideoReading?) {
        val context = view.context
        if (reading == null) {
            view.text = context.getString(R.string.fps_counter_waiting)
            view.setTextColor(0xFFFFFFFF.toInt())
            return
        }
        val decode = if (reading.decodeMillis >= 0) context.getString(R.string.fps_counter_millis, reading.decodeMillis)
        else context.getString(R.string.fps_counter_no_value)
        view.text = context.getString(R.string.fps_counter_text, reading.shownFps, reading.receivedFps, decode)
        view.setTextColor(fpsCounterColor(reading.shownFps))
    }

    private companion object {
        const val INTERVAL_MS = 500L
    }
}
