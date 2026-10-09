package com.shilapi.xcertplay

/** Keep a consumed press paired even if guidance ends between DOWN and UP. */
internal class NavigationWheelKeyPolicy {
    private data class Press(val device: Int, val key: Int, val downTime: Long)
    private val consumed = mutableSetOf<Press>()

    fun owns(key: Int, device: Int, downTime: Long): Boolean = Press(device, key, downTime) in consumed

    fun onKey(action: Int, key: Int, device: Int, downTime: Long, repeat: Int,
        eligible: Boolean, adjust: (Int) -> Boolean): Boolean {
        val delta = when (key) {
            24, 291, 307 -> 1
            25, 292, 308 -> -1
            else -> return false
        }
        val press = Press(device, key, downTime)
        if (action == 1) return consumed.remove(press)
        if (action != 0) return false
        if (press in consumed) {
            if (eligible) adjust(delta)
            return true
        }
        // Never take over a press whose initial DOWN went to the system.
        if (repeat != 0 || !eligible || !adjust(delta)) return false
        consumed.add(press)
        return true
    }
}

/** Stream 14 bypasses the public minimum validator on the verified head unit. */
internal fun navigationWheelMinimum(stream: Int, sdk: Int, publicMinimum: (Int) -> Int): Int =
    if (stream == 14 || sdk < 28) 0 else publicMinimum(stream)

internal fun navigationWheelTarget(current: Int, minimum: Int, maximum: Int, delta: Int): Int? {
    if (minimum > maximum || current !in minimum..maximum || delta !in listOf(-1, 1)) return null
    return (current.toLong() + delta).coerceIn(minimum.toLong(), maximum.toLong()).toInt()
}
