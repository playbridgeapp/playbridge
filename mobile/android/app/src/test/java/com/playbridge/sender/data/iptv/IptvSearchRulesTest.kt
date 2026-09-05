package com.playbridge.sender.data.iptv

import org.junit.Assert.assertEquals
import org.junit.Test

class IptvSearchRulesTest {
    private fun channels(vararg names: String) = names.mapIndexed { index, name ->
        IptvChannelEntity(id = index.toLong(), playlistId = 1, name = name, url = "u$index", orderIndex = index)
    }

    @Test
    fun matchesUnicodeCaseInsensitivelyWithoutChangingOrder() {
        val items = channels("Évasion HD", "КИНО", "évasion SD", "News")
        assertEquals(listOf(items[0], items[2]), IptvSearchRules.filter(items, "évasion"))
        assertEquals(listOf(items[1]), IptvSearchRules.filter(items, "кино"))
    }

    @Test
    fun wildcardCharactersAreLiteralAndBlankQueryShowsEverything() {
        val items = channels("100% TV", "News_HD", "News")
        assertEquals(listOf(items[0]), IptvSearchRules.filter(items, "%"))
        assertEquals(listOf(items[1]), IptvSearchRules.filter(items, "_"))
        assertEquals(items, IptvSearchRules.filter(items, "  "))
        assertEquals(items, IptvSearchRules.filter(items, ""))
    }
}
