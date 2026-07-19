package com.playbridge.player.player

import android.content.Context
import android.content.Intent
import com.playbridge.player.data.PlaybackContext
import com.playbridge.player.data.toSafeLogString
import com.playbridge.player.logging.FileLogger
import com.playbridge.player.server.ServerService
import com.playbridge.shared.protocol.decodePlaylistPayloadJson
import com.playbridge.shared.protocol.encodePlaylistPayloadJson
import com.playbridge.shared.protocol.protocolJson
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import playbridge.PlayPayload
import playbridge.PlaylistPayload
import java.util.concurrent.atomic.AtomicLong

/**
 * Single source of truth for turning a [PlaylistPayload] into a player [Intent].
 *
 * Both the live-cast path ([ServerService] handling an incoming `playlist` command) and
 * the history-replay path ([com.playbridge.player.MainActivity] re-playing a stored item)
 * build the launch intent here, so a replay behaves byte-for-byte like a fresh cast —
 * subtitles, audio-language preference, headers, and visual metadata all come back for
 * free because they are derived from the same payload by the same code.
 */
object PlayerLauncher {

    private const val TAG = "PlayerLauncher"

    const val EXTRA_PLAYBACK_REQUEST_ID = "extra_playback_request_id"
    const val EXTRA_HISTORY_ID = "extra_history_id"
    private const val EXTRA_PLAYBACK_CONTEXT = "extra_playback_context"
    private const val REQUEST_ID_SEQUENCE_BITS = 16
    // Seed from wall time so an Activity restored after process recreation does not reject
    // new requests merely because the process-local counter restarted from zero.
    private val playbackRequestIds = AtomicLong(
        System.currentTimeMillis() shl REQUEST_ID_SEQUENCE_BITS
    )

    /**
     * Build the player launch intent for [payload].
     *
     * @param tvPlayerMode the TV's `player_mode` preference ("mpv"/"exo"/"phone"/unset);
     *   when "mpv"/"exo" it forces the engine, otherwise the payload's per-item
     *   `player_mode` wins (defaulting to ExoPlayer).
     * @param overrideStartPositionMs when non-null, takes priority over the payload's own
     *   `start_position_ms`. A positive value resumes there; zero explicitly starts from the
     *   beginning (used when replaying completed history).
     */
    fun buildPlayerIntent(
        context: Context,
        payload: PlaylistPayload,
        tvPlayerMode: String,
        overrideStartPositionMs: Long? = null,
        historyId: String? = null,
        playbackContext: PlaybackContext? = null,
    ): Intent {
        // The coordinator/engine reads the live queue from PlaylistStore — set it here so
        // both callers get identical queue setup.
        PlaylistStore.currentPlaylist = payload.items

        val firstItem = payload.items.getOrNull(payload.start_index) ?: payload.items.firstOrNull()

        val mode = when {
            tvPlayerMode == "mpv" || tvPlayerMode == "exo" -> tvPlayerMode
            firstItem?.player_mode == "mpv" -> "mpv"
            else -> "exo"
        }
        return Intent(context, PlayerHostActivity::class.java).apply {
            putExtra(EXTRA_PLAYBACK_REQUEST_ID, playbackRequestIds.incrementAndGet())
            historyId?.let { putExtra(EXTRA_HISTORY_ID, it) }
            playbackContext?.let {
                FileLogger.i(
                    TAG,
                    "Adding saved playback context to Library launch intent: " +
                        it.toSafeLogString(),
                )
                putExtra(EXTRA_PLAYBACK_CONTEXT, protocolJson.encodeToString(it))
            } ?: FileLogger.d(TAG, "Launch intent has no saved playback context")
            putExtra(PlayerHostActivity.EXTRA_RENDERER, mode)
            putExtra(ServerService.EXTRA_PLAYLIST, encodePlaylistPayloadJson(payload))
            firstItem?.let { item ->
                putExtra(ServerService.EXTRA_URL, item.url)
                putExtra(ServerService.EXTRA_TITLE, item.title)
                putExtra(ServerService.EXTRA_CONTENT_TYPE, item.content_type)
                item.detected_by?.let { putExtra(ServerService.EXTRA_DETECTED_BY, it) }
                if (item.subtitles.isNotEmpty()) {
                    putStringArrayListExtra(ServerService.EXTRA_SUBTITLES, ArrayList(item.subtitles))
                }
                if (item.headers.isNotEmpty()) {
                    putExtra(ServerService.EXTRA_HEADERS, HashMap(item.headers))
                }
                item.preferred_audio_language?.let {
                    putExtra(ServerService.EXTRA_PREFERRED_AUDIO_LANG, it)
                }
                item.preferred_subtitle_language?.let {
                    putExtra(ServerService.EXTRA_PREFERRED_SUBTITLE_LANG, it)
                }
                item.default_video_quality?.let {
                    putExtra("default_video_quality", it)
                }
                item.max_bitrate_cap_mbps?.let {
                    putExtra(ServerService.EXTRA_MAX_BITRATE_CAP_MBPS, it)
                }
            }
            putExtra(ServerService.EXTRA_IS_PLAYLIST, true)
            putExtra(ServerService.EXTRA_PLAYLIST_INDEX, payload.start_index)

            // Playlist-level metadata wins; fall back to the start item's (single videos
            // carry it on the item, not the playlist wrapper).
            (payload.visual_metadata ?: firstItem?.visual_metadata)?.let { visualMetadata ->
                putExtra(ServerService.EXTRA_VISUAL_METADATA, visualMetadata.encode())
            }

            // Resume point: a caller override (including an explicit zero) beats the payload's
            // own start_position_ms. Both engines honour a positive EXTRA_START_POSITION;
            // omitting it represents a deliberate start from zero.
            (if (overrideStartPositionMs != null) {
                overrideStartPositionMs.takeIf { it > 0 }
            } else {
                firstItem?.start_position_ms?.takeIf { it > 0 }
            })
                ?.let { putExtra(ServerService.EXTRA_START_POSITION, it) }

            addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP
            )
        }
    }

    /** Resolve a playlist across the main/MPV process boundary. */
    fun playlistFromIntent(intent: Intent?): PlaylistPayload? =
        intent?.getStringExtra(ServerService.EXTRA_PLAYLIST)
            ?.let(::decodePlaylistPayloadJson)

    fun playbackContextFromIntent(intent: Intent?): PlaybackContext? {
        val encoded = intent?.getStringExtra(EXTRA_PLAYBACK_CONTEXT)
        if (encoded == null) {
            FileLogger.d(TAG, "Playback request contains no saved playback context")
            return null
        }
        return runCatching { protocolJson.decodeFromString<PlaybackContext>(encoded) }
            .onSuccess { context ->
                FileLogger.i(
                    TAG,
                    "Decoded saved playback context from intent: ${context.toSafeLogString()}",
                )
            }
            .onFailure { error ->
                FileLogger.e(TAG, "Unable to decode saved playback context from intent", error)
            }
            .getOrNull()
    }

    /**
     * Encode the live queue into the canonical history blob. [items] are the original,
     * untouched per-episode [PlayPayload]s (so subtitles/headers/langs survive), [index]
     * is the current cursor.
     */
    fun historyPayloadJson(items: List<PlayPayload>, index: Int): String =
        encodePlaylistPayloadJson(PlaylistPayload(items = items, start_index = index))

    /**
     * Stable, index-independent history key so every episode of a series updates the same
     * entry instead of spawning one row per episode. Single items key on their URL.
     */
    fun historyId(items: List<PlayPayload>): String =
        items.asSequence()
            .mapNotNull(PlayPayload::binge_group)
            .firstOrNull(String::isNotBlank)
            ?.let { "playlist_${it.hashCode()}" }
            ?: if (items.size > 1) {
                "playlist_${items.joinToString("|") { it.url }.hashCode()}"
            } else {
                items.firstOrNull()?.url ?: "unknown"
            }
}
