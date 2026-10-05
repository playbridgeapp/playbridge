package com.playbridge.sender.browser

import com.playbridge.shared.protocol.createPlaylistCommandJson
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import playbridge.PlayPayload

class PageCastPayloadPolicyTest {
    @Test
    fun rejectsSenderFieldsAtNativeRequestAndItemBoundaries() {
        val item = """{"id":"episode","url":"https://media.example/video.mp4"}"""
        for (value in listOf("null", "false", """{"url":"https://callback.example/progress","bearerToken":"test-only"}""")) {
            for (source in listOf(
                """{"type":"cast","progressWebhook":$value,"items":[$item]}""",
                """{"type":"cast","items":[{"url":"https://media.example/video.mp4","progressWebhook":$value}]}""",
                """{"type":"linked_open","payload":{"progressWebhook":$value,"items":[$item]}}""",
                """{"type":"linked_supply","payload":{"items":[{"id":"episode","url":"https://media.example/video.mp4","progressWebhook":$value}]}}""",
                """{"type":"linked_supply","payload":{"items":[],"endOfList":true,"progressWebhook":$value}}""",
            )) {
                assertTrue(source, pageCastHasSenderOnlyFields(Json.parseToJsonElement(source)))
            }
        }
        assertFalse(pageCastHasSenderOnlyFields(Json.parseToJsonElement(
            """{"type":"cast","items":[$item],"metadata":{"progressWebhook":"just metadata"}}""",
        )))
    }

    @Test
    fun websitePlaylistSerializationNeverCarriesWebhook() {
        val payload = pageCastPlaylistPayload(
            items = listOf(PlayPayload(url = "https://media.example/video.mp4")),
            startIndex = 0,
            metadata = null,
            skipPreplay = false,
        )
        assertNull(payload.progress_webhook)
        val wire = Json.parseToJsonElement(createPlaylistCommandJson(payload)).jsonObject["payload"]!!.jsonObject
        assertFalse("progressWebhook" in wire)
        assertTrue((wire["items"] as JsonArray).all { "progressWebhook" !in (it as JsonObject) })
    }
}
