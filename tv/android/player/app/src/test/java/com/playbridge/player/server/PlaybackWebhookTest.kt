package com.playbridge.player.server

import java.net.InetAddress
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.*
import org.junit.Test
import playbridge.ProgressWebhook

class PlaybackWebhookTest {
    private val config = ProgressWebhook("https://sync.example.com/progress", "test-token")
    private fun queue(playback: String = "one") = """{"type":"playlist_status","playbackId":"$playback","items":[{"itemId":"e1","progressIdentity":{"type":"series","contentId":"tt1","videoId":"tt1:1:1","season":1,"episode":1}},{"itemId":"e2","progressIdentity":{"type":"series","contentId":"tt1","videoId":"tt1:1:2","season":1,"episode":2}}]}"""
    private fun status(state: String, item: String = "e1", playback: String = "one", duration: Long = 100000L, position: Long = 12000L) = """{"type":"status","playbackId":"$playback","currentItemId":"$item","state":"$state","position":$position,"duration":$duration}"""

    @Test fun reportsTransitionsAndThrottlesProgressWithoutSenderConnection() {
        var now = 1000L
        val bodies = mutableListOf<String>()
        val reporter = PlaybackWebhook({ _, body -> bodies.add(body) }, { now })
        assertTrue(reporter.configure(config))
        reporter.observe(queue())
        reporter.observe(status("playing"))
        now += 29999
        reporter.observe(status("playing"))
        assertEquals(1, bodies.size)
        now++
        reporter.observe(status("playing"))
        reporter.observe(status("buffering"))
        reporter.observe(status("playing"))
        reporter.observe(status("paused"))
        reporter.observe(status("paused"))
        reporter.observe(status("playing"))
        reporter.observe(status("ended"))
        reporter.stop()
        assertEquals(listOf("started", "progress", "paused", "started", "ended"), bodies.map { Json.parseToJsonElement(it).jsonObject["event"]!!.jsonPrimitive.content })
        assertTrue(bodies.all { !it.contains("test-token") })
    }

    @Test fun queueChangeReportsCorrectEpisodeAndReplacementIgnoresStalePlayback() {
        val bodies = mutableListOf<String>()
        val reporter = PlaybackWebhook({ _, body -> bodies.add(body) })
        reporter.configure(config)
        reporter.observe(queue())
        reporter.observe(status("playing"))
        reporter.observe(status("ended"))
        reporter.observe(status("playing", "e2"))
        assertTrue(bodies.last().contains("tt1:1:2"))
        reporter.configure(config)
        val size = bodies.size
        reporter.observe(queue())
        reporter.observe(status("playing"))
        assertEquals(size, bodies.size)
        reporter.observe(queue("two"))
        reporter.observe(status("playing", playback = "two"))
        assertEquals(size + 1, bodies.size)
    }

    @Test fun skipsLiveAndClearsConfigurationForLocalPlayback() {
        val bodies = mutableListOf<String>()
        val reporter = PlaybackWebhook({ _, body -> bodies.add(body) })
        reporter.configure(config)
        reporter.observe(queue())
        reporter.observe(status("playing", duration = 0))
        assertTrue(bodies.isEmpty())
        reporter.configure(null)
        reporter.observe(status("playing"))
        assertTrue(bodies.isEmpty())
    }

    @Test fun terminalWithResetDurationUsesLastValidPosition() {
        val bodies = mutableListOf<String>()
        val reporter = PlaybackWebhook({ _, body -> bodies.add(body) })
        reporter.configure(config)
        reporter.observe(queue())
        reporter.observe(status("playing"))
        reporter.observe(status("stopped", duration = 0))
        reporter.stop()
        assertEquals(2, bodies.size)
        assertTrue(bodies.last().contains("\"event\":\"stopped\""))
        assertTrue(bodies.last().contains("\"durationMs\":100000"))
    }

    @Test fun terminalWithResetPositionUsesLastValidPosition() {
        val bodies = mutableListOf<String>()
        val reporter = PlaybackWebhook({ _, body -> bodies.add(body) })
        reporter.configure(config)
        reporter.observe(queue())
        reporter.observe(status("playing"))
        reporter.observe(status("stopped", position = 0))
        assertEquals("12000", Json.parseToJsonElement(bodies.last()).jsonObject["positionMs"]!!.jsonPrimitive.content)
    }

    @Test fun ignoresInvalidContentIdentities() {
        val bodies = mutableListOf<String>()
        val reporter = PlaybackWebhook({ _, body -> bodies.add(body) })
        reporter.configure(config)
        reporter.observe(queue().replace("series", "invalid"))
        reporter.observe(status("playing"))
        assertTrue(bodies.isEmpty())
    }

    @Test fun rejectsPrivateDestinationsCredentialsAndRedirectStyleUrls() {
        for (url in listOf("http://example.com", "https://u:p@example.com", "https://example.com/#secret", "https://localhost", "https://example.com:8443/", "https://example.com/?token=x", "https://127.0.0.1", "https://[::1]", "https://[::ffff:192.168.1.1]")) {
            assertFalse(url, validWebhook(config.copy(url = url)))
        }
        assertFalse(validWebhook(config.copy(bearer_token = "bad\r\nheader")))
        for (ip in listOf("10.1.2.3", "172.31.1.1", "192.168.1.1", "169.254.169.254", "100.64.0.1", "0.0.0.0", "224.0.0.1", "192.0.2.1", "198.51.100.1", "203.0.113.1", "fc00::1", "fe80::1", "2001:db8::1", "2002:7f00:1::")) {
            assertFalse(ip, isPublicWebhookAddress(InetAddress.getByName(ip)))
        }
        assertTrue(isPublicWebhookAddress(InetAddress.getByName("1.1.1.1")))
        assertTrue(isPublicWebhookAddress(InetAddress.getByName("2606:4700:4700::1111")))
    }
}
