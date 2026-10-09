package com.shilapi.xcertplay.hud

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BydTyresTest {
    private fun parcel(value: Int) = "Result: Parcel(00000000 %08x '........')".format(value)

    @Test
    fun readsPressureAndTemperatureOfTheFourTyres() {
        val replies = mapOf(
            "1016 i32 -1728052956" to 232, "1016 i32 -1728052952" to 230,
            "1016 i32 -1728052948" to 232, "1016 i32 -1728052944" to 230,
            "1007 i32 1246797848" to 11, "1007 i32 1246797860" to 10,
            "1007 i32 1246797872" to 11, "1007 i32 1246797884" to 10,
        )
        val reading = BydTyres.read { command -> replies.entries.firstOrNull { command.endsWith(it.key) }?.let { parcel(it.value) } }
        assertEquals(BydTyreReading(listOf(232, 230, 232, 230), listOf(11, 10, 11, 10)), reading)
    }

    @Test
    fun implausibleOrMissingValuesAreLeftOut() {
        val reading = BydTyres.read { command ->
            when {
                command.endsWith("-1728052956") -> parcel(232)
                command.contains(" 1016 ") -> parcel(65535)
                command.endsWith("1246797848") -> parcel(-40)
                else -> null
            }
        }
        assertEquals(BydTyreReading(listOf(232, null, null, null), listOf(-40, null, null, null)), reading)
    }

    @Test
    fun noPressureMeansNoReading() {
        assertNull(BydTyres.read { null })
    }

    @Test
    fun aShellWithoutAutoserviceIsToldApartFromAnUnavailableShell() {
        assertEquals(true, BydTyres.noService("Service autoservice does not exist."))
        assertEquals(false, BydTyres.noService(parcel(232)))
        assertEquals(false, BydTyres.noService(null))
    }
}
