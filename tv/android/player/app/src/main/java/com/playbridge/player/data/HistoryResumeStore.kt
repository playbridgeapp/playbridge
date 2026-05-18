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
        // HistoryStore.saveProgress requires title/duration which we don't have here.
        // For the resume-store interface we only need position; we store a minimal
        // entry with empty title and zero duration so that the next full save
        // from the Activity (with thumbnail, title, etc.) overwrites it cleanly.
        historyStore.saveProgress(
            url = url,
            title = null,
            position = positionMs,
            duration = 0L,
            contentType = null,
            headers = null,
        )
    }
}
