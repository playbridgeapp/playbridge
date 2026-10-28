package com.playbridge.sender.cast.remote

import com.playbridge.sender.connection.ReceiverDescriptor
import com.playbridge.sender.connection.ReceiverDescriptorParser
import com.playbridge.sender.connection.ReceiverEngine
import com.playbridge.sender.connection.ReceiverProtocolSession
import com.playbridge.sender.connection.ReceiverProtocolState
import com.playbridge.sender.connection.ReceiverSettingsAvailability
import com.playbridge.sender.connection.ReceiverSurface
import com.playbridge.sender.connection.ReceiverVolume
import com.playbridge.sender.connection.ReceiverVolumeState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.double
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import playbridge.SubtitleResource

@OptIn(ExperimentalCoroutinesApi::class)
class ReceiverRemotePolicyTest {
    @Test fun liveGeckoUpdatesKeepControllerAndMediaAndFenceExecutionBeforeUiRefresh() = runTest {
        fun frame(value: String) = Json.parseToJsonElement(value) as JsonObject
        val owner = ReceiverProtocolSession()
        val session = owner.begin()
        val withoutGecko = javaClass.getResourceAsStream("/receiver-policy/android-tv.json")!!.bufferedReader().use { it.readText() }
        val withGecko = javaClass.getResourceAsStream("/receiver-policy/android-tv-gecko.json")!!.bufferedReader().use { it.readText() }
        owner.acceptCredentials(session, frame("""{"features":["receiver_capabilities_v1"],"capabilities":$withoutGecko}"""))
        owner.subscription(session)
        val config = testConfiguration(RemoteProfile.Unknown).copy(receiverProtocol = owner.state)
        val raw = testSnapshot(RemoteContext.Browser)
        val transport = FakeNativeRemoteTransport(raw.copy(state = raw.state.copy(browser = RemoteBrowserState(RemoteBrowserEngine.Unknown))))
        val registry = RemoteControllerRegistry(MutableStateFlow<RemoteTarget?>(RemoteNativeTarget(config)), transport, backgroundScope)
        val controller = registry.controller.value!!
        runCurrent()
        val media = controller.snapshot.value.state.playback?.mediaId
        assertEquals(Support.Supported, controller.snapshot.value.state.capabilities.pointer.zoom)
        owner.observe(session, frame("""{"type":"receiver_capabilities","revision":1,"capabilities":$withGecko}"""))
        // Unknown active browser engine must intersect WebView/Gecko operations.
        // Do not run the combined StateFlow first: dispatch must already deny zoom.
        assertTrue(controller.execute(RemoteAction.ImageZoom(1.1f)) is RemoteCommandResult.Unsupported)
        runCurrent()
        assertEquals(Support.Unsupported, controller.snapshot.value.state.capabilities.pointer.zoom)
        assertTrue(controller === registry.controller.value)
        assertEquals(media, controller.snapshot.value.state.playback?.mediaId)
        owner.observe(session, frame("""{"type":"receiver_capabilities","revision":2,"capabilities":$withoutGecko}"""))
        runCurrent()
        assertEquals(Support.Supported, controller.snapshot.value.state.capabilities.pointer.zoom)
        val reducer = RemoteStateReducer { 0L }
        reducer.observe(controller.snapshot.value)
        reducer.local(RemoteAction.OpenSheet(RemoteSheet.BrowserMore))
        owner.observe(session, frame("""{"type":"receiver_capabilities","revision":3,"capabilities":{"version":1,"surfaces":{}}}"""))
        assertTrue(controller.execute(RemoteAction.ReplaceText("synthetic")) is RemoteCommandResult.Unsupported)
        runCurrent()
        assertNull(reducer.observe(controller.snapshot.value).sheet)
        assertTrue(controller === registry.controller.value)
        assertTrue(transport.commands.isEmpty())
    }

    private val guarded = setOf("queue_crud_v1", "stable_item_ids", "command_results", "subtitle_resource_add_v1", "progress_webhook_v1")
    private fun fixture(name: String): ReceiverDescriptor = javaClass.getResourceAsStream("/receiver-policy/$name.json")!!.use {
        ReceiverDescriptorParser.parse(Json.parseToJsonElement(it.bufferedReader().readText()))!!
    }
    private fun state(descriptor: ReceiverDescriptor?, context: RemoteContext = RemoteContext.Player, protocol: ReceiverProtocolState = ReceiverProtocolState(descriptor), features: Set<String> = guarded): RemoteUiState {
        val config = testConfiguration()
        val raw = testSnapshot(context).state.copy(
            receiver = config.receiver, sessionId = RemoteSessionId(remoteTestEndpoint, 1), receiverProtocol = protocol,
            tracks = RemoteTracks(audio = listOf(RemoteTrack("a", "Audio", true)), subtitles = listOf(RemoteTrack("s", "Subtitle", true))),
        )
        return resolveReceiverRemotePolicy(raw, config.players, features)
    }
    private fun player(vararg engines: Pair<String, ReceiverEngine>) = ReceiverDescriptor(1, mapOf("player" to ReceiverSurface(mapOf(*engines))))
    private fun engine(transport: Set<String> = emptySet(), keys: Set<String> = emptySet(), settings: Set<String> = emptySet(), queue: Set<String> = emptySet()) =
        ReceiverEngine(transport = transport, keys = keys, settings = settings, queue = queue)

    @Test fun idleAppKeysUseOnlyTheirOwnEngineIntersectionAndDispatch() {
        val navigation = setOf("dpad_up", "dpad_down", "dpad_left", "dpad_right", "dpad_center", "back", "home")
        val descriptor = ReceiverDescriptor(1, mapOf(
            "app" to ReceiverSurface(mapOf("native" to engine(keys = navigation))),
            "player" to ReceiverSurface(mapOf("mpv" to engine(keys = setOf("dpad_left")))),
        ))
        val raw = state(descriptor, RemoteContext.Idle)
        val idle = resolveReceiverRemotePolicy(raw.copy(receiverProtocol = raw.receiverProtocol.copy(contextEngine = "mpv")), listOf("mpv"), guarded)
        assertEquals(setOf(RemoteKey.Up, RemoteKey.Down, RemoteKey.Left, RemoteKey.Right, RemoteKey.Ok, RemoteKey.Back, RemoteKey.Home), idle.capabilities.keys)
        assertTrue(resolveRemoteLayout(idle, RemoteViewport()).modes.contains(RemoteInputMode.Pad))
        for (key in idle.capabilities.keys) assertTrue(idle.receiverActionSupported(RemoteAction.Key(key)))
        assertEquals(Support.Unsupported, idle.capabilities.transport.playPause)
        assertFalse(idle.receiverActionSupported(RemoteAction.StopPlayback))
        val noApp = state(descriptor.copy(surfaces = descriptor.surfaces - "app"), RemoteContext.Idle)
        assertTrue(noApp.capabilities.keys.isEmpty())
        assertFalse(resolveRemoteLayout(noApp, RemoteViewport()).modes.contains(RemoteInputMode.Pad))
        assertFalse(noApp.receiverActionSupported(RemoteAction.Key(RemoteKey.Ok)))
        assertEquals(setOf(RemoteKey.Left), state(descriptor, RemoteContext.Player).capabilities.keys)
        assertTrue(state(descriptor, RemoteContext.Browser).capabilities.keys.isEmpty())
        val multiple = descriptor.copy(surfaces = descriptor.surfaces + ("app" to ReceiverSurface(mapOf(
            "native" to engine(keys = navigation), "future" to engine(keys = setOf("back")),
        ))))
        val intersected = resolveReceiverRemotePolicy(state(multiple, RemoteContext.Idle).copy(
            receiverProtocol = ReceiverProtocolState(multiple, contextEngine = "native")), emptyList(), guarded)
        assertEquals(setOf(RemoteKey.Back), intersected.capabilities.keys)
        val legacy = state(null, RemoteContext.Idle).copy(receiver = testConfiguration(RemoteProfile.AppleTv).receiver)
        val legacyApple = resolveReceiverRemotePolicy(legacy, listOf("mpv"), emptySet())
        assertFalse(RemoteKey.Ok in legacyApple.capabilities.keys)
    }

    @Test fun realPlayerDescriptorsExposeTheirActualControls() {
        for (name in listOf("android-tv", "android-tv-gecko", "apple-tv", "apple-tv-app-volume", "desktop", "cli")) {
            val resolved = state(fixture(name))
            val caps = resolved.capabilities
            assertEquals(name, Support.Supported, caps.transport.playPause)
            assertEquals(name, Support.Supported, caps.transport.seekAbsolute)
            assertEquals(name, Support.Supported, caps.transport.stop)
            assertEquals(name, Support.Supported, caps.queue.jumpIndex)
            val nav = name.startsWith("android") || name.startsWith("apple")
            assertEquals(name, nav, RemoteKey.Up in caps.keys)
            assertEquals(name, nav, RemoteKey.Down in caps.keys)
            assertEquals(name, nav, RemoteKey.Back in caps.keys)
            assertEquals(name, nav, RemoteKey.Home in caps.keys)
            assertEquals(name, nav, resolveRemoteLayout(resolved, RemoteViewport()).modes.contains(RemoteInputMode.Pad))
            assertEquals(name, name != "cli", caps.tracks.audio == Support.Supported)
            assertEquals(name, name.startsWith("android"), PlayerSetting.Speed in caps.settings)
            assertEquals(name, name.startsWith("apple") || name.startsWith("android"), PlayerSetting.Loop in caps.settings)
            assertEquals(name, name in setOf("android-tv", "android-tv-gecko", "desktop", "apple-tv-app-volume"), caps.volume.adjust == Support.Supported)
            assertEquals(name, name == "apple-tv-app-volume", caps.volume.absolute == Support.Supported)
            assertEquals(name, name == "apple-tv-app-volume", resolved.volumeLabel() == "App volume")
            assertEquals(name, name == "desktop", caps.volume.scope == VolumeScope.SystemWithPlayerFallback)
            assertFalse(name, resolveRemoteLayout(resolved, RemoteViewport()).browserRow)
        }
        val apple = state(fixture("apple-tv"))
        assertEquals(setOf(PlayerSetting.Engine, PlayerSetting.Loop), apple.capabilities.settings)
        assertEquals(setOf("avplayer", "mpv"), apple.capabilities.engines)
        assertFalse(apple.receiverActionSupported(RemoteAction.Key(RemoteKey.Enter)))
    }

    @Test fun geckoZoomIsDeclaredPerEngineAndUnknownEngineUsesIntersection() {
        for (installed in listOf(false, true)) {
            val descriptor = fixture(if (installed) "android-tv-gecko" else "android-tv")
            val raw = state(descriptor, RemoteContext.Browser)
            fun resolve(engine: String?, browserEngine: RemoteBrowserEngine = RemoteBrowserEngine.Unknown) = resolveReceiverRemotePolicy(
                raw.copy(browser = raw.browser?.copy(engine = browserEngine), receiverProtocol = raw.receiverProtocol.copy(contextEngine = engine)), emptyList(), guarded,
            )
            assertEquals(Support.Supported, resolve("webview").capabilities.pointer.zoom)
            assertEquals(if (installed) Support.Unsupported else Support.Supported, resolve(null).capabilities.pointer.zoom)
            assertEquals(if (installed) Support.Unsupported else Support.Supported, resolve("unrecognized").capabilities.pointer.zoom)
            if (installed) assertEquals(Support.Unsupported, resolve("gecko").capabilities.pointer.zoom)
            assertEquals(Support.Supported, resolve(null).capabilities.pointer.drag)
            assertEquals(Support.Supported, resolve(null).capabilities.text.replace)
            assertEquals(Support.Unsupported, resolve(null).capabilities.text.delete)
            assertTrue(resolve(null).receiverActionSupported(RemoteAction.TogglePlayPause))
            assertFalse(resolve(null).receiverActionSupported(RemoteAction.SetPlaying(false)))
        }
    }

    @Test fun engineReportsTakePrecedenceAndNoEngineUnionOrCrossSurfaceFallbackExists() {
        val descriptor = player("exo" to engine(keys = setOf("dpad_up"), settings = setOf("loop")), "mpv" to engine(keys = setOf("dpad_down"), settings = setOf("loop")))
        val raw = state(descriptor)
        fun resolved(contextEngine: String?, settingsEngine: String?) = resolveReceiverRemotePolicy(raw.copy(
            receiverProtocol = raw.receiverProtocol.copy(contextEngine = contextEngine), settings = RemotePlayerSettings(engine = settingsEngine)), listOf("exo", "mpv"), guarded)
        assertEquals(setOf(RemoteKey.Up), resolved("exo", "mpv").capabilities.keys)
        assertEquals(setOf(RemoteKey.Down), resolved(null, "mpv").capabilities.keys)
        assertTrue(resolved(null, null).capabilities.keys.isEmpty())
        assertTrue(resolved("future_engine", "exo").capabilities.keys.isEmpty())
        assertEquals(setOf(PlayerSetting.Loop), resolved(null, null).capabilities.settings)
        assertEquals(setOf(RemoteKey.Up), resolveReceiverRemotePolicy(raw, listOf("exo"), guarded).capabilities.keys)
        assertTrue(state(descriptor, RemoteContext.Browser).capabilities.keys.isEmpty())
        assertTrue(state(descriptor, RemoteContext.Idle).capabilities.keys.isEmpty())
    }

    @Test fun unknownIdentifiersAndHostHintsDoNotGrantOrRemoveRecognizedOperations() {
        val descriptor = player("future_engine" to engine(keys = setOf("home", "future_key"), transport = setOf("toggle", "future_transport")))
        val resolved = resolveReceiverRemotePolicy(state(descriptor).copy(receiver = testConfiguration(RemoteProfile.Unknown).receiver), emptyList(), emptySet())
        assertEquals(setOf(RemoteKey.Home), resolved.capabilities.keys)
        assertEquals(Support.Supported, resolved.capabilities.transport.playPause)
        assertEquals(Support.Unsupported, resolved.capabilities.volume.adjust)
        assertTrue(resolved.capabilities.engines.isEmpty())
        assertEquals("DescriptorRemotePolicy", resolved.nativePolicy.toString())
    }

    @Test fun emptyAndMalformedV1FailClosedWhileUnknownVersionsUseExactLegacy() {
        val owner = ReceiverProtocolSession()
        for ((json, malformed) in listOf("""{"version":1,"surfaces":{}}""" to false, """{"version":1,"surfaces":[]}""" to true)) {
            val session = owner.begin()
            owner.acceptCredentials(session, Json.parseToJsonElement("""{"capabilities":$json}""") as JsonObject)
            val resolved = state(owner.state.value.descriptor, protocol = owner.state.value)
            assertEquals(malformed, owner.state.value.malformedV1)
            assertNotNull(resolved.nativePolicy)
            assertFalse(resolveRemoteLayout(resolved, RemoteViewport()).wave)
            assertFalse(resolveRemoteLayout(resolved, RemoteViewport()).showQueue)
            assertFalse(resolveRemoteLayout(resolved, RemoteViewport()).tracks.more)
            assertTrue(resolved.capabilities.keys.isEmpty())
            assertFalse(resolved.receiverActionSupported(RemoteAction.SetLoop(true)))
        }
        val session = owner.begin()
        owner.acceptCredentials(session, Json.parseToJsonElement("""{"capabilities":{"version":999,"surfaces":[]}}""") as JsonObject)
        assertFalse(owner.state.value.malformedV1)
        val old = state(null)
        assertEquals(old.capabilities, state(null, protocol = owner.state.value).capabilities)
        assertNull(state(null, protocol = owner.state.value).nativePolicy)
    }

    @Test fun availabilityFlagsRestrictSettingsButNeverAddOperations() {
        val descriptor = fixture("android-tv")
        val raw = state(descriptor)
        val resolved = resolveReceiverRemotePolicy(raw.copy(
            settings = raw.settings.copy(availability = PlayerSetting.entries.associateWith { Support.Unsupported }),
            receiverProtocol = raw.receiverProtocol.copy(settingsAvailability = ReceiverSettingsAvailability(false, false, false))), listOf("exo", "mpv"), guarded)
        assertTrue(resolved.capabilities.settings.isEmpty())
        val flagsOnly = state(player("exo" to engine()), protocol = ReceiverProtocolState(player("exo" to engine()), settingsAvailability = ReceiverSettingsAvailability(true, true, true)))
        assertTrue(flagsOnly.capabilities.settings.isEmpty())
        for (setting in listOf(PlayerSetting.SubtitleOffset, PlayerSetting.Engine, PlayerSetting.Loop)) {
            val flags = when (setting) {
                PlayerSetting.SubtitleOffset -> ReceiverSettingsAvailability(subtitleOffsetAvailable = false)
                PlayerSetting.Engine -> ReceiverSettingsAvailability(engineAvailable = false)
                else -> ReceiverSettingsAvailability(loopAvailable = false)
            }
            assertFalse(setting in state(descriptor, protocol = ReceiverProtocolState(descriptor, settingsAvailability = flags)).capabilities.settings)
        }
    }

    @Test fun mediaAndLiveRestrictionsRemainAuthoritative() {
        for (name in listOf("android-tv", "apple-tv", "desktop", "cli")) {
            val descriptor = fixture(name)
            val raw = state(descriptor)
            val image = resolveReceiverRemotePolicy(raw.copy(playback = raw.playback?.copy(mediaKind = RemoteMediaKind.Image)), emptyList(), guarded)
            assertEquals(Support.Unsupported, image.capabilities.transport.seekAbsolute)
            assertTrue(image.capabilities.settings.isEmpty())
            assertEquals(Support.Unsupported, image.capabilities.tracks.audio)
            assertEquals(name in setOf("android-tv", "desktop"), image.hasPointerRoute())
            val live = resolveReceiverRemotePolicy(raw.copy(playback = raw.playback?.copy(isLive = true)), emptyList(), guarded)
            assertEquals(Support.Unsupported, live.capabilities.transport.seekRelative)
            assertFalse(PlayerSetting.Loop in live.capabilities.settings)
            val audio = resolveReceiverRemotePolicy(raw.copy(playback = raw.playback?.copy(mediaKind = RemoteMediaKind.Audio)), emptyList(), guarded)
            assertEquals(Support.Unsupported, audio.capabilities.tracks.subtitle)
            assertEquals(Support.Unsupported, audio.capabilities.tracks.addResource)
        }
    }

    @Test fun volumeReportsRequireDeclaredReportingAndMatchingScopeAndDoNotInferMute() {
        val descriptor = fixture("apple-tv-app-volume")
        val report = ReceiverVolumeState("app", 0.35, true)
        val resolved = state(descriptor, protocol = ReceiverProtocolState(descriptor, report))
        assertEquals(35, resolved.capabilities.volume.reportedPercent)
        assertEquals(true, resolved.capabilities.volume.muted)
        assertEquals("App volume", resolved.volumeLabel())
        val zero = state(descriptor, protocol = ReceiverProtocolState(descriptor, ReceiverVolumeState("app", 0.0, false)))
        assertEquals(false, zero.capabilities.volume.muted)
        val mismatch = state(descriptor, protocol = ReceiverProtocolState(descriptor, ReceiverVolumeState("system", 0.8, true)))
        assertNull(mismatch.capabilities.volume.level)
        assertNull(mismatch.capabilities.volume.muted)
        val desktop = fixture("desktop")
        val fallback = state(desktop, protocol = ReceiverProtocolState(desktop, report))
        assertEquals("Volume", fallback.volumeLabel())
        assertNull(fallback.capabilities.volume.level)
        assertNull(fallback.capabilities.volume.muted)
        assertFalse(fallback.receiverActionSupported(RemoteAction.SetVolume(35)))
        val muteOnly = descriptor.copy(volume = ReceiverVolume("app", setOf("mute_report")))
        assertNull(state(muteOnly, protocol = ReceiverProtocolState(muteOnly, report)).capabilities.volume.level)
        assertEquals(true, state(muteOnly, protocol = ReceiverProtocolState(muteOnly, report)).capabilities.volume.muted)
        val future = descriptor.copy(volume = ReceiverVolume("future_scope", setOf("absolute", "relative_step")))
        assertEquals(VolumeScope.None, state(future).capabilities.volume.scope)
    }

    @Test fun fallbackReportsNameActualAppScopeWithoutCreatingAppAbsoluteRoute() {
        val descriptor = fixture("desktop").copy(volume = ReceiverVolume("system", setOf("relative_step", "level_report"), fallbackScope = "app"))
        val resolved = state(descriptor, protocol = ReceiverProtocolState(descriptor, ReceiverVolumeState("app", 0.25)))
        assertEquals(VolumeScope.SystemWithPlayerFallback, resolved.capabilities.volume.scope)
        assertEquals("Volume", resolved.volumeLabel())
        assertEquals("App volume", resolved.volumeReportLabel())
        assertEquals(25, resolved.capabilities.volume.level)
        assertFalse(resolved.receiverActionSupported(RemoteAction.SetVolume(25)))
    }

    @Test fun absoluteVolumeUsesDeclaredPrimaryScopeAndNormalizedWireLevelOnly() = runTest {
        for (scope in listOf("app", "system")) {
            val descriptor = fixture("apple-tv-app-volume").copy(volume = ReceiverVolume(scope, setOf("absolute")))
            val transport = FakeNativeRemoteTransport()
            val metadata = MutableStateFlow(ReceiverProtocolState(descriptor))
            val controller = NativeProfileRemoteController(RemoteSessionId(remoteTestEndpoint, 1), testConfiguration().copy(receiverProtocol = metadata), transport, backgroundScope)
            assertEquals(RemoteCommandResult.Submitted, controller.execute(RemoteAction.SetVolume(35)))
            val frame = Json.parseToJsonElement(transport.commands.single()) as JsonObject
            assertEquals("volume", frame["action"].toString().trim('"'))
            val payload = frame["payload"] as JsonObject
            assertEquals(setOf("scope", "level"), payload.keys)
            assertEquals(scope, payload["scope"].toString().trim('"'))
            assertEquals(0.35, payload["level"]!!.let { it as kotlinx.serialization.json.JsonPrimitive }.double, 0.0)
            for (boundary in listOf(0, 100)) assertEquals(RemoteCommandResult.Submitted, controller.execute(RemoteAction.SetVolume(boundary)))
            for (invalid in listOf(-1, 101)) assertTrue(controller.execute(RemoteAction.SetVolume(invalid)) is RemoteCommandResult.Failed)
            assertTrue(controller.execute(RemoteAction.AdjustVolume(1)) is RemoteCommandResult.Unsupported)
            assertEquals(3, transport.commands.size)
        }
    }

    @Test fun realDescriptorsUiAndExecutionAgreeOnEveryExistingNativeAction() = runTest {
        val actions = listOf(
            RemoteAction.TogglePlayPause, RemoteAction.SetPlaying(false), RemoteAction.SeekTo(15_000), RemoteAction.SeekBy(10_000), RemoteAction.StopPlayback,
            RemoteAction.Key(RemoteKey.Up), RemoteAction.Key(RemoteKey.Home), RemoteAction.Key(RemoteKey.Back), RemoteAction.Key(RemoteKey.Enter), RemoteAction.Key(RemoteKey.Mute),
            RemoteAction.AdjustVolume(1), RemoteAction.SetVolume(35), RemoteAction.SelectAudio("a"), RemoteAction.SelectSubtitle("s"),
            RemoteAction.AddSubtitle(SubtitleResource(url = "https://example.test/subtitle.srt")), RemoteAction.SetLoop(true), RemoteAction.SwitchEngine("mpv"),
            RemoteAction.SetSpeed(1.5f), RemoteAction.SetScaling(ScalingMode.Fit), RemoteAction.SetVideoQuality(720), RemoteAction.ToggleAudioBoost, RemoteAction.AdjustSubtitleOffset(250),
            RemoteAction.ReplaceText("synthetic"), RemoteAction.DeleteText, RemoteAction.PointerMove(1f, 2f), RemoteAction.PointerClick, RemoteAction.PointerScroll(1f, 2f),
            RemoteAction.PointerDown, RemoteAction.PointerUp, RemoteAction.ImageZoom(1.2f), RemoteAction.ImageRotate(10f), RemoteAction.ImageAnchor(0.5f, 0.5f), RemoteAction.ResetImageTransform,
            RemoteAction.Browser(BrowserAction.Forward), RemoteAction.SetBrowserFullscreen(true), RemoteAction.ManageUserScripts, RemoteAction.ManageUserAgent,
            RemoteAction.UpdateUserScript("synthetic.js", "synthetic"), RemoteAction.UpdateUserAgent("synthetic", "synthetic", true),
            RemoteAction.JumpToEpisode(8), RemoteAction.JumpToEpisode(8, "two"), RemoteAction.RemoveQueueItem("three"), RemoteAction.PlayNext("three"),
        )
        for (name in listOf("android-tv", "android-tv-gecko", "apple-tv", "apple-tv-app-volume", "desktop", "cli")) {
            for (context in listOf(RemoteContext.Player, RemoteContext.Browser)) {
                val initial = state(fixture(name), context, features = if (name == "cli") setOf("progress_webhook_v1") else guarded)
                val transport = FakeNativeRemoteTransport(RemoteReceiverSnapshot(initial))
                val config = testConfiguration(features = if (name == "cli") setOf("progress_webhook_v1") else guarded)
                val controller = NativeProfileRemoteController(RemoteSessionId(remoteTestEndpoint, 1), config, transport, backgroundScope)
                for (action in actions) {
                    val supported = controller.snapshot.value.state.receiverActionSupported(action)
                    val before = transport.commands.size + transport.pointers.size + transport.confirmations.size
                    val result = controller.execute(action)
                    if (supported) assertTrue("$name/$context/${action::class.simpleName}", result == RemoteCommandResult.Submitted || result == RemoteCommandResult.Confirmed)
                    else {
                        assertTrue("$name/$context/${action::class.simpleName}", result is RemoteCommandResult.Unsupported)
                        assertEquals(before, transport.commands.size + transport.pointers.size + transport.confirmations.size)
                    }
                }
            }
        }
    }

    @Test fun queueGuardsRemainRequiredAndCliJumpsUseIndexDespiteObservedItemIds() = runTest {
        val cli = state(fixture("cli"), features = setOf("progress_webhook_v1"))
        assertNull(cli.queueJump(8, "two").itemId)
        assertFalse(cli.receiverActionSupported(RemoteAction.RemoveQueueItem("two")))
        val apple = state(fixture("apple-tv"), features = emptySet())
        assertEquals(Support.Unsupported, apple.capabilities.queue.jumpItem)
        assertEquals(Support.Unsupported, apple.capabilities.queue.remove)
        val onlyItem = player("exo" to engine(queue = setOf("jump_item")))
        val initial = state(onlyItem)
        assertTrue(resolveRemoteLayout(initial, RemoteViewport()).showQueue)
        val controller = NativeProfileRemoteController(RemoteSessionId(remoteTestEndpoint, 1), testConfiguration(features = guarded), FakeNativeRemoteTransport(RemoteReceiverSnapshot(initial)), backgroundScope)
        assertEquals(RemoteCommandResult.Confirmed, controller.execute(initial.queueJump(8, "two")))
        assertTrue(controller.execute(RemoteAction.JumpToEpisode(8)) is RemoteCommandResult.Unsupported)
    }

    @Test fun browserLaunchSelectsOnlyExplicitlyAdvertisedEnginesEvenWhileIdle() = runTest {
        val descriptor = fixture("android-tv")
        val initial = state(descriptor, RemoteContext.Idle)
        val transport = FakeNativeRemoteTransport(RemoteReceiverSnapshot(initial))
        val controller = NativeProfileRemoteController(RemoteSessionId(remoteTestEndpoint, 1), testConfiguration(), transport, backgroundScope)
        assertEquals(RemoteCommandResult.Submitted, controller.execute(RemoteAction.OpenTvBrowser("https://example.test")))
        assertTrue(transport.commands.single().contains("webview"))
        assertTrue(controller.execute(RemoteAction.OpenTvBrowser("https://example.test", "gecko")) is RemoteCommandResult.Unsupported)
    }

    @Test fun missingTracksCannotExecuteAnInvisibleAudioSelection() {
        val resolved = state(fixture("android-tv")).copy(tracks = RemoteTracks())
        assertFalse(resolveRemoteLayout(resolved, RemoteViewport()).tracks.audio)
        assertFalse(resolved.receiverActionSupported(RemoteAction.SelectAudio("a")))
    }

    private class Channel : TvRemoteChannel {
        override val state = MutableStateFlow(TvRemoteChannelState(remoteTestEndpoint, "synthetic", true, true,
            RemoteKey.entries.toSet(), VolumeCapabilities(adjust = Support.Supported, level = 5, maximum = 10), TextCapabilities(Support.Supported, Support.Supported, Support.Supported)))
        val actions = mutableListOf<RemoteAction>()
        override suspend fun sendKey(key: RemoteKey): RemoteCommandResult { actions += RemoteAction.Key(key); return RemoteCommandResult.Submitted }
        override suspend fun adjustVolume(steps: Int): RemoteCommandResult { actions += RemoteAction.AdjustVolume(steps); return RemoteCommandResult.Submitted }
        override suspend fun replaceText(text: String) = RemoteCommandResult.Submitted
        override suspend fun deleteText() = RemoteCommandResult.Submitted
        override suspend fun editText(deletes: Int, text: String, enter: Boolean): RemoteCommandResult { actions += RemoteAction.EditTvText(deletes, text, enter); return RemoteCommandResult.Submitted }
    }

    @Test fun fullControlOverlaysWithAbsentEmptyOrPlayerOnlyDescriptorsAndRoutesIndependently() = runTest {
        for (descriptor in listOf(null, ReceiverDescriptor(1, emptyMap()), fixture("apple-tv"), fixture("apple-tv-app-volume"))) {
            val initial = state(descriptor)
            val channel = Channel()
            val projected = projectFullControl(initial, initial.capabilities, channel.state.value)
            assertTrue(projected.fullControlReady)
            assertTrue(projected.receiverActionSupported(RemoteAction.Key(RemoteKey.Home)))
            assertTrue(projected.receiverActionSupported(RemoteAction.AdjustVolume(1)))
            assertEquals("Volume", projected.volumeLabel())
            val transport = FakeNativeRemoteTransport(RemoteReceiverSnapshot(initial))
            val controller = NativeProfileRemoteController(RemoteSessionId(remoteTestEndpoint, 1), testConfiguration(), transport, backgroundScope)
            val router = RemoteInputRouter(channel)
            assertEquals(RemoteCommandResult.Submitted, router.execute(controller, RemoteAction.Key(RemoteKey.Home)))
            assertEquals(RemoteCommandResult.Submitted, router.execute(controller, RemoteAction.AdjustVolume(1)))
            assertTrue(transport.commands.isEmpty())
            channel.state.value = channel.state.value.copy(connected = false)
            assertFalse(projectFullControl(initial, initial.capabilities, channel.state.value).fullControlReady)
        }
    }

    @Test fun descriptorRefreshKeepsControllerMediaAndOpenKeyboardButFencesItsOldRoute() = runTest {
        val metadata = MutableStateFlow(ReceiverProtocolState(fixture("android-tv")))
        val config = testConfiguration(RemoteProfile.Unknown).copy(receiverProtocol = metadata)
        val transport = FakeNativeRemoteTransport(testSnapshot(RemoteContext.Browser))
        val targets = MutableStateFlow<RemoteTarget?>(RemoteNativeTarget(config))
        val registry = RemoteControllerRegistry(targets, transport, backgroundScope)
        val controller = registry.controller.value!!
        val channel = Channel()
        val reducer = RemoteStateReducer { 0L }
        reducer.observe(controller.snapshot.value)
        reducer.applyChannel(channel.state.value)
        reducer.local(RemoteAction.OpenSheet(RemoteSheet.Keyboard))
        val before = controller.snapshot.value.state.playback?.mediaId
        assertEquals(RemoteKeyboardRoute.PlayBridge, controller.snapshot.value.state.keyboardRoute(true))
        metadata.value = ReceiverProtocolState(ReceiverDescriptor(1, emptyMap()))
        runCurrent()
        val after = reducer.observe(controller.snapshot.value)
        assertEquals(RemoteSheet.Keyboard, after.sheet)
        assertEquals(before, controller.snapshot.value.state.playback?.mediaId)
        assertTrue(controller === registry.controller.value)
        val router = RemoteInputRouter(channel)
        assertTrue(router.execute(controller, RemoteAction.KeyboardInput(RemoteAction.ReplaceText("synthetic"), RemoteKeyboardRoute.PlayBridge, RemoteContext.Browser)) is RemoteCommandResult.Failed)
        assertTrue(transport.commands.isEmpty())
        assertTrue(channel.actions.isEmpty())
        assertEquals(RemoteCommandResult.Submitted, router.execute(controller, RemoteAction.KeyboardInput(RemoteAction.EditTvText(0, "synthetic"), RemoteKeyboardRoute.FullControl, RemoteContext.Browser)))
    }

    @Test fun viewModelPreservesDeclaredBrowserToggleInsteadOfRewritingToUndeclaredPause() = runTest {
        val initial = state(fixture("android-tv"), RemoteContext.Browser)
        val transport = FakeNativeRemoteTransport(RemoteReceiverSnapshot(initial))
        val registry = RemoteControllerRegistry(MutableStateFlow<RemoteTarget?>(RemoteNativeTarget(testConfiguration())), transport, backgroundScope)
        val vm = RemoteViewModel(registry, RemoteInputRouter(), { 0L }, backgroundScope)
        runCurrent()
        assertEquals(RemoteCommandResult.Submitted, vm.execute(RemoteAction.TogglePlayPause))
        assertTrue(transport.commands.single().contains("toggle_play"))
        assertTrue(vm.execute(RemoteAction.SetPlaying(false)) is RemoteCommandResult.Unsupported)
    }

    @Test fun partialTransportKeysAndManagersAuthorizeOnlyTheirExactWireOperation() = runTest {
        val relative = player("exo" to engine(transport = setOf("seek_relative"), keys = setOf("key_enter")))
        val raw = state(relative)
        assertEquals(Support.Unsupported, raw.capabilities.transport.seekAbsolute)
        assertEquals(Support.Supported, raw.capabilities.transport.seekRelative)
        assertTrue(resolveRemoteLayout(raw, RemoteViewport()).wave)
        assertFalse(resolveRemoteLayout(raw, RemoteViewport()).transport.seek)
        assertTrue(raw.receiverActionSupported(RemoteAction.Key(RemoteKey.Enter)))
        assertFalse(raw.receiverActionSupported(RemoteAction.Key(RemoteKey.Ok)))
        val transport = FakeNativeRemoteTransport(RemoteReceiverSnapshot(raw))
        val registry = RemoteControllerRegistry(MutableStateFlow<RemoteTarget?>(RemoteNativeTarget(testConfiguration())), transport, backgroundScope)
        val vm = RemoteViewModel(registry, RemoteInputRouter(), { 0L }, backgroundScope)
        runCurrent()
        assertEquals(RemoteCommandResult.Submitted, vm.execute(RemoteAction.SeekBy(-10_000)))
        assertTrue(transport.commands.single().contains("seek_back"))
        assertTrue(vm.execute(RemoteAction.SeekTo(0)) is RemoteCommandResult.Unsupported)
        val browser = ReceiverDescriptor(1, mapOf("browser" to ReceiverSurface(mapOf("webview" to ReceiverEngine(browser = setOf("manage_user_agent"))))))
        val initial = state(browser, RemoteContext.Browser)
        val managerTransport = FakeNativeRemoteTransport(RemoteReceiverSnapshot(initial))
        val managerRegistry = RemoteControllerRegistry(MutableStateFlow<RemoteTarget?>(RemoteNativeTarget(testConfiguration())), managerTransport, backgroundScope)
        val managers = RemoteViewModel(managerRegistry, RemoteInputRouter(), { 0L }, backgroundScope)
        val effects = mutableListOf<RemoteEffect>()
        backgroundScope.launch { managers.effects.collect { effects += it } }
        runCurrent()
        assertTrue(managers.execute(RemoteAction.ManageUserScripts) is RemoteCommandResult.Unsupported)
        runCurrent()
        assertTrue(effects.none { it is RemoteEffect.OpenManager })
        assertEquals(RemoteCommandResult.Submitted, managers.execute(RemoteAction.UpdateUserAgent("synthetic", "synthetic", false)))
        assertTrue(managers.execute(RemoteAction.UpdateUserScript("synthetic.js", "synthetic")) is RemoteCommandResult.Unsupported)
        assertEquals(1, managerTransport.commands.size)
    }

    @Test fun contextAndMediaChangesUpdateSnapshotAuthorityAndRejectOldKeyboardContext() = runTest {
        val metadata = MutableStateFlow(ReceiverProtocolState(fixture("android-tv-gecko")))
        val transport = FakeNativeRemoteTransport(testSnapshot(RemoteContext.Browser))
        val controller = NativeProfileRemoteController(RemoteSessionId(remoteTestEndpoint, 1), testConfiguration().copy(receiverProtocol = metadata), transport, backgroundScope)
        val router = RemoteInputRouter()
        assertEquals(RemoteCommandResult.Submitted, router.execute(controller, RemoteAction.KeyboardInput(RemoteAction.ReplaceText("synthetic"), RemoteKeyboardRoute.PlayBridge, RemoteContext.Browser)))
        transport.snapshot.value = testSnapshot(RemoteContext.Player, RemoteMediaKind.Image)
        runCurrent()
        assertEquals(Support.Unsupported, controller.snapshot.value.state.capabilities.text.replace)
        assertTrue(controller.execute(RemoteAction.SeekTo(500)) is RemoteCommandResult.Unsupported)
        assertTrue(router.execute(controller, RemoteAction.KeyboardInput(RemoteAction.ReplaceText("synthetic"), RemoteKeyboardRoute.PlayBridge, RemoteContext.Browser)) is RemoteCommandResult.Failed)
        assertEquals(RemoteCommandResult.Submitted, controller.execute(RemoteAction.ImageZoom(1.2f)))
        transport.snapshot.value = testSnapshot(RemoteContext.Player, RemoteMediaKind.Video)
        runCurrent()
        assertEquals(Support.Unsupported, controller.snapshot.value.state.capabilities.pointer.zoom)
        assertEquals(RemoteCommandResult.Submitted, controller.execute(RemoteAction.SeekTo(500)))
    }

    @Test fun gestureCleanupDoesNotInventAnchorSupportAndReleasesAnEstablishedAnchor() = runTest {
        for (anchor in listOf(false, true)) {
            val descriptor = player("exo" to ReceiverEngine(imageTransforms = if (anchor) setOf("zoom", "anchor") else setOf("zoom")))
            val initial = state(descriptor).let { resolveReceiverRemotePolicy(it.copy(playback = it.playback?.copy(mediaKind = RemoteMediaKind.Image)), emptyList(), guarded) }
            val transport = FakeNativeRemoteTransport(RemoteReceiverSnapshot(initial))
            val controller = NativeProfileRemoteController(RemoteSessionId(remoteTestEndpoint, 1), testConfiguration(), transport, backgroundScope)
            if (anchor) assertEquals(RemoteCommandResult.Submitted, controller.execute(RemoteAction.ImageAnchor(0.5f, 0.5f)))
            assertEquals(RemoteCommandResult.Submitted, controller.execute(RemoteAction.ImageZoom(1.2f)))
            assertEquals(RemoteCommandResult.Submitted, controller.execute(RemoteAction.PointerGestureEnd))
            assertEquals(listOf(anchor), transport.anchorResets)
        }
    }

    @Test fun subtitleResourceAndUrlOnlyRoutesDoNotDiscardScopedHeaders() = runTest {
        val resource = SubtitleResource(url = "https://example.test/subtitle.srt", headers = mapOf("Referer" to "https://example.test"))
        for (structured in listOf(false, true)) {
            val descriptor = player("exo" to ReceiverEngine(tracks = setOf(if (structured) "subtitle_resource_add" else "subtitle_url_add")))
            val initial = state(descriptor)
            val transport = FakeNativeRemoteTransport(RemoteReceiverSnapshot(initial))
            val controller = NativeProfileRemoteController(RemoteSessionId(remoteTestEndpoint, 1), testConfiguration(features = guarded), transport, backgroundScope)
            val action = RemoteAction.AddSubtitle(resource)
            assertEquals(structured, initial.receiverActionSupported(action))
            if (structured) assertEquals(RemoteCommandResult.Confirmed, controller.execute(action))
            else {
                assertTrue(controller.execute(action) is RemoteCommandResult.Unsupported)
                assertTrue(transport.commands.isEmpty())
                assertTrue(transport.confirmations.isEmpty())
            }
            val local = RemoteAction.AddSubtitle(SubtitleResource(url = "http://example.test/subtitle.srt"), legacyUrlOnly = !structured)
            assertTrue(initial.receiverActionSupported(local))
            assertTrue(controller.execute(local) in setOf(RemoteCommandResult.Submitted, RemoteCommandResult.Confirmed))
        }
    }
}
