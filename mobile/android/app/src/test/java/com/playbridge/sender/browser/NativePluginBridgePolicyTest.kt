package com.playbridge.sender.browser

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NativePluginBridgePolicyTest {
    private fun authorized(
        sender: String = "https://streams.example/#/movie/1",
        current: String = sender,
        installed: String? = "https://streams.example",
        top: Boolean = true,
        session: Boolean = true,
        content: Boolean = true,
        debug: Boolean = false,
    ) = NativePluginBridgePolicy.authorized(sender, current, installed, top, session, content, debug)

    @Test fun `requires actual installed top-level session and origin`() {
        assertTrue(authorized())
        assertFalse(authorized(installed = null))
        assertFalse(authorized(top = false))
        assertFalse(authorized(session = false))
        assertFalse(authorized(content = false))
        assertFalse(authorized(current = "https://attacker.example/"))
        assertFalse(authorized(sender = "https://streams.example.attacker.example/"))
        assertFalse(authorized(sender = "https://user@streams.example/"))
    }

    @Test fun `HTTP app access is restricted to debug development builds`() {
        val local = "http://192.168.1.8:5182"
        assertFalse(authorized(sender = "$local/#/", installed = local))
        assertTrue(authorized(sender = "$local/#/", installed = local, debug = true))
        assertFalse(authorized(sender = "http://public.example/", installed = "http://public.example", debug = true))
    }

    @Test fun `resolve accepts bounded identifiers and valid episode combinations`() {
        val repo = "https://plugins.example/manifest.json"
        fun valid(ids: List<String> = listOf("castle"), id: String = "60625", type: String = "tv", season: Int? = 8, episode: Int? = 2) =
            NativePluginBridgePolicy.validResolveRequest(repo, ids, id, type, season, episode)
        assertTrue(valid())
        assertTrue(valid(type = "movie", season = null, episode = null))
        assertFalse(valid(ids = emptyList()))
        assertFalse(valid(ids = List(33) { "provider$it" }))
        assertFalse(valid(ids = listOf("castle", "castle")))
        assertFalse(valid(ids = listOf("castle\n")))
        assertFalse(valid(id = "https://attacker.example"))
        assertFalse(valid(id = "0"))
        assertFalse(valid(id = "60625:8:2"))
        assertFalse(valid(type = "series"))
        assertFalse(valid(type = "movie"))
        assertFalse(valid(episode = null))
        assertFalse(valid(episode = -1))
        assertFalse(NativePluginBridgePolicy.validResolveRequest("https://user:password@plugins.example/manifest.json", listOf("castle"), "60625", "tv", 8, 2))
    }
}
