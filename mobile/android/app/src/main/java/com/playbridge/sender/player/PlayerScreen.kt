package com.playbridge.sender.player

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.media3.common.Player

/** The full-screen phone player: video, gestures, overlays, controls and sheets. */
@Composable
internal fun PlayerScreen(
    player: Player,
    engine: PhonePlayerEngine,
    onSwitchEngine: (PhonePlayerEngine) -> Unit,
    title: String?,
    externalHeaders: Map<String, String>,
    externalContentType: String?,
    onClose: () -> Unit,
    onEnded: () -> Unit,
    websiteQueue: Boolean = false,
    waitingForWebsiteQueue: Boolean = false,
    isInPip: Boolean,
    /** Shared with the activity: onStop reads it, notification-return resets it. */
    backgroundMode: MutableState<Boolean>,
    /** Send the task home (background mode was just enabled synchronously). */
    onEnterBackgroundMode: () -> Unit = {}
) {
    val state = rememberPlayerScreenState(player, title, websiteQueue)
    PlayerScreenEffects(state, isInPip, onEnded)

    Surface(modifier = Modifier.fillMaxSize(), color = Color.Black) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
                .playerGestures(state, isInPip, LocalHapticFeedback.current)
        ) {
            VideoSurface(state)
            BufferingIndicator(state, waitingForWebsiteQueue)
            DoubleTapIndicator(state)
            SwipeHuds(state)
            StatusMessage(state)
            StatsPanel(state, engine)
            PlaybackErrorOverlay(state, onClose)
            PlayerControlsOverlay(
                state = state,
                externalHeaders = externalHeaders,
                externalContentType = externalContentType,
                onClose = onClose,
                backgroundMode = backgroundMode,
                onEnterBackgroundMode = onEnterBackgroundMode,
            )
            UnlockOverlay(state)
            PlayerSheetsHost(state, engine, onSwitchEngine)
        }
    }
}
