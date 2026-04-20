package com.playbridge.shared.stremio

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import okio.FileSystem
import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class StremioClientTest {
    private lateinit var fs: FakeFileSystem
    private val json = Json { ignoreUnknownKeys = true; isLenient = true; encodeDefaults = true }

    @BeforeTest
    fun setup() {
        fs = FakeFileSystem()
    }

    @Test
    fun `resolveStreamsByContentId returns scored streams from addon`() = runTest {
        val mockEngine = MockEngine { request ->
            respond(
                content = """
                    {
                        "streams": [
                            {
                                "url": "http://example.com/video.mkv",
                                "name": "Example Addon\n1080p",
                                "title": "Example Video"
                            }
                        ]
                    }
                """.trimIndent(),
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            )
        }

        val http = HttpClient(mockEngine) {
            install(ContentNegotiation) {
                json(json)
            }
        }

        val client = StremioClient(
            http = http,
            fs = fs,
            cacheFile = "/cache/stremio_cache.json".toPath(),
            json = json
        )

        val results = client.resolveStreamsByContentId(
            addonBaseUrls = listOf("http://addon.example.com"),
            contentId = "tt1234567",
            contentType = "movie"
        )

        assertEquals(1, results.size)
        assertEquals("http://example.com/video.mkv", results[0].url)
        assertEquals(3, results[0].rank) // 1080p should be rank 3
    }

    @Test
    fun `resolveEpisode handles addon resolution with quality preference`() = runTest {
        val mockEngine = MockEngine { request ->
            respond(
                content = """
                    {
                        "streams": [
                            {
                                "url": "http://example.com/720p.mkv",
                                "name": "720p stream",
                                "title": "S01E01"
                            },
                            {
                                "url": "http://example.com/1080p.mkv",
                                "name": "1080p stream",
                                "title": "S01E01"
                            }
                        ]
                    }
                """.trimIndent(),
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            )
        }

        val http = HttpClient(mockEngine) {
            install(ContentNegotiation) {
                json(json)
            }
        }

        val client = StremioClient(
            http = http,
            fs = fs,
            cacheFile = "/cache/stremio_cache.json".toPath(),
            json = json
        )

        val result = client.resolveEpisode(
            addonBaseUrls = listOf("http://addon.example.com"),
            imdbId = "tt1234567",
            season = 1,
            episode = 1,
            qualityPref = "1080p"
        )

        assertNotNull(result)
        assertEquals("http://example.com/1080p.mkv", result.url)
    }
}
