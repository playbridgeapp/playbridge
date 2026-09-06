package com.playbridge.sender.cast

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** Decorate all native media send paths, including incremental queue additions. */
internal fun applyCastHistoryPreference(message: String, preventHistory: Boolean): String {
    if (!preventHistory) return message
    val command = Json.parseToJsonElement(message) as? JsonObject ?: return message
    if ((command["type"] as? JsonPrimitive)?.contentOrNull != "command") return message
    val payload = command["payload"] as? JsonObject ?: return message
    fun mark(item: JsonElement): JsonObject =
        JsonObject((item as JsonObject) + ("skipHistory" to JsonPrimitive(true)))
    val updated = when ((command["action"] as? JsonPrimitive)?.contentOrNull) {
        "playlist" -> JsonObject(payload + ("items" to JsonArray(
            (payload["items"] as JsonArray).map(::mark),
        )))
        "queue_add" -> JsonObject(payload + ("item" to mark(payload.getValue("item"))))
        else -> return message
    }
    return JsonObject(command + ("payload" to updated)).toString()
}
