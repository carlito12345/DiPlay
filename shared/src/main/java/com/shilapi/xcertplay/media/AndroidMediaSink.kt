package com.shilapi.xcertplay.media

import android.content.Context
import android.content.BroadcastReceiver
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioAttributes
import android.media.AudioFormat as AndroidAudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.media.MediaCodec
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Surface
import com.shilapi.xcertplay.airplay.AudioCodecKind
import com.shilapi.xcertplay.airplay.AudioFormat
import com.shilapi.xcertplay.airplay.AudioStreamId
import com.shilapi.xcertplay.airplay.MediaSink
import com.shilapi.xcertplay.airplay.MicrophoneConfig
import com.shilapi.xcertplay.airplay.MicrophoneCounters
import com.shilapi.xcertplay.airplay.VideoCodec
import com.shilapi.xcertplay.airplay.toHexString
import com.shilapi.xcertplay.vehicle.GeelyFactoryCarPlay
import java.io.Closeable
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.TimeUnit

/** AudioTrack's attributes getter is only available from Android 10. */
internal fun audioTrackAttributesForFocus(track: AudioTrack, configured: AudioAttributes): AudioAttributes =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) track.audioAttributes else configured

/**
 * Android rendering backend for the CarPlay media engine. Video frames are
 * decoded with MediaCodec onto a Surface; audio streams are decoded to PCM and
 * played through AudioTrack. Each audio stream keeps its own track and usage so
 * media and navigation guidance stay independently routable. Call [close]
 * when the session tears down.
 */
class AndroidMediaSink(
    surface: Surface? = null,
    private val videoWidth: Int = 1280,
    private val videoHeight: Int = 720,
    private val preferSoftwareHevcDecoder: Boolean = false,
    private val advancedAudioChannelMapping: Boolean = false,
    private val audioFocusEnabled: Boolean = false,
    private val mediaChannel: Int = 0,
    private val navigationChannel: Int = 0,
    context: Context? = null,
    private val navigationStreamType: Int = AudioChannelMapper.DEFAULT_NAVIGATION_STREAM_TYPE,
    onScreenStreamActiveChanged: ((Int, Boolean) -> Unit)? = null,
    private val mediaBufferMillis: Int = MediaAudioBuffer.DEFAULT_MILLIS,
    private val onAudioDiagnostic: (String) -> Unit = {},
    /** True while any music ("media") audio stream is running; called from media threads. */
    private val onMediaAudioChanged: (Boolean) -> Unit = {},
    private val wirelessAudio: Boolean = false,
    private val videoFps: Int = 60,
    private val navigationOutputDevice: AudioOutputDevice? = null,
    private val audioOutputRoutes: AudioOutputRoutes = AudioOutputRoutes(),
    onAudioOwnershipChanged: (Boolean) -> Unit = {},
    private val unifiedMediaOutput: Boolean = false,
    private val bluetoothOutput: Boolean = false,
) : MediaSink {
    private val appContext = context?.applicationContext
    private val factoryAudio = appContext?.let(GeelyFactoryCarPlay::load)
    private val audioManager = appContext?.getSystemService(AudioManager::class.java)
    @Volatile private var audioOwnershipListener: ((Boolean) -> Unit)? = onAudioOwnershipChanged
    @Volatile private var ownsAudio = false
    // carlito | Rebind the live controller when an Activity adopts a background session.
    fun setAudioOwnershipChangedListener(listener: ((Boolean) -> Unit)?) {
        audioOwnershipListener = listener
        listener?.invoke(ownsAudio)
    }
    private val audioFocusCoordinator = AudioFocusCoordinator(
        appContext,
        audioFocusEnabled,
        onAudioDiagnostic,
        factoryRouting = factoryAudio != null && !bluetoothOutput,
        unifiedMediaOutput = unifiedMediaOutput,
        onOwnershipChanged = { owned -> ownsAudio = owned; audioOwnershipListener?.invoke(owned) },
    )
    private val screenStateLock = Any()
    private val activeScreenTypes = mutableSetOf<Int>()
    private val defaultSurface = surface
    @Volatile private var screenStreamActiveChanged = onScreenStreamActiveChanged
    private val surfaces = ConcurrentHashMap<Int, Surface>()
    private val videoDecoders = ConcurrentHashMap<Int, VideoDecoder>()
    private val mediaAudioTypes = mutableSetOf<AudioStreamId>()
    private val telephonyAudioTypes = mutableSetOf<AudioStreamId>()
    private val audioRenderers = ConcurrentHashMap<AudioStreamId, AudioRenderer>()
    // carlito | Capture restarts retain the same RTP counters until the negotiated stream ends.
    private data class PendingMicrophone(val config: MicrophoneConfig, val counters: MicrophoneCounters = MicrophoneCounters(), var retryAt: Long = 0L)
    private data class ActiveMicrophone(val request: PendingMicrophone, val uplink: MicrophoneUplink)
    private val pendingMicrophones = ConcurrentHashMap<AudioStreamId, PendingMicrophone>()
    private val microphoneUplinks = ConcurrentHashMap<AudioStreamId, ActiveMicrophone>()
    // carlito | Report input readiness separately from the selected playback channel.
    private var lastMicrophoneRouteDiagnostic: String? = null
    private val audioRouteWorker = Executors.newSingleThreadScheduledExecutor { Thread(it, "carplay-audio-route").apply { isDaemon = true } }
    private val communicationRoute = CommunicationAudioRoute(appContext, onAudioDiagnostic)
    private var scoRegistered = false
    private val audioDeviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(devices: Array<out AudioDeviceInfo>) = deviceRoutesChanged()
        override fun onAudioDevicesRemoved(devices: Array<out AudioDeviceInfo>) = deviceRoutesChanged()
    }
    private val scoReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != AudioManager.ACTION_SCO_AUDIO_STATE_UPDATED) return
            queueAudioRoute {
                communicationRoute.onScoState(intent.getIntExtra(AudioManager.EXTRA_SCO_AUDIO_STATE, -1) == AudioManager.SCO_AUDIO_STATE_CONNECTED)
                refreshCommunication()
            }
        }
    }
    private val audioModeLock = Any()
    private val captureLock = Any()
    private val closingCommunicationRenderers = mutableSetOf<AudioRenderer>()
    @Volatile private var closed = false
    private val pendingVideoCodec = ConcurrentHashMap<Int, VideoCodec>()
    private val videoRecoveryHandlers = ConcurrentHashMap<Int, () -> Unit>()
    private val videoDiagnosticHandlers = ConcurrentHashMap<Int, (String) -> Unit>()
    private val recoveryPending = AtomicBoolean(false)
    // Extra decoders draw the same stream on other surfaces, such as the centre card.
    private val mirrorLock = Any()
    private val mirrorSurfaces = HashMap<Pair<Int, String>, Surface>()
    private val mirrorDecoders = HashMap<Pair<Int, String>, VideoDecoder>()
    private val lastVideoConfig = ConcurrentHashMap<Int, Pair<VideoCodec, ByteArray>>()
    private val recoveryExecutor = Executors.newSingleThreadExecutor { task ->
        Thread(task, "carplay-video-recovery").apply { isDaemon = true }
    }

    // carlito | Devices and native-call mode are observed while this sink owns a live session.
    private val routePoll = appContext?.let {
        audioRouteWorker.scheduleWithFixedDelay({ queueSafeRouteRefresh() }, 0, 500, TimeUnit.MILLISECONDS)
    }
    init {
        runCatching { audioManager?.registerAudioDeviceCallback(audioDeviceCallback, Handler(Looper.getMainLooper())) }
        appContext?.let { app -> runCatching {
            val filter = IntentFilter(AudioManager.ACTION_SCO_AUDIO_STATE_UPDATED)
            if (Build.VERSION.SDK_INT >= 33) app.registerReceiver(scoReceiver, filter, Context.RECEIVER_EXPORTED)
            else app.registerReceiver(scoReceiver, filter)
            scoRegistered = true
        } }
    }

    override fun setVideoRecoveryHandler(type: Int, handler: () -> Unit) {
        videoRecoveryHandlers[type] = handler
    }

    override fun setVideoDiagnosticHandler(type: Int, handler: (String) -> Unit) {
        videoDiagnosticHandlers[type] = handler
    }

    private fun requestVideoRecovery(type: Int) {
        if (!recoveryPending.compareAndSet(false, true)) return
        try {
            recoveryExecutor.execute {
                try { videoRecoveryHandlers[type]?.invoke() }
                catch (error: Exception) { Log.w("xcertplay-usb", "Video keyframe request failed", error) }
                finally { recoveryPending.set(false) }
            }
        } catch (_: java.util.concurrent.RejectedExecutionException) { recoveryPending.set(false) }
    }

    fun setSurface(type: Int, surface: Surface) {
        surfaces[type] = surface
        videoDecoders[type]?.setSurface(surface)
    }

    fun clearSurface(type: Int, surface: Surface) {
        if (surfaces.remove(type, surface)) videoDecoders[type]?.setSurface(null)
    }

    /**
     * Also decodes stream [type] onto [surface] with its own decoder, one per [key], which
     * starts at the next keyframe it asks for; null stops it. The stream's own surface is not affected.
     */
    fun setMirrorSurface(type: Int, key: String, surface: Surface?) {
        val id = type to key
        synchronized(mirrorLock) {
            mirrorDecoders.remove(id)?.close()
            if (surface == null) {
                mirrorSurfaces.remove(id)
                return
            }
            mirrorSurfaces[id] = surface
        }
        lastVideoConfig[type]?.let { (codec, data) -> mirrorDecoders(type).forEach { it.configure(codec, data) } }
    }

    private fun mirrorDecoders(type: Int): List<VideoDecoder> = synchronized(mirrorLock) {
        if (mirrorSurfaces.isEmpty()) return emptyList()
        mirrorSurfaces.filterKeys { it.first == type }.map { (id, surface) ->
            mirrorDecoders.getOrPut(id) { newVideoDecoder(type, surface, " stream=$type mirror=${id.second}") }
        }
    }

    fun setScreenStreamActiveChangedListener(listener: ((Int, Boolean) -> Unit)?) {
        synchronized(screenStateLock) {
            screenStreamActiveChanged = listener
            activeScreenTypes.forEach { listener?.invoke(it, true) }
        }
    }

    override fun onVideoCodec(type: Int, codec: VideoCodec) {
        pendingVideoCodec[type] = codec
    }

    override fun onVideoConfig(type: Int, codecData: ByteArray) {
        val codec = pendingVideoCodec[type] ?: VideoCodec.H264
        lastVideoConfig[type] = codec to codecData
        videoDecoder(type).configure(codec, codecData)
        mirrorDecoders(type).forEach { it.configure(codec, codecData) }
    }

    override fun onVideoFrame(type: Int, naluBytes: ByteArray) {
        videoDecoder(type).submit(naluBytes)
        mirrorDecoders(type).forEach { it.submit(naluBytes) }
    }

    override fun onScreenStreamActive(type: Int, active: Boolean) {
        if (!active) {
            videoRecoveryHandlers.remove(type)
            videoDiagnosticHandlers.remove(type)
            videoDecoders.remove(type)?.close()
            synchronized(mirrorLock) {
                mirrorDecoders.keys.filter { it.first == type }.forEach { mirrorDecoders.remove(it)?.close() }
            }
            lastVideoConfig.remove(type)
            pendingVideoCodec.remove(type)
        }
        synchronized(screenStateLock) {
            if (active) activeScreenTypes.add(type) else activeScreenTypes.remove(type)
            screenStreamActiveChanged?.invoke(type, active)
        }
    }

    override fun onAudioStarted(id: AudioStreamId, format: AudioFormat, firstSample: Int) {
        if (closed) return
        if (isPhoneAudio(format.audioType)) synchronized(telephonyAudioTypes) {
            telephonyAudioTypes.add(id)
        }
        audioRenderer(id, format).start()
        queueAudioRoute(::refreshCommunication)
        if (format.audioType == "media") updateMediaAudio(id, true)
    }

    override fun onAudioRtp(id: AudioStreamId, format: AudioFormat, rtp: ByteArray, sample: Int) {
        if (closed) return
        audioRenderer(id, format).submit(rtp, sample)
    }

    override fun onAudioStopped(id: AudioStreamId) {
        val renderer = audioRenderers.remove(id)
        if (renderer?.format?.audioType?.let { isPhoneAudio(it) || it.equals("speechrecognition", true) } == true) synchronized(audioModeLock) {
            closingCommunicationRenderers.add(renderer)
        }
        updateMediaAudio(id, false)
        val callEnded = synchronized(telephonyAudioTypes) {
            telephonyAudioTypes.remove(id) && telephonyAudioTypes.isEmpty()
        }
        renderer?.close()
        if (callEnded && renderer?.format?.audioType?.let(::isPhoneAudio) != true) finishCommunication(force = true)
        queueAudioRoute(::refreshCommunication)
    }

    private fun finishCommunication(force: Boolean = false) = queueAudioRoute(::refreshCommunication)

    private fun updateMediaAudio(id: AudioStreamId, active: Boolean) {
        val (before, after) = synchronized(mediaAudioTypes) {
            val before = mediaAudioTypes.isNotEmpty()
            if (active) mediaAudioTypes.add(id) else mediaAudioTypes.remove(id)
            before to mediaAudioTypes.isNotEmpty()
        }
        if (before != after) {
            // carlito | Actual stream transitions also work when the phone omits Now Playing metadata.
            audioFocusCoordinator.onMediaPlaying(after)
            onMediaAudioChanged(after)
        }
    }

    override fun onMicrophoneStarted(id: AudioStreamId, config: MicrophoneConfig) {
        if (closed) return
        pendingMicrophones.compute(id) { _, previous ->
            if (previous != null && config.key.contentEquals(previous.config.key)) {
                if (config.copy(key = previous.config.key) == previous.config) previous
                else PendingMicrophone(config, previous.counters)
            } else PendingMicrophone(config)
        }
        queueSafeRouteRefresh()
    }

    override fun onMicrophoneStopped(id: AudioStreamId) {
        pendingMicrophones.remove(id)
        queueSafeRouteRefresh()
    }

    private fun queueAudioRoute(action: () -> Unit) {
        if (!closed) runCatching { audioRouteWorker.execute { if (!closed) runCatching(action).onFailure {
            runCatching { onAudioDiagnostic("Audio: route update failed ${it.javaClass.simpleName}") }
        } } }
    }
    private fun queueSafeRouteRefresh() {
        if (!closed) runCatching { refreshCommunication() }.onFailure {
            runCatching { onAudioDiagnostic("Audio: route recovery pending ${it.javaClass.simpleName}") }
        }
    }
    private fun deviceRoutesChanged() = queueAudioRoute {
        audioRenderers.values.forEach { it.refreshOutputDevice() }
        microphoneUplinks.values.forEach { it.uplink.refreshInputDevice() }
        pendingMicrophones.values.forEach { it.retryAt = 0L }
        communicationRoute.refreshDevices(retry = true)
        refreshCommunication()
    }

    private fun refreshCommunication(): Unit = synchronized(captureLock) {
        if (closed) return
        val closing = synchronized(audioModeLock) { closingCommunicationRenderers.toList() }
        // carlito | Honor the recorder retry delay before reacquiring a call input route.
        val phoneCapture = pendingMicrophones.values.any {
            isPhoneAudio(it.config.audioType) && (it.retryAt == 0L || android.os.SystemClock.elapsedRealtime() >= it.retryAt) }
        val phone = synchronized(telephonyAudioTypes) { telephonyAudioTypes.isNotEmpty() } ||
            closing.any { isPhoneAudio(it.format.audioType) } || phoneCapture
        val assistant = pendingMicrophones.values.any { it.config.audioType.equals("speechrecognition", true) } ||
            (audioRenderers.values + closing).any { it.format.audioType.equals("speechrecognition", true) }
        val assistantVoiceDevice = (!unifiedMediaOutput && audioOutputRoutes.assistant?.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO) ||
            audioOutputRoutes.assistantMicrophone?.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO
        // carlito | Unified playback does not disable the mode/device required for call capture.
        // Playback-only media streams do not acquire a communication route in unified mode.
        val needCommunication = (!unifiedMediaOutput && phone || phoneCapture) || assistant && assistantVoiceDevice
        val hadCommunication = communicationRoute.held()
        val external = communicationRoute.externalCall()
        if (needCommunication && !external) communicationRoute.acquire(
            if (unifiedMediaOutput) null else if (phone) audioOutputRoutes.phone else audioOutputRoutes.assistant,
            if (phone) audioOutputRoutes.phoneMicrophone else audioOutputRoutes.assistantMicrophone)
        audioFocusCoordinator.setExternalCall(communicationRoute.externalCall())
        audioFocusCoordinator.setMicrophones(
            pendingMicrophones.values.any { isPhoneAudio(it.config.audioType) },
            !phone && pendingMicrophones.values.any { it.config.audioType.equals("speechrecognition", true) })
        val ready = !communicationRoute.externalCall() && audioFocusCoordinator.captureAllowed() &&
            (!needCommunication || communicationRoute.captureReady())
        val routeState = when {
            pendingMicrophones.isEmpty() -> null
            communicationRoute.externalCall() -> "blocked_native_call"
            !audioFocusCoordinator.captureAllowed() -> "waiting_audio_focus"
            !ready -> "waiting_communication_route"
            else -> "ready"
        }
        val routeDiagnostic = routeState?.let {
            "Microphone: route state=$it systemMuted=${runCatching { audioManager?.isMicrophoneMute }.getOrNull() ?: "unknown"}"
        }
        if (routeDiagnostic != lastMicrophoneRouteDiagnostic) {
            lastMicrophoneRouteDiagnostic = routeDiagnostic
            routeDiagnostic?.let { runCatching { onAudioDiagnostic(it) } }
        }
        microphoneUplinks.entries.toList().forEach { (id, active) ->
            if (pendingMicrophones[id] !== active.request || !ready || phone && !isPhoneAudio(active.request.config.audioType)) {
                if (microphoneUplinks.remove(id, active)) active.uplink.close()
            }
        }
        if (!needCommunication && hadCommunication) {
            communicationRoute.release()
            audioFocusCoordinator.onCommunicationEnded()
            audioRenderers.values.filter { it.format.audioType == "media" }.forEach { it.resumeAfterCommunication() }
        } else if (needCommunication) communicationRoute.refreshDevices()
        if (!ready) return
        pendingMicrophones.entries.toList().forEach { (id, pending) ->
            if (microphoneUplinks.containsKey(id) || phone && !isPhoneAudio(pending.config.audioType) ||
                android.os.SystemClock.elapsedRealtime() < pending.retryAt) return@forEach
            try {
                val config = pending.config
                val uplink = MicrophoneUplink(config, onAudioDiagnostic,
                    factorySource = factoryAudio?.microphoneSource(config.audioType, config.sampleRate, wirelessAudio),
                    preferredInput = audioOutputRoutes.microphone(config.audioType),
                    audioManager = audioManager, counters = pending.counters)
                if (uplink.start() && !closed && pendingMicrophones[id] === pending) microphoneUplinks[id] = ActiveMicrophone(pending, uplink)
                else { uplink.close(); pending.retryAt = android.os.SystemClock.elapsedRealtime() + 5_000L }
            } catch (error: Exception) {
                pending.retryAt = android.os.SystemClock.elapsedRealtime() + 5_000L
                MicrophoneCaptureStats.reportStartFailure(pending.config, error, onAudioDiagnostic)
            }
        }
        if (phone && microphoneUplinks.isEmpty() && synchronized(telephonyAudioTypes) { telephonyAudioTypes.isEmpty() } && closing.isEmpty() &&
            pendingMicrophones.values.none { isPhoneAudio(it.config.audioType) && it.retryAt == 0L }) {
            communicationRoute.release()
            audioFocusCoordinator.onCommunicationEnded()
        }
    }

    @Synchronized fun close() {
        if (closed) return
        closed = true
        routePoll?.cancel(false)
        runCatching { audioManager?.unregisterAudioDeviceCallback(audioDeviceCallback) }
        if (scoRegistered) runCatching { appContext?.unregisterReceiver(scoReceiver) }
        audioFocusCoordinator.close()
        audioOwnershipListener = null
        synchronized(screenStateLock) {
            activeScreenTypes.forEach { screenStreamActiveChanged?.invoke(it, false) }
            activeScreenTypes.clear()
            screenStreamActiveChanged = null
        }
        videoDecoders.values.forEach(VideoDecoder::close)
        videoDecoders.clear()
        synchronized(mirrorLock) {
            mirrorDecoders.values.forEach(VideoDecoder::close)
            mirrorDecoders.clear()
            mirrorSurfaces.clear()
        }
        videoRecoveryHandlers.clear()
        videoDiagnosticHandlers.clear()
        recoveryExecutor.shutdownNow()
        audioRenderers.values.forEach(AudioRenderer::close)
        audioRenderers.clear()
        synchronized(telephonyAudioTypes) { telephonyAudioTypes.clear() }
        val hadMedia = synchronized(mediaAudioTypes) { mediaAudioTypes.isNotEmpty().also { mediaAudioTypes.clear() } }
        if (hadMedia) onMediaAudioChanged(false)
        pendingMicrophones.clear()
        synchronized(captureLock) {
            try { microphoneUplinks.values.forEach { it.uplink.close() }; microphoneUplinks.clear() }
            finally { communicationRoute.close() }
        }
        audioRouteWorker.shutdown()
    }

    private fun videoDecoder(type: Int): VideoDecoder =
        videoDecoders.computeIfAbsent(type) { newVideoDecoder(type, surfaces[type] ?: defaultSurface) }

    private fun newVideoDecoder(type: Int, surface: Surface?, statsLabel: String? = null) = VideoDecoder(
        type,
        surface,
        videoWidth,
        videoHeight,
        preferSoftwareHevcDecoder,
        videoFps,
        requestKeyFrame = { requestVideoRecovery(type) },
        report = { videoDiagnosticHandlers[type]?.invoke(it) },
        statsLabel = statsLabel,
    )

    @Synchronized
    private fun audioRenderer(id: AudioStreamId, format: AudioFormat): AudioRenderer {
        val existing = audioRenderers[id]
        if (existing?.format == format) return existing
        existing?.close()
        return AudioRenderer(
            format,
            advancedAudioChannelMapping,
            audioFocusEnabled,
            mediaChannel,
            navigationChannel,
            audioFocusCoordinator,
            navigationStreamType,
            mediaBufferMillis,
            onAudioDiagnostic,
            factoryAudio,
            audioManager,
            navigationOutputDevice,
            audioOutputRoutes,
            unifiedMediaOutput,
            onReleased = { renderer ->
                val callReleased = synchronized(audioModeLock) { closingCommunicationRenderers.remove(renderer) }
                if (callReleased) finishCommunication(force = true)
            },
        ).also { audioRenderers[id] = it }
    }

    // carlito | Never drop negotiated streams or microphones. Only confirmed native music yields locally.
    fun setNativeBluetoothPlaying(playing: Boolean) {
        if (bluetoothOutput) audioFocusCoordinator.setNativeBluetoothPlaying(playing)
    }

    fun onMediaPlaying(playing: Boolean) {
        audioFocusCoordinator.onMediaPlaying(playing)
    }
}

/** Serial MediaCodec video decoder: one worker owns configure and frame feeding. */
private class VideoDecoder(
    streamType: Int,
    surface: Surface?,
    private val width: Int,
    private val height: Int,
    private val preferSoftwareHevcDecoder: Boolean,
    private val fps: Int,
    private val requestKeyFrame: () -> Unit,
    private val report: (String) -> Unit,
    statsLabel: String? = null,
) : Closeable {
    private val queue = VideoDecodeQueue()
    @Volatile private var running = true
    @Volatile private var decoder: MediaCodec? = null
    private var outputSurface: Surface? = surface
    private var lastConfig: VideoJob.Config? = null
    private var renderedFrameLogged = false
    private var submittedFrameLogged = false
    private var duplicateConfigLogged = false
    private var failureReports = 0
    private val referenceChain = VideoReferenceChain()
    private var lastKeyFrameRequestNs = 0L
    // The main screen keeps the historical log format; other screens are labelled.
    private val stats = VideoStats(statsLabel ?: if (streamType == 110) "" else " stream=$streamType")
    private val thread = Thread(::run, "carplay-video").apply { isDaemon = true; start() }

    fun configure(codec: VideoCodec, codecData: ByteArray) {
        queue.offer(VideoJob.Config(codec, codecData))
    }

    fun submit(nalus: ByteArray) {
        stats.onReceived(nalus.size)
        queue.offer(VideoJob.Frame(nalus))
    }

    fun setSurface(surface: Surface?) {
        queue.offer(VideoJob.SurfaceChanged(surface))
    }

    override fun close() {
        running = false
        thread.interrupt()
    }

    private fun run() {
        try {
            while (running) {
                val job = queue.poll(5)
                try {
                    when (job) {
                        is VideoJob.Config -> configureDecoder(job)
                        is VideoJob.Frame -> {
                            if (System.nanoTime() - job.receivedNs > MAX_FRAME_AGE_NS) {
                                queue.discardFrames()
                                recover("video backlog exceeded 250 ms")
                            } else feed(job)
                        }
                        is VideoJob.SurfaceChanged -> changeSurface(job.surface)
                        is VideoJob.Resync -> recover("video queue overflow")
                        null -> Unit
                    }
                    decoder?.let(::drainOutput)
                    stats.logIfDue()?.let(report)
                    if (referenceChain.needsKeyFrame && lastConfig != null && outputSurface != null) requestKeyFrameIfDue()
                } catch (error: Exception) {
                    if (running) Log.e(TAG, "video decoder job failed: ${job?.javaClass?.simpleName}", error)
                    if (running) reportFailure("stage=${job?.javaClass?.simpleName ?: "drain"}", error)
                    releaseDecoder()
                    referenceChain.reset()
                    requestKeyFrameIfDue()
                } catch (error: LinkageError) {
                    if (running) reportFailure("stage=${job?.javaClass?.simpleName ?: "drain"}", error)
                    throw error
                }
            }
        } catch (_: InterruptedException) {
            // Worker shut down.
        } finally {
            releaseDecoder()
        }
    }

    private fun configureDecoder(config: VideoJob.Config) {
        val previous = lastConfig
        if (
            decoder != null &&
            previous?.codec == config.codec &&
            previous.codecData.contentEquals(config.codecData)
        ) {
            if (!duplicateConfigLogged) {
                duplicateConfigLogged = true
                Log.i(TAG, "video decoder config unchanged; keeping existing decoder")
            }
            return
        }
        lastConfig = config
        duplicateConfigLogged = false
        releaseDecoder()
        referenceChain.reset()
        val surface = outputSurface ?: return
        val codec = config.codec
        val codecData = config.codecData
        val mime = if (codec == VideoCodec.H265) MediaFormat.MIMETYPE_VIDEO_HEVC
        else MediaFormat.MIMETYPE_VIDEO_AVC
        val csd = if (codec == VideoCodec.H265) {
            MediaCodecSupport.hevcCodecSpecificData(codecData).takeIf { it.isNotEmpty() }
                ?.let { listOf(it) } ?: emptyList()
        } else {
            val (sps, pps) = MediaCodecSupport.avcParameterSets(codecData)
            listOfNotNull(
                sps.takeIf { it.isNotEmpty() }?.let { START_CODE + it },
                pps.takeIf { it.isNotEmpty() }?.let { START_CODE + it },
            )
        }
        // Some vendor decoders (e.g. MediaTek c2.mtk.avc.decoder) reject the tuned
        // parameters with BAD_VALUE. Fall back to a minimal format, then to software.
        val candidates = VideoDecoderSupport.candidates(mime, width, height, fps)
        val softwareOnly = codec == VideoCodec.H265 && preferSoftwareHevcDecoder
        val preferred = candidates.filter { VideoDecoderSupport.isHardware(it) != softwareOnly }
        val attempts = preferred.flatMap {
            listOf(DecoderAttempt(it.name, tuned = true), DecoderAttempt(it.name, tuned = false))
        } + listOf(DecoderAttempt(codecName = null, tuned = true), DecoderAttempt(codecName = null, tuned = false)) +
            candidates.filter { !VideoDecoderSupport.isHardware(it) }
                .map { DecoderAttempt(it.name, tuned = false) }
        var next: MediaCodec? = null
        for (attempt in attempts) {
            next = tryConfigure(mime, csd, surface, attempt)
            if (next != null) break
        }
        if (next == null) {
            report("decoder configuration failed mime=$mime size=${width}x$height")
        }
        decoder = next
        renderedFrameLogged = false
        submittedFrameLogged = false
        if (next != null) {
            report("decoder=${next.name} mime=$mime size=${width}x$height")
            Log.i(
                TAG,
                "video decoder configured name=${next.name} mime=$mime size=${width}x$height",
            )
        }
    }

    private data class DecoderAttempt(val codecName: String?, val tuned: Boolean)

    private fun buildFormat(mime: String, csd: List<ByteArray>, tuned: Boolean): MediaFormat =
        MediaFormat.createVideoFormat(mime, width, height).apply {
            if (tuned) {
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, MAX_INPUT_SIZE)
                setInteger(MediaFormat.KEY_PRIORITY, 0)
            }
            csd.forEachIndexed { index, bytes -> setByteBuffer("csd-$index", ByteBuffer.wrap(bytes)) }
        }

    private fun tryConfigure(
        mime: String,
        csd: List<ByteArray>,
        surface: Surface,
        attempt: DecoderAttempt,
    ): MediaCodec? {
        var candidate: MediaCodec? = null
        return try {
            val format = buildFormat(mime, csd, attempt.tuned)
            val codec = attempt.codecName?.let { MediaCodec.createByCodecName(it) } ?: createDecoder(mime)
            candidate = codec
            if (attempt.tuned && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
                codec.codecInfo.getCapabilitiesForType(mime).isFeatureSupported("low-latency")) {
                format.setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
            }
            codec.configure(format, surface, null, 0)
            codec.start()
            codec
        } catch (error: Exception) {
            runCatching { candidate?.release() }
            reportFailure("stage=configure tuned=${attempt.tuned} mime=$mime", error)
            Log.w(
                TAG,
                "video decoder configure failed name=${attempt.codecName ?: "default"} " +
                    "tuned=${attempt.tuned} mime=$mime size=${width}x$height",
                error,
            )
            null
        }
    }

    private fun createDecoder(mime: String): MediaCodec {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
            mime == MediaFormat.MIMETYPE_VIDEO_HEVC &&
            preferSoftwareHevcDecoder
        ) {
            val software = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.firstOrNull {
                !it.isEncoder && it.isSoftwareOnly && mime in it.supportedTypes
            }
            if (software != null) {
                try {
                    return MediaCodec.createByCodecName(software.name)
                } catch (error: Exception) {
                    Log.w(TAG, "software HEVC decoder unavailable name=${software.name}", error)
                }
            }
        }
        return MediaCodec.createDecoderByType(mime)
    }

    private fun changeSurface(surface: Surface?) {
        if (outputSurface === surface) return
        outputSurface = surface
        if (surface == null) {
            releaseDecoder()
            Log.i(TAG, "video decoder detached from surface")
            return
        }
        val codec = decoder
        if (codec != null) {
            try {
                codec.setOutputSurface(surface)
                Log.i(TAG, "video decoder output surface updated")
                return
            } catch (error: Exception) {
                Log.w(TAG, "video decoder output surface update failed; reconfiguring", error)
            }
        }
        releaseDecoder()
        lastConfig?.let(::configureDecoder)
    }

    private fun feed(frame: VideoJob.Frame) {
        val nalus = frame.nalus
        val annexB = MediaCodecSupport.toAnnexB(nalus)
        val config = lastConfig ?: return
        if (outputSurface == null) return
        if (annexB.isEmpty()) { recover("invalid video access unit"); return }
        if (!referenceChain.accepts(annexB, config.codec)) {
            requestKeyFrameIfDue()
            return
        }
        if (decoder == null) configureDecoder(config)
        val codec = decoder ?: return
        if (!submittedFrameLogged) {
            submittedFrameLogged = true
            Log.i(
                TAG,
                "video decoder first input avcc=${nalus.size} annexB=${annexB.size}",
            )
        }
        val index = VideoInputPump.acquire(
            running = { running }, drain = { drainOutput(codec) },
            dequeue = { codec.dequeueInputBuffer(INPUT_TIMEOUT_US) },
        )
        if (index < 0) { recover("video decoder input stalled"); return }
        if (System.nanoTime() - frame.receivedNs > MAX_FRAME_AGE_NS) {
            recover("video frame expired while waiting for decoder input")
            return
        }
        val input = checkNotNull(codec.getInputBuffer(index)) { "Decoder input buffer unavailable" }
        input.clear()
        if (annexB.size <= input.remaining()) {
            input.put(annexB)
            codec.queueInputBuffer(index, 0, annexB.size, frame.receivedNs / 1000, 0)
            referenceChain.onQueued()
        } else {
            recover("video frame exceeded codec input capacity")
            return
        }
        drainOutput(codec)
    }

    private fun recover(reason: String) {
        Log.w(TAG, "Video recovery: $reason; waiting for keyframe")
        stats.onRecovery()
        report("recovery: $reason; waiting for keyframe")
        // Recreate with codec-specific data: flush can discard CSD before the first output.
        releaseDecoder()
        referenceChain.reset()
        requestKeyFrameIfDue()
    }

    private fun requestKeyFrameIfDue() {
        val now = System.nanoTime()
        if (lastKeyFrameRequestNs != 0L && now - lastKeyFrameRequestNs < 1_000_000_000L) return
        lastKeyFrameRequestNs = now
        runCatching { requestKeyFrame() }
            .onFailure { reportFailure("stage=requestKeyFrame", it) }
    }

    private fun drainOutput(codec: MediaCodec) {
        val info = MediaCodec.BufferInfo()
        while (running) {
            val index = codec.dequeueOutputBuffer(info, 0)
            when {
                index == MediaCodec.INFO_TRY_AGAIN_LATER -> return
                index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> logOutputFormat(codec.outputFormat)
                index >= 0 -> {
                    // Output frames can be skipped without breaking the decoder's reference chain.
                    val render = outputSurface != null &&
                        System.nanoTime() - info.presentationTimeUs * 1000 <= MAX_FRAME_AGE_NS
                    codec.releaseOutputBuffer(index, render)
                    if (render) stats.onRendered()
                    if (render && !renderedFrameLogged) {
                        renderedFrameLogged = true
                        report("first frame rendered")
                        Log.i(TAG, "video decoder rendered first frame bytes=${info.size}")
                    }
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
                }
                else -> return
            }
        }
    }

    private fun logOutputFormat(format: MediaFormat) {
        report("output format requested=${width}x${height} " +
            "coded=${format.intOrNull(MediaFormat.KEY_WIDTH)}x${format.intOrNull(MediaFormat.KEY_HEIGHT)} " +
            "crop=${format.intOrNull("crop-left")},${format.intOrNull("crop-top")}," +
            "${format.intOrNull("crop-right")},${format.intOrNull("crop-bottom")} " +
            "stride=${format.intOrNull(MediaFormat.KEY_STRIDE)} slice=${format.intOrNull(MediaFormat.KEY_SLICE_HEIGHT)} " +
            "color=${format.intOrNull(MediaFormat.KEY_COLOR_STANDARD)}/${format.intOrNull(MediaFormat.KEY_COLOR_RANGE)}/${format.intOrNull(MediaFormat.KEY_COLOR_TRANSFER)}")
        Log.i(
            TAG,
            "video decoder output format " +
                "size=${format.intOrNull(MediaFormat.KEY_WIDTH)}x" +
                "${format.intOrNull(MediaFormat.KEY_HEIGHT)} " +
                "stride=${format.intOrNull(MediaFormat.KEY_STRIDE)} " +
                "slice=${format.intOrNull(MediaFormat.KEY_SLICE_HEIGHT)} " +
                "standard=${format.intOrNull(MediaFormat.KEY_COLOR_STANDARD)} " +
                "range=${format.intOrNull(MediaFormat.KEY_COLOR_RANGE)} " +
                "transfer=${format.intOrNull(MediaFormat.KEY_COLOR_TRANSFER)}",
        )
    }

    private fun reportFailure(context: String, error: Throwable) {
        // A bad decoder can fail again on every frame; exported detail stays bounded per worker.
        if (failureReports >= 8) return
        failureReports++
        runCatching { report("decoder failed api=${Build.VERSION.SDK_INT} $context " +
            MediaFailureSummary.describe(error)) }
    }

    @Synchronized
    private fun releaseDecoder() {
        val codec = decoder
        decoder = null
        if (codec != null) {
            try {
                codec.stop()
            } catch (_: Exception) {
                // Best effort.
            }
            try {
                codec.release()
            } catch (_: Exception) {
                // Best effort.
            }
        }
    }

    private companion object {
        const val TAG = "xcertplay-usb"
        const val MAX_INPUT_SIZE = 8 * 1024 * 1024
        const val INPUT_TIMEOUT_US = 10_000L
        const val MAX_FRAME_AGE_NS = 250_000_000L
        val START_CODE = byteArrayOf(0x00, 0x00, 0x00, 0x01)
    }
}

private fun MediaFormat.intOrNull(key: String): Int? =
    if (!containsKey(key)) {
        null
    } else {
        try {
            getInteger(key)
        } catch (_: Exception) {
            null
        }
    }

/** Decodes AAC-LC/Opus to PCM and plays it, or plays wired LPCM directly. */
private class AudioRenderer(
    val format: AudioFormat,
    private val advancedAudioChannelMapping: Boolean,
    private val audioFocusEnabled: Boolean,
    private val mediaChannel: Int,
    private val navigationChannel: Int,
    private val audioFocusCoordinator: AudioFocusCoordinator,
    private val navigationStreamType: Int,
    private val mediaBufferMillis: Int,
    private val report: (String) -> Unit,
    private val factoryAudio: GeelyFactoryCarPlay?,
    private val audioManager: AudioManager?,
    private val navigationOutputDevice: AudioOutputDevice?,
    private val audioOutputRoutes: AudioOutputRoutes,
    private val unifiedMediaOutput: Boolean,
    private val onReleased: (AudioRenderer) -> Unit,
) : Closeable {
    private data class AudioPacket(val rtp: ByteArray, val sample: Int)

    private var trackAttributes: AudioAttributes? = null
    private var mappedChannel: AudioChannel? = null
    private val queue = LinkedBlockingQueue<AudioPacket>(MAX_QUEUED_PACKETS)
    @Volatile private var running = true
    @Volatile private var started = false
    private var released = false
    private val resumeRequested = AtomicBoolean(false)
    private val routeRefreshRequested = AtomicBoolean(false)
    private var codec: MediaCodec? = null
    private var softwareOpusDecoder: SoftwareOpusDecoder? = null
    private var track: AudioTrack? = null
    private var pcm = ByteArray(64 * 1024)
    private var playbackStarted = false
    private var prebufferBytes = 0
    private var startThresholdBytes = 0
    private var fadeApplied = false
    private var droppedPacketsLogged = false
    private var firstAacPayloadLogged = false
    private var firstOpusShortPacketLogged = false
    private var firstInputQueuedLogged = false
    private var inputQueued = 0
    private var inputDropped = 0
    private var shortOpusPackets = 0
    private var decoderUnavailablePackets = 0
    private var outputBuffers = 0
    private var firstPcmLogged = false
    private val packetsReceived = AtomicInteger()
    private val packetsDropped = AtomicInteger()
    private val lastArrivalNs = AtomicLong()
    private val maxArrivalGapMs = AtomicLong()
    private val frameBytes = if (format.channels >= 2) 4 else 2
    private var totalWrittenFrames = 0L
    private var writtenFramesThisWindow = 0L
    private var writeErrorsThisWindow = 0
    private var lastWriteErrorCode: Int? = null
    private var zeroWritesThisWindow = 0
    private var partialWritesThisWindow = 0
    private var lastPlaybackHeadFrames: Long? = null
    private var maxWriteMs = 0L
    private var statsWindowStartNs = 0L
    private var statsLastUnderruns = 0
    private var bytesPerSecond = 0
    private val bufferProgress = AudioBufferProgress(if (format.channels >= 2) 4 else 2)
    private var underrunsAtPlaybackStart = 0
    private var lastPcmWriteNs = 0L
    private var rebufferCount = 0
    private var diagnosticStage = "starting"
    private var lastDecoderOutputMetadata: String? = null
    private var decoderOutputReports = 0
    private val thread = Thread(::run, "carplay-audio").apply { isDaemon = true }

    @Synchronized
    fun start() {
        if (started || !running) return
        started = true
        thread.start()
    }

    fun submit(rtp: ByteArray, sample: Int) {
        if (started) {
            packetsReceived.incrementAndGet()
            val now = System.nanoTime()
            val previous = lastArrivalNs.getAndSet(now)
            if (previous != 0L) maxArrivalGapMs.accumulateAndGet((now - previous) / 1_000_000L, ::maxOf)
        }
        if (!started || !queue.offer(AudioPacket(rtp, sample))) {
            if (started) packetsDropped.incrementAndGet()
            if (started && !droppedPacketsLogged) {
                droppedPacketsLogged = true
                Log.w(TAG, "audio queue full; dropping newest packets to bound latency")
                report("Audio: queue full audioType=${format.audioType}")
            }
        }
    }

    @Synchronized
    override fun close() {
        running = false
        thread.interrupt()
        if (!started) release()
        else if (released) runCatching { onReleased(this) }
    }

    fun refreshOutputDevice() { routeRefreshRequested.set(true) }

    fun resumeAfterCommunication() {
        if (running) resumeRequested.set(true)
    }

    private fun run() {
        try {
            runCatching { report("Audio: starting api=${Build.VERSION.SDK_INT} " +
                "audioType=${format.audioType} codec=${format.codec} rate=${format.sampleRate} channels=${format.channels} " +
                "mapping=${if (advancedAudioChannelMapping) "automotive" else "mobile"} " +
                "mediaChannel=$mediaChannel navigationChannel=$navigationChannel focus=$audioFocusEnabled") }
            when (format.codec) {
                AudioCodecKind.AAC_LC -> configureCodec(MediaFormat.MIMETYPE_AUDIO_AAC)
                AudioCodecKind.OPUS -> {
                    diagnosticStage = "decoder-create"
                    softwareOpusDecoder = SoftwareOpusDecoder(format.sampleRate, format.channels)
                    report("Audio: decoder ready audioType=${format.audioType} codec=${format.codec} name=Concentus")
                }
                AudioCodecKind.LPCM -> Unit
            }
            createTrack()
            diagnosticStage = "focus"
            requestAudioFocus()
            while (running) {
                if (routeRefreshRequested.getAndSet(false)) applyPreferredOutput()
                if (resumeRequested.getAndSet(false) && playbackStarted) {
                    track?.let { output ->
                        output.pause()
                        output.play()
                        underrunsAtPlaybackStart = output.underrunCount
                    }
                }
                diagnosticStage = "packet"
                queue.poll(AUDIO_POLL_MILLIS, TimeUnit.MILLISECONDS)?.let(::handle)
                // Output becomes ready asynchronously, including after the last packet of a burst.
                // Waiting for the next UDP packet can strand decoded sound for hundreds of ms.
                diagnosticStage = "decoder-output"
                codec?.let(::drainCodec)
                diagnosticStage = "buffer-maintenance"
                maintainPlaybackBuffer()
                diagnosticStage = "stats"
                logStatsIfDue()
            }
        } catch (_: InterruptedException) {
            // Worker shut down.
        } catch (error: Exception) {
            if (running) {
                Log.e(TAG, "audio renderer worker failed", error)
                reportFailure(error)
            }
        } catch (error: LinkageError) {
            // Record an unsupported platform API without changing the existing crash semantics.
            if (running) reportFailure(error)
            throw error
        } finally {
            runCatching { logStatsIfDue(force = true) }
            release()
        }
    }

    private fun configureCodec(mime: String) {
        diagnosticStage = "decoder-format"
        val mediaFormat = MediaFormat().apply {
            setString(MediaFormat.KEY_MIME, mime)
            setInteger(MediaFormat.KEY_SAMPLE_RATE, format.sampleRate)
            setInteger(MediaFormat.KEY_CHANNEL_COUNT, format.channels)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 64 * 1024)
            if (mime == MediaFormat.MIMETYPE_AUDIO_AAC) {
                setInteger(MediaFormat.KEY_IS_ADTS, 1)
                setByteBuffer("csd-0", ByteBuffer.wrap(aacAudioSpecificConfig()))
            } else {
                setByteBuffer("csd-0", ByteBuffer.wrap(opusHead()))
                setByteBuffer("csd-1", ByteBuffer.wrap(opusCodecDelay()))
                setByteBuffer("csd-2", ByteBuffer.wrap(opusSeekPreRoll()))
            }
        }
        if (mime == MediaFormat.MIMETYPE_AUDIO_AAC) {
            Log.i(
                TAG,
                "audio AAC config rate=${format.sampleRate} channels=${format.channels} " +
                    "csd0=${aacAudioSpecificConfig().toHexString()}",
            )
        }
        codec = try {
            MediaCodecStartup.create(
                create = { diagnosticStage = "decoder-create"; MediaCodec.createDecoderByType(mime) },
                configure = { diagnosticStage = "decoder-configure"; it.configure(mediaFormat, null, null, 0) },
                start = { diagnosticStage = "decoder-start"; it.start() },
                release = { it.release() },
            ).also {
                // A vendor name getter or diagnostic callback must not discard a started codec.
                runCatching {
                    val name = it.name
                    Log.i(TAG, "audio decoder configured mime=$mime name=$name")
                    report("Audio: decoder ready audioType=${format.audioType} codec=${format.codec} name=$name")
                }
            }
        } catch (error: Exception) {
            Log.e(TAG, "audio decoder configuration failed mime=$mime", error)
            reportFailure(error)
            null
        }
    }

    private fun createTrack() {
        diagnosticStage = "track-buffer-size"
        val encoding = AndroidAudioFormat.ENCODING_PCM_16BIT
        val channelMask = if (format.channels >= 2) AndroidAudioFormat.CHANNEL_OUT_STEREO
        else AndroidAudioFormat.CHANNEL_OUT_MONO
        val minBuffer = AudioTrack.getMinBufferSize(format.sampleRate, channelMask, encoding)
        if (minBuffer <= 0) {
            Log.e(TAG, "AudioTrack buffer size unavailable rate=${format.sampleRate} channels=${format.channels}")
            runCatching { report("Audio: track unavailable api=${Build.VERSION.SDK_INT} stage=$diagnosticStage " +
                "audioType=${format.audioType} codec=${format.codec} rate=${format.sampleRate} " +
                "channels=${format.channels} minBufferResult=$minBuffer") }
            return
        }
        val selection = mappedSelection()
        mappedChannel = selection.channel
        val streamOverride = channelOverride(selection.channel)
        var attributes = audioAttributesFor(selection, streamOverride)
        trackAttributes = attributes
        val plan = MediaAudioBuffer.plan(selection.channel == AudioChannel.MEDIA,
            format.sampleRate, format.channels, minBuffer, mediaBufferMillis)
        bytesPerSecond = format.sampleRate * frameBytes
        val built: AudioTrack
        var routeLabel: String
        diagnosticStage = "track-build"
        if (streamOverride == 0) {
            attributes = audioAttributesFor(selection)
            routeLabel = "usage"
            built = AudioTrack.Builder()
                .setAudioAttributes(attributes)
                .setAudioFormat(pcmFormat(encoding, channelMask))
                .setTransferMode(AudioTrack.MODE_STREAM)
                .setBufferSizeInBytes(plan.trackBufferBytes)
                .build()
        } else {
            val streamType = streamOverride
            routeLabel = "streamType=$streamType"
            built = LegacyAudioFallback.build(
                createLegacy = {
                    AudioTrack(streamType, format.sampleRate, channelMask, encoding,
                        plan.trackBufferBytes, AudioTrack.MODE_STREAM)
                },
                isInitialized = { it.state == AudioTrack.STATE_INITIALIZED },
                release = { it.release() },
                createFallback = {
                    diagnosticStage = "track-fallback-build"
                    routeLabel = "streamType=$streamType(fallback=usage)"
                    Log.w(TAG, "streamType=$streamType rejected by this ROM; falling back to usage-based track")
                    attributes = audioAttributesFor(selection)
                    AudioTrack.Builder()
                        .setAudioAttributes(attributes)
                        .setAudioFormat(pcmFormat(encoding, channelMask))
                        .setTransferMode(AudioTrack.MODE_STREAM)
                        .setBufferSizeInBytes(plan.trackBufferBytes)
                        .build()
                },
            )
        }
        track = built
        applyPreferredOutput()
        diagnosticStage = "track-attributes"
        trackAttributes = audioTrackAttributesForFocus(built, attributes)
        diagnosticStage = "track-capacity"
        val capacityBytes = built.bufferSizeInFrames * frameBytes
        startThresholdBytes = MediaAudioBuffer.startBytesFor(plan.startBytes, capacityBytes, PREBUFFER_WRITE_CHUNK_BYTES)
        val trackMetadata = runCatching {
            "api=${Build.VERSION.SDK_INT} trackState=${built.state} trackRate=${built.sampleRate} " +
                "usage=${trackAttributes?.usage} contentType=${trackAttributes?.contentType} " +
                "attributesSource=${if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) "track" else "configured"}"
        }.getOrDefault("api=${Build.VERSION.SDK_INT} trackMetadata=unavailable")
        report("Audio: ready audioType=${format.audioType} codec=${format.codec} " +
            "rate=${format.sampleRate} channels=${format.channels} " +
            "route=$routeLabel " +
            "bufferMs=${capacityBytes * 1000L / bytesPerSecond} startMs=${startThresholdBytes * 1000L / bytesPerSecond} " +
            trackMetadata)
        Log.i(
            TAG,
            "audio track prepared type=${format.payloadType} audioType=${format.audioType} " +
                "codec=${format.codec} " +
                "rate=${format.sampleRate} channels=${format.channels} " +
                "route=$routeLabel " +
                "buffer=${capacityBytes * 1000L / bytesPerSecond}ms start=${startThresholdBytes * 1000L / bytesPerSecond}",
        )
        Log.i(
            TAG,
            "audio route type=${format.payloadType} audioType=${format.audioType} " +
                "mode=${if (advancedAudioChannelMapping) AudioChannelMappingMode.AUTOMOTIVE_BUS else AudioChannelMappingMode.MOBILE_COMPATIBLE} " +
                "channel=${selection.channel} usage=${usageFor(selection.channel)} " +
                "contentType=${contentTypeFor(selection.contentType)} " +
                "streamOverride=$streamOverride " +
                "focus=${if (audioFocusEnabled) "on" else "off"}",
        )
    }

    // carlito | Reapply preferences when USB/Bluetooth devices change; a missing device uses system routing.
    private fun applyPreferredOutput() {
        val output = track ?: return
        val channel = mappedChannel ?: return
        val preferred = preferredOutput(channel) ?: return
        val device = preferred.resolve(audioManager)
        val accepted = device != null && runCatching { output.setPreferredDevice(device) }.getOrDefault(false)
        if (!accepted) runCatching { output.setPreferredDevice(null) }
        runCatching { report("Audio: role=$channel preferredDevice=${device?.id ?: -1} accepted=$accepted fallback=${!accepted}") }
    }

    /** 0 uses usage-based routing; 1–20 attempt legacy stream types supported by the head unit. */
    // carlito | Physical output is shared; logical PHONE/ASSISTANT roles retain transient focus priority.
    private fun outputChannel(channel: AudioChannel): AudioChannel =
        if (unifiedMediaOutput && channel != AudioChannel.NAVIGATION) AudioChannel.MEDIA else channel

    private fun preferredOutput(channel: AudioChannel): AudioOutputDevice? = audioOutputRoutes.device(outputChannel(channel))
        ?: navigationOutputDevice.takeIf { channel == AudioChannel.NAVIGATION }

    private fun channelOverride(channel: AudioChannel): Int = if (preferredOutput(channel) != null) 0 else when (outputChannel(channel)) {
        AudioChannel.MEDIA -> mediaChannel
        AudioChannel.NAVIGATION -> navigationChannel
        else -> 0
    }

    private fun audioAttributesFor(
        selection: AudioChannelSelection,
        streamOverride: Int,
    ): AudioAttributes {
        if (streamOverride in AudioManager.STREAM_SYSTEM..AudioManager.STREAM_ACCESSIBILITY) {
            // Android accepts only its defined legacy stream IDs here. BYD audio policy can
            // map these standard streams to vehicle outputs; arbitrary channel numbers are
            // not valid AudioAttributes legacy stream types.
            try {
                return AudioAttributes.Builder().setLegacyStreamType(streamOverride).build()
            } catch (error: Exception) {
                Log.w(TAG, "legacy audio stream $streamOverride rejected; keeping usage routing", error)
            }
        }
        return audioAttributesFor(selection)
    }

    private fun mappedSelection(): AudioChannelSelection {
        if (unifiedMediaOutput && isVehicleNavigationAudio(format.audioType)) {
            return AudioChannelSelection(AudioChannel.NAVIGATION, AudioContentType.SPEECH)
        }
        if (unifiedMediaOutput) return when (format.audioType.lowercase()) {
            "telephony", "facetime" -> AudioChannelSelection(AudioChannel.PHONE, AudioContentType.SPEECH)
            "speechrecognition" -> AudioChannelSelection(AudioChannel.ASSISTANT, AudioContentType.SPEECH)
            "ringtone", "ring", "alert" -> AudioChannelSelection(AudioChannel.RINGTONE, AudioContentType.SPEECH)
            else -> AudioChannelSelection(AudioChannel.MEDIA, AudioContentType.MUSIC)
        }
        if (factoryAudio != null && format.audioType.equals("alert", true)) {
            return AudioChannelSelection(AudioChannel.RINGTONE, AudioContentType.SPEECH)
        }
        val mode = if (advancedAudioChannelMapping) {
            AudioChannelMappingMode.AUTOMOTIVE_BUS
        } else {
            AudioChannelMappingMode.MOBILE_COMPATIBLE
        }
        return AudioChannelMapper.map(
            audioType = format.audioType,
            payloadType = format.payloadType,
            mode = mode,
            navigationStreamType = navigationStreamType,
        )
    }

    private fun audioAttributesFor(selection: AudioChannelSelection): AudioAttributes {
        val routedChannel = outputChannel(selection.channel)
        val usage = usageFor(routedChannel)
        val attributes = AudioAttributes.Builder().setUsage(usage)
            .setContentType(contentTypeFor(selection.contentType)).build()
        if (attributes.usage == usage) return attributes
        Log.w(TAG, "Factory audio usage $usage rejected; using Android usage")
        return AudioAttributes.Builder().setUsage(standardUsageFor(routedChannel))
            .setContentType(contentTypeFor(selection.contentType)).build()
    }

    /**
     * Shares the sink's focus owner. The factory profile also requests transient navigation focus.
     */
    private fun requestAudioFocus() {
        val channel = mappedChannel ?: return
        val attributes = trackAttributes ?: return
        track?.let { audioFocusCoordinator.acquire(it, channel, attributes) }
    }

    private fun pcmFormat(encoding: Int, channelMask: Int) = AndroidAudioFormat.Builder()
        .setSampleRate(format.sampleRate)
        .setChannelMask(channelMask)
        .setEncoding(encoding)
        .build()

    private fun streamType(): Int {
        val mode = if (advancedAudioChannelMapping) {
            AudioChannelMappingMode.AUTOMOTIVE_BUS
        } else {
            AudioChannelMappingMode.MOBILE_COMPATIBLE
        }
        return AudioChannelMapper.map(
            audioType = format.audioType,
            payloadType = format.payloadType,
            mode = mode,
            navigationStreamType = navigationStreamType,
        ).streamType
    }

    private fun aacAudioSpecificConfig(): ByteArray {
        val frequencyIndex = MediaCodecSupport.aacFrequencyIndex(format.sampleRate)
        val value = (AAC_OBJECT_TYPE_LC shl 11) or
            (frequencyIndex shl 7) or
            (format.channels.coerceIn(1, 7) shl 3)
        return byteArrayOf((value ushr 8).toByte(), value.toByte())
    }

    private fun usageFor(channel: AudioChannel): Int {
        val kind = when (channel) {
            AudioChannel.MEDIA -> "MEDIA"
            AudioChannel.PHONE -> "PHONE"
            AudioChannel.ASSISTANT -> "SIRI"
            AudioChannel.NAVIGATION -> "GUIDANCE"
            AudioChannel.RINGTONE -> "RING"
        }
        return factoryAudio?.audioUsage(kind, standardUsageFor(channel)) ?: standardUsageFor(channel)
    }

    private fun standardUsageFor(channel: AudioChannel): Int = when (channel) {
        AudioChannel.MEDIA -> AudioAttributes.USAGE_MEDIA
        AudioChannel.PHONE -> AudioAttributes.USAGE_VOICE_COMMUNICATION
        AudioChannel.ASSISTANT -> AudioAttributes.USAGE_ASSISTANT
        AudioChannel.NAVIGATION -> AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE
        AudioChannel.RINGTONE -> AudioAttributes.USAGE_NOTIFICATION_RINGTONE
    }

    private fun contentTypeFor(contentType: AudioContentType): Int = when (contentType) {
        AudioContentType.MUSIC -> AudioAttributes.CONTENT_TYPE_MUSIC
        AudioContentType.SPEECH -> AudioAttributes.CONTENT_TYPE_SPEECH
    }

    /** Minimal OpusHead CSD for the mono 48 kHz stream CarPlay negotiates. */
    private fun opusHead(): ByteArray {
        val head = ByteArray(19)
        "OpusHead".toByteArray(Charsets.US_ASCII).copyInto(head, 0)
        head[8] = 1
        head[9] = format.channels.toByte()
        head[10] = 0x38
        head[11] = 0x01
        head[12] = format.sampleRate.toByte()
        head[13] = (format.sampleRate ushr 8).toByte()
        head[14] = (format.sampleRate ushr 16).toByte()
        head[15] = (format.sampleRate ushr 24).toByte()
        return head
    }

    private fun opusCodecDelay(): ByteArray =
        java.nio.ByteBuffer.allocate(8)
            .order(java.nio.ByteOrder.LITTLE_ENDIAN)
            .putLong(OPUS_CODEC_DELAY_NANOS)
            .array()

    private fun opusSeekPreRoll(): ByteArray =
        java.nio.ByteBuffer.allocate(8)
            .order(java.nio.ByteOrder.LITTLE_ENDIAN)
            .putLong(OPUS_SEEK_PRE_ROLL_NANOS)
            .array()

    private fun handle(packet: AudioPacket) {
        val rtp = packet.rtp
        when (format.codec) {
            AudioCodecKind.LPCM -> writePcm(byteSwapS16(rtp.copyOfRange(12, rtp.size)))
            AudioCodecKind.AAC_LC -> {
                val accessUnit = rtp.copyOfRange(12, rtp.size)
                if (accessUnit.isNotEmpty()) {
                    if (!firstAacPayloadLogged) {
                        firstAacPayloadLogged = true
                        Log.i(
                            TAG,
                            "audio AAC access unit bytes=${accessUnit.size}",
                        )
                    }
                    feedCodec(
                        MediaCodecSupport.adtsFrame(accessUnit, format.sampleRate, format.channels),
                        sampleTimestampUs(packet.sample),
                    )
                }
            }
            AudioCodecKind.OPUS -> {
                val accessUnit = rtp.copyOfRange(12, rtp.size)
                if (accessUnit.size < MIN_OPUS_PACKET_BYTES) {
                    shortOpusPackets++
                    if (!firstOpusShortPacketLogged) {
                        firstOpusShortPacketLogged = true
                        Log.i(
                            TAG,
                            "audio Opus skipping short packet bytes=${accessUnit.size}",
                        )
                    }
                    return
                }
                val decoder = softwareOpusDecoder ?: run {
                    decoderUnavailablePackets++
                    return
                }
                try {
                    val decodedBytes = decoder.decode(accessUnit)
                    inputQueued++
                    outputBuffers++
                    if (!firstInputQueuedLogged) {
                        firstInputQueuedLogged = true
                        Log.i(TAG, "audio software Opus first input bytes=${accessUnit.size} decodedBytes=$decodedBytes")
                    }
                    if (decodedBytes > 0) writePcm(decoder.pcm, 0, decodedBytes)
                } catch (error: Exception) {
                    inputDropped++
                    if (inputDropped <= 3) {
                        Log.w(TAG, "audio software Opus decode failed bytes=${accessUnit.size}", error)
                        report("Audio: software Opus decode failed audioType=${format.audioType} " +
                            "error=${error.javaClass.simpleName}")
                    }
                }
            }
        }
    }

    private fun sampleTimestampUs(sample: Int): Long =
        (sample.toLong() and 0xffff_ffffL) * 1_000_000L / format.sampleRate

    private fun feedCodec(payload: ByteArray, presentationTimeUs: Long) {
        val codec = codec ?: run { decoderUnavailablePackets++; return }
        diagnosticStage = "decoder-input"
        val index = codec.dequeueInputBuffer(INPUT_TIMEOUT_US)
        if (index < 0) {
            inputDropped++
            if (inputDropped == 1) {
                Log.w(
                    TAG,
                    "audio decoder input unavailable codec=${format.codec} " +
                        "queued=$inputQueued dropped=$inputDropped",
                )
            }
            return
        }
        val input = codec.getInputBuffer(index) ?: return
        input.clear()
        if (payload.size <= input.remaining()) {
            input.put(payload)
            codec.queueInputBuffer(index, 0, payload.size, presentationTimeUs, 0)
            inputQueued++
            if (!firstInputQueuedLogged) {
                firstInputQueuedLogged = true
                Log.i(
                    TAG,
                    "audio decoder first input codec=${format.codec} bytes=${payload.size}",
                )
            }
        } else {
            codec.queueInputBuffer(index, 0, 0, 0, 0)
            inputDropped++
        }
        drainCodec(codec)
    }

    private fun drainCodec(codec: MediaCodec) {
        diagnosticStage = "decoder-output"
        val info = MediaCodec.BufferInfo()
        while (running) {
            diagnosticStage = "decoder-output"
            val index = codec.dequeueOutputBuffer(info, 0)
            when {
                index == MediaCodec.INFO_TRY_AGAIN_LATER -> return
                index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    // Vendor metadata inspection must not interrupt otherwise working playback.
                    if (decoderOutputReports < 4) runCatching {
                        val outputFormat = codec.outputFormat
                        val metadata = "rate=${outputFormat.intOrNull(MediaFormat.KEY_SAMPLE_RATE)} " +
                            "channels=${outputFormat.intOrNull(MediaFormat.KEY_CHANNEL_COUNT)} " +
                            "pcmEncoding=${outputFormat.intOrNull(MediaFormat.KEY_PCM_ENCODING)}"
                        if (metadata != lastDecoderOutputMetadata) {
                            lastDecoderOutputMetadata = metadata
                            decoderOutputReports++
                            report("Audio: decoded format audioType=${format.audioType} codec=${format.codec} $metadata")
                        }
                    }
                }
                index >= 0 -> {
                    val size = info.size
                    if (size > 0) {
                        outputBuffers++
                        if (outputBuffers == 1 || outputBuffers % DECODED_BUFFER_LOG_INTERVAL == 0) {
                            Log.i(
                                TAG,
                                "audio decoder output codec=${format.codec} " +
                                    "buffers=$outputBuffers bytes=$size " +
                                    "queued=$inputQueued dropped=$inputDropped",
                            )
                        }
                    }
                    if (size > 0) {
                        val output = codec.getOutputBuffer(index)
                        if (output != null) {
                            if (size > pcm.size) pcm = ByteArray(size)
                            output.position(info.offset)
                            output.limit(info.offset + size)
                            output.get(pcm, 0, size)
                            writePcm(pcm, 0, size)
                        }
                    }
                    codec.releaseOutputBuffer(index, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
                }
                else -> return
            }
        }
    }

    private fun writePcm(data: ByteArray, offset: Int = 0, length: Int = data.size) {
        val track = track ?: return
        diagnosticStage = "track-write"
        if (!firstPcmLogged && length > 0) {
            firstPcmLogged = true
            Log.i(
                TAG,
                "audio first PCM type=${format.payloadType} bytes=$length",
            )
        }
        if (!fadeApplied) {
            applyFadeIn(data, offset, length)
            fadeApplied = true
        }
        var written = 0
        while (written < length && running) {
            val writeLength = if (playbackStarted) {
                length - written
            } else {
                minOf(length - written, PREBUFFER_WRITE_CHUNK_BYTES)
            }
            val writeStarted = System.nanoTime()
            diagnosticStage = "track-write"
            val count = track.write(data, offset + written, writeLength, AudioTrack.WRITE_BLOCKING)
            maxWriteMs = maxOf(maxWriteMs, (System.nanoTime() - writeStarted) / 1_000_000L)
            if (count < 0) {
                writeErrorsThisWindow++
                lastWriteErrorCode = count
                break
            }
            if (count == 0) {
                zeroWritesThisWindow++
                break
            }
            if (count < writeLength) partialWritesThisWindow++
            written += count
            val framesWritten = count / frameBytes
            totalWrittenFrames += framesWritten
            writtenFramesThisWindow += framesWritten
            bufferProgress.written(count)
            lastPcmWriteNs = System.nanoTime()
            if (!playbackStarted) {
                prebufferBytes += count
                if (prebufferBytes >= startThresholdBytes) {
                    startPlayback(track)
                    Log.i(TAG, "audio playback started type=${format.payloadType}")
                }
            }
        }
    }

    private fun startPlayback(track: AudioTrack) {
        diagnosticStage = "track-play"
        underrunsAtPlaybackStart = track.underrunCount
        track.play()
        playbackStarted = true
    }

    private fun maintainPlaybackBuffer() {
        val track = track ?: return
        if (bufferProgress.shouldRebuffer(mappedChannel == AudioChannel.MEDIA, playbackStarted,
                track.underrunCount > underrunsAtPlaybackStart, queue.isEmpty(), track.playbackHeadPosition)) {
            // The hardware buffer has actually drained. Pause without flushing or discarding PCM,
            // then use the configured start threshold again when music resumes.
            track.pause()
            playbackStarted = false
            prebufferBytes = 0
            rebufferCount++
        }
        // A short final burst may never reach the start threshold. Play it after a bounded wait.
        if (!playbackStarted && prebufferBytes > 0 && queue.isEmpty() &&
            System.nanoTime() - lastPcmWriteNs >= BUFFER_TAIL_WAIT_NS) {
            startPlayback(track)
        }
    }

    // Persist counters even during packet starvation, and flush before disconnect releases the track.
    private fun logStatsIfDue(force: Boolean = false) {
        val now = System.nanoTime()
        if (statsWindowStartNs == 0L) statsWindowStartNs = now
        if (!force && now - statsWindowStartNs < STATS_WINDOW_NS) return
        val underruns = track?.underrunCount ?: 0
        val lastRx = lastArrivalNs.get()
        val currentTrack = track
        val playbackHeadFrames = currentTrack?.playbackHeadPosition
            ?.toLong()?.and(0xffff_ffffL)
        val playbackAdvanceFrames = playbackHeadFrames?.let { current ->
            val previous = lastPlaybackHeadFrames
            lastPlaybackHeadFrames = current
            previous?.let { (current - it) and 0xffff_ffffL }
        }
        val queuedFrames = playbackHeadFrames?.let { (totalWrittenFrames - it).coerceAtLeast(0L) }
        val line = "audio stats audioType=${format.audioType} channel=$mappedChannel " +
            "routeType=${currentTrack?.routedDevice?.type ?: -1} routeDevice=${currentTrack?.routedDevice?.id ?: -1} codec=${format.codec} " +
            "trackState=${currentTrack?.state ?: -1} playState=${currentTrack?.playState ?: -1} " +
            "sampleRate=${currentTrack?.sampleRate ?: format.sampleRate} " +
            "trackBufferFrames=${currentTrack?.bufferSizeInFrames ?: -1} " +
            "rx=${packetsReceived.getAndSet(0)} " +
            "dropped=${packetsDropped.getAndSet(0)} underruns=+${underruns - statsLastUnderruns} queue=${queue.size} " +
            "playing=$playbackStarted maxGapMs=${maxArrivalGapMs.getAndSet(0)} " +
            "sinceRxMs=${if (lastRx == 0L) -1 else (now - lastRx) / 1_000_000L} maxWriteMs=$maxWriteMs " +
            "writtenFrames=$writtenFramesThisWindow totalWrittenFrames=$totalWrittenFrames " +
            "playbackHeadFrames=${playbackHeadFrames ?: -1} playbackAdvanceFrames=${playbackAdvanceFrames ?: -1} " +
            "estimatedQueuedFrames=${queuedFrames ?: -1} writeErrors=$writeErrorsThisWindow " +
            "lastWriteError=${lastWriteErrorCode ?: "none"} zeroWrites=$zeroWritesThisWindow " +
            "partialWrites=$partialWritesThisWindow " +
            "decoderDroppedTotal=$inputDropped outputBuffersTotal=$outputBuffers rebuffers=$rebufferCount ended=$force"
        Log.i(STATS_TAG, line)
        report(line)
        if (format.codec != AudioCodecKind.LPCM) {
            // Keep this separate: the exported diagnostic recorder caps each line at 700 characters.
            val decoderLine = "Audio: decoder stats audioType=${format.audioType} codec=${format.codec} " +
                "inputQueuedTotal=$inputQueued inputDroppedTotal=$inputDropped " +
                "shortOpusPacketsTotal=$shortOpusPackets decoderUnavailablePacketsTotal=$decoderUnavailablePackets " +
                "outputBuffersTotal=$outputBuffers ended=$force"
            Log.i(STATS_TAG, decoderLine)
            runCatching { report(decoderLine) }
        }
        statsLastUnderruns = underruns
        maxWriteMs = 0L
        writtenFramesThisWindow = 0L
        writeErrorsThisWindow = 0
        lastWriteErrorCode = null
        zeroWritesThisWindow = 0
        partialWritesThisWindow = 0
        statsWindowStartNs = now
    }

    private fun reportFailure(error: Throwable) {
        runCatching { report("Audio: renderer failed api=${Build.VERSION.SDK_INT} " +
            "audioType=${format.audioType} codec=${format.codec} stage=$diagnosticStage " +
            MediaFailureSummary.describe(error)) }
    }

    private fun applyFadeIn(data: ByteArray, offset: Int, length: Int) {
        val samples = (length - length % 2) / 2
        val fadeSamples = minOf(samples, maxOf(1, format.sampleRate / 100))
        for (index in 0 until fadeSamples) {
            val position = offset + index * 2
            val sample = (data[position].toInt() and 0xff) or (data[position + 1].toInt() shl 8)
            val scaled = (sample.toLong() * (index + 1) / fadeSamples).toInt()
            data[position] = scaled.toByte()
            data[position + 1] = (scaled shr 8).toByte()
        }
    }

    private fun byteSwapS16(source: ByteArray): ByteArray {
        for (index in 0 until source.size - 1 step 2) {
            val tmp = source[index]
            source[index] = source[index + 1]
            source[index + 1] = tmp
        }
        return source
    }

    @Synchronized
    private fun release() {
        if (released) return
        val releasedTrack = track
        val codec = codec
        this.codec = null
        softwareOpusDecoder = null
        if (codec != null) {
            try {
                codec.stop()
            } catch (_: Exception) {
                // Best effort.
            }
            try {
                codec.release()
            } catch (_: Exception) {
                // Best effort.
            }
        }
        val track = track
        this.track = null
        if (track != null) {
            try {
                track.pause()
            } catch (_: Exception) {
                // Best effort.
            }
            try {
                track.flush()
            } catch (_: Exception) {
                // Best effort.
            }
            try {
                track.release()
            } catch (_: Exception) {
                // Best effort.
            }
        }
        runCatching { releasedTrack?.let(audioFocusCoordinator::release) }
            .onFailure { error -> runCatching { report("Audio: focus release failed: ${error.message}") } }
        released = true
        runCatching { onReleased(this) }
            .onFailure { error -> runCatching { report("Audio: communication recovery failed: ${error.message}") } }
    }

    private companion object {
        const val TAG = "xcertplay-usb"
        const val AAC_OBJECT_TYPE_LC = 2
        const val MIN_OPUS_PACKET_BYTES = 4
        const val OPUS_CODEC_DELAY_NANOS = 6_500_000L
        const val OPUS_SEEK_PRE_ROLL_NANOS = 80_000_000L
        const val INPUT_TIMEOUT_US = 10_000L
        const val AUDIO_POLL_MILLIS = 10L
        const val BUFFER_TAIL_WAIT_NS = 500_000_000L
        // Holds a burst after a Wi-Fi gap (~4 s of AAC) instead of dropping it.
        const val MAX_QUEUED_PACKETS = 192
        const val PREBUFFER_WRITE_CHUNK_BYTES = 2 * 1024
        const val STATS_TAG = "DiPlay-AudioStats"
        const val STATS_WINDOW_NS = 5_000_000_000L
        const val DECODED_BUFFER_LOG_INTERVAL = 50
    }
}
