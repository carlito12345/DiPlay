package com.shilapi.xcertplay.media

import org.junit.Assert.assertEquals
import org.junit.Test

class VideoOperatingRateTest {
    @Test fun theMainScreenDecoderAsksForTheFrameRateSetting() {
        assertEquals(60, videoOperatingRate(110, statsLabel = null, frameRate = 60))
        assertEquals(30, videoOperatingRate(110, statsLabel = null, frameRate = 30))
        assertEquals(0, videoOperatingRate(110, statsLabel = null, frameRate = 0)) // none known: leave it unset
    }

    @Test fun otherDecodersKeepTheirFormat() {
        assertEquals(0, videoOperatingRate(111, statsLabel = null, frameRate = 60)) // the cluster stream
        assertEquals(0, videoOperatingRate(110, statsLabel = " stream=110 mirror=centre", frameRate = 60))
    }

    @Test fun theRateIsTriedFirstOnItsOwnBeforeTheExistingFallbacks() {
        assertEquals(
            listOf(
                DecoderAttempt(null, tuned = true, operatingRate = 60),
                DecoderAttempt(null, tuned = true),
                DecoderAttempt(null, tuned = false),
                DecoderAttempt("c2.android.avc.decoder", tuned = false),
            ),
            videoDecoderAttempts(60, "c2.android.avc.decoder"),
        )
    }

    @Test fun withoutARateTheAttemptsAreTheOnesBefore() {
        assertEquals(
            listOf(DecoderAttempt(null, tuned = true), DecoderAttempt(null, tuned = false)),
            videoDecoderAttempts(0, softwareDecoder = null),
        )
    }

    @Test fun aRefusedRateIsNotAskedForAgain() {
        assertEquals(60, nextOperatingRate(60, DecoderAttempt(null, tuned = true, operatingRate = 60)))
        assertEquals(0, nextOperatingRate(60, DecoderAttempt(null, tuned = true))) // refused, tuned worked
        assertEquals(0, nextOperatingRate(60, DecoderAttempt(null, tuned = false))) // the MediaTek case
        assertEquals(60, nextOperatingRate(60, used = null)) // nothing worked: not the rate's fault
        assertEquals(0, nextOperatingRate(0, DecoderAttempt(null, tuned = true)))
    }

    @Test fun onlyQualcommDecodersGetTheVendorLowLatencyKeys() {
        val keys = listOf("vendor.qti-ext-dec-low-latency.enable", "vendor.qti-ext-dec-picture-order.enable")
        assertEquals(keys, vendorLowLatencyKeys("c2.qti.avc.decoder"))
        assertEquals(keys, vendorLowLatencyKeys("OMX.qcom.video.decoder.avc"))
        assertEquals(emptyList<String>(), vendorLowLatencyKeys("c2.mtk.avc.decoder"))
        assertEquals(emptyList<String>(), vendorLowLatencyKeys("c2.android.avc.decoder"))
    }
}
