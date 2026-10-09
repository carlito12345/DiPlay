package com.shilapi.xcertplay.hud

import java.io.File
import java.io.RandomAccessFile
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException

/** A zero-data OS lock, held through the worker's final restoration. Process death releases it. */
internal class AmbientWorkerOwnerLock private constructor(
    private val file: RandomAccessFile,
    private val lock: FileLock,
) : AutoCloseable {
    override fun close() {
        try { if (lock.isValid) lock.release() } finally { file.close() }
    }

    companion object {
        // Tests supply a temporary file. The production worker never accepts a path argument.
        fun tryAcquire(path: File): AmbientWorkerOwnerLock? {
            val file = RandomAccessFile(path, "rw")
            try {
                val lock = try { file.channel.tryLock() } catch (_: OverlappingFileLockException) { null }
                if (lock == null) { file.close(); return null }
                return AmbientWorkerOwnerLock(file, lock)
            } catch (failure: Throwable) {
                file.close()
                throw failure
            }
        }
    }
}
