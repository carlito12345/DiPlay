package com.shilapi.xcertplay.adb

import java.io.IOException
import org.junit.Assert.*
import org.junit.Test

class LocalAdbInteractiveTest {
    private class Link(vararg frames: AdbPacket) {
        val inbound = frames.toMutableList()
        val sent = mutableListOf<AdbPacket>()
        var closed = false
        val shell = LocalAdb.InteractiveShell(7, 9, { sent += it }, {
            if (inbound.isEmpty()) throw IOException("EOF")
            inbound.removeAt(0)
        }, { assertTrue(it in 1..10000) }, { closed = true })
    }
    private fun ack() = AdbPacket(AdbPacket.OKAY, 9, 7, ByteArray(0))
    private fun data(text: String) = AdbPacket(AdbPacket.WRTE, 9, 7, text.toByteArray())

    @Test
    fun handlesAckAndFragmentedUtf8BeforeResponseTerminator() {
        val bytes = "歌词\ndone\n".toByteArray()
        val link = Link(ack(), AdbPacket(AdbPacket.WRTE, 9, 7, bytes.copyOfRange(0, 2)),
            AdbPacket(AdbPacket.WRTE, 9, 7, bytes.copyOfRange(2, bytes.size - 2)),
            AdbPacket(AdbPacket.WRTE, 9, 7, bytes.takeLast(2).toByteArray()))
        assertEquals("歌词", link.shell.exchangeBounded("token ping"))
        assertEquals(AdbPacket.WRTE, link.sent.first().command)
        assertEquals("token ping\n", String(link.sent.first().payload))
        assertEquals(3, link.sent.count { it.command == AdbPacket.OKAY })
        assertFalse(link.closed)
        link.shell.close()
        assertTrue(link.closed)
        assertEquals(AdbPacket.CLSE, link.sent.last().command)
    }

    @Test
    fun responseBeforeAckStillConsumesAckAndNextExchangeUsesSameStream() {
        val link = Link(data("ready\ndone\n"), ack(), ack(), data("text=0\ndone\n"))
        assertEquals("ready", link.shell.exchangeBounded("token ping"))
        assertEquals("text=0", link.shell.exchangeBounded("token write - - dGV4dA=="))
        assertEquals(2, link.sent.count { it.command == AdbPacket.WRTE })
    }

    @Test
    fun closesOnWrongStreamEofAndOversizedResponse() {
        val wrong = Link(AdbPacket(AdbPacket.OKAY, 99, 7, ByteArray(0)))
        assertNull(wrong.shell.exchangeBounded("token ping"))
        assertTrue(wrong.closed)
        val eof = Link(ack())
        assertNull(eof.shell.exchangeBounded("token ping"))
        assertTrue(eof.closed)
        val large = Link(ack(), data("x".repeat(4097)))
        assertNull(large.shell.exchangeBounded("token ping"))
        assertTrue(large.closed)
    }

    @Test
    fun rejectsMultilineAndOversizedRequestsBeforeSending() {
        val link = Link()
        assertNull(link.shell.exchangeBounded("token ping\nunknown"))
        assertNull(link.shell.exchangeBounded("x".repeat(1025)))
        assertTrue(link.sent.isEmpty())
    }
}
