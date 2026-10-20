package com.playbridge.player.player

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class SkipSegmentFetcherTest {
    @Test fun v4MovieAndEpisodeQueriesUseExistingIds() {
        val movie = SkipSegmentFetcher.theIntroDbMediaUrl("https://example.test", "tt0137523", "550", null, null)!!
        assertEquals("/v4/media", movie.encodedPath)
        assertEquals(setOf("tmdb_id"), movie.queryParameterNames)
        assertEquals("550", movie.queryParameter("tmdb_id"))

        val episode = SkipSegmentFetcher.theIntroDbMediaUrl("https://example.test", "tt0903747", null, 1, 2)!!
        assertEquals("/v4/media", episode.encodedPath)
        assertEquals("tt0903747", episode.queryParameter("imdb_id"))
        assertEquals("1", episode.queryParameter("season"))
        assertEquals("2", episode.queryParameter("episode"))
        assertFalse(episode.queryParameterNames.contains("mapping"))
    }

    @Test fun savedVersionedRootsKeepCustomPathsAndQueryValues() {
        for (suffix in listOf("", "/", "/v1", "/v2/", "/v3", "/v3///", "/v4/")) {
            val url = SkipSegmentFetcher.theIntroDbMediaUrl(" https://example.test/proxy$suffix ", null, "550", null, null)!!
            assertEquals("/proxy/v4/media", url.encodedPath)
        }
        val custom = SkipSegmentFetcher.theIntroDbMediaUrl(
            "https://example.test/proxy%2Froute/v3/?key=a%26b&tvdb_id=123&season=8&episode=9#fragment",
            "tt0137523", null, null, null,
        )!!
        assertEquals("/proxy%2Froute/v4/media", custom.encodedPath)
        assertEquals("a&b", custom.queryParameter("key"))
        assertEquals("tt0137523", custom.queryParameter("imdb_id"))
        assertNull(custom.queryParameter("tvdb_id"))
        assertNull(custom.queryParameter("season"))
        assertNull(custom.queryParameter("episode"))
        assertNull(custom.fragment)
        val blank = SkipSegmentFetcher.theIntroDbMediaUrl("  ", null, "550", null, null)!!
        assertEquals("https://api.theintrodb.org/v4/media?tmdb_id=550", blank.toString())
    }

    @Test fun queryGuardsAndEncodingDoNotTurnEpisodesIntoMovies() {
        assertNull(SkipSegmentFetcher.theIntroDbMediaUrl("https://example.test", null, null, null, null))
        assertNull(SkipSegmentFetcher.theIntroDbMediaUrl("https://example.test", null, "550", 1, null))
        assertNull(SkipSegmentFetcher.theIntroDbMediaUrl("https://example.test", null, "550", null, 1))
        assertNull(SkipSegmentFetcher.theIntroDbMediaUrl("not a URL", null, "550", null, null))
        val special = SkipSegmentFetcher.theIntroDbMediaUrl("https://example.test", null, "1396", 0, 0)!!
        assertEquals("0", special.queryParameter("season"))
        assertEquals("0", special.queryParameter("episode"))
        val encoded = SkipSegmentFetcher.theIntroDbMediaUrl("https://example.test", "tt1&mapping=dvd", null, 1, 1)!!
        assertEquals("tt1&mapping=dvd", encoded.queryParameter("imdb_id"))
        assertNull(encoded.queryParameter("mapping"))
    }

    @Test fun v4RangesNeedNoTmdbResponseField() {
        val segments = SkipSegmentFetcher.parseTheIntroDbSegments(JSONObject("""
            {"tvdb_id":81189,"type":"tv","season":1,"episode":1,
             "intro":[{"start_ms":null,"end_ms":23000},{"start_ms":30000,"end_ms":90000}],
             "recap":[{"start_ms":null,"end_ms":12000}],
             "credits":[{"start_ms":5801777,"end_ms":6371111},{"start_ms":6408000,"end_ms":null}],
             "preview":[{"start_ms":1680000,"end_ms":1740000}]}
        """.trimIndent()))
        assertEquals(listOf(
            SkipSegment("intro", 0, 23000), SkipSegment("intro", 30000, 90000),
            SkipSegment("recap", 0, 12000), SkipSegment("outro", 5801777, 6371111),
            SkipSegment("outro", 6408000, SkipSegmentFetcher.OPEN_ENDED_MS),
            SkipSegment("preview", 1680000, 1740000),
        ), segments)
    }

    @Test fun invalidAndZeroLengthRangesAreIgnored() {
        val segments = SkipSegmentFetcher.parseTheIntroDbSegments(JSONObject("""
            {"tvdb_id":247,"type":"movie",
             "intro":[{"start_ms":null,"end_ms":0},{"start_ms":0},{"end_ms":2},
                      {"start_ms":-1,"end_ms":2},{"start_ms":2,"end_ms":1}],
             "outro":{},
             "credits":[{"start_ms":null,"end_ms":5},{"start_ms":90000,"end_ms":null}]}
        """.trimIndent()))
        assertEquals(listOf(SkipSegment("outro", 90000, SkipSegmentFetcher.OPEN_ENDED_MS)), segments)
        assertEquals(emptyList<SkipSegment>(), SkipSegmentFetcher.parseTheIntroDbSegments(JSONObject("""
            {"tvdb_id":81189,"type":"tv","episodes":[{"season":1,"episode":1}]}
        """.trimIndent())))
    }

    @Test
    fun userAgentCarriesTheRealVersionAndAContactUrl() {
        assertEquals(
            "PlayBridgeTV/${com.playbridge.player.BuildConfig.VERSION_NAME} (+https://github.com/playbridgeapp/playbridge)",
            SkipSegmentFetcher.USER_AGENT,
        )
    }
}
