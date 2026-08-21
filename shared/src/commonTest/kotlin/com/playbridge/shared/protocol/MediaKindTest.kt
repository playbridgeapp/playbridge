package com.playbridge.shared.protocol

import kotlin.test.Test
import kotlin.test.assertEquals
import playbridge.PlayPayload

class MediaKindTest {
    @Test
    fun explicitKindWinsOverWeakTransportHints() {
        assertEquals(
            MediaKind.IMAGE,
            resolveMediaKind(
                PlayPayload(
                    url = "https://example.test/resource",
                    content_type = "application/octet-stream",
                    media_kind = "image",
                ),
            ),
        )
        assertEquals(
            MediaKind.AUDIO,
            resolveMediaKind(
                PlayPayload(
                    url = "https://example.test/live.m3u8",
                    content_type = "application/vnd.apple.mpegurl",
                    media_kind = "audio",
                ),
            ),
        )
    }

    @Test
    fun mimeAndExtensionInferenceIsPerItem() {
        assertEquals(
            MediaKind.AUDIO,
            resolveMediaKind(PlayPayload(url = "https://example.test/song.flac")),
        )
        assertEquals(
            MediaKind.IMAGE,
            resolveMediaKind(PlayPayload(url = "https://example.test/photo.webp?token=x")),
        )
        assertEquals(
            MediaKind.VIDEO,
            resolveMediaKind(
                PlayPayload(url = "https://example.test/unknown", content_type = "series"),
            ),
        )
    }
}
