package com.playbridge.sender.cast.remote

import com.playbridge.sender.cast.TargetKind
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RemoteControllerRegistryTest {
    @Test fun nativeExternalNativeSwitchNeverRoutesIntoRetainedSocketOrRetiredTarget() = runTest {
        val native = FakeNativeRemoteTransport()
        val external = FakeExternalRemoteTransport()
        val targets = MutableStateFlow<RemoteTarget?>(RemoteNativeTarget(testConfiguration(), "native-1"))
        val registry = RemoteControllerRegistry(targets, native, backgroundScope, external)
        val first = registry.controller.value!!
        first.execute(RemoteAction.SetPlaying(false))
        targets.value = external.target
        assertTrue(first.execute(RemoteAction.SetPlaying(true)) is RemoteCommandResult.Failed)
        runCurrent()
        val cast = registry.controller.value!!
        assertTrue(cast is GoogleCastRemoteController)
        assertEquals(RemoteCommandResult.Submitted, cast.execute(RemoteAction.SetPlaying(false)))
        // Native remains connected/reporting, but only the external controller remains selected.
        native.snapshot.value = testSnapshot(RemoteContext.Browser); runCurrent()
        assertTrue(registry.controller.value === cast)
        assertEquals(external.receiver.endpoint, cast.sessionId.endpoint)
        targets.value = RemoteNativeTarget(testConfiguration(), "native-2")
        assertTrue(cast.execute(RemoteAction.SetPlaying(true)) is RemoteCommandResult.Failed)
        runCurrent()
        val second = registry.controller.value!!
        assertNotEquals(first.sessionId, second.sessionId)
        assertEquals(RemoteCommandResult.Submitted, second.execute(RemoteAction.Key(RemoteKey.Home)))
        assertEquals(listOf("pause"), external.owner.calls)
        assertEquals(2, native.commands.size)
    }
    @Test fun externalReconnectAtSameEndpointChangesSessionAndFencesOldController() = runTest {
        val external = FakeExternalRemoteTransport(TargetKind.ROKU)
        val targets = MutableStateFlow<RemoteTarget?>(external.target)
        val registry = RemoteControllerRegistry(targets, FakeNativeRemoteTransport(), backgroundScope, external)
        val old = registry.controller.value!!
        external.owner.identity = Any(); external.refresh()
        targets.value = external.target
        assertTrue(old.execute(RemoteAction.Key(RemoteKey.Home)) is RemoteCommandResult.Failed)
        runCurrent()
        val current = registry.controller.value!!
        assertNotEquals(old.sessionId, current.sessionId)
        assertEquals(RemoteCommandResult.Submitted, current.execute(RemoteAction.Key(RemoteKey.Home)))
        targets.value = null; runCurrent(); assertNull(registry.controller.value)
        assertTrue(current.execute(RemoteAction.Key(RemoteKey.Home)) is RemoteCommandResult.Failed)
        assertEquals(listOf("roku:home"), external.owner.calls)
    }
    @Test fun externalAuthoritativeRouteFenceWorksBeforeCombinedSelectionEmits() = runTest {
        val external = FakeExternalRemoteTransport()
        val targets = MutableStateFlow<RemoteTarget?>(external.target)
        var routeOwnsTarget = true
        val registry = RemoteControllerRegistry(targets, FakeNativeRemoteTransport(), backgroundScope, external, { routeOwnsTarget })
        val old = registry.controller.value!!
        routeOwnsTarget = false
        assertTrue(old.execute(RemoteAction.SetPlaying(false)) is RemoteCommandResult.Failed)
        assertTrue(external.owner.calls.isEmpty())
    }
}
