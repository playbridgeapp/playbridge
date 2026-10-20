package com.playbridge.player.protocol

import com.playbridge.shared.protocol.createAuthResponseJson
import com.playbridge.player.server.ServerService
import com.playbridge.player.server.TvCapabilities
import java.io.File
import com.playbridge.shared.protocol.createAddSubtitleCommandJson
import com.playbridge.shared.protocol.createContextJson
import com.playbridge.shared.protocol.createContextQueryJson
import com.playbridge.shared.protocol.createLogsClearJson
import com.playbridge.shared.protocol.createLogsJson
import com.playbridge.shared.protocol.createLogsQueryJson
import com.playbridge.shared.protocol.createProtectedPairingApprovedJson
import com.playbridge.shared.protocol.createPlaylistCommandJson
import com.playbridge.shared.protocol.createScreenMirrorCandidateCommandJson
import com.playbridge.shared.protocol.createScreenMirrorOfferJson
import com.playbridge.shared.protocol.createScreenMirrorReadyJson
import com.playbridge.shared.protocol.createScreenMirrorStartJson
import com.playbridge.shared.protocol.parseIncomingMessage
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import playbridge.PlayPayload
import playbridge.PlaylistPayload
import playbridge.SubtitleResource

import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class ProtocolJsonTest {

    private fun obj(json: String) = Json.parseToJsonElement(json).jsonObject

    private fun oldCredentialBundleJson(
        token: String,
        certFingerprint: String?,
        caps: TvCapabilities,
    ): String = buildJsonObject {
        put("token", token)
        certFingerprint?.let { put("certFingerprint", it) }
        put("players", buildJsonArray { caps.players.forEach { add(it) } })
        put("browsers", buildJsonArray { caps.browsers.forEach { add(it) } })
        put("mediaKinds", buildJsonArray { caps.mediaKinds.forEach { add(it) } })
        put("features", buildJsonArray { caps.features.forEach { add(it) } })
        if (caps.screenMirrorWebRtc) put("screenMirrorWebRtc", true)
    }.toString()

    private fun newCredentialBundleJson(
        token: String,
        certFingerprint: String?,
        caps: TvCapabilities,
    ): String = buildJsonObject {
        put("token", token)
        certFingerprint?.let { put("certFingerprint", it) }
        put("players", buildJsonArray { caps.players.forEach { add(it) } })
        put("browsers", buildJsonArray { caps.browsers.forEach { add(it) } })
        put("mediaKinds", buildJsonArray { caps.mediaKinds.forEach { add(it) } })
        put("features", buildJsonArray { caps.features.forEach { add(it) } })
        if (caps.screenMirrorWebRtc) put("screenMirrorWebRtc", true)
        put("capabilities", caps.capabilities)
    }.toString()

    @Test
    fun lateSubtitleCarriesItsOwnHeadersWithoutChangingLegacyControl() {
        val parsed = parseIncomingMessage(
            createAddSubtitleCommandJson(
                SubtitleResource(
                    url = "https://subs.example/captions.vtt",
                    headers = mapOf("Origin" to "https://page.example"),
                    label = "English",
                    language = "en",
                ),
            ),
        ) as com.playbridge.shared.protocol.IncomingMessage.Control
        assertEquals("add_subtitle", parsed.payload.command)
        assertEquals("https://subs.example/captions.vtt", parsed.payload.subtitle_resource?.url)
        assertEquals("https://page.example", parsed.payload.subtitle_resource?.headers?.get("Origin"))
        assertEquals("English", parsed.payload.subtitle_resource?.label)

        val legacy = parseIncomingMessage(
            com.playbridge.shared.protocol.createControlCommandJson("add_subtitle:https://subs.example/old.vtt"),
        ) as com.playbridge.shared.protocol.IncomingMessage.Control
        assertNull(legacy.payload.subtitle_resource)
    }

    @Test
    fun playlistSkipPreplayRoundTripsThroughTheParser() {
        val parsed = parseIncomingMessage(
            createPlaylistCommandJson(
                PlaylistPayload(
                    items = listOf(PlayPayload(url = "https://media.example/song.mp3")),
                    skip_preplay = true,
                ),
            ),
        )
        assertTrue(parsed is com.playbridge.shared.protocol.IncomingMessage.Playlist)
        val playlist =
            (parsed as com.playbridge.shared.protocol.IncomingMessage.Playlist).payload
        assertEquals(true, playlist.skip_preplay)
        assertEquals("audio", playlist.items.single().media_kind)
    }

    @Test
    fun pairingApprovedContainsOnlyProtectedCredentialEnvelope() {
        val o = obj(createProtectedPairingApprovedJson("nonce-b64", "ciphertext-b64"))
        assertEquals("pairing_approved", o["type"]?.jsonPrimitive?.content)
        assertEquals("nonce-b64", o["nonce"]?.jsonPrimitive?.content)
        assertEquals("ciphertext-b64", o["ciphertext"]?.jsonPrimitive?.content)
        assertNull(o["token"])
        assertNull(o["certFingerprint"])
    }

    @Test
    fun authResponseIncludesCertFingerprintWhenPresent() {
        val o = obj(createAuthResponseJson(success = true, certFingerprint = "sha256/xyz"))
        assertEquals("auth_response", o["type"]?.jsonPrimitive?.content)
        assertEquals(true, o["success"]?.jsonPrimitive?.content?.toBoolean())
        assertEquals("sha256/xyz", o["certFingerprint"]?.jsonPrimitive?.content)
    }

    @Test
    fun authResponseOmitsCertFingerprintWhenNull() {
        val o = obj(createAuthResponseJson(success = false))
        assertEquals(false, o["success"]?.jsonPrimitive?.content?.toBoolean())
        assertNull(o["certFingerprint"])
    }

    @Test
    fun authResponseAdvertisesQueueCrudFeatures() {
        val features = listOf("queue_crud_v1", "stable_item_ids", "command_results")
        val o = obj(createAuthResponseJson(success = true, features = features))
        assertEquals(features, o["features"]?.jsonArray?.map { it.jsonPrimitive.content })
    }

    @Test
    fun queueCrudCommandsParseStableIdsAndPlaybackGuard() {
        val add = parseIncomingMessage(
            """{"type":"command","action":"queue_add","requestId":"r1","payload":{"items":[{"url":"https://example.com/2.mp4","itemId":"item-2"}],"ifPlaybackId":"playback-1"}}""",
        ) as com.playbridge.shared.protocol.IncomingMessage.QueueAdd
        assertEquals("item-2", add.payload.items.single().item_id)
        assertEquals("playback-1", add.payload.if_playback_id)

        val remove = parseIncomingMessage(
            """{"type":"command","action":"queue_remove","payload":{"itemIds":["item-2"],"ifPlaybackId":"playback-1"}}""",
        ) as com.playbridge.shared.protocol.IncomingMessage.QueueRemove
        assertEquals(listOf("item-2"), remove.payload.item_ids)

        val move = parseIncomingMessage(
            """{"type":"command","action":"queue_move","payload":{"itemId":"item-2","beforeItemId":"item-1"}}""",
        ) as com.playbridge.shared.protocol.IncomingMessage.QueueMove
        assertEquals("item-1", move.payload.before_item_id)

        assertTrue(
            parseIncomingMessage("""{"type":"command","action":"queue_query"}""") is
                com.playbridge.shared.protocol.IncomingMessage.QueueQuery,
        )
    }

    @Test
    fun authResponseIncludesCapabilitiesWhenPresent() {
        val o = obj(createAuthResponseJson(
            success = true,
            players = listOf("exo", "mpv"),
            browsers = listOf("webview", "gecko"),
            mediaKinds = listOf("video", "audio", "image"),
        ))
        assertEquals(
            listOf("exo", "mpv"),
            o["players"]?.jsonArray?.map { it.jsonPrimitive.content }
        )
        assertEquals(
            listOf("webview", "gecko"),
            o["browsers"]?.jsonArray?.map { it.jsonPrimitive.content }
        )
        assertEquals(
            listOf("video", "audio", "image"),
            o["mediaKinds"]?.jsonArray?.map { it.jsonPrimitive.content },
        )
    }

    @Test
    fun authResponseAdvertisesScreenMirrorCapability() {
        val o = obj(createAuthResponseJson(success = true, screenMirrorWebRtc = true))
        assertEquals(true, o["screenMirrorWebRtc"]?.jsonPrimitive?.content?.toBoolean())
    }

    @Test
    fun parsesScreenMirrorOfferAndCandidateCommands() {
        val sessionId = "a271abbb-8a9c-4947-a776-8af9c6fe7fd0"
        val offer = parseIncomingMessage(createScreenMirrorOfferJson(sessionId, "v=0\\r\\n"))
        assertTrue(offer is com.playbridge.shared.protocol.IncomingMessage.ScreenMirrorOffer)

        val candidate = parseIncomingMessage(createScreenMirrorCandidateCommandJson(
            sessionId, "0", 0, "candidate:1 1 UDP 1 192.168.1.20 5000 typ host",
        ))
        assertTrue(candidate is com.playbridge.shared.protocol.IncomingMessage.ScreenMirrorCandidate)
    }

    @Test
    fun screenMirrorStartRequiresProtocolVersionOne() {
        val sessionId = "a271abbb-8a9c-4947-a776-8af9c6fe7fd0"
        assertTrue(
            parseIncomingMessage(createScreenMirrorStartJson(sessionId)) is
                com.playbridge.shared.protocol.IncomingMessage.ScreenMirrorStart,
        )

        val missingVersion =
            """{"type":"command","action":"screen_mirror_start","payload":{"sessionId":"$sessionId"}}"""
        val unsupportedVersion =
            """{"type":"command","action":"screen_mirror_start","payload":{"sessionId":"$sessionId","protocolVersion":2}}"""
        assertTrue(parseIncomingMessage(missingVersion) is com.playbridge.shared.protocol.IncomingMessage.Unknown)
        assertTrue(parseIncomingMessage(unsupportedVersion) is com.playbridge.shared.protocol.IncomingMessage.Unknown)
    }

    @Test
    fun screenMirrorReadyRoundTripsThroughTheParser() {
        val sessionId = "6ea051d8-d6d6-4513-aad4-7c72852420d5"
        val parsed = parseIncomingMessage(createScreenMirrorReadyJson(sessionId))
        assertTrue(parsed is com.playbridge.shared.protocol.IncomingMessage.ScreenMirrorReady)
        assertEquals(sessionId, (parsed as com.playbridge.shared.protocol.IncomingMessage.ScreenMirrorReady).sessionId)
    }

    @Test
    fun authResponseOmitsCapabilitiesWhenEmpty() {
        val o = obj(createAuthResponseJson(success = true))
        assertNull(o["players"])
        assertNull(o["browsers"])
        assertNull(o["mediaKinds"])
    }

    @Test
    fun pairingCommitSerializationAndParsing() {
        val json = com.playbridge.shared.protocol.createPairingCommitJson(
            commit = "my-commit-hash",
            deviceName = "My Phone",
            deviceUUID = "uuid-1234"
        )
        val parsed = com.playbridge.shared.protocol.parseIncomingMessage(json)
        assertTrue(parsed is com.playbridge.shared.protocol.IncomingMessage.PairingCommit)
        val commitMsg = (parsed as com.playbridge.shared.protocol.IncomingMessage.PairingCommit).msg
        assertEquals("pairing_commit", commitMsg.type)
        assertEquals("my-commit-hash", commitMsg.commit)
        assertEquals("My Phone", commitMsg.device_name)
        assertEquals("uuid-1234", commitMsg.device_uuid)
    }

    @Test
    fun pairingChallengeSerializationAndParsing() {
        val json = com.playbridge.shared.protocol.createPairingChallengeJson(
            tvEphPub = "tv-pubkey-bytes",
            nonceT = "nonce-t-bytes"
        )
        val parsed = com.playbridge.shared.protocol.parseIncomingMessage(json)
        assertTrue(parsed is com.playbridge.shared.protocol.IncomingMessage.PairingChallenge)
        val challengeMsg = (parsed as com.playbridge.shared.protocol.IncomingMessage.PairingChallenge).msg
        assertEquals("pairing_challenge", challengeMsg.type)
        assertEquals("tv-pubkey-bytes", challengeMsg.tv_eph_pub)
        assertEquals("nonce-t-bytes", challengeMsg.nonce_t)
    }

    @Test
    fun pairingRevealSerializationAndParsing() {
        val json = com.playbridge.shared.protocol.createPairingRevealJson(
            senderEphPub = "sender-pubkey-bytes",
            nonceS = "nonce-s-bytes"
        )
        val parsed = com.playbridge.shared.protocol.parseIncomingMessage(json)
        assertTrue(parsed is com.playbridge.shared.protocol.IncomingMessage.PairingReveal)
        val revealMsg = (parsed as com.playbridge.shared.protocol.IncomingMessage.PairingReveal).msg
        assertEquals("pairing_reveal", revealMsg.type)
        assertEquals("sender-pubkey-bytes", revealMsg.sender_eph_pub)
        assertEquals("nonce-s-bytes", revealMsg.nonce_s)
    }

    @Test
    fun pairingConfirmationSerializationAndParsing() {
        val json = com.playbridge.shared.protocol.createPairingConfirmationJson(
            mac = "confirmation-mac"
        )
        val parsed = com.playbridge.shared.protocol.parseIncomingMessage(json)
        assertTrue(parsed is com.playbridge.shared.protocol.IncomingMessage.PairingConfirmation)
        val confirmationMsg = (parsed as com.playbridge.shared.protocol.IncomingMessage.PairingConfirmation).msg
        assertEquals("pairing_confirmation", confirmationMsg.type)
        assertEquals("confirmation-mac", confirmationMsg.mac)
    }

    @Test
    fun logsTravelAsPairedRequestAndTargetedReply() {
        assertEquals(
            com.playbridge.shared.protocol.IncomingMessage.LogsQuery(clear = false),
            parseIncomingMessage(createLogsQueryJson("logs-1")),
        )
        assertEquals(
            com.playbridge.shared.protocol.IncomingMessage.LogsQuery(clear = true),
            parseIncomingMessage(createLogsClearJson("logs-2")),
        )
        val reply = obj(createLogsJson("logs-1", enabled = true, text = "line", truncated = true))
        assertEquals("logs", reply["type"]?.jsonPrimitive?.content)
        assertEquals("logs-1", reply["requestId"]?.jsonPrimitive?.content)
        assertEquals("line", reply["text"]?.jsonPrimitive?.content)
        assertEquals(true, reply["truncated"]?.jsonPrimitive?.content?.toBoolean())
    }

    @Test
    fun receiverAdvertisesPairedDiagnosticLogs() {
        val features = com.playbridge.player.server.TvCapabilities(emptyList(), emptyList()).features
        assertTrue("diagnostic_logs_v1" in features)
        assertTrue("player_home_v1" in features)
    }

    @Test
    fun descriptorShapeAndPerEngineSetsMatchCodeDerivation() {
        val caps = TvCapabilities(
            players = listOf("exo", "mpv"),
            browsers = listOf("webview", "gecko"),
        )
        val root = caps.capabilities

        // 1. Top-level envelope
        assertEquals(1, root["version"]?.jsonPrimitive?.content?.toInt())

        // 2. Host
        val host = root["host"]?.jsonObject
        assertEquals("android", host?.get("osFamily")?.jsonPrimitive?.content)
        assertEquals("tv", host?.get("deviceClass")?.jsonPrimitive?.content)

        // 3. Volume
        val volume = root["volume"]?.jsonObject
        assertEquals("system", volume?.get("scope")?.jsonPrimitive?.content)
        assertEquals(
            listOf("relative_step", "absolute", "mute_toggle", "level_report", "mute_report"),
            volume?.get("operations")?.jsonArray?.map { it.jsonPrimitive.content },
        )

        // 4. Companion hints
        assertEquals(
            listOf("android_tv_remote_v2"),
            root["companionHints"]?.jsonArray?.map { it.jsonPrimitive.content },
        )

        // 5. Player surface & engines
        val playerEngines = root["surfaces"]?.jsonObject?.get("player")?.jsonObject?.get("engines")?.jsonObject
        assertEquals(setOf("exo", "mpv"), playerEngines?.keys)

        val expectedPlayerTransport = listOf("play", "pause", "toggle", "stop", "seek_absolute", "seek_relative")
        val expectedPlayerKeys = listOf("dpad_up", "dpad_down", "dpad_left", "dpad_right", "dpad_center", "back", "home")
        val expectedPlayerSettings = listOf("loop", "engine", "speed", "scaling", "quality", "audio_boost", "subtitle_offset")
        val expectedPlayerImageTransforms = listOf("pan", "scroll", "zoom", "rotate", "anchor", "reset")
        val expectedPlayerTracks = listOf("audio_select", "subtitle_select", "subtitle_url_add", "subtitle_resource_add")
        val expectedPlayerQueue = listOf("replace", "append", "jump_index", "jump_item", "remove", "move", "clear")
        val expectedPlayerReporting = listOf("status", "context", "tracks", "playlist_status", "player_settings")

        for (engineName in listOf("exo", "mpv")) {
            val engine = playerEngines?.get(engineName)?.jsonObject
            assertEquals(expectedPlayerTransport, engine?.get("transport")?.jsonArray?.map { it.jsonPrimitive.content })
            assertEquals(expectedPlayerKeys, engine?.get("keys")?.jsonArray?.map { it.jsonPrimitive.content })
            assertEquals(expectedPlayerSettings, engine?.get("settings")?.jsonArray?.map { it.jsonPrimitive.content })
            assertEquals(expectedPlayerImageTransforms, engine?.get("imageTransforms")?.jsonArray?.map { it.jsonPrimitive.content })
            assertEquals(expectedPlayerTracks, engine?.get("tracks")?.jsonArray?.map { it.jsonPrimitive.content })
            assertEquals(expectedPlayerQueue, engine?.get("queue")?.jsonArray?.map { it.jsonPrimitive.content })
            assertEquals(expectedPlayerReporting, engine?.get("reporting")?.jsonArray?.map { it.jsonPrimitive.content })

            // Both engines implement quality and audio_boost in ExoRendererService & MpvRendererService
            val settingsList = engine?.get("settings")?.jsonArray?.map { it.jsonPrimitive.content } ?: emptyList()
            assertTrue("quality in $engineName settings", "quality" in settingsList)
            assertTrue("audio_boost in $engineName settings", "audio_boost" in settingsList)
        }

        // 6. Browser surface & engines
        val browserEngines = root["surfaces"]?.jsonObject?.get("browser")?.jsonObject?.get("engines")?.jsonObject
        assertEquals(setOf("webview", "gecko"), browserEngines?.keys)

        val expectedBrowserTransport = listOf("toggle", "seek_absolute", "seek_relative")
        val expectedBrowserKeys = listOf("dpad_up", "dpad_down", "dpad_left", "dpad_right", "dpad_center", "back", "home", "key_enter")
        val expectedBrowserText = listOf("replace", "enter")
        val expectedBrowserOperations = listOf("launch", "forward", "reload", "cycle_source", "unmute", "fullscreen", "manage_user_scripts", "manage_user_agent")
        val expectedBrowserReporting = listOf("context", "status", "fullscreen")

        for (engineName in listOf("webview", "gecko")) {
            val engine = browserEngines?.get(engineName)?.jsonObject
            assertEquals(expectedBrowserTransport, engine?.get("transport")?.jsonArray?.map { it.jsonPrimitive.content })
            assertEquals(expectedBrowserKeys, engine?.get("keys")?.jsonArray?.map { it.jsonPrimitive.content })
            assertEquals(expectedBrowserText, engine?.get("text")?.jsonArray?.map { it.jsonPrimitive.content })
            assertEquals(expectedBrowserOperations, engine?.get("browser")?.jsonArray?.map { it.jsonPrimitive.content })
            assertEquals(expectedBrowserReporting, engine?.get("reporting")?.jsonArray?.map { it.jsonPrimitive.content })
        }

        // Both engines implement bounded page pinch-zoom through the shared controller
        val webviewPointer = browserEngines?.get("webview")?.jsonObject?.get("pointer")?.jsonArray?.map { it.jsonPrimitive.content } ?: emptyList()
        val geckoPointer = browserEngines?.get("gecko")?.jsonObject?.get("pointer")?.jsonArray?.map { it.jsonPrimitive.content } ?: emptyList()
        assertEquals(listOf("move", "click", "down", "up", "scroll", "zoom"), webviewPointer)
        assertEquals(listOf("move", "click", "down", "up", "scroll", "zoom"), geckoPointer)
        assertTrue("zoom" in webviewPointer)
        assertTrue("zoom" in geckoPointer)
    }

    @Test
    fun authResponseAndDecryptedBundleCarryIdenticalDescriptor() {
        val caps = TvCapabilities(
            players = listOf("exo", "mpv"),
            browsers = listOf("webview", "gecko"),
        )
        val authObj = obj(createAuthResponseJson(
            success = true,
            certFingerprint = "test-cert",
            players = caps.players,
            browsers = caps.browsers,
            mediaKinds = caps.mediaKinds,
            screenMirrorWebRtc = caps.screenMirrorWebRtc,
            features = caps.features,
            capabilities = caps.capabilities,
        ))
        val bundleObj = obj(newCredentialBundleJson(
            token = "test-token",
            certFingerprint = "test-cert",
            caps = caps,
        ))

        // Same descriptor in both
        assertEquals(authObj["capabilities"], bundleObj["capabilities"])
        assertEquals(caps.capabilities, authObj["capabilities"])
        assertEquals(caps.capabilities, bundleObj["capabilities"])
    }

    @Test
    fun descriptorWithGeckoAbsentOmitsGeckoEngine() {
        val caps = TvCapabilities(
            players = listOf("exo", "mpv"),
            browsers = listOf("webview"),
        )
        val browserEngines = caps.capabilities["surfaces"]?.jsonObject?.get("browser")?.jsonObject?.get("engines")?.jsonObject
        assertEquals(setOf("webview"), browserEngines?.keys)
        assertFalse("gecko" in (browserEngines?.keys ?: emptySet()))

        val playerEngines = caps.capabilities["surfaces"]?.jsonObject?.get("player")?.jsonObject?.get("engines")?.jsonObject
        assertEquals(setOf("exo", "mpv"), playerEngines?.keys)
    }

    @Test
    fun descriptorWithNoBrowsersOmitsBrowserSurface() {
        val caps = TvCapabilities(
            players = listOf("exo", "mpv"),
            browsers = emptyList(),
        )
        val surfaces = caps.capabilities["surfaces"]?.jsonObject?.keys
        assertEquals(setOf("player"), surfaces)
    }

    @Test
    fun descriptorWithNoPlayersOmitsPlayerSurface() {
        val caps = TvCapabilities(
            players = emptyList(),
            browsers = listOf("webview"),
        )
        val surfaces = caps.capabilities["surfaces"]?.jsonObject?.keys
        assertEquals(setOf("browser"), surfaces)
    }

    @Test
    fun decryptedBundlePlaintextExistingFieldsByteIdenticalToOldBuilder() {
        val capsPopulated = TvCapabilities(
            players = listOf("exo", "mpv"),
            browsers = listOf("webview", "gecko"),
            mediaKinds = listOf("video", "audio", "image"),
            screenMirrorWebRtc = true,
        )
        val token = "TEST_TOKEN_12345"
        val certFingerprint = "sha256/abcdef"

        val oldPopulated = oldCredentialBundleJson(token, certFingerprint, capsPopulated)
        val newPopulated = newCredentialBundleJson(token, certFingerprint, capsPopulated)

        // Stripping the trailing ,"capabilities":{...} must result in exact byte identity
        val suffix = ",\"capabilities\":${capsPopulated.capabilities}}"
        assertTrue("new bundle ends with capabilities block", newPopulated.endsWith(suffix))
        val strippedPopulated = newPopulated.removeSuffix(suffix) + "}"
        assertEquals(oldPopulated, strippedPopulated)
        assertTrue(oldPopulated.toByteArray().contentEquals(strippedPopulated.toByteArray()))

        // Reconstructed JsonObject without capabilities retains exact keys and order
        val filteredPopulated = JsonObject(obj(newPopulated).filterKeys { it != "capabilities" }).toString()
        assertEquals(oldPopulated, filteredPopulated)

        // Verify with empty arrays (old senders rely on empty arrays rather than absent keys)
        val capsEmpty = TvCapabilities(
            players = emptyList(),
            browsers = emptyList(),
            mediaKinds = emptyList(),
            screenMirrorWebRtc = false,
        )
        val oldEmpty = oldCredentialBundleJson(token, null, capsEmpty)
        val newEmpty = newCredentialBundleJson(token, null, capsEmpty)

        val suffixEmpty = ",\"capabilities\":${capsEmpty.capabilities}}"
        assertTrue("new empty bundle ends with capabilities block", newEmpty.endsWith(suffixEmpty))
        val strippedEmpty = newEmpty.removeSuffix(suffixEmpty) + "}"
        assertEquals(oldEmpty, strippedEmpty)
        assertTrue(oldEmpty.toByteArray().contentEquals(strippedEmpty.toByteArray()))

        // Explicitly assert empty arrays are preserved in the JSON
        assertTrue(newEmpty.contains("\"players\":[]"))
        assertTrue(newEmpty.contains("\"browsers\":[]"))
        assertTrue(newEmpty.contains("\"mediaKinds\":[]"))
        assertFalse(newEmpty.contains("\"screenMirrorWebRtc\""))
    }

    @Test
    fun authResponseOmitsCapabilitiesWhenNull() {
        val authWithout = createAuthResponseJson(
            success = true,
            certFingerprint = "fp",
            players = listOf("exo", "mpv"),
            browsers = listOf("webview"),
            mediaKinds = listOf("video", "audio", "image"),
            screenMirrorWebRtc = true,
            features = listOf("queue_crud_v1"),
            capabilities = null,
        )
        val authObj = obj(authWithout)
        assertNull(authObj["capabilities"])
        assertEquals("auth_response", authObj["type"]?.jsonPrimitive?.content)
        assertEquals(true, authObj["success"]?.jsonPrimitive?.content?.toBoolean())
        assertEquals("fp", authObj["certFingerprint"]?.jsonPrimitive?.content)
    }

    @Test
    fun contextFrameCarriesEngineForPlayerAndBrowser() {
        val exoObj = obj(createContextJson("player", "exo"))
        assertEquals("context", exoObj["type"]?.jsonPrimitive?.content)
        assertEquals("player", exoObj["active"]?.jsonPrimitive?.content)
        assertEquals("exo", exoObj["engine"]?.jsonPrimitive?.content)
        assertEquals(setOf("type", "active", "engine"), exoObj.keys)

        val mpvObj = obj(createContextJson("player", "mpv"))
        assertEquals("context", mpvObj["type"]?.jsonPrimitive?.content)
        assertEquals("player", mpvObj["active"]?.jsonPrimitive?.content)
        assertEquals("mpv", mpvObj["engine"]?.jsonPrimitive?.content)
        assertEquals(setOf("type", "active", "engine"), mpvObj.keys)

        val webviewObj = obj(createContextJson("browser", "webview"))
        assertEquals("context", webviewObj["type"]?.jsonPrimitive?.content)
        assertEquals("browser", webviewObj["active"]?.jsonPrimitive?.content)
        assertEquals("webview", webviewObj["engine"]?.jsonPrimitive?.content)
        assertEquals(setOf("type", "active", "engine"), webviewObj.keys)

        val geckoObj = obj(createContextJson("browser", "gecko"))
        assertEquals("context", geckoObj["type"]?.jsonPrimitive?.content)
        assertEquals("browser", geckoObj["active"]?.jsonPrimitive?.content)
        assertEquals("gecko", geckoObj["engine"]?.jsonPrimitive?.content)
        assertEquals(setOf("type", "active", "engine"), geckoObj.keys)
    }

    @Test
    fun contextFrameOmitsEngineForIdleScreenMirrorAndUnknown() {
        val idleObj = obj(createContextJson("idle"))
        assertEquals("context", idleObj["type"]?.jsonPrimitive?.content)
        assertEquals("idle", idleObj["active"]?.jsonPrimitive?.content)
        assertNull(idleObj["engine"])
        assertEquals(setOf("type", "active"), idleObj.keys)

        val screenMirrorObj = obj(createContextJson("screen_mirror"))
        assertEquals("context", screenMirrorObj["type"]?.jsonPrimitive?.content)
        assertEquals("screen_mirror", screenMirrorObj["active"]?.jsonPrimitive?.content)
        assertNull(screenMirrorObj["engine"])
        assertEquals(setOf("type", "active"), screenMirrorObj.keys)

        val playerNullObj = obj(createContextJson("player", null))
        assertEquals("context", playerNullObj["type"]?.jsonPrimitive?.content)
        assertEquals("player", playerNullObj["active"]?.jsonPrimitive?.content)
        assertNull(playerNullObj["engine"])
        assertEquals(setOf("type", "active"), playerNullObj.keys)
    }

    @Test
    fun serverServiceWireEngineAndContextMappingMatchesDescriptor() {
        // Player context
        assertEquals("player", ServerService.wireContext("player"))
        assertEquals("exo", ServerService.wireEngine("player", "exo"))
        assertEquals("mpv", ServerService.wireEngine("player", "mpv"))
        assertNull(ServerService.wireEngine("player", null))
        assertNull(ServerService.wireEngine("player", "unknown"))

        // Browser context: internal WebView
        assertEquals("browser", ServerService.wireContext("browser"))
        assertEquals("webview", ServerService.wireEngine("browser"))

        // Browser context: external GeckoView plugin maps to wire active "browser" and engine "gecko"
        assertEquals("browser", ServerService.wireContext("browser_external"))
        assertEquals("gecko", ServerService.wireEngine("browser_external"))

        // Idle & Screen Mirror omit engine
        assertEquals("idle", ServerService.wireContext("idle"))
        assertNull(ServerService.wireEngine("idle"))
        assertNull(ServerService.wireEngine("idle", "exo"))

        assertEquals("screen_mirror", ServerService.wireContext("screen_mirror"))
        assertNull(ServerService.wireEngine("screen_mirror"))
        assertNull(ServerService.wireEngine("screen_mirror", "exo"))
    }

    @Test
    fun engineSwitchEmitsUpdatedContextWithoutContextChange() {
        // Player renderer switch (e.g. exo -> mpv)
        val playerExoFrame = obj(createContextJson(
            ServerService.wireContext("player"),
            ServerService.wireEngine("player", "exo"),
        ))
        assertEquals("player", playerExoFrame["active"]?.jsonPrimitive?.content)
        assertEquals("exo", playerExoFrame["engine"]?.jsonPrimitive?.content)

        val playerMpvFrame = obj(createContextJson(
            ServerService.wireContext("player"),
            ServerService.wireEngine("player", "mpv"),
        ))
        assertEquals("player", playerMpvFrame["active"]?.jsonPrimitive?.content)
        assertEquals("mpv", playerMpvFrame["engine"]?.jsonPrimitive?.content)
        assertEquals(playerExoFrame["active"], playerMpvFrame["active"])

        // Browser engine switch (e.g. webview -> gecko)
        val browserWebviewFrame = obj(createContextJson(
            ServerService.wireContext("browser"),
            ServerService.wireEngine("browser"),
        ))
        assertEquals("browser", browserWebviewFrame["active"]?.jsonPrimitive?.content)
        assertEquals("webview", browserWebviewFrame["engine"]?.jsonPrimitive?.content)

        val browserGeckoFrame = obj(createContextJson(
            ServerService.wireContext("browser_external"),
            ServerService.wireEngine("browser_external"),
        ))
        assertEquals("browser", browserGeckoFrame["active"]?.jsonPrimitive?.content)
        assertEquals("gecko", browserGeckoFrame["engine"]?.jsonPrimitive?.content)
        assertEquals(browserWebviewFrame["active"], browserGeckoFrame["active"])
    }

    @Test
    fun contextQueryReplyIncludesActiveEngine() {
        val query = parseIncomingMessage(createContextQueryJson())
        assertTrue(query is com.playbridge.shared.protocol.IncomingMessage.ContextQuery)

        // Player with exo
        val playerReply = obj(createContextJson(
            ServerService.wireContext("player"),
            ServerService.wireEngine("player", "exo"),
        ))
        assertEquals("context", playerReply["type"]?.jsonPrimitive?.content)
        assertEquals("player", playerReply["active"]?.jsonPrimitive?.content)
        assertEquals("exo", playerReply["engine"]?.jsonPrimitive?.content)

        // Browser with gecko
        val geckoReply = obj(createContextJson(
            ServerService.wireContext("browser_external"),
            ServerService.wireEngine("browser_external"),
        ))
        assertEquals("context", geckoReply["type"]?.jsonPrimitive?.content)
        assertEquals("browser", geckoReply["active"]?.jsonPrimitive?.content)
        assertEquals("gecko", geckoReply["engine"]?.jsonPrimitive?.content)

        // Idle
        val idleReply = obj(createContextJson(
            ServerService.wireContext("idle"),
            ServerService.wireEngine("idle"),
        ))
        assertEquals("context", idleReply["type"]?.jsonPrimitive?.content)
        assertEquals("idle", idleReply["active"]?.jsonPrimitive?.content)
        assertNull(idleReply["engine"])
    }
}

