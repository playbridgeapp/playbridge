package com.playbridge.sender.cast.remote

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import com.playbridge.sender.cast.atv.AtvPairingPhase
import com.playbridge.sender.cast.atv.AtvPairingUi
import com.playbridge.sender.cast.remote.ui.AtvOpenAppContent
import com.playbridge.sender.cast.remote.ui.AtvPairingContent
import com.playbridge.sender.cast.remote.ui.RemoteHeader
import com.playbridge.sender.model.CastProtocol
import com.playbridge.sender.model.EndpointKey
import org.junit.Rule
import org.junit.Test

class AtvFullControlUiTest {
    @get:Rule val compose = createComposeRule()

    @Test fun pairingSheetExplainsTheCodeField() {
        compose.setContent {
            MaterialTheme {
                AtvPairingContent(AtvPairingUi(phase = AtvPairingPhase.WaitingForCode), onSubmit = {}, onCancel = {}, onRetry = {})
            }
        }
        compose.onNodeWithText("Lets PlayBridge work like your TV remote: any app, Home, typing.").assertIsDisplayed()
        compose.onNodeWithContentDescription("Pairing code").assertIsDisplayed()
        compose.onNodeWithContentDescription("Cancel pairing").assertIsDisplayed()
    }

    @Test fun headerShowsTheFullControlTag() {
        val endpoint = EndpointKey(CastProtocol.PLAYBRIDGE, "tv")
        val state = RemoteUiState(
            receiver = RemoteReceiver(endpoint, "Hisense", RemoteProfile.AndroidTv),
            connection = RemoteConnection.Connected("Hisense"),
            fullControl = FullControlStatus(connected = true, paired = true, available = true, powerOn = true),
        )
        compose.setContent { MaterialTheme { RemoteHeader(state) {} } }
        compose.onNodeWithTag("remote.fullControl").assertIsDisplayed()
        compose.onNodeWithContentDescription("Full control connected").assertIsDisplayed()
    }

    @Test fun openAppListsYouTube() {
        compose.setContent { MaterialTheme { AtvOpenAppContent({}, {}) } }
        compose.onNodeWithText("YouTube").assertIsDisplayed()
        compose.onNodeWithText("com.google.android.youtube.tv").assertIsDisplayed()
    }
}
