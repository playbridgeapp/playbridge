package com.playbridge.player.data

import com.playbridge.shared.resume.ResumeStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * Android-specific [ResumeStore] implementation backed by [HistoryStore].
 *
 * Keeps the Android-specific [DataStore] dependency out of `:shared` by living
 * inside `:tv:player:app` and being injected into [PlayerViewModel] at
 * Activity construction time.
 */
class HistoryResumeStore(private val historyStore: HistoryStore) : ResumeStore {

    override suspend fun loadPosition(url: String): Long {
        return historyStore.history
            .map { items -> items.find { it.url == url }?.position ?: 0L }
            .first()
    }

    override suspend fun savePosition(url: String, positionMs: Long) {
        // The resume-store interface only carries a position. Store a minimal one-item
        // payload keyed on the URL so the next full save from the Activity (with the real
        // payload, thumbnail, title, etc.) overwrites it cleanly.
        val payloadJson = com.playbridge.shared.protocol.encodePlaylistPayloadJson(
            playbridge.PlaylistPayload(items = listOf(playbridge.PlayPayload(url = url)))
        )
        historyStore.saveProgress(
            id = url,
            payloadJson = payloadJson,
            url = url,
            title = null,
            position = positionMs,
            duration = 0L,
        )
    }
}
