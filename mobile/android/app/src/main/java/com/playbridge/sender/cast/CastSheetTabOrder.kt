package com.playbridge.sender.cast

private val defaultCastSheetTabOrder = listOf(
    DetectedMediaKind.VIDEO,
    DetectedMediaKind.AUDIO,
    DetectedMediaKind.SUBTITLE,
    DetectedMediaKind.IMAGE,
)

internal fun prioritizedCastSheetTabs(
    videoCount: Int,
    audioCount: Int,
    subtitleCount: Int,
    imageCount: Int,
): List<DetectedMediaKind> {
    val counts = mapOf(
        DetectedMediaKind.VIDEO to videoCount,
        DetectedMediaKind.AUDIO to audioCount,
        DetectedMediaKind.SUBTITLE to subtitleCount,
        DetectedMediaKind.IMAGE to imageCount,
    )
    return defaultCastSheetTabOrder.filter { counts.getValue(it) > 0 } +
        defaultCastSheetTabOrder.filter { counts.getValue(it) <= 0 }
}
