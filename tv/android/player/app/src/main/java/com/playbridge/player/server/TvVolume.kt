package com.playbridge.player.server

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.math.roundToInt

internal data class TvVolumeState(val level: Double?, val muted: Boolean) {
    fun frame(): String = buildJsonObject {
        put("type", "volume_state")
        put("scope", "system")
        level?.let { put("level", it) }
        put("muted", muted)
    }.toString()
}

internal fun normalizedTvVolume(value: Int, maximum: Int): Double? =
    if (maximum > 0) value.coerceIn(0, maximum).toDouble() / maximum else null

internal fun tvVolumeIndex(level: Double, maximum: Int): Int? =
    if (level.isFinite() && level in 0.0..1.0 && maximum > 0) (level * maximum).roundToInt().coerceIn(0, maximum) else null

internal fun shouldPublishTvVolume(previous: TvVolumeState?, current: TvVolumeState?): Boolean =
    current != null && current != previous

/** The shared compatibility parser leaves this already-defined command as Unknown. */
internal fun parseTvVolumeCommand(raw: String): Double? = runCatching {
    val root = Json.parseToJsonElement(raw).jsonObject
    if (root["type"]?.jsonPrimitive?.content != "command" || root["action"]?.jsonPrimitive?.content != "volume") return null
    val payload = root["payload"]?.jsonObject ?: return null
    if (payload["scope"]?.jsonPrimitive?.content != "system") return null
    val number = payload["level"] as? JsonPrimitive ?: return null
    if (number.isString) return null
    number.doubleOrNull?.takeIf { it.isFinite() && it in 0.0..1.0 }
}.getOrNull()

/** Resync/auth never consume the broadcast change cache. */
internal class TvVolumePublication {
    private var previous: TvVolumeState? = null
    @Synchronized fun changed(state: TvVolumeState?): String? {
        val publish = shouldPublishTvVolume(previous, state)
        previous = state
        return if (publish) state?.frame() else null
    }
    fun sync(state: TvVolumeState?): String? = state?.frame()
}
