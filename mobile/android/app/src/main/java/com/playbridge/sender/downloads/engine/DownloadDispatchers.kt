package com.playbridge.sender.downloads.engine

import kotlinx.coroutines.Dispatchers

/**
 * Shared bounded dispatcher for segment/ranged fetching (process-wide cap of 8).
 * Per-download `limitedParallelism` pools contended on the shared OkHttp pool;
 * one shared dispatcher bounds total concurrency across simultaneous downloads.
 */
internal object DownloadDispatchers {
    val segments = Dispatchers.IO.limitedParallelism(8)
}
