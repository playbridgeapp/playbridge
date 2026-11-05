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
import com.playbridge.sender.cast.openInExternalPlayer

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
    /** Shared with the activity: onStop reads it to keep playing with a media notification. */
    backgroundMode: MutableState<Boolean>,
    engineNotice: EngineNotice?,
    onEngineNoticeAction: () -> Unit,
    onEngineNoticeDismiss: () -> Unit,
) {
    val state = rememberPlayerScreenState(player, title, websiteQueue)
    PlayerScreenEffects(state, isInPip, onEnded)

    fun openExternal() {
        val uri = player.currentMediaItem?.localConfiguration?.uri?.toString() ?: return
        player.pause()
        openInExternalPlayer(state.context, uri, externalContentType, externalHeaders.takeIf { it.isNotEmpty() })
    }

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
            StatsPanel(state, engine)
            PlaybackErrorOverlay(state, engine, onClose)
            PlayerControlsOverlay(state, engine, onClose)
            StatusMessage(state)
            UnlockOverlay(state)
            if (!isInPip) {
                EngineNoticeCard(engineNotice, onEngineNoticeAction, onEngineNoticeDismiss)
                CapturedFrameSnackbar(state)
            }
            PlayerSheetsHost(state, engine, onSwitchEngine, backgroundMode, ::openExternal)
        }
    }
}
