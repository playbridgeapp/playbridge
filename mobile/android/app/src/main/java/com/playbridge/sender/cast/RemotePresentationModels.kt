package com.playbridge.sender.cast



/** Live playback status synced from the TV via `status` messages. */
data class TvPlaybackStatus(
    val state: String,
    val positionMs: Long,
    val durationMs: Long,
    val title: String?,
    val mediaKind: String = "video",
)

/** An available audio or subtitle track on the TV, synced via `tracks` messages. */
data class MediaTrack(
    val id: String,
    val name: String,
    val selected: Boolean,
    val type: String? = null
)

internal val subtitleOffIds = setOf("off", "none", "no", "-1")

/** Use the receiver's own Off ID; Apple receivers omit the row, but accept `none`. */
internal fun subtitleOffTrack(tracks: List<MediaTrack>): MediaTrack =
    tracks.firstOrNull { it.id.lowercase() in subtitleOffIds }
        ?: MediaTrack(id = "none", name = "Off", selected = tracks.none { it.selected })

/** TV player settings synced via `player_settings` messages. */
data class TvPlayerSettings(
    val speed: Float = 1.0f,
    val scaling: String = "Fit",
    val audioBoost: Boolean = false,
    val subtitleOffsetMs: Long = 0L,
    val engine: String = "",
    val qualityMaxHeight: Int = 0,
    val currentVideoHeight: Int = 0,
    val isLive: Boolean = false,
    val isSeekable: Boolean = true,
    val speedAvailable: Boolean = true,
    val scalingAvailable: Boolean = true,
    val audioBoostAvailable: Boolean = true,
    val qualityAvailable: Boolean = false,
    val loop: Boolean? = null,
)
