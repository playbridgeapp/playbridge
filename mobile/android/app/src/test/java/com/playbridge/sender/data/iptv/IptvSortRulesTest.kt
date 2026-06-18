package com.playbridge.sender.data.iptv

import org.junit.Assert.assertEquals
import org.junit.Test

class IptvSortRulesTest {

    private fun playlist(id: Long, name: String, addedAt: Long) =
        IptvPlaylistEntity(id = id, name = name, source = "s", sourceType = IptvSourceType.URL, addedAt = addedAt, updatedAt = addedAt)

    private fun channel(
        id: Long,
        order: Int,
        status: String = IptvProbeStatus.UNKNOWN,
        latency: Int? = null,
    ) = IptvChannelEntity(
        id = id, playlistId = 1, name = "ch$id", url = "u$id",
        orderIndex = order, probeStatus = status, probeLatencyMs = latency,
    )

    // ── Playlist sorting ────────────────────────────────────────────────────

    @Test
    fun sortPlaylistsByAddedDateDescending() {
        val list = listOf(
            playlist(1, "B", 100),
            playlist(2, "A", 300),
            playlist(3, "C", 200),
        )
        val result = IptvSortRules.sortPlaylists(list, IptvPlaylistSort.ADDED_DATE, ascending = false)
        assertEquals(listOf(2L, 3L, 1L), result.map { it.id })
    }

    @Test
    fun sortPlaylistsByNameAscendingCaseInsensitive() {
        val list = listOf(
            playlist(1, "banana", 1),
            playlist(2, "Apple", 2),
            playlist(3, "cherry", 3),
        )
        val result = IptvSortRules.sortPlaylists(list, IptvPlaylistSort.NAME, ascending = true)
        assertEquals(listOf(2L, 1L, 3L), result.map { it.id })
    }

    // ── Channel sorting ─────────────────────────────────────────────────────

    @Test
    fun activeFirstFloatsLiveChannelsUpAndDeadDown() {
        val channels = listOf(
            channel(1, order = 0, status = IptvProbeStatus.DEAD),
            channel(2, order = 1, status = IptvProbeStatus.ACTIVE, latency = 200),
            channel(3, order = 2, status = IptvProbeStatus.UNKNOWN),
            channel(4, order = 3, status = IptvProbeStatus.ACTIVE, latency = 50),
        )
        val result = IptvSortRules.sortChannels(channels, activeFirst = true)
        // ACTIVE (faster first) → UNKNOWN → DEAD
        assertEquals(listOf(4L, 2L, 3L, 1L), result.map { it.id })
    }

    @Test
    fun activeFirstDisabledKeepsOriginalOrder() {
        val channels = listOf(
            channel(3, order = 2, status = IptvProbeStatus.ACTIVE, latency = 10),
            channel(1, order = 0, status = IptvProbeStatus.DEAD),
            channel(2, order = 1, status = IptvProbeStatus.UNKNOWN),
        )
        val result = IptvSortRules.sortChannels(channels, activeFirst = false)
        assertEquals(listOf(1L, 2L, 3L), result.map { it.id })
    }

    @Test
    fun headerRoundTrip() {
        val headers = mapOf("User-Agent" to "Agent/1.0", "Referer" to "http://ref")
        val encoded = encodeHeaders(headers)
        assertEquals(headers, decodeHeaders(encoded))
    }

    @Test
    fun emptyHeadersEncodeToNull() {
        assertEquals(null, encodeHeaders(emptyMap()))
        assertEquals(emptyMap<String, String>(), decodeHeaders(null))
    }
}
