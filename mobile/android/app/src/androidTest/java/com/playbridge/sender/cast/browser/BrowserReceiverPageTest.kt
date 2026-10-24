package com.playbridge.sender.cast.browser

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/** Compile-only unless device/emulator testing is explicitly authorized. */
class BrowserReceiverPageTest {
    @get:Rule val compose = createComposeRule()

    private val request = BrowserPairingRequest("session", "receiver", "TV browser", Long.MAX_VALUE)

    @Test
    fun pairingApprovesAtSixDigitsAndClearsAnIncorrectCodeForRetry() {
        var attempts = 0
        compose.setContent {
            MaterialTheme {
                BrowserPairingCard(request, autoFocus = false, onApprove = {
                    attempts++
                    if (attempts == 1) error("wrong code")
                }, onCancel = {})
            }
        }
        compose.onNodeWithText("TV browser wants to connect").assertIsDisplayed()
        compose.onNodeWithContentDescription("6-digit code, 0 entered").performTextInput("12345")
        compose.runOnIdle { assertEquals(0, attempts) }
        compose.onNodeWithContentDescription("6-digit code, 5 entered").performTextInput("6")
        compose.onNodeWithText("Incorrect code").assertIsDisplayed()
        compose.onNodeWithContentDescription("6-digit code, 0 entered").performTextInput("654321")
        compose.runOnIdle { assertEquals(2, attempts) }
        compose.onNodeWithContentDescription("6-digit code, 6 entered").performTextInput("x")
        compose.runOnIdle { assertEquals(2, attempts) }
    }

    @Test
    fun expiredRequestDisablesEntryAndKeepsCancel() {
        var cancelled = false
        compose.setContent {
            MaterialTheme {
                BrowserPairingCard(request.copy(expiresAtMs = 0), autoFocus = false,
                    onApprove = { error("Expired requests must not be approved") }, onCancel = { cancelled = true })
            }
        }
        compose.onNodeWithText("Code expired — reload the page on the TV.").assertIsDisplayed()
        compose.onNodeWithContentDescription("6-digit code, 0 entered").assertIsNotEnabled()
        compose.onNodeWithText("Cancel").performClick()
        compose.runOnIdle { assertEquals(true, cancelled) }
    }

    @Test
    fun hostStartsOnLocalNetworkOnlyAndDoesNotRestartAfterStop() {
        var onLocalNetwork by mutableStateOf(false)
        var host by mutableStateOf(BrowserHostState(urls = listOf("http://192.0.2.1:8770/")))
        var starts = 0
        var copied: String? = null
        compose.setContent {
            MaterialTheme {
                Page(host, onLocalNetwork, onStart = { starts++; host = host.copy(busy = true) }, onCopy = { copied = it })
            }
        }
        compose.onNodeWithText("Connect this phone to Wi‑Fi to use a TV browser.").assertIsDisplayed()
        compose.onNodeWithText("Copy address").assertDoesNotExist()
        compose.runOnIdle { assertEquals(0, starts); onLocalNetwork = true }
        compose.onNodeWithText("Starting…").assertIsDisplayed()
        compose.runOnIdle {
            assertEquals(1, starts)
            host = host.copy(running = true, busy = false)
        }
        compose.onNodeWithText("http://192.0.2.1:8770").assertIsDisplayed()
        compose.onNodeWithText("Waiting for the TV…").assertIsDisplayed()
        compose.onNodeWithText("Copy address").performClick()
        compose.runOnIdle {
            assertEquals("http://192.0.2.1:8770", copied)
            host = host.copy(running = false)
        }
        compose.runOnIdle { assertEquals(1, starts) }
    }

    @Test
    fun readyBrowserUsesPlayHereAndOverflowActions() {
        val session = BrowserReadySession("session", "receiver", "TV browser")
        val actions = mutableListOf<String>()
        compose.setContent {
            MaterialTheme {
                Page(BrowserHostState(running = true, ready = listOf(session)),
                    onPlayHere = { actions += "play" }, onDisconnect = { actions += "disconnect" }, onForget = { actions += "forget" })
            }
        }
        compose.onNodeWithText("CONNECTED").assertIsDisplayed()
        compose.onNodeWithText("Ready").assertIsDisplayed()
        compose.onNodeWithText("Waiting for the TV…").assertDoesNotExist()
        compose.onNodeWithText("Play here").performClick()
        compose.onNodeWithContentDescription("More options for TV browser").performClick()
        compose.onNodeWithText("Disconnect").performClick()
        compose.onNodeWithContentDescription("More options for TV browser").performClick()
        compose.onNodeWithText("Forget").performClick()
        compose.runOnIdle { assertEquals(listOf("play", "disconnect", "forget"), actions) }
    }

    @Test
    fun stopAlwaysRequiresConfirmation() {
        var running by mutableStateOf(true)
        var stops = 0
        compose.setContent {
            MaterialTheme {
                BrowserHostStopAction(running, busy = false) { stops++; running = false }
            }
        }
        compose.onNodeWithText("Stop").performClick()
        compose.onNodeWithText("Stop hosting?").assertIsDisplayed()
        compose.onNodeWithText("The TV browser will disconnect.").assertIsDisplayed()
        compose.onNodeWithText("Cancel").performClick()
        compose.runOnIdle { assertEquals(0, stops) }
        compose.onNodeWithText("Stop").performClick()
        compose.onNodeWithText("Stop host").performClick()
        compose.runOnIdle { assertEquals(1, stops) }
        compose.onNodeWithText("Stop").assertDoesNotExist()
    }

    @Composable
    private fun Page(
        host: BrowserHostState,
        onLocalNetwork: Boolean = true,
        onStart: () -> Unit = {},
        onCopy: (String) -> Unit = {},
        onPlayHere: (BrowserReadySession) -> Unit = {},
        onDisconnect: (BrowserReadySession) -> Unit = {},
        onForget: (BrowserReadySession) -> Unit = {},
    ) {
        BrowserReceiverContent(host, onLocalNetwork, onStart, onCopy, onApprove = { _, _ -> },
            onPlayHere, onDisconnect, onForget)
    }
}
