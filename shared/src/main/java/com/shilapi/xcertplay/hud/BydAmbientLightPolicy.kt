package com.shilapi.xcertplay.hud

/** Pure validation and restore-order rules for the narrowly scoped interior-lamp worker. */
internal object BydAmbientLightPolicy {
    const val TYPE = 1023
    const val LEASE_MILLIS = 15_000L
    const val MIN_APPLY_INTERVAL_MILLIS = 200L
    const val READBACK_POLL_MILLIS = 50L

    val getNames = setOf(
        "SET_HAS_INTERIOR_ATMOSPHERE_LAMP", "SET_IAL_COLOR_CONFIG", "SET_IAL_BRIGHTNESS_CONFIG",
        "SET_IAL_AREA_CONFIG", "SET_INTERIOR_ATMOSPHERE_LAMP_AREA", "SET_IAL_FRONT_COLOR",
        "SET_IAL_BACK_COLOR", "SET_IAL_FRONT_BRIGHTNESS", "SET_IAL_BACK_BRIGHTNESS",
    )
    val setNames = setOf(
        "SET_INTERIOR_ATMOSPHERE_LAMP_COLOR_SET", "SET_INTERIOR_ATMOSPHERE_LAMP_BRIGHTNESS_SET",
        "SET_INTERIOR_ATMOSPHERE_LAMP_AREA_SET",
    )

    data class Snapshot(val frontColor: Int, val backColor: Int, val frontBrightness: Int, val backBrightness: Int, val area: Int)
    data class Values(val color: Int, val rawBrightness: Int, val area: Int)
    data class RestoreStep(val values: Values? = null, val areaOnly: Int? = null)

    fun validSnapshot(s: Snapshot): Boolean =
        s.frontColor in 1..31 && s.backColor in 1..31 &&
            s.frontBrightness in 1..6 && s.backBrightness in 1..6 && s.area in 1..3

    fun validApply(area: Int, color: Int, rawBrightness: Int): Boolean =
        area in 1..3 && color in 1..31 && rawBrightness in 1..6

    /** SDK display brightness 0 is raw 1: minimum output, not verified physical power-off. */
    fun minimumTarget(current: Snapshot): Snapshot {
        require(validSnapshot(current))
        return current.copy(frontBrightness = 1, backBrightness = 1)
    }

    /** Preserve the current zone colors, then restore the current area selection. */
    fun minimumSteps(current: Snapshot): List<RestoreStep> {
        val target = minimumTarget(current)
        return listOf(
            RestoreStep(values = Values(target.frontColor, 1, 1)),
            RestoreStep(values = Values(target.backColor, 1, 2)),
            RestoreStep(areaOnly = target.area),
        )
    }

    /** Equal original zones can be restored atomically with the known-good ALL-area batch. */
    fun restoreSteps(s: Snapshot): List<RestoreStep> {
        if (s.frontColor == s.backColor && s.frontBrightness == s.backBrightness) {
            val steps = mutableListOf(RestoreStep(values = Values(s.frontColor, s.frontBrightness, 3)))
            if (s.area != 3) steps += RestoreStep(areaOnly = s.area)
            return steps
        }
        val steps = mutableListOf<RestoreStep>()
        steps += RestoreStep(values = Values(s.frontColor, s.frontBrightness, 1))
        steps += RestoreStep(values = Values(s.backColor, s.backBrightness, 2))
        steps += RestoreStep(areaOnly = s.area)
        return steps
    }
}
