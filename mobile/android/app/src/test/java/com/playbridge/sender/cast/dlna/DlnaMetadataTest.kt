package com.playbridge.sender.cast.dlna

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DlnaMetadataTest {
    @Test
    fun responseHeadersUseStreamingAndInteractiveModes() {
        assertEquals(
            listOf(
                "transferMode.dlna.org" to "Streaming",
                "realTimeInfo.dlna.org" to "DLNA.ORG_TLAG=*",
                "contentFeatures.dlna.org" to "DLNA.ORG_OP=01;DLNA.ORG_CI=0;DLNA.ORG_FLAGS=01700000000000000000000000000000",
            ),
            dlnaResponseHeaders("video/mp4", requestContentFeatures = true, byteSeek = true),
        )
        assertEquals(
            listOf("transferMode.dlna.org" to "Interactive"),
            dlnaResponseHeaders("image/jpeg", requestContentFeatures = false, byteSeek = true),
        )
    }

    @Test
    fun contentFeaturesRequestHeaderNameIsCaseInsensitive() {
        assertTrue(requestsDlnaContentFeatures(listOf("GETCONTENTFEATURES.DLNA.ORG" to "1")))
        assertFalse(requestsDlnaContentFeatures(listOf("getcontentFeatures.dlna.org" to "0")))
    }
}
