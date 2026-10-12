package com.playbridge.sender.cast.remote.ui

import com.playbridge.sender.cast.remote.RemoteQueueItem
import com.playbridge.sender.cast.remote.RemoteUiState
import com.playbridge.sender.cast.remote.Support

internal data class RemoteQueueMutations(val remove: Boolean, val playNext: Boolean)

internal fun resolveQueueMutations(state: RemoteUiState, item: RemoteQueueItem): RemoteQueueMutations {
    val identified = item.itemId != null
    val queue = state.queue
    val current = if (queue?.currentItemId != null) item.itemId == queue.currentItemId else item.index == queue?.currentIndex
    return RemoteQueueMutations(
        remove = identified && !current && state.capabilities.queue.remove == Support.Supported,
        playNext = identified && !current && state.capabilities.queue.move == Support.Supported,
    )
}
