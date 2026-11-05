package com.playbridge.sender.player

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.union
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.media3.common.Player
import com.playbridge.sender.cast.openInExternalPlayer

/**
 * The phone player: video, gestures, overlays, controls and sheets. A landscape video on an
 * upright phone uses the portrait layout (video on top, controls below); otherwise the video
 * fills the screen with controls over it.
 */
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
    poster: PlayerPoster,
    /** The tapped thumbnail's screen bounds: the video frame grows out of it once. */
    growFrom: android.graphics.Rect?,
    onGrown: () -> Unit,
    /** Fullscreen (landscape) when true, back to portrait when false. */
    onFullscreen: (Boolean) -> Unit,
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
        BoxWithConstraints(Modifier.fillMaxSize().background(Color.Black)) {
            val portraitLayout = maxHeight > maxWidth && state.isLandscapeVideo && !isInPip
            val videoTop = WindowInsets.statusBars.union(WindowInsets.displayCutout).asPaddingValues().calculateTopPadding() +
                PORTRAIT_TOP_BAR_HEIGHT
            val videoHeight = if (portraitLayout) maxWidth / state.layoutAspect else maxHeight
            // Growing from a thumbnail only maps onto an upright screen; landscape just fades in.
            val grow = rememberGrowFromThumbnail(growFrom.takeIf { maxHeight > maxWidth }, onGrown)
            val gestures = Modifier.playerGestures(state, isInPip, LocalHapticFeedback.current)

            // The video keeps its place in the tree across layouts, so its PlayerView survives rotation.
            Box(
                modifier = (if (portraitLayout) {
                    Modifier.padding(top = videoTop).fillMaxWidth().height(videoHeight)
                } else {
                    Modifier.fillMaxSize()
                }).then(grow.videoModifier).then(gestures)
            ) {
                VideoSurface(state)
                PosterBackdrop(poster, state)
                BufferingIndicator(state, waitingForWebsiteQueue)
                DoubleTapIndicator(state)
                SwipeHuds(state)
                if (portraitLayout) FullscreenButton(onFullscreen)
                else PlayerControlsOverlay(state, engine, onClose)
            }
            if (portraitLayout) {
                Box(grow.surroundingModifier) { PortraitTopBar(state, onClose) }
                PortraitPanel(state, engine, onFullscreen, Modifier.padding(top = videoTop + videoHeight).then(grow.surroundingModifier))
            }
            StatsPanel(state, engine)
            PlaybackErrorOverlay(state, engine, onClose)
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
