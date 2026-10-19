package com.playbridge.sender.cast

import com.playbridge.sender.model.CastProtocol
import com.playbridge.sender.model.EndpointKey
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CastSessionActivityTest {
    private val externalRoute = CastSessionManager.Route.External(EndpointKey(CastProtocol.DLNA, "receiver"), "Receiver")
    private val idle = ExternalCastServiceActivity(false, false)
    private fun needsService(
        external: ExternalCastServiceActivity = idle,
        route: CastSessionManager.Route = externalRoute,
        linked: Boolean = false,
        player: Boolean = false,
        reconnecting: Boolean = false,
        mirroring: Boolean = false,
    ) = castSessionNeedsService(external, route, linked, player, reconnecting, mirroring)

    @Test fun directOrRemoteProxyLoadedMediaNeedsServiceWithoutAPhoneDataPath() {
        assertTrue(needsService(ExternalCastServiceActivity(false, true)))
    }

    @Test fun directPauseKeepsServiceAndTerminalCleanupReleasesIt() = runTest {
        val loaded = MutableStateFlow(false)
        val phonePath = MutableStateFlow(false)
        val observed = mutableListOf<Boolean>()
        val job = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            combine(phonePath, loaded) { phone, media -> needsService(ExternalCastServiceActivity(phone, media)) }
                .collect { observed += it }
        }
        loaded.value = true // Direct/remote-proxy load; pause retains this same ownership.
        runCurrent()
        assertEquals(listOf(false, true), observed)
        assertTrue(needsService(ExternalCastServiceActivity(phonePath.value, loaded.value)))
        loaded.value = false // Existing terminal/inactivity/Stop/Disconnect cleanup.
        runCurrent()
        assertEquals(listOf(false, true, false), observed)
        job.cancel()
    }

    @Test fun selectedButUnloadedExternalTargetDoesNotKeepService() {
        assertFalse(needsService())
    }

    @Test fun phoneDataPathRetainsItsExistingOwnershipUntilItAlsoEnds() {
        assertTrue(needsService(ExternalCastServiceActivity(true, false)))
        assertTrue(needsService(ExternalCastServiceActivity(true, true)))
        assertFalse(needsService(ExternalCastServiceActivity(false, false)))
    }

    @Test fun nativeIdleLinkStillKeepsServiceEvenWithLocalPlaybackPreference() {
        for (route in listOf(CastSessionManager.Route.NativeTv, CastSessionManager.Route.ThisDevice)) {
            assertTrue(needsService(route = route, linked = true))
        }
    }

    @Test fun anExternalRouteIgnoresAnUnrelatedNativeLinkOrPlayerSnapshot() {
        assertFalse(needsService(linked = true, player = true, reconnecting = true))
    }

    @Test fun nativeStickyPlaybackAndReconnectKeepTheirExistingOwnership() {
        assertTrue(needsService(route = CastSessionManager.Route.NativeTv, player = true))
        assertTrue(needsService(route = CastSessionManager.Route.NativeTv, reconnecting = true))
        assertTrue(needsService(route = CastSessionManager.Route.ThisDevice, player = true))
        assertFalse(needsService(route = CastSessionManager.Route.ThisDevice, reconnecting = true))
    }

    @Test fun mirroringKeepsServiceIndependentlyOfMediaMode() {
        assertTrue(needsService(mirroring = true))
        assertTrue(needsService(route = CastSessionManager.Route.NativeTv, mirroring = true))
    }

    @Test fun noSessionAndNoMirrorCanFinishAnUnusedServiceStart() {
        assertFalse(needsService(route = CastSessionManager.Route.ThisDevice))
        assertFalse(needsService(route = CastSessionManager.Route.NativeTv))
    }
}
