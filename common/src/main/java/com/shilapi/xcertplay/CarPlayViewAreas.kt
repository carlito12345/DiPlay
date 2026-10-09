package com.shilapi.xcertplay

import android.content.Context
import com.shilapi.xcertplay.airplay.AirPlayInfoPlist
import com.shilapi.xcertplay.airplay.AirPlayViewArea
import kotlin.math.abs
import kotlin.math.ln

/**
 * The view areas the main screen declares for one session, and the one CarPlay uses now. A fixed dock
 * declares every area once per dock edge; the head unit's split screen adds an area the size of DiPlay's
 * window there; a square canvas for a turning screen holds a landscape and a portrait screen. The car
 * moves CarPlay between them with updateViewArea, without reconnecting. No Android types beyond
 * preferences, so the choices are unit-tested.
 */
class CarPlayViewAreas private constructor(
    val areas: List<AirPlayViewArea>,
    private val slots: List<Slot>,
    initial: Int,
) {
    /** What an area is for; SIDE_PANEL is CarPlay beside DiPlay's panel and is only chosen on request. */
    enum class Kind { FULL_SCREEN, SPLIT_SCREEN, SIDE_PANEL }

    /** A full screen the canvas holds: its size and whether the screen is portrait then. */
    data class Screen(val width: Int, val height: Int, val portrait: Boolean)

    /** One area's purpose; [sixths] is CarPlay's share of its screen beside the side panel, in sixths. */
    private data class Slot(val kind: Kind, val portrait: Boolean, val sixths: Int = 6)

    /** The area CarPlay uses now; shared by the session's host and its settings. */
    @Volatile var current: Int = initial
        private set

    /** True when the canvas holds both a landscape and a portrait screen. */
    val turnsWithScreen: Boolean = slots.any { it.portrait } && slots.any { !it.portrait }

    fun kindOf(index: Int): Kind = slots[index].kind

    /** The area of [kind] with [dockEdge] (or the edge-less one) on the current screen, or null. */
    fun index(kind: Kind, dockEdge: Int?): Int? = index(kind, slots[current].portrait, dockEdge)

    fun index(kind: Kind, portrait: Boolean, dockEdge: Int?): Int? =
        areas.indices.firstOrNull { slots[it].kind == kind && slots[it].portrait == portrait && areas[it].dockEdge == dockEdge }

    /**
     * The area for DiPlay's window on a [portrait] or landscape screen, keeping the dock edge: the
     * split-screen area in the head unit's split screen when one has about the window's shape, the
     * whole screen otherwise. Null when this session has no area for that screen (the caller reconnects).
     */
    fun indexFor(width: Int, height: Int, splitScreen: Boolean, portrait: Boolean = slots[current].portrait): Int? {
        val edge = areas[current].dockEdge
        val split = index(Kind.SPLIT_SCREEN, portrait, edge)
        if (splitScreen && split != null && closeShape(areas[split], width, height)) return split
        return index(Kind.FULL_SCREEN, portrait, edge)
    }

    /**
     * The area beside DiPlay's side panel on the current (or given) screen with CarPlay's share in [sixths]
     * (another share when this session has no such area), or null without a side panel.
     */
    fun sidePanel(portrait: Boolean = slots[current].portrait, sixths: Int = DEFAULT_SIDE_PANEL_SIXTHS): Int? {
        val edge = areas[current].dockEdge
        return areas.indices.firstOrNull {
            slots[it].kind == Kind.SIDE_PANEL && slots[it].portrait == portrait && areas[it].dockEdge == edge && slots[it].sixths == sixths
        } ?: index(Kind.SIDE_PANEL, portrait, edge)
    }

    /**
     * The area to lay out the canvas by: beside the side panel CarPlay draws in part of its screen, so the
     * whole screen is laid out and the panel covers the rest ([panelRect]).
     */
    fun layoutArea(index: Int): AirPlayViewArea =
        if (slots[index].kind == Kind.SIDE_PANEL) {
            index(Kind.FULL_SCREEN, slots[index].portrait, areas[index].dockEdge)?.let { areas[it] } ?: areas[index]
        } else areas[index]

    /**
     * The part of the stream DiPlay's panel covers beside side-panel area [index]: the strip of its whole
     * screen that CarPlay leaves (stream pixels), or null for other areas.
     */
    fun panelRect(index: Int): AirPlayViewArea? {
        if (slots[index].kind != Kind.SIDE_PANEL) return null
        val side = areas[index]
        val screen = layoutArea(index)
        return when {
            side.height < screen.height -> AirPlayViewArea(screen.width, screen.height - side.height,
                screen.originX, side.originY + side.height)
            side.originX > screen.originX -> AirPlayViewArea(side.originX - screen.originX, screen.height,
                screen.originX, screen.originY)
            else -> AirPlayViewArea(screen.originX + screen.width - side.originX - side.width, screen.height,
                side.originX + side.width, screen.originY)
        }
    }

    /** The area in use after moving the dock to [dockEdge], keeping the kind of area and the screen. */
    fun withDock(dockEdge: Int): Int? = index(kindOf(current), dockEdge)

    fun use(index: Int) {
        current = index.coerceIn(0, areas.lastIndex)
    }

    companion object {
        /** Further than this (as an aspect ratio) from DiPlay's window, a split-screen area does not fit it. */
        private const val MAX_ASPECT_MISMATCH = 1.25

        /** CarPlay's share of the screen beside the side panel, in sixths: two thirds, half, one third. */
        val SIDE_PANEL_SIXTHS = listOf(4, 3, 2)
        const val DEFAULT_SIDE_PANEL_SIXTHS = 4

        /** The declared share nearest to CarPlay's [fraction] of the screen (0..1). */
        fun nearestSidePanelSixths(fraction: Double): Int = SIDE_PANEL_SIXTHS.minByOrNull { abs(it / 6.0 - fraction) }!!

        /** One screen the size of the stream (no turning); see the general [build]. */
        fun build(
            width: Int,
            height: Int,
            dock: CarPlayDock,
            splitWindow: Pair<Float, Float>?,
            sidePanel: Boolean = false,
            rightHandDrive: Boolean = false,
        ): CarPlayViewAreas? =
            build(width, height, listOf(Screen(width, height, portrait = height > width)), dock,
                splitWindow = { splitWindow }, startPortrait = height > width, sidePanel = sidePanel,
                rightHandDrive = rightHandDrive)

        /**
         * The areas for a [canvasWidth] x [canvasHeight] stream holding [screens] (each at its top-left
         * corner), or null when the whole stream as one area will do (one screen, automatic dock, no split
         * screen). [splitWindow] gives the split-screen window on a portrait or landscape screen as
         * fractions of that full screen, or null without split-screen support. With [sidePanel], CarPlay
         * also gets two thirds of each screen beside a DiPlay panel, nearer the driver ([rightHandDrive]).
         */
        fun build(
            canvasWidth: Int,
            canvasHeight: Int,
            screens: List<Screen>,
            dock: CarPlayDock,
            splitWindow: (portrait: Boolean) -> Pair<Float, Float>?,
            startPortrait: Boolean,
            sidePanel: Boolean = false,
            rightHandDrive: Boolean = false,
        ): CarPlayViewAreas? {
            val splits = screens.associateWith { splitWindow(it.portrait) }
            if (screens.size == 1 && dock.edge == null && splits.values.all { it == null } && !sidePanel &&
                screens[0].width == canvasWidth && screens[0].height == canvasHeight) return null
            val edges = dock.edge?.let { listOf(AirPlayInfoPlist.DOCK_EDGE_DRIVER_SIDE, AirPlayInfoPlist.DOCK_EDGE_BOTTOM) }
                ?: listOf(null)
            val areas = mutableListOf<AirPlayViewArea>()
            val slots = mutableListOf<Slot>()
            for (screen in screens) for (edge in edges) {
                areas += AirPlayViewArea(screen.width, screen.height, dockEdge = edge)
                slots += Slot(Kind.FULL_SCREEN, screen.portrait)
            }
            for (screen in screens) {
                val window = splits[screen] ?: continue
                // The window's own size (BYD shows its bars in split screen), kept even for the encoder.
                val splitWidth = (screen.width * window.first).toInt().coerceIn(2, screen.width) and 1.inv()
                val splitHeight = (screen.height * window.second).toInt().coerceIn(2, screen.height) and 1.inv()
                for (edge in edges) {
                    areas += AirPlayViewArea(splitWidth, splitHeight, dockEdge = edge)
                    slots += Slot(Kind.SPLIT_SCREEN, screen.portrait)
                }
            }
            if (sidePanel) for (screen in screens) for (sixths in SIDE_PANEL_SIXTHS) {
                // CarPlay keeps two thirds, half or one third: the driver's side of a landscape screen (the panel
                // on the passenger's side) or the top of a portrait one (the panel at the bottom).
                val width = if (screen.portrait) screen.width else (screen.width * sixths / 6) and 1.inv()
                val height = if (screen.portrait) (screen.height * sixths / 6) and 1.inv() else screen.height
                val originX = if (rightHandDrive) screen.width - width else 0
                for (edge in edges) {
                    areas += AirPlayViewArea(width, height, originX = originX, dockEdge = edge)
                    slots += Slot(Kind.SIDE_PANEL, screen.portrait, sixths)
                }
            }
            val initial = areas.indices.firstOrNull {
                slots[it].kind == Kind.FULL_SCREEN && slots[it].portrait == startPortrait && areas[it].dockEdge == dock.edge
            } ?: areas.indices.first { slots[it].kind == Kind.FULL_SCREEN && areas[it].dockEdge == dock.edge }
            return CarPlayViewAreas(areas, slots, initial)
        }

        private fun closeShape(area: AirPlayViewArea, width: Int, height: Int): Boolean {
            if (width <= 0 || height <= 0) return false
            val ratio = area.width.toDouble() / area.height / (width.toDouble() / height)
            return abs(ln(ratio)) <= ln(MAX_ASPECT_MISMATCH)
        }
    }
}

/**
 * Optional: CarPlay fills DiPlay's window in the head unit's split screen without reconnecting. The
 * window there is remembered as fractions of the whole screen, which hiding or showing the system bars
 * does not change, so the next connection declares an area of exactly that shape.
 */
object SplitScreenSettings {
    private const val PREFS = "diplay_split_screen"
    private const val KEY_ENABLED = "enabled"

    fun enabled(context: Context): Boolean = prefs(context).getBoolean(KEY_ENABLED, false)

    fun setEnabled(context: Context, enabled: Boolean) = prefs(context).edit().putBoolean(KEY_ENABLED, enabled).apply()

    /**
     * The split-screen window seen last on a [portrait] or landscape screen, as fractions of the whole
     * screen. Before that: [expected] when known, else half the width side by side, or half the height on
     * a portrait screen.
     */
    fun window(context: Context, portrait: Boolean, expected: Pair<Float, Float>? = null): Pair<Float, Float> {
        val key = if (portrait) "portrait" else "landscape"
        val width = prefs(context).getFloat("${key}_screen_width", 0f)
        val height = prefs(context).getFloat("${key}_screen_height", 0f)
        return if (width in 0.1f..1f && height in 0.1f..1f) width to height
        else expected ?: if (portrait) 1f to 0.5f else 0.5f to 1f
    }

    /**
     * The split window to expect before DiPlay has seen one, as fractions of the whole screen: the screen
     * halved across its long side less the [divider], with both system bars shown, as BYD shows them in
     * split screen whatever the full-screen settings. On my Tang (2560x1440, bars of 112 and 120 px, a
     * 20 px divider) that is 1270x1208 side by side and 1440x1154 stacked, the windows the head unit gave.
     * Null when the screen size is unknown.
     */
    fun expectedWindow(
        portrait: Boolean,
        screenLong: Int,
        screenShort: Int,
        statusBar: Int,
        navigationBar: Int,
        divider: Int,
    ): Pair<Float, Float>? {
        if (screenLong <= 0 || screenShort <= 0) return null
        val bars = statusBar.coerceAtLeast(0) + navigationBar.coerceAtLeast(0)
        val gap = divider.coerceAtLeast(0)
        val window = if (portrait) {
            1f to ((screenLong - bars - gap) / 2).toFloat() / screenLong
        } else {
            ((screenLong - gap) / 2).toFloat() / screenLong to (screenShort - bars).toFloat() / screenShort
        }
        return window.takeIf { it.first in 0.1f..1f && it.second in 0.1f..1f }
    }

    /** Remembers the split-screen window on a [portrait] or landscape screen as fractions of the whole screen. */
    fun saveWindow(context: Context, portrait: Boolean, width: Float, height: Float) {
        if (width !in 0.1f..1f || height !in 0.1f..1f) return
        val key = if (portrait) "portrait" else "landscape"
        prefs(context).edit().putFloat("${key}_screen_width", width).putFloat("${key}_screen_height", height).apply()
    }

    /**
     * A split window given as fractions of a [screenWidth] x [screenHeight] screen, as fractions of the
     * [windowWidth] x [windowHeight] full window CarPlay has on that screen (the screen minus the bars it
     * shows then), which is what its area is sized against.
     */
    fun ofWindow(screenFraction: Pair<Float, Float>, screenWidth: Int, screenHeight: Int, windowWidth: Int, windowHeight: Int): Pair<Float, Float> {
        if (screenWidth <= 0 || screenHeight <= 0 || windowWidth <= 0 || windowHeight <= 0) return screenFraction
        return (screenFraction.first * screenWidth / windowWidth).coerceAtMost(1f) to
            (screenFraction.second * screenHeight / windowHeight).coerceAtMost(1f)
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
