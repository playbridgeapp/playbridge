package com.playbridge.player.player

import com.playbridge.shared.network.MediaNetworkPolicy
import playbridge.PlayPayload

internal fun PlayPayload.externalSubtitleUrls(): List<String> =
    (subtitles + subtitle_resources.map { it.url }).filter(String::isNotBlank).distinct()

internal fun PlayPayload.isPageControlledMedia(): Boolean =
    detected_by == "page_cast" || detected_by == "linked_page"

internal fun PlayPayload.headersForSubtitle(url: String): Map<String, String> {
    subtitle_resources.firstOrNull { it.url == url }?.let { return it.headers }
    return if (!isPageControlledMedia() || MediaNetworkPolicy.sameOrigin(this.url, url)) {
        headers
    } else {
        emptyMap()
    }
}

internal fun PlayPayload.subtitleHeadersByUrl(): Map<String, Map<String, String>> =
    externalSubtitleUrls().associateWith(::headersForSubtitle)
