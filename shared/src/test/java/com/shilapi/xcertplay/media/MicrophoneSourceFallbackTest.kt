package com.shilapi.xcertplay.media

import android.Manifest
import android.media.AudioManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AudioEffect
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import android.os.Parcel
import com.shilapi.xcertplay.airplay.AudioStreamId
import com.shilapi.xcertplay.airplay.MicrophoneConfig
import java.net.InetAddress
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.annotation.RealObject
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowAudioEffect
import org.robolectric.shadows.ShadowAudioRecord
import org.robolectric.util.ReflectionHelpers

/** Exercises the real sink/uplink, including creation, teardown and exported diagnostics. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28, 33], manifest = Config.NONE, shadows = [MicrophoneSourceFallbackTest.RecorderBuilder::class,
    MicrophoneSourceFallbackTest.CurrentAudioEffect::class])
class MicrophoneSourceFallbackTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private val id = AudioStreamId(100, "speechrecognition")
    private val diagnostics = CopyOnWriteArrayList<String>()
    private val captured = AtomicReference<AudioRecord?>()
    private val captureStarted = CountDownLatch(1)
    private lateinit var sink: AndroidMediaSink

    @Before fun setUp() {
        RecorderBuilder.attempts.clear()
        RecorderBuilder.built.clear()
        RecorderBuilder.rejectSources = emptySet()
        RecorderBuilder.uninitializedSources = emptySet()
        RecorderBuilder.startRejectedSources = emptySet()
        RecorderBuilder.stoppedSources = emptySet()
        RecorderBuilder.effectsAtBuild.clear()
        RecorderBuilder.effectsAtFailedStart.clear()
        shadowOf(context).grantPermissions(Manifest.permission.RECORD_AUDIO, Manifest.permission.MODIFY_AUDIO_SETTINGS)
        context.getSystemService(AudioManager::class.java).mode = AudioManager.MODE_RINGTONE
        sink = AndroidMediaSink(context = context, onAudioDiagnostic = diagnostics::add)
        ShadowAudioRecord.setSourceProvider { recorder ->
            captured.set(recorder)
            captureStarted.countDown()
            object : ShadowAudioRecord.AudioRecordSource {
                override fun readInByteArray(buffer: ByteArray, offset: Int, size: Int, blocking: Boolean): Int {
                    Thread.sleep(5)
                    return 0
                }
            }
        }
    }

    @After fun tearDown() {
        sink.close()
        ShadowAudioRecord.clearSource()
    }

    @Test fun siriCreationFailureRetriesCommunicationOnceWithoutCallEffectsOrModeChanges() {
        RecorderBuilder.rejectSources = setOf(MediaRecorder.AudioSource.VOICE_RECOGNITION)

        sink.onMicrophoneStarted(id, config("speechrecognition"))

        assertTrue(captureStarted.await(5, TimeUnit.SECONDS))
        val recorder = requireNotNull(captured.get())
        assertEquals(listOf(MediaRecorder.AudioSource.VOICE_RECOGNITION, MediaRecorder.AudioSource.VOICE_COMMUNICATION), RecorderBuilder.attempts)
        assertEquals(MediaRecorder.AudioSource.VOICE_COMMUNICATION, recorder.audioSource)
        assertEquals(AudioManager.MODE_RINGTONE, context.getSystemService(AudioManager::class.java).mode)
        assertTrue(ShadowAudioEffect.getAudioEffects().isEmpty())
        assertTrue(diagnostics.any { it.contains("failure type=speechrecognition source=VOICE_RECOGNITION") && it.contains("stage=RECORDER_CREATION") })
        assertTrue(diagnostics.any { it.startsWith("Microphone: start type=speechrecognition source=VOICE_COMMUNICATION") })
        sink.onMicrophoneStopped(id)
        assertEquals(AudioRecord.STATE_UNINITIALIZED, recorder.state)
        assertTrue(diagnostics.any { it.contains("stats type=speechrecognition source=VOICE_COMMUNICATION") && it.endsWith("ended=true") })
    }

    @Test fun siriInitializationFailureReleasesRejectedRecorderBeforeFallbackCapture() {
        RecorderBuilder.uninitializedSources = setOf(MediaRecorder.AudioSource.VOICE_RECOGNITION)

        sink.onMicrophoneStarted(id, config("speechrecognition"))

        assertTrue(captureStarted.await(5, TimeUnit.SECONDS))
        assertEquals(2, RecorderBuilder.built.size)
        assertTrue((RecorderBuilder.built.first() as RejectedRecorder).released)
        assertEquals(AudioRecord.RECORDSTATE_RECORDING, RecorderBuilder.built.last().recordingState)
        assertTrue(diagnostics.any { it.contains("source=VOICE_RECOGNITION") && it.contains("stage=RECORDER_INITIALIZATION") })
    }

    @Test fun bothUnsupportedSourcesFailOnceAndALaterStreamCanStart() {
        RecorderBuilder.rejectSources = setOf(MediaRecorder.AudioSource.VOICE_RECOGNITION, MediaRecorder.AudioSource.VOICE_COMMUNICATION)
        sink.onMicrophoneStarted(id, config("speechrecognition"))
        assertEquals(listOf(MediaRecorder.AudioSource.VOICE_RECOGNITION, MediaRecorder.AudioSource.VOICE_COMMUNICATION), RecorderBuilder.attempts)
        assertNull(captured.get())
        assertEquals(2, diagnostics.count { it.contains("stage=RECORDER_CREATION") })
        assertTrue(diagnostics.none { it.startsWith("Microphone: start") })

        RecorderBuilder.rejectSources = emptySet()
        sink.onMicrophoneStarted(id, config("speechrecognition"))
        assertTrue(captureStarted.await(5, TimeUnit.SECONDS))
        assertEquals(MediaRecorder.AudioSource.VOICE_RECOGNITION, requireNotNull(captured.get()).audioSource)
    }

    @Test fun telephonyCreationFailureDoesNotRetryOrLeaveTheCallModeActive() {
        RecorderBuilder.rejectSources = setOf(MediaRecorder.AudioSource.VOICE_COMMUNICATION)
        sink.onMicrophoneStarted(AudioStreamId(101, "telephony"), config("telephony"))
        assertEquals(listOf(MediaRecorder.AudioSource.VOICE_COMMUNICATION), RecorderBuilder.attempts)
        assertNull(captured.get())
        assertEquals(AudioManager.MODE_RINGTONE, context.getSystemService(AudioManager::class.java).mode)
        assertTrue(ShadowAudioEffect.getAudioEffects().isEmpty())
    }

    @Test fun siriRecordingExceptionReleasesRejectedSourceAndStartsCommunicationFallback() {
        RecorderBuilder.startRejectedSources = setOf(MediaRecorder.AudioSource.VOICE_RECOGNITION)
        assertSiriStartFallback()
    }

    @Test fun siriSilentlyStoppedRecorderIsNotReportedAsStartedAndRetriesCommunication() {
        RecorderBuilder.stoppedSources = setOf(MediaRecorder.AudioSource.VOICE_RECOGNITION)
        assertSiriStartFallback()
    }

    private fun assertSiriStartFallback() {
        sink.onMicrophoneStarted(id, config("speechrecognition"))
        assertTrue(captureStarted.await(5, TimeUnit.SECONDS))
        assertEquals(listOf(MediaRecorder.AudioSource.VOICE_RECOGNITION,
            MediaRecorder.AudioSource.VOICE_COMMUNICATION), RecorderBuilder.attempts)
        assertTrue((RecorderBuilder.built.first() as FailedStartRecorder).released)
        assertSame(RecorderBuilder.built.last(), captured.get())
        assertEquals(AudioRecord.RECORDSTATE_RECORDING, captured.get()!!.recordingState)
        assertEquals(1, diagnostics.count { it.startsWith("Microphone: start") })
        assertTrue(diagnostics.any { it.contains("stage=RECORDING") })
        assertTrue(diagnostics.any { it.startsWith("Microphone: start type=speechrecognition source=VOICE_COMMUNICATION") })
        assertTrue(ShadowAudioEffect.getAudioEffects().isEmpty())
        assertEquals(AudioManager.MODE_RINGTONE, context.getSystemService(AudioManager::class.java).mode)
    }

    @Test fun factoryRecordingExceptionReleasesCallEffectsAndRetriesAndroidSource() {
        RecorderBuilder.startRejectedSources = setOf(MediaRecorder.AudioSource.MIC)
        assertFactoryStartFallback()
    }

    @Test fun factorySilentlyStoppedRecorderReleasesCallEffectsAndRetriesAndroidSource() {
        RecorderBuilder.stoppedSources = setOf(MediaRecorder.AudioSource.MIC)
        assertFactoryStartFallback()
    }

    private fun assertFactoryStartFallback() {
        for (type in listOf(AudioEffect.EFFECT_TYPE_AEC, AudioEffect.EFFECT_TYPE_NS)) {
            ShadowAudioEffect.addEffect(AudioEffect.Descriptor(type.toString(), type.toString(),
                "Pre Processing", "Test effect", "DiPlay"))
        }
        val cancellers = mutableListOf<TestEchoCanceller>()
        val uplink = MicrophoneUplink(config("telephony"), onDiagnostic = diagnostics::add,
            factorySource = MediaRecorder.AudioSource.MIC, echoReference = EchoReference(16_000),
            echoCancellerFactory = { _, _, _ -> TestEchoCanceller().also(cancellers::add) })
        try {
            assertTrue(uplink.start())
            assertTrue(captureStarted.await(5, TimeUnit.SECONDS))
            assertEquals(listOf(MediaRecorder.AudioSource.MIC,
                MediaRecorder.AudioSource.VOICE_COMMUNICATION), RecorderBuilder.attempts)
            assertTrue((RecorderBuilder.built.first() as FailedStartRecorder).released)
            assertEquals(listOf(2), RecorderBuilder.effectsAtFailedStart)
            assertEquals(listOf(0, 0), RecorderBuilder.effectsAtBuild)
            assertEquals(2, cancellers.size)
            assertTrue(cancellers.first().closed)
            assertFalse(cancellers.last().closed)
            val effects = ShadowAudioEffect.getAudioEffects()
            assertEquals(2, effects.size)
            assertFalse(effects.filterIsInstance<AcousticEchoCanceler>().single().enabled)
            // carlito | Software AEC denoises after cancellation; platform NS stays disabled.
            assertFalse(effects.filterIsInstance<NoiseSuppressor>().single().enabled)
            effects.forEach {
                assertEquals(captured.get()!!.audioSessionId, Shadow.extract<ShadowAudioEffect>(it).audioSession)
            }
            assertEquals(AudioRecord.RECORDSTATE_RECORDING, captured.get()!!.recordingState)
            assertEquals(1, diagnostics.count { it.startsWith("Microphone: start") })
            assertTrue(diagnostics.any { it.contains("stage=RECORDING") })
        } finally { uplink.close() }
        assertTrue(cancellers.all { it.closed })
        assertTrue(ShadowAudioEffect.getAudioEffects().isEmpty())
        assertEquals(AudioRecord.STATE_UNINITIALIZED, captured.get()!!.state)
    }

    @Test fun allStoppedSourcesFailWithoutCaptureAndALaterStreamCanStart() {
        RecorderBuilder.stoppedSources = setOf(MediaRecorder.AudioSource.VOICE_RECOGNITION,
            MediaRecorder.AudioSource.VOICE_COMMUNICATION)
        sink.onMicrophoneStarted(id, config("speechrecognition"))
        assertNull(captured.get())
        assertEquals(2, diagnostics.count { it.contains("stage=RECORDING") })
        assertTrue(RecorderBuilder.built.all { (it as FailedStartRecorder).released })
        assertTrue(diagnostics.none { it.startsWith("Microphone: start") })
        RecorderBuilder.stoppedSources = emptySet()
        sink.onMicrophoneStarted(id, config("speechrecognition"))
        assertTrue(captureStarted.await(5, TimeUnit.SECONDS))
        assertEquals(AudioRecord.RECORDSTATE_RECORDING, captured.get()!!.recordingState)
    }

    private fun config(type: String) = MicrophoneConfig(audioType = type, sampleRate = 16_000,
        channels = 1, payloadType = 100, frameMillis = 20, host = InetAddress.getLoopbackAddress(),
        port = 9, key = ByteArray(32))

    @Implements(AudioRecord.Builder::class)
    class RecorderBuilder {
        @RealObject private lateinit var builder: AudioRecord.Builder

        @Implementation fun build(): AudioRecord {
            val attributes = ReflectionHelpers.getField<AudioAttributes>(builder, "mAttributes")
            val source = ReflectionHelpers.callInstanceMethod<Int>(attributes, "getCapturePreset")
            attempts += source
            effectsAtBuild += ShadowAudioEffect.getAudioEffects().size
            if (source in rejectSources) throw UnsupportedOperationException("Unsupported test capture source")
            return (when {
                source in uninitializedSources -> RejectedRecorder(source)
                source in startRejectedSources -> FailedStartRecorder(source, throwsOnStart = true)
                source in stoppedSources -> FailedStartRecorder(source, throwsOnStart = false)
                else -> Shadow.directlyOn<AudioRecord, AudioRecord.Builder>(builder, AudioRecord.Builder::class.java, "build")
            }).also {
                built += it
            }
        }

        companion object {
            val attempts = CopyOnWriteArrayList<Int>()
            val built = CopyOnWriteArrayList<AudioRecord>()
            var rejectSources = emptySet<Int>()
            var uninitializedSources = emptySet<Int>()
            var startRejectedSources = emptySet<Int>()
            var stoppedSources = emptySet<Int>()
            val effectsAtBuild = CopyOnWriteArrayList<Int>()
            val effectsAtFailedStart = CopyOnWriteArrayList<Int>()
        }
    }

    class RejectedRecorder(source: Int) : AudioRecord(source, 16_000,
        AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, 4096) {
        var released = false
            private set
        override fun getState(): Int = STATE_UNINITIALIZED
        override fun release() {
            released = true
            super.release()
        }
    }

    class FailedStartRecorder(source: Int, private val throwsOnStart: Boolean) : AudioRecord(source,
        16_000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, 4096) {
        var released = false
            private set
        override fun getState(): Int = if (released) STATE_UNINITIALIZED else STATE_INITIALIZED
        override fun getRecordingState(): Int = RECORDSTATE_STOPPED
        override fun startRecording() {
            RecorderBuilder.effectsAtFailedStart += ShadowAudioEffect.getAudioEffects().size
            if (throwsOnStart) throw IllegalStateException("Test capture source refused recording")
        }
        override fun release() {
            released = true
            super.release()
        }
    }

    // Robolectric 4.17 tracks the pre-Android-11 native_setup signature only. Bridge Android
    // 13's attribution/device arguments into that same lifecycle rather than treating its
    // untracked effects as unavailable or weakening the release assertions.
    @Implements(AudioEffect::class)
    class CurrentAudioEffect : ShadowAudioEffect() {
        @Suppress("UNUSED_PARAMETER")
        @Implementation(minSdk = 33, maxSdk = 33)
        protected fun native_setup(effectThis: Any, type: String, uuid: String, priority: Int,
                                   session: Int, deviceType: Int, deviceAddress: String, id: IntArray,
                                   descriptor: Array<Any?>, attribution: Parcel, probe: Boolean): Int =
            super.native_setup(effectThis, type, uuid, priority, session, id, descriptor, "")
    }

    private class TestEchoCanceller : CallEchoCanceller {
        override val frameSamples = 320
        var closed = false
            private set
        override fun process(frame: ByteArray, reference: ShortArray): Boolean = true
        override fun close() { closed = true }
    }
}
