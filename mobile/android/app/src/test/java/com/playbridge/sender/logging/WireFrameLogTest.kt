package com.playbridge.sender.logging

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WireFrameLogTest {
    @Test fun liveDescriptorNeverAppearsInDebugOrReleaseLogs() {
        val frame = """{"type":"receiver_capabilities","revision":1,"capabilities":{"version":1,"surfaces":{"PRIVATE_DESCRIPTOR":{"engines":{}}}}}"""
        assertEquals(WireFrameLog.summary(frame), WireFrameLog.redact(frame))
        assertFalse(WireFrameLog.redact(frame).contains("PRIVATE_DESCRIPTOR"))
        assertFalse(WireFrameLog.summary(frame).contains("PRIVATE_DESCRIPTOR"))
    }
    @Test fun debugFramesNeverRetainTypedTextOrPinsOrProtectedCredentials() {
        val frames = listOf(
            """{"type":"command","action":"remote","payload":{"key":"text:U1lOVEhFVElDX1RFWFQ="}}""",
            """{"type":"auth_response","success":true,"certFingerprint":"sha256/TEST_PIN","pin":"TEST_PIN","token":"TEST_TOKEN"}""",
            """{"type":"pairing_approved","nonce":"TEST_NONCE","ciphertext":"TEST_CIPHERTEXT"}""",
            """{"type":"user_agent","name":"TEST_NAME","value":"TEST_TYPED_AGENT"}""",
            """{"type":"user_script","name":"TEST_NAME","content":"TEST_TYPED_SCRIPT"}""",
        )
        for (frame in frames) {
            val redacted = WireFrameLog.redact(frame)
            assertFalse(redacted.contains("TEST_"))
            assertFalse(redacted.contains("U1lOVEhFVElDX1RFWFQ="))
        }
    }

    @Test fun untrustedEnvelopeTypeCannotInjectContentIntoLogs() {
        val frame = """{"type":"TEST_TYPED_SECRET"}"""
        assertEquals("type=unknown bytes=${frame.toByteArray(Charsets.UTF_8).size}", WireFrameLog.summary(frame))
    }
    @Test fun authLoggingOmitsDescriptorAndCredentialsEvenInDebugFrames() {
        val frame = """{"type":"auth_response","success":true,"token":"TEST_SECRET","capabilities":{"version":1,"surfaces":{"PRIVATE_SURFACE":{"engines":{}}}}}"""
        assertFalse(WireFrameLog.summary(frame).contains("PRIVATE_SURFACE"))
        assertFalse(WireFrameLog.redact(frame).contains("PRIVATE_SURFACE"))
        assertFalse(WireFrameLog.redact(frame).contains("TEST_SECRET"))
        assertTrue(WireFrameLog.redact(frame).contains("<v1, 1 surfaces>"))
    }

    @Test fun summaryKeepsTheEnvelopeTypeAndDropsThePayload() {
        val frame = """{"type":"status","title":"Secret Movie","position":5000}"""
        val summary = WireFrameLog.summary(frame)
        assertEquals("type=status bytes=${frame.toByteArray(Charsets.UTF_8).size}", summary)
        assertFalse(summary.contains("Secret"))
        assertFalse(summary.contains("5000"))
    }

    @Test fun summaryCountsUtf8BytesAndLabelsAFrameWithNoType() {
        val frame = """{"title":"映画"}"""
        assertEquals("type=unknown bytes=${frame.toByteArray(Charsets.UTF_8).size}", WireFrameLog.summary(frame))
        assertFalse(WireFrameLog.summary(frame).contains("映画"))
    }

    @Test fun redactDropsStreamQueriesAndAuthFields() {
        val frame = """{"type":"command","action":"playlist","payload":{"items":[{"url":"https://cdn.example/v.m3u8?token=secret&sig=abc","headers":{"Cookie":"sid=user-secret","Authorization":"Bearer user-secret","Referer":"https://videos.example/watch?v=1"}}]}}"""
        val redacted = WireFrameLog.redact(frame)
        assertFalse(redacted.contains("secret"))
        assertFalse(redacted.contains("sid=user-secret"))
        assertFalse(redacted.contains("Bearer user-secret"))
        assertFalse(redacted.contains("v.m3u8"))
        assertFalse(redacted.contains("videos.example"))
        assertTrue(redacted.contains("command"))
    }

    @Test fun releaseOutputIsUnchangedEvenForUsefulAndSensitiveFrames() {
        val frames = listOf(
            """{"type":"status","state":"playing","position":5000,"duration":9000}""",
            """{"type":"auth_response","success":true,"token":"secret"}""",
            """{"type":"context","active":"browser","engine":"gecko"}""",
            """{"type":"unknown","text":"secret"}""",
            """{"type":"status","broken": """,
        )
        frames.forEach { frame ->
            assertEquals(WireFrameLog.summary(frame), WireFrameLog.format(frame, debug = false))
        }
    }

    @Test fun debugRetainsStatusAndContextWithoutTitlesOrRawIdentifiers() {
        val status = WireFrameLog.format("""{"type":"status","state":"playing","position":5000,"duration":9000,"fullscreen":true,"title":"PRIVATE_TITLE","mediaKind":"video","playbackId":"PRIVATE_PLAYBACK"}""", true)
        assertTrue(status.contains("\"state\":\"playing\""))
        assertTrue(status.contains("\"position\":5000"))
        assertTrue(status.contains("\"duration\":9000"))
        assertTrue(status.contains("\"fullscreen\":true"))
        assertTrue(status.contains("\"mediaKind\":\"video\""))
        assertFalse(status.contains("PRIVATE_"))
        val context = WireFrameLog.redact("""{"type":"context","active":"browser","engine":"gecko"}""")
        assertTrue(context.contains("\"active\":\"browser\""))
        assertTrue(context.contains("\"engine\":\"gecko\""))
    }

    @Test fun debugRetainsSettingsAndScopedVolume() {
        val settings = WireFrameLog.redact("""{"type":"player_settings","speed":1.5,"scaling":"Fit","audioBoost":false,"subtitleOffsetMs":-50,"engine":"exo","qualityMaxHeight":1080,"currentVideoHeight":720,"isLive":false,"isSeekable":true,"loop":true,"speedAvailable":true,"scalingAvailable":true,"audioBoostAvailable":false,"qualityAvailable":true,"subtitleOffsetAvailable":false,"engineAvailable":true,"loopAvailable":true}""")
        assertTrue(settings.contains("\"speed\":1.5"))
        assertTrue(settings.contains("\"scaling\":\"Fit\""))
        assertTrue(settings.contains("\"subtitleOffsetMs\":-50"))
        assertTrue(settings.contains("\"engine\":\"exo\""))
        assertTrue(settings.contains("\"loopAvailable\":true"))
        val volume = WireFrameLog.redact("""{"type":"volume_state","scope":"app","level":0.35,"muted":false}""")
        assertTrue(volume.contains("\"scope\":\"app\""))
        assertTrue(volume.contains("\"level\":0.35"))
        assertTrue(volume.contains("\"muted\":false"))
    }

    @Test fun debugRetainsNavigationControlsAndNumericArguments() {
        val cases = mapOf(
            """{"type":"command","action":"remote","payload":{"key":"dpad_up"}}""" to "\"key\":\"dpad_up\"",
            """{"type":"command","action":"control","payload":{"command":"pause"}}""" to "\"command\":\"pause\"",
            """{"type":"command","action":"control","payload":{"command":"seek_to:12345"}}""" to "\"value\":12345",
            """{"type":"command","action":"control","payload":{"command":"speed:1.5"}}""" to "\"value\":1.5",
            """{"type":"command","action":"control","payload":{"command":"switch_player:mpv"}}""" to "\"value\":\"mpv\"",
            """{"type":"command","action":"browser_control","payload":{"action":"toggle_play"}}""" to "\"action\":\"toggle_play\"",
            """{"type":"command","action":"volume","payload":{"scope":"app","level":0}}""" to "\"level\":0",
            """{"type":"command","action":"mouse","payload":{"event":"move","dx":1.5,"dy":-2}}""" to "\"dx\":1.5",
        )
        cases.forEach { (frame, expected) -> assertTrue(WireFrameLog.redact(frame), WireFrameLog.redact(frame).contains(expected)) }
    }

    @Test fun debugRetainsPlaylistCountsIndexesAndOpaqueStableItemTags() {
        val frame = """{"type":"playlist_status","currentIndex":1,"totalCount":2,"queueRevision":7,"playbackId":"PRIVATE_PLAYBACK","currentItemId":"PRIVATE_ITEM","items":[{"index":1,"title":"PRIVATE_TITLE","itemId":"PRIVATE_ITEM","mediaKind":"video","season":2,"episode":3}]}"""
        val result = WireFrameLog.redact(frame)
        assertTrue(result.contains("\"currentIndex\":1"))
        assertTrue(result.contains("\"totalCount\":2"))
        assertTrue(result.contains("\"queueRevision\":7"))
        assertTrue(result.contains("\"count\":1"))
        assertTrue(result.contains("\"index\":1"))
        assertFalse(result.contains("PRIVATE_"))
        val obj = kotlinx.serialization.json.Json.parseToJsonElement(result.substringAfter(" frame=")) as kotlinx.serialization.json.JsonObject
        val items = obj["items"] as kotlinx.serialization.json.JsonObject
        val item = (items["items"] as kotlinx.serialization.json.JsonArray)[0] as kotlinx.serialization.json.JsonObject
        assertEquals(obj["currentItemId"], item["itemId"])
        val jump = WireFrameLog.redact("""{"type":"command","action":"playlist_jump","payload":{"index":1,"itemId":"PRIVATE_ITEM","ifPlaybackId":"PRIVATE_PLAYBACK"}}""")
        assertTrue(jump.contains("\"index\":1"))
        assertFalse(jump.contains("PRIVATE_"))
    }

    @Test fun debugRetainsTrackSelectionsAndKnownErrorCodes() {
        val tracks = WireFrameLog.redact("""{"type":"tracks","audio":[{"id":"PRIVATE_TRACK","name":"PRIVATE_LANGUAGE","selected":true}],"subtitle":[]}""")
        assertTrue(tracks.contains("\"selected\":true"))
        assertTrue(tracks.contains("<id:"))
        assertFalse(tracks.contains("PRIVATE_"))
        val result = WireFrameLog.redact("""{"type":"command_result","requestId":"PRIVATE_REQUEST","ok":false,"error":"stale_playback","message":"PRIVATE_MESSAGE"}""")
        assertTrue(result.contains("\"ok\":false"))
        assertTrue(result.contains("\"error\":\"stale_playback\""))
        assertFalse(result.contains("PRIVATE_"))
    }

    @Test fun sensitiveWireExamplesNeverAppearInDebugOutput() {
        val secret = "PRIVATE_SECRET"
        val encoded = "U1lOVEhFVElDX1RFWFQ="
        val token = "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxMjM0NTY3ODkwIn0.PRIVATE_SECRET"
        val pin = "sha256/abcdefghijklmNOPQRSTUVWXYZ0123456789abcdefghi="
        val frames = listOf(
            """{"type":"command","action":"remote","payload":{"key":"text:$encoded","text":"$secret"}}""",
            """{"type":"auth","token":"$token"}""",
            """{"type":"auth_response","success":true,"certFingerprint":"$pin","token":"$token","pin":"$pin","spki":"$pin","credentials":{"token":"$token"},"credentialBundle":{"password":"$secret"},"capabilities":{"version":1,"surfaces":{"$secret":{"engines":{"$secret":{}}}}}}""",
            """{"type":"pairing_commit","commit":"$secret","deviceName":"$secret","deviceUUID":"$secret"}""",
            """{"type":"pairing_challenge","tvEphPub":"$secret","nonceT":"$secret","sas":"$secret"}""",
            """{"type":"pairing_reveal","senderEphPub":"$secret","nonceS":"$secret","reveal":"$secret"}""",
            """{"type":"pairing_confirmation","mac":"$secret"}""",
            """{"type":"pairing_approved","nonce":"$secret","ciphertext":"$secret"}""",
            """{"type":"user_script","name":"$secret","content":"console.log('$secret')"}""",
            """{"type":"user_scripts","names":["$secret"]}""",
            """{"type":"user_agent","name":"$secret","value":"$secret","save":true}""",
            """{"type":"tv_logs","text":"$secret","contents":"$secret"}""",
            """{"type":"command","action":"browser","payload":{"url":"https://user:password@example.com/$secret?token=$token","browserMode":"gecko"}}""",
            """{"type":"command","action":"play","payload":{"url":"https://example.com/$secret?token=$token","headers":{"Authorization":"Bearer $token","Cookie":"$secret"},"title":"$secret"}}""",
            """{"type":"command","action":"control","payload":{"command":"add_subtitle:https://example.com/$secret?token=$token"}}""",
            """{"type":"command","action":"control","payload":{"command":"add_subtitle","subtitleResource":{"url":"https://example.com/$secret?token=$token","headers":{"Authorization":"Bearer $token"}}}}""",
            """{"type":"command","action":"control","payload":{"command":"audio_track:$secret"}}""",
        )
        frames.forEach { frame ->
            val result = WireFrameLog.redact(frame)
            listOf(secret, encoded, token, pin, "https://", "password", "Bearer").forEach {
                assertFalse("Leaked $it in $result", result.contains(it))
            }
        }
    }

    @Test fun unknownTypesFieldsAndCommandsFallBackToSummary() {
        val frames = listOf(
            """{"type":"PRIVATE_TYPE","position":123,"text":"PRIVATE_SECRET"}""",
            """{"type":"status","position":123,"PRIVATE_FIELD":"PRIVATE_SECRET"}""",
            """{"type":"command","action":"remote","payload":{"key":"dpad_up","PRIVATE_FIELD":"PRIVATE_SECRET"}}""",
            """{"type":"playlist_status","items":[{"index":0,"PRIVATE_FIELD":"PRIVATE_SECRET"}]}""",
            """{"type":"command","action":"PRIVATE_ACTION","payload":{"text":"PRIVATE_SECRET"}}""",
        )
        frames.forEach { assertEquals(WireFrameLog.summary(it), WireFrameLog.redact(it)) }
    }

    @Test fun unexpectedValuesInAllowlistedFieldsCannotLeakText() {
        val frames = listOf(
            """{"type":"status","state":"PRIVATE_SECRET","position":"PRIVATE_SECRET","duration":{"text":"PRIVATE_SECRET"}}""",
            """{"type":"context","active":"PRIVATE_SECRET","engine":"PRIVATE_SECRET"}""",
            """{"type":"volume_state","scope":"PRIVATE_SECRET","level":"PRIVATE_SECRET","muted":"PRIVATE_SECRET"}""",
            """{"type":"command","action":"control","payload":{"command":"seek_to:\"PRIVATE_SECRET\""}}""",
            """{"type":"command","action":"control","payload":{"command":"PRIVATE_SECRET:PRIVATE_SECRET"}}""",
            """{"type":"command_result","error":"PRIVATE_SECRET","ok":"PRIVATE_SECRET"}""",
        )
        frames.forEach { assertFalse(WireFrameLog.redact(it).contains("PRIVATE_SECRET")) }
    }

    @Test fun malformedHugeDeepAndOversizedOutputAreBoundedAndNeverThrow() {
        val frames = listOf("", "null", "[]", "{", """{"type":"status","position": """,
            """{"type":"status","title":"""" + "PRIVATE_SECRET".repeat(10000) + "\"}",
            "[".repeat(10000) + "0" + "]".repeat(10000))
        frames.forEach {
            assertEquals(WireFrameLog.summary(it), WireFrameLog.redact(it))
            assertTrue(WireFrameLog.redact(it).length <= WireFrameLog.MAX_DEBUG_LENGTH)
        }
        val item = """{"index":1,"season":2,"episode":3,"itemId":"PRIVATE_ITEM","title":"PRIVATE_TITLE","mediaKind":"video","url":"https://example.com/PRIVATE_SECRET","headers":{"Cookie":"PRIVATE_SECRET"},"subtitles":[],"subtitleResources":[],"progressIdentity":{},"visualMetadata":{},"contentType":"PRIVATE_SECRET"}"""
        val frame = """{"type":"playlist_status","items":[${List(20) { item }.joinToString(",")}],"totalCount":20}"""
        val result = WireFrameLog.redact(frame)
        assertTrue(result.length <= WireFrameLog.MAX_DEBUG_LENGTH)
        assertFalse(result.contains("PRIVATE_"))
        assertFalse(result.contains("https://"))
        assertTrue(result.contains("output limit"))
    }
    @Test fun unknownFieldsInEntriesBeyondThePreviewStillFallBack() {
        val items = List(8) { """{"index":1,"title":"PRIVATE_TITLE"}""" } +
            """{"index":9,"PRIVATE_FIELD":"PRIVATE_SECRET"}"""
        val frame = """{"type":"playlist_status","items":[${items.joinToString(",")}]}"""
        assertEquals(WireFrameLog.summary(frame), WireFrameLog.redact(frame))
    }

    @Test fun bracesAndEscapesInsideRedactedStringsDoNotTriggerDepthLimit() {
        val title = kotlinx.serialization.json.JsonPrimitive("[".repeat(20) + "\\\"PRIVATE_SECRET" + "]".repeat(20))
        val frame = """{"type":"status","state":"paused","position":1,"title":$title}"""
        val result = WireFrameLog.redact(frame)
        assertTrue(result.contains("\"state\":\"paused\""))
        assertFalse(result.contains("PRIVATE_SECRET"))
    }
    @Test fun receiverPlaybackFailureTextIsNeverCopiedToWireLogs() {
        val frame = """{"type":"status","state":"error","position":0,"duration":0,"error":"PRIVATE_REASON https://example.test?token=PRIVATE_TOKEN"}"""
        assertEquals(WireFrameLog.summary(frame), WireFrameLog.redact(frame))
        assertFalse(WireFrameLog.redact(frame).contains("PRIVATE_"))
        assertFalse(WireFrameLog.redact(frame).contains("https://"))
    }

}
