package com.playbridge.player.player

import com.playbridge.player.proxy.RegisteredMedia
import com.playbridge.shared.network.MediaNetworkPolicy
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import playbridge.PlayPayload
import playbridge.SubtitleResource
import java.net.InetAddress

class PageMediaProxyRouterTest {

    private data class RecordedRegistration(
        val id: String,
        val url: String,
        val headers: Map<String, String>,
        val contentType: String?,
        val allowedPrivateOrigins: List<String>,
        val remoteOrigin: Boolean,
    )

    private class FakePageProxyClient(
        override var isSupported: Boolean = true,
        var port: Int = 43125,
        var startError: Throwable? = null,
        var failOnUrlPredicate: (String) -> Boolean = { false },
        var customUrlBuilder: ((String, String) -> String)? = null,
    ) : PageProxyClient {
        var startCalls: Int = 0
        var started: Boolean = false
        val registrations = mutableListOf<RecordedRegistration>()
        val activeLeases = linkedSetOf<String>()
        val closedLeases = mutableListOf<String>()
        private var nextId = 1

        override val trustedOrigin: String?
            get() = if (started && port > 0) "http://127.0.0.1:$port" else null

        override suspend fun ensureStarted(): String {
            startCalls++
            startError?.let { throw it }
            started = true
            return "http://127.0.0.1:$port"
        }

        override suspend fun registerUrl(
            url: String,
            headers: Map<String, String>,
            contentType: String?,
            allowedPrivateOrigins: List<String>,
            remoteOrigin: Boolean,
        ): RegisteredMedia {
            if (failOnUrlPredicate(url)) {
                throw IllegalStateException("Simulated proxy registration failure")
            }
            val id = "reg-${nextId++}"
            registrations += RecordedRegistration(
                id = id,
                url = url,
                headers = headers,
                contentType = contentType,
                allowedPrivateOrigins = allowedPrivateOrigins,
                remoteOrigin = remoteOrigin,
            )
            val fileName = url.substringBefore('?').substringAfterLast('/').ifBlank { "stream" }
            val proxyUrl = customUrlBuilder?.invoke(id, fileName)
                ?: "http://127.0.0.1:$port/s/$id/$fileName"
            return RegisteredMedia(id = id, url = proxyUrl)
        }

        override fun retainId(id: String): AutoCloseable? {
            if (!started) return null
            activeLeases += id
            return AutoCloseable {
                if (activeLeases.remove(id)) {
                    closedLeases += id
                }
            }
        }
    }

    @Test
    fun nonPageItemsPlayDirectlyWithoutStartingProxy() = runBlocking {
        val fakeProxy = FakePageProxyClient()
        val router = PageMediaProxyRouter(fakeProxy)
        val castSheetItem = PlayPayload(
            url = "https://cdn.example.com/live/master.m3u8",
            title = "Cast Sheet Stream",
            detected_by = "cast_sheet",
            headers = mapOf(
                "Referer" to "https://watch.example.com/show",
                "User-Agent" to "ExampleAgent/1.0",
            ),
            subtitles = listOf("https://cdn.example.com/subs/en.vtt#English"),
        )

        val prepared = router.prepareItem(castSheetItem).getOrThrow()

        assertEquals(0, fakeProxy.startCalls)
        assertTrue(fakeProxy.registrations.isEmpty())
        assertNull(prepared.proxyOrigin)
        assertSame(castSheetItem, prepared.enginePayload(RendererKind.EXO))
        assertSame(castSheetItem, prepared.enginePayload(RendererKind.MPV))

        val subTarget = router.resolveSubtitleFetch(
            "https://cdn.example.com/subs/en.vtt#English",
            castSheetItem.headers,
        )
        assertNotNull(subTarget)
        assertEquals("https://cdn.example.com/subs/en.vtt#English", subTarget!!.fetchUrl)
        assertEquals(castSheetItem.headers, subTarget.headers)
        assertNull(subTarget.trustedLocalProxyOrigin)
    }

    @Test
    fun pageCastAndLinkedPageItemsRegisterWithPolicyStripHeadersAndReleaseLeases() = runBlocking {
        val fakeProxy = FakePageProxyClient(port = 45210)
        val router = PageMediaProxyRouter(fakeProxy)
        val pageItem = PlayPayload(
            url = "http://10.0.0.42:8096/videos/10/master.m3u8",
            title = "Bridged Episode",
            detected_by = "page_cast",
            headers = mapOf(
                "Referer" to "https://portal.example.com/watch",
                "Origin" to "https://portal.example.com",
            ),
            allowed_private_origins = listOf("http://10.0.0.42:8096"),
            subtitles = listOf(
                "http://10.0.0.42:8096/videos/10/en.vtt#English",
                "https://subs.example.net/es.vtt#Spanish",
            ),
            subtitle_resources = listOf(
                SubtitleResource(
                    url = "https://captions.example.org/fr.srt",
                    label = "French",
                    language = "fr",
                    headers = mapOf("Referer" to "https://captions.example.org/"),
                ),
            ),
        )

        val prepared = router.prepareItem(pageItem).getOrThrow()
        assertEquals(1, fakeProxy.startCalls)
        assertEquals("http://127.0.0.1:45210", prepared.proxyOrigin)
        assertEquals(4, fakeProxy.registrations.size)
        assertEquals(setOf("reg-1", "reg-2", "reg-3", "reg-4"), fakeProxy.activeLeases)

        // Media registration preserves headers, private origins, and remoteOrigin=true.
        val mediaReg = fakeProxy.registrations[0]
        assertEquals("http://10.0.0.42:8096/videos/10/master.m3u8", mediaReg.url)
        assertEquals(pageItem.headers, mediaReg.headers)
        assertEquals(listOf("http://10.0.0.42:8096"), mediaReg.allowedPrivateOrigins)
        assertTrue(mediaReg.remoteOrigin)

        // Same-origin subtitle keeps item headers; fragment is stripped before registration.
        val sameOriginSub = fakeProxy.registrations[1]
        assertEquals("http://10.0.0.42:8096/videos/10/en.vtt", sameOriginSub.url)
        assertEquals(pageItem.headers, sameOriginSub.headers)
        assertEquals(listOf("http://10.0.0.42:8096"), sameOriginSub.allowedPrivateOrigins)

        // Cross-origin subtitle in `subtitles` does not receive origin-bound media headers.
        val crossOriginSub = fakeProxy.registrations[2]
        assertEquals("https://subs.example.net/es.vtt", crossOriginSub.url)
        assertTrue(crossOriginSub.headers.isEmpty())

        // `subtitle_resources` forwards per-resource headers.
        val resourceSub = fakeProxy.registrations[3]
        assertEquals("https://captions.example.org/fr.srt", resourceSub.url)
        assertEquals(mapOf("Referer" to "https://captions.example.org/"), resourceSub.headers)
        assertEquals(null, resourceSub.contentType)

        // Engine payload points to loopback, strips browser headers, and retains policy metadata.
        val exoPayload = prepared.enginePayload(RendererKind.EXO)
        assertEquals("http://127.0.0.1:45210/s/reg-1/master.m3u8", exoPayload.url)
        assertTrue(exoPayload.headers.isEmpty())
        assertTrue(exoPayload.subtitle_resources.isEmpty())
        assertEquals(3, exoPayload.subtitles.size)
        assertTrue(exoPayload.subtitles.all { it.startsWith("http://127.0.0.1:45210/s/") })
        assertEquals("page_cast", exoPayload.detected_by)
        assertEquals(listOf("http://10.0.0.42:8096"), exoPayload.allowed_private_origins)

        // Subtitle fetch resolution returns loopback URL with empty headers for both #label and raw URL.
        val resolvedSub = router.resolveSubtitleFetch(
            "http://10.0.0.42:8096/videos/10/en.vtt#English",
            pageItem.headers,
        )
        assertNotNull(resolvedSub)
        assertEquals("http://127.0.0.1:45210/s/reg-2/en.vtt", resolvedSub!!.fetchUrl)
        assertTrue(resolvedSub.headers.orEmpty().isEmpty())
        assertEquals("http://127.0.0.1:45210", resolvedSub.trustedLocalProxyOrigin)

        // Linked-page replacement revokes previous item's leases and registers the new item.
        val linkedPageItem = PlayPayload(
            url = "https://cdn.example.com/linked/manifest.mpd",
            content_type = "application/dash+xml",
            detected_by = "linked_page",
        )
        val linkedPrepared = router.prepareItem(linkedPageItem).getOrThrow()
        assertEquals(listOf("reg-1", "reg-2", "reg-3", "reg-4"), fakeProxy.closedLeases)
        assertEquals(setOf("reg-5"), fakeProxy.activeLeases)

        // DASH uses .mpd on Exo and manifest.edl on MPV without re-registering.
        assertEquals(
            "http://127.0.0.1:45210/s/reg-5/manifest.mpd",
            linkedPrepared.enginePayload(RendererKind.EXO).url,
        )
        assertEquals(
            "http://127.0.0.1:45210/s/reg-5/manifest.edl",
            linkedPrepared.enginePayload(RendererKind.MPV).url,
        )

        router.close()
        assertTrue(fakeProxy.activeLeases.isEmpty())
    }

    @Test
    fun lateSubtitleRegistersWithAllowedOriginsAndFailsClosedOnError() = runBlocking {
        val fakeProxy = FakePageProxyClient(
            port = 46100,
            failOnUrlPredicate = { it.contains("broken.vtt") },
        )
        val router = PageMediaProxyRouter(fakeProxy)
        val pageItem = PlayPayload(
            url = "https://cdn.example.com/video/index.m3u8",
            detected_by = "linked_page",
            allowed_private_origins = listOf("http://10.0.0.50:8080"),
        )
        router.prepareItem(pageItem).getOrThrow()

        // Unregistered subtitle on a page item never falls back to a direct fetch target.
        assertNull(
            router.resolveSubtitleFetch("http://10.0.0.50:8080/late/en.vtt", emptyMap()),
        )

        val lateProxyUrl = router.registerLateSubtitle(
            item = pageItem,
            subtitleUrl = "http://10.0.0.50:8080/late/en.vtt#LateEnglish",
            headers = mapOf("Referer" to "https://watch.example.com/"),
            contentType = "text/vtt",
        ).getOrThrow()
        assertEquals("http://127.0.0.1:46100/s/reg-2/en.vtt", lateProxyUrl)
        val lastReg = fakeProxy.registrations.last()
        assertEquals("http://10.0.0.50:8080/late/en.vtt", lastReg.url)
        assertEquals(listOf("http://10.0.0.50:8080"), lastReg.allowedPrivateOrigins)
        assertTrue(lastReg.remoteOrigin)

        val resolvedLate = router.resolveSubtitleFetch(
            "http://10.0.0.50:8080/late/en.vtt#LateEnglish",
            mapOf("Referer" to "https://watch.example.com/"),
        )
        assertNotNull(resolvedLate)
        assertEquals(lateProxyUrl, resolvedLate!!.fetchUrl)
        assertTrue(resolvedLate.headers.orEmpty().isEmpty())

        // Failed late subtitle registration fails closed and leaves no direct fetch path.
        val failedLate = router.registerLateSubtitle(
            item = pageItem,
            subtitleUrl = "https://cdn.example.com/subs/broken.vtt",
            headers = emptyMap(),
        )
        assertTrue(failedLate.isFailure)
        assertNull(router.resolveSubtitleFetch("https://cdn.example.com/subs/broken.vtt", null))

        // A subtitle the viewer adds from another LAN origin (a file served by the phone) is
        // their choice: it registers with its own origin approved on top of the item's grants.
        val phoneSubtitle = router.registerLateSubtitle(
            item = pageItem,
            subtitleUrl = "http://192.168.1.30:43210/subtitle/file.srt#file.srt",
            headers = emptyMap(),
        ).getOrThrow()
        assertTrue(phoneSubtitle.startsWith("http://127.0.0.1:46100/s/"))
        assertEquals(
            listOf("http://10.0.0.50:8080", "http://192.168.1.30:43210"),
            fakeProxy.registrations.last().allowedPrivateOrigins,
        )

        // Forbidden loopback late subtitle is rejected before touching proxy registration.
        val regCountBefore = fakeProxy.registrations.size
        val forbiddenLate = router.registerLateSubtitle(
            item = pageItem,
            subtitleUrl = "http://127.0.0.1:9000/secret.vtt",
            headers = emptyMap(),
        )
        assertTrue(forbiddenLate.isFailure)
        assertEquals(regCountBefore, fakeProxy.registrations.size)
    }

    @Test
    fun pageMediaFailsClosedWhenProxyUnavailableOrRegistrationFails() = runBlocking {
        val pageItem = PlayPayload(
            url = "https://cdn.example.com/video/master.m3u8",
            detected_by = "page_cast",
            subtitles = listOf("https://cdn.example.com/video/en.vtt"),
        )

        // 1. Native proxy unsupported -> rejected without direct fallback.
        val unsupportedRouter = PageMediaProxyRouter(FakePageProxyClient(isSupported = false))
        assertFalse(unsupportedRouter.canAcceptItem(pageItem))
        assertTrue(unsupportedRouter.prepareItem(pageItem).isFailure)
        assertNull(unsupportedRouter.currentPrepared())

        // 2. Proxy start failure -> rejected.
        val startFailRouter = PageMediaProxyRouter(
            FakePageProxyClient(startError = IllegalStateException("bind failed")),
        )
        assertTrue(startFailRouter.prepareItem(pageItem).isFailure)
        assertNull(startFailRouter.currentPrepared())

        // 3. Subtitle registration failure -> the subtitle is dropped and the media still plays.
        val subFailClient = FakePageProxyClient(
            failOnUrlPredicate = { it.endsWith("en.vtt") },
        )
        val subFailRouter = PageMediaProxyRouter(subFailClient)
        val subFailPrepared = subFailRouter.prepareItem(pageItem).getOrThrow()
        assertTrue(subFailPrepared.enginePayload(RendererKind.EXO).subtitles.isEmpty())
        assertNull(subFailPrepared.resolveSubtitleFetch("https://cdn.example.com/video/en.vtt", null))
        assertEquals(listOf("reg-1"), subFailClient.activeLeases.toList())

        // 4. Forbidden loopback/metadata/ungranted-LAN URLs and disallowed headers are rejected.
        val strictClient = FakePageProxyClient()
        val strictRouter = PageMediaProxyRouter(strictClient)
        for (badItem in listOf(
            pageItem.copy(
                url = "http://127.0.0.1:8080/internal.m3u8",
                allowed_private_origins = listOf("http://127.0.0.1:8080"),
            ),
            pageItem.copy(
                url = "http://169.254.169.254/latest/meta-data",
                allowed_private_origins = listOf("http://169.254.169.254"),
            ),
            pageItem.copy(
                url = "http://10.0.0.25:8080/private.m3u8",
                allowed_private_origins = emptyList(),
            ),
            pageItem.copy(
                headers = mapOf("X-Forwarded-For" to "192.0.2.1"),
            ),
        )) {
            assertFalse(strictRouter.canAcceptItem(badItem))
            assertTrue(strictRouter.prepareItem(badItem).isFailure)
        }
        assertEquals(0, strictClient.startCalls)
    }

    @Test
    fun refusedPageSubtitlesAreDroppedWithoutBlockingTheItem() = runBlocking {
        val client = FakePageProxyClient()
        val router = PageMediaProxyRouter(client)
        val item = PlayPayload(
            url = "https://cdn.example.com/video/master.m3u8",
            detected_by = "page_cast",
            subtitles = listOf(
                "http://10.0.0.50:8080/ungranted.vtt",
                "https://cdn.example.com/video/en.vtt",
            ),
            subtitle_resources = listOf(
                SubtitleResource(url = "http://127.0.0.1:9000/secret.vtt"),
            ),
        )

        assertTrue(router.canAcceptItem(item))
        val prepared = router.prepareItem(item).getOrThrow()

        // Only the media and the public subtitle reach the proxy; refused ones are never fetched.
        assertEquals(
            listOf(
                "https://cdn.example.com/video/master.m3u8",
                "https://cdn.example.com/video/en.vtt",
            ),
            client.registrations.map { it.url },
        )
        val subtitles = prepared.enginePayload(RendererKind.EXO).subtitles
        assertEquals(1, subtitles.size)
        assertTrue(subtitles.single().startsWith("http://127.0.0.1:43125/s/"))
        assertNull(prepared.resolveSubtitleFetch("http://10.0.0.50:8080/ungranted.vtt", null))
        assertNull(prepared.resolveSubtitleFetch("http://127.0.0.1:9000/secret.vtt", null))
    }

    @Test
    fun launcherSelectsMpvForPageMediaOnlyWhenProxyIsAvailable() {
        val pageMpvItem = PlayPayload(
            url = "https://cdn.example.com/video.mkv",
            detected_by = "page_cast",
            player_mode = "mpv",
        )
        val linkedItem = PlayPayload(
            url = "https://cdn.example.com/stream.m3u8",
            detected_by = "linked_page",
        )
        val directMpvItem = PlayPayload(
            url = "https://cdn.example.com/direct.mkv",
            detected_by = "cast_sheet",
            player_mode = "mpv",
        )

        assertEquals(
            "mpv",
            PlayerLauncher.selectRendererMode(pageMpvItem, tvPlayerMode = "phone", proxyAvailable = true),
        )
        assertEquals(
            "exo",
            PlayerLauncher.selectRendererMode(pageMpvItem, tvPlayerMode = "phone", proxyAvailable = false),
        )
        assertEquals(
            "mpv",
            PlayerLauncher.selectRendererMode(linkedItem, tvPlayerMode = "mpv", proxyAvailable = true),
        )
        assertEquals(
            "exo",
            PlayerLauncher.selectRendererMode(linkedItem, tvPlayerMode = "mpv", proxyAvailable = false),
        )
        assertEquals(
            "mpv",
            PlayerLauncher.selectRendererMode(directMpvItem, tvPlayerMode = "phone", proxyAvailable = false),
        )
    }

    @Test
    fun secondLayerPolicyAllowsTrustedLoopbackProxyAndBlocksUntrustedLoopback() {
        val proxyOrigin = "http://127.0.0.1:41055"
        val loopbackPeer = InetAddress.getByName("127.0.0.1")
        val ipv6Loopback = InetAddress.getByName("::1")
        val privatePeer = InetAddress.getByName("10.0.0.99")

        val trustedPlaylist = "$proxyOrigin/s/session-9/master.m3u8"
        val trustedSegment = "$proxyOrigin/proxy/seg-1.ts?token=abc"
        assertTrue(
            MediaNetworkPolicy.areAllowedConnectionOrTrustedProxy(
                trustedPlaylist,
                loopbackPeer,
                emptyList(),
                proxyOrigin,
            ),
        )
        assertTrue(
            MediaNetworkPolicy.areAllowedConnectionOrTrustedProxy(
                trustedSegment,
                loopbackPeer,
                emptyList(),
                proxyOrigin,
            ),
        )

        // Wrong port, admin endpoint, IPv6 loopback, missing trusted origin, or non-loopback peer fail.
        assertFalse(
            MediaNetworkPolicy.areAllowedConnectionOrTrustedProxy(
                "http://127.0.0.1:9999/s/session-9/master.m3u8",
                loopbackPeer,
                emptyList(),
                proxyOrigin,
            ),
        )
        assertFalse(
            MediaNetworkPolicy.areAllowedConnectionOrTrustedProxy(
                "$proxyOrigin/register",
                loopbackPeer,
                emptyList(),
                proxyOrigin,
            ),
        )
        assertFalse(
            MediaNetworkPolicy.areAllowedConnectionOrTrustedProxy(
                trustedPlaylist,
                ipv6Loopback,
                emptyList(),
                proxyOrigin,
            ),
        )
        assertFalse(
            MediaNetworkPolicy.areAllowedConnectionOrTrustedProxy(
                trustedPlaylist,
                privatePeer,
                emptyList(),
                proxyOrigin,
            ),
        )
        assertFalse(
            MediaNetworkPolicy.areAllowedConnectionOrTrustedProxy(
                trustedPlaylist,
                loopbackPeer,
                emptyList(),
                null,
            ),
        )
    }
}
