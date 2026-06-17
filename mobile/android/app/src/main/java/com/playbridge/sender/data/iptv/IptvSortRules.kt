package com.playbridge.sender.data.iptv

/** How the playlist list is ordered. Persisted in settings. */
enum class IptvPlaylistSort { ADDED_DATE, NAME }

/** Pure, unit-tested ordering rules for IPTV playlists and channels. */
object IptvSortRules {

    /** Sort playlists by the chosen key + direction. */
    fun sortPlaylists(
        playlists: List<IptvPlaylistEntity>,
        sort: IptvPlaylistSort,
        ascending: Boolean,
    ): List<IptvPlaylistEntity> {
        val base = when (sort) {
            IptvPlaylistSort.ADDED_DATE -> playlists.sortedBy { it.addedAt }
            IptvPlaylistSort.NAME -> playlists.sortedBy { it.name.lowercase() }
        }
        return if (ascending) base else base.reversed()
    }

    /**
     * Rank a channel for "active first" ordering: lower sorts earlier.
     * ACTIVE (0) → UNKNOWN (1) → DEAD (2). Within ACTIVE, faster probes win.
     */
    fun channelRank(channel: IptvChannelEntity): Int = when (channel.probeStatus) {
        IptvProbeStatus.ACTIVE -> 0
        IptvProbeStatus.DEAD -> 2
        else -> 1
    }

    /**
     * Order channels so live (ACTIVE) ones float to the top, faster first; DEAD sink to the
     * bottom; original order is preserved as the final tiebreak. When [activeFirst] is false,
     * channels keep their original playlist order.
     */
    fun sortChannels(
        channels: List<IptvChannelEntity>,
        activeFirst: Boolean,
    ): List<IptvChannelEntity> {
        if (!activeFirst) return channels.sortedBy { it.orderIndex }
        return channels.sortedWith(
            compareBy(
                { channelRank(it) },
                { it.probeLatencyMs ?: Int.MAX_VALUE },
                { it.orderIndex },
            ),
        )
    }
}
