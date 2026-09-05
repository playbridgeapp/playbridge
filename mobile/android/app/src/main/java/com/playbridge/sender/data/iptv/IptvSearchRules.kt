package com.playbridge.sender.data.iptv

internal object IptvSearchRules {
    fun filter(channels: List<IptvChannelEntity>, query: String): List<IptvChannelEntity> =
        if (query.isBlank()) channels
        else channels.filter { it.name.contains(query, ignoreCase = true) }
}
