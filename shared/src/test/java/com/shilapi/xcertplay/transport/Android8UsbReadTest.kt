package com.shilapi.xcertplay.transport

import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers
import org.robolectric.util.ReflectionHelpers.ClassParameter

/** Android 8.0/8.1 queue(ByteBuffer) throws above 16 KiB before a false-return fallback can run. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 27], manifest = Config.NONE,
    shadows = [CompatibilityUsbConnectionShadow::class, CompatibilityUsbRequestShadow::class])
class Android8UsbReadTest {
    @Before fun reset() { UsbQueueReplay.reset() }

    @Test fun usbmuxCappedRejectionRetriesSmallerAndCachesSuccess() {
        UsbQueueReplay.outcomes.addAll(listOf(false, true, true))
        val pipe = Iap2UsbSession(connection(), endpoint(0x04), endpoint(0x85))
        try {
            repeat(2) { assertArrayEquals(UsbReadQueueCompatibilityTest.payload, pipe.read(1_000)) }
        } finally { pipe.close() }
        assertEquals(listOf(16_384, 8_192, 8_192), UsbQueueReplay.sizes)
    }

    @Test fun ncmReassemblesLargeNtbAfterCappedRejectionAtEightK() {
        val frame = ByteArray(32_740) { (it * 31).toByte() }
        val following = UsbReadQueueCompatibilityTest.payload
        val padded = Ntb16Codec.build(frame, 7)
        UsbQueueReplay.transfer = padded.copyOf(padded.size - 1) + Ntb16Codec.build(following, 8)
        UsbQueueReplay.outcomes.addAll(listOf(false, true))
        val ncm = ncm()
        try {
            assertArrayEquals(frame, ncm.recv(1_000))
            assertArrayEquals(following, ncm.recv(1_000))
        } finally { ncm.close() }
        assertEquals(listOf(16_384, 8_192, 8_192, 8_192, 8_192, 8_192), UsbQueueReplay.sizes)
        assertEquals(listOf(8_192, 8_192, 8_192, 8_192, 32), UsbQueueReplay.completedBytes)
    }

    @Test fun usbmuxCapsTheFirstAndSubsequentRequests() {
        val diagnostics = mutableListOf<String>()
        val pipe = Iap2UsbSession(connection(), endpoint(0x04), endpoint(0x85), diagnostics::add)
        try {
            repeat(2) { assertArrayEquals(UsbReadQueueCompatibilityTest.payload, pipe.read(1_000)) }
        } finally { pipe.close() }
        assertEquals(listOf(16_384, 16_384), UsbQueueReplay.sizes)
        assertTrue(diagnostics.isEmpty())
    }

    @Test fun ncmReassemblesLargerNtbAndPreservesNextFrameAcrossCappedReads() {
        val frame = ByteArray(32_740) { (it * 31).toByte() }
        val following = UsbReadQueueCompatibilityTest.payload
        val padded = Ntb16Codec.build(frame, 7)
        UsbQueueReplay.transfer = padded.copyOf(padded.size - 1) + Ntb16Codec.build(following, 8)
        val ncm = ncm()
        try {
            assertArrayEquals(frame, ncm.recv(1_000))
            assertArrayEquals(following, ncm.recv(1_000))
        } finally { ncm.close() }
        assertEquals(listOf(16_384, 16_384, 16_384), UsbQueueReplay.sizes)
        assertEquals(listOf(16_384, 16_384, 32), UsbQueueReplay.completedBytes)
    }

    @Test fun rejectionOfEveryCappedLadderSizeFailsBothPipes() {
        UsbQueueReplay.outcomes.addAll(listOf(false, false, false, false))
        val pipe = Iap2UsbSession(connection(), endpoint(0x04), endpoint(0x85))
        try { checkCappedFailure { pipe.read(1_000) } } finally { pipe.close() }
        assertEquals(listOf(16_384, 8_192, 4_096, 2_048), UsbQueueReplay.sizes)
        UsbQueueReplay.reset()
        UsbQueueReplay.outcomes.addAll(listOf(false, false, false, false))
        val ncm = ncm()
        try { checkCappedFailure { ncm.recv(1_000) } } finally { ncm.close() }
        assertEquals(listOf(16_384, 8_192, 4_096, 2_048), UsbQueueReplay.sizes)
    }

    @Test fun ambiguousQueueExceptionIsNotRetriedOnEitherPipe() {
        UsbQueueReplay.queueException = IllegalStateException("unknown queue state")
        val pipe = Iap2UsbSession(connection(), endpoint(0x04), endpoint(0x85))
        try { expectFailure { pipe.read(1_000) } } finally { pipe.close() }
        assertEquals(listOf(16_384), UsbQueueReplay.sizes)
        UsbQueueReplay.reset()
        UsbQueueReplay.queueException = IllegalStateException("unknown queue state")
        val ncm = ncm()
        try { expectFailure { ncm.recv(1_000) } } finally { ncm.close() }
        assertEquals(listOf(16_384), UsbQueueReplay.sizes)
    }

    private fun checkCappedFailure(block: () -> Unit) {
        val error = expectFailure(block)
        assertTrue(error.message!!.contains("firstBytes=16384"))
        assertTrue(error.message!!.contains("fallbackBytes=2048"))
    }

    private fun expectFailure(block: () -> Unit): IphoneUsbException.DeviceUnavailable {
        try { block(); throw AssertionError("Expected read failure") }
        catch (error: IphoneUsbException.DeviceUnavailable) { return error }
    }

    private fun ncm() = NcmUsbBridge(connection(), endpoint(0x06), endpoint(0x85), null, emptyList(), null)
    private fun connection(): UsbDeviceConnection = ReflectionHelpers.callConstructor(
        UsbDeviceConnection::class.java, ClassParameter.from(UsbDevice::class.java, null))
    private fun endpoint(address: Int): UsbEndpoint = ReflectionHelpers.callConstructor(
        UsbEndpoint::class.java, ClassParameter.from(Int::class.javaPrimitiveType, address),
        ClassParameter.from(Int::class.javaPrimitiveType, 2),
        ClassParameter.from(Int::class.javaPrimitiveType, 512),
        ClassParameter.from(Int::class.javaPrimitiveType, 0))
}
