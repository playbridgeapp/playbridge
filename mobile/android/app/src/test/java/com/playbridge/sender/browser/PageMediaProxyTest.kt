package com.playbridge.sender.browser

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import playbridge.PlayPayload
import playbridge.SubtitleResource

class PageMediaProxyTest {
    private class Registration(
        val host: String,
        val url: String,
        val headers: Map<String, String>,
        val contentType: String?,
        val origins: List<String>,
    )

    private class FakeRegistrar(private val failOn: String? = null) : PageMediaRegistrar {
        val registrations = mutableListOf<Registration>()
        val released = mutableListOf<String>()

        override suspend fun register(
            host: String,
            url: String,
            headers: Map<String, String>,
            contentType: String?,
            allowedPrivateOrigins: List<String>,
        ): String {
            if (url == failOn) throw IllegalStateException("refused")
            registrations += Registration(host, url, headers, contentType, allowedPrivateOrigins)
            return "http://$host:9000/s/id${registrations.size}/media"
        }

        override fun retain(proxyUrl: String): AutoCloseable = AutoCloseable { released += proxyUrl }
    }

    private val item = LinkedPageCastItem(
        id = "episode-1",
        payload = PlayPayload(
            url = "http://192.168.1.20:8080/live.m3u8",
            title = "Episode",
            headers = mapOf("Referer" to "https://website.example/"),
            content_type = "application/vnd.apple.mpegurl",
            subtitles = listOf("https://cdn.example/en.vtt"),
            subtitle_resources = listOf(
                SubtitleResource(
                    url = "https://cdn.example/fr.vtt",
                    headers = mapOf("Authorization" to "Bearer token"),
                    label = "Français",
                ),
            ),
            detected_by = "linked_page",
            allowed_private_origins = listOf("http://192.168.1.20:8080"),
        ),
    )

    @Test
    fun `routes media and subtitles through the proxy with the approved origins`() = runBlocking {
        val registrar = FakeRegistrar()
        val routed = PageMediaProxy.route(item, PageMediaProxy.LOCAL_HOST, PageMediaLeases(), registrar)

        assertEquals(3, registrar.registrations.size)
        registrar.registrations.forEach {
            assertEquals("127.0.0.1", it.host)
            assertEquals(listOf("http://192.168.1.20:8080"), it.origins)
        }
        val (video, plain, credentialed) = registrar.registrations
        assertEquals(item.payload.url, video.url)
        assertEquals(mapOf("Referer" to "https://website.example/"), video.headers)
        assertEquals("application/vnd.apple.mpegurl", video.contentType)
        assertEquals(emptyMap<String, String>(), plain.headers)
        assertEquals(mapOf("Authorization" to "Bearer token"), credentialed.headers)

        // The player only ever sees proxy URLs, and sends no website credentials itself.
        assertEquals("http://127.0.0.1:9000/s/id1/media", routed.payload.url)
        assertTrue(routed.payload.headers.isEmpty())
        assertEquals(listOf("http://127.0.0.1:9000/s/id2/media"), routed.payload.subtitles)
        val resource = routed.payload.subtitle_resources.single()
        assertEquals("http://127.0.0.1:9000/s/id3/media", resource.url)
        assertTrue(resource.headers.isEmpty())
        assertEquals("Français", resource.label)
        assertEquals("episode-1", routed.id)
    }

    @Test
    fun `a refused registration fails instead of falling back to the website URL`() {
        val registrar = FakeRegistrar(failOn = "https://cdn.example/fr.vtt")
        val leases = PageMediaLeases()
        assertThrows(IllegalStateException::class.java) {
            runBlocking { PageMediaProxy.route(item, PageMediaProxy.LOCAL_HOST, leases, registrar) }
        }
        leases.close()
        assertEquals(2, registrar.released.size)
    }

    @Test
    fun `closing the session releases every lease, including ones added afterwards`() = runBlocking {
        val registrar = FakeRegistrar()
        val leases = PageMediaLeases()
        PageMediaProxy.route(item, "192.168.1.5", leases, registrar)
        leases.close()
        assertEquals(3, registrar.released.size)

        PageMediaProxy.route(item, "192.168.1.5", leases, registrar)
        assertEquals(6, registrar.released.size)
    }
}
