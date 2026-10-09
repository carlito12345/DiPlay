package com.shilapi.xcertplay.transport

import android.os.Build
import java.nio.ByteBuffer

internal data class UsbReadQueueResult(val queued: Boolean, val firstBytes: Int, val fallbackBytes: Int? = null)

/** Upper bound enforced by Android 8.0/8.1's queue(ByteBuffer) overload. */
internal const val USBFS_BULK_URB_CEILING_BYTES = 16 * 1024

/**
 * Android 8.0/8.1 queue(ByteBuffer) throws above 16 KiB, so cap the first submission there.
 * The false-return fallback is a compatibility hypothesis for explicit vendor queue rejection.
 * Android 9 UsbRequest.queue(ByteBuffer) accepts any size and clears its queued state on false:
 * https://android.googlesource.com/platform/frameworks/base/+/android-9.0.0_r1/core/java/android/hardware/usb/UsbRequest.java
 * Large read regions may retry at smaller sizes down to 2 KiB, even when a platform cap or
 * cached successful size reduced the first submission. Short regions are never retried.
 * Call under the pipe's state lock, including publication, queueing and the open-state checks.
 */
internal class UsbReadQueuePolicy(private val queueCeiling: Int? = null) {
    private var successfulLimit: Int? = null

    fun queue(buffer: ByteBuffer, checkOpen: () -> Unit, submit: (ByteBuffer) -> Boolean): UsbReadQueueResult {
        require(buffer.isDirect && !buffer.isReadOnly) { "USB read requires a writable direct buffer" }
        checkOpen()
        val position = buffer.position()
        val originalLimit = buffer.limit()
        val originalRemaining = buffer.remaining()
        val firstBytes = minOf(
            originalRemaining, successfulLimit ?: originalRemaining, queueCeiling ?: originalRemaining,
        )
        buffer.limit(position + firstBytes)
        if (submitUnchanged(buffer, position, firstBytes, submit)) {
            return UsbReadQueueResult(true, firstBytes)
        }
        if (originalRemaining <= COMPATIBILITY_BYTES || firstBytes <= MIN_COMPATIBILITY_BYTES) {
            buffer.limit(originalLimit)
            return UsbReadQueueResult(false, firstBytes)
        }

        var size = if (firstBytes > COMPATIBILITY_BYTES) COMPATIBILITY_BYTES else {
            maxOf(MIN_COMPATIBILITY_BYTES, firstBytes / 2)
        }
        var lastAttempt = firstBytes
        while (size >= MIN_COMPATIBILITY_BYTES) {
            checkOpen()
            buffer.limit(position + size)
            lastAttempt = size
            if (submitUnchanged(buffer, position, size, submit)) {
                successfulLimit = size
                return UsbReadQueueResult(true, firstBytes, size)
            }
            size /= 2
        }
        buffer.limit(originalLimit)
        return UsbReadQueueResult(false, firstBytes, lastAttempt)
    }

    /** A false queue result is retryable only while its buffer range remains unchanged. */
    private fun submitUnchanged(
        buffer: ByteBuffer,
        position: Int,
        size: Int,
        submit: (ByteBuffer) -> Boolean,
    ): Boolean {
        if (submit(buffer)) return true
        check(buffer.position() == position && buffer.limit() == position + size) {
            "Rejected USB queue changed its buffer state"
        }
        return false
    }

    companion object {
        private const val COMPATIBILITY_BYTES = USBFS_BULK_URB_CEILING_BYTES
        private const val MIN_COMPATIBILITY_BYTES = 2 * 1024

        fun forCurrentPlatform(): UsbReadQueuePolicy = UsbReadQueuePolicy(
            // Android 7 uses queue(ByteBuffer, Int); Android 9 removed the new queue's bound.
            // Only API 26/27 require a ceiling before queueing, rather than a retry on false.
            if (Build.VERSION.SDK_INT in Build.VERSION_CODES.O until Build.VERSION_CODES.P) {
                USBFS_BULK_URB_CEILING_BYTES
            } else {
                null
            },
        )
    }
}
