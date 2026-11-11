package com.playbridge.sender.player

import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.Tracks
import androidx.media3.common.VideoSize
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.delay

/**
 * The player screen's side effects: following the player, polling the position, auto-hiding
 * the controls and transient indicators, the A-B loop, and ExoPlayer's stuck-audio recovery.
 */
@Composable
internal fun PlayerScreenEffects(state: PlayerScreenState, isInPip: Boolean, onEnded: () -> Unit) {
    val player = state.player

    // Precision AB Loop segment repeat — only while playing, 200ms cadence.
    LaunchedEffect(state.abStartMs, state.abEndMs, state.isPlaying) {
        val start = state.abStartMs
        val end = state.abEndMs
        if (start != null && end != null && state.isPlaying) {
            while (state.isPlaying) {
                if (player.currentPosition >= end) {
                    player.seekTo(start)
                }
                delay(200)
            }
        }
    }

    // The navigation bar shows with the controls and hides with them, so the gesture bar is
    // reachable whenever the controls are.
    val controlsShown = state.controlsVisible && !state.locked && state.errorMessage == null && !isInPip
    LaunchedEffect(controlsShown) {
        val w = state.window ?: return@LaunchedEffect
        val bars = WindowInsetsControllerCompat(w, w.decorView)
        if (controlsShown) bars.show(WindowInsetsCompat.Type.navigationBars())
        else bars.hide(WindowInsetsCompat.Type.navigationBars())
    }

    // Force hide controls in PiP mode
    LaunchedEffect(isInPip) {
        if (isInPip) {
            state.controlsVisible = false
        }
    }

    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onTimelineChanged(timeline: Timeline, reason: Int) {
                state.episodeTitles = state.readQueueTitles()
                state.currentIndex = player.currentMediaItemIndex
            }
            override fun onIsPlayingChanged(playing: Boolean) { state.isPlaying = playing }
            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                state.playWhenReady = playWhenReady
            }
            override fun onTracksChanged(tracks: Tracks) {
                state.currentTracks = tracks
            }
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                // Title + index follow the current playlist item (e.g. next episode on auto-advance).
                state.currentTitle = mediaItem?.mediaMetadata?.title?.toString() ?: state.initialTitle
                state.currentIndex = player.currentMediaItemIndex

                // On media item transition (seeking to previous/next or auto-advance), certain hardware
                // decoders fail to hand off the surface correctly, causing a black screen.
                // Re-preparing the player forces codec recreation and clean rendering.
                if (player.unwrapped() is ExoPlayer && (reason == Player.MEDIA_ITEM_TRANSITION_REASON_SEEK ||
                    reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO ||
                    reason == Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED)) {
                    player.prepare()
                }
            }
            override fun onPlaybackStateChanged(playbackState: Int) {
                state.isBuffering = playbackState == Player.STATE_BUFFERING
                if (playbackState == Player.STATE_READY) {
                    state.errorMessage = null
                    val d = player.duration
                    if (d > 0) state.playbackDuration.longValue = d
                }
                // End of the current item. In lazy mode advance to (and resolve) the next
                // episode; otherwise this is the end of all content, so exit.
                if (playbackState == Player.STATE_ENDED) {
                    onEnded()
                }
            }
            override fun onPlayerError(error: PlaybackException) {
                state.errorMessage = error.message ?: "Playback error"
                state.isBuffering = false
            }
            override fun onVideoSizeChanged(videoSize: VideoSize) {
                if (videoSize.width <= 0 || videoSize.height <= 0) return
                // Some containers deliver a rotated frame + rotation metadata.
                @Suppress("DEPRECATION")
                val swapped = videoSize.unappliedRotationDegrees % 180 != 0
                val width = videoSize.width * videoSize.pixelWidthHeightRatio
                val height = videoSize.height.toFloat()
                state.videoAspect = if (swapped) height / width else width / height
            }
            override fun onRenderedFirstFrame() {
                state.hasRenderedFrame = true
                state.isBuffering = false
            }
        }
        player.addListener(listener)
        state.episodeTitles = state.readQueueTitles()
        state.currentIndex = player.currentMediaItemIndex
        onDispose { player.removeListener(listener) }
    }

    // Safety net after first frame: reinit only. Do NOT drop audio when FFmpeg is
    // available — silent video was the bug FFmpeg fixed; muting would reintroduce it.
    // Drop-audio is a last resort only if the extension AAR failed to load.
    LaunchedEffect(player, state.hasRenderedFrame) {
        val exo = player.unwrapped() as? ExoPlayer ?: return@LaunchedEffect
        if (!state.hasRenderedFrame) return@LaunchedEffect
        delay(1_500)
        if (!isPlaybackStuck(exo, hasRenderedFrame = true)) return@LaunchedEffect
        Log.w(PB_PLAYER, "stuck BUFFERING after first frame — reinit")
        PhoneExoPlayerFactory.recoverAudio(exo, "reinit")
        delay(2_000)
        if (!isPlaybackStuck(exo, hasRenderedFrame = true)) return@LaunchedEffect
        if (PhoneExoPlayerFactory.isFfmpegAvailable()) {
            // FFmpeg path is the fix for no-audio; clear overrides and re-prepare once more.
            Log.w(PB_PLAYER, "still stuck with FFmpeg — clear-override (not mute)")
            PhoneExoPlayerFactory.recoverAudio(exo, "clear-override")
        } else {
            Log.w(PB_PLAYER, "still stuck, FFmpeg missing — drop audio last resort")
            PhoneExoPlayerFactory.recoverAudio(exo, "drop-audio")
        }
    }

    // Poll position/buffer while not actively scrubbing/seeking.
    LaunchedEffect(Unit) {
        while (true) {
            if (!state.isScrubbing && state.dragMode != DragMode.SEEK) {
                state.playbackProgress.longValue = player.currentPosition
                state.playbackBuffered.longValue = player.bufferedPosition
                val d = player.duration
                if (d > 0) state.playbackDuration.longValue = d
            }
            delay(400)
        }
    }

    // Auto-hide controls a few seconds after the last interaction while playing.
    LaunchedEffect(state.controlsVisible, state.isPlaying, state.interactionTick, state.dragMode) {
        if (state.controlsVisible && state.isPlaying && state.dragMode == DragMode.NONE) {
            delay(3500)
            state.controlsVisible = false
        }
    }

    // Clear the double-tap skip indicator shortly after the last tap.
    LaunchedEffect(state.doubleTapSide, state.doubleTapSeconds) {
        if (state.doubleTapSide != 0) {
            delay(800)
            state.doubleTapSide = 0
            state.doubleTapSeconds = 0
        }
    }

    // Auto-hide the unlock affordance shown while locked.
    LaunchedEffect(state.showUnlock) {
        if (state.showUnlock) {
            delay(2500)
            state.showUnlock = false
        }
    }

    // Auto-clear the status message.
    LaunchedEffect(state.statusMessage) {
        if (state.statusMessage != null) {
            delay(1500)
            state.statusMessage = null
        }
    }
}
