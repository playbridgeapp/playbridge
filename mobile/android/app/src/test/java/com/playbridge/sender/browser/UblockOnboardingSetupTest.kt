package com.playbridge.sender.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UblockOnboardingSetupTest {
    private class Backend {
        val checks = mutableListOf<(Boolean?) -> Unit>()
        val installs = mutableListOf<(Boolean) -> Unit>()
        val cancellations = mutableListOf<(Boolean) -> Unit>()
        val setup = UblockOnboardingSetup(
            checkInstalled = { checks += it },
            install = { complete ->
                installs += complete
                val cancel: CancelUblockInstall = { cancellations += it }
                cancel
            },
        )
        fun notInstalled() {
            setup.refresh()
            checks.last()(false)
        }
        fun start() {
            notInstalled()
            setup.requestInstall()
            checks.last()(false)
        }
    }

    @Test fun checkingAndSkippingNeverInstallsOrGrantsAnything() {
        val backend = Backend()
        assertTrue(backend.setup.canFinish())
        backend.setup.requestInstall() // Presence is still unknown.
        assertTrue(backend.installs.isEmpty())
        backend.notInstalled()
        assertTrue(backend.setup.canFinish())
        backend.setup.refresh()
        backend.checks.last()(false)
        assertTrue(backend.installs.isEmpty())
    }

    @Test fun existingAndLegacyCopiesArePreservedWithoutReinstalling() {
        val backend = Backend()
        backend.setup.refresh()
        backend.checks.last()(true)
        assertEquals(UblockSetupStatus.INSTALLED, backend.setup.status.value)
        backend.setup.requestInstall()
        assertTrue(backend.installs.isEmpty())
    }

    @Test fun explicitInstallRechecksPresenceBeforeStarting() {
        val backend = Backend()
        backend.notInstalled()
        backend.setup.requestInstall()
        assertFalse(backend.setup.canFinish())
        assertTrue(backend.installs.isEmpty())
        backend.checks.last()(true) // Another native action installed it meanwhile.
        assertEquals(UblockSetupStatus.INSTALLED, backend.setup.status.value)
        assertTrue(backend.installs.isEmpty())
    }

    @Test fun downloadIsNotInstalledUntilTheApprovedSdkOperationCompletes() {
        val backend = Backend()
        backend.start()
        assertEquals(UblockSetupStatus.INSTALLING, backend.setup.status.value)
        assertFalse(backend.setup.canFinish())
        backend.installs.last()(true)
        assertEquals(UblockSetupStatus.INSTALLED, backend.setup.status.value)
        assertTrue(backend.setup.canFinish())
    }

    @Test fun declinedPermissionsOrOfflineFailureAllowRetryAndSkip() {
        val backend = Backend()
        backend.start()
        backend.installs.last()(false)
        assertEquals(UblockSetupStatus.INSTALL_FAILED, backend.setup.status.value)
        assertTrue(backend.setup.canFinish())
        backend.setup.requestInstall()
        backend.checks.last()(false)
        assertEquals(2, backend.installs.size)
        backend.installs.last()(true)
        assertEquals(UblockSetupStatus.INSTALLED, backend.setup.status.value)
    }

    @Test fun unknownPresenceDoesNotReplaceAnExistingExtension() {
        val backend = Backend()
        backend.setup.refresh()
        backend.checks.last()(null)
        assertEquals(UblockSetupStatus.CHECK_FAILED, backend.setup.status.value)
        backend.setup.requestInstall()
        assertTrue(backend.installs.isEmpty())
        backend.notInstalled()
        backend.setup.requestInstall()
        backend.checks.last()(null)
        assertEquals(UblockSetupStatus.CHECK_FAILED, backend.setup.status.value)
        assertTrue(backend.installs.isEmpty())
    }

    @Test fun cancellationBeforePreflightFinishesNeverStartsADownload() {
        val backend = Backend()
        backend.notInstalled()
        backend.setup.requestInstall()
        val old = backend.checks.last()
        backend.setup.cancel()
        old(false)
        assertTrue(backend.installs.isEmpty())
        assertEquals(UblockSetupStatus.NOT_INSTALLED, backend.setup.status.value)
        assertTrue(backend.setup.canFinish())
    }

    @Test fun cancellationWaitsForConfirmationAndRejectsLateCallbacks() {
        val backend = Backend()
        backend.start()
        val oldResult = backend.installs.last()
        backend.setup.cancel()
        assertEquals(UblockSetupStatus.CANCELLING, backend.setup.status.value)
        assertFalse(backend.setup.canFinish())
        backend.cancellations.last()(true)
        oldResult(false)
        assertEquals(UblockSetupStatus.NOT_INSTALLED, backend.setup.status.value)
        backend.setup.requestInstall()
        backend.checks.last()(false)
        oldResult(true)
        assertEquals(UblockSetupStatus.INSTALLING, backend.setup.status.value)
        backend.installs.last()(true)
        assertEquals(UblockSetupStatus.INSTALLED, backend.setup.status.value)
    }

    @Test fun anAlreadyCommittingApprovedInstallUsesItsRealResult() {
        val backend = Backend()
        backend.start()
        backend.setup.cancel()
        backend.cancellations.last()(false)
        assertEquals(UblockSetupStatus.INSTALLING, backend.setup.status.value)
        assertFalse(backend.setup.canFinish())
        backend.installs.last()(true)
        assertEquals(UblockSetupStatus.INSTALLED, backend.setup.status.value)
    }

    @Test fun installFailureDuringCancellationIsReportedAsNotInstalled() {
        val backend = Backend()
        backend.start()
        backend.setup.cancel()
        backend.installs.last()(false)
        backend.cancellations.last()(false)
        assertEquals(UblockSetupStatus.NOT_INSTALLED, backend.setup.status.value)
    }

    @Test fun installedResultRacingCancellationIsNeverHidden() {
        val backend = Backend()
        backend.start()
        backend.setup.cancel()
        backend.installs.last()(true)
        backend.cancellations.last()(false)
        assertEquals(UblockSetupStatus.INSTALLED, backend.setup.status.value)
    }

    @Test fun repeatedClicksRefreshAndDuplicateLookupCannotStartTwoInstalls() {
        val backend = Backend()
        backend.start()
        val check = backend.checks.last()
        backend.setup.requestInstall()
        backend.setup.refresh()
        check(false)
        assertEquals(1, backend.installs.size)
        backend.setup.cancel()
        backend.setup.cancel()
        assertEquals(1, backend.cancellations.size)
    }

    @Test fun staleRefreshCannotOverrideNewerState() {
        val backend = Backend()
        backend.setup.refresh()
        val old = backend.checks.last()
        backend.setup.refresh()
        backend.checks.last()(true)
        old(false)
        assertEquals(UblockSetupStatus.INSTALLED, backend.setup.status.value)
    }

    @Test fun synchronousBackendCompletionDoesNotLeaveACancellationHandle() {
        var installed = false
        var cancelled = false
        val setup = UblockOnboardingSetup(
            checkInstalled = { it(installed) },
            install = { complete ->
                installed = true
                complete(true)
                val cancel: CancelUblockInstall = { cancelled = true; it(true) }
                cancel
            },
        )
        setup.refresh()
        setup.requestInstall()
        setup.cancel()
        assertFalse(cancelled)
        assertEquals(UblockSetupStatus.INSTALLED, setup.status.value)
    }

    @Test fun throwingBackendsRemainSkippableAndDoNotAutoRetry() {
        val checkFailure = UblockOnboardingSetup({ error("unavailable") }, { error("must not install") })
        checkFailure.refresh()
        assertEquals(UblockSetupStatus.CHECK_FAILED, checkFailure.status.value)
        assertTrue(checkFailure.canFinish())
        val installFailure = UblockOnboardingSetup({ it(false) }, { error("offline") })
        installFailure.refresh()
        installFailure.requestInstall()
        assertEquals(UblockSetupStatus.INSTALL_FAILED, installFailure.status.value)
        assertTrue(installFailure.canFinish())
    }
}
