package com.playbridge.sender.player

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.TrackGroup
import androidx.media3.common.Tracks
import com.playbridge.shared.player.MpvTrackType
import com.playbridge.shared.player.parseMpvTrackList

/** One entry of mpv's `track-list` property. */
internal data class MpvTrack(
    val type: Int,
    val mpvId: String,
    val language: String?,
    val title: String?,
    val codec: String?,
    val selected: Boolean,
    val width: Int,
    val height: Int,
    val channelCount: Int,
) {
    /** Stable [TrackGroup] id, so a [androidx.media3.common.TrackSelectionOverride] maps back to mpv. */
    val groupId: String get() = "mpv:${MpvTrackList.typeName(type)}:$mpvId"
}

/**
 * Presents mpv's tracks as Media3 [Tracks], so the player's existing audio and subtitle sheets
 * and stats overlay work unchanged on mpv. Every track mpv lists is one it can decode.
 */
internal object MpvTrackList {
    fun parse(json: String): List<MpvTrack> = parseMpvTrackList(json).map { track ->
        MpvTrack(
            type = when (track.type) {
                MpvTrackType.VIDEO -> C.TRACK_TYPE_VIDEO
                MpvTrackType.AUDIO -> C.TRACK_TYPE_AUDIO
                MpvTrackType.SUBTITLE -> C.TRACK_TYPE_TEXT
            },
            mpvId = track.id,
            language = track.language,
            title = track.title,
            codec = track.codec,
            selected = track.selected,
            width = track.width,
            height = track.height,
            channelCount = track.channelCount,
        )
    }

    fun toTracks(tracks: List<MpvTrack>): Tracks = Tracks(
        tracks.map { track ->
            Tracks.Group(
                TrackGroup(track.groupId, format(track)),
                false,
                intArrayOf(C.FORMAT_HANDLED),
                booleanArrayOf(track.selected),
            )
        },
    )

    /** The mpv track a [TrackGroup] built by [toTracks] stands for, or null for any other group. */
    fun parseGroupId(groupId: String): Pair<Int, String>? {
        val parts = groupId.split(':')
        if (parts.size != 3 || parts[0] != "mpv") return null
        val type = when (parts[1]) {
            "video" -> C.TRACK_TYPE_VIDEO
            "audio" -> C.TRACK_TYPE_AUDIO
            "sub" -> C.TRACK_TYPE_TEXT
            else -> return null
        }
        return type to parts[2]
    }

    private fun format(track: MpvTrack): Format = Format.Builder()
        .setId(track.mpvId)
        .setSampleMimeType(mimeType(track.type, track.codec))
        .setCodecs(track.codec)
        .setLanguage(track.language)
        .setLabel(track.title)
        .apply {
            if (track.width > 0) setWidth(track.width)
            if (track.height > 0) setHeight(track.height)
            if (track.channelCount > 0) setChannelCount(track.channelCount)
        }
        .build()

    internal fun mimeType(type: Int, codec: String?): String = when (type) {
        C.TRACK_TYPE_VIDEO -> when (codec) {
            "h264" -> MimeTypes.VIDEO_H264
            "hevc" -> MimeTypes.VIDEO_H265
            "vp9" -> MimeTypes.VIDEO_VP9
            "av1" -> MimeTypes.VIDEO_AV1
            "mpeg2video" -> MimeTypes.VIDEO_MPEG2
            else -> MimeTypes.VIDEO_UNKNOWN
        }
        C.TRACK_TYPE_AUDIO -> when (codec) {
            "aac" -> MimeTypes.AUDIO_AAC
            "mp3" -> MimeTypes.AUDIO_MPEG
            "ac3" -> MimeTypes.AUDIO_AC3
            "eac3" -> MimeTypes.AUDIO_E_AC3
            "truehd" -> MimeTypes.AUDIO_TRUEHD
            "dts" -> MimeTypes.AUDIO_DTS
            "opus" -> MimeTypes.AUDIO_OPUS
            "vorbis" -> MimeTypes.AUDIO_VORBIS
            "flac" -> MimeTypes.AUDIO_FLAC
            else -> MimeTypes.AUDIO_UNKNOWN
        }
        else -> when (codec) {
            "subrip" -> MimeTypes.APPLICATION_SUBRIP
            "ass", "ssa" -> MimeTypes.TEXT_SSA
            "webvtt" -> MimeTypes.TEXT_VTT
            "hdmv_pgs_subtitle" -> MimeTypes.APPLICATION_PGS
            "dvd_subtitle" -> MimeTypes.APPLICATION_VOBSUB
            "dvb_subtitle" -> MimeTypes.APPLICATION_DVBSUBS
            else -> MimeTypes.TEXT_UNKNOWN
        }
    }

    internal fun typeName(type: Int): String = when (type) {
        C.TRACK_TYPE_VIDEO -> "video"
        C.TRACK_TYPE_AUDIO -> "audio"
        else -> "sub"
    }
}
