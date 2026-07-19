package com.playbridge.player.data

/**
 * Privacy-safe playback-context descriptions for diagnostics.
 *
 * External subtitle URLs and media/history identifiers may contain credentials, so logs only
 * record whether an external subtitle exists. Track metadata is bounded and stripped of control
 * characters before it is written to Logcat or the optional persistent log.
 */
internal fun PlaybackContext?.toSafeLogString(): String {
    if (this == null) return "<none>"
    return "audio=${audioTrack.toSafeLogString()}, " +
        "subtitle=${subtitleTrack.toSafeLogString()}, " +
        "subtitlesDisabled=$subtitlesDisabled, " +
        "externalSubtitle=${!externalSubtitleUrl.isNullOrBlank()}, " +
        "speed=$playbackSpeed, scaling=$videoScalingMode, " +
        "qualityMaxHeight=$videoQualityMaxHeight, subtitleDelayMs=$subtitleDelayMs, " +
        "loop=$isLooping"
}

internal fun PlaybackTrackPreference?.toSafeLogString(): String {
    if (this == null) return "<none>"
    return "{id=${id.safeTrackLogValue()}, label=${label.safeTrackLogValue()}, " +
        "language=${language.safeTrackLogValue()}}"
}

internal fun historyLogKey(id: String): String = id.hashCode().toUInt().toString(16)

private fun String?.safeTrackLogValue(): String = this
    ?.replace(Regex("[\\p{Cntrl}]"), " ")
    ?.trim()
    ?.take(80)
    ?.takeIf(String::isNotBlank)
    ?: "<none>"
