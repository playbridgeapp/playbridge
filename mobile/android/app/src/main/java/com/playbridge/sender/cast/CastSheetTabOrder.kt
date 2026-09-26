package com.playbridge.sender.cast

private val defaultCastSheetTabOrder = listOf(
    DetectedMediaKind.VIDEO,
    DetectedMediaKind.SUBTITLE,
    DetectedMediaKind.AUDIO,
    DetectedMediaKind.IMAGE,
)

internal fun prioritizedCastSheetTabs(
    videoCount: Int,
    audioCount: Int,
    subtitleCount: Int,
    imageCount: Int,
    hideEmptyAudioAndImages: Boolean = true,
): List<DetectedMediaKind> {
    val counts = mapOf(
        DetectedMediaKind.VIDEO to videoCount,
        DetectedMediaKind.AUDIO to audioCount,
        DetectedMediaKind.SUBTITLE to subtitleCount,
        DetectedMediaKind.IMAGE to imageCount,
    )
    val visible = defaultCastSheetTabOrder.filter { kind ->
        !hideEmptyAudioAndImages ||
            kind == DetectedMediaKind.VIDEO ||
            kind == DetectedMediaKind.SUBTITLE ||
            counts.getValue(kind) > 0
    }
    return visible.filter { counts.getValue(it) > 0 } +
        visible.filter { counts.getValue(it) <= 0 }
}
