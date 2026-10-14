package com.playbridge.sender.cast.remote

import com.playbridge.sender.connection.ReceiverDescriptorParser
import com.playbridge.sender.connection.ReceiverProtocolSession
import com.playbridge.sender.connection.ReceiverProtocolState
import com.playbridge.sender.connection.ReceiverSettingsAvailability
import com.playbridge.sender.connection.ReceiverVolumeState
import com.playbridge.sender.connection.receiverFixture
import java.security.MessageDigest
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ReceiverProtocolPlumbingTest {
    @Test fun frozenProfilePrecedenceAndCaseSensitivityIgnoreDescriptorHostAndEngineHints() {
        assertEquals(RemoteProfile.AndroidTv, detectRemoteProfile(listOf("avplayer", "exo", "internal_mpv"), emptySet()))
        assertEquals(RemoteProfile.AppleTv, detectRemoteProfile(listOf("avplayer", "internal_mpv"), setOf("subtitle_resource_add_v1")))
        assertEquals(RemoteProfile.Desktop, detectRemoteProfile(listOf("internal_mpv"), setOf("subtitle_resource_add_v1", "progress_webhook_v1")))
        for (players in listOf(emptyList(), listOf("EXO"), listOf("AVPlayer"), listOf("internal_mpv"))) {
            assertEquals(RemoteProfile.Unknown, detectRemoteProfile(players, emptySet()))
        }
    }

    private fun descriptor(name: String) = ReceiverDescriptorParser.parse(receiverFixture(name)["capabilities"])
    private val metadataVariants get() = listOf(
        ReceiverProtocolState(),
        ReceiverProtocolState(descriptor("empty-complete-descriptor.json")),
        ReceiverProtocolState(descriptor("android-tv-auth-v1.json")),
        ReceiverProtocolState(descriptor("unknown-identifiers-auth-v1.json")),
        ReceiverProtocolState(descriptor("unknown-version-auth-future.json")),
        ReceiverProtocolState(descriptor("future-apple-app-volume.json"), ReceiverVolumeState("app", 0.75, true),
            ReceiverSettingsAvailability(false, false, false), "future_engine"),
    )

    private val legacyMetadataVariants get() = listOf(
        ReceiverProtocolState(),
        ReceiverProtocolState(volume = ReceiverVolumeState("app", 0.75, true)),
        ReceiverProtocolState(settingsAvailability = ReceiverSettingsAvailability(false, false, false)),
        ReceiverProtocolState(contextEngine = "future_engine"),
        ReceiverProtocolState(descriptor("unknown-version-auth-future.json")),
        ReceiverProtocolState(volume = ReceiverVolumeState("app", 0.75, true),
            settingsAvailability = ReceiverSettingsAvailability(false, false, false), contextEngine = "future_engine"),
    )

    @Test fun legacyCapabilityGoldenBytesRemainIdenticalWithEveryMetadataVariant() = runTest {
        val golden = MessageDigest.getInstance("SHA-256")
        var comparisons = 0
        for (profile in RemoteProfile.entries.filter { it != RemoteProfile.External }) {
            for (context in RemoteContext.entries) for (kind in RemoteMediaKind.entries) for (runtime in 0..2) {
                val config = testConfiguration(profile, if (runtime == 2) setOf(
                    "player_home_v1", "queue_crud_v1", "stable_item_ids", "command_results", "subtitle_resource_add_v1",
                ) else emptySet())
                val raw = testSnapshot(context, kind).copy(state = testSnapshot(context, kind).state.copy(
                    settings = RemotePlayerSettings(availability = if (runtime == 1) PlayerSetting.entries.associateWith { Support.Unsupported } else emptyMap()),
                    tracks = if (runtime == 2) RemoteTracks(audio = listOf(RemoteTrack("a", "Audio", true)), subtitles = listOf(RemoteTrack("s", "Sub", false))) else RemoteTracks(),
                    queue = if (runtime == 0) null else testSnapshot().state.queue,
                    browser = if (context == RemoteContext.Browser) RemoteBrowserState(if (runtime == 1) RemoteBrowserEngine.Gecko else RemoteBrowserEngine.WebView) else null,
                    playback = testSnapshot(context, kind).state.playback?.copy(isLive = runtime == 1, seekable = if (runtime == 1) Support.Unsupported else Support.Supported),
                ))
                val transport = FakeNativeRemoteTransport(raw)
                val metadata = MutableStateFlow(ReceiverProtocolState())
                val controller = NativeProfileRemoteController(RemoteSessionId(remoteTestEndpoint, 1), config.copy(receiverProtocol = metadata), transport, backgroundScope)
                val baseline = controller.snapshot.value.state
                val bytes = baseline.capabilities.toString().toByteArray(Charsets.UTF_8)
                golden.update("$profile/$context/$kind/$runtime\n".toByteArray())
                golden.update(bytes)
                golden.update('\n'.code.toByte())
                for (variant in legacyMetadataVariants) {
                    metadata.value = variant; runCurrent()
                    val state = controller.snapshot.value.state
                    assertEquals(variant, state.receiverProtocol)
                    assertArrayEquals(bytes, state.capabilities.toString().toByteArray(Charsets.UTF_8))
                    assertEquals(resolveRemoteLayout(baseline, RemoteViewport()), resolveRemoteLayout(state, RemoteViewport()))
                    for (connected in listOf(false, true)) {
                        val channel = TvRemoteChannelState(remoteTestEndpoint, "synthetic-tv", true, connected,
                            setOf(RemoteKey.Home, RemoteKey.Back), VolumeCapabilities(adjust = Support.Supported, level = 2, maximum = 10, muted = false),
                            TextCapabilities(Support.Supported, Support.Supported, Support.Supported))
                        val before = projectFullControl(baseline, baseline.capabilities, channel)
                        val after = projectFullControl(state, state.capabilities, channel)
                        assertArrayEquals(before.capabilities.toString().toByteArray(), after.capabilities.toString().toByteArray())
                        assertEquals(before.keyboardRoute(), after.keyboardRoute())
                        assertEquals(variant, after.receiverProtocol)
                    }
                    comparisons++
                }
            }
        }
        assertEquals(1350, comparisons)
        val hash = golden.digest().joinToString("") { "%02x".format(it) }
        // Frozen against origin/main's unchanged nativeRemoteCapabilities function.
        assertEquals("ff9b528eb9fec22d4ce829d20ca3350df4df50348019ccb785c0f6373b0caf4a", hash)
    }

    @Test fun metadataRefreshExposesNewStateWithoutReplacingRegistryControllerOrMediaIdentity() = runTest {
        val owner = ReceiverProtocolSession()
        val session = owner.begin()
        val config = testConfiguration().copy(receiverProtocol = owner.state)
        val targets = MutableStateFlow<RemoteTarget?>(RemoteNativeTarget(config))
        val registry = RemoteControllerRegistry(targets, FakeNativeRemoteTransport(), backgroundScope)
        val controller = registry.controller.value!!
        val before = controller.snapshot.value
        owner.acceptCredentials(session, receiverFixture("android-tv-auth-v1.json"))
        owner.observe(session, receiverFixture("future-apple-complete-settings.json"))
        owner.observe(session, receiverFixture("volume-app-state.json"))
        owner.observe(session, receiverFixture("browser-active-engine.json"))
        // Mirrors registry.create(): the flow reference, legacy players/features and route are unchanged.
        targets.value = RemoteNativeTarget(config.copy(receiverProtocol = owner.state))
        runCurrent()
        assertSame(controller, registry.controller.value)
        val after = controller.snapshot.value
        assertEquals(before.state.sessionId, after.state.sessionId)
        assertEquals(before.state.playback?.mediaId, after.state.playback?.mediaId)
        assertEquals(owner.state.value, after.state.receiverProtocol)
        assertNotEquals(before.state.capabilities, after.state.capabilities)
        owner.clear(); runCurrent()
        assertEquals(ReceiverProtocolState(), controller.snapshot.value.state.receiverProtocol)
    }

    @Test fun disconnectedSnapshotNeverExposesRetainedMetadata() = runTest {
        val transport = FakeNativeRemoteTransport()
        val metadata = MutableStateFlow(metadataVariants.last())
        val config = testConfiguration().copy(receiverProtocol = metadata)
        val controller = NativeProfileRemoteController(RemoteSessionId(remoteTestEndpoint, 1), config, transport, backgroundScope)
        assertNotNull(controller.snapshot.value.state.receiverProtocol.descriptor)
        transport.snapshot.value = testSnapshot().copy(state = testSnapshot().state.copy(connection = RemoteConnection.Disconnected))
        runCurrent()
        assertEquals(ReceiverProtocolState(), controller.snapshot.value.state.receiverProtocol)
    }

    @Test fun screenMirrorAndReducerPreserveMetadataWithoutChangingLegacyGatingOrKeyboardDraftLifetime() = runTest {
        val transport = FakeNativeRemoteTransport(testSnapshot(RemoteContext.Browser))
        val metadata = MutableStateFlow(ReceiverProtocolState())
        val config = testConfiguration().copy(receiverProtocol = metadata)
        val base = NativeProfileRemoteController(RemoteSessionId(remoteTestEndpoint, 1), config, transport, backgroundScope)
        val controller = ScreenMirrorRemoteController(base, transport, backgroundScope)
        val reducer = RemoteStateReducer { 0L }
        reducer.observe(controller.snapshot.value)
        reducer.local(RemoteAction.OpenSheet(RemoteSheet.Keyboard))
        metadata.value = metadataVariants.last(); runCurrent()
        val browser = reducer.observe(controller.snapshot.value)
        assertEquals(RemoteSheet.Keyboard, browser.sheet)
        assertEquals(metadata.value, browser.receiverProtocol)
        transport.snapshot.value = testSnapshot(RemoteContext.ScreenMirror); runCurrent()
        val mirror = controller.snapshot.value.state
        assertEquals(metadata.value, mirror.receiverProtocol)
        assertEquals(RemoteContext.ScreenMirror, mirror.context)
        assertTrue(mirror.capabilities.settings.isEmpty())
        assertEquals(Support.Unsupported, mirror.capabilities.text.replace)
    }

    @Test fun descriptorAndAvailabilityFlagsDoNotChangeExecutionOrOutgoingCommands() = runTest {
        for (profile in listOf(RemoteProfile.AndroidTv, RemoteProfile.AppleTv, RemoteProfile.Desktop, RemoteProfile.Cli, RemoteProfile.Unknown)) {
            for (context in listOf(RemoteContext.Player, RemoteContext.Browser)) {
                val legacy = FakeNativeRemoteTransport(testSnapshot(context))
                val plumbing = FakeNativeRemoteTransport(testSnapshot(context))
                val metadata = MutableStateFlow(legacyMetadataVariants.last())
                val config = testConfiguration(profile)
                val id = RemoteSessionId(remoteTestEndpoint, 1)
                val before = NativeProfileRemoteController(id, config, legacy, backgroundScope)
                val after = NativeProfileRemoteController(id, config.copy(receiverProtocol = metadata), plumbing, backgroundScope)
                val actions = listOf(RemoteAction.SetPlaying(false), RemoteAction.Key(RemoteKey.Home), RemoteAction.Key(RemoteKey.Up),
                    RemoteAction.AdjustVolume(1), RemoteAction.SetVolume(75), RemoteAction.SetLoop(true), RemoteAction.SwitchEngine("mpv"),
                    RemoteAction.AdjustSubtitleOffset(500), RemoteAction.ReplaceText("synthetic text"), RemoteAction.DeleteText,
                    RemoteAction.PointerClick, RemoteAction.SeekTo(15_000))
                for (action in actions) assertEquals("$profile/$context/$action", before.execute(action), after.execute(action))
                assertEquals(legacy.commands, plumbing.commands)
                assertEquals(legacy.pointers, plumbing.pointers)
            }
        }
    }

    @Test fun malformedV1CarriesReportsButFailsClosedForReceiverControls() = runTest {
        val owner = ReceiverProtocolSession()
        val session = owner.begin()
        owner.acceptCredentials(session, Json.parseToJsonElement("""{"type":"auth_response","success":true,"capabilities":{"version":1,"surfaces":[]}}""") as JsonObject)
        owner.observe(session, receiverFixture("volume-app-state.json"))
        val transport = FakeNativeRemoteTransport()
        val config = testConfiguration().copy(receiverProtocol = owner.state)
        val controller = NativeProfileRemoteController(RemoteSessionId(remoteTestEndpoint, 1), config, transport, backgroundScope)
        assertNull(controller.snapshot.value.state.receiverProtocol.descriptor)
        assertNotNull(controller.snapshot.value.state.receiverProtocol.volume)
        assertTrue(controller.snapshot.value.state.receiverProtocol.malformedV1)
        assertEquals(Support.Unsupported, controller.snapshot.value.state.capabilities.transport.playPause)
        assertEquals(Support.Unsupported, controller.snapshot.value.state.capabilities.volume.adjust)
        assertTrue(controller.snapshot.value.state.capabilities.keys.isEmpty())
    }
}
