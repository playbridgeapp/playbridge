package com.playbridge.sender.cast

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * A global channel for relaying media control actions (Play, Pause, Stop)
 * from the MediaPlaybackService (Notification) to the active GeckoSession.
 */
object MediaControlChannel {
    private val _actions = MutableSharedFlow<Action>(extraBufferCapacity = 10)
    val actions = _actions.asSharedFlow()

    enum class Action {
        PLAY,
        PAUSE,
        STOP
    }

    suspend fun emit(action: Action) {
        _actions.emit(action)
    }

    fun tryEmit(action: Action) {
        _actions.tryEmit(action)
    }
}
