package com.shilapi.xcertplay.hud

import java.nio.file.Files
import java.io.File
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

class AmbientWorkerOwnerLockTest {
    @Test fun exclusiveOwnerIsNonblockingAndReacquiredAfterClose() {
        val path = Files.createTempFile("ambient-lock-test", ".lock").toFile()
        try {
            val first = requireNotNull(AmbientWorkerOwnerLock.tryAcquire(path))
            try { assertNull(AmbientWorkerOwnerLock.tryAcquire(path)); assertEquals(0L, path.length()) }
            finally { first.close() }
            requireNotNull(AmbientWorkerOwnerLock.tryAcquire(path)).use { assertNull(AmbientWorkerOwnerLock.tryAcquire(path)) }
        } finally { path.delete() }
    }
    @Test fun exceptionInOwnerBodyReleasesLock() {
        val path = Files.createTempFile("ambient-lock-test", ".lock").toFile()
        try {
            try { requireNotNull(AmbientWorkerOwnerLock.tryAcquire(path)).use { error("synthetic worker failure") } }
            catch (_: IllegalStateException) { }
            requireNotNull(AmbientWorkerOwnerLock.tryAcquire(path)).use { assertEquals(0L, path.length()) }
        } finally { path.delete() }
    }
    @Test fun anotherProcessCannotEnterUntilOwnerReleases() {
        val path = Files.createTempFile("ambient-process-lock", ".lock").toFile()
        val classpath = listOf(AmbientWorkerOwnerLock::class.java, AmbientWorkerOwnerLockTest::class.java, Unit::class.java, org.junit.Assert::class.java)
            .map { File(it.protectionDomain.codeSource.location.toURI()).path }.distinct().joinToString(File.pathSeparator)
        fun attempt(): String {
            val child = ProcessBuilder(File(System.getProperty("java.home"), "bin/java").path,
                "-cp", classpath, AmbientWorkerOwnerLockTest::class.java.name, path.path).redirectErrorStream(false).start()
            try {
                assertTrue("Child lock probe timed out", child.waitFor(5, TimeUnit.SECONDS))
                assertEquals(child.errorStream.bufferedReader().readText(), 0, child.exitValue())
                return child.inputStream.bufferedReader().readText().trim()
            } finally { if (child.isAlive) child.destroyForcibly() }
        }
        try {
            requireNotNull(AmbientWorkerOwnerLock.tryAcquire(path)).use { assertEquals("busy", attempt()) }
            assertEquals("acquired", attempt())
        } finally { path.delete() }
    }
    companion object {
        @JvmStatic fun main(args: Array<String>) {
            val owner = AmbientWorkerOwnerLock.tryAcquire(File(args.single()))
            if (owner == null) println("busy") else owner.use { println("acquired") }
        }
    }

}
