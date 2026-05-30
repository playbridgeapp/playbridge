package com.playbridge.player.player

import playbridge.PlayPayload

/**
 * Single owner of playlist/queue navigation for the TV receiver, shared by both
 * engine Activities (Exo + MPV) so the fragile sync logic lives in one place
 * instead of being duplicated per engine.
 *
 * The coordinator owns the queue ([items]) and cursor ([index]) and makes all the
 * navigation *decisions* (advance / retreat / jump / queue-append / end-of-stream).
 * It delegates everything engine- or Android-specific — actually loading a stream,
 * saving progress, broadcasting status, toasts, finishing — to a [Host] so the
 * decision logic stays pure and unit-testable.
 *
 * A single video is modelled as an empty playlist; navigation UI only applies once
 * [hasPlaylist] is true (size > 1), matching the receiver's existing convention.
 */
class PlaybackCoordinator(private val host: Host) {

    interface Host {
        /** Load and start [item] in the underlying engine. [displayTitle] carries the "(n/m)" suffix. */
        fun loadItem(item: PlayPayload, displayTitle: String?)

        /**
         * Persist progress for the current item before navigating away. [captureThumbnail]
         * mirrors the existing behaviour (next/previous capture a frame; jump does not).
         * Implementations guard on engine state (only save if playback actually started).
         */
        suspend fun saveProgressBeforeAdvance(captureThumbnail: Boolean)

        /** The queue contents/cursor changed — re-broadcast `playlist_status` and refresh controls. */
        fun onPlaylistChanged(items: List<PlayPayload>, index: Int)

        /** Show a brief user-facing message (e.g. "Already on first episode"). */
        fun showMessage(message: String)

        /** No more items to advance to — end the session. */
        fun onPlaylistFinished()
    }

    private val items = mutableListOf<PlayPayload>()
    private var cursor = 0

    val playlist: List<PlayPayload> get() = items
    val index: Int get() = cursor
    val hasPlaylist: Boolean get() = items.size > 1
    val isEmpty: Boolean get() = items.isEmpty()

    /** Replace the queue (e.g. on initial intent / M3U expansion). Does not load. */
    fun setPlaylist(newItems: List<PlayPayload>, startIndex: Int) {
        items.clear()
        items.addAll(newItems)
        cursor = startIndex.coerceIn(0, (items.size - 1).coerceAtLeast(0))
    }

    /** Append phone-driven `queue_add` items to the active queue. */
    fun queueAdd(newItems: List<PlayPayload>) {
        if (newItems.isEmpty()) return
        items.addAll(newItems)
        host.onPlaylistChanged(items, cursor)
    }

    /** Build the "(n/m)"-suffixed display title used in toasts and the now-playing surface. */
    fun displayTitle(item: PlayPayload, position: Int): String {
        val total = items.size
        val base = item.title
        return if (total > 1) {
            if (base != null) "$base (${position + 1}/$total)" else "Item ${position + 1}/$total"
        } else {
            base ?: ""
        }
    }

    /** Advance to the next item, or finish if at/over the end. */
    suspend fun next() {
        host.saveProgressBeforeAdvance(captureThumbnail = true)
        if (items.isEmpty() || cursor + 1 >= items.size) {
            host.onPlaylistFinished()
            return
        }
        cursor++
        val item = items[cursor]
        host.loadItem(item, displayTitle(item, cursor))
        host.onPlaylistChanged(items, cursor)
    }

    /** Go back to the previous item, no-op (with a message) at the start. */
    suspend fun previous() {
        if (items.isEmpty()) {
            host.showMessage("Already on first item")
            return
        }
        if (cursor <= 0) {
            host.showMessage("Already on first episode")
            return
        }
        host.saveProgressBeforeAdvance(captureThumbnail = true)
        cursor--
        val item = items[cursor]
        host.loadItem(item, displayTitle(item, cursor))
        host.onPlaylistChanged(items, cursor)
    }

    /** Jump to an explicit index (phone `playlist_jump` / on-TV panel selection). */
    suspend fun jumpTo(target: Int) {
        if (items.isEmpty() || target !in items.indices) return
        host.saveProgressBeforeAdvance(captureThumbnail = false)
        cursor = target
        val item = items[cursor]
        host.loadItem(item, displayTitle(item, cursor))
        host.onPlaylistChanged(items, cursor)
    }

    /** Mark the current item as failed in place (IPTV channel failover). */
    fun markCurrentFailed() {
        val item = items.getOrNull(cursor) ?: return
        val title = item.title ?: "Channel ${cursor + 1}"
        if (!title.startsWith("[FAILED]")) {
            items[cursor] = item.copy(title = "[FAILED] $title")
        }
    }
}
