package com.shilapi.xcertplay.transport

import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbRequest
import java.nio.ByteBuffer
import java.util.ArrayDeque
import java.util.concurrent.TimeoutException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.annotation.RealObject
import org.robolectric.util.ReflectionHelpers
import org.robolectric.util.ReflectionHelpers.ClassParameter

/** Replays USB completions through recv(), including boundaries invisible to an NTB byte stream. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE,
    shadows = [NcmFramingConnectionShadow::class, NcmFramingRequestShadow::class])
class NcmUsbBridgeFramingTest {
    @Before fun reset() { NcmFramingReplay.reset() }

    @Test fun completeAlignedBlockDoesNotWaitForAnExtraByte() {
        NcmFramingReplay.chunks.add(unpadded(alignedFrame, 1))
        withBridge { ncm -> assertArrayEquals(alignedFrame, ncm.recv(50)) }
        assertEquals(1, NcmFramingReplay.completions)
    }

    @Test fun adjacentAlignedBlocksKeepBothHeadersAndAllFrames() {
        val second = ByteArray(996) { (it * 17).toByte() }
        NcmFramingReplay.chunks.add(
            unpadded(alignedFrame, 1) + unpadded(second, 2) + Ntb16Codec.build(smallFrame, 3),
        )
        withBridge { ncm ->
            assertArrayEquals(alignedFrame, ncm.recv(50))
            assertArrayEquals(second, ncm.recv(50))
            assertArrayEquals(smallFrame, ncm.recv(50))
        }
        assertEquals(1, NcmFramingReplay.completions)
    }

    @Test fun optionalZeroPadAndFollowingBlockCanShareACompletion() {
        NcmFramingReplay.chunks.add(Ntb16Codec.build(alignedFrame, 1) + Ntb16Codec.build(smallFrame, 2))
        withBridge { ncm ->
            assertArrayEquals(alignedFrame, ncm.recv(50))
            assertArrayEquals(smallFrame, ncm.recv(50))
        }
    }

    @Test fun optionalPadAndNextHeaderCanArriveInSeparatePartialReads() {
        checkSplitFollowingBlock(padded = true)
    }

    @Test fun unpaddedNextHeaderCanArriveInSeparatePartialReads() {
        checkSplitFollowingBlock(padded = false)
    }

    @Test fun nonzeroGarbageAfterAlignedBlockStillFailsTheTransport() {
        NcmFramingReplay.chunks.add(unpadded(alignedFrame, 1) + byteArrayOf(0x55))
        withBridge { ncm ->
            val error = expectFailure { ncm.recv(50) }
            assertTrue(error.message!!.contains("Invalid NTB16 short-packet pad"))
            assertTrue(error === expectFailure { ncm.recv(50) })
        }
    }

    @Test fun nextHeaderWithOnlyItsFirstSignatureByteCorrectStillFails() {
        val malformed = Ntb16Codec.build(smallFrame, 2).also { it[1] = 0x55 }
        NcmFramingReplay.chunks.add(unpadded(alignedFrame, 1) + malformed)
        withBridge { ncm ->
            assertTrue(expectFailure { ncm.recv(50) }.message!!.contains("NTB16 header"))
        }
    }

    @Test fun moreThanOnePadByteStillFailsHeaderValidation() {
        NcmFramingReplay.chunks.add(Ntb16Codec.build(alignedFrame, 1) + byteArrayOf(0) + Ntb16Codec.build(smallFrame, 2))
        withBridge { ncm ->
            assertTrue(expectFailure { ncm.recv(50) }.message!!.contains("NTB16 header"))
        }
    }

    @Test fun unalignedBlocksDoNotAcceptExtraPadding() {
        NcmFramingReplay.chunks.add(Ntb16Codec.build(smallFrame, 1) + byteArrayOf(0) + Ntb16Codec.build(smallFrame, 2))
        withBridge { ncm ->
            assertTrue(expectFailure { ncm.recv(50) }.message!!.contains("NTB16 header"))
        }
    }

    private fun checkSplitFollowingBlock(padded: Boolean) {
        NcmFramingReplay.chunks.add(unpadded(alignedFrame, 1))
        if (padded) NcmFramingReplay.chunks.add(byteArrayOf(0))
        val following = Ntb16Codec.build(smallFrame, 2)
        NcmFramingReplay.chunks.addAll(listOf(
            following.copyOfRange(0, 1), following.copyOfRange(1, 3),
            following.copyOfRange(3, 12), following.copyOfRange(12, following.size),
        ))
        withBridge { ncm ->
            assertArrayEquals(alignedFrame, ncm.recv(50))
            assertEquals(1, NcmFramingReplay.completions)
            assertArrayEquals(smallFrame, ncm.recv(50))
        }
        assertTrue(NcmFramingReplay.chunks.isEmpty())
    }

    private fun unpadded(frame: ByteArray, sequence: Int): ByteArray {
        val block = Ntb16Codec.build(frame, sequence)
        assertEquals(1, block.size % 512)
        return block.copyOf(block.size - 1)
    }

    private fun expectFailure(block: () -> Unit): IphoneUsbException.DeviceUnavailable {
        try { block(); throw AssertionError("Expected invalid NTB framing to fail") }
        catch (error: IphoneUsbException.DeviceUnavailable) { return error }
    }

    private fun withBridge(block: (NcmUsbBridge) -> Unit) {
        val connection = ReflectionHelpers.callConstructor(UsbDeviceConnection::class.java,
            ClassParameter.from(UsbDevice::class.java, null))
        fun endpoint(address: Int): UsbEndpoint = ReflectionHelpers.callConstructor(UsbEndpoint::class.java,
            ClassParameter.from(Int::class.javaPrimitiveType, address),
            ClassParameter.from(Int::class.javaPrimitiveType, 2),
            ClassParameter.from(Int::class.javaPrimitiveType, 512),
            ClassParameter.from(Int::class.javaPrimitiveType, 0))
        val ncm = NcmUsbBridge(connection, endpoint(0x06), endpoint(0x85), null, emptyList(), null)
        try { block(ncm) } finally { ncm.close() }
    }

    companion object {
        private val alignedFrame = ByteArray(484) { (it * 31).toByte() }
        private val smallFrame = byteArrayOf(0x33, 0x33, 0, 0, 0, 1, 0x86.toByte(), 0xdd.toByte())
    }
}

object NcmFramingReplay {
    val chunks = ArrayDeque<ByteArray>()
    var request: UsbRequest? = null
    var buffer: ByteBuffer? = null
    var completions = 0
    fun reset() { chunks.clear(); request = null; buffer = null; completions = 0 }
}

@Implements(UsbRequest::class)
class NcmFramingRequestShadow {
    @RealObject lateinit var request: UsbRequest
    @Implementation fun initialize(connection: UsbDeviceConnection, endpoint: UsbEndpoint) = true
    @Implementation fun queue(buffer: ByteBuffer): Boolean {
        NcmFramingReplay.request = request
        NcmFramingReplay.buffer = buffer
        return true
    }
    @Implementation fun cancel() = true
    @Implementation fun close() = Unit
}

@Implements(UsbDeviceConnection::class)
class NcmFramingConnectionShadow {
    @Implementation fun requestWait(timeoutMillis: Long): UsbRequest {
        val chunk = NcmFramingReplay.chunks.pollFirst() ?: throw TimeoutException()
        NcmFramingReplay.buffer!!.put(chunk)
        NcmFramingReplay.completions += 1
        return NcmFramingReplay.request!!
    }
    @Implementation fun close() = Unit
}
