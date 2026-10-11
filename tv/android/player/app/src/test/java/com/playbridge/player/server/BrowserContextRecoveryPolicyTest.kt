package com.playbridge.player.server

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowserContextRecoveryPolicyTest {
    @Test fun recoversAnIdleServiceWithNoLaunchPending() {
        assertTrue(canRecoverExternalBrowserContext("idle", launchPending = false))
    }

    @Test fun neverOverridesAnotherContextOwner() {
        for (owner in listOf("player", "browser", "browser_external", "screen_mirror", "unknown")) {
            assertFalse(owner, canRecoverExternalBrowserContext(owner, launchPending = false))
        }
    }

    @Test fun neverOverridesAnIdleServiceDuringAnInFlightLaunch() {
        assertFalse(canRecoverExternalBrowserContext("idle", launchPending = true))
    }
}
