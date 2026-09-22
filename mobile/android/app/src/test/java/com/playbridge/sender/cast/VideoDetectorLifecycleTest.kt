package com.playbridge.sender.cast

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * SPA (same-document) navigations advance a tab's media lifecycle without clearing
 * detections; stale detector messages must not move it. Unique tab ids keep the
 * [VideoDetector] singleton isolated between tests.
 */
class VideoDetectorLifecycleTest {

    @Test
    fun detectorMasterQualitiesAreImmediatelyRankable() {
        val message = Json.parseToJsonElement(
            """
            {
              "type": "video_detected",
              "url": "https://cdn.example/master.m3u8",
              "contentType": "application/vnd.apple.mpegurl",
              "detectedBy": "body_content_m3u8",
              "hlsRole": "master",
              "mediaKind": "video",
              "qualities": [
                {
                  "resolution": "1080p",
                  "bandwidth": 5000000,
                  "averageBandwidth": 4500000,
                  "url": "https://cdn.example/1080.m3u8",
                  "codecs": "avc1.640028,mp4a.40.2",
                  "audioGroupId": "audio",
                  "frameRate": "23.976"
                },
                {
                  "resolution": "720p",
                  "bandwidth": 2500000,
                  "averageBandwidth": null,
                  "url": "https://cdn.example/720.m3u8",
                  "codecs": null,
                  "audioGroupId": null,
                  "frameRate": null
                }
              ]
            }
            """.trimIndent(),
        ).jsonObject

        val qualities = detectorVideoQualities(message["qualities"]?.jsonArray).orEmpty()
        val video = DetectedVideo(
            url = "https://cdn.example/master.m3u8",
            contentType = "application/vnd.apple.mpegurl",
            detectedBy = "body_content_m3u8",
            hlsRole = "master",
            qualities = qualities,
            qualitiesChecked = true,
            hlsPlaylist = HlsPlaylist(
                videoQualities = qualities,
                masterPlaylistUrl = "https://cdn.example/master.m3u8",
                validation = HlsPlaylistValidation.VALID_MASTER,
            ),
            validationState = MediaValidationState.VERIFIED_PLAYABLE,
            isPlayable = true,
        )

        assertEquals(2, qualities.size)
        assertEquals("1080p", qualities.first().resolution)
        assertEquals(4_500_000L, qualities.first().averageBandwidth)
        assertTrue(video.castScore() > 700)
    }

    @Test
    fun sameDocumentNavigationBumpsLifecycleForCurrentDocumentOnly() {
        val tabId = "spa-lifecycle-bump-${System.nanoTime()}"
        val version = DetectorPageVersion(detectorEpoch = 7L, navigationGeneration = 2L)

        // First contact with the document advances (nothing to clear yet).
        assertTrue(VideoDetector.acceptDetectorVideo(tabId, version))
        assertEquals(0, VideoDetector.lifecycleIndexForTab(tabId))

        assertTrue(VideoDetector.onSameDocumentNavigation(tabId, version, atMs = 1_000_000L))
        assertEquals(1, VideoDetector.lifecycleIndexForTab(tabId))

        assertTrue(VideoDetector.onSameDocumentNavigation(tabId, version, atMs = 1_001_000L))
        assertEquals(2, VideoDetector.lifecycleIndexForTab(tabId))
    }

    @Test
    fun staleSameDocumentNavigationDoesNotBumpLifecycle() {
        val tabId = "spa-lifecycle-stale-${System.nanoTime()}"
        val version = DetectorPageVersion(detectorEpoch = 7L, navigationGeneration = 3L)
        assertTrue(VideoDetector.acceptDetectorVideo(tabId, version))

        val stale = version.copy(navigationGeneration = 1L)
        assertFalse(VideoDetector.onSameDocumentNavigation(tabId, stale, atMs = 1_000_000L))
        assertEquals(0, VideoDetector.lifecycleIndexForTab(tabId))
    }

    @Test
    fun fullNavigationResetsLifecycle() {
        val tabId = "spa-lifecycle-reset-${System.nanoTime()}"
        val first = DetectorPageVersion(detectorEpoch = 7L, navigationGeneration = 4L)
        assertEquals(
            DetectorMessageOrder.ADVANCE,
            VideoDetector.onDetectorNavigation(tabId, first),
        )
        assertTrue(VideoDetector.onSameDocumentNavigation(tabId, first, atMs = 1_000_000L))
        assertEquals(1, VideoDetector.lifecycleIndexForTab(tabId))

        // A real document commit clears the tab and its lifecycle counter.
        val next = first.copy(navigationGeneration = 5L)
        assertEquals(
            DetectorMessageOrder.ADVANCE,
            VideoDetector.onDetectorNavigation(tabId, next),
        )
        assertEquals(0, VideoDetector.lifecycleIndexForTab(tabId))
    }
}
