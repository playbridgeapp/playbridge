package com.playbridge.sender.cast.remote

import kotlinx.coroutines.flow.StateFlow

interface RemoteController {
    val sessionId: RemoteSessionId
    val snapshot: StateFlow<RemoteReceiverSnapshot>
    suspend fun execute(action: RemoteAction): RemoteCommandResult
    /** The intent's media identity must survive asynchronous state projection. */
    suspend fun execute(action: RemoteAction, expectedMedia: RemoteMediaId?): RemoteCommandResult {
        if (snapshot.value.state.playback?.mediaId != expectedMedia) return RemoteCommandResult.Failed("Remote media changed")
        return execute(action)
    }
}
