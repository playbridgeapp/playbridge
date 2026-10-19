package com.playbridge.sender.cast

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Owner-addressed controls from the notification/MediaSession to one media source.
 * Capturing the owner at emission prevents a later tab switch from redirecting a command.
 */
object MediaControlChannel {
    private val _actions = MutableSharedFlow<Command>(extraBufferCapacity = 10)
    val actions = _actions.asSharedFlow()

    enum class Action {
        PLAY,
        PAUSE,
        STOP,
        SEEK_TO,
    }

    data class Command(val ownerId: String, val action: Action, val positionMs: Long? = null) {
        fun targets(ownerId: String): Boolean = this.ownerId == ownerId
    }

    fun tryEmit(command: Command): Boolean = _actions.tryEmit(command)
}

/** Drop stale button intents and absent owners instead of broadcasting or retargeting. */
internal fun notificationMediaControl(
    currentOwnerId: String?,
    requestedOwnerId: String? = null,
    action: MediaControlChannel.Action,
    positionMs: Long? = null,
): MediaControlChannel.Command? {
    val ownerId = currentOwnerId?.takeIf { it.isNotBlank() } ?: return null
    if (requestedOwnerId != null && requestedOwnerId != ownerId) return null
    if (action == MediaControlChannel.Action.SEEK_TO && (positionMs == null || positionMs < 0)) {
        return null
    }
    return MediaControlChannel.Command(ownerId, action, positionMs)
}
