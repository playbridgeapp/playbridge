package com.playbridge.sender.cast

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BuildCastSheetVideosTest {

    @Test
    fun withoutHandoff_ranksNormally() {
        val a = DetectedVideo(url = "https://cdn.example/a.mp4", timestamp = 1)
        val b = DetectedVideo(
            url = "https://cdn.example/master.m3u8",
            contentType = "application/vnd.apple.mpegurl",
            timestamp = 2,
        )
        val out = buildCastSheetVideos(listOf(a, b))
        assertEquals(listOf(b.url, a.url), out.map { it.url })
    }

    @Test
    fun withPlaylistBody_prependsSyntheticRow() {
        val raw = DetectedVideo(
            url = "https://cdn.example/chunklist_video.m3u8?session=1",
            contentType = "application/vnd.apple.mpegurl",
            playlistBody = "#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=1\nhttps://cdn.example/v.m3u8\n",
            audioUrl = "https://cdn.example/audio.m3u8?session=1",
            timestamp = 10,
        )
        val other = DetectedVideo(url = "https://cdn.example/other.mp4", timestamp = 1)
        val out = buildCastSheetVideos(listOf(raw, other))

        assertEquals(2, out.size)
        assertEquals(SYNTHETIC_CAST_ITEM_TITLE, out[0].title)
        assertTrue(out[0].isSyntheticMaster)
        assertTrue(out[0].hasSyntheticHandoff)
        assertEquals(raw.url, out[0].url)
        assertEquals(raw.playlistBody, out[0].playlistBody)
        assertEquals(raw.audioUrl, out[0].audioUrl)
        assertEquals(other.url, out[1].url)
    }

    @Test
    fun detectorSynthetic_isPromotedAndNotDuplicated() {
        val synth = DetectedVideo(
            url = "https://cdn.example/session-video.m3u8",
            isSyntheticMaster = true,
            playlistBody = "#EXTM3U\n",
            detectedBy = "synthetic_hls_master",
            title = "old title",
            timestamp = 5,
        )
        val ladder = DetectedVideo(
            url = "https://cdn.example/chunklist_0.m3u8",
            contentType = "application/x-mpegurl",
            timestamp = 4,
        )
        val out = buildCastSheetVideos(listOf(ladder, synth))

        assertEquals(2, out.size)
        assertEquals(SYNTHETIC_CAST_ITEM_TITLE, out[0].title)
        assertEquals("synthetic", out[0].detectedBy)
        assertFalse(out.any { it !== out[0] && it.isSyntheticMaster })
        assertEquals(ladder.url, out[1].url)
    }

    @Test
    fun castScore_prefersSyntheticHandoff() {
        val synth = DetectedVideo(
            url = "https://cdn.example/a.m3u8",
            playlistBody = "#EXTM3U\n",
        )
        val hls = DetectedVideo(
            url = "https://cdn.example/master.m3u8",
            contentType = "application/vnd.apple.mpegurl",
        )
        assertTrue(synth.castScore() > hls.castScore())
    }

    @Test
    fun mediaKinds_keepAudioAndImagesOutOfVideosTab() {
        val video = DetectedVideo(url = "https://cdn.example/movie.mp4")
        val audio = DetectedVideo(
            url = "https://cdn.example/theme.mp3",
            contentType = "audio/mpeg",
        )
        val image = DetectedVideo(
            url = "https://cdn.example/poster",
            contentType = "image/webp",
        )

        assertEquals(listOf(video), buildCastSheetVideos(listOf(audio, image, video)))
        assertEquals(listOf(audio), buildCastSheetAudio(listOf(audio, image, video)))
        assertEquals(listOf(image), buildCastSheetImages(listOf(audio, image, video)))
    }

    @Test
    fun demuxedHlsAudio_isClassifiedAsAudio() {
        val audio = DetectedVideo(
            url = "https://cdn.example/chunklist_audio.m3u8",
            contentType = "application/vnd.apple.mpegurl",
            hlsRole = "audio_media",
        )

        assertEquals(DetectedMediaKind.AUDIO, audio.kind)
        assertTrue(audio.isAudio)
        assertEquals(0, audio.castScore())
    }

    @Test
    fun explicitDetectorKind_supportsExtensionlessImages() {
        val image = DetectedVideo(
            url = "https://cdn.example/resource/42",
            contentType = "application/octet-stream",
            mediaKind = "image",
            width = 1920,
            height = 1080,
        )

        assertEquals(DetectedMediaKind.IMAGE, image.kind)
        assertTrue(image.isImage)
    }

    @Test
    fun adaptiveStreamUrl_winsOverDeceptiveImageMime() {
        val stream = DetectedVideo(
            url = "https://cdn.example/manifest/master.m3u8",
            contentType = "image/jpeg",
        )

        assertEquals(DetectedMediaKind.VIDEO, stream.kind)
        assertTrue(stream.isVideo)
    }

    @Test
    fun toolbarBadge_prefersVideoThenAudioThenImages() {
        val image = DetectedVideo(url = "https://cdn.example/poster.jpg")
        val audio = DetectedVideo(url = "https://cdn.example/theme.mp3")
        val video1 = DetectedVideo(url = "https://cdn.example/movie.mp4")
        val video2 = DetectedVideo(url = "https://cdn.example/trailer.webm")

        assertEquals(
            DetectedMediaBadge(DetectedMediaKind.VIDEO, 2),
            buildDetectedMediaBadge(listOf(image, audio, video1, video2)),
        )
        assertEquals(
            DetectedMediaBadge(DetectedMediaKind.AUDIO, 1),
            buildDetectedMediaBadge(listOf(image, audio)),
        )
        assertEquals(
            DetectedMediaBadge(DetectedMediaKind.IMAGE, 1),
            buildDetectedMediaBadge(listOf(image)),
        )
        assertEquals(
            null,
            buildDetectedMediaBadge(listOf(DetectedVideo(url = "https://cdn.example/captions.vtt"))),
        )
        assertEquals(null, buildDetectedMediaBadge(emptyList()))
    }

    @Test
    fun thumbnailPrefetch_isLimitedToTopRankedVideos() {
        val progressive = DetectedVideo(
            url = "https://cdn.example/movie.mp4",
            timestamp = 3,
        )
        val hls = DetectedVideo(
            url = "https://cdn.example/master.m3u8",
            contentType = "application/vnd.apple.mpegurl",
            timestamp = 2,
            validationState = MediaValidationState.VERIFIED_PLAYABLE,
        )
        val dash = DetectedVideo(
            url = "https://cdn.example/manifest.mpd",
            contentType = "application/dash+xml",
            timestamp = 1,
            validationState = MediaValidationState.FAILED,
        )
        val audio = DetectedVideo(
            url = "https://cdn.example/theme.mp3",
            contentType = "audio/mpeg",
        )

        assertEquals(
            listOf(hls.url, progressive.url),
            thumbnailPrefetchCandidates(listOf(progressive, audio, dash, hls)).map { it.url },
        )
        assertEquals(emptyList<DetectedVideo>(), thumbnailPrefetchCandidates(listOf(hls), limit = 0))
    }

    @Test
    fun verifiedManifestOutranksNewerUrlGuess() {
        val guessed = DetectedVideo(
            url = "https://cdn.example/not-really-a-playlist.m3u8",
            detectedBy = "url_pattern_m3u8",
            timestamp = 20,
        )
        val actual = DetectedVideo(
            url = "https://cdn.example/session/actual.m3u8",
            detectedBy = "body_content_m3u8",
            timestamp = 10,
            validationState = MediaValidationState.VERIFIED_PLAYABLE,
            thumbnailState = ThumbnailPreviewState.READY,
        )

        assertEquals(
            listOf(actual.url, guessed.url),
            buildCastSheetVideos(listOf(guessed, actual)).map { it.url },
        )
    }

    @Test
    fun multivariantMasterOutranksChildThatFinishedPreviewFirst() {
        val masterUrl = "https://cdn.example/master.m3u8"
        val child = DetectedVideo(
            url = "https://cdn.example/playlist.m3u8",
            detectedBy = "body_content_m3u8",
            headers = mapOf("Referer" to "https://example.com/watch"),
            timestamp = 20,
            lastSeen = 20,
            validationState = MediaValidationState.VERIFIED_PLAYABLE,
            thumbnailState = ThumbnailPreviewState.READY,
            hlsPlaylist = HlsPlaylist(
                videoQualities = emptyList(),
                masterPlaylistUrl = "https://cdn.example/playlist.m3u8",
                validation = HlsPlaylistValidation.VALID_MEDIA,
            ),
        )
        val qualities = listOf(
            VideoQuality("1920x1080", 5_000_000, "https://cdn.example/1080p.m3u8"),
            VideoQuality("1280x720", 2_500_000, "https://cdn.example/720p.m3u8"),
            VideoQuality("854x480", 1_200_000, "https://cdn.example/480p.m3u8"),
        )
        val master = DetectedVideo(
            url = masterUrl,
            detectedBy = "url_pattern_m3u8",
            timestamp = 10,
            lastSeen = 10,
            qualities = qualities,
            qualitiesChecked = true,
            validationState = MediaValidationState.VERIFIED_PLAYABLE,
            hlsPlaylist = HlsPlaylist(
                videoQualities = qualities,
                masterPlaylistUrl = masterUrl,
                validation = HlsPlaylistValidation.VALID_MASTER,
            ),
        )

        assertEquals(
            listOf(master.url, child.url),
            buildCastSheetVideos(listOf(child, master)).map { it.url },
        )
    }

    @Test
    fun failedCandidateRanksBelowPendingCandidate() {
        val failed = DetectedVideo(
            url = "https://cdn.example/fake.m3u8",
            detectedBy = "url_pattern_m3u8",
            validationState = MediaValidationState.FAILED,
        )
        val pending = DetectedVideo(
            url = "https://cdn.example/movie.mp4",
            validationState = MediaValidationState.PENDING,
        )

        assertEquals(
            listOf(pending.url, failed.url),
            buildCastSheetVideos(listOf(failed, pending)).map { it.url },
        )
    }

    @Test
    fun recentObservationBreaksEqualEvidenceTie() {
        val older = DetectedVideo(
            url = "https://cdn.example/older.m3u8",
            detectedBy = "body_content_m3u8",
            timestamp = 100,
            lastSeen = 100,
            validationState = MediaValidationState.VERIFIED_PLAYABLE,
        )
        val active = DetectedVideo(
            url = "https://cdn.example/active.m3u8",
            detectedBy = "body_content_m3u8",
            timestamp = 50,
            lastSeen = 200,
            validationState = MediaValidationState.VERIFIED_PLAYABLE,
        )

        assertEquals(
            listOf(active.url, older.url),
            buildCastSheetVideos(listOf(older, active)).map { it.url },
        )
    }
}
