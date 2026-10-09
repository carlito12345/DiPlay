package com.shilapi.xcertplay.airplay

import android.util.Log
import java.io.Closeable
import java.io.DataInputStream
import java.math.BigInteger
import java.net.ServerSocket
import java.net.Socket
import java.net.InetAddress
import java.util.ArrayDeque
import java.util.concurrent.atomic.AtomicBoolean

/**
 * CarPlay's main buffered audio (WWDC23 "Enhanced buffering"). For apps that support it (Apple Music),
 * the iPhone sends music ahead of time over TCP, here up to about two minutes, so short Wi-Fi gaps do
 * not interrupt playback. The car keeps the timing: SETRATE starts playback at an RTP time and the car
 * answers with the anchor (when that sample plays on the session's network clock); GETANCHOR reads it;
 * SETRATEANCHORTIME with rate 0 pauses; FLUSHBUFFERED drops what was sent before a track change.
 *
 * Frames are length-prefixed RTP packets sealed with the stream's key (shk) like the other audio
 * streams. They are kept here and handed to the [MediaSink] about [LEAD_MILLIS] ahead of playback, so
 * the media renderer (focus, ducking, channel settings) treats them like any music stream.
 */
internal class BufferedAudioStream(
    private val key: ByteArray,
    private val format: AudioFormat,
    private val sink: MediaSink,
    private val log: (String) -> Unit = {},
    private val nanoTime: () -> Long = System::nanoTime,
    private val pause: (Long) -> Unit = { Thread.sleep(it) },
    private val expectedPeer: InetAddress = InetAddress.getLoopbackAddress(),
) : Closeable {
    internal class Frame(val sequence: Int, val timestamp: Long, val rtp: ByteArray)

    private val id = AudioStreamId(format.payloadType, format.audioType)
    private val closed = AtomicBoolean(false)
    private val started = AtomicBoolean(false)
    private var cleanupStarted = false // guarded by lock; permits reentrant close without recursion
    @Volatile internal var outputCleanupComplete = false
        private set
    private val server = ServerSocket(0)
    private val boundPort = server.localPort
    @Volatile private var client: Socket? = null
    private val lock = Object()
    private val queue = ArrayDeque<Frame>()
    // Retain the small renderer preload until its estimated audible position passes it. A pause
    // destroys that renderer, so these frames must be available when playback resumes.
    private val fed = ArrayDeque<Frame>()
    // Includes queued and not-yet-audible fed frames, bounded by both bytes and frame count.
    private var queuedBytes = 0L
    private val maxBufferedFrames = (format.sampleRate * MAX_BUFFER_MILLIS / (SAMPLES_PER_FRAME * 1000)).toInt()
    // Music that must be queued before the fill is paced, and the wall time one frame then costs.
    private val pacedFillFloorFrames =
        (format.sampleRate * PACED_FILL_FLOOR_MILLIS / (SAMPLES_PER_FRAME * 1000)).toInt()
    private val pacedFrameMillis =
        (SAMPLES_PER_FRAME * 1000L / (format.sampleRate.toLong() * FILL_RATE_MULTIPLE)).coerceAtLeast(1L)

    // Playback state, guarded by [lock].
    private var rate = 0
    private var flushUntil: Long? = null
    private var anchorRtp: Long? = null
    private var anchorNtp: BigInteger? = null
    private var feedStartTimestamp: Long? = null
    private var feedStartNs = 0L
    private var fedSamples = 0L
    private var outputGeneration = 0L

    val port: Int get() = boundPort

    fun start() {
        if (closed.get() || !started.compareAndSet(false, true)) return
        Thread({ receive() }, "airplay-buffered-rx").apply { isDaemon = true; start() }
        Thread({ feed() }, "airplay-buffered-feed").apply { isDaemon = true; start() }
    }

    private fun receive() {
        var accepted: Socket? = null
        try {
            val socket = acceptPeer() ?: return
            accepted = socket
            // One stream has one TCP sender. Release the listening socket after ownership is set.
            server.close()
            log("Buffered audio: connected")
            val input = DataInputStream(socket.getInputStream().buffered())
            var failures = 0
            var receivedBytes = 0L
            var windowStart = nanoTime()
            while (!closed.get()) {
                val length = input.readUnsignedShort()
                if (length < LENGTH_BYTES + RTP_HEADER + TAG_BYTES + NONCE_BYTES) {
                    log("Buffered audio: invalid frame length $length; closed")
                    close()
                    return
                }
                val body = ByteArray(length - LENGTH_BYTES)
                input.readFully(body)
                val frame = open(key, body)
                if (frame == null) {
                    if (failures++ < 3) log("Buffered audio: frame did not open (${body.size} bytes)")
                    continue
                }
                receivedBytes += length
                val now = nanoTime()
                if (now - windowStart >= STATS_WINDOW_NS) {
                    val queuedMillis = synchronized(lock) { queue.size * SAMPLES_PER_FRAME * 1000L / format.sampleRate }
                    log("Buffered audio: queued ${queuedMillis / 1000} s, received " +
                        "${receivedBytes * 8 * 1_000_000L / ((now - windowStart) / 1000L) / 1000} kbit/s")
                    receivedBytes = 0
                    windowStart = now
                }
                if (frame.rtp.size <= RTP_HEADER) continue
                var pacing = false
                synchronized(lock) {
                    while (!closed.get() &&
                        flushUntil?.let { before(frame.timestamp, it) } != true &&
                        (queuedBytes + frame.rtp.size > AUDIO_BUFFER_BYTES || queue.size + fed.size >= maxBufferedFrames)) {
                        // TCP backpressure preserves music rather than dropping an arbitrary part
                        // of a track. Close, playback and flush all wake the bounded receiver.
                        lock.wait()
                    }
                    if (closed.get()) return
                    if (flushUntil?.let { before(frame.timestamp, it) } == true) return@synchronized
                    queue.addLast(frame)
                    queuedBytes += frame.rtp.size
                    lock.notifyAll()
                    // Past the floor, hold the intake near FILL_RATE_MULTIPLE times real time.
                    // Reading the remaining two minutes as fast as TCP allows is a multi-megabit
                    // burst that starves the realtime audio and the video sharing the link.
                    // Playback is anchored START_LATENCY_MILLIS after the first frame either way,
                    // so pacing the fill never delays the music, and a queue drained by playback
                    // or by a flush falls back below the floor and refills at full speed.
                    pacing = queue.size + fed.size >= pacedFillFloorFrames
                }
                if (pacing) pause(pacedFrameMillis)
            }
        } catch (error: Exception) {
            if (!closed.get()) log("Buffered audio: connection ended (${error.javaClass.simpleName})")
        } finally {
            runCatching { accepted?.close() }
            synchronized(lock) { if (client === accepted) client = null }
        }
    }

    private fun acceptPeer(): Socket? {
        while (!closed.get()) {
            val socket = server.accept()
            val accepted = synchronized(lock) {
                if (closed.get()) {
                    runCatching { socket.close() }
                    return null
                }
                if (socket.inetAddress != expectedPeer) {
                    runCatching { socket.close() }
                    log("Buffered audio: rejected sender outside the control session")
                    false
                } else {
                    client = socket
                    true
                }
            }
            if (accepted) return socket
        }
        return null
    }

    /** Hands frames to the sink while playing, keeping about [LEAD_MILLIS] of audio ahead of real time. */
    private fun feed() {
        try {
            while (!closed.get()) {
                synchronized(lock) {
                    if (closed.get()) return
                    retireFedLocked()
                    if (rate == 0 || queue.isEmpty() || leadMillisLocked() >= LEAD_MILLIS) {
                        lock.wait(FEED_POLL_MILLIS)
                    } else {
                        queue.pollFirst()?.also { frame ->
                            fed.addLast(frame)
                            val generation = outputGeneration
                            if (feedStartTimestamp == null) {
                                feedStartTimestamp = frame.timestamp
                                feedStartNs = nanoTime()
                                fedSamples = 0
                                sink.onAudioStarted(id, format, frame.timestamp.toInt())
                            }
                            // A sink may synchronously report state back to its owner. Respect a
                            // reentrant pause/flush/close just as a control call on another thread.
                            if (closed.get() || rate == 0 || generation != outputGeneration) return@synchronized
                            fedSamples += SAMPLES_PER_FRAME
                            // The same lock orders every sink callback against pause/flush/close.
                            // Otherwise a stale RTP callback can recreate a renderer after stop.
                            sink.onAudioRtp(id, format, frame.rtp, frame.timestamp.toInt())
                        }
                    }
                }
            }
        } catch (_: InterruptedException) {
        } catch (error: Exception) {
            if (!closed.get()) Log.w(TAG, "buffered audio feed failed", error)
            close()
        }
    }

    private fun leadMillisLocked(): Long {
        if (feedStartTimestamp == null) return 0
        val playedMillis = (nanoTime() - feedStartNs) / 1_000_000L
        return fedSamples * 1000L / format.sampleRate - playedMillis
    }

    /**
     * SETRATE / SETRATEANCHORTIME. Rate 1 starts at [rtpTime] (frames before it are dropped) about
     * [START_LATENCY_MILLIS] from [nowNtp]; rate 0 pauses where playback is. Returns the new anchor.
     */
    fun setRate(rtpTime: Long?, newRate: Int, nowNtp: BigInteger): Map<String, Any?>? = synchronized(lock) {
        if (closed.get()) return@synchronized null
        if (newRate > 0) {
            val start = rtpTime ?: positionLocked() ?: queue.peekFirst()?.timestamp
            restoreFedLocked()
            if (start != null) dropBeforeLocked(start)
            restartOutputLocked()
            anchorRtp = start
            anchorNtp = nowNtp.add(millisToNtp(START_LATENCY_MILLIS))
        } else {
            val position = positionLocked()
            restoreFedLocked()
            position?.let { anchorRtp = it; dropBeforeLocked(it) }
            anchorNtp = nowNtp
            restartOutputLocked()
        }
        rate = newRate.coerceIn(0, 1)
        lock.notifyAll()
        anchorLocked()
    }

    /** GETANCHOR: the current anchor, or null before the first SETRATE. */
    fun anchor(): Map<String, Any?>? = synchronized(lock) { anchorLocked() }

    /** FLUSHBUFFERED: drop what was sent before [untilTimestamp] and restart the output there. */
    fun flush(untilTimestamp: Long?) = synchronized(lock) {
        if (closed.get()) return@synchronized
        restoreFedLocked()
        if (untilTimestamp != null) dropBeforeLocked(untilTimestamp)
        restartOutputLocked()
        lock.notifyAll()
    }

    /** The /feedback entry for this stream: the sample playing now on the session clock. */
    fun feedback(nowNtp: BigInteger, connectionId: Any?): Map<String, Any?> = synchronized(lock) {
        linkedMapOf<String, Any?>(
            "type" to format.payloadType,
            "sampleRate" to format.sampleRate,
        ).apply {
            val position = positionLocked() ?: return@apply
            put("streamConnectionID", unsignedPlistInteger(connectionId ?: 0L))
            put("timestamp", nowNtp)
            put("timestampRawNs", nanoTime())
            put("sampleTime", position)
        }
    }

    private fun positionLocked(): Long? {
        val start = feedStartTimestamp
        if (start != null && rate > 0) {
            val playedMillis = maxOf(0L, (nanoTime() - feedStartNs) / 1_000_000L - START_LATENCY_MILLIS)
            return (start + playedMillis * format.sampleRate / 1000L) and U32
        }
        return anchorRtp
    }

    private fun anchorLocked(): Map<String, Any?>? {
        val rtp = anchorRtp ?: return null
        val at = anchorNtp ?: return null
        return anchorPlist(rtp, at, rate)
    }

    private fun dropBeforeLocked(timestamp: Long) {
        flushUntil = timestamp
        while (queue.peekFirst()?.let { before(it.timestamp, timestamp) } == true) {
            queuedBytes -= queue.pollFirst()!!.rtp.size
        }
        lock.notifyAll()
    }

    private fun retireFedLocked() {
        val position = positionLocked() ?: return
        while (fed.peekFirst()?.let { frame ->
            val end = (frame.timestamp + SAMPLES_PER_FRAME) and U32
            end == position || before(end, position)
        } == true) {
            queuedBytes -= fed.removeFirst().rtp.size
        }
        lock.notifyAll()
    }

    private fun restoreFedLocked() {
        while (fed.isNotEmpty()) queue.addFirst(fed.removeLast())
    }

    /** Stops what the sink still holds, so a pause or a track change is heard at once. */
    private fun restartOutputLocked() {
        outputGeneration++
        if (feedStartTimestamp != null) runCatching { sink.onAudioStopped(id) }
            .onFailure { log("Buffered audio: output cleanup failed (${it.javaClass.simpleName})") }
        feedStartTimestamp = null
        fedSamples = 0
    }

    override fun close() {
        closed.set(true)
        runCatching { server.close() }
        synchronized(lock) {
            // A second external closer must wait for the first one's sink cleanup, rather
            // than return just because closed was set while a delivery callback was active.
            if (cleanupStarted) return
            cleanupStarted = true
            try {
                runCatching { client?.close() }
                client = null
                queue.clear()
                fed.clear()
                queuedBytes = 0
                rate = 0
                restartOutputLocked()
            } finally {
                outputCleanupComplete = true
                lock.notifyAll()
            }
        }
    }

    companion object {
        private const val TAG = "xcertplay-usb"
        const val STREAM_TYPE = 103
        /** Advertised and locally enforced; a full buffer applies TCP backpressure. */
        const val AUDIO_BUFFER_BYTES = 8 * 1024 * 1024
        private const val MAX_BUFFER_MILLIS = 120_000L
        /** Music queued before the fill is paced, so a gap right after a track starts is covered. */
        private const val PACED_FILL_FLOOR_MILLIS = 15_000L
        /** Ceiling on the intake past that floor, as a multiple of real time. */
        private const val FILL_RATE_MULTIPLE = 4L
        internal const val LEAD_MILLIS = 1_000L
        /** About when the first fed sample is heard: the media renderer's start level and the decoder. */
        internal const val START_LATENCY_MILLIS = 400L
        private const val FEED_POLL_MILLIS = 20L
        private const val STATS_WINDOW_NS = 10_000_000_000L
        private const val SAMPLES_PER_FRAME = 1024L
        private const val LENGTH_BYTES = 2
        private const val RTP_HEADER = 12
        private const val NONCE_BYTES = 8
        private const val TAG_BYTES = 16
        private const val U32 = 0xffff_ffffL
        /** Seconds between NTP's 1900 epoch and the 1970 epoch the anchor's networkTimeSecs uses. */
        private const val NTP_UNIX_OFFSET = 2_208_988_800L

        /**
         * One buffered frame: RTP header, ChaCha20-Poly1305 ciphertext and tag sealed with [key] (the
         * header's timestamp and SSRC as associated data), then an 8-byte nonce. Null when it does not open.
         */
        internal fun open(key: ByteArray, body: ByteArray): Frame? {
            if (body.size < RTP_HEADER + TAG_BYTES + NONCE_BYTES) return null
            // This wire format has exactly a 12-byte RTP v2 header; extensions, padding and CSRCs
            // cannot be interpreted as ciphertext or passed to the AAC renderer.
            if ((body[0].toInt() and 0xff) != 0x80) return null
            val sealedEnd = body.size - NONCE_BYTES
            val nonce = ByteArray(12).also { body.copyInto(it, 4, sealedEnd, body.size) }
            val payload = runCatching {
                AirPlayCrypto.chachaOpen(key, nonce, body.copyOfRange(RTP_HEADER, sealedEnd), body.copyOfRange(4, RTP_HEADER))
            }.getOrNull() ?: return null
            val sequence = ((body[2].toInt() and 0xff) shl 8) or (body[3].toInt() and 0xff)
            return Frame(sequence, u32(body, 4), body.copyOf(RTP_HEADER) + payload)
        }

        /**
         * The anchor plist: RTP sample [rtp] plays at [ntp] (NTP64 on the session's timing clock). The
         * iPhone reads networkTimeSecs on the 1970 epoch and networkTimeFrac as a 64-bit fraction.
         */
        internal fun anchorPlist(rtp: Long, ntp: BigInteger, rate: Int): Map<String, Any?> = linkedMapOf(
            "rtpTime" to (rtp and U32),
            "networkTimeSecs" to ntp.shiftRight(32).toLong() - NTP_UNIX_OFFSET,
            "networkTimeFrac" to ntp.and(BigInteger.valueOf(U32)).shiftLeft(32),
            "rate" to rate,
        )

        internal fun millisToNtp(millis: Long): BigInteger =
            BigInteger.valueOf(millis).shiftLeft(32).divide(BigInteger.valueOf(1000))

        /** Whether RTP timestamp [a] comes before [b], across the 32-bit wrap. */
        internal fun before(a: Long, b: Long): Boolean = ((a - b) and U32) >= 0x8000_0000L

        private fun u32(bytes: ByteArray, offset: Int): Long =
            ((bytes[offset].toLong() and 0xff) shl 24) or ((bytes[offset + 1].toLong() and 0xff) shl 16) or
                ((bytes[offset + 2].toLong() and 0xff) shl 8) or (bytes[offset + 3].toLong() and 0xff)
    }
}
