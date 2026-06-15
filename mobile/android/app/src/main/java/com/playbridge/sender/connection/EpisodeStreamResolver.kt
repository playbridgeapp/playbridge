package com.playbridge.sender.connection

import com.playbridge.sender.cast.QualityFilter
import com.playbridge.sender.cast.StreamSelector
import com.playbridge.sender.data.library.AddonRepository
import com.playbridge.sender.player.AutoPickPrefs

/**
 * Shared "pick the best stream for episode N of this plan" logic, used by both queue
 * coordinators: [TvQueueCoordinator] (native receiver — TV-side `queue_add`) and
 * [DlnaQueueCoordinator] (phone-driven advance on DLNA renderers).
 *
 * Prefers a stream in the plan's Stremio `bingeGroup` (consistent quality/source across
 * episodes), then falls back to the user's auto-pick preferences, then to anything.
 */
internal object EpisodeStreamResolver {

    suspend fun resolveBest(
        addonRepository: AddonRepository,
        plan: TvEpisodeQueuePlan,
        index: Int,
        autoPick: AutoPickPrefs,
    ): String? {
        val streams = runCatching {
            addonRepository.resolveStreamsOnce(plan.streamType, plan.items[index].streamId, plan.forcedSource)
        }.getOrNull() ?: return null

        val best = StreamSelector.matchBingeGroup(streams, plan.bingeGroup)
            ?: StreamSelector.selectBest(
                streams = streams,
                preferredQuality = QualityFilter.fromKey(autoPick.qualityKey) ?: QualityFilter.ALL,
                maxMbps = autoPick.maxMbps,
                runtimeMinutes = 45,
                preferredAddon = autoPick.addonKey.takeIf { it.isNotEmpty() },
                preferredSourceTypes = autoPick.sourceTypes,
            )
            ?: streams.firstOrNull()
        return best?.stream?.url
    }
}
