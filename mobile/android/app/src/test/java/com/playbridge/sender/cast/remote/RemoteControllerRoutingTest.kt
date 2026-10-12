package com.playbridge.sender.cast.remote

import com.playbridge.sender.cast.Capability
import com.playbridge.sender.cast.TargetKind
import com.playbridge.sender.cast.PlaybackState
import com.playbridge.sender.connection.QueueCommandResult
import java.util.Base64
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import playbridge.SubtitleResource

@OptIn(ExperimentalCoroutinesApi::class)
class RemoteControllerRoutingTest {
    private fun control(value: String) = """{"type":"command","action":"control","payload":{"command":"$value"}}"""
    private fun key(value: String) = """{"type":"command","action":"remote","payload":{"key":"$value"}}"""
    private fun browser(value: String) = """{"type":"command","action":"browser_control","payload":{"action":"$value"}}"""

    @Test fun allNativeProfilesUseCommonMediaFixturesAndActualQueueIndices() = runTest {
        for (profile in RemoteProfile.entries.filter { it != RemoteProfile.External }) {
            val transport = FakeNativeRemoteTransport()
            val controller = testController(profile, transport, backgroundScope)
            assertEquals(RemoteCommandResult.Submitted, controller.execute(RemoteAction.SetPlaying(false)))
            controller.execute(RemoteAction.SetPlaying(true))
            controller.execute(RemoteAction.SeekTo(200_000L))
            controller.execute(RemoteAction.SeekBy(-30_000L))
            controller.execute(RemoteAction.JumpToEpisode(8))
            controller.execute(RemoteAction.StopPlayback)
            assertEquals(listOf(control("pause"), control("play"), control("seek_to:120000"), control("seek_to:0"), """{"type":"command","action":"playlist_jump","payload":{"index":8}}""", control("stop")), transport.commands)
            assertTrue(controller.execute(RemoteAction.JumpToEpisode(7)) is RemoteCommandResult.Failed)
            assertTrue(controller.execute(RemoteAction.JumpToEpisode(8, "stale")) is RemoteCommandResult.Failed)
            assertEquals(6, transport.commands.size)
        }
    }
    @Test fun androidPlayerKeysTracksAndSettingsUseExistingStrings() = runTest {
        val transport = FakeNativeRemoteTransport()
        val controller = testController(RemoteProfile.AndroidTv, transport, backgroundScope)
        val fixtures = listOf(
            RemoteAction.Key(RemoteKey.Back) to key("back"), RemoteAction.Key(RemoteKey.Up) to key("dpad_up"),
            RemoteAction.AdjustVolume(-1) to key("volume_down"), RemoteAction.SelectAudio("a") to control("audio_track:a"),
            RemoteAction.SelectSubtitle("off") to control("sub_track:off"), RemoteAction.SetVideoQuality(null) to control("video_quality:auto"),
            RemoteAction.SetVideoQuality(720) to control("video_quality:720"), RemoteAction.SetSpeed(1.5f) to control("speed:1.5"),
            RemoteAction.SetScaling(ScalingMode.Zoom) to control("scaling:Zoom"), RemoteAction.ToggleAudioBoost to control("audio_boost"),
            RemoteAction.AdjustSubtitleOffset(-500L) to control("sub_offset:-500"), RemoteAction.SwitchEngine("mpv") to control("switch_player:mpv"),
            RemoteAction.SetLoop(true) to control("loop_on"), RemoteAction.SetLoop(false) to control("loop_off"),
        )
        for ((action, _) in fixtures) assertEquals(RemoteCommandResult.Submitted, controller.execute(action))
        assertEquals(fixtures.map { it.second }, transport.commands)
        for (action in listOf(RemoteAction.Key(RemoteKey.Home), RemoteAction.ReplaceText("abc"), RemoteAction.PointerClick, RemoteAction.SetVolume(50), RemoteAction.SwitchEngine("missing"))) {
            assertTrue(controller.execute(action) is RemoteCommandResult.Unsupported)
        }
        assertEquals(fixtures.size, transport.commands.size)
    }
    @Test fun browserRoutesToggleSeekTextNavigationAndManagersToExactFixtures() = runTest {
        val transport = FakeNativeRemoteTransport(testSnapshot(RemoteContext.Browser))
        val controller = testController(RemoteProfile.AndroidTv, transport, backgroundScope)
        val text = "héllo 👋"
        val encoded = Base64.getEncoder().encodeToString(text.toByteArray(Charsets.UTF_8))
        val fixtures = listOf(
            RemoteAction.SetPlaying(false) to browser("toggle_play"), RemoteAction.SeekTo(25_000L) to browser("seek_to:25000"),
            RemoteAction.Key(RemoteKey.Home) to key("home"), RemoteAction.ReplaceText(text) to key("text:$encoded"),
            RemoteAction.Key(RemoteKey.Enter) to key("key_enter"), RemoteAction.Browser(BrowserAction.Forward) to browser("forward"),
            RemoteAction.Browser(BrowserAction.Reload) to browser("refresh"), RemoteAction.Browser(BrowserAction.CycleSource) to browser("video_target_cycle"),
            RemoteAction.Browser(BrowserAction.Unmute) to browser("video_unmute"), RemoteAction.SetBrowserFullscreen(true) to browser("maximize_video"),
            RemoteAction.SetBrowserFullscreen(false) to browser("restore_video"), RemoteAction.ManageUserScripts to """{"type":"user_script_query"}""",
            RemoteAction.ManageUserAgent to """{"type":"user_agent_query"}""", RemoteAction.Resync to """{"type":"command","action":"context_query"}""",
        )
        for ((action, _) in fixtures) assertEquals(RemoteCommandResult.Submitted, controller.execute(action))
        assertEquals(fixtures.map { it.second }, transport.commands)
        assertTrue(controller.execute(RemoteAction.SetSpeed(2f)) is RemoteCommandResult.Unsupported)
        assertTrue(controller.execute(RemoteAction.DeleteText) is RemoteCommandResult.Unsupported)
    }
    @Test fun browserPointerDelegatesInOrderAndHeldPointerIsReleasedOnceOnContextLoss() = runTest {
        val transport = FakeNativeRemoteTransport(testSnapshot(RemoteContext.Browser))
        val controller = testController(RemoteProfile.AndroidTv, transport, backgroundScope)
        controller.execute(RemoteAction.PointerMove(2f, 3f))
        controller.execute(RemoteAction.PointerScroll(1f, -1f))
        controller.execute(RemoteAction.PointerDown)
        transport.snapshot.value = testSnapshot(RemoteContext.Idle)
        runCurrent()
        controller.execute(RemoteAction.PointerGestureEnd)
        controller.execute(RemoteAction.PointerGestureEnd)
        assertEquals(listOf("move", "scroll", "down", "up", "final-flush"), transport.ordering)
        assertTrue(controller.execute(RemoteAction.PointerMove(Float.NaN, 0f)) is RemoteCommandResult.Unsupported)
        transport.snapshot.value = testSnapshot(RemoteContext.Browser)
        runCurrent()
        controller.execute(RemoteAction.PointerDown)
        controller.execute(RemoteAction.PointerGestureEnd)
        controller.execute(RemoteAction.PointerUp) // the second pointer detector may finish afterward
        controller.execute(RemoteAction.PointerGestureEnd)
        assertEquals(listOf("move", "scroll", "down", "up", "final-flush", "down", "up", "final-flush"), transport.ordering)
    }
    @Test fun appleHasRestrictedKeysAndNoAndroidSettingsVolumeOrPointer() = runTest {
        val transport = FakeNativeRemoteTransport()
        val controller = testController(RemoteProfile.AppleTv, transport, backgroundScope)
        for (k in listOf(RemoteKey.Left, RemoteKey.Right, RemoteKey.Ok)) controller.execute(RemoteAction.Key(k))
        controller.execute(RemoteAction.SelectAudio("1")); controller.execute(RemoteAction.SelectSubtitle("off"))
        controller.execute(RemoteAction.SetLoop(true)); controller.execute(RemoteAction.SwitchEngine("mpv"))
        assertEquals(listOf(key("dpad_left"), key("dpad_right"), key("dpad_center"), control("audio_track:1"), control("sub_track:off"), control("loop_on"), control("switch_player:mpv")), transport.commands)
        for (action in listOf(RemoteAction.Key(RemoteKey.Back), RemoteAction.Key(RemoteKey.Up), RemoteAction.AdjustVolume(1), RemoteAction.PointerClick, RemoteAction.SetSpeed(1.5f), RemoteAction.SetScaling(ScalingMode.Fill))) assertTrue(controller.execute(action) is RemoteCommandResult.Unsupported)
        assertEquals(7, transport.commands.size)
    }
    @Test fun desktopImageHasTransformsButNoClicksDragsTextOrPlayerSettings() = runTest {
        val transport = FakeNativeRemoteTransport(testSnapshot(kind = RemoteMediaKind.Image))
        val controller = testController(RemoteProfile.Desktop, transport, backgroundScope)
        controller.execute(RemoteAction.PointerMove(1f, 2f)); controller.execute(RemoteAction.PointerScroll(3f, 4f))
        controller.execute(RemoteAction.ImageZoom(1.2f)); controller.execute(RemoteAction.ImageRotate(90f))
        controller.execute(RemoteAction.ImageAnchor(.3f, .4f)); controller.execute(RemoteAction.ResetImageTransform)
        controller.execute(RemoteAction.PointerGestureEnd); controller.execute(RemoteAction.AdjustVolume(1))
        assertEquals(listOf(Triple("move", 1f, 2f), Triple("scroll", 3f, 4f), Triple("zoom", 1.2f, 0f), Triple("rotate", 90f, 0f), Triple("transform_anchor", .3f, .4f), Triple("reset", 0f, 0f)), transport.pointers)
        assertEquals("final-flush", transport.ordering[6])
        assertEquals(listOf(key("volume_up")), transport.commands)
        for (action in listOf(RemoteAction.PointerClick, RemoteAction.PointerDown, RemoteAction.ReplaceText("text"), RemoteAction.Key(RemoteKey.Ok), RemoteAction.SetLoop(true), RemoteAction.SeekTo(100))) assertTrue(controller.execute(action) is RemoteCommandResult.Unsupported)
    }
    @Test fun cliAndUnknownRejectAllUnprovenInputSettingsAndTracks() = runTest {
        for (profile in listOf(RemoteProfile.Cli, RemoteProfile.Unknown)) {
            val transport = FakeNativeRemoteTransport()
            val controller = testController(profile, transport, backgroundScope)
            val unsupported = listOf(RemoteAction.Key(RemoteKey.Ok), RemoteAction.AdjustVolume(1), RemoteAction.ReplaceText("x"), RemoteAction.PointerMove(1f, 1f), RemoteAction.SelectAudio("a"), RemoteAction.SelectSubtitle("off"), RemoteAction.SetLoop(true), RemoteAction.SetSpeed(1.5f), RemoteAction.ManageUserAgent)
            for (action in unsupported) assertTrue("$profile/$action", controller.execute(action) is RemoteCommandResult.Unsupported)
            assertTrue(transport.commands.isEmpty() && transport.pointers.isEmpty())
        }
    }
    @Test fun geckoSupportsCursorButRejectsWebviewZoomAndNativeSettings() = runTest {
        val raw = testSnapshot(RemoteContext.Browser)
        val transport = FakeNativeRemoteTransport(raw.copy(state = raw.state.copy(browser = RemoteBrowserState(RemoteBrowserEngine.Gecko))))
        val controller = testController(RemoteProfile.AndroidTv, transport, backgroundScope)
        assertEquals(RemoteCommandResult.Submitted, controller.execute(RemoteAction.PointerClick))
        assertTrue(controller.execute(RemoteAction.ImageZoom(1.2f)) is RemoteCommandResult.Unsupported)
        assertTrue(controller.execute(RemoteAction.SelectAudio("a")) is RemoteCommandResult.Unsupported)
        assertEquals(1, transport.pointers.size)
    }
    @Test fun confirmedSubtitlePreservesHeadersAndThirtySecondDeadlineAndErrorStrings() = runTest {
        for (profile in listOf(RemoteProfile.AndroidTv, RemoteProfile.AppleTv, RemoteProfile.Desktop)) {
            val transport = FakeNativeRemoteTransport()
            val controller = testController(profile, transport, backgroundScope, setOf("subtitle_resource_add_v1"))
            val resource = SubtitleResource(url = "https://example.test/sub.vtt", headers = mapOf("Referer" to "https://example.test/page"))
            assertEquals(RemoteCommandResult.Confirmed, controller.execute(RemoteAction.AddSubtitle(resource)))
            val (id, command, timeout) = transport.confirmations.single()
            assertEquals(30_000L, timeout)
            val json = JSONObject(command)
            assertEquals(id, json.getString("requestId")); assertEquals("control", json.getString("action"))
            assertEquals("add_subtitle", json.getJSONObject("payload").getString("command"))
            val sub = json.getJSONObject("payload").getJSONObject("subtitleResource")
            assertEquals(resource.url, sub.getString("url")); assertEquals(resource.headers["Referer"], sub.getJSONObject("headers").getString("Referer"))
            assertTrue(transport.commands.isEmpty())
            for ((error, message) in mapOf("no_active_playback" to "No video is playing on the TV", "invalid_command" to "The TV rejected this subtitle", "subtitle_unavailable" to "The TV could not load this subtitle")) {
                transport.confirmation = { QueueCommandResult(false, error) }
                assertEquals(RemoteCommandResult.Failed(message), controller.execute(RemoteAction.AddSubtitle(resource)))
            }
            transport.confirmation = { null }
            assertEquals(RemoteCommandResult.Failed("The TV did not confirm the subtitle"), controller.execute(RemoteAction.AddSubtitle(resource)))
        }
    }
    @Test fun legacySubtitleFallbackNeverDropsHeadersOrClaimsConfirmation() = runTest {
        val transport = FakeNativeRemoteTransport()
        val controller = testController(RemoteProfile.AndroidTv, transport, backgroundScope)
        assertEquals(RemoteCommandResult.Submitted, controller.execute(RemoteAction.AddSubtitle(SubtitleResource(url = "https://example.test/sub.srt"))))
        assertEquals(listOf(control("add_subtitle:https://example.test/sub.srt")), transport.commands)
        assertTrue(controller.execute(RemoteAction.AddSubtitle(SubtitleResource(url = "https://example.test/sub.srt", headers = mapOf("Referer" to "https://example.test/")))) is RemoteCommandResult.Unsupported)
        assertEquals(1, transport.commands.size)
        assertTrue(transport.confirmations.isEmpty())
    }
    @Test fun stableQueueMutationsAreFeatureGatedGuardedAndConfirmed() = runTest {
        val transport = FakeNativeRemoteTransport()
        val controller = NativeProfileRemoteController(RemoteSessionId(remoteTestEndpoint, 1L), testConfiguration(features = setOf("queue_crud_v1", "stable_item_ids", "command_results")), transport, backgroundScope, requestId = { "request-1" })
        assertEquals(RemoteCommandResult.Confirmed, controller.execute(RemoteAction.JumpToEpisode(8, "two")))
        assertEquals("""{"type":"command","action":"playlist_jump","requestId":"request-1","payload":{"itemId":"two","ifPlaybackId":"playback"}}""", transport.confirmations[0].second)
        assertEquals(RemoteCommandResult.Confirmed, controller.execute(RemoteAction.RemoveQueueItem("three")))
        assertEquals("""{"type":"command","action":"queue_remove","requestId":"request-1","payload":{"itemIds":["three"],"ifPlaybackId":"playback"}}""", transport.confirmations[1].second)
        assertEquals(RemoteCommandResult.Confirmed, controller.execute(RemoteAction.PlayNext("three")))
        assertEquals("""{"type":"command","action":"queue_move","requestId":"request-1","payload":{"itemId":"three","beforeItemId":"two","ifPlaybackId":"playback"}}""", transport.confirmations[2].second)
        assertTrue(transport.confirmations.all { it.third == 8_000L })
        assertTrue(controller.execute(RemoteAction.RemoveQueueItem("gone")) is RemoteCommandResult.Failed)
        assertTrue(controller.execute(RemoteAction.PlayNext("one")) is RemoteCommandResult.Failed)
        val legacy = testController(RemoteProfile.AndroidTv, transport, backgroundScope)
        assertTrue(legacy.execute(RemoteAction.RemoveQueueItem("three")) is RemoteCommandResult.Unsupported)
        assertEquals(3, transport.confirmations.size)
    }
    @Test fun mirrorDecoratorReplacesMovieStateAndStopsExistingMirrorOwner() = runTest {
        val raw = testSnapshot()
        val transport = FakeNativeRemoteTransport(raw.copy(state = raw.state.copy(mirror = RemoteMirrorState(active = true, sessionId = "mirror"))))
        val controller = ScreenMirrorRemoteController(testController(RemoteProfile.AndroidTv, transport, backgroundScope), transport, backgroundScope)
        val state = controller.snapshot.value.state
        assertEquals(RemoteContext.ScreenMirror, state.context)
        assertNull(state.playback); assertNull(state.queue); assertTrue(state.tracks.audio.isEmpty())
        assertFalse(resolveRemoteLayout(state, RemoteViewport()).wave)
        assertEquals(RemoteCommandResult.Submitted, controller.execute(RemoteAction.StopPlayback))
        assertEquals(1, transport.mirrorStops)
        assertTrue(controller.execute(RemoteAction.SeekTo(10_000)) is RemoteCommandResult.Unsupported)
        assertTrue(transport.commands.isEmpty())
    }
    @Test fun compatibilityCallbackMappingIsTypedAndInvalidCallbacksAreExplicit() {
        val keys = mapOf("dpad_up" to RemoteKey.Up, "dpad_down" to RemoteKey.Down, "dpad_left" to RemoteKey.Left, "dpad_right" to RemoteKey.Right, "dpad_center" to RemoteKey.Ok, "back" to RemoteKey.Back, "home" to RemoteKey.Home, "key_enter" to RemoteKey.Enter)
        for ((wire, key) in keys) assertEquals(RemoteAction.Key(key), RemoteCallbackActions.key(wire))
        assertEquals(RemoteAction.AdjustVolume(1), RemoteCallbackActions.key("volume_up"))
        assertEquals(RemoteAction.AdjustVolume(-1), RemoteCallbackActions.key("volume_down"))
        assertEquals(RemoteAction.ReplaceText("é👋"), RemoteCallbackActions.key("text:w6nwn5GL"))
        for ((wire, action) in mapOf("play" to RemoteAction.SetPlaying(true), "pause" to RemoteAction.SetPlaying(false), "stop" to RemoteAction.StopPlayback, "seek_back" to RemoteAction.SeekBy(-10_000L), "seek_forward" to RemoteAction.SeekBy(10_000L), "loop_on" to RemoteAction.SetLoop(true), "loop_off" to RemoteAction.SetLoop(false))) assertEquals(action, RemoteCallbackActions.player(wire))
        assertEquals(RemoteAction.TogglePlayPause, RemoteCallbackActions.browser("toggle_play"))
        assertEquals(RemoteAction.SeekTo(42L), RemoteCallbackActions.browser("seek_to:42"))
        assertEquals(RemoteAction.ManageUserAgent, RemoteCallbackActions.browser("manage_user_agent"))
        assertEquals(RemoteAction.ManageUserScripts, RemoteCallbackActions.browser("manage_user_scripts"))
        assertEquals(RemoteAction.SetBrowserFullscreen(true), RemoteCallbackActions.browser("maximize_video"))
        assertEquals(RemoteAction.SetBrowserFullscreen(false), RemoteCallbackActions.browser("restore_video"))
        for (action in BrowserAction.entries) assertEquals(RemoteAction.Browser(action), RemoteCallbackActions.browser(action.wireValue))
        assertTrue(RemoteCallbackActions.player("invalid") is RemoteAction.Unavailable)
        assertTrue(RemoteCallbackActions.browser("seek_to:invalid") is RemoteAction.Unavailable)
        assertTrue(RemoteCallbackActions.key("text:!") is RemoteAction.Unavailable)
    }
    @Test fun eachExternalProfileCallsExistingSessionOwnerAndNeverClaimsConfirmation() = runTest {
        for (kind in listOf(TargetKind.GOOGLE_CAST, TargetKind.ROKU, TargetKind.DLNA, TargetKind.WEB_BROWSER)) {
            val transport = FakeExternalRemoteTransport(kind)
            val targets = kotlinx.coroutines.flow.MutableStateFlow<RemoteTarget?>(transport.target)
            val registry = RemoteControllerRegistry(targets, FakeNativeRemoteTransport(), backgroundScope, transport)
            val controller = registry.controller.value!!
            when (kind) {
                TargetKind.GOOGLE_CAST -> assertTrue(controller is GoogleCastRemoteController)
                TargetKind.ROKU -> assertTrue(controller is RokuRemoteController)
                TargetKind.DLNA -> assertTrue(controller is DlnaRemoteController)
                TargetKind.WEB_BROWSER -> assertTrue(controller is WebReceiverRemoteController)
                else -> error("fixture")
            }
            for (action in listOf(RemoteAction.SetPlaying(false), RemoteAction.SetPlaying(true), RemoteAction.AdjustVolume(2), RemoteAction.AdjustVolume(-1), RemoteAction.StopPlayback)) {
                assertEquals(RemoteCommandResult.Submitted, controller.execute(action))
            }
            assertEquals(listOf("pause", "play", "volume:up", "volume:up", "volume:down", "stop"), transport.owner.calls)
            assertEquals(RemoteCommandResult.Submitted, controller.execute(RemoteAction.Resync))
            assertEquals(6, transport.owner.calls.size)
        }
    }
    @Test fun externalSeekBoundsAndRelativeCommandsUseExactSessionSeekCalls() = runTest {
        for (kind in listOf(TargetKind.GOOGLE_CAST, TargetKind.DLNA, TargetKind.WEB_BROWSER)) {
            val transport = FakeExternalRemoteTransport(kind)
            val controller = ExternalProfileRemoteController(RemoteSessionId(transport.receiver.endpoint, 1L), transport.target, transport, backgroundScope)
            controller.execute(RemoteAction.SeekTo(200_000L)); controller.execute(RemoteAction.SeekTo(-500L))
            controller.execute(RemoteAction.SeekBy(Long.MAX_VALUE)); controller.execute(RemoteAction.SeekBy(-30_000L))
            assertEquals(listOf("seek:120000", "seek:0", "seek:120000", "seek:0"), transport.owner.calls)
            transport.tick(live = true); runCurrent()
            assertTrue(controller.execute(RemoteAction.SeekTo(50_000L)) is RemoteCommandResult.Unsupported)
            assertEquals(4, transport.owner.calls.size)
        }
    }
    @Test fun rokuKeepsExactNavigationWireKeysAtIdleButRejectsArbitrarySeekAndAbsoluteVolume() = runTest {
        val transport = FakeExternalRemoteTransport(TargetKind.ROKU)
        transport.tick(state = PlaybackState.IDLE)
        val controller = RokuRemoteController(RemoteSessionId(transport.receiver.endpoint, 1L), transport.target, transport, backgroundScope)
        for (key in listOf(RemoteKey.Up, RemoteKey.Down, RemoteKey.Left, RemoteKey.Right, RemoteKey.Ok, RemoteKey.Back, RemoteKey.Home, RemoteKey.Enter)) {
            assertEquals(RemoteCommandResult.Submitted, controller.execute(RemoteAction.Key(key)))
        }
        assertEquals(listOf("roku:dpad_up", "roku:dpad_down", "roku:dpad_left", "roku:dpad_right", "roku:dpad_center", "roku:back", "roku:home", "roku:key_enter"), transport.owner.calls)
        for (action in listOf(RemoteAction.SeekTo(10_000L), RemoteAction.SeekBy(10_000L), RemoteAction.SetVolume(50), RemoteAction.Key(RemoteKey.Power), RemoteAction.Key(RemoteKey.Mute), RemoteAction.Key(RemoteKey.Delete))) {
            assertTrue(controller.execute(action) is RemoteCommandResult.Unsupported)
        }
        assertEquals(8, transport.owner.calls.size)
    }
    @Test fun everyExternalRejectsUnimplementedTracksSettingsInputAndPhoneQueueMutations() = runTest {
        for (kind in listOf(TargetKind.GOOGLE_CAST, TargetKind.ROKU, TargetKind.DLNA, TargetKind.WEB_BROWSER)) {
            val transport = FakeExternalRemoteTransport(kind)
            // Even misleading broad flags cannot create a track, text, browser or queue API.
            transport.capabilities = transport.capabilities + setOf(Capability.SUBTITLES, Capability.QUEUE, Capability.REMOTE, Capability.BROWSER)
            transport.refresh()
            val controller = ExternalProfileRemoteController(RemoteSessionId(transport.receiver.endpoint, 1L), transport.target, transport, backgroundScope)
            val actions = listOf(RemoteAction.SelectAudio("a"), RemoteAction.SelectSubtitle("off"), RemoteAction.AddSubtitle(SubtitleResource(url = "https://example.test/sub.srt")), RemoteAction.SetSpeed(1.5f), RemoteAction.SetLoop(true), RemoteAction.SetScaling(ScalingMode.Zoom), RemoteAction.SetVideoQuality(720), RemoteAction.ToggleAudioBoost, RemoteAction.AdjustSubtitleOffset(250), RemoteAction.SwitchEngine("mpv"), RemoteAction.ReplaceText("abc"), RemoteAction.DeleteText, RemoteAction.PointerMove(1f, 2f), RemoteAction.PointerClick, RemoteAction.PointerScroll(1f, 2f), RemoteAction.PointerDown, RemoteAction.PointerUp, RemoteAction.PointerGestureEnd, RemoteAction.ImageZoom(2f), RemoteAction.ImageRotate(90f), RemoteAction.ImageAnchor(.5f, .5f), RemoteAction.ResetImageTransform, RemoteAction.Browser(BrowserAction.Reload), RemoteAction.SetBrowserFullscreen(true), RemoteAction.OpenTvBrowser("https://example.test"), RemoteAction.ManageUserScripts, RemoteAction.ManageUserAgent, RemoteAction.JumpToEpisode(1), RemoteAction.RemoveQueueItem("one"), RemoteAction.PlayNext("two"))
            for (action in actions) assertTrue("$kind/$action", controller.execute(action) is RemoteCommandResult.Unsupported)
            if (kind != TargetKind.ROKU) assertTrue(controller.execute(RemoteAction.Key(RemoteKey.Home)) is RemoteCommandResult.Unsupported)
            assertTrue(transport.owner.calls.isEmpty())
        }
    }
    @Test fun externalDynamicVolumeGatesCallsAndAbsoluteVolumeClampsWhereImplemented() = runTest {
        for (kind in listOf(TargetKind.GOOGLE_CAST, TargetKind.DLNA, TargetKind.WEB_BROWSER)) {
            val transport = FakeExternalRemoteTransport(kind)
            val controller = ExternalProfileRemoteController(RemoteSessionId(transport.receiver.endpoint, 1L), transport.target, transport, backgroundScope)
            controller.execute(RemoteAction.SetVolume(150)); controller.execute(RemoteAction.SetVolume(-5))
            assertEquals(listOf("setVolume:100", "setVolume:0"), transport.owner.calls)
            transport.capabilities = transport.capabilities - Capability.VOLUME; transport.tick(volume = false); runCurrent()
            assertTrue(controller.execute(RemoteAction.AdjustVolume(1)) is RemoteCommandResult.Unsupported)
            assertTrue(controller.execute(RemoteAction.SetVolume(50)) is RemoteCommandResult.Unsupported)
            transport.capabilities = transport.capabilities + Capability.VOLUME; transport.tick(volume = true); runCurrent()
            assertEquals(RemoteCommandResult.Submitted, controller.execute(RemoteAction.AdjustVolume(-1)))
            assertEquals(listOf("setVolume:100", "setVolume:0", "volume:down"), transport.owner.calls)
        }
    }
    @Test fun externalOwnerLoadAndRouteChecksRejectBeforeSnapshotCollectorCatchesUp() = runTest {
        val transport = FakeExternalRemoteTransport()
        val controller = GoogleCastRemoteController(RemoteSessionId(transport.receiver.endpoint, 1L), transport.target, transport, backgroundScope)
        transport.owner.generation++ // authoritative owner changed; old snapshot still visible
        assertTrue(controller.execute(RemoteAction.SetPlaying(false)) is RemoteCommandResult.Failed)
        assertTrue(transport.owner.calls.isEmpty())
        transport.tick(); runCurrent()
        transport.owner.active = false
        assertTrue(controller.execute(RemoteAction.SetPlaying(false)) is RemoteCommandResult.Failed)
        assertTrue(transport.owner.calls.isEmpty())
    }
    @Test fun externalVolumeStopsAfterOwnerChangesAndMirrorStopUsesExistingMirrorOwner() = runTest {
        val transport = FakeExternalRemoteTransport()
        val controller = GoogleCastRemoteController(RemoteSessionId(transport.receiver.endpoint, 1L), transport.target, transport, backgroundScope)
        transport.owner.afterVolume = { transport.owner.active = false }
        assertTrue(controller.execute(RemoteAction.AdjustVolume(3)) is RemoteCommandResult.Failed)
        assertEquals(listOf("volume:up"), transport.owner.calls)
        transport.owner.active = true; transport.owner.mirroring = true
        assertEquals(RemoteCommandResult.Submitted, controller.execute(RemoteAction.StopPlayback))
        assertEquals(listOf("volume:up", "stopMirror"), transport.owner.calls)
    }

}
