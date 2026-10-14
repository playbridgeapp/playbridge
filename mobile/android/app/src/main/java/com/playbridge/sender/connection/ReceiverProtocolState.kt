package com.playbridge.sender.connection

import java.math.BigDecimal
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull

/** Phone-owned, session-only metadata. Step 6 will interpret operations; no policy lives here. */
data class ReceiverDescriptor(
    val version: Int,
    val surfaces: Map<String, ReceiverSurface>,
    val volume: ReceiverVolume? = null,
    val host: ReceiverHost? = null,
    val companionHints: Set<String> = emptySet(),
)

data class ReceiverSurface(val engines: Map<String, ReceiverEngine>)
data class ReceiverEngine(
    val transport: Set<String> = emptySet(),
    val keys: Set<String> = emptySet(),
    val pointer: Set<String> = emptySet(),
    val imageTransforms: Set<String> = emptySet(),
    val text: Set<String> = emptySet(),
    val settings: Set<String> = emptySet(),
    val tracks: Set<String> = emptySet(),
    val queue: Set<String> = emptySet(),
    val browser: Set<String> = emptySet(),
    val reporting: Set<String> = emptySet(),
)
data class ReceiverVolume(
    val scope: String,
    val operations: Set<String> = emptySet(),
    val step: Double? = null,
    val fallbackScope: String? = null,
)
data class ReceiverHost(val osFamily: String? = null, val deviceClass: String? = null)
data class ReceiverVolumeState(val scope: String, val level: Double? = null, val muted: Boolean? = null)
data class ReceiverSettingsAvailability(
    val subtitleOffsetAvailable: Boolean? = null,
    val engineAvailable: Boolean? = null,
    val loopAvailable: Boolean? = null,
)
data class ReceiverProtocolState(
    /** null means LEGACY, including malformed/unsupported descriptors in Step 2. */
    val descriptor: ReceiverDescriptor? = null,
    val volume: ReceiverVolumeState? = null,
    val settingsAvailability: ReceiverSettingsAvailability = ReceiverSettingsAvailability(),
    val contextEngine: String? = null,
)

/** Validate a bounded v1 object without decoding future versions as v1 or closing identifier sets. */
internal object ReceiverDescriptorParser {
    private const val MAX_BYTES = 64 * 1024
    private val operationFields = setOf(
        "transport", "keys", "pointer", "imageTransforms", "text", "settings", "tracks", "queue", "browser", "reporting",
    )

    fun parse(raw: JsonElement?): ReceiverDescriptor? = runCatching {
        val json = raw as? JsonObject ?: return null
        // Version selection MUST precede the typed shape: future surfaces need not even be maps.
        val version = json["version"] as? JsonPrimitive ?: return null
        if (version.isString || version.content.toBigDecimalOrNull()?.compareTo(BigDecimal.ONE) != 0) return null
        val serialized = json.toString()
        require(serialized.length <= MAX_BYTES && serialized.toByteArray(Charsets.UTF_8).size <= MAX_BYTES)
        json.only("version", "surfaces", "volume", "host", "companionHints")
        val surfaces = boundedMap(json.getValue("surfaces")) { surface ->
            surface.only("engines")
            ReceiverSurface(boundedMap(surface.getValue("engines")) { engine ->
                engine.only(*operationFields.toTypedArray())
                ReceiverEngine(
                    identifiers(engine["transport"]), identifiers(engine["keys"]), identifiers(engine["pointer"]),
                    identifiers(engine["imageTransforms"]), identifiers(engine["text"]), identifiers(engine["settings"]),
                    identifiers(engine["tracks"]), identifiers(engine["queue"]), identifiers(engine["browser"]), identifiers(engine["reporting"]),
                )
            })
        }
        val volume = json["volume"]?.let { value ->
            val obj = value as? JsonObject ?: error("Expected volume object")
            obj.only("scope", "operations", "step", "fallbackScope")
            ReceiverVolume(
                identifier(obj.getValue("scope")), identifiers(obj["operations"]),
                obj["step"]?.let { normalizedNumber(it, positive = true) },
                obj["fallbackScope"]?.let(::identifier),
            )
        }
        val host = json["host"]?.let { value ->
            val obj = value as? JsonObject ?: error("Expected host object")
            obj.only("osFamily", "deviceClass")
            ReceiverHost(obj["osFamily"]?.let(::identifier), obj["deviceClass"]?.let(::identifier))
        }
        ReceiverDescriptor(1, surfaces, volume, host, identifiers(json["companionHints"]))
    }.getOrNull()

    internal fun identifier(value: JsonElement): String {
        val primitive = value as? JsonPrimitive ?: error("Expected identifier")
        require(primitive.isString)
        return primitive.content.also { require(it.codePointCount(0, it.length) in 1..64) }
    }

    private fun identifiers(value: JsonElement?): Set<String> {
        if (value == null) return emptySet()
        val array = value as? JsonArray ?: error("Expected identifier array")
        require(array.size <= 64)
        val ids = array.map(::identifier)
        require(ids.distinct().size == ids.size)
        return ids.toSet()
    }

    private fun <T> boundedMap(value: JsonElement, decode: (JsonObject) -> T): Map<String, T> {
        val obj = value as? JsonObject ?: error("Expected identifier map")
        require(obj.size <= 16)
        return obj.mapValues { (key, child) ->
            identifier(JsonPrimitive(key))
            decode(child as? JsonObject ?: error("Expected object"))
        }
    }

    internal fun normalizedNumber(value: JsonElement, positive: Boolean = false): Double {
        val primitive = value as? JsonPrimitive ?: error("Expected number")
        require(!primitive.isString)
        // Check the original decimal before Double conversion can round an out-of-range value
        // onto 0 or 1. A step must also remain positive in the representation the phone stores.
        val decimal = primitive.content.toBigDecimalOrNull() ?: error("Expected finite number")
        require(decimal >= BigDecimal.ZERO && decimal <= BigDecimal.ONE && (!positive || decimal > BigDecimal.ZERO))
        return primitive.doubleOrNull?.also { require(it.isFinite() && (!positive || it > 0)) }
            ?: error("Expected finite number")
    }

    internal fun JsonObject.only(vararg fields: String) { require(keys.all { it in fields }) }
}

/** One owner for authenticated metadata; generation guards asynchronous retired-socket callbacks. */
internal class ReceiverProtocolSession {
    private var generation = 0L
    private var authenticated = false
    private val mutableState = MutableStateFlow(ReceiverProtocolState())
    val state = mutableState.asStateFlow()

    @Synchronized fun begin(): Long {
        generation++
        authenticated = false
        mutableState.value = ReceiverProtocolState()
        return generation
    }

    @Synchronized fun clear() { begin() }

    /** Called only after successful auth or verified decryption/pin checks of first-pairing credentials. */
    @Synchronized fun acceptCredentials(session: Long, envelope: JsonObject): Boolean {
        if (session != generation) return false
        authenticated = true
        mutableState.value = ReceiverProtocolState(descriptor = ReceiverDescriptorParser.parse(envelope["capabilities"]))
        return true
    }

    @Synchronized fun observe(session: Long, frame: JsonObject) {
        if (session != generation || !authenticated) return
        val old = mutableState.value
        when ((frame["type"] as? JsonPrimitive)?.contentOrNull) {
            "volume_state" -> parseVolume(frame)?.let { incoming ->
                val previous = old.volume?.takeIf { it.scope == incoming.scope }
                mutableState.value = old.copy(volume = incoming.copy(
                    level = incoming.level ?: previous?.level, muted = incoming.muted ?: previous?.muted,
                ))
            }
            "player_settings" -> mutableState.value = old.copy(settingsAvailability = ReceiverSettingsAvailability(
                boolean(frame["subtitleOffsetAvailable"]), boolean(frame["engineAvailable"]), boolean(frame["loopAvailable"]),
            ))
            "context" -> mutableState.value = old.copy(contextEngine = frame["engine"]?.let {
                runCatching { ReceiverDescriptorParser.identifier(it) }.getOrNull()
            })
        }
    }

    private fun parseVolume(frame: JsonObject): ReceiverVolumeState? = runCatching {
        with(ReceiverDescriptorParser) {
            frame.only("type", "scope", "level", "muted")
            val scope = identifier(frame.getValue("scope"))
            require("level" in frame || "muted" in frame)
            val level = frame["level"]?.let { normalizedNumber(it) }
            val muted = frame["muted"]?.let { boolean(it) ?: error("Expected boolean") }
            ReceiverVolumeState(scope, level, muted)
        }
    }.getOrNull()

    private fun boolean(value: JsonElement?): Boolean? =
        (value as? JsonPrimitive)?.takeUnless { it.isString }?.booleanOrNull
}
