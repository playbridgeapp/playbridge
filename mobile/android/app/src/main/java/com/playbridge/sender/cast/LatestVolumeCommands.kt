package com.playbridge.sender.cast

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

/** One call in flight; slow receivers get only the newest queued absolute intent. */
internal class LatestVolumeCommands<T>(scope: CoroutineScope, apply: suspend (T) -> Unit) {
    private val requests = Channel<T>(Channel.CONFLATED)
    init { scope.launch { for (request in requests) apply(request) } }
    fun submit(request: T) { requests.trySend(request) }
}
