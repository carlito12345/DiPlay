package com.shilapi.xcertplay.transport

import com.shilapi.xcertplay.iap2.wire.Iap2Frame
import com.shilapi.xcertplay.iap2.wire.Iap2Parameter
import com.shilapi.xcertplay.iap2.wire.Iap2ParameterList
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class Iap2IdentificationRejectionTest {
    @Test
    fun protocolFixtureRetainsUnsupportedMessagesInsteadOfOnlyParameterIds() {
        // carplayd's protocol fixture: empty parameter 30, BE16 [0x415a] in parameter 6,
        // and empty parameter 20. This is a synthetic upstream vector, not an iPhone capture.
        // https://github.com/lvalen91/carplayd/blob/4eb254499aa00f456aea91d00755cf150c7d18a4/rust/carplayd/crates/iap2-core/src/message.rs#L847
        val rejected = rejection(
            byteArrayOf(0, 4, 0, 30, 0, 6, 0, 6, 0x41, 0x5a, 0, 4, 0, 20),
        )

        assertEquals(setOf(30, 6, 20), rejected.parameterIds)
        assertEquals(listOf(0x415a), rejected.unsupportedMessagesSent!!.messageIds)
        assertNull(rejected.unsupportedMessagesReceived)
        assertTrue(rejected.message!!.contains("before MFi authentication"))
        assertTrue(rejected.message!!.contains("sent=[0x415a]"))
        assertFalse(rejected.message!!.contains("no optional components"))
    }

    @Test
    fun receivedMessageIdsAreUnsignedBigEndianAndSeparateFromSentMessages() {
        val rejected = rejection(
            Iap2Parameter(6, byteArrayOf(0x50, 1)),
            Iap2Parameter(7, byteArrayOf(0xaa.toByte(), 0, 0xff.toByte(), 0xff.toByte())),
        )

        assertEquals(listOf(0x5001), rejected.unsupportedMessagesSent!!.messageIds)
        assertEquals(listOf(0xaa00, 0xffff), rejected.unsupportedMessagesReceived!!.messageIds)
        assertTrue(rejected.message!!.contains("received=[0xaa00, 0xffff]"))
    }

    @Test
    fun emptyAndOddLengthPayloadsRemainDistinctAndDoNotInventMessageIds() {
        val rejected = rejection(
            Iap2Parameter(6, byteArrayOf()),
            Iap2Parameter(7, byteArrayOf(0x41, 0x5a, 0x50)),
        )

        val sent = rejected.unsupportedMessagesSent!!
        val received = rejected.unsupportedMessagesReceived!!
        assertTrue(sent.messageIds.isEmpty())
        assertEquals(1, sent.emptyPayloadCount)
        assertEquals(0, sent.malformedPayloadCount)
        assertTrue(received.messageIds.isEmpty())
        assertEquals(0, received.emptyPayloadCount)
        assertEquals(1, received.malformedPayloadCount)
        assertTrue(rejected.message!!.contains("sent=[] payloads=1 empty=1 malformed=0"))
        assertTrue(rejected.message!!.contains("received=[] payloads=1 empty=0 malformed=1"))
    }

    @Test
    fun repeatedParametersAndMessageIdsAreVisibleInWireOrder() {
        val rejected = rejection(
            Iap2Parameter(6, byteArrayOf(0x50, 0, 0x50, 0)),
            Iap2Parameter(6, byteArrayOf()),
            Iap2Parameter(6, byteArrayOf(0x52, 0)),
            Iap2Parameter(7, byteArrayOf(0)),
            Iap2Parameter(7, byteArrayOf(0x4e, 0x0d)),
        )

        val sent = rejected.unsupportedMessagesSent!!
        val received = rejected.unsupportedMessagesReceived!!
        assertEquals(listOf(0x5000, 0x5000, 0x5200), sent.messageIds)
        assertEquals(3, sent.payloadCount)
        assertEquals(1, sent.emptyPayloadCount)
        assertEquals(listOf(0x4e0d), received.messageIds)
        assertEquals(2, received.payloadCount)
        assertEquals(1, received.malformedPayloadCount)
        assertTrue(sent.diagnostic().contains("duplicateParameters=true duplicateRetainedIds=true"))
        assertTrue(received.diagnostic().contains("duplicateParameters=true duplicateRetainedIds=false"))
    }

    @Test
    fun messageIdDiagnosticsAreBoundedAcrossRepeatedParametersInEachDirection() {
        val values = ByteArray(80) { index -> if (index % 2 == 0) 0 else (index / 2).toByte() }
        val rejected = rejection(
            Iap2Parameter(6, values.copyOfRange(0, 40)),
            Iap2Parameter(6, values.copyOfRange(40, 80)),
            Iap2Parameter(7, values),
        )

        for (messages in listOf(rejected.unsupportedMessagesSent!!, rejected.unsupportedMessagesReceived!!)) {
            assertEquals((0..31).toList(), messages.messageIds)
            assertEquals(8, messages.omittedMessageCount)
            assertTrue(messages.diagnostic().contains("omitted=8"))
            assertFalse(messages.diagnostic().contains("0x0020"))
        }
    }

    @Test
    fun otherRejectedParameterPayloadsAreNotExported() {
        val rejected = rejection(Iap2Parameter(1, "private phone name".encodeToByteArray()))

        assertEquals(setOf(1), rejected.parameterIds)
        assertNull(rejected.unsupportedMessagesSent)
        assertNull(rejected.unsupportedMessagesReceived)
        assertFalse(rejected.message!!.contains("private phone name"))
    }

    @Test
    fun existingRejectedConstructorRemainsAvailableAndCopiesParameterIds() {
        val ids = linkedSetOf(6, 7)
        val rejected = Iap2IdentificationException.Rejected(ids)
        ids.clear()

        assertEquals(setOf(6, 7), rejected.parameterIds)
        assertNull(rejected.unsupportedMessagesSent)
        assertNull(rejected.unsupportedMessagesReceived)
    }

    private fun rejection(vararg parameters: Iap2Parameter): Iap2IdentificationException.Rejected =
        rejection(Iap2ParameterList.of(*parameters).encode())

    private fun rejection(payload: ByteArray): Iap2IdentificationException.Rejected =
        Iap2IdentificationClient.identificationRejection(
            Iap2Frame(Iap2IdentificationClient.IDENTIFICATION_REJECTED, payload),
        )
}
