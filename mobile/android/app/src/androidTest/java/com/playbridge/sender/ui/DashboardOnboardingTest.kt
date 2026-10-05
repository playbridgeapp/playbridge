package com.playbridge.sender.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.playbridge.sender.browser.UblockSetupStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** Compile-only unless explicitly authorized: these fixtures perform native UI clicks. */
class DashboardOnboardingTest {
    @get:Rule val compose = createComposeRule()

    private fun reachUblock() {
        repeat(2) { compose.onNodeWithText("Next").performClick() }
    }

    @Test fun onboardingCoversTheDashboardRatherThanDrawingABottomCard() {
        compose.setContent {
            MaterialTheme {
                Box(Modifier.fillMaxSize().testTag("dashboard-behind-onboarding")) {
                    DashboardOnboardingOverlay(onDone = {})
                }
            }
        }
        val dashboard = compose.onNodeWithTag("dashboard-behind-onboarding").fetchSemanticsNode().boundsInRoot
        val tour = compose.onNodeWithTag("onboarding-fullscreen").fetchSemanticsNode().boundsInRoot
        assertTrue(tour.width >= dashboard.width - 1f)
        assertTrue(tour.height >= dashboard.height - 1f)
    }

    @Test fun ublockRequiresExplicitInstallAndCanBeSkipped() {
        var installed = 0
        var finished = 0
        compose.setContent {
            MaterialTheme {
                DashboardOnboardingOverlay(onDone = { finished++ },
                    ublockStatus = UblockSetupStatus.NOT_INSTALLED, onInstallUblock = { installed++ })
            }
        }
        reachUblock()
        compose.onNodeWithText("Install uBlock Origin").assertIsDisplayed()
        compose.runOnIdle { assertEquals(0, installed) }
        compose.onNodeWithText("Not now").performClick()
        compose.onNodeWithText("Getting back here").assertIsDisplayed()
        compose.onNodeWithText("Finish").performClick()
        compose.runOnIdle { assertEquals(0, installed); assertEquals(1, finished) }
    }

    @Test fun installClickCallsTheNativeSetupOnlyOnce() {
        var installed = 0
        compose.setContent {
            MaterialTheme {
                DashboardOnboardingOverlay(onDone = {}, ublockStatus = UblockSetupStatus.NOT_INSTALLED,
                    onInstallUblock = { installed++ })
            }
        }
        reachUblock()
        compose.onNodeWithText("Install uBlock Origin").performClick()
        compose.runOnIdle { assertEquals(1, installed) }
    }

    @Test fun activeInstallBlocksLeavingUntilCancellationOrCompletion() {
        val status = mutableStateOf(UblockSetupStatus.NOT_INSTALLED)
        var cancelled = 0
        compose.setContent {
            MaterialTheme {
                DashboardOnboardingOverlay(onDone = {}, ublockStatus = status.value,
                    onCancelUblock = { cancelled++ })
            }
        }
        reachUblock()
        compose.runOnIdle { status.value = UblockSetupStatus.INSTALLING }
        compose.onNodeWithText("Skip tour").assertIsNotEnabled()
        compose.onNodeWithText("Not now").assertIsNotEnabled()
        compose.onNodeWithText("Cancel installation").performClick()
        compose.runOnIdle { assertEquals(1, cancelled) }
    }

    @Test fun existingUblockIsShownWithoutOfferingReinstallation() {
        compose.setContent {
            MaterialTheme {
                DashboardOnboardingOverlay(onDone = {}, ublockStatus = UblockSetupStatus.INSTALLED)
            }
        }
        reachUblock()
        compose.onNodeWithText("uBlock is already installed. Its settings and enable/disable controls are in Extensions.")
            .assertIsDisplayed()
        compose.onNodeWithText("Next").assertIsDisplayed()
    }
}
