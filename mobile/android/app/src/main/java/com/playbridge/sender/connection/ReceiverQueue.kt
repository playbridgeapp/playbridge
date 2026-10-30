package com.playbridge.sender.connection

/** Snapshot of the receiver playlist_status message, independent of its source. */
data class ReceiverQueueState(
    val currentIndex: Int = 0,
    val totalCount: Int = 0,
    val items: List<ReceiverQueueItem> = emptyList(),
    val playbackId: String? = null,
    val queueRevision: Long = 0,
    val currentItemId: String? = null,
)

/** Optional sender metadata is echoed by playlist_status for remote queue presentation. */
data class ReceiverQueueItem(
    val index: Int,
    val title: String,
    val season: Int? = null,
    val episode: Int? = null,
    val imdbId: String? = null,
    val bingeGroup: String? = null,
    val mediaKind: String = "video",
    val itemId: String? = null,
    val tmdbId: String? = null,
)
