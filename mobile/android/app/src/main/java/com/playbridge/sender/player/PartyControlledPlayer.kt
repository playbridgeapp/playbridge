package com.playbridge.sender.player

import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.Player
import com.playbridge.sender.together.PhoneTogether

/**
 * The player UI's view of a Watch Together video: play, pause and seek go to the whole
 * party and come back through [PlayerActivity]'s party commands, which drive the real
 * player. Outside a party (the room refused), they fall through to the player.
 */
internal class PartyControlledPlayer(player: Player) : ForwardingPlayer(player) {
    override fun play() {
        if (!PhoneTogether.localPlay()) super.play()
    }

    override fun pause() {
        if (!PhoneTogether.localPause()) super.pause()
    }

    override fun setPlayWhenReady(playWhenReady: Boolean) {
        if (playWhenReady) play() else pause()
    }

    override fun seekTo(positionMs: Long) {
        if (!PhoneTogether.localSeek(positionMs)) super.seekTo(positionMs)
    }

    override fun seekTo(mediaItemIndex: Int, positionMs: Long) = seekTo(positionMs)

    override fun seekToDefaultPosition() = seekTo(0L)

    override fun seekBack() = seekTo(currentPosition - seekBackIncrement)

    override fun seekForward() = seekTo(currentPosition + seekForwardIncrement)
}

/** The player behind a party wrapper, for code that needs the real ExoPlayer or mpv. */
internal fun Player.unwrapped(): Player = (this as? ForwardingPlayer)?.wrappedPlayer ?: this
