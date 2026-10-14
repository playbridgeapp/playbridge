package com.playbridge.sender.connection

import com.playbridge.shared.crypto.SasCrypto
import java.io.File
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

internal fun receiverFixture(name: String): JsonObject {
    val directory = generateSequence(File(requireNotNull(System.getProperty("user.dir")))) { it.parentFile }
        .map { File(it, "protocol/tests/fixtures/receiver-capabilities") }.first { it.isDirectory }
    return Json.parseToJsonElement(File(directory, name).readText()) as JsonObject
}

class ReceiverProtocolStateTest {
    private fun json(value: String) = Json.parseToJsonElement(value) as JsonObject
    private fun parse(value: String) = ReceiverDescriptorParser.parse(Json.parseToJsonElement(value))
    private fun auth(descriptor: String? = null) = json(
        """{"type":"auth_response","success":true${descriptor?.let { ",\"capabilities\":$it" }.orEmpty()}}""",
    )
    private fun authenticatedSession(): Pair<ReceiverProtocolSession, Long> {
        val owner = ReceiverProtocolSession()
        val session = owner.begin()
        assertTrue(owner.acceptCredentials(session, receiverFixture("apple-tv-auth-v1.json")))
        return owner to session
    }

    @Test fun authWithAndWithoutDescriptorReplacesRatherThanRetains() {
        val (owner, session) = authenticatedSession()
        assertNotNull(owner.state.value.descriptor)
        owner.acceptCredentials(session, auth())
        assertEquals(ReceiverProtocolState(), owner.state.value)
        owner.acceptCredentials(session, auth("""{"version":1,"surfaces":{}}"""))
        assertEquals(emptyMap<String, ReceiverSurface>(), owner.state.value.descriptor!!.surfaces)
    }

    @Test fun firstPairingReadsDecryptedBundleAndNeverPlaintextEnvelope() {
        // Synthetic credentials, using the same AES-GCM primitive as WebSocketClient; no socket/device.
        val bundle = receiverFixture("apple-tv-bundle-v1.json")
        val key = ByteArray(32) { it.toByte() }
        val nonce = ByteArray(12) { (it + 32).toByte() }
        val aad = SasCrypto.sha256("synthetic test transcript".toByteArray())
        val ciphertext = SasCrypto.aesGcmEncrypt(key, nonce, bundle.toString().toByteArray(), aad)
        val decrypted = json(SasCrypto.aesGcmDecrypt(key, nonce, ciphertext, aad).decodeToString())
        val owner = ReceiverProtocolSession()
        val session = owner.begin()
        owner.observe(session, receiverFixture("invalid-plaintext-pairing-descriptor.json"))
        assertNull(owner.state.value.descriptor)
        assertTrue(owner.acceptCredentials(session, decrypted))
        assertEquals(ReceiverDescriptorParser.parse(bundle["capabilities"]), owner.state.value.descriptor)
        assertEquals(bundle["token"], decrypted["token"])
        assertThrows(Exception::class.java) {
            SasCrypto.aesGcmDecrypt(key, nonce, ciphertext, ByteArray(32))
        }
    }

    @Test fun contractAuthAndBundleCorpusUsesSameParser() {
        for (profile in listOf("android-tv", "apple-tv", "desktop", "cli", "unknown-identifiers", "unknown-version")) {
            val variant = if (profile == "unknown-version") "future" else "v1"
            val auth = receiverFixture("$profile-auth-$variant.json")
            val bundle = receiverFixture("$profile-bundle-$variant.json")
            val (owner, session) = authenticatedSession()
            owner.acceptCredentials(session, auth)
            val authDescriptor = owner.state.value.descriptor
            owner.acceptCredentials(session, bundle)
            assertEquals(profile, authDescriptor, owner.state.value.descriptor)
            assertEquals(profile != "unknown-version", authDescriptor != null)
            owner.acceptCredentials(session, receiverFixture("$profile-auth-legacy.json"))
            assertNull(owner.state.value.descriptor)
            owner.acceptCredentials(session, receiverFixture("$profile-bundle-legacy.json"))
            assertNull(owner.state.value.descriptor)
        }
    }

    @Test fun reconnectReplacesAndRetiredCallbacksCannotRepublish() {
        val (owner, old) = authenticatedSession()
        owner.observe(old, receiverFixture("volume-app-state.json"))
        val next = owner.begin()
        assertEquals(ReceiverProtocolState(), owner.state.value)
        assertFalse(owner.acceptCredentials(old, receiverFixture("android-tv-auth-v1.json")))
        owner.observe(old, receiverFixture("volume-app-state.json"))
        assertEquals(ReceiverProtocolState(), owner.state.value)
        owner.acceptCredentials(next, receiverFixture("desktop-auth-v1.json"))
        assertEquals("system", owner.state.value.descriptor!!.volume!!.scope)
        assertNull(owner.state.value.volume)
    }

    @Test fun receiverSwitchDisconnectAndRepairClearAllMetadata() {
        val (owner, session) = authenticatedSession()
        owner.observe(session, receiverFixture("volume-app-state.json"))
        owner.observe(session, receiverFixture("future-apple-complete-settings.json"))
        owner.observe(session, receiverFixture("browser-active-engine.json"))
        assertNotNull(owner.state.value.volume)
        assertNotNull(owner.state.value.contextEngine)
        owner.clear()
        assertEquals(ReceiverProtocolState(), owner.state.value)
        assertFalse(owner.acceptCredentials(session, receiverFixture("apple-tv-bundle-v1.json")))
        val receiverB = owner.begin()
        owner.observe(receiverB, receiverFixture("volume-app-state.json")) // unauthenticated reports ignored
        assertEquals(ReceiverProtocolState(), owner.state.value)
        owner.acceptCredentials(receiverB, receiverFixture("minimal-bundle.json"))
        assertEquals(ReceiverProtocolState(), owner.state.value)
    }

    @Test fun unknownIdentifiersAreRetainedCaseSensitively() {
        val descriptor = ReceiverDescriptorParser.parse(receiverFixture("unknown-identifiers-auth-v1.json")["capabilities"])!!
        assertEquals(setOf("future_transport"), descriptor.surfaces.getValue("player").engines.getValue("future_engine").transport)
        assertEquals(setOf("future_operation"), descriptor.surfaces.getValue("future_surface").engines.getValue("future_browser").browser)
        assertEquals("future_os", descriptor.host!!.osFamily)
        assertEquals("future_device", descriptor.host.deviceClass)
        assertEquals(setOf("future_companion"), descriptor.companionHints)
        assertEquals("future_scope", descriptor.volume!!.scope)
        val case = parse("""{"version":1,"surfaces":{"Player":{"engines":{"EXO":{"keys":["HOME","home"]}}}}}""")!!
        assertEquals(setOf("HOME", "home"), case.surfaces.getValue("Player").engines.getValue("EXO").keys)
    }

    @Test fun emptyAndPartialSetsAreCompleteAndDoNotInventOperations() {
        assertEquals(emptyMap<String, ReceiverSurface>(), parse("""{"version":1,"surfaces":{}}""")!!.surfaces)
        val engine = parse("""{"version":1,"surfaces":{"browser":{"engines":{"webview":{"text":["replace"]}}}}}""")!!
            .surfaces.getValue("browser").engines.getValue("webview")
        assertEquals(setOf("replace"), engine.text)
        assertTrue(engine.keys.isEmpty() && engine.pointer.isEmpty() && engine.browser.isEmpty())
        assertEquals(ReceiverEngine(), parse("""{"version":1,"surfaces":{"player":{"engines":{"mpv":{}}}}}""")!!
            .surfaces.getValue("player").engines.getValue("mpv"))
    }

    @Test fun unsupportedVersionIsOpaqueBeforeAnyV1ShapeParsing() {
        for (version in listOf("2", "99", "4294967295", "4294967296", "0", "-1", "1.1", "\"1\"", "null")) {
            assertNull(version, parse("""{"version":$version,"surfaces":[],"futureLayout":{"anything":true}}"""))
        }
        assertNotNull(parse("""{"version":1.0,"surfaces":{}}"""))
        assertNull(parse("""{"version":1.0000000000000000001,"surfaces":{}}"""))
    }

    @Test fun malformedRecognizedV1AndInvalidShapesAreRejected() {
        val cases = listOf(
            "missing-version", "string-version", "zero-version", "oversized-version", "missing-surfaces",
            "contradictory-surface-list", "duplicate-operations", "nonstring-operation", "transient-active-engine",
            "transient-volume", "transient-loop", "missing-engine-map", "empty-identifier", "invalid-volume-step",
            "wrong-companion-hint-type",
        )
        for (case in cases) assertNull(case, ReceiverDescriptorParser.parse(receiverFixture("invalid-$case.json")["capabilities"]))
        for (value in listOf("null", "[]", "true", "\"bad\"", """{"version":1,"surfaces":null}""")) assertNull(parse(value))
        val (owner, session) = authenticatedSession()
        owner.acceptCredentials(session, auth("""{"version":1,"surfaces":[]}"""))
        assertNull(owner.state.value.descriptor)
        assertTrue(owner.state.value.malformedV1)
    }

    @Test fun mapArrayAndIdentifierBoundsAreEnforced() {
        fun surfaces(count: Int) = (0 until count).joinToString(",") { "\"s$it\":{\"engines\":{}}" }
        fun engines(count: Int) = (0 until count).joinToString(",") { "\"e$it\":{}" }
        fun operations(count: Int) = (0 until count).joinToString(",") { "\"o$it\"" }
        assertNotNull(parse("""{"version":1,"surfaces":{${surfaces(16)}}}"""))
        assertNull(parse("""{"version":1,"surfaces":{${surfaces(17)}}}"""))
        assertNotNull(parse("""{"version":1,"surfaces":{"player":{"engines":{${engines(16)}}}}}"""))
        assertNull(parse("""{"version":1,"surfaces":{"player":{"engines":{${engines(17)}}}}}"""))
        assertNotNull(parse("""{"version":1,"surfaces":{"p":{"engines":{"e":{"text":[${operations(64)}]}}}}}"""))
        assertNull(parse("""{"version":1,"surfaces":{"p":{"engines":{"e":{"text":[${operations(65)}]}}}}}"""))
        for (size in listOf(64, 65)) {
            assertEquals(size == 64, parse("""{"version":1,"surfaces":{"${"x".repeat(size)}":{"engines":{}}}}""") != null)
        }
    }

    @Test fun descriptorUtf8SizeIsBoundedBeforeTypedDecode() {
        // Every map/set/string separately satisfies its bound; aggregate exceeds 64 KiB.
        fun descriptor(id: String): JsonObject = json("""{"version":1,"surfaces":{"p":{"engines":{${
            (0 until 16).joinToString(",") { engine -> "\"e$engine\":{\"text\":[" +
                (0 until 64).joinToString(",") { "\"$id$it\"" } + "]}" }
        }}}}}""")
        assertNull(ReceiverDescriptorParser.parse(descriptor("x".repeat(62))))
        val utf8 = descriptor("界".repeat(30))
        assertTrue(utf8.toString().length < 64 * 1024)
        assertTrue(utf8.toString().toByteArray().size > 64 * 1024)
        assertNull(ReceiverDescriptorParser.parse(utf8))
    }

    @Test fun volumeReportsPreserveIndependentMuteAndActualScopeWithoutGrantingCommands() {
        val (owner, session) = authenticatedSession()
        owner.observe(session, json("""{"type":"volume_state","scope":"app","level":0,"muted":false}"""))
        assertEquals(ReceiverVolumeState("app", 0.0, false), owner.state.value.volume)
        owner.observe(session, json("""{"type":"volume_state","scope":"app","muted":true}"""))
        assertEquals(ReceiverVolumeState("app", 0.0, true), owner.state.value.volume)
        owner.observe(session, json("""{"type":"volume_state","scope":"system","level":1}"""))
        assertEquals(ReceiverVolumeState("system", 1.0, null), owner.state.value.volume)
        owner.observe(session, json("""{"type":"volume_state","scope":"future_scope","muted":false}"""))
        assertEquals(ReceiverVolumeState("future_scope", null, false), owner.state.value.volume)
        assertNull(owner.state.value.descriptor!!.volume) // Apple has no native volume advertisement
    }

    @Test fun malformedVolumeDoesNotReplaceStoredState() {
        val (owner, session) = authenticatedSession()
        owner.observe(session, receiverFixture("volume-app-state.json"))
        val before = owner.state.value
        for (fields in listOf("\"level\":100", "\"level\":-1", "\"level\":\"0.5\"", "\"level\":null", "\"muted\":\"false\"", "\"muted\":0")) {
            owner.observe(session, json("""{"type":"volume_state","scope":"app",$fields}"""))
            assertEquals(before, owner.state.value)
        }
        owner.observe(session, receiverFixture("invalid-empty-volume-state.json"))
        assertEquals(before, owner.state.value)
    }

    @Test fun decimalBoundsAreCheckedBeforeFloatingPointRounding() {
        for (step in listOf("1.0000000000000000001", "-1e-400", "0", "1e-400")) {
            assertNull(parse("""{"version":1,"surfaces":{},"volume":{"scope":"app","step":$step}}"""))
        }
        assertNotNull(parse("""{"version":1,"surfaces":{},"volume":{"scope":"app","step":1}}"""))
        val (owner, session) = authenticatedSession()
        owner.observe(session, receiverFixture("volume-app-state.json"))
        val before = owner.state.value
        for (level in listOf("1.0000000000000000001", "-1e-400")) {
            owner.observe(session, json("""{"type":"volume_state","scope":"app","level":$level}"""))
            assertEquals(before, owner.state.value)
        }
    }

    @Test fun newSettingsFlagsAreOptionalTruthfulMetadataAndSnapshotsReplaceThem() {
        val (owner, session) = authenticatedSession()
        owner.observe(session, receiverFixture("future-apple-complete-settings.json"))
        assertEquals(ReceiverSettingsAvailability(false, true, true), owner.state.value.settingsAvailability)
        owner.observe(session, receiverFixture("legacy-complete-settings.json"))
        assertEquals(ReceiverSettingsAvailability(), owner.state.value.settingsAvailability)
        owner.observe(session, json("""{"type":"player_settings","subtitleOffsetAvailable":true,"engineAvailable":false,"loopAvailable":"true"}"""))
        assertEquals(ReceiverSettingsAvailability(true, false, null), owner.state.value.settingsAvailability)
    }

    @Test fun contextEngineChangesAndOmissionClearsRatherThanInfers() {
        val (owner, session) = authenticatedSession()
        owner.observe(session, receiverFixture("browser-active-engine.json"))
        assertEquals("gecko", owner.state.value.contextEngine)
        owner.observe(session, json("""{"type":"context","active":"player","engine":"future_engine"}"""))
        assertEquals("future_engine", owner.state.value.contextEngine)
        owner.observe(session, json("""{"type":"context","active":"idle"}"""))
        assertNull(owner.state.value.contextEngine)
        owner.observe(session, json("""{"type":"context","active":"player","engine":5}"""))
        assertNull(owner.state.value.contextEngine)
    }

    @Test fun reservedPushNeverChangesStaticDescriptor() {
        val (owner, session) = authenticatedSession()
        val before = owner.state.value
        owner.observe(session, receiverFixture("invalid-reserved-push.json"))
        assertEquals(before, owner.state.value)
    }
}
