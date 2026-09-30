package com.playbridge.sender.browser

import android.view.MotionEvent
import android.view.View
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.click
import androidx.compose.ui.viewinterop.AndroidView
import androidx.test.espresso.Espresso.pressBack
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class PlayBridgeEdgeShortcutTest {
    @get:Rule val compose = createComposeRule()

    @Test fun menuNavigatesInTwoTapsAndDismissesWithoutNavigating() {
        var dashboards = 0
        var devices = 0
        var refreshes = 0
        compose.setContent {
            MaterialTheme {
                PlayBridgeEdgeShortcut(true, false, 0, { dashboards++ }, { devices++ }, { refreshes++ })
            }
        }
        compose.onNodeWithText("Dashboard").assertDoesNotExist()
        compose.onNodeWithContentDescription("PlayBridge menu").performClick()
        compose.onNodeWithText("Dashboard").performClick()
        compose.runOnIdle { assertEquals(1, dashboards); assertEquals(0, devices) }
        compose.onNodeWithContentDescription("PlayBridge menu").performClick()
        compose.onNodeWithText("Devices").performClick()
        compose.runOnIdle { assertEquals(1, devices) }
        compose.onNodeWithContentDescription("PlayBridge menu").performClick()
        compose.onNodeWithText("Refresh").performClick()
        compose.onNodeWithText("Refresh").assertDoesNotExist()
        compose.runOnIdle { assertEquals(1, refreshes); assertEquals(1, dashboards); assertEquals(1, devices) }
        compose.onNodeWithContentDescription("PlayBridge menu").performClick()
        pressBack()
        compose.onNodeWithText("Devices").assertDoesNotExist()
        compose.runOnIdle { assertEquals(1, dashboards); assertEquals(1, devices); assertEquals(1, refreshes) }
    }

    @Test fun nativeScreenDashboardNeedsOneTap() {
        var dashboards = 0
        compose.setContent {
            MaterialTheme {
                PlayBridgeEdgeShortcut(false, false, 0, { dashboards++ }, { error("No device menu") }, { error("No refresh menu") })
            }
        }
        compose.onNodeWithContentDescription("Dashboard").performClick()
        compose.runOnIdle { assertEquals(1, dashboards) }
        compose.onNodeWithText("Devices").assertDoesNotExist()
    }

    @Test fun fullscreenTapRevealsShortcutAndStillReachesNativePage() {
        var nativeDowns = 0
        compose.mainClock.autoAdvance = false
        compose.setContent {
            var interactions by remember { mutableIntStateOf(0) }
            MaterialTheme {
                Box(Modifier.fillMaxSize().observeFullscreenInteractions(true) { interactions++ }) {
                    AndroidView(
                        factory = { context ->
                            View(context).apply {
                                setOnTouchListener { _, event ->
                                    if (event.actionMasked == MotionEvent.ACTION_DOWN) nativeDowns++
                                    true
                                }
                            }
                        },
                        modifier = Modifier.fillMaxSize().testTag("page"),
                    )
                    PlayBridgeEdgeShortcut(true, true, interactions, {}, {}, {}, Modifier.align(Alignment.CenterEnd))
                }
            }
        }
        compose.mainClock.advanceTimeBy(200)
        compose.onNodeWithContentDescription("PlayBridge menu").assertDoesNotExist()
        compose.onNodeWithTag("page").performTouchInput { click(center) }
        compose.mainClock.advanceTimeBy(200)
        compose.onNodeWithContentDescription("PlayBridge menu").assertIsDisplayed()
        compose.runOnIdle { assertTrue("The native page must receive the tap", nativeDowns > 0) }
        compose.onNodeWithContentDescription("PlayBridge menu").performClick()
        compose.mainClock.advanceTimeBy(5_000)
        compose.onNodeWithText("Devices").assertIsDisplayed()
        pressBack()
        compose.mainClock.advanceTimeBy(4_000)
        compose.onNodeWithContentDescription("PlayBridge menu").assertDoesNotExist()
        compose.onNodeWithTag("page").performTouchInput { click(center) }
        compose.mainClock.advanceTimeBy(200)
        compose.onNodeWithContentDescription("PlayBridge menu").assertIsDisplayed()
    }

    @Test fun enteringFullscreenHidesShortcutUntilInteraction() {
        val fullscreen = mutableStateOf(false)
        compose.setContent {
            MaterialTheme {
                PlayBridgeEdgeShortcut(true, fullscreen.value, 0, {}, {}, {})
            }
        }
        compose.onNodeWithContentDescription("PlayBridge menu").assertIsDisplayed()
        compose.runOnIdle { fullscreen.value = true }
        compose.onNodeWithContentDescription("PlayBridge menu").assertDoesNotExist()
        compose.runOnIdle { fullscreen.value = false }
        compose.onNodeWithContentDescription("PlayBridge menu").assertIsDisplayed()
    }
}
