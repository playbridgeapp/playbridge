package com.playbridge.sender.cast.remote

import com.playbridge.sender.connection.QueueCommandResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import playbridge.SubtitleResource

@OptIn(ExperimentalCoroutinesApi::class)
class RemoteViewModelTest {
    private class Fixture(val scope: TestScope, features: Set<String> = emptySet(), initial: RemoteReceiverSnapshot = testSnapshot(), profile: RemoteProfile = RemoteProfile.AndroidTv, preferenceStore: RemotePresentationPreferenceStore? = null, progressSource: RemoteQueueProgressSource? = null) {
        val transport = FakeNativeRemoteTransport(initial)
        val target = MutableStateFlow<RemoteNativeTarget?>(RemoteNativeTarget(testConfiguration(profile, features), "connection-1"))
        val registry = RemoteControllerRegistry(target, transport, scope.backgroundScope)
        var now = 10L
        val vm = RemoteViewModel(registry, RemoteInputRouter(), { now }, scope.backgroundScope, preferenceStore, progressSource)
        val effects = mutableListOf<RemoteEffect>()
        init { scope.backgroundScope.launch(UnconfinedTestDispatcher(scope.testScheduler)) { vm.effects.collect { effects += it } } }
        fun tick(position: Long = 10_000L, playing: Boolean = true, fresh: Boolean = true, title: String? = "Episode", generation: Long = 0L) {
            val old = transport.snapshot.value
            transport.snapshot.value = old.copy(
                state = old.state.copy(playback = old.state.playback?.copy(reportedPositionMs = position, reportedState = if (playing) RemotePlaybackState.Playing else RemotePlaybackState.Paused, title = title)),
                observation = old.observation.copy(sequence = old.observation.sequence + 1L, playbackSequence = old.observation.playbackSequence + if (fresh) 1L else 0L, playbackAtMs = if (fresh) now else old.observation.playbackAtMs, mediaGeneration = generation),
            )
        }
        val playback get() = vm.state.value.playback!!
    }

    @Test fun seekOverlaySurvivesStaleTicksAndOnlyFreshMatchingStatusConfirmsIt() = runTest {
        val f = Fixture(this); runCurrent()
        assertEquals(RemoteCommandResult.Submitted, f.vm.execute(RemoteAction.SeekTo(60_000L)))
        assertEquals(10_000L, f.playback.reportedPositionMs); assertEquals(60_000L, f.playback.displayedPositionMs)
        assertTrue(f.vm.state.value.pending.seek)
        f.now++
        f.tick(20_000L); runCurrent()
        assertEquals(60_000L, f.playback.displayedPositionMs)
        f.tick(60_000L, fresh = false); runCurrent()
        assertTrue(f.vm.state.value.pending.seek)
        f.now++; f.tick(59_000L); runCurrent()
        assertFalse(f.vm.state.value.pending.seek); assertEquals(59_000L, f.playback.displayedPositionMs)
    }
    @Test fun rapidRelativeSeeksAndTogglesUseDisplayedStateBeforeRecomposition() = runTest {
        val f = Fixture(this); runCurrent()
        f.vm.dispatch(RemoteAction.SeekBy(10_000L)); f.vm.dispatch(RemoteAction.SeekBy(10_000L))
        f.vm.dispatch(RemoteAction.TogglePlayPause); f.vm.dispatch(RemoteAction.TogglePlayPause)
        assertEquals(30_000L, f.playback.displayedPositionMs); assertTrue(f.playback.displayedPlaying)
        assertEquals(10_000L, f.playback.reportedPositionMs)
        runCurrent()
        val commands = f.transport.commands.map { JSONObject(it).getJSONObject("payload").getString("command") }
        assertEquals(listOf("seek_to:20000", "seek_to:30000", "pause", "play"), commands)
        // Identical receiver state can still settle the second toggle via observation metadata.
        f.now++; f.tick(); runCurrent()
        assertFalse(f.vm.state.value.pending.playing); assertTrue(f.vm.state.value.pending.seek)
    }
    @Test fun expiredIntentAndSendFailureReturnToReportedState() = runTest {
        val f = Fixture(this); runCurrent()
        f.vm.dispatch(RemoteAction.SeekTo(80_000L)); runCurrent()
        f.now += 2_999L; advanceTimeBy(100L); runCurrent()
        assertTrue(f.vm.state.value.pending.seek)
        f.now++; advanceTimeBy(100L); runCurrent()
        assertFalse(f.vm.state.value.pending.seek); assertEquals(10_000L, f.playback.displayedPositionMs)
        f.transport.accepted = false
        assertTrue(f.vm.execute(RemoteAction.TogglePlayPause) is RemoteCommandResult.Failed)
        assertFalse(f.vm.state.value.pending.playing); assertTrue(f.playback.displayedPlaying)
    }
    @Test fun replacementSeekGetsItsOwnDeadlineAndFailureCannotClearNewerIntent() {
        var now = 10L
        val reducer = RemoteStateReducer { now }
        val id = RemoteSessionId(remoteTestEndpoint, 1L)
        val raw = testSnapshot()
        reducer.observe(raw.copy(state = raw.state.copy(sessionId = id)))
        reducer.begin(RemoteAction.SeekTo(60_000L)); val first = reducer.intentVersion
        now += 2_000L; reducer.begin(RemoteAction.SeekTo(5_000L))
        reducer.failure(first, "late failure")
        now += 1_000L; reducer.expire()
        assertEquals(5_000L, reducer.state.playback?.displayedPositionMs)
        now += 2_000L; reducer.expire()
        assertFalse(reducer.state.pending.seek)
    }
    @Test fun mediaContextDisconnectAndPlaybackErrorClearIntentsAndSheets() = runTest {
        val f = Fixture(this); runCurrent()
        fun pending() { f.vm.dispatch(RemoteAction.SeekTo(60_000L)); f.vm.dispatch(RemoteAction.OpenSheet(RemoteSheet.More)) }
        pending(); f.now++; f.tick(title = "New Episode", generation = 1L); runCurrent()
        assertFalse(f.vm.state.value.pending.seek); assertNull(f.vm.state.value.sheet)
        pending(); val raw = f.transport.snapshot.value
        f.transport.snapshot.value = raw.copy(state = raw.state.copy(context = RemoteContext.Browser)); runCurrent()
        assertFalse(f.vm.state.value.pending.seek); assertNull(f.vm.state.value.sheet)
        pending(); f.transport.snapshot.value = f.transport.snapshot.value.copy(state = f.transport.snapshot.value.state.copy(connection = RemoteConnection.Disconnected)); runCurrent()
        assertFalse(f.vm.state.value.pending.seek); assertNull(f.vm.state.value.sheet)
        f.transport.snapshot.value = raw; runCurrent(); pending()
        f.transport.snapshot.value = raw.copy(state = raw.state.copy(playback = raw.state.playback?.copy(reportedState = RemotePlaybackState.Error))); runCurrent()
        assertFalse(f.vm.state.value.pending.seek); assertNull(f.vm.state.value.sheet)
    }
    @Test fun targetSwitchAndReconnectFenceOldControllerAndResetOptimism() = runTest {
        val f = Fixture(this); runCurrent()
        val old = f.registry.controller.value!!
        f.vm.execute(RemoteAction.SeekTo(80_000L))
        f.target.value = null
        // Even before the collector runs, the old controller cannot send into another route.
        assertTrue(old.execute(RemoteAction.SetPlaying(false)) is RemoteCommandResult.Failed)
        runCurrent(); assertNull(f.vm.state.value.sessionId)
        f.target.value = RemoteNativeTarget(testConfiguration(), "connection-2"); runCurrent()
        val fresh = f.registry.controller.value!!
        assertNotEquals(old.sessionId, fresh.sessionId)
        assertFalse(f.vm.state.value.pending.seek)
        assertTrue(old.execute(RemoteAction.SetPlaying(false)) is RemoteCommandResult.Failed)
        assertEquals(1, f.transport.commands.size)
    }
    @Test fun registryAlsoFencesAuthoritativeRouteBeforeCombinedTargetEmits() = runTest {
        var native = true
        val transport = FakeNativeRemoteTransport()
        val targets = MutableStateFlow<RemoteNativeTarget?>(RemoteNativeTarget(testConfiguration()))
        val registry = RemoteControllerRegistry(targets, transport, backgroundScope) { native }
        val controller = registry.controller.value!!
        native = false
        assertTrue(controller.execute(RemoteAction.Key(RemoteKey.Back)) is RemoteCommandResult.Failed)
        assertTrue(transport.commands.isEmpty())
    }
    @Test fun lateSubtitleFailureFromOldSessionCannotCloseNewSheetOrEmitNotice() = runTest {
        val f = Fixture(this, setOf("subtitle_resource_add_v1")); runCurrent()
        val response = CompletableDeferred<QueueCommandResult?>()
        f.transport.confirmation = { response.await() }
        f.vm.dispatch(RemoteAction.OpenSheet(RemoteSheet.AddSubtitle))
        val operation = async { f.vm.execute(RemoteAction.AddSubtitle(SubtitleResource(url = "https://example.test/sub.srt"))) }
        runCurrent(); assertNull(f.vm.state.value.sheet); assertEquals(1, f.vm.state.value.pending.subtitleRequests.size)
        f.target.value = RemoteNativeTarget(testConfiguration(features = setOf("subtitle_resource_add_v1")), "connection-2"); runCurrent()
        f.vm.dispatch(RemoteAction.OpenSheet(RemoteSheet.More))
        response.complete(QueueCommandResult(false, "invalid_command")); operation.await(); runCurrent()
        assertEquals(RemoteSheet.More, f.vm.state.value.sheet); assertTrue(f.effects.isEmpty())
        assertTrue(f.vm.state.value.pending.subtitleRequests.isEmpty())
    }
    @Test fun lateSubtitleFailureFromOldMediaCannotAffectNewMedia() = runTest {
        val f = Fixture(this, setOf("subtitle_resource_add_v1")); runCurrent()
        val response = CompletableDeferred<QueueCommandResult?>(); f.transport.confirmation = { response.await() }
        val operation = async { f.vm.execute(RemoteAction.AddSubtitle(SubtitleResource(url = "https://example.test/sub.srt"))) }
        runCurrent(); f.now++; f.tick(title = "Other", generation = 2L); runCurrent()
        f.vm.dispatch(RemoteAction.OpenSheet(RemoteSheet.More))
        response.complete(QueueCommandResult(false, "subtitle_unavailable")); operation.await(); runCurrent()
        assertEquals(RemoteSheet.More, f.vm.state.value.sheet); assertTrue(f.effects.isEmpty())
    }
    @Test fun acceptedSubtitleClosesImmediatelyAndCurrentFailureEmitsExactNotice() = runTest {
        val f = Fixture(this, setOf("subtitle_resource_add_v1")); runCurrent()
        val response = CompletableDeferred<QueueCommandResult?>(); f.transport.confirmation = { response.await() }
        f.vm.dispatch(RemoteAction.OpenSheet(RemoteSheet.AddSubtitle))
        f.vm.dispatch(RemoteAction.AddSubtitle(SubtitleResource(url = "https://example.test/sub.srt")))
        assertNull(f.vm.state.value.sheet)
        runCurrent(); response.complete(QueueCommandResult(false, "invalid_command")); runCurrent()
        assertEquals(listOf(RemoteEffect.Notice("The TV rejected this subtitle", true)), f.effects)
        assertTrue(f.vm.state.value.pending.subtitleRequests.isEmpty())
    }
    @Test fun subtitleFallbackFailureLeavesDialogAndEmitsLegacyGenericNotice() = runTest {
        val f = Fixture(this); runCurrent()
        f.vm.dispatch(RemoteAction.OpenSheet(RemoteSheet.AddSubtitle))
        f.vm.execute(RemoteAction.AddSubtitle(SubtitleResource(url = "https://example.test/sub.srt", headers = mapOf("Referer" to "https://example.test/"))))
        runCurrent()
        assertEquals(RemoteSheet.AddSubtitle, f.vm.state.value.sheet)
        assertEquals(listOf(RemoteEffect.Notice("Could not add subtitle. Check the TV connection or update the receiver.", true)), f.effects)
        assertTrue(f.transport.commands.isEmpty())
    }
    @Test fun effectsPreserveOriginNavigationUnlinkAndPhoneManagersEvenWhenQueryFails() = runTest {
        val f = Fixture(this, initial = testSnapshot(RemoteContext.Browser)); runCurrent()
        f.vm.dispatch(RemoteAction.BackToOrigin); f.vm.dispatch(RemoteAction.OpenConnections)
        f.vm.dispatch(RemoteAction.UnlinkWebsite)
        f.vm.dispatch(RemoteAction.ManageUserScripts); f.vm.dispatch(RemoteAction.ManageUserAgent); runCurrent()
        assertEquals(listOf(RemoteEffect.Navigate(RemoteEffect.Navigate.Destination.Origin), RemoteEffect.Navigate(RemoteEffect.Navigate.Destination.Connections), RemoteEffect.UnlinkWebsite, RemoteEffect.OpenManager(RemoteEffect.OpenManager.Manager.UserScripts), RemoteEffect.OpenManager(RemoteEffect.OpenManager.Manager.UserAgent)), f.effects)
        f.transport.accepted = false; f.vm.execute(RemoteAction.ManageUserScripts); runCurrent()
        assertEquals(RemoteEffect.OpenManager(RemoteEffect.OpenManager.Manager.UserScripts), f.effects.last())
    }
    @Test fun browserAutoSwitchesOnlyOnVideoBecomingActiveAndKeepsExplicitModeAfterward() = runTest {
        val raw = testSnapshot(RemoteContext.Browser)
        val f = Fixture(this, initial = raw.copy(state = raw.state.copy(playback = raw.state.playback?.copy(reportedState = RemotePlaybackState.Idle)))); runCurrent()
        assertEquals(RemoteInputMode.Pointer, f.vm.state.value.inputMode)
        f.now++; f.tick(); runCurrent()
        assertEquals(RemoteInputMode.Context, f.vm.state.value.inputMode)
        f.vm.dispatch(RemoteAction.SelectInputMode(RemoteInputMode.Pointer))
        f.now++; f.tick(11_000L); runCurrent()
        assertEquals(RemoteInputMode.Pointer, f.vm.state.value.inputMode)
    }
    @Test fun submittedStopUsesLocalIdleUntilNextContextReportWithoutMutatingTransportSnapshot() = runTest {
        val f = Fixture(this); runCurrent()
        f.vm.execute(RemoteAction.StopPlayback)
        assertEquals(RemoteContext.Idle, f.vm.state.value.context)
        assertEquals(RemoteContext.Player, f.transport.snapshot.value.state.context)
        f.now++; f.tick(); runCurrent()
        assertEquals(RemoteContext.Idle, f.vm.state.value.context)
        val raw = f.transport.snapshot.value
        f.transport.snapshot.value = raw.copy(observation = raw.observation.copy(contextSequence = 1L)); runCurrent()
        assertEquals(RemoteContext.Player, f.vm.state.value.context)
    }
    @Test fun leavingRemoteReleasesPointerAndClearsPendingIntentWithoutDisconnecting() = runTest {
        val f = Fixture(this, initial = testSnapshot(RemoteContext.Browser)); runCurrent()
        f.vm.execute(RemoteAction.PointerMove(1f, 2f)); f.vm.execute(RemoteAction.PointerDown)
        f.vm.dispatch(RemoteAction.SeekTo(50_000L)); runCurrent()
        f.vm.deactivate()
        assertFalse(f.vm.state.value.pending.seek)
        assertEquals(listOf("move", "down", "up", "final-flush"), f.transport.ordering.filter { !it.startsWith("{") })
        assertTrue(f.vm.execute(RemoteAction.SetPlaying(false)) is RemoteCommandResult.Failed)
        f.vm.activate(); assertEquals(RemoteCommandResult.Submitted, f.vm.execute(RemoteAction.SetPlaying(false)))
        assertTrue(f.vm.state.value.connection.ready)
    }
    @Test fun browserMoreSheetRetainsStageOneDismissalOnModeOrAutomaticContextSwitch() = runTest {
        val raw = testSnapshot(RemoteContext.Browser)
        val f = Fixture(this, initial = raw.copy(state = raw.state.copy(playback = raw.state.playback?.copy(reportedState = RemotePlaybackState.Idle)))); runCurrent()
        f.vm.dispatch(RemoteAction.OpenSheet(RemoteSheet.BrowserMore))
        f.now++; f.tick(); runCurrent()
        assertEquals(RemoteInputMode.Context, f.vm.state.value.inputMode)
        assertNull(f.vm.state.value.sheet)
        f.vm.dispatch(RemoteAction.SelectInputMode(RemoteInputMode.Pointer))
        f.vm.dispatch(RemoteAction.OpenSheet(RemoteSheet.BrowserMore))
        f.vm.dispatch(RemoteAction.SelectInputMode(RemoteInputMode.Context))
        assertNull(f.vm.state.value.sheet)
    }
    @Test fun unsupportedLegacyAffordancesKeepPresentationWithoutClaimingSubmissionOrClearingSeek() = runTest {
        val f = Fixture(this, profile = RemoteProfile.Cli); runCurrent()
        f.vm.execute(RemoteAction.SeekTo(60_000L))
        assertTrue(f.vm.execute(RemoteAction.SetSpeed(1.5f)) is RemoteCommandResult.Unsupported)
        assertTrue(f.vm.state.value.pending.seek)
        f.vm.dispatch(RemoteAction.OpenSheet(RemoteSheet.AddSubtitle))
        assertTrue(f.vm.execute(RemoteAction.AddSubtitle(SubtitleResource(url = "https://example.test/sub.srt"))) is RemoteCommandResult.Unsupported)
        runCurrent()
        assertNull(f.vm.state.value.sheet)
        assertTrue(f.vm.state.value.pending.seek)
        assertTrue(f.effects.isEmpty())
        assertEquals(1, f.transport.commands.size)
    }
    @Test fun effectsQueuedBeforeLeavingRemoteDoNotReplayWhenRouteIsReactivated() = runTest {
        val transport = FakeNativeRemoteTransport()
        val targets = MutableStateFlow<RemoteNativeTarget?>(RemoteNativeTarget(testConfiguration()))
        val vm = RemoteViewModel(RemoteControllerRegistry(targets, transport, backgroundScope), RemoteInputRouter(), { 10L }, backgroundScope)
        vm.dispatch(RemoteAction.BackToOrigin)
        vm.deactivate(); vm.activate()
        val effects = mutableListOf<RemoteEffect>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.effects.collect { effects += it } }
        assertTrue(effects.isEmpty())
        vm.dispatch(RemoteAction.OpenConnections)
        assertEquals(listOf(RemoteEffect.Navigate(RemoteEffect.Navigate.Destination.Connections)), effects)
    }

    @Test fun reentryResetsNativePresentationLikeStageOneWhileKeepingTheConnection() = runTest {
        val f = Fixture(this); runCurrent()
        f.vm.dispatch(RemoteAction.SelectInputMode(RemoteInputMode.Pointer))
        f.vm.dispatch(RemoteAction.OpenSheet(RemoteSheet.More))
        f.vm.deactivate(); f.vm.activate()
        assertEquals(RemoteInputMode.Context, f.vm.state.value.inputMode)
        assertNull(f.vm.state.value.sheet)
        assertTrue(f.vm.state.value.connection.ready)
        assertEquals(RemoteCommandResult.Submitted, f.vm.execute(RemoteAction.SetPlaying(false)))
    }

    @Test fun lateSubtitleCompletionCannotAffectAReopenedRemoteForTheSameSessionAndMedia() = runTest {
        val f = Fixture(this, setOf("subtitle_resource_add_v1")); runCurrent()
        val response = CompletableDeferred<QueueCommandResult?>(); f.transport.confirmation = { response.await() }
        val operation = async { f.vm.execute(RemoteAction.AddSubtitle(SubtitleResource(url = "https://example.test/sub.srt"))) }
        runCurrent()
        f.vm.deactivate(); f.vm.activate()
        f.vm.dispatch(RemoteAction.OpenSheet(RemoteSheet.More))
        response.complete(QueueCommandResult(false, "invalid_command")); operation.await(); runCurrent()
        assertEquals(RemoteSheet.More, f.vm.state.value.sheet)
        assertTrue(f.effects.isEmpty())
        assertTrue(f.vm.state.value.pending.subtitleRequests.isEmpty())
    }

    @Test fun successfulSubtitleConfirmationDoesNotDismissASheetOpenedAfterAcceptedSubmission() = runTest {
        val f = Fixture(this, setOf("subtitle_resource_add_v1")); runCurrent()
        val response = CompletableDeferred<QueueCommandResult?>(); f.transport.confirmation = { response.await() }
        f.vm.dispatch(RemoteAction.OpenSheet(RemoteSheet.AddSubtitle))
        val operation = async { f.vm.execute(RemoteAction.AddSubtitle(SubtitleResource(url = "https://example.test/sub.srt"))) }
        runCurrent(); assertNull(f.vm.state.value.sheet)
        f.vm.dispatch(RemoteAction.OpenSheet(RemoteSheet.More))
        response.complete(QueueCommandResult(true, null))
        assertEquals(RemoteCommandResult.Confirmed, operation.await()); runCurrent()
        assertEquals(RemoteSheet.More, f.vm.state.value.sheet)
        assertTrue(f.vm.state.value.pending.subtitleRequests.isEmpty())
    }

    @Test fun buttonPadIsLocalPersistedAndRestoredWithoutReceiverCommands() = runTest {
        val store = Preferences()
        val f = Fixture(this, preferenceStore = store); runCurrent()
        assertFalse(f.vm.state.value.preferences.buttonPad)
        val before = resolveInputPresentation(f.vm.state.value)
        assertEquals(RemoteCommandResult.Submitted, f.vm.execute(RemoteAction.SetButtonPad(true)))
        assertTrue(store.value.buttonPad)
        assertEquals(before, resolveInputPresentation(f.vm.state.value))
        assertEquals(RemoteInputPresentation.Absent, resolveInputPresentation(f.vm.state.value))
        val recreated = Fixture(this, preferenceStore = store); runCurrent()
        assertTrue(recreated.vm.state.value.preferences.buttonPad)
        recreated.vm.dispatch(RemoteAction.SetButtonPad(false)); runCurrent()
        assertFalse(store.value.buttonPad)
        assertTrue(f.transport.commands.isEmpty())
        assertTrue(recreated.transport.commands.isEmpty())
    }
    @Test fun libraryProgressFlowsIntoStateAndSurvivesTicksButClearsOnQueueReplacement() = runTest {
        val updates = MutableStateFlow<Map<String, RemoteQueueProgress>>(emptyMap())
        val requests = mutableListOf<Set<RemoteProgressKey>>()
        val source = RemoteQueueProgressSource { keys ->
            requests += keys
            if (keys.isEmpty()) kotlinx.coroutines.flow.flowOf(emptyMap()) else updates
        }
        val raw = testSnapshot()
        val identified = raw.state.queue!!.items.mapIndexed { index, item -> item.copy(tmdbId = "100", season = 1, episode = index + 1) }
        val initial = raw.copy(state = raw.state.copy(queue = raw.state.queue.copy(items = identified)))
        val f = Fixture(this, initial = initial, progressSource = source); runCurrent()
        val resume = com.playbridge.sender.data.library.PlaybackResumeEntity("tmdb:100:1:2", 100, "tv", 1, 2, positionMs = 50_000, durationMs = 100_000)
        updates.value = mapOf(resume.contentKey to RemoteQueueProgress(resume = resume)); runCurrent()
        assertEquals(resume, f.vm.state.value.queueProgress[resume.contentKey]?.resume)
        val requestCount = requests.size
        f.tick(position = 11_000); runCurrent()
        assertEquals(requestCount, requests.size)
        assertEquals(resume, f.vm.state.value.queueProgress[resume.contentKey]?.resume)
        val changed = f.transport.snapshot.value
        f.transport.snapshot.value = changed.copy(state = changed.state.copy(queue = changed.state.queue!!.copy(items = listOf(identified.first().copy(tmdbId = "200")))))
        runCurrent()
        assertTrue(f.vm.state.value.queueProgress.isEmpty())
        updates.value = mapOf(resume.contentKey to RemoteQueueProgress(resume = resume)); runCurrent()
        assertTrue(f.vm.state.value.queueProgress.isEmpty())
        f.target.value = null; runCurrent()
        assertTrue(f.vm.state.value.queueProgress.isEmpty())
        assertEquals(emptySet<RemoteProgressKey>(), requests.last())
        assertTrue(f.transport.commands.isEmpty())
    }

    private class Preferences : RemotePresentationPreferenceStore {
        var value = RemotePreferences()
        override fun load() = value
        override fun save(preferences: RemotePreferences) { value = preferences }
    }
    @Test fun successfulWaveGesturesPersistWhileFailuresAndOrdinaryVolumeDoNotTeach() = runTest {
        val store = Preferences()
        val f = Fixture(this, preferenceStore = store); runCurrent()
        f.transport.accepted = false
        assertTrue(f.vm.execute(RemoteAction.SeekTo(30_000, RemoteLearnedGesture.SEEK)) is RemoteCommandResult.Failed)
        assertTrue(f.vm.execute(RemoteAction.TogglePlayPause) is RemoteCommandResult.Failed)
        assertTrue(store.value.learnedGestures.isEmpty())
        f.transport.accepted = true
        f.vm.execute(RemoteAction.AdjustVolume(1))
        assertTrue(store.value.learnedGestures.isEmpty())
        f.vm.execute(RemoteAction.SeekTo(30_000, RemoteLearnedGesture.SEEK))
        f.vm.execute(RemoteAction.TogglePlayPause)
        f.vm.execute(RemoteAction.AdjustVolume(1, RemoteLearnedGesture.VOLUME))
        assertEquals(setOf("seek", "tap", "volume"), store.value.learnedGestures)
        f.vm.dispatch(RemoteAction.SetTimeDisplay(RemoteTimeDisplay.Remaining))
        f.vm.deactivate(); f.vm.activate()
        assertEquals(RemoteTimeDisplay.Remaining, f.vm.state.value.preferences.timeDisplay)
        assertEquals(store.value.learnedGestures, f.vm.state.value.preferences.learnedGestures)
        val recreated = Fixture(this, preferenceStore = store); runCurrent()
        assertEquals(store.value.learnedGestures, recreated.vm.state.value.preferences.learnedGestures)
        assertEquals(RemoteTimeDisplay.Remaining, recreated.vm.state.value.preferences.timeDisplay)
    }
    @Test fun canceledGestureCommandCannotPersistLearning() = runTest {
        val store = Preferences()
        val f = Fixture(this, preferenceStore = store); runCurrent()
        f.vm.dispatch(RemoteAction.SeekTo(50_000, RemoteLearnedGesture.SEEK))
        f.vm.deactivate(); runCurrent()
        assertTrue(store.value.learnedGestures.isEmpty())
        assertTrue(f.transport.commands.isEmpty())
    }

}
