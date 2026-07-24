package com.playbridge.sender.cast.roku

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class RokuClientTest {
    @Test
    fun parsePlayerStatus_parsesValidXmlCorrectly() {
        val xml = """
            <player error="false" state="play">
                <plugin bandwidth="43857850 bps" id="15985" name="PlayBridge"/>
                <format audio="aac" container="mp4" drm="none" video="h264"/>
                <buffering current="1000" max="1000" target="1000"/>
                <new_stream speed="128"/>
                <position>125000 ms</position>
                <duration>3600000 ms</duration>
                <is_live>false</is_live>
            </player>
        """.trimIndent()

        val status = RokuClient.parsePlayerStatus(xml)
        assertNotNull(status)
        assertEquals("play", status?.state)
        assertEquals(125000L, status?.positionMs)
        assertEquals(3600000L, status?.durationMs)
    }

    @Test
    fun parsePlayerStatus_handlesPausedState() {
        val xml = """
            <player error="false" state="pause">
                <position>5000 ms</position>
                <duration>10000 ms</duration>
            </player>
        """.trimIndent()

        val status = RokuClient.parsePlayerStatus(xml)
        assertNotNull(status)
        assertEquals("pause", status?.state)
        assertEquals(5000L, status?.positionMs)
        assertEquals(10000L, status?.durationMs)
    }

    @Test
    fun parsePlayerStatus_returnsNullOnInvalidXml() {
        val xml = "invalid xml content"
        val status = RokuClient.parsePlayerStatus(xml)
        assertNull(status)
    }
}
