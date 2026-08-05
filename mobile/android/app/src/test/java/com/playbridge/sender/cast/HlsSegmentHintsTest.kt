package com.playbridge.sender.cast

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HlsSegmentHintsTest {

    // --- segmentFormatForBody: container inference from media playlists ---

    @Test
    fun jpgSegmentsAreTreatedAsTs() {
        val body = """
            #EXTM3U
            #EXT-X-TARGETDURATION:2
            #EXTINF:2,
            000.jpg?session=test
            #EXTINF:2,
            001.jpg?session=test
        """.trimIndent()

        assertEquals("ts", HlsSegmentHints.segmentFormatForBody(body))
    }

    @Test
    fun jpegSegmentIsTreatedAsTs() {
        val body = """
            #EXTM3U
            #EXT-X-TARGETDURATION:6
            #EXTINF:6,
            https://cdn.example/video/000.jpeg
        """.trimIndent()

        assertEquals("ts", HlsSegmentHints.segmentFormatForBody(body))
    }

    @Test
    fun llHlsJpgPartsAreTreatedAsTs() {
        val body = """
            #EXTM3U
            #EXT-X-TARGETDURATION:2
            #EXT-X-PART:DURATION=0.333,URI="part.jpg?session=test"
            #EXTINF:2,
            000.jpg?session=test
            #EXT-X-PRELOAD-HINT:TYPE=PART,URI="next.jpg"
        """.trimIndent()

        assertEquals("ts", HlsSegmentHints.segmentFormatForBody(body))
    }

    @Test
    fun tsSegmentsAreTreatedAsTs() {
        val body = """
            #EXTM3U
            #EXT-X-TARGETDURATION:6
            #EXTINF:6,
            segment-1.ts
        """.trimIndent()

        assertEquals("ts", HlsSegmentHints.segmentFormatForBody(body))
    }

    @Test
    fun mixedJpgAndTsStillAgreeOnTs() {
        val body = """
            #EXTM3U
            #EXT-X-TARGETDURATION:6
            #EXTINF:6,
            000.jpg
            #EXTINF:6,
            001.ts
        """.trimIndent()

        assertEquals("ts", HlsSegmentHints.segmentFormatForBody(body))
    }

    @Test
    fun m4sSegmentsAreTreatedAsFmp4() {
        val body = """
            #EXTM3U
            #EXT-X-TARGETDURATION:6
            #EXTINF:6,
            segment-1.m4s
        """.trimIndent()

        assertEquals("fmp4", HlsSegmentHints.segmentFormatForBody(body))
    }

    @Test
    fun mapTagMeansFmp4() {
        val body = """
            #EXTM3U
            #EXT-X-TARGETDURATION:6
            #EXT-X-MAP:URI="init.mp4"
            #EXTINF:6,
            segment-1.jpg
        """.trimIndent()

        assertEquals("fmp4", HlsSegmentHints.segmentFormatForBody(body))
    }

    @Test
    fun packedAudioIsNotRelabelledAsMpegTs() {
        val body = """
            #EXTM3U
            #EXT-X-TARGETDURATION:10
            #EXTINF:10,
            track.aac
        """.trimIndent()

        assertNull(HlsSegmentHints.segmentFormatForBody(body))
    }

    @Test
    fun mixedTsAndFmp4HaveNoCommonFormat() {
        val body = """
            #EXTM3U
            #EXT-X-TARGETDURATION:6
            #EXTINF:6,
            000.ts
            #EXTINF:6,
            001.m4s
        """.trimIndent()

        assertNull(HlsSegmentHints.segmentFormatForBody(body))
    }

    @Test
    fun masterPlaylistHasNoSegmentFormat() {
        val body = """
            #EXTM3U
            #EXT-X-STREAM-INF:BANDWIDTH=2000000
            720/index.m3u8
        """.trimIndent()

        assertNull(HlsSegmentHints.segmentFormatForBody(body))
    }

    @Test
    fun nonPlaylistBodyHasNoSegmentFormat() {
        assertNull(HlsSegmentHints.segmentFormatForBody("<html>not a manifest</html>"))
        assertNull(HlsSegmentHints.segmentFormatForBody(null))
    }

    // --- variantUrlsForBody ---

    @Test
    fun variantsAreResolvedSortedAndDeduped() {
        val body = """
            #EXTM3U
            #EXT-X-STREAM-INF:BANDWIDTH=800000
            low/index.m3u8
            #EXT-X-STREAM-INF:BANDWIDTH=3000000
            high/index.m3u8
            #EXT-X-STREAM-INF:BANDWIDTH=800000
            low/index.m3u8
        """.trimIndent()

        val variants = HlsSegmentHints.variantUrlsForBody(body, "https://cdn.example/path/master.m3u8")

        assertEquals(
            listOf(
                "https://cdn.example/path/high/index.m3u8",
                "https://cdn.example/path/low/index.m3u8",
            ),
            variants,
        )
    }

    @Test
    fun audioMediaUrisAreIncludedAsVariants() {
        val body = """
            #EXTM3U
            #EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="aud",NAME="English",URI="audio/eng.m3u8"
            #EXT-X-STREAM-INF:BANDWIDTH=2000000,AUDIO="aud"
            720/index.m3u8
        """.trimIndent()

        val variants = HlsSegmentHints.variantUrlsForBody(body, "https://cdn.example/master.m3u8")

        assertTrue(variants.contains("https://cdn.example/audio/eng.m3u8"))
        assertTrue(variants.contains("https://cdn.example/720/index.m3u8"))
    }

    // --- commonSegmentFormatForBodies ---

    @Test
    fun commonFormatRequiresAllBodiesToAgree() {
        val tsBody = """
            #EXTM3U
            #EXT-X-TARGETDURATION:2
            #EXTINF:2,
            000.jpg
        """.trimIndent()

        assertEquals(
            "ts",
            HlsSegmentHints.commonSegmentFormatForBodies(listOf(tsBody, tsBody)),
        )
        assertNull(
            HlsSegmentHints.commonSegmentFormatForBodies(listOf(tsBody, null)),
        )
        assertNull(
            HlsSegmentHints.commonSegmentFormatForBodies(emptyList()),
        )
    }

    // --- streamTypeForBody ---

    @Test
    fun endlistIsBuffered() {
        val body = """
            #EXTM3U
            #EXT-X-TARGETDURATION:6
            #EXTINF:6,
            000.jpg
            #EXT-X-ENDLIST
        """.trimIndent()

        assertEquals("buffered", HlsSegmentHints.streamTypeForBody(body))
    }

    @Test
    fun llHlsMarkersAreLive() {
        val body = """
            #EXTM3U
            #EXT-X-TARGETDURATION:2
            #EXT-X-SERVER-CONTROL:CAN-BLOCK-RELOAD=YES
            #EXT-X-PART:DURATION=0.333,URI="part.jpg"
            #EXTINF:2,
            000.jpg
        """.trimIndent()

        assertEquals("live", HlsSegmentHints.streamTypeForBody(body))
    }

    @Test
    fun ambiguousPlaylistIsBuffered() {
        val body = """
            #EXTM3U
            #EXT-X-TARGETDURATION:6
            #EXTINF:6,
            000.ts
        """.trimIndent()

        assertEquals("buffered", HlsSegmentHints.streamTypeForBody(body))
        assertEquals("buffered", HlsSegmentHints.streamTypeForBody(null))
    }

    // --- withHints / readers round-trip ---

    @Test
    fun withHintsAttachesAndReplacesQueryParams() {
        val hinted = HlsSegmentHints.withHints(
            "http://192.168.1.10:9000/s/id/playlist.m3u8?token=abc",
            format = "ts",
            streamType = "buffered",
        )

        assertTrue(hinted.contains("token=abc"))
        assertEquals("ts", hinted.substringAfter("pb_hls_format=").substringBefore('&'))
        assertEquals("buffered", hinted.substringAfter("pb_hls_stream=").substringBefore('&'))

        val rehinted = HlsSegmentHints.withHints(hinted, format = "fmp4", streamType = "live")
        assertEquals("fmp4", rehinted.substringAfter("pb_hls_format=").substringBefore('&'))
        assertEquals("live", rehinted.substringAfter("pb_hls_stream=").substringBefore('&'))
        assertEquals(1, rehinted.split("pb_hls_format=").size - 1)
        assertEquals(1, rehinted.split("pb_hls_stream=").size - 1)
    }

    @Test
    fun withHintsWithoutFormatOmitsFormatParam() {
        val hinted = HlsSegmentHints.withHints(
            "http://192.168.1.10:9000/s/id/playlist.m3u8",
            format = null,
            streamType = "buffered",
        )

        assertTrue(!hinted.contains("pb_hls_format"))
        assertTrue(hinted.contains("pb_hls_stream=buffered"))
    }

    @Test
    fun withHintsLeavesUnparseableUrlsUnchanged() {
        assertEquals(
            "data:application/x-mpegurl;base64,AAA",
            HlsSegmentHints.withHints("data:application/x-mpegurl;base64,AAA", "ts", "buffered"),
        )
    }

    @Test
    fun readersMapHintsOntoCastLoadMetadata() {
        val url = "http://192.168.1.10:9000/s/id/master.m3u8?pb_hls_format=ts&pb_hls_stream=live"

        val container = HlsSegmentHints.formatFromUrl(url)
        assertEquals("ts", container)
        assertEquals("ts_aac", HlsSegmentHints.googleCastAudioFormat(container))
        assertEquals("mpeg2_ts", HlsSegmentHints.googleCastVideoFormat(container))
        assertEquals("LIVE", HlsSegmentHints.streamTypeFromUrl(url))
    }

    @Test
    fun fmp4HintsMapToFmp4CastMetadata() {
        val url = "http://192.168.1.10:9000/media/id/master.m3u8?pb_hls_format=fmp4&pb_hls_stream=buffered"

        val container = HlsSegmentHints.formatFromUrl(url)
        assertEquals("fmp4", HlsSegmentHints.googleCastAudioFormat(container))
        assertEquals("fmp4", HlsSegmentHints.googleCastVideoFormat(container))
        assertEquals("BUFFERED", HlsSegmentHints.streamTypeFromUrl(url))
    }

    @Test
    fun urlsWithoutHintsYieldNoCastMetadata() {
        val url = "http://192.168.1.10:9000/s/id/playlist.m3u8"

        assertNull(HlsSegmentHints.formatFromUrl(url))
        assertNull(HlsSegmentHints.streamTypeFromUrl(url))
        assertNull(HlsSegmentHints.googleCastAudioFormat(null))
        assertNull(HlsSegmentHints.googleCastVideoFormat(null))
    }

    @Test
    fun bogusHintValuesAreIgnored() {
        val url = "http://192.168.1.10:9000/s/id/playlist.m3u8?pb_hls_format=webm&pb_hls_stream=weird"

        assertNull(HlsSegmentHints.formatFromUrl(url))
        assertNull(HlsSegmentHints.streamTypeFromUrl(url))
    }

    // --- content-type gate ---

    @Test
    fun hlsContentTypeDetection() {
        assertTrue(HlsSegmentHints.isHlsContentType("application/vnd.apple.mpegurl"))
        assertTrue(HlsSegmentHints.isHlsContentType("application/x-mpegURL"))
        assertTrue(HlsSegmentHints.isHlsContentType("video/m3u8"))
        assertTrue(!HlsSegmentHints.isHlsContentType("video/mp4"))
        assertTrue(!HlsSegmentHints.isHlsContentType(null))
    }
}
