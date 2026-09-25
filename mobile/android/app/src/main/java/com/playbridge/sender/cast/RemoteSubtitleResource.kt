package com.playbridge.sender.cast

import playbridge.SubtitleResource

/** Preserve the subtitle request's own headers when attaching it after playback starts. */
internal fun subtitleResourceForDetected(subtitle: DetectedVideo): SubtitleResource {
    val headers = subtitle.headers.orEmpty().toMutableMap()
    if (!subtitle.originUrl.isNullOrBlank() &&
        headers.keys.none { it.equals("Referer", ignoreCase = true) }
    ) headers["Referer"] = subtitle.originUrl
    return SubtitleResource(
        url = subtitle.url,
        headers = headers,
        label = subtitle.title ?: subtitle.url.substringBefore('?').substringAfterLast('/'),
        language = subtitle.subtitleLanguage,
    )
}
