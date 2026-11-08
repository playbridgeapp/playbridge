package com.playbridge.shared.protocol

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class CommandResultTest {
    @Test
    fun resultCarriesRequestIdAndCode() {
        fun parse(json: String) = Json.parseToJsonElement(json).jsonObject
        val rejected = parse(createCommandResultJson("r1", ok = false, error = "queue_full"))
        assertEquals("command_result", rejected["type"]?.jsonPrimitive?.content)
        assertEquals("r1", rejected["requestId"]?.jsonPrimitive?.content)
        assertFalse(rejected.getValue("ok").jsonPrimitive.boolean)
        assertEquals("queue_full", rejected["error"]?.jsonPrimitive?.content)

        val accepted = parse(createCommandResultJson("r2", ok = true))
        assertTrue(accepted.getValue("ok").jsonPrimitive.boolean)
        assertFalse("error" in accepted)
    }

    @Test
    fun malformedCommandsAreInvalidAndUnknownOnesUnsupported() {
        fun rejectionOf(text: String) = assertIs<IncomingMessage.Unknown>(parseIncomingMessage(text)).rejection()

        assertEquals("invalid_command", rejectionOf("""{"type":"command","action":"playlist","requestId":"r","payload":{"items":"x"}}"""))
        assertEquals("invalid_command", rejectionOf("""{"type":"command","action":"playlist","requestId":"r"}"""))
        assertEquals("invalid_command", rejectionOf("""{"type":"command""""))
        assertEquals("unsupported", rejectionOf("""{"type":"command","action":"teleport","requestId":"r","payload":{}}"""))
    }
}
