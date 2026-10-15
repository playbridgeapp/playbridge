package com.playbridge.shared.protocol

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

const val RECEIVER_CAPABILITIES_V1_FEATURE = "receiver_capabilities_v1"
const val MAX_RECEIVER_CAPABILITIES_REVISION = 9_007_199_254_740_991L

fun createReceiverCapabilitiesSubscribeJson(): String = buildJsonObject {
    put("type", "command")
    put("action", "receiver_capabilities_subscribe")
    put("payload", buildJsonObject {})
}.toString()

/** Manual JSON keeps the bounded revision numeric; protobuf JSON uint64 uses strings. */
fun createReceiverCapabilitiesJson(revision: Long, capabilities: JsonObject): String {
    require(revision in 1..MAX_RECEIVER_CAPABILITIES_REVISION)
    return buildJsonObject {
        put("type", "receiver_capabilities")
        put("revision", revision)
        put("capabilities", capabilities)
    }.toString()
}
