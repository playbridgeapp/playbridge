package com.playbridge.player.logging

import com.playbridge.shared.logging.redactUrlForLog
import com.playbridge.shared.protocol.statusWithFullscreen
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class DebugNetworkLoggerTest {
    @Test fun headerNamesDropValuesAndBlankNames() {
        assertEquals("Authorization, Cookie", headerNamesForLog(listOf(" Authorization ", "", "Cookie", "Authorization")))
        assertEquals("", headerNamesForLog(emptyList()))
        assertFalse(headerNamesForLog(listOf("Authorization")).contains("secret"))
    }

    @Test fun statusFullscreenIsAddedOnlyToStatusFrames() {
        val playing = Json.parseToJsonElement(
            statusWithFullscreen("""{"type":"status","state":"playing","position":1,"duration":2}""", true),
        ).jsonObject
        assertEquals(true, playing["fullscreen"]?.jsonPrimitive?.boolean)
        assertEquals("playing", playing["state"]?.jsonPrimitive?.content)
        val idle = Json.parseToJsonElement(
            statusWithFullscreen("""{"type":"status","state":"idle","position":0,"duration":0}""", false),
        ).jsonObject
        assertEquals(false, idle["fullscreen"]?.jsonPrimitive?.boolean)
        val settings = """{"type":"player_settings","speed":1}"""
        assertEquals(settings, statusWithFullscreen(settings, true))
        assertEquals("not-json", statusWithFullscreen("not-json", true))
        assertEquals("https://<redacted>", redactUrlForLog("https://user:token@videos.example/a?sig=1"))
    }
}
