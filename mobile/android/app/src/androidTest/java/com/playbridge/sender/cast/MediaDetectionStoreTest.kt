package com.playbridge.sender.cast

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Exercise native message ingestion with Android logging and the real Compose store. */
class MediaDetectionStoreTest {
    @Test
    fun categoryChangesRemoveRowsAcrossTabsWithoutLosingDocumentOrdering() {
        val first = "media-filter-first-${System.nanoTime()}"
        val second = "media-filter-suspended-${System.nanoTime()}"
        val version = DetectorPageVersion(detectorEpoch = 7L, navigationGeneration = 3L)
        for (tab in listOf(first, second)) {
            VideoDetector.acceptDetectorVideo(tab, version)
            for ((kind, extension) in listOf("video" to "mp4", "image" to "jpg")) {
                VideoDetector.onMessageReceived(Json.parseToJsonElement(
                    """{"type":"video_detected","url":"https://cdn.example/media.$extension","mediaKind":"$kind"}"""
                ).jsonObject, tab)
            }
        }
        assertEquals(2, VideoDetector.getVideosForTab(first).size)
        VideoDetector.filterDetections({ true }, { it != DetectedMediaKind.IMAGE })
        for (tab in listOf(first, second)) {
            assertEquals(listOf(DetectedMediaKind.VIDEO), VideoDetector.getVideosForTab(tab).map { it.kind })
            assertEquals(DetectorMessageOrder.STALE,
                VideoDetector.onDetectorNavigation(tab, version.copy(navigationGeneration = 1L)))
        }
        VideoDetector.filterDetections({ it != second }, { true })
        assertEquals(1, VideoDetector.getVideosForTab(first).size)
        assertTrue(VideoDetector.getVideosForTab(second).isEmpty())
        VideoDetector.clearTab(first)
        VideoDetector.clearTab(second)
    }

    @Test
    fun largeCatalogBoundsImagesWithoutRemovingStreamsAndCanRediscoverExpiredPosters() {
        val tab = "media-large-catalog-${System.nanoTime()}"
        fun detect(url: String, kind: String) {
            VideoDetector.onMessageReceived(Json.parseToJsonElement(
                """{"type":"video_detected","url":"$url","mediaKind":"$kind"}"""
            ).jsonObject, tab)
        }
        try {
            detect("https://cdn.example/movie.mp4", "video")
            for (index in 0 until 100) detect("https://cdn.example/poster-$index.jpg", "image")
            val rows = VideoDetector.getVideosForTab(tab)
            assertEquals(30, rows.count { it.kind == DetectedMediaKind.IMAGE })
            assertTrue(rows.any { it.url == "https://cdn.example/movie.mp4" })
            assertTrue(rows.none { it.url == "https://cdn.example/poster-0.jpg" })
            assertTrue(rows.any { it.url == "https://cdn.example/poster-99.jpg" })
            detect("https://cdn.example/poster-0.jpg", "image")
            assertEquals(30, rows.count { it.kind == DetectedMediaKind.IMAGE })
            assertTrue(rows.any { it.url == "https://cdn.example/poster-0.jpg" })
            assertTrue(rows.any { it.url == "https://cdn.example/movie.mp4" })
        } finally { VideoDetector.clearTab(tab) }
    }

}
