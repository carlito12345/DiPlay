package com.shilapi.xcertplay

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.KeyEvent
import androidx.core.graphics.drawable.toBitmap
import com.shilapi.xcertplay.airplay.CarPlayMediaButton
import com.shilapi.xcertplay.compat.AudioFocusRequestCompat
import com.shilapi.xcertplay.host.R
import com.shilapi.xcertplay.hud.BydOutputSettings
import com.shilapi.xcertplay.media.CarPlayNowPlaying
import com.shilapi.xcertplay.orchestration.CarPlayController
import com.shilapi.xcertplay.vehicle.GeelyFactoryCarPlay
import com.shilapi.xcertplay.vehicleprobe.VehicleSteeringClient
import com.shilapi.xcertplay.vehicleprobe.VehicleProjectionClient
import java.util.concurrent.Executors
import java.util.concurrent.Executor
import kotlin.math.roundToInt

/**
 * Steering-wheel and other hardware media buttons for CarPlay.
 *
 * Android delivers media keys to a media session; BYD picks the session of the audio-focus
 * owner. Once CarPlay plays music, DiPlay holds audio focus and an active session until the
 * CarPlay session ends, so play also works after a pause. Keys go to the iPhone as CarPlay media
 * HID presses ([CarPlayMediaButton]).
 */
internal object CarPlayMediaKeys {
    private const val TAG = "DiPlay-MediaKeys"
    private const val ACTIONS = PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or
        PlaybackState.ACTION_PLAY_PAUSE or PlaybackState.ACTION_SKIP_TO_NEXT or PlaybackState.ACTION_SKIP_TO_PREVIOUS

    private val mainHandler = Handler(Looper.getMainLooper())
    private val artworkQueue = NowPlayingArtworkQueue(
        worker = Executors.newSingleThreadExecutor { task ->
            Thread(task, "diplay-now-playing-artwork").apply { isDaemon = true }
        },
        main = Executor { mainHandler.post(it) },
        decode = ::decodeArtwork,
        publish = ::onArtworkDecoded,
        discard = Bitmap::recycle,
    )
    private var artworkOwner: Any? = null
    @Volatile private var controller: CarPlayController? = null
    private var session: MediaSession? = null
    private var focusRequest: AudioFocusRequestCompat? = null
    private var focusOwner: Any? = null
    private var focusEventRevision = 0L
    private var focusHeld = false
    private var manageAudioFocus = true
    private var appContext: Context? = null
    // carlito | The independent bridge owns all OEM input implementation and interception.
    @Volatile private var bridgeInput: VehicleSteeringClient? = null
    // carlito | One shared steering lease; volume keys are added only while map zoom is active.
    private var bridgeBaseKeys = intArrayOf()
    private val bridgeZoom = WheelZoomKeys()
    private var bridgeZoomKeys = emptyMap<WheelZoomSettings.Role, Int>()
    @Volatile private var instrumentStatus: VehicleProjectionClient? = null
    @Volatile private var zoomEpoch = 0L
    @Volatile private var zoomActive = false
    @Volatile private var zoomDeadline = 0L
    private val zoomPoll = object : Runnable {
        override fun run() = synchronized(this@CarPlayMediaKeys) {
            refreshZoomEligibility()
            if (controller != null && bridgeZoomKeys.isNotEmpty()) mainHandler.postDelayed(this, 100L)
        }
    }
    @Volatile private var steeringGeneration = 0
    private var keyLogMonitor: SteeringKeyLogMonitor? = null
    private var lastGeelyInputDiagnostics = "vehicleBridge INACTIVE"
    private var lastKeyLogDiagnostics = "logMonitor INACTIVE"
    @Volatile private var monitorGeneration = 0
    private var steeringProfile: SteeringProfile? = null
    private data class Learning(val owner: Any, val onKey: (SteeringObservedKey?) -> Unit)
    @Volatile private var learning: Learning? = null
    private var learningTimeout: Runnable? = null
    private var suppressedUntil = 0L
    private var lastSentButton = -1
    private var lastSentSource = ""
    private var lastSentAt = 0L
    private val observedKeys = ArrayDeque<String>()
    private val lastSystemTrigger = mutableMapOf<String, Long>()
    private var mediaAudioActive = false
    // carlito | Resume only the music paused by this speech burst in the same controller session.
    private var speechPlaying = false
    private var resumeAfterSpeech = false
    private var speechMedia: CarPlayNowPlaying? = null
    private var nowPlaying = CarPlayNowPlaying()
    private var elapsedUpdatedAt = 0L
    private var artwork: Bitmap? = null
    private val artworkCache = LinkedHashMap<Int, Bitmap?>()
    private var placeholder: Bitmap? = null

    @Synchronized
    fun attach(context: Context, next: CarPlayController, manageAudioFocus: Boolean = true,
        onMediaPlaying: (Boolean) -> Unit = {}) {
        if (controller !== next) {
            releaseLocked()
            artworkOwner = artworkQueue.newSession()
        }
        appContext = context.applicationContext
        controller = next
        this.manageAudioFocus = manageAudioFocus
        steeringProfile = SteeringProfiles.loadEnabled(context)
        com.shilapi.xcertplay.media.AmbientMusicController.claimPlaybackOwner(next)
        next.playbackListener = { playing ->
            com.shilapi.xcertplay.media.AmbientMusicController.phonePlaybackChanged(next, playing)
            onMediaPlaying(playing)
            onIphonePlaying(next, playing)
        }
        next.nowPlayingListener = { update -> onNowPlayingChanged(next, update) }
        next.artworkListener = { id, bytes -> onArtworkChanged(next, id, bytes) }
        syncGeelyInputLocked()
    }

    /** Ends key handling for [expected]; a newer controller's state is left alone. */
    @Synchronized
    fun detach(expected: CarPlayController?) {
        if (expected == null || controller !== expected) return
        com.shilapi.xcertplay.media.AmbientMusicController.releasePlaybackOwner(expected)
        expected.playbackListener = null
        expected.nowPlayingListener = null
        expected.artworkListener = null
        controller = null
        releaseLocked()
    }

    /** Called when CarPlay music starts or stops; may run on any thread. */
    fun onMediaAudioChanged(active: Boolean) {
        mainHandler.post { synchronized(this) { updateLocked(active) } }
    }

    fun onSpeechPlayback(expected: CarPlayController, active: Boolean) {
        mainHandler.post { synchronized(this) {
            if (controller !== expected || speechPlaying == active) return@synchronized
            speechPlaying = active
            if (active) {
                val playing = if (nowPlaying.title != null || nowPlaying.elapsedMillis != null) nowPlaying.playing else mediaAudioActive
                speechMedia = nowPlaying
                resumeAfterSpeech = playing && expected.sendMediaButton(CarPlayMediaButton.PAUSE)
            } else {
                val resume = resumeAfterSpeech
                resumeAfterSpeech = false; speechMedia = null
                if (resume) expected.sendMediaButton(CarPlayMediaButton.PLAY)
            }
        } }
    }

    /** The iPhone started or stopped playing; may run on any thread. */
    private fun onIphonePlaying(expected: CarPlayController, playing: Boolean) {
        if (playing) mainHandler.post {
            synchronized(this) {
                if (controller === expected) regainFocusLocked()
            }
        }
    }

    /** Publishes the iPhone's retained metadata through Android's system media session. */
    private fun onNowPlayingChanged(expected: CarPlayController, update: CarPlayNowPlaying) {
        mainHandler.post {
            synchronized(this) {
                if (controller !== expected) return@synchronized
                val previousArtwork = artwork
                speechMedia?.let { before ->
                    if (before.title != update.title || before.artist != update.artist || before.sourceApp != update.sourceApp)
                        resumeAfterSpeech = false
                }
                if (nowPlaying.artworkTransferId != update.artworkTransferId) {
                    artwork = nextArtwork(update.artworkTransferId, artworkCache, artwork)
                }
                if (nowPlaying.elapsedMillis != update.elapsedMillis) elapsedUpdatedAt = SystemClock.elapsedRealtime()
                val metadataChanged = metadataChanged(nowPlaying, update) || artwork !== previousArtwork
                nowPlaying = update
                // The iPhone repeats NowPlayingUpdate about twice a second for the position alone.
                // Republishing the metadata each time sent a copy of the artwork through system_server
                // to every media listener, and on a DiLink 5.0 Tang that exhausted memory within
                // minutes. The position goes in the playback state.
                if (metadataChanged) session?.setMetadata(androidMetadata(update, shownArtworkLocked()))
                publishPlaybackStateLocked()
            }
        }
    }

    @Synchronized
    private fun onArtworkChanged(expected: CarPlayController, id: Int, bytes: ByteArray) {
        if (controller !== expected) return
        artworkOwner?.let { artworkQueue.submit(it, id, bytes) }
    }

    @Synchronized
    private fun onArtworkDecoded(expected: Any, id: Int, decoded: Bitmap?) {
        if (artworkOwner !== expected) {
            decoded?.recycle()
            return
        }
        artworkCache.remove(id)
        artworkCache[id] = decoded
        while (artworkCache.size > MAX_CACHED_ARTWORK) artworkCache.remove(artworkCache.keys.first())
        if (nowPlaying.artworkTransferId == id) {
            artwork = decoded
            session?.setMetadata(androidMetadata(nowPlaying, shownArtworkLocked()))
        }
    }

    /** Changes the Geely steering listener immediately if a CarPlay session is already active. */
    fun setGeelySteeringEnabled(context: Context, enabled: Boolean) {
        AirPlayPersistence.saveGeelySteeringEnabled(context, enabled)
        synchronized(this) {
            appContext = context.applicationContext
            syncGeelyInputLocked()
        }
    }

    private fun syncGeelyInputLocked() {
        bridgeZoomKeys = appContext?.takeIf { WheelZoomSettings.enabled(it) }?.let(WheelZoomSettings::bridgeKeys).orEmpty()
        if (controller != null && bridgeZoomKeys.isNotEmpty() && instrumentStatus == null)
            instrumentStatus = VehicleProjectionClient(appContext!!)
        if (controller == null || bridgeZoomKeys.isEmpty()) { instrumentStatus?.close(); instrumentStatus = null; endBridgeZoom() }
        mainHandler.removeCallbacks(zoomPoll)
        if (controller != null && bridgeZoomKeys.isNotEmpty()) mainHandler.post(zoomPoll)
        val profileUsesVehicleBridge = steeringProfile?.bindings?.any { it.isVendorInput } == true
        val useGeelyInput = learning != null || controller != null &&
            (bridgeZoomKeys.isNotEmpty() || profileUsesVehicleBridge || steeringProfile == null &&
                appContext?.let(AirPlayPersistence::loadGeelySteeringEnabled) == true)
        if (!useGeelyInput) {
            steeringGeneration++
            bridgeInput?.let { lastGeelyInputDiagnostics = it.diagnostics(); it.close() }
            bridgeInput = null
        } else {
            if (bridgeInput == null) {
                val generation = ++steeringGeneration
                bridgeInput = VehicleSteeringClient(appContext!!) { event ->
                    onGeelySteeringKey(GeelySteeringKeyEvent(event.getInt("keyCode"), event.getInt("rawKeyCode"),
                        event.getInt("action"), event.getLong("eventTimeMs")), generation, source = "vehicle_bridge")
                }
            }
            bridgeBaseKeys = steeringProfile?.bindings?.filter { it.isVendorInput }
                ?.map { GeelySteeringKeyCodes.canonicalize(it.keyCode) ?: it.keyCode }?.toIntArray()
                ?: intArrayOf(200085, 200087, 200088, 200231, 210005, 210006)
            // Learning is a passive observation; playback only uses acknowledged interception.
            updateBridgeKeys()
        }
        keyLogMonitor?.let { lastKeyLogDiagnostics = it.diagnostics(); it.close() }; keyLogMonitor = null
        val generation = ++monitorGeneration
        val inputBindings = steeringProfile?.bindings?.filterNot { it.isVendorInput } ?: if (!useGeelyInput && appContext?.let(GeelyFactoryCarPlay::load) != null) {
            // HardKeyModel in the factory APK logs this press even without a connected iPhone.
            listOf(SteeringBinding("siri", 200231, 0, "logcat", "HardKeyModel"))
        } else emptyList()
        val needsSystemInput = learning != null || (controller != null && inputBindings.isNotEmpty())
        if (needsSystemInput && appContext != null &&
            (learning == null || !VehicleSteeringClient.installed(appContext!!))) {
            keyLogMonitor = SteeringKeyLogMonitor(appContext!!, inputBindings, learning != null) { key ->
                mainHandler.post { if (generation == monitorGeneration) onObservedKey(key) }
            }.also { it.start() }
        }
    }

    private fun onGeelySteeringKey(event: GeelySteeringKeyEvent, generation: Int = steeringGeneration,
        source: String = "oneos") {
        mainHandler.post {
            if (generation != steeringGeneration) return@post
            if (learning == null && onBridgeZoomKey(event)) return@post
            if (learning != null || steeringProfile?.bindings?.any { it.isVendorInput } == true) {
                onObservedKey(SteeringObservedKey(event.keyCode, event.action, source))
                return@post
            }
            if (steeringProfile != null) return@post
            val operation = when (event.keyCode) {
                GeelySteeringKeyCodes.MEDIA_PLAY_PAUSE -> "play_pause"
                GeelySteeringKeyCodes.MEDIA_NEXT, GeelySteeringKeyCodes.SEEK_NEXT -> "next"
                GeelySteeringKeyCodes.MEDIA_PREVIOUS, GeelySteeringKeyCodes.SEEK_PREVIOUS -> "previous"
                GeelySteeringKeyCodes.VOICE_ASSIST -> "siri"
                else -> return@post
            }
            if (steeringProfile?.bindings?.any { it.operation == operation } == true) return@post
            val trigger = if (operation == "siri") event.action in 1..4 else event.action == 0 || event.action == 2
            if (trigger) sendSteeringOperation(operation, source)
        }
    }

    fun refreshWheelZoom(context: Context) = synchronized(this) {
        appContext = context.applicationContext
        endBridgeZoom()
        syncGeelyInputLocked()
    }

    fun learnBridgeZoom(context: Context, owner: Any, role: WheelZoomSettings.Role, done: (WheelKey?) -> Unit) {
        startSteeringLearning(context, owner) { observed ->
            if (observed == null) done(null)
            else if (observed.source in listOf("oneos", "ecarx", "vehicle_bridge")) {
                stopSteeringLearning(owner)
                val key = WheelKey(GeelySteeringKeyCodes.canonicalize(observed.keyCode) ?: observed.keyCode, 0, "vehicle_bridge")
                WheelZoomSettings.assign(context, role, key)
                done(key)
            }
        }
    }

    private fun menuAllowsBridgeZoom(): Boolean {
        val status = instrumentStatus?.status() ?: return false
        // Unknown/stale OEM menu state cannot steal a volume knob from the original instrument UI.
        return status.getInt("schema") == 1 && (!status.getBoolean("supported") || status.getBoolean("menuAllowsZoom"))
    }
    private fun bridgeZoomRoute(): Any? {
        val mode = appContext?.getSystemService(AudioManager::class.java)?.mode
        return if (learning == null && mode !in listOf(AudioManager.MODE_IN_CALL, AudioManager.MODE_IN_COMMUNICATION) &&
            menuAllowsBridgeZoom()) controller?.dashboardMapRoute() else null
    }

    private fun refreshZoomEligibility() {
        val stage = bridgeInput?.status()?.getString("stage")
        val volumes = bridgeZoomKeys.filterKeys { it != WheelZoomSettings.Role.MODE }.values
        if (bridgeZoom.updateEligibility(bridgeZoomRoute()) || zoomActive &&
            (SystemClock.elapsedRealtime() >= zoomDeadline || stage !in listOf("ACTIVE", "PARTIAL_INTERCEPTION", "CONFIGURING") ||
                stage == "PARTIAL_INTERCEPTION" && volumes.any { bridgeInput?.ownsCanonicalKey(it) != true }))
            endBridgeZoom()
        if (bridgeZoomRoute() == null) endBridgeZoom()
        updateBridgeKeys()
    }
    private fun updateBridgeKeys() {
        val route = bridgeZoomRoute()
        val mode = bridgeZoomKeys[WheelZoomSettings.Role.MODE]?.takeIf { route != null }
        val volume = if (zoomActive && route != null) bridgeZoomKeys.filterKeys { it != WheelZoomSettings.Role.MODE }.values else emptyList()
        bridgeInput?.update(if (learning != null) intArrayOf() else (bridgeBaseKeys.toList() + listOfNotNull(mode) + volume).distinct().toIntArray(), learning == null)
    }
    private fun endBridgeZoom() {
        if (zoomActive || bridgeZoom.zoomMode) zoomEpoch++
        zoomActive = false; zoomDeadline = 0L; bridgeZoom.timeOut()
    }

    private fun onBridgeZoomKey(event: GeelySteeringKeyEvent): Boolean {
        if (bridgeZoomKeys.isEmpty()) return false
        refreshZoomEligibility()
        val role = bridgeZoomKeys.entries.firstOrNull { it.value == event.keyCode }?.key ?: return false
        val route = bridgeZoomRoute() ?: return false
        val now = SystemClock.elapsedRealtime()
        if (now - event.eventTimeMs !in 0..2_000L) return true
        val volumes = bridgeZoomKeys.filterKeys { it != WheelZoomSettings.Role.MODE }.values
        val confirmed = volumes.all { bridgeInput?.ownsCanonicalKey(it) == true }
        if (role != WheelZoomSettings.Role.MODE && (!zoomActive || !confirmed)) return true
        val single = event.action == GeelySteeringKeyEvent.ACTION_SINGLE
        if (event.action !in 0..2) return false
        val action = bridgeZoom.onKey(role, event.action != GeelySteeringKeyEvent.ACTION_UP, true, true, false, event.keyCode)
        if (single) bridgeZoom.onKey(role, false, true, true, false, event.keyCode)
        when (action) {
            WheelZoomKeys.Action.MODE_ON -> {
                zoomEpoch++; zoomActive = true
                zoomDeadline = if (appContext?.let(WheelZoomSettings::behaviour) == WheelZoomSettings.Behaviour.TIMED) now + 2_000L else Long.MAX_VALUE
                updateBridgeKeys()
            }
            WheelZoomKeys.Action.MODE_OFF -> { endBridgeZoom(); updateBridgeKeys() }
            WheelZoomKeys.Action.ZOOM_IN, WheelZoomKeys.Action.ZOOM_OUT -> {
                val epoch = zoomEpoch
                controller?.zoomDashboardMap(action == WheelZoomKeys.Action.ZOOM_IN) {
                    zoomActive && epoch == zoomEpoch && SystemClock.elapsedRealtime() < zoomDeadline &&
                        bridgeZoomRoute() == route && volumes.all { bridgeInput?.ownsCanonicalKey(it) == true }
                }
            }
            else -> Unit
        }
        return action != WheelZoomKeys.Action.PASS
    }

    fun reloadSteeringProfile(context: Context) = synchronized(this) {
        appContext = context.applicationContext
        steeringProfile = SteeringProfiles.loadEnabled(context)
        lastSystemTrigger.clear()
        syncGeelyInputLocked()
    }

    fun startSteeringLearning(context: Context, owner: Any, onKey: (SteeringObservedKey?) -> Unit) = synchronized(this) {
        stopSteeringLearning(owner)
        appContext = context.applicationContext
        steeringProfile = SteeringProfiles.loadEnabled(context)
        learning = Learning(owner, onKey)
        learningTimeout?.let(mainHandler::removeCallbacks)
        learningTimeout = Runnable {
            val callback = synchronized(this) { learning?.takeIf { it.owner === owner }?.onKey }
            stopSteeringLearning(owner)
            callback?.invoke(null)
        }.also { mainHandler.postDelayed(it, 25_000L) }
        syncGeelyInputLocked()
    }

    fun stopSteeringLearning(owner: Any) = synchronized(this) {
        if (learning?.owner !== owner) return@synchronized
        learning = null
        learningTimeout?.let(mainHandler::removeCallbacks)
        learningTimeout = null
        suppressedUntil = SystemClock.elapsedRealtime() + 500L
        syncGeelyInputLocked()
    }

    fun steeringDiagnostics(): String = synchronized(this) {
        val permitted = appContext?.checkSelfPermission(android.Manifest.permission.READ_LOGS) == android.content.pm.PackageManager.PERMISSION_GRANTED
        "systemLogAccess=$permitted\n" + SteeringLogAccess.diagnostics() + "\n" +
            (bridgeInput?.diagnostics() ?: "last: $lastGeelyInputDiagnostics") + "\n" +
            (keyLogMonitor?.diagnostics() ?: "last: $lastKeyLogDiagnostics") + "\n" + observedKeys.joinToString("\n")
    }

    fun steeringDirectReady(): Boolean = synchronized(this) { bridgeInput?.ready() == true }

    private fun onObservedKey(key: SteeringObservedKey) {
        val learner = synchronized(this) {
            if (observedKeys.size >= 40) observedKeys.removeFirst()
            observedKeys.addLast("key=${key.keyCode} event=${key.event} source=${key.source} tag=${key.logTag} broadcast=${key.broadcastAction}")
            learning?.onKey
        }
        if (learner != null) { learner(key); return }
        // carlito | Passive OEM logs/broadcasts cannot consume the original music-card action.
        if (key.source in listOf("logcat", "broadcast") &&
            GeelySteeringKeyCodes.canonicalize(key.keyCode) in listOf(200085, 200087, 200088, 210005, 210006)) return
        // carlito | A bridge-owned key can also be logged/broadcast by the stock dispatcher.
        if (key.source !in listOf("oneos", "ecarx", "vehicle_bridge") && bridgeInput?.ownsCanonicalKey(
                GeelySteeringKeyCodes.canonicalize(key.keyCode) ?: key.keyCode) == true) return
        if (SystemClock.elapsedRealtime() < suppressedUntil) return
        val profile = synchronized(this) { steeringProfile }
        val binding = profile?.bindings?.firstOrNull {
            (it.keyCode == key.keyCode || it.isVendorInput &&
                GeelySteeringKeyCodes.canonicalize(it.keyCode)?.let { code ->
                    code == GeelySteeringKeyCodes.canonicalize(key.keyCode) } == true) &&
                (it.source == key.source || it.isVendorInput && key.source in listOf("oneos", "ecarx", "vehicle_bridge")) &&
                (key.source != "logcat" || it.logTag == key.logTag) &&
                it.logContains == key.logContains &&
                (key.source != "broadcast" || (it.broadcastAction == key.broadcastAction && it.keyExtra == key.keyExtra && it.eventExtra == key.eventExtra))
        }
        if (binding != null) {
            if (key.event == binding.event) {
                val now = SystemClock.elapsedRealtime()
                val ready = synchronized(this) {
                    val previous = lastSystemTrigger[binding.inputId]
                    if (previous != null && now - previous < 180L) false
                    else { lastSystemTrigger[binding.inputId] = now; true }
                }
                if (ready) sendSteeringOperation(binding.operation, key.source)
            }
            return
        }
        if (profile == null && key.source == "logcat" && key.logTag == "HardKeyModel" &&
            key.keyCode == 200231 && key.event == 0 && appContext?.let(GeelyFactoryCarPlay::load) != null) {
            val now = SystemClock.elapsedRealtime()
            val ready = synchronized(this) {
                val previous = lastSystemTrigger.put("factory_siri", now)
                previous == null || now - previous >= 180L
            }
            if (ready) sendSteeringOperation("siri", "logcat")
        }
    }

    /** Avoid applying the standard action again when this physical key has a custom mapping. */
    fun consumesHardwareKey(keyCode: Int): Boolean = synchronized(this) {
        val standard = CarPlayMediaButton.forKeyCode(keyCode) != null || CarPlayMediaButton.opensSiri(keyCode)
        ((learning != null || SystemClock.elapsedRealtime() < suppressedUntil) && standard) ||
            bridgeInput?.ownsRawKey(keyCode) == true ||
            bridgeInput?.ownsCanonicalKey(GeelySteeringKeyCodes.canonicalize(keyCode) ?: keyCode) == true ||
            steeringProfile?.bindings?.any { it.keyCode > 0 && it.keyCode == keyCode } == true
    }

    private fun sendSteeringOperation(operation: String, source: String) {
        when (operation) {
            "play_pause" -> send(CarPlayMediaButton.PLAY_PAUSE, source)
            "next" -> send(CarPlayMediaButton.NEXT, source)
            "previous" -> send(CarPlayMediaButton.PREVIOUS, source)
            "siri" -> {
                val sent = synchronized(this) { controller }?.requestSiri() == true
                Log.i(TAG, "Steering voice key source=$source sent=$sent")
            }
        }
    }

    // Another car app (its own Spotify, the radio) took audio focus and with it the steering-wheel
    // keys. When CarPlay starts playing again it becomes the car's media source again, as any player
    // would; only the start counts, so a car source picked while the iPhone plays on is not undone.
    private fun regainFocusLocked() {
        val request = focusRequest ?: return
        if (focusHeld) return
        val audio = appContext?.getSystemService(AudioManager::class.java) ?: return
        focusHeld = request.request(audio) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        if (focusHeld) forwardGrantedFocusLocked()
        Log.i(TAG, "audio focus regained=$focusHeld")
    }

    private fun updateLocked(active: Boolean) {
        val context = appContext ?: return
        if (controller == null) return
        mediaAudioActive = active
        if (active && session == null) start(context) else if (active) regainFocusLocked()
        publishPlaybackStateLocked()
    }

    private fun start(context: Context) {
        val expectedController = controller ?: return
        val owner = Any().also { focusOwner = it }
        focusEventRevision = 0L
        val audio = context.getSystemService(AudioManager::class.java)
        val request = AudioFocusRequestCompat(
            AudioManager.AUDIOFOCUS_GAIN,
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build(),
            { change -> onFocusChanged(expectedController, owner, change) },
            mainHandler,
        )
        val granted = manageAudioFocus && audio?.let(request::request) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        focusRequest = request.takeIf { manageAudioFocus }
        focusHeld = granted
        if (granted) forwardGrantedFocusLocked()
        session = MediaSession(context, "DiPlay CarPlay").apply {
            setCallback(callback, mainHandler)
            setMetadata(androidMetadata(nowPlaying, shownArtworkLocked()))
            isActive = true
        }
        Log.i(TAG, "media keys active focusGranted=$granted")
    }

    private fun forwardGrantedFocusLocked() {
        val expectedController = controller ?: return
        val owner = focusOwner ?: return
        val revision = focusEventRevision
        // Immediate grants do not promise a later focus callback. Defer dispatch until the caller
        // releases the media-key monitor. A newer real focus event invalidates this observation,
        // as do a controller or request replacement while the queued work waits.
        mainHandler.post { onFocusChanged(expectedController, owner, AudioManager.AUDIOFOCUS_GAIN, revision) }
    }

    private fun onFocusChanged(expectedController: CarPlayController, owner: Any, change: Int,
        grantedRevision: Long? = null) {
        val current = synchronized(this) {
            if (controller !== expectedController || focusOwner !== owner ||
                (grantedRevision != null && grantedRevision != focusEventRevision)) false
            else {
                focusEventRevision += 1
                // Only permanent loss moves media keys elsewhere; transient losses come back.
                if (change == AudioManager.AUDIOFOCUS_LOSS) focusHeld = false
                else if (change == AudioManager.AUDIOFOCUS_GAIN) focusHeld = true
                true
            }
        }
        if (!current) return
        Log.i(TAG, "audio focus change=$change")
        // Resolve the matching sink and invoke it outside the media-key monitor. An abandoned
        // request must never mute a newer controller, and these owners must not nest locks.
        val background = CarPlayBackgroundSession.snapshot()
        if (background?.controller === expectedController) background.sink.onMediaAudioFocusChanged(change)
    }

    private fun releaseLocked() {
        endBridgeZoom(); mainHandler.removeCallbacks(zoomPoll)
        instrumentStatus?.close(); instrumentStatus = null; bridgeZoomKeys = emptyMap()
        steeringGeneration++
        bridgeInput?.close()
        bridgeInput = null
        keyLogMonitor?.close()
        keyLogMonitor = null
        monitorGeneration++
        lastSystemTrigger.clear()
        focusOwner = null
        artworkOwner = null
        artworkQueue.clear()
        session?.let {
            it.isActive = false
            it.release()
        }
        session = null
        mediaAudioActive = false
        speechPlaying = false; resumeAfterSpeech = false; speechMedia = null
        nowPlaying = CarPlayNowPlaying()
        artwork = null
        artworkCache.clear()
        focusRequest?.let { request -> appContext?.getSystemService(AudioManager::class.java)?.let(request::abandon) }
        focusRequest = null
        focusHeld = false
        if (learning != null) syncGeelyInputLocked()
    }

    private fun publishPlaybackStateLocked() {
        val playing = if (nowPlaying.elapsedMillis != null || nowPlaying.title != null) {
            nowPlaying.playing
        } else {
            mediaAudioActive
        }
        session?.setPlaybackState(
            PlaybackState.Builder()
                .setActions(ACTIONS)
                .setState(
                    if (playing) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED,
                    nowPlaying.elapsedMillis ?: PlaybackState.PLAYBACK_POSITION_UNKNOWN,
                    if (playing) 1f else 0f,
                    // The iPhone sends elapsed time only on play, pause or seek, so Android must
                    // extrapolate from when it arrived, not from this republish.
                    elapsedUpdatedAt,
                )
                .build(),
        )
    }

    private fun send(index: Int, source: String) {
        val group = if (source in listOf("oneos", "ecarx", "vehicle_bridge", "logcat", "broadcast")) source else "media_session"
        val now = SystemClock.elapsedRealtime()
        synchronized(this) {
            if (learning != null || now < suppressedUntil) return
            if (lastSentButton == index && group != lastSentSource && now - lastSentAt < 120L) return
            resumeAfterSpeech = false
            lastSentButton = index; lastSentSource = group; lastSentAt = now
        }
        // While the car's video player is on screen the wheel drives it: a CarPlay play/pause would
        // make the iPhone end the video session.
        if (CarPlayVideo.onMediaKey(index)) {
            Log.i(TAG, "media key $source -> car video player $index")
            return
        }
        val sent = synchronized(this) { controller }?.sendMediaButton(index) ?: false
        Log.i(TAG, "media key $source -> CarPlay $index sent=$sent")
    }

    private val callback = CarPlayMediaCallback(
        send = ::send,
        consumesKey = ::consumesHardwareKey,
        experimentalDiLink3Keys = { appContext?.let(BydOutputSettings::carPlayCallControls) == true },
    )

    /** Whether [next] changes what the media session's metadata shows; position and play state do not. */
    internal fun metadataChanged(previous: CarPlayNowPlaying, next: CarPlayNowPlaying): Boolean =
        previous.copy(elapsedMillis = null, playing = false) != next.copy(elapsedMillis = null, playing = false)

    internal fun androidMetadata(info: CarPlayNowPlaying, artwork: Bitmap? = null): MediaMetadata =
        MediaMetadata.Builder().apply {
            info.title?.let {
                putString(MediaMetadata.METADATA_KEY_TITLE, it)
                putString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE, it)
            }
            info.artist?.let {
                putString(MediaMetadata.METADATA_KEY_ARTIST, it)
                putString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE, it)
            }
            info.album?.let { putString(MediaMetadata.METADATA_KEY_ALBUM, it) }
            info.durationMillis?.let { putLong(MediaMetadata.METADATA_KEY_DURATION, it) }
            info.sourceApp?.let { putString(MediaMetadata.METADATA_KEY_DISPLAY_DESCRIPTION, it) }
            artwork?.let {
                putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, it)
                putBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON, it)
            }
        }.build()

    // Without art the car draws DiPlay's bright launcher icon instead.
    private fun shownArtworkLocked(): Bitmap? =
        artwork ?: placeholder ?: appContext?.let(::placeholderArt)?.also { placeholder = it }

    internal fun placeholderArt(context: Context): Bitmap? = context
        .getDrawable(R.drawable.art_now_playing_placeholder)
        ?.toBitmap(MAX_ARTWORK_DIMENSION, MAX_ARTWORK_DIMENSION)

    /**
     * The art to show once the iPhone names transfer [id]. A pending transfer keeps [current], so the
     * placeholder does not flash between tracks.
     */
    internal fun nextArtwork(id: Int?, cache: Map<Int, Bitmap?>, current: Bitmap?): Bitmap? = when {
        id == null -> null
        cache.containsKey(id) -> cache[id]
        else -> current
    }

    private fun decodeArtwork(bytes: ByteArray): Bitmap? {
        if (bytes.isEmpty()) return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth !in 1..MAX_ARTWORK_SOURCE_DIMENSION ||
            bounds.outHeight !in 1..MAX_ARTWORK_SOURCE_DIMENSION
        ) return null
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > MAX_ARTWORK_DIMENSION * 2) sample *= 2
        val decoded = BitmapFactory.decodeByteArray(
            bytes,
            0,
            bytes.size,
            BitmapFactory.Options().apply { inSampleSize = sample },
        ) ?: return null
        return squareArtwork(decoded).also { square -> if (square !== decoded) decoded.recycle() }
    }

    /**
     * Fits [source] inside a transparent square of at most [MAX_ARTWORK_DIMENSION]. Car clusters draw
     * art in a square box and stretch it, so 16:9 video thumbnails looked squashed.
     */
    internal fun squareArtwork(source: Bitmap): Bitmap {
        val largest = maxOf(source.width, source.height)
        if (source.width == source.height && largest <= MAX_ARTWORK_DIMENSION) return source
        val side = minOf(largest, MAX_ARTWORK_DIMENSION)
        val scale = side.toFloat() / largest
        val width = (source.width * scale).roundToInt().coerceAtLeast(1)
        val height = (source.height * scale).roundToInt().coerceAtLeast(1)
        val left = (side - width) / 2
        val top = (side - height) / 2
        return Bitmap.createBitmap(side, side, Bitmap.Config.ARGB_8888).also { square ->
            Canvas(square).drawBitmap(
                source,
                null,
                Rect(left, top, left + width, top + height),
                Paint(Paint.FILTER_BITMAP_FLAG),
            )
        }
    }

    private const val MAX_ARTWORK_DIMENSION = 384
    private const val MAX_ARTWORK_SOURCE_DIMENSION = 8_192
    private const val MAX_CACHED_ARTWORK = 4
}

/**
 * Media-session input → CarPlay presses. Hardware keys arrive as button events and keep the toggle;
 * media controllers (not hardware keys) call [onPlay] and [onPause] with an explicit intent.
 */
internal class CarPlayMediaCallback(
    private val send: (index: Int, source: String) -> Unit,
    private val consumesKey: (Int) -> Boolean = { false },
    private val experimentalDiLink3Keys: () -> Boolean = { false },
) : MediaSession.Callback() {
    constructor(send: (index: Int, source: String) -> Unit) : this(send, { false }, { false })

    // carlito: Preserve upstream's named opt-in with a trailing send callback.
    constructor(experimentalDiLink3Keys: () -> Boolean, send: (index: Int, source: String) -> Unit) :
        this(send = send, consumesKey = { false }, experimentalDiLink3Keys = experimentalDiLink3Keys)

    override fun onMediaButtonEvent(mediaButtonIntent: Intent): Boolean {
        @Suppress("DEPRECATION")
        val event = mediaButtonIntent.getParcelableExtra<KeyEvent>(Intent.EXTRA_KEY_EVENT) ?: return false
        if (consumesKey(event.keyCode)) return true
        val index = CarPlayMediaButton.forKeyCode(event.keyCode, experimentalDiLink3Keys())
            ?: return super.onMediaButtonEvent(mediaButtonIntent)
        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
            send(index, KeyEvent.keyCodeToString(event.keyCode))
        }
        return true
    }

    override fun onPlay() = send(CarPlayMediaButton.PLAY, "play")
    override fun onPause() = send(CarPlayMediaButton.PAUSE, "pause")
    override fun onSkipToNext() = send(CarPlayMediaButton.NEXT, "next")
    override fun onSkipToPrevious() = send(CarPlayMediaButton.PREVIOUS, "previous")
}
