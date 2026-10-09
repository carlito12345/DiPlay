package com.shilapi.xcertplay.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VideoFramesAtOnceTest {
    private val tang = listOf(
        DecoderCandidate("c2.qti.avc.decoder", hardware = true, alias = false, lowLatency = false),
        DecoderCandidate("OMX.qcom.video.decoder.avc", hardware = true, alias = true, lowLatency = false),
        DecoderCandidate("c2.qti.avc.decoder.low_latency", hardware = true, alias = false, lowLatency = true),
        DecoderCandidate("OMX.qcom.video.decoder.avc.low_latency", hardware = true, alias = true, lowLatency = true),
        DecoderCandidate("c2.android.avc.decoder", hardware = false, alias = false, lowLatency = false),
    )

    @Test fun theFirstHardwareLowLatencyDecoderIsChosen() {
        assertEquals("c2.qti.avc.decoder.low_latency", lowLatencyDecoderName(tang))
    }

    @Test fun aliasesAndSoftwareDecodersAreSkipped() {
        assertNull(lowLatencyDecoderName(listOf(
            DecoderCandidate("OMX.qcom.video.decoder.avc.low_latency", hardware = true, alias = true, lowLatency = true),
            DecoderCandidate("c2.android.avc.decoder", hardware = false, alias = false, lowLatency = true),
        )))
    }

    @Test fun noLowLatencyDecoderMeansNone() {
        assertNull(lowLatencyDecoderName(tang.filterNot { it.lowLatency }))
        assertNull(lowLatencyDecoderName(emptyList()))
    }

    @Test fun theLowLatencyDecoderIsTriedBeforeTheDefaultOne() {
        assertEquals(
            listOf(
                DecoderAttempt("c2.qti.avc.decoder.low_latency", tuned = true, operatingRate = 60),
                DecoderAttempt("c2.qti.avc.decoder.low_latency", tuned = true),
                DecoderAttempt(null, tuned = true, operatingRate = 60),
                DecoderAttempt(null, tuned = true),
                DecoderAttempt(null, tuned = false),
                DecoderAttempt("c2.android.avc.decoder", tuned = false),
            ),
            videoDecoderAttempts(60, "c2.android.avc.decoder", lowLatencyDecoder = "c2.qti.avc.decoder.low_latency"),
        )
        assertEquals(
            listOf(
                DecoderAttempt("c2.qti.avc.decoder.low_latency", tuned = true),
                DecoderAttempt(null, tuned = true),
                DecoderAttempt(null, tuned = false),
            ),
            videoDecoderAttempts(0, softwareDecoder = null, lowLatencyDecoder = "c2.qti.avc.decoder.low_latency"),
        )
    }

    @Test fun aRefusedLowLatencyDecoderIsNotTriedAgain() {
        val name = "c2.qti.avc.decoder.low_latency"
        assertEquals(name, nextLowLatencyDecoder(name, DecoderAttempt(name, tuned = true, operatingRate = 60)))
        assertEquals(name, nextLowLatencyDecoder(name, DecoderAttempt(name, tuned = true)))
        assertNull(nextLowLatencyDecoder(name, DecoderAttempt(null, tuned = true))) // the default took over
        assertNull(nextLowLatencyDecoder(name, DecoderAttempt("c2.android.avc.decoder", tuned = false)))
        assertEquals(name, nextLowLatencyDecoder(name, used = null)) // nothing worked: not the decoder's fault
        assertNull(nextLowLatencyDecoder(null, DecoderAttempt(null, tuned = true)))
    }
}
