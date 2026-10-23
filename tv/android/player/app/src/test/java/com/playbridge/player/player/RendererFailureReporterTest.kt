package com.playbridge.player.player

import com.playbridge.shared.protocol.createStatusJson
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RendererFailureReporterTest {
    private class Host {
        val reporter = RendererFailureReporter()
        val events = mutableListOf<String>()
        val frames = mutableListOf<String>()

        fun fail(
            allowFailover: Boolean = false,
            fallback: RendererKind? = RendererKind.MPV,
            attempted: Set<RendererKind> = setOf(RendererKind.EXO),
            message: String = "ERROR_CODE_IO_BAD_HTTP_STATUS: http_403",
        ): RendererKind? {
            val next = reporter.fallbackOrReport(
                allowEngineFailover = allowFailover,
                fallback = fallback,
                attemptedRenderers = attempted,
                message = message,
                positionMs = 0L,
                durationMs = 0L,
                mediaKind = "video",
                playbackId = "playback-1",
                currentItemId = "item-1",
                broadcastStatus = { frame -> frames += frame; events += "status" },
            )
            if (next == null) events += "schedule-exit"
            return next
        }
    }

    @Test fun `safe categories use the same short sentences as other receivers`() {
        val cases = mapOf(
            "ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED: unsupported_format" to "This video format isn't supported",
            "mpv_unsupported_format" to "This video format isn't supported",
            "ERROR_CODE_DECODING_FAILED: decoder_failure" to "The TV couldn't decode this video",
            "mpv_decoder_failure" to "The TV couldn't decode this video",
            "ERROR_CODE_IO_NETWORK_CONNECTION_FAILED: network_exhausted" to "The TV couldn't reach the video server",
            "ERROR_CODE_TIMEOUT: timeout_exhausted" to "The TV couldn't reach the video server",
            "mpv_network_failed" to "The TV couldn't reach the video server",
            "playback_stalled" to "The video stopped loading",
            "The video stopped loading" to "The video stopped loading",
            "mpv_load_failed" to "The media couldn't be loaded",
            "mpv_playback_failed" to "The media couldn't be loaded",
        )
        for ((code, sentence) in cases) assertEquals(sentence, RendererFailureReporter.userMessage(code))
        for (status in listOf(401, 403, 404, 410, 429, 500)) {
            val sentence = when (status) {
                401, 403 -> "The link was refused (HTTP $status)"
                404, 410 -> "The video wasn't found (HTTP $status)"
                else -> "The media couldn't be loaded (HTTP $status)"
            }
            assertEquals(sentence, RendererFailureReporter.userMessage("ERROR_CODE_IO_BAD_HTTP_STATUS: http_$status"))
            assertEquals(sentence, RendererFailureReporter.userMessage("mpv_http_$status"))
        }
    }

    @Test
    fun `terminal failure sends one error status before scheduling exit`() {
        val host = Host()
        assertNull(host.fail())
        assertEquals(listOf("status", "schedule-exit"), host.events)
        val status = Json.parseToJsonElement(host.frames.single()).jsonObject
        assertEquals(setOf("type", "state", "position", "duration", "mediaKind", "playbackId", "currentItemId", "error"), status.keys)
        assertEquals("status", status.getValue("type").jsonPrimitive.content)
        assertEquals("error", status.getValue("state").jsonPrimitive.content)
        assertEquals("0", status.getValue("position").jsonPrimitive.content)
        assertEquals("0", status.getValue("duration").jsonPrimitive.content)
        assertEquals("playback-1", status.getValue("playbackId").jsonPrimitive.content)
        assertEquals("item-1", status.getValue("currentItemId").jsonPrimitive.content)
        assertEquals("The link was refused (HTTP 403)", status.getValue("error").jsonPrimitive.content)
        host.fail()
        assertEquals(1, host.frames.size)
    }

    @Test
    fun `available failover emits no error and only exhausted fallback reports failure`() {
        val host = Host()
        assertEquals(RendererKind.MPV, host.fail(allowFailover = true))
        assertTrue(host.frames.isEmpty())
        assertTrue(host.events.isEmpty())
        assertNull(host.fail(allowFailover = true, fallback = RendererKind.EXO, attempted = setOf(RendererKind.EXO, RendererKind.MPV)))
        assertEquals(1, host.frames.size)
        assertEquals(listOf("status", "schedule-exit"), host.events)
    }

    @Test
    fun `page controlled media with no permitted fallback still reports terminal error`() {
        val host = Host()
        val fallback = fallbackRenderer(RendererKind.EXO, pageControlled = true)
        assertNull(host.fail(allowFailover = true, fallback = fallback))
        assertEquals(1, host.frames.size)
    }

    @Test
    fun `new playback request resets terminal deduplication`() {
        val host = Host()
        host.fail()
        host.reporter.reset()
        assertEquals(RendererKind.MPV, host.fail(allowFailover = true))
        host.fail()
        assertEquals(2, host.frames.size)
    }

    @Test
    fun `arbitrary renderer text never appears in error frame`() {
        for (message in listOf(
            "https://media.example/movie.m3u8?token=private-token",
            "HTTP 403: https://media.example/movie Authorization: Bearer private-token",
            "ERROR_CODE_IO_BAD_HTTP_STATUS: http_403\nCookie: private-cookie",
            "Renderer failed with password=private-password",
            "ERROR_CODE_IO_BAD_HTTP_STATUS: http_999",
        )) {
            val host = Host()
            host.fail(message = message)
            val frame = host.frames.single()
            val safe = Json.parseToJsonElement(frame).jsonObject.getValue("error").jsonPrimitive.content
            assertEquals("The media couldn't be loaded", safe)
            assertFalse(frame.contains("https://"))
            assertFalse(frame.contains("private-"))
            assertFalse(frame.contains("Authorization"))
            assertFalse(frame.contains("Cookie"))
        }
    }

    @Test
    fun `normal statuses retain their existing fields and omit optional error`() {
        val normal = Json.parseToJsonElement(createStatusJson("playing", 123L, 456L, "Movie")).jsonObject
        assertEquals(setOf("type", "state", "position", "duration", "title"), normal.keys)
        assertFalse(normal.containsKey("error"))
    }
}
