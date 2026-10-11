package com.playbridge.sender.cast.remote

import com.playbridge.sender.cast.Capability
import com.playbridge.sender.cast.PlaybackState
import com.playbridge.sender.cast.SessionPhase
import com.playbridge.sender.cast.TargetKind
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RemoteExternalViewModelTest {
    private class Fixture(scope: TestScope, kind: TargetKind = TargetKind.GOOGLE_CAST) {
        val external = FakeExternalRemoteTransport(kind)
        val targets = MutableStateFlow<RemoteTarget?>(external.target)
        val registry = RemoteControllerRegistry(targets, FakeNativeRemoteTransport(), scope.backgroundScope, external)
        var now = 10L
        val vm = RemoteViewModel(registry, RemoteInputRouter(), { now }, scope.backgroundScope)
        val playback get() = vm.state.value.playback!!
        val effects = mutableListOf<RemoteEffect>()
        init { scope.backgroundScope.launch(UnconfinedTestDispatcher(scope.testScheduler)) { vm.effects.collect { effects += it } } }
        fun tick(position: Long = 10_000L, state: PlaybackState = PlaybackState.PLAYING, fresh: Boolean = true) {
            external.now = now; external.tick(position, state, fresh)
        }
    }
    @Test fun eachExternalSeekableControllerUsesSharedOverlayAndFreshObservationReconcile() = runTest {
        for (kind in listOf(TargetKind.GOOGLE_CAST, TargetKind.DLNA, TargetKind.WEB_BROWSER)) {
            val f = Fixture(this, kind); runCurrent()
            f.vm.dispatch(RemoteAction.SeekBy(10_000L)); f.vm.dispatch(RemoteAction.SeekBy(10_000L)); runCurrent()
            assertEquals(listOf("seek:20000", "seek:30000"), f.external.owner.calls)
            assertEquals(10_000L, f.playback.reportedPositionMs); assertEquals(30_000L, f.playback.displayedPositionMs)
            f.now++; f.tick(20_000L); runCurrent(); assertTrue(f.vm.state.value.pending.seek)
            f.tick(30_000L, fresh = false); runCurrent(); assertTrue(f.vm.state.value.pending.seek)
            f.now++; f.tick(29_000L); runCurrent()
            assertFalse(f.vm.state.value.pending.seek); assertEquals(29_000L, f.playback.displayedPositionMs)
        }
    }
    @Test fun allExternalRapidTogglesSetOppositeIntentAndIdenticalFreshTickSettlesIt() = runTest {
        for (kind in listOf(TargetKind.GOOGLE_CAST, TargetKind.ROKU, TargetKind.DLNA, TargetKind.WEB_BROWSER)) {
            val f = Fixture(this, kind); runCurrent()
            f.vm.dispatch(RemoteAction.TogglePlayPause); f.vm.dispatch(RemoteAction.TogglePlayPause); runCurrent()
            assertEquals(listOf("pause", "play"), f.external.owner.calls)
            assertTrue(f.vm.state.value.pending.playing)
            f.now++; f.tick(); runCurrent()
            assertFalse(f.vm.state.value.pending.playing); assertTrue(f.playback.displayedPlaying)
        }
    }
    @Test fun sameTitleNewLoadResetsOverlayAndQueuedOldLoadActionCannotSend() = runTest {
        val f = Fixture(this); runCurrent()
        f.vm.execute(RemoteAction.SeekTo(80_000L))
        val media = f.playback.mediaId
        f.vm.dispatch(RemoteAction.SeekBy(10_000L))
        f.external.owner.generation++ // changes before combined transport/VM snapshots arrive
        f.now++; f.tick(); runCurrent()
        assertNotEquals(media, f.playback.mediaId)
        assertFalse(f.vm.state.value.pending.seek)
        assertEquals(listOf("seek:80000"), f.external.owner.calls)
        assertEquals("Episode", f.playback.title)
    }
    @Test fun failureVersionClearsOverlayOnceWithoutClosingTheExternalMoreSheet() = runTest {
        val f = Fixture(this); runCurrent()
        f.vm.execute(RemoteAction.SeekTo(80_000L))
        f.vm.dispatch(RemoteAction.OpenSheet(RemoteSheet.More))
        f.external.fail(); runCurrent()
        assertFalse(f.vm.state.value.pending.seek); assertEquals(10_000L, f.playback.displayedPositionMs)
        assertEquals(RemoteSheet.More, f.vm.state.value.sheet)
        f.vm.execute(RemoteAction.SeekTo(60_000L))
        f.external.capabilities = f.external.capabilities - Capability.VOLUME; f.external.refresh(); runCurrent()
        assertTrue(f.vm.state.value.pending.seek) // recombination is not another command failure
        assertTrue(f.effects.isEmpty())
    }
    @Test fun deadlineDisconnectErrorAndLeavingRemoteRestoreReportedExternalState() = runTest {
        val f = Fixture(this); runCurrent()
        f.vm.execute(RemoteAction.SeekTo(60_000L))
        f.now += 3_000L; advanceTimeBy(100L); runCurrent()
        assertFalse(f.vm.state.value.pending.seek); assertEquals(10_000L, f.playback.displayedPositionMs)
        f.vm.execute(RemoteAction.SetPlaying(false)); f.external.phase = SessionPhase.CONNECTING; f.external.refresh(); runCurrent()
        assertFalse(f.vm.state.value.pending.playing)
        f.external.phase = SessionPhase.CONNECTED; f.now++; f.tick(); runCurrent()
        f.vm.execute(RemoteAction.SetPlaying(false)); f.now++; f.tick(state = PlaybackState.ERROR); runCurrent()
        assertFalse(f.vm.state.value.pending.playing); assertEquals(RemoteContext.Idle, f.vm.state.value.context)
        f.now++; f.tick(); runCurrent(); f.vm.execute(RemoteAction.SetPlaying(false))
        f.vm.deactivate(); assertFalse(f.vm.state.value.pending.playing)
        assertTrue(f.external.owner.active)
    }
    @Test fun externalIdleEffectsKeepOriginReturnWebsiteUnlinkAndPhoneNavigation() = runTest {
        val f = Fixture(this, TargetKind.ROKU)
        f.external.linked = LinkedWebsiteState(true, "Linked app"); f.tick(state = PlaybackState.IDLE); runCurrent()
        assertEquals("Linked app", f.vm.state.value.linkedWebsite?.controllerName)
        f.vm.dispatch(RemoteAction.BackToOrigin); f.vm.dispatch(RemoteAction.BrowseLibrary)
        f.vm.dispatch(RemoteAction.OpenPhoneBrowser); f.vm.dispatch(RemoteAction.UnlinkWebsite); runCurrent()
        assertEquals(listOf(RemoteEffect.Navigate(RemoteEffect.Navigate.Destination.Origin), RemoteEffect.Navigate(RemoteEffect.Navigate.Destination.Library), RemoteEffect.Navigate(RemoteEffect.Navigate.Destination.PhoneBrowser), RemoteEffect.UnlinkWebsite), f.effects)
        assertTrue(f.external.owner.calls.isEmpty())
    }
    @Test fun externalReadOnlyQueueDoesNotCreateNativeNeighborsOrQueueActionRoutes() = runTest {
        val f = Fixture(this)
        f.external.queue = testSnapshot().state.queue!!.copy(phoneOwned = true)
        f.external.refresh(); runCurrent()
        assertEquals(3, f.vm.state.value.queue?.items?.size)
        val policy = resolveRemoteLayout(f.vm.state.value, RemoteViewport())
        assertFalse(policy.showQueue); assertNull(policy.transport.previous); assertNull(policy.transport.next)
        assertTrue(f.vm.execute(RemoteAction.JumpToEpisode(8)) is RemoteCommandResult.Unsupported)
        assertTrue(f.external.owner.calls.isEmpty())
    }
}
