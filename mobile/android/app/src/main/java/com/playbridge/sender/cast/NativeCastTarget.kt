package com.playbridge.sender.cast

import com.playbridge.sender.connection.ConnectionCoordinator
import com.playbridge.sender.connection.WebSocketClient
import com.playbridge.shared.protocol.createControlCommandJson
import com.playbridge.shared.protocol.createSingleVideoCommandJson
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import playbridge.PlayPayload

/**
 * [CastTarget] backed by the native PlayBridge receiver over the WebSocket session.
 * Full capability set; status is pushed by the TV (relayed through
 * [ConnectionCoordinator.tvPlayback]) rather than polled.
 *
 * Note: the rich library/browser send paths (subtitle bundling, playback-pref
 * decoration, playlists) still build their own PlayPayloads — [load] is the plain
 * transport-agnostic entry point used by [CastSessionManager] and generic callers.
 */
class NativeCastTarget(
    override val id: String,
    override val name: String,
    private val webSocketClient: WebSocketClient,
    private val coordinator: ConnectionCoordinator,
) : CastTarget {

    override val kind = TargetKind.NATIVE

    override val capabilities = setOf(
        Capability.LOAD,
        Capability.PLAY_PAUSE,
        Capability.SEEK,
        Capability.STOP,
        Capability.NOW_PLAYING,
        Capability.REMOTE,
        Capability.BROWSER,
        Capability.QUEUE,
        Capability.SUBTITLES,
    )

    override suspend fun load(media: MediaItem) {
        webSocketClient.send(
            createSingleVideoCommandJson(
                PlayPayload(
                    url = media.url,
                    title = media.title,
                    headers = media.headers,
                    content_type = media.mimeType,
                    subtitles = media.subtitles.map { it.url },
                    start_position_ms = media.startPositionMs.takeIf { it > 0 },
                    visual_metadata = media.visualMetadata,
                ),
            ),
        )
        coordinator.tvActiveContext.value = "player"
    }

    override suspend fun play() {
        webSocketClient.send(createControlCommandJson("play"))
    }

    override suspend fun pause() {
        webSocketClient.send(createControlCommandJson("pause"))
    }

    override suspend fun stop() {
        webSocketClient.send(createControlCommandJson("stop"))
        coordinator.tvActiveContext.value = "idle"
    }

    override suspend fun seekTo(positionMs: Long) {
        webSocketClient.send(createControlCommandJson("seek_to:$positionMs"))
    }

    /** The receiver exposes audio boost, not absolute volume — not wired here. */
    override suspend fun setVolume(percent: Int) = Unit

    override fun status(): Flow<PlaybackStatus> = coordinator.tvPlayback.map { pb ->
        PlaybackStatus(
            state = when (pb?.state) {
                "playing" -> PlaybackState.PLAYING
                "paused" -> PlaybackState.PAUSED
                "buffering" -> PlaybackState.BUFFERING
                else -> PlaybackState.IDLE
            },
            positionMs = pb?.positionMs ?: 0L,
            durationMs = pb?.durationMs ?: 0L,
        )
    }

    /** Nothing to free — the WS session is owned by [WebSocketClient]. */
    override fun release() = Unit
}
