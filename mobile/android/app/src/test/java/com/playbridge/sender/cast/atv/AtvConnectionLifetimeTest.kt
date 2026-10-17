package com.playbridge.sender.cast.atv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AtvConnectionLifetimeTest {
    @Test fun foregroundOnAnyScreenAllowsConnectionAndBackgroundGetsOneGraceWindow() {
        val initial = AtvConnectionLifetime()
        assertFalse(initial.keepConnection)
        val foreground = initial.foregroundChanged(true)
        assertTrue(foreground.keepConnection)
        val background = foreground.foregroundChanged(false)
        assertTrue(background.keepConnection)
        assertFalse(background.expiredAt(59_999))
        assertTrue(background.expiredAt(60_000))
        assertEquals(background, background.foregroundChanged(false))
        assertFalse(background.graceExpired().keepConnection)
        assertEquals(60_000L, AtvConnectionLifetime.BACKGROUND_GRACE_MS)
    }

    @Test fun returningWithinOrAfterGraceImmediatelyAllowsConnectionAndFencesStaleExpiry() {
        val background = AtvConnectionLifetime().foregroundChanged(true).foregroundChanged(false)
        val withinGrace = background.foregroundChanged(true)
        assertTrue(withinGrace.keepConnection)
        assertEquals(withinGrace, withinGrace.graceExpired())
        assertTrue(background.graceExpired().foregroundChanged(true).keepConnection)
    }

    @Test fun onlyNoSelectionWaitsForAnUnsettledDiscoverySnapshot() {
        assertFalse(AtvConnectionLifetime.waitForDiscovery(hasSelection = true, settled = false))
        assertFalse(AtvConnectionLifetime.waitForDiscovery(hasSelection = true, settled = true))
        assertTrue(AtvConnectionLifetime.waitForDiscovery(hasSelection = false, settled = false))
        assertFalse(AtvConnectionLifetime.waitForDiscovery(hasSelection = false, settled = true))
    }
}
