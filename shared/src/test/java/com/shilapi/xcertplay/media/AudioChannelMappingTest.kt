package com.shilapi.xcertplay.media

import android.media.AudioManager
import org.junit.Assert.assertEquals
import org.junit.Test

class AudioChannelMappingTest {
    @Test
    fun mobileCompatibleMappingMatchesTheOriginalRouting() {
        assertMapped(
            mode = AudioChannelMappingMode.MOBILE_COMPATIBLE,
            audioType = "telephony",
            payloadType = 100,
            channel = AudioChannel.PHONE,
            contentType = AudioContentType.SPEECH,
        )
        assertMapped(
            mode = AudioChannelMappingMode.MOBILE_COMPATIBLE,
            audioType = "speechRecognition",
            payloadType = 100,
            channel = AudioChannel.ASSISTANT,
            contentType = AudioContentType.SPEECH,
        )
        assertMapped(
            mode = AudioChannelMappingMode.MOBILE_COMPATIBLE,
            audioType = "media",
            payloadType = 100,
            channel = AudioChannel.MEDIA,
            contentType = AudioContentType.MUSIC,
        )
        listOf("default", "alert", "compatibility").forEach { audioType ->
            assertMapped(
                mode = AudioChannelMappingMode.MOBILE_COMPATIBLE,
                audioType = audioType,
                payloadType = 100,
                channel = AudioChannel.NAVIGATION,
                contentType = AudioContentType.SPEECH,
                streamType = AudioChannelMapper.DEFAULT_NAVIGATION_STREAM_TYPE,
            )
        }
    }

    @Test
    fun automotiveMappingUsesTheBusSpecificCarPlayTypes() {
        listOf("media", "compatibility").forEach { audioType ->
            assertMapped(
                mode = AudioChannelMappingMode.AUTOMOTIVE_BUS,
                audioType = audioType,
                payloadType = 100,
                channel = AudioChannel.MEDIA,
                contentType = AudioContentType.MUSIC,
            )
        }
        assertMapped(
            mode = AudioChannelMappingMode.AUTOMOTIVE_BUS,
            audioType = "telephony",
            payloadType = 100,
            channel = AudioChannel.PHONE,
            contentType = AudioContentType.SPEECH,
        )
        assertMapped(
            mode = AudioChannelMappingMode.AUTOMOTIVE_BUS,
            audioType = "speechRecognition",
            payloadType = 100,
            channel = AudioChannel.ASSISTANT,
            contentType = AudioContentType.SPEECH,
        )
        listOf("default", "alert").forEach { audioType ->
            assertMapped(
                mode = AudioChannelMappingMode.AUTOMOTIVE_BUS,
                audioType = audioType,
                payloadType = 100,
                channel = AudioChannel.NAVIGATION,
                contentType = AudioContentType.SPEECH,
                streamType = AudioChannelMapper.DEFAULT_NAVIGATION_STREAM_TYPE,
            )
        }
    }

    @Test
    fun unknownTypesKeepTheMainHighAudioFallback() {
        assertMapped(
            mode = AudioChannelMappingMode.MOBILE_COMPATIBLE,
            audioType = "unknown",
            payloadType = AudioChannelMapper.STREAM_TYPE_MAIN_HIGH_AUDIO,
            channel = AudioChannel.MEDIA,
            contentType = AudioContentType.MUSIC,
        )
        assertMapped(
            mode = AudioChannelMappingMode.AUTOMOTIVE_BUS,
            audioType = "unknown",
            payloadType = 100,
            channel = AudioChannel.NAVIGATION,
            contentType = AudioContentType.SPEECH,
            streamType = AudioChannelMapper.DEFAULT_NAVIGATION_STREAM_TYPE,
        )
    }

    @Test
    fun aPhoneCallOnTheSpeakerUsesTheMediaChannel() {
        val phone = AudioChannelMapper.map("telephony", 100, AudioChannelMappingMode.MOBILE_COMPATIBLE)
        assertEquals(AudioChannel.PHONE, phone.channel)
        assertEquals(
            AudioChannelSelection(AudioChannel.MEDIA, AudioContentType.SPEECH, phone.streamType),
            AudioChannelMapper.playCallOnSpeaker(phone),
        )
        val media = AudioChannelMapper.map("media", 100, AudioChannelMappingMode.MOBILE_COMPATIBLE)
        assertEquals(media, AudioChannelMapper.playCallOnSpeaker(media))
    }

    @Test
    fun navigationStreamTypeIsOnlyAppliedToGuidanceChannels() {
        val custom = 7
        assertEquals(
            AudioChannelSelection(AudioChannel.NAVIGATION, AudioContentType.SPEECH, custom),
            AudioChannelMapper.map("alert", 100, AudioChannelMappingMode.MOBILE_COMPATIBLE, custom),
        )
        assertEquals(
            AudioChannelSelection(AudioChannel.NAVIGATION, AudioContentType.SPEECH, custom),
            AudioChannelMapper.map("default", 100, AudioChannelMappingMode.AUTOMOTIVE_BUS, custom),
        )
        assertEquals(
            AudioChannelSelection(AudioChannel.MEDIA, AudioContentType.MUSIC, AudioManager.STREAM_MUSIC),
            AudioChannelMapper.map("media", 100, AudioChannelMappingMode.AUTOMOTIVE_BUS, custom),
        )
    }

    private fun assertMapped(
        mode: AudioChannelMappingMode,
        audioType: String,
        payloadType: Int,
        channel: AudioChannel,
        contentType: AudioContentType,
        streamType: Int = AudioManager.STREAM_MUSIC,
    ) {
        assertEquals(
            AudioChannelSelection(channel, contentType, streamType),
            AudioChannelMapper.map(audioType, payloadType, mode),
        )
    }
}
