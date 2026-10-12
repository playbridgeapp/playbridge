package com.playbridge.sender.cast.remote

import com.playbridge.sender.cast.Capability
import com.playbridge.sender.cast.TargetKind
import com.playbridge.sender.cast.PlaybackState
import com.playbridge.sender.cast.SessionPhase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteCapabilityPolicyTest {
    private val nativeProfiles = RemoteProfile.entries.filter { it != RemoteProfile.External }
    private fun state(profile: RemoteProfile, context: RemoteContext, kind: RemoteMediaKind): RemoteUiState {
        val raw = testSnapshot(context, kind).state
        return raw.copy(receiver = testConfiguration(profile).receiver, capabilities = nativeRemoteCapabilities(profile, context, kind))
    }

    @Test fun profileDetectionUsesVerifiedAdsAndNeverAssumesMpvMeansDesktop() {
        assertEquals(RemoteProfile.AndroidTv, detectRemoteProfile(listOf("exo", "mpv"), emptySet()))
        assertEquals(RemoteProfile.AppleTv, detectRemoteProfile(listOf("avplayer", "mpv"), emptySet()))
        assertEquals(RemoteProfile.Desktop, detectRemoteProfile(listOf("internal_mpv"), setOf("subtitle_resource_add_v1")))
        assertEquals(RemoteProfile.Cli, detectRemoteProfile(listOf("internal_mpv"), setOf("progress_webhook_v1")))
        assertEquals(RemoteProfile.Unknown, detectRemoteProfile(listOf("internal_mpv"), emptySet()))
        assertEquals(RemoteProfile.Unknown, detectRemoteProfile(emptyList(), emptySet()))
    }
    @Test fun nativeTruthTableExposesOnlyUsefulInputBodiesAndPermanentLayers() {
        for (profile in nativeProfiles) for (context in RemoteContext.entries) for (kind in RemoteMediaKind.entries) {
            val layout = resolveRemoteLayout(state(profile, context, kind), RemoteViewport())
            val active = context in setOf(RemoteContext.Player, RemoteContext.Browser)
            val expected = buildList {
                if (!active) add(RemoteInputMode.Context)
                if (profile == RemoteProfile.AndroidTv && (context == RemoteContext.Browser || context == RemoteContext.Player && kind == RemoteMediaKind.Image)) add(RemoteInputMode.Pointer)
                if (profile == RemoteProfile.Desktop && context == RemoteContext.Player && kind == RemoteMediaKind.Image) add(RemoteInputMode.Pointer)
            }
            assertEquals("$profile/$context/$kind", expected, layout.modes)
            assertEquals(expected.isNotEmpty(), layout.input)
            assertTrue(layout.header && layout.navLayer)
            assertFalse(layout.transport.visible)
            assertEquals(!active, layout.idle)
            assertEquals(context == RemoteContext.Browser, layout.browserRow)
            assertEquals(profile == RemoteProfile.AndroidTv && active, layout.nav.back.enabled)
            assertEquals(profile == RemoteProfile.AndroidTv && context == RemoteContext.Browser, layout.nav.home.enabled)
            assertEquals(profile in setOf(RemoteProfile.AndroidTv, RemoteProfile.Desktop), layout.nav.volume.enabled)
            assertEquals(context == RemoteContext.Player && kind != RemoteMediaKind.Image, layout.transport.seek)
        }
    }
    @Test fun reconnectPairingFailureAndDisconnectDimReceiverControlsWithoutRemovingLayers() {
        val connections = listOf(RemoteConnection.Disconnected, RemoteConnection.Connecting("TV"), RemoteConnection.Reconnecting(1, 3, 2), RemoteConnection.Pairing("TV", RemoteConnection.Pairing.Phase.Approval), RemoteConnection.Failed("Lost"))
        for (connection in connections) {
            val layout = resolveRemoteLayout(state(RemoteProfile.AndroidTv, RemoteContext.Browser, RemoteMediaKind.Video).copy(connection = connection), RemoteViewport())
            assertTrue(layout.header && layout.navLayer)
            assertFalse(layout.nav.back.enabled || layout.nav.home.enabled || layout.nav.keys.enabled || layout.nav.volume.enabled || layout.transport.enabled)
        }
    }
    @Test fun reportedAvailabilityControlsCommandsWithoutInventingLoopOrFullscreen() {
        val settings = RemotePlayerSettings(availability = mapOf(PlayerSetting.Speed to Support.Unsupported, PlayerSetting.Quality to Support.Supported))
        val caps = nativeRemoteCapabilities(RemoteProfile.AndroidTv, RemoteContext.Player, RemoteMediaKind.Video, settings = settings)
        assertFalse(PlayerSetting.Speed in caps.settings)
        assertTrue(PlayerSetting.Quality in caps.settings)
        assertEquals(Support.Unknown, RemoteCapabilities().pointer.move)
        assertEquals(Support.Unsupported, nativeRemoteCapabilities(RemoteProfile.Cli, RemoteContext.Player, RemoteMediaKind.Video).pointer.move)
        assertNull(settings.loopEnabled)
        assertNull(RemoteBrowserState().fullscreen)
    }
    @Test fun browserTimelineIsolatedFromNativeSettingsAndGeckoCannotZoom() {
        val raw = state(RemoteProfile.AndroidTv, RemoteContext.Browser, RemoteMediaKind.Video)
        val quiet = raw.copy(playback = raw.playback?.copy(reportedState = RemotePlaybackState.Idle))
        assertFalse(resolveRemoteLayout(quiet, RemoteViewport()).wave)
        val active = resolveRemoteLayout(raw, RemoteViewport())
        assertTrue(active.wave)
        assertFalse(active.transport.visible)
        val caps = nativeRemoteCapabilities(RemoteProfile.AndroidTv, RemoteContext.Browser, RemoteMediaKind.Video, browserEngine = RemoteBrowserEngine.Gecko)
        assertEquals(Support.Unsupported, caps.pointer.zoom)
        assertTrue(caps.settings.isEmpty())
        assertEquals(Support.Unsupported, caps.tracks.subtitle)
    }
    @Test fun queueNeighborsUseActualIndicesAndMissingCurrentHasNoNeighbors() {
        val raw = state(RemoteProfile.AndroidTv, RemoteContext.Player, RemoteMediaKind.Video)
        val queue = raw.queue!!
        fun transport(index: Int, items: List<RemoteQueueItem> = queue.items) = resolveRemoteLayout(raw.copy(queue = queue.copy(currentIndex = index, items = items)), RemoteViewport()).transport
        assertNull(transport(3).previous); assertEquals(8, transport(3).next)
        assertEquals(3, transport(8).previous); assertEquals(12, transport(8).next)
        assertNull(transport(12).next)
        assertNull(transport(99).previous); assertNull(transport(99).next)
        assertNull(transport(3, queue.items.take(1)).next)
        assertNull(transport(3, emptyList()).next)
    }
    @Test fun liveImagesAndAudioKeepStageOneTrackAndTransportPresentation() {
        val image = state(RemoteProfile.AndroidTv, RemoteContext.Player, RemoteMediaKind.Image)
        assertFalse(resolveRemoteLayout(image.copy(playback = image.playback?.copy(durationMs = 0)), RemoteViewport()).transport.playPause)
        assertTrue(resolveRemoteLayout(image, RemoteViewport()).transport.playPause)
        assertFalse(resolveRemoteLayout(image, RemoteViewport()).tracks.subtitles)
        val audio = state(RemoteProfile.AndroidTv, RemoteContext.Player, RemoteMediaKind.Audio)
        assertFalse(resolveRemoteLayout(audio, RemoteViewport()).tracks.subtitles)
        assertFalse(resolveRemoteLayout(audio.copy(playback = audio.playback?.copy(isLive = true)), RemoteViewport()).transport.seek)
    }
    @Test fun unsupportedModeFallsBackAndAdaptiveGeometryWaitsForStageThree() {
        val raw = state(RemoteProfile.Cli, RemoteContext.Player, RemoteMediaKind.Video).copy(inputMode = RemoteInputMode.Keyboard)
        val normal = resolveRemoteLayout(raw, RemoteViewport())
        assertEquals(RemoteInputMode.Context, normal.selectedMode)
        assertFalse(normal.showModeRow)
        assertEquals(normal, resolveRemoteLayout(raw, RemoteViewport(800, 300, true, 2f, 60)))
    }
    @Test fun conservativeUnknownRequiresObservedTracksAndQueueAndNeverEnablesInput() {
        val none = nativeRemoteCapabilities(RemoteProfile.Unknown, RemoteContext.Player, RemoteMediaKind.Video)
        assertEquals(Support.Unsupported, none.tracks.audio)
        assertEquals(Support.Unsupported, none.queue.jumpIndex)
        val observed = nativeRemoteCapabilities(RemoteProfile.Unknown, RemoteContext.Player, RemoteMediaKind.Video, observedTracks = RemoteTracks(audio = listOf(RemoteTrack("a", "Audio", true))), observedQueue = testSnapshot().state.queue)
        assertEquals(Support.Supported, observed.tracks.audio)
        assertEquals(Support.Supported, observed.queue.jumpIndex)
        assertTrue(observed.keys.isEmpty())
        assertEquals(Support.Unsupported, observed.volume.adjust)
    }
    @Test fun externalRowsKeepStageOneModesNavigationAndNoEpisodeControls() {
        for (kind in listOf(TargetKind.GOOGLE_CAST, TargetKind.ROKU, TargetKind.DLNA, TargetKind.WEB_BROWSER)) for (playback in PlaybackState.entries) {
            val transport = FakeExternalRemoteTransport(kind)
            transport.queue = RemoteQueueState(currentIndex = 8, items = testSnapshot().state.queue!!.items, phoneOwned = true)
            transport.tick(state = playback)
            val state = transport.snapshot.value.receiverSnapshot.state
            val layout = resolveRemoteLayout(state, RemoteViewport())
            val player = playback in setOf(PlaybackState.PLAYING, PlaybackState.PAUSED, PlaybackState.BUFFERING)
            assertEquals(if (player) RemoteContext.Player else RemoteContext.Idle, state.context)
            val expected = buildList {
                if (!player && kind != TargetKind.ROKU) add(RemoteInputMode.Context)
                if (kind == TargetKind.ROKU) add(RemoteInputMode.Pad)
            }
            assertEquals(expected, layout.modes)
            assertEquals(!player || kind == TargetKind.ROKU, layout.input)
            assertFalse(layout.transport.visible)
            assertEquals(player && kind != TargetKind.ROKU, layout.transport.seek)
            assertEquals(kind == TargetKind.ROKU && playback != PlaybackState.ERROR, layout.nav.home.enabled)
            assertFalse(layout.nav.keys.enabled || layout.showQueue || layout.tracks.audio || layout.tracks.subtitles)
            assertNull(layout.transport.previous); assertNull(layout.transport.next)
            assertTrue(state.queue!!.phoneOwned && state.capabilities.queue.phoneOwned)
            assertEquals(Support.Unsupported, state.capabilities.queue.jumpIndex)
        }
    }
    @Test fun externalVolumeScopesAndRuntimeLossKeepConservativeUnknownFacts() {
        for ((kind, scope) in listOf(TargetKind.GOOGLE_CAST to VolumeScope.ReceiverDevice, TargetKind.ROKU to VolumeScope.ReceiverDevice, TargetKind.DLNA to VolumeScope.Renderer, TargetKind.WEB_BROWSER to VolumeScope.Player)) {
            val caps = externalRemoteCapabilities(kind, externalFixtureCapabilities(kind))
            assertEquals(scope, caps.volume.scope); assertNull(caps.volume.reportedPercent)
            val lost = externalRemoteCapabilities(kind, externalFixtureCapabilities(kind) - Capability.VOLUME, false)
            assertEquals(Support.Unsupported, lost.volume.adjust)
            assertTrue(lost.settings.isEmpty() && lost.engines.isEmpty())
            assertEquals(Support.Unsupported, lost.tracks.subtitle)
        }
        assertEquals(Support.Unknown, externalRemoteCapabilities(TargetKind.DLNA, emptySet()).volume.adjust)
        val unknown = externalRemoteCapabilities(TargetKind.NATIVE, Capability.entries.toSet())
        assertEquals(Support.Unknown, unknown.volume.adjust)
        assertEquals(Support.Unsupported, unknown.transport.seekAbsolute)
        assertTrue(unknown.keys.isEmpty())
    }
    @Test fun externalReadinessUsesSessionPhaseAndErrorWhileLiveMediaCannotSeek() {
        for (kind in listOf(TargetKind.GOOGLE_CAST, TargetKind.ROKU, TargetKind.DLNA, TargetKind.WEB_BROWSER)) {
            val transport = FakeExternalRemoteTransport(kind)
            transport.phase = SessionPhase.CONNECTING; transport.tick(live = true)
            val state = transport.snapshot.value.receiverSnapshot.state
            assertEquals(kind in setOf(TargetKind.ROKU, TargetKind.DLNA), state.connection.ready)
            assertFalse(resolveRemoteLayout(state, RemoteViewport()).transport.seek)
            transport.phase = SessionPhase.CONNECTED; transport.tick(state = PlaybackState.ERROR)
            assertFalse(transport.snapshot.value.receiverSnapshot.state.connection.ready)
        }
    }

    @Test fun playerVideoHasNoPadBrowserIsPointerAndIdleIsContext() {
        val player = resolveRemoteLayout(state(RemoteProfile.AndroidTv, RemoteContext.Player, RemoteMediaKind.Video), RemoteViewport())
        assertFalse(player.input)
        assertTrue(player.modes.isEmpty())
        val browser = resolveRemoteLayout(state(RemoteProfile.AndroidTv, RemoteContext.Browser, RemoteMediaKind.Video).copy(inputMode = RemoteInputMode.Context), RemoteViewport())
        assertEquals(RemoteInputMode.Pointer, browser.selectedMode)
        assertEquals(listOf(RemoteInputMode.Pointer), browser.modes)
        val idle = resolveRemoteLayout(state(RemoteProfile.AndroidTv, RemoteContext.Idle, RemoteMediaKind.Video), RemoteViewport())
        assertEquals(RemoteInputMode.Context, idle.selectedMode)
        assertEquals(listOf(RemoteInputMode.Context), idle.modes)
    }

    @Test fun validUserInputPreferenceSurvivesAndLostPointerRouteFallsBackToPad() {
        val raw = state(RemoteProfile.AndroidTv, RemoteContext.Browser, RemoteMediaKind.Video)
        assertEquals(RemoteInputMode.Pointer, resolveRemoteLayout(raw.copy(inputMode = RemoteInputMode.Pad), RemoteViewport()).selectedMode)
        val whole = raw.copy(capabilities = raw.capabilities.copy(keyScope = ControlScope.WholeDevice))
        assertEquals(RemoteInputMode.Pad, resolveRemoteLayout(whole.copy(inputMode = RemoteInputMode.Pad), RemoteViewport()).selectedMode)
        val lost = whole.copy(inputMode = RemoteInputMode.Pointer, capabilities = whole.capabilities.copy(pointer = PointerCapabilities()))
        val layout = resolveRemoteLayout(lost, RemoteViewport())
        assertFalse(RemoteInputMode.Pointer in layout.modes)
        assertEquals(RemoteInputMode.Pad, layout.selectedMode)
    }

    @Test fun keyboardSheetAffordanceAloneCannotCreateAnEmptyInputBody() {
        val raw = state(RemoteProfile.AndroidTv, RemoteContext.Browser, RemoteMediaKind.Video)
        val layout = resolveRemoteLayout(raw.copy(capabilities = raw.capabilities.copy(keys = emptySet(), pointer = PointerCapabilities())), RemoteViewport())
        assertTrue(layout.modes.isEmpty())
        assertFalse(layout.input)
        assertTrue(layout.nav.keys.enabled)
        assertEquals(RemoteInputMode.Pointer, resolveRemoteLayout(raw.copy(inputMode = RemoteInputMode.Keyboard), RemoteViewport()).selectedMode)
    }

}
