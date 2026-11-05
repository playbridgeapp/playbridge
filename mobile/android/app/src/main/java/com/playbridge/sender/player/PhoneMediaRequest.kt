package com.playbridge.sender.player

import androidx.media3.common.MediaItem

/**
 * Request details carried as a [MediaItem]'s tag. ExoPlayer gets the same headers from its media
 * sources; mpv reads them from here, because it plays plain media items.
 *
 * [subtitleHeaders] maps a sidecar subtitle URL to its own headers. Subtitles without an entry
 * are fetched with no headers, so video credentials never reach a subtitle host.
 */
data class PhoneMediaRequest(
    val headers: Map<String, String> = emptyMap(),
    val subtitleHeaders: Map<String, Map<String, String>> = emptyMap(),
) {
    companion object {
        fun of(item: MediaItem?): PhoneMediaRequest =
            item?.localConfiguration?.tag as? PhoneMediaRequest ?: PhoneMediaRequest()
    }
}
