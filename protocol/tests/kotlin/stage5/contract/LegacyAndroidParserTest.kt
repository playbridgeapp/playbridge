package stage5.contract

import com.squareup.moshi.Moshi
import com.squareup.wire.WireJsonAdapterFactory
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import playbridge.AuthResponse
import playbridge.CredentialBundle
import java.io.File

private data class TvCapabilities(
    val players: List<String> = emptyList(), val browsers: List<String> = emptyList(),
    val mediaKinds: List<String> = emptyList(), val screenMirrorWebRtc: Boolean = false,
    val features: Set<String> = emptySet(),
)
private class State { var value = TvCapabilities() }
private class Flow { fun emit(value: TvCapabilities) {} }
private class Scope { fun launch(block: () -> Unit) = block() }
private class LegacyAndroidParser {
    private val _tvCapabilitiesState = State()
    private val _tvCapabilities = Flow()
    private val scope = Scope()
    fun parse(json: JsonObject): TvCapabilities {
        emitCapabilities(json)
        return _tvCapabilitiesState.value
    }
    private fun emitCapabilities(json: JsonObject) {
        val players = parseStringArray(json, "players")
        val browsers = parseStringArray(json, "browsers")
        val mediaKinds = parseStringArray(json, "mediaKinds")
            .filter { it in setOf("video", "audio", "image") }
        val screenMirrorWebRtc = json["screenMirrorWebRtc"]?.jsonPrimitive?.contentOrNull == "true"
        val features = parseStringArray(json, "features").toSet()
        val capabilities = TvCapabilities(players, browsers, mediaKinds, screenMirrorWebRtc, features)
        // Always replace the previous receiver's capabilities. Otherwise connecting to an
        // older TV after a capable one can leave screen mirroring incorrectly enabled.
        _tvCapabilitiesState.value = capabilities
        scope.launch { _tvCapabilities.emit(capabilities) }
    }

    private fun parseStringArray(json: JsonObject, key: String): List<String> =
        (json[key] as? JsonArray)?.mapNotNull { it.jsonPrimitive.contentOrNull } ?: emptyList()

}

class LegacyAndroidParserTest {
    private val root = File(requireNotNull(System.getProperty("stage5.fixtureDir")))
    private val cases = Json.parseToJsonElement(File(root, "manifest.json").readText()).jsonArray
    private val moshi = Moshi.Builder().add(WireJsonAdapterFactory()).build()

    @Test
    fun retainedManualAuthAndDecryptedBundleFieldsIgnoreDescriptor() {
        var count = 0
        for (case in cases) {
            val item = case.jsonObject
            val schema = item.getValue("schema").jsonPrimitive.content
            if (item.getValue("valid").jsonPrimitive.content != "true" ||
                schema !in setOf("AuthResponse", "CredentialBundle")) continue
            val name = item.getValue("file").jsonPrimitive.content
            val json = Json.parseToJsonElement(File(root, name).readText()).jsonObject
            val without = JsonObject(json.filterKeys { it != "capabilities" })
            assertEquals(name, LegacyAndroidParser().parse(without), LegacyAndroidParser().parse(json))
            if (schema == "AuthResponse") {
                // Literal success expression retained from old onMessage auth branch.
                assertEquals(without["success"].toString() == "true", json["success"].toString() == "true")
            } else {
                // These are post-decryption field reads, not an encryption/pin test.
                assertEquals(without["token"]?.jsonPrimitive?.contentOrNull, json["token"]?.jsonPrimitive?.contentOrNull)
                assertEquals(without["certFingerprint"]?.jsonPrimitive?.contentOrNull, json["certFingerprint"]?.jsonPrimitive?.contentOrNull)
            }
            count++
        }
        assertEquals(29, count)
    }

    @Test
    fun generatedV1ModelsPreserveOpenIdentifiersAndCredentialDiagnosticsRedactToken() {
        val authJson = File(root, "unknown-identifiers-auth-v1.json").readText()
        val auth = requireNotNull(moshi.adapter(AuthResponse::class.java).fromJson(authJson))
        val caps = requireNotNull(auth.capabilities)
        assertEquals(1, caps.version)
        assertTrue("future_surface" in caps.surfaces)
        assertTrue("future_engine" in requireNotNull(caps.surfaces["player"]).engines)
        assertEquals(listOf("future_transport"), caps.surfaces.getValue("player").engines.getValue("future_engine").transport)
        assertEquals("future_scope", caps.volume?.scope)
        val bundle = requireNotNull(moshi.adapter(CredentialBundle::class.java)
            .fromJson(File(root, "unknown-identifiers-bundle-v1.json").readText()))
        assertEquals("TEST_ONLY_NOT_A_CREDENTIAL", bundle.token)
        assertFalse(bundle.toString().contains(bundle.token))
        assertEquals(caps, bundle.capabilities)
    }

    @Test
    fun unsupportedVersionIsExaminedBeforeTypedDescriptorDecode() {
        val obj = Json.parseToJsonElement(File(root, "unknown-version-auth-future.json").readText()).jsonObject
        val descriptor = obj.getValue("capabilities").jsonObject
        val version = descriptor["version"]?.jsonPrimitive?.intOrNull
        assertEquals(99, version)
        // The consumer must retain raw/opaque data and use legacy behavior; this
        // future layout is deliberately not decoded as a v1 generated object.
        val legacy = LegacyAndroidParser().parse(obj)
        assertEquals(listOf("avplayer"), legacy.players)
        assertNotNull(descriptor["futureLayout"])
    }
}
