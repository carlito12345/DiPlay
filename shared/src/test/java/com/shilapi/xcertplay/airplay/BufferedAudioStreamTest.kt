package com.shilapi.xcertplay.airplay

import java.io.DataOutputStream
import java.math.BigInteger
import java.net.InetAddress
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BufferedAudioStreamTest {
    private val config = AirPlayConfig(
        deviceName = "test",
        deviceId = "02:00:00:00:00:02",
        btMac = "02:00:00:00:00:02",
        sourceVersion = "366.0",
        main = AirPlayDisplayConfig(widthPixels = 1280, heightPixels = 720),
    )
    private val key = ByteArray(32) { (it + 1).toByte() }
    private val format = AudioFormat(AudioCodecKind.AAC_LC, 48_000, 2, BufferedAudioStream.STREAM_TYPE, "media")

    @Test
    fun theOfferIsMadeOnlyWhenEnabled() {
        val off = AirPlayInfoPlist.build(config)
        assertFalse(off.containsKey("mainBufferedInfo"))
        assertFalse((off["audioFormats"] as List<*>).any { (it as Map<*, *>)["type"] == 103 })
        assertFalse(MAIN_BUFFERED_FEATURE in setupEnabledFeatures(config, listOf("mainBuffered", "iAPChannel")))

        val on = config.copy(mainBufferedAudio = true)
        val info = AirPlayInfoPlist.build(on)
        assertEquals(emptyMap<String, Any?>(), info["mainBufferedInfo"])
        val entry = (info["audioFormats"] as List<*>).single { (it as Map<*, *>)["type"] == 103 } as Map<*, *>
        assertEquals("media", entry["audioType"])
        assertEquals(0x800000, entry["audioOutputFormats"]) // AAC-LC 48 kHz stereo
        assertTrue(MAIN_BUFFERED_FEATURE in setupEnabledFeatures(on, listOf("mainBuffered", "iAPChannel")))
        assertFalse(MAIN_BUFFERED_FEATURE in setupEnabledFeatures(on, listOf("iAPChannel")))
    }

    @Test
    fun disablingAudioOutputAlsoDisablesBufferedSetupNegotiation() {
        val muted = config.copy(mainBufferedAudio = true, disableAudioOutput = true)
        assertFalse(AirPlayInfoPlist.build(muted).containsKey("mainBufferedInfo"))
        assertFalse(MAIN_BUFFERED_FEATURE in setupEnabledFeatures(muted, listOf(MAIN_BUFFERED_FEATURE)))
    }

    @Test
    fun pausedTcpPreloadCannotExceedTheAdvertisedByteBudget() {
        val stream = BufferedAudioStream(key, format, RecordingSink())
        val executor = Executors.newSingleThreadExecutor()
        try {
            stream.start()
            Socket(InetAddress.getLoopbackAddress(), stream.port).use { socket ->
                val sending = executor.submit {
                    try {
                        val out = DataOutputStream(socket.getOutputStream())
                        repeat(180) { i ->
                            val body = seal(i, i * 1024L, ByteArray(60_000))
                            out.writeShort(body.size + 2)
                            out.write(body)
                        }
                        out.flush()
                    } catch (_: java.io.IOException) { /* Closing the stream interrupts a blocked writer. */ }
                }
                waitFor { queuedBytes(stream) >= BufferedAudioStream.AUDIO_BUFFER_BYTES - 120_000L }
                Thread.sleep(150)
                assertTrue("TCP preload exceeded its advertised buffer", queuedBytes(stream) <= BufferedAudioStream.AUDIO_BUFFER_BYTES)
                stream.close()
                sending.get(3, TimeUnit.SECONDS)
            }
        } finally {
            stream.close()
            executor.shutdownNow()
        }
    }

    @Test
    fun theFillRunsFreeToTheFloorAndIsPacedBeyondIt() {
        // 15 s of 1024-sample frames at 48 kHz, the point past which the intake is held back.
        val floor = 48_000 * 15_000 / (1024 * 1000)
        val extra = 200
        val pauses = CopyOnWriteArrayList<Long>()
        val stream = BufferedAudioStream(key, format, RecordingSink(), pause = { pauses.add(it) })
        val executor = Executors.newSingleThreadExecutor()
        try {
            stream.start()
            Socket(InetAddress.getLoopbackAddress(), stream.port).use { socket ->
                val sending = executor.submit {
                    try {
                        val out = DataOutputStream(socket.getOutputStream())
                        repeat(floor + extra) { i ->
                            val body = seal(i, i * 1024L, byteArrayOf(0x21))
                            out.writeShort(body.size + 2); out.write(body)
                        }
                        out.flush()
                    } catch (_: java.io.IOException) { /* Closing the stream interrupts a blocked writer. */ }
                }
                waitFor { queuedFrames(stream) >= floor + extra }
                sending.get(3, TimeUnit.SECONDS)
            }
            assertEquals(floor + extra, queuedFrames(stream))
            // Music starts at once: nothing is held back until the floor is reached, and from
            // there every frame costs a quarter of its own duration, so the intake settles at
            // about four times real time instead of racing TCP to the two-minute cap.
            assertEquals(extra + 1, pauses.size)
            assertEquals(setOf(5L), pauses.toSet())
        } finally {
            stream.close()
            executor.shutdownNow()
        }
    }

    @Test fun pauseWaitsForAnInFlightSinkDelivery() = controlWaitsForDelivery { stream ->
        stream.setRate(null, 0, BigInteger.ZERO)
    }

    @Test fun flushWaitsForAnInFlightSinkDelivery() = controlWaitsForDelivery { stream ->
        stream.flush(2048L)
    }

    @Test fun closeWaitsForAnInFlightSinkDelivery() = controlWaitsForDelivery { stream ->
        stream.close()
    }

    @Test
    fun pauseAndResumeRetainFramesAlreadyFedAheadOfTheAudiblePosition() {
        val sink = RecordingSink()
        val stream = BufferedAudioStream(key, format, sink, nanoTime = { 0L })
        try {
            stream.start()
            Socket(InetAddress.getLoopbackAddress(), stream.port).use { socket ->
                val out = DataOutputStream(socket.getOutputStream())
                repeat(100) { i ->
                    val body = seal(i, 1000L + i * 1024L, byteArrayOf(0x21))
                    out.writeShort(body.size + 2); out.write(body)
                }
                out.flush()
                stream.setRate(1000L, 1, BigInteger.ZERO)
                waitFor { sink.rtp.size >= 47 }
                val paused = stream.setRate(null, 0, BigInteger.ZERO)!!
                assertEquals(1000L, paused["rtpTime"])
                stream.setRate(paused["rtpTime"] as Long, 1, BigInteger.ZERO)
                waitFor { sink.started.size == 2 }
                assertEquals("Resume skipped the one-second renderer preload", 1000, sink.started.last())
            }
        } finally { stream.close() }
    }

    private fun controlWaitsForDelivery(control: (BufferedAudioStream) -> Unit) {
        val delivering = CountDownLatch(1)
        val release = CountDownLatch(1)
        val startedControl = CountDownLatch(1)
        val completedControl = CountDownLatch(1)
        val sink = object : MediaSink {
            override fun onAudioRtp(id: AudioStreamId, format: AudioFormat, rtp: ByteArray, sample: Int) {
                delivering.countDown()
                assertTrue(release.await(3, TimeUnit.SECONDS))
            }
        }
        val stream = BufferedAudioStream(key, format, sink)
        val executor = Executors.newSingleThreadExecutor()
        try {
            stream.start()
            Socket(InetAddress.getLoopbackAddress(), stream.port).use { socket ->
                val body = seal(0, 1024L, byteArrayOf(0x21))
                DataOutputStream(socket.getOutputStream()).apply {
                    writeShort(body.size + 2); write(body); flush()
                }
                stream.setRate(1024L, 1, BigInteger.ZERO)
                assertTrue(delivering.await(3, TimeUnit.SECONDS))
                val action = executor.submit {
                    startedControl.countDown()
                    control(stream)
                    completedControl.countDown()
                }
                assertTrue(startedControl.await(3, TimeUnit.SECONDS))
                assertFalse("Control returned while a stale sink delivery was still in flight", completedControl.await(150, TimeUnit.MILLISECONDS))
                release.countDown()
                action.get(3, TimeUnit.SECONDS)
            }
        } finally {
            release.countDown()
            stream.close()
            executor.shutdownNow()
        }
    }

    private fun queuedFrames(stream: BufferedAudioStream): Int {
        val lock = BufferedAudioStream::class.java.getDeclaredField("lock").apply { isAccessible = true }.get(stream)!!
        return synchronized(lock) {
            val queue = BufferedAudioStream::class.java.getDeclaredField("queue").apply { isAccessible = true }.get(stream)
            (queue as java.util.ArrayDeque<*>).size
        }
    }

    private fun queuedBytes(stream: BufferedAudioStream): Long {
        val lock = BufferedAudioStream::class.java.getDeclaredField("lock").apply { isAccessible = true }.get(stream)!!
        return synchronized(lock) {
            BufferedAudioStream::class.java.getDeclaredField("queuedBytes").apply { isAccessible = true }.getLong(stream)
        }
    }

    @Test
    fun framesOpenWithTheStreamKeyAndKeepTheirRtpHeader() {
        val payload = byteArrayOf(0x21, 0x6c, 0x4f, 0x55, 0x10)
        val body = seal(sequence = 7, timestamp = 0xfffffc00L, payload = payload)
        val frame = BufferedAudioStream.open(key, body)!!
        assertEquals(7, frame.sequence)
        assertEquals(0xfffffc00L, frame.timestamp)
        assertArrayEquals(payload, frame.rtp.copyOfRange(12, frame.rtp.size))
        assertNull(BufferedAudioStream.open(ByteArray(32), body))
        assertNull(BufferedAudioStream.open(key, body.copyOf(20)))
        assertNull(BufferedAudioStream.open(key, body.copyOf().apply { this[4] = (this[4].toInt() xor 1).toByte() }))
        assertNull(BufferedAudioStream.open(key, body.copyOf().apply { this[lastIndex - 8] = (this[lastIndex - 8].toInt() xor 1).toByte() }))
        for (invalidHeader in listOf(0x40, 0x81, 0x90, 0xa0)) {
            assertNull(BufferedAudioStream.open(key, body.copyOf().apply { this[0] = invalidHeader.toByte() }))
        }
    }

    @Test
    fun malformedLengthClosesTheTcpStreamWithoutReadingANewPrefix() {
        val stream = BufferedAudioStream(key, format, RecordingSink())
        try {
            stream.start()
            Socket(InetAddress.getLoopbackAddress(), stream.port).use { socket ->
                socket.soTimeout = 3000
                DataOutputStream(socket.getOutputStream()).apply { writeShort(1); flush() }
                assertEquals(-1, socket.getInputStream().read())
                assertNull(stream.setRate(0L, 1, BigInteger.ZERO))
            }
        } finally { stream.close() }
    }

    @Test
    fun splitAndCoalescedTcpFramesKeepPayloadAndOrdering() {
        val packets = CopyOnWriteArrayList<ByteArray>()
        val sink = object : MediaSink {
            override fun onAudioRtp(id: AudioStreamId, format: AudioFormat, rtp: ByteArray, sample: Int) { packets += rtp }
        }
        val stream = BufferedAudioStream(key, format, sink)
        try {
            stream.start()
            Socket(InetAddress.getLoopbackAddress(), stream.port).use { socket ->
                val out = DataOutputStream(socket.getOutputStream())
                val first = seal(1, 1024L, byteArrayOf(0x21, 1, 2))
                out.writeByte((first.size + 2) shr 8); out.flush()
                out.writeByte(first.size + 2)
                out.write(first, 0, 5); out.flush()
                out.write(first, 5, first.size - 5)
                val second = seal(2, 2048L, byteArrayOf(0x21, 3, 4))
                out.writeShort(second.size + 2); out.write(second); out.flush()
                stream.setRate(1024L, 1, BigInteger.ZERO)
                waitFor { packets.size == 2 }
                assertArrayEquals(byteArrayOf(0x21, 1, 2), packets[0].copyOfRange(12, packets[0].size))
                assertArrayEquals(byteArrayOf(0x21, 3, 4), packets[1].copyOfRange(12, packets[1].size))
            }
        } finally { stream.close() }
    }

    @Test
    fun tinyAuthenticatedFramesCannotGrowAnUnboundedObjectQueue() {
        // The frame-count bound, not the paced fill: take the frames at full speed so the two
        // minutes this fills are reached inside the test's own deadline.
        val stream = BufferedAudioStream(key, format, RecordingSink(), pause = {})
        val executor = Executors.newSingleThreadExecutor()
        try {
            stream.start()
            Socket(InetAddress.getLoopbackAddress(), stream.port).use { socket ->
                val sending = executor.submit {
                    try {
                        val out = DataOutputStream(socket.getOutputStream())
                        repeat(5800) { i ->
                            val body = seal(i, i * 1024L, byteArrayOf(0x21))
                            out.writeShort(body.size + 2); out.write(body)
                        }
                        out.flush()
                    } catch (_: java.io.IOException) { }
                }
                waitFor { queuedBytes(stream) >= 5625L * 13 }
                Thread.sleep(100)
                assertEquals(5625L * 13, queuedBytes(stream))
                stream.close()
                sending.get(3, TimeUnit.SECONDS)
            }
        } finally {
            stream.close()
            executor.shutdownNow()
        }
    }

    @Test
    fun closeBeforeAcceptAndAfterAcceptCannotLeaveAReadableClientOrRestartPlayback() {
        val unconnected = BufferedAudioStream(key, format, RecordingSink())
        unconnected.start()
        unconnected.close()
        assertNull(unconnected.setRate(0L, 1, BigInteger.ZERO))
        unconnected.start()
        val connected = BufferedAudioStream(key, format, RecordingSink())
        try {
            connected.start()
            Socket(InetAddress.getLoopbackAddress(), connected.port).use { socket ->
                socket.soTimeout = 3000
                connected.close()
                try {
                    assertEquals(-1, socket.getInputStream().read())
                } catch (error: java.net.SocketException) {
                    // Closing a listener before its worker claims an already-connected client
                    // resets that connection rather than sending FIN. Both cancel the reader.
                    assertTrue(error.message.orEmpty().contains("reset", ignoreCase = true))
                }
            }
        } finally { connected.close() }
    }

    @Test
    fun aForeignSenderCannotClaimTheSingleTcpStream() {
        val stream = BufferedAudioStream(key, format, RecordingSink(),
            expectedPeer = InetAddress.getByAddress(byteArrayOf(127, 0, 0, 2)))
        try {
            stream.start()
            repeat(2) {
                Socket(InetAddress.getLoopbackAddress(), stream.port).use { socket ->
                    socket.soTimeout = 3000
                    assertEquals(-1, socket.getInputStream().read())
                }
            }
            assertNull(stream.anchor())
        } finally { stream.close() }
    }

    @Test
    fun reentrantPauseDuringSinkStartCannotDeliverStaleRtp() {
        val paused = CountDownLatch(1)
        val packets = CopyOnWriteArrayList<Int>()
        lateinit var stream: BufferedAudioStream
        val sink = object : MediaSink {
            override fun onAudioStarted(id: AudioStreamId, format: AudioFormat, firstSample: Int) {
                stream.setRate(null, 0, BigInteger.ZERO)
                paused.countDown()
            }
            override fun onAudioRtp(id: AudioStreamId, format: AudioFormat, rtp: ByteArray, sample: Int) { packets += sample }
        }
        stream = BufferedAudioStream(key, format, sink)
        try {
            stream.start()
            Socket(InetAddress.getLoopbackAddress(), stream.port).use { socket ->
                val body = seal(1, 1024L, byteArrayOf(0x21))
                DataOutputStream(socket.getOutputStream()).apply { writeShort(body.size + 2); write(body); flush() }
                stream.setRate(1024L, 1, BigInteger.ZERO)
                assertTrue(paused.await(3, TimeUnit.SECONDS))
                Thread.sleep(100)
                assertTrue(packets.isEmpty())
                assertEquals(13L, queuedBytes(stream))
            }
        } finally { stream.close() }
    }

    @Test
    fun theAnchorUsesThe1970EpochAndA64BitFraction() {
        // NTP seconds 2208988800 + 131959 (the iPhone's clock) and half a second.
        val ntp = BigInteger.valueOf(2_208_988_800L + 131_959L).shiftLeft(32).or(BigInteger.valueOf(0x8000_0000L))
        val anchor = BufferedAudioStream.anchorPlist(0x1_0000_0010L, ntp, 1)
        assertEquals(0x10L, anchor["rtpTime"])
        assertEquals(131_959L, anchor["networkTimeSecs"])
        assertEquals(BigInteger.ONE.shiftLeft(63), anchor["networkTimeFrac"])
        assertEquals(1, anchor["rate"])
    }

    @Test
    fun timestampsCompareAcrossTheWrap() {
        assertTrue(BufferedAudioStream.before(0xffff_ff00L, 0x100L))
        assertFalse(BufferedAudioStream.before(0x100L, 0xffff_ff00L))
        assertTrue(BufferedAudioStream.before(1_000L, 2_048L))
        assertFalse(BufferedAudioStream.before(2_048L, 2_048L))
    }

    @Test
    fun setRateStartsPlaybackFromItsTimeAndPausesAndFlushesStopTheOutput() {
        val sink = RecordingSink()
        val stream = BufferedAudioStream(key, format, sink)
        try {
            stream.start()
            assertNull(stream.anchor())
            Socket(InetAddress.getLoopbackAddress(), stream.port).use { socket ->
                val out = DataOutputStream(socket.getOutputStream())
                for (i in 0 until 4) {
                    val body = seal(sequence = i, timestamp = 1_000L + i * 1_024L, payload = byteArrayOf(0x21, i.toByte()))
                    out.writeShort(body.size + 2)
                    out.write(body)
                }
                out.flush()
                // Nothing plays before SETRATE.
                Thread.sleep(150)
                assertTrue(sink.rtp.isEmpty())

                val now = BigInteger.valueOf(2_208_988_800L + 100L).shiftLeft(32)
                val anchor = stream.setRate(rtpTime = 2_024L, newRate = 1, nowNtp = now)!!
                assertEquals(2_024L, anchor["rtpTime"])
                assertEquals(100L, anchor["networkTimeSecs"])
                assertEquals(anchor, stream.anchor())
                waitFor { sink.rtp.size == 3 }
                // The frame before rtpTime was dropped; playback starts at it.
                assertEquals(listOf(2_024L, 3_048L, 4_072L), sink.rtp.map { it.toLong() and 0xffff_ffffL })
                assertEquals(1, sink.started.size)

                val paused = stream.setRate(rtpTime = null, newRate = 0, nowNtp = now)!!
                assertEquals(0, paused["rate"])
                assertTrue(sink.stopped >= 1)
                val feedback = stream.feedback(now, 42L)
                assertEquals(103, feedback["type"])
                assertEquals(paused["rtpTime"], feedback["sampleTime"])

                val stopsBeforeFlush = sink.stopped
                stream.flush(untilTimestamp = 9_000L)
                assertTrue(sink.stopped >= stopsBeforeFlush)
            }
        } finally {
            stream.close()
        }
    }

    private fun seal(sequence: Int, timestamp: Long, payload: ByteArray): ByteArray {
        val header = ByteArray(12)
        header[0] = 0x80.toByte()
        header[1] = 0x60
        header[2] = (sequence shr 8).toByte(); header[3] = sequence.toByte()
        for (i in 0 until 4) header[4 + i] = (timestamp shr (24 - 8 * i)).toByte()
        val nonceTail = ByteArray(8) { (sequence + it).toByte() }
        val nonce = ByteArray(12).also { nonceTail.copyInto(it, 4) }
        val sealed = AirPlayCrypto.chachaSeal(key, nonce, payload, header.copyOfRange(4, 12))
        return header + sealed + nonceTail
    }

    private fun waitFor(condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 3_000
        while (!condition() && System.currentTimeMillis() < deadline) Thread.sleep(20)
        assertTrue("condition not met in time", condition())
    }

    private class RecordingSink : MediaSink {
        val started = CopyOnWriteArrayList<Int>()
        val rtp = CopyOnWriteArrayList<Int>()
        @Volatile var stopped = 0
        override fun onAudioStarted(id: AudioStreamId, format: AudioFormat, firstSample: Int) { started += firstSample }
        override fun onAudioRtp(id: AudioStreamId, format: AudioFormat, rtp: ByteArray, sample: Int) { this.rtp += sample }
        override fun onAudioStopped(id: AudioStreamId) { stopped++ }
    }
}
