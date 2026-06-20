package com.playbridge.player.player

data class SkipSegment(
    val type: String, // "intro", "recap", "outro"
    val startMs: Long,
    val endMs: Long
)
