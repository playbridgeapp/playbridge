package com.playbridge.sender.cast.remote

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.playbridge.sender.cast.atv.AtvPairingController
import com.playbridge.sender.cast.remote.ui.AtvPairingRoute
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
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

    @Test fun retainedCompositionFollowsActivityStopAndRestartAndDisposal() {
        val owner = object : LifecycleOwner { override val lifecycle = LifecycleRegistry.createUnsafe(this) }
        val events = mutableListOf<Boolean>()
        val composed = mutableStateOf(true)
        compose.setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides owner) {
                if (composed.value) RemoteRouteLifecycleEffect(owner, { events += true }, { events += false })
            }
        }
        compose.runOnIdle {
            owner.lifecycle.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
            owner.lifecycle.handleLifecycleEvent(Lifecycle.Event.ON_START)
            owner.lifecycle.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
            owner.lifecycle.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
            owner.lifecycle.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
            owner.lifecycle.handleLifecycleEvent(Lifecycle.Event.ON_START)
            assertEquals(listOf(false, true, false, true), events)
            composed.value = false
        }
        compose.runOnIdle { assertEquals(listOf(false, true, false, true, false), events) }
    }

    @Test fun removingPairingSheetCancelsItsOwner() {
        var begins = 0
        var cancels = 0
        val controller = object : AtvPairingController {
            override val pairing = MutableStateFlow(AtvPairingUi())
            override fun begin() { begins++; pairing.value = AtvPairingUi(AtvPairingPhase.WaitingForCode) }
            override fun submitCode(code: String) = Unit
            override fun cancel() { cancels++; pairing.value = AtvPairingUi() }
        }
        val show = mutableStateOf(true)
        compose.setContent { MaterialTheme { if (show.value) AtvPairingRoute({}, controller) } }
        compose.runOnIdle { assertEquals(1, begins); show.value = false }
        compose.runOnIdle { assertEquals(1, cancels); assertEquals(AtvPairingPhase.Idle, controller.pairing.value.phase) }
    }

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
