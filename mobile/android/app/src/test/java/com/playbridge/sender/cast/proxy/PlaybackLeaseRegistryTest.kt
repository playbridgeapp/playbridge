package com.playbridge.sender.cast.proxy

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PlaybackLeaseRegistryTest {
    @Test fun unknownIdsCannotObtainNativeOwnership() = runTest {
        val registry = PlaybackLeaseRegistry(this, { error("must not renew") }, { error("must not revoke") })
        assertNull(registry.retain("unregistered"))
    }
    @Test fun overlappingOwnersRenewOnceAndOnlyLastCloseRevokes() = runTest {
        var renewed = 0; var revoked = 0
        val registry = PlaybackLeaseRegistry(this, { renewed++; true }, { revoked++ })
        registry.register("media")
        val first = registry.retain("media")!!
        val second = registry.retain("media")!!
        runCurrent(); assertEquals(1, renewed)
        first.close(); first.close(); runCurrent(); assertEquals(0, revoked)
        advanceTimeBy(61_000); runCurrent(); assertEquals(2, renewed)
        second.close(); runCurrent(); assertEquals(1, revoked)
        assertNull(registry.retain("media"))
        advanceTimeBy(120_000); runCurrent(); assertEquals(2, renewed)
    }
    @Test fun pauseDoesNotNeedReceiverRequestsToMaintainLease() = runTest {
        var renewed = 0; var revoked = 0
        val registry = PlaybackLeaseRegistry(this, { renewed++; true }, { revoked++ })
        registry.register("paused")
        val lease = registry.retain("paused")!!
        runCurrent(); advanceTimeBy(3 * 60 * 60 * 1000L); runCurrent()
        assertTrue(renewed > 100)
        assertEquals(0, revoked)
        lease.close(); runCurrent(); assertEquals(1, revoked)
    }
    @Test fun failedRenewalDoesNotSpinOrReviveLease() = runTest {
        var renewed = 0; var revoked = 0
        val registry = PlaybackLeaseRegistry(this, { renewed++; false }, { revoked++ })
        registry.register("expired")
        val lease = registry.retain("expired")!!
        runCurrent(); advanceTimeBy(300_000); runCurrent()
        assertEquals(1, renewed)
        lease.close(); runCurrent(); assertEquals(1, revoked)
    }
    @Test fun shutdownAndStaleCloseCannotReleaseAnotherOwnersRegistration() = runTest {
        var revoked = 0
        val registry = PlaybackLeaseRegistry(this, { true }, { revoked++ })
        registry.register("same-id")
        val stale = registry.retain("same-id")!!
        registry.clear()
        registry.register("same-id")
        val current = registry.retain("same-id")!!
        stale.close(); runCurrent(); assertEquals(0, revoked)
        current.close(); runCurrent(); assertEquals(1, revoked)
    }
}
