package com.playbridge.sender.player

import androidx.compose.runtime.produceState
import androidx.compose.runtime.collectAsState
import android.content.Intent
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.FilledTonalButton
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import kotlinx.coroutines.delay
import android.view.LayoutInflater
import android.widget.FrameLayout
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.BrightnessHigh
import androidx.compose.material.icons.filled.Forward10
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.ui.PlayerView
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.FastOutSlowInEasing
import coil.compose.AsyncImage
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.media3.common.Player
import androidx.media3.common.text.CueGroup
import androidx.media3.ui.SubtitleView
import androidx.media3.ui.CaptionStyleCompat
import com.playbridge.sender.R
import kotlin.math.roundToInt

/**
 * The video itself, in a TextureView-backed PlayerView, with pinch-zoom applied on top. Subtitles
 * sit in their own layer over the visible area, so zooming or filling the screen never pushes
 * them out of view.
 */
@Composable
internal fun VideoSurface(state: PlayerScreenState) {
    Box(Modifier.fillMaxSize()) {
        ZoomedVideo(state)
        SubtitleLayer(state.player)
    }
}

@Composable
private fun ZoomedVideo(state: PlayerScreenState) {
    val player = state.player
    // mpv draws subtitles into the picture, so it zooms and fills itself rather than being scaled.
    val mpv = player.unwrapped() as? MpvMediaPlayer
    var size by remember { mutableStateOf(IntSize.Zero) }
    LaunchedEffect(mpv, state.shownResizeMode) { mpv?.setScaleMode(mpvScaleMode(state.shownResizeMode)) }
    LaunchedEffect(mpv) {
        if (mpv == null) return@LaunchedEffect
        snapshotFlow { Triple(state.scale, state.panOffset, size) }.collect { (scale, pan, area) ->
            if (area.width == 0 || area.height == 0) return@collect
            mpv.setZoom(scale.toDouble(), pan.x / (area.width * scale).toDouble(), pan.y / (area.height * scale).toDouble())
        }
    }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .onSizeChanged { size = it }
            // Perf: pinch-zoom transform on the outer Box, not the AndroidView —
            // per-gesture-frame TextureView re-layout dropped frames. Lambda
            // overload applies values at draw time without recomposing.
            .graphicsLayer {
                if (mpv == null) {
                    scaleX = state.scale
                    scaleY = state.scale
                    translationX = state.panOffset.x
                    translationY = state.panOffset.y
                }
            }
            .panZoomGestures(state)
    ) {
        AndroidView(
            factory = { ctx ->
                // TextureView-backed PlayerView — surface ready when attached
                // (SurfaceView is async and left Compose in permanent BUFFERING).
                (LayoutInflater.from(ctx).inflate(R.layout.player_view, FrameLayout(ctx), false) as PlayerView).apply {
                    this.player = player
                    this.resizeMode = viewResizeMode(player, state.shownResizeMode)
                    ensurePrepared(player)
                    post { ensurePrepared(player) }

                    // SubtitleLayer draws subtitles outside the zoomed frame.
                    subtitleView?.visibility = android.view.View.GONE
                }
            },
            update = { view ->
                val resizeMode = viewResizeMode(player, state.shownResizeMode)
                if (view.resizeMode != resizeMode) view.resizeMode = resizeMode
                if (view.player !== player) {
                    view.player = player
                    ensurePrepared(player)
                }
            },
            modifier = Modifier
                .fillMaxSize()
        )
    }
}

/** mpv's picture fills the view; mpv letterboxes, zooms or stretches it inside. */
private fun viewResizeMode(player: Player, mode: Int): Int =
    if (player.unwrapped() is MpvMediaPlayer) AspectRatioFrameLayout.RESIZE_MODE_FILL else mode

private fun mpvScaleMode(mode: Int): String = when (mode) {
    AspectRatioFrameLayout.RESIZE_MODE_ZOOM -> "Zoom"
    AspectRatioFrameLayout.RESIZE_MODE_FILL -> "Fill"
    else -> "Fit"
}

/**
 * ExoPlayer's subtitles, unzoomed, over the whole video area, in the phone's subtitle style.
 * mpv draws its own into the picture, so it gets the style passed instead.
 */
@Composable
private fun SubtitleLayer(player: Player) {
    val settings: com.playbridge.sender.data.settings.SettingsRepository = org.koin.compose.koinInject()
    val style by settings.phoneSubtitleStyle.collectAsState(initial = com.playbridge.shared.player.SubtitleStyle.DEFAULT)
    LaunchedEffect(player, style) { (player.unwrapped() as? MpvMediaPlayer)?.setSubtitleStyle(style) }
    val context = androidx.compose.ui.platform.LocalContext.current
    val fontRevision by PhoneSubtitleFonts.revision.collectAsState()
    // The chosen custom font, linked in for mpv; Android falls back per character to the device's fonts.
    val typeface by produceState<android.graphics.Typeface?>(null, style.customFont, fontRevision) {
        value = PhoneSubtitleFonts.select(context, style.customFont)
    }
    var cues by remember(player) { mutableStateOf(player.currentCues.cues) }
    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onCues(cueGroup: CueGroup) {
                cues = cueGroup.cues
            }
        }
        player.addListener(listener)
        onDispose { player.removeListener(listener) }
    }
    AndroidView(
        factory = { ctx ->
            SubtitleView(ctx).apply {
                // ExoPlayer has no ASS, and site WebVTT boxes clash with the chosen look, so the
                // phone style always wins here; "Restyle styled subtitles" matters for mpv.
                setApplyEmbeddedStyles(false)
                setApplyEmbeddedFontSizes(false)
            }
        },
        update = { view ->
            view.setStyle(
                CaptionStyleCompat(
                    android.graphics.Color.WHITE,
                    android.graphics.Color.argb((style.background.opacity * 255).toInt(), 0, 0, 0),
                    android.graphics.Color.TRANSPARENT,
                    when (style.edge) {
                        com.playbridge.shared.player.SubtitleStyle.Edge.OUTLINE -> CaptionStyleCompat.EDGE_TYPE_OUTLINE
                        com.playbridge.shared.player.SubtitleStyle.Edge.SHADOW -> CaptionStyleCompat.EDGE_TYPE_DROP_SHADOW
                        com.playbridge.shared.player.SubtitleStyle.Edge.NONE -> CaptionStyleCompat.EDGE_TYPE_NONE
                    },
                    android.graphics.Color.BLACK,
                    typeface ?: android.graphics.Typeface.DEFAULT_BOLD,
                )
            )
            view.setFixedTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 18f * style.size.scale)
            view.setCues(cues)
        },
        modifier = Modifier.fillMaxSize(),
    )
}

/**
 * Buffering spinner — hidden once a frame has painted even if ExoPlayer still reports
 * BUFFERING (local content:// edge case). Also shown while a website supplies the next item.
 */
@Composable
internal fun BoxScope.BufferingIndicator(state: PlayerScreenState, waitingForWebsiteQueue: Boolean, siteIconUrl: String?) {
    if ((state.isBuffering && !state.hasRenderedFrame || waitingForWebsiteQueue) &&
        state.dragMode == DragMode.NONE &&
        state.errorMessage == null
    ) {
        Column(
            modifier = Modifier.align(Alignment.Center),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            LoadingMark(siteIconUrl)
            if (waitingForWebsiteQueue) {
                OverlayPill("Getting the next episode from the website…")
            }
        }
    }
}

/**
 * The website's icon gently pulsing while the video loads; the spinner shows until the icon
 * arrives, and stays if the site has none.
 */
@Composable
private fun LoadingMark(siteIconUrl: String?) {
    if (siteIconUrl == null) {
        CircularProgressIndicator(color = Color.White)
        return
    }
    var iconShown by remember(siteIconUrl) { mutableStateOf(false) }
    val pulse = rememberInfiniteTransition(label = "SiteIconPulse")
    val pulseSpec = infiniteRepeatable<Float>(tween(900, easing = FastOutSlowInEasing), RepeatMode.Reverse)
    val alpha by pulse.animateFloat(0.35f, 1f, pulseSpec, label = "SiteIconAlpha")
    val scale by pulse.animateFloat(0.9f, 1f, pulseSpec, label = "SiteIconScale")
    Box(Modifier.size(56.dp), contentAlignment = Alignment.Center) {
        if (!iconShown) CircularProgressIndicator(color = Color.White)
        AsyncImage(
            model = siteIconUrl,
            contentDescription = null,
            onSuccess = { iconShown = true },
            onError = { iconShown = false },
            modifier = Modifier
                .size(48.dp)
                .graphicsLayer {
                    this.alpha = if (iconShown) alpha else 0f
                    scaleX = scale
                    scaleY = scale
                }
                .clip(RoundedCornerShape(12.dp)),
        )
    }
}

/** Double-tap skip indicator (cumulative: 10s, 20s, …). */
@Composable
internal fun BoxScope.DoubleTapIndicator(state: PlayerScreenState) {
    if (state.doubleTapSide == 0) return
    Box(
        modifier = Modifier
            .fillMaxHeight()
            .fillMaxWidth(0.5f)
            .align(if (state.doubleTapSide < 0) Alignment.CenterStart else Alignment.CenterEnd),
        contentAlignment = Alignment.Center
    ) {
        Surface(
            color = Color.Black.copy(alpha = 0.45f),
            shape = RoundedCornerShape(50)
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = if (state.doubleTapSide < 0) Icons.Filled.Replay10 else Icons.Filled.Forward10,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(28.dp)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    "${state.doubleTapSeconds}s",
                    color = Color.White,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }
    }
}

/** Volume, brightness and seek HUDs while a swipe is in progress. */
@Composable
internal fun BoxScope.SwipeHuds(state: PlayerScreenState) {
    val maxVolume = state.maxVolume
    when (state.dragMode) {
        DragMode.VOLUME -> GestureHud(
            icon = { Icon(Icons.AutoMirrored.Filled.VolumeUp, null, tint = Color.White) },
            fraction = state.volume / maxVolume,
            label = "${(state.volume / maxVolume * 100).roundToInt()}%",
            alignment = Alignment.CenterStart
        )
        DragMode.BRIGHTNESS -> GestureHud(
            icon = { Icon(Icons.Filled.BrightnessHigh, null, tint = Color.White) },
            fraction = state.brightness,
            label = "${(state.brightness * 100).roundToInt()}%",
            alignment = Alignment.CenterEnd
        )
        DragMode.SEEK -> SeekHud(state.seekDeltaMs, state.seekPreviewMs, state.playbackDuration.longValue)
        DragMode.NONE -> {}
    }
}

/** A short mid-screen confirmation (speed, repeat, aspect…). */
@Composable
internal fun BoxScope.StatusMessage(state: PlayerScreenState) {
    AnimatedVisibility(
        visible = state.statusMessage != null,
        modifier = Modifier.align(Alignment.Center),
        enter = fadeIn(),
        exit = fadeOut()
    ) {
        OverlayPill(state.statusMessage ?: "", bold = true)
    }
}

@Composable
internal fun BoxScope.StatsPanel(state: PlayerScreenState, engine: PhonePlayerEngine) {
    AnimatedVisibility(
        visible = state.showStats && state.errorMessage == null,
        modifier = Modifier
            .align(Alignment.TopStart)
            .windowInsetsPadding(WindowInsets.statusBars.union(WindowInsets.displayCutout)),
        enter = fadeIn(),
        exit = fadeOut()
    ) {
        StatsOverlay(
            player = state.player,
            engine = engine,
            activeVideoFormat = state.activeVideoFormat,
            activeAudioFormat = state.activeAudioFormat,
            positionMs = state.playbackProgress.longValue,
            bufferedMs = state.playbackBuffered.longValue,
            speed = state.speed,
            onClose = { state.showStats = false }
        )
    }
}

/** Error overlay with recovery actions (transient timeouts shouldn't dead-end). */
@Composable
internal fun PlaybackErrorOverlay(state: PlayerScreenState, engine: PhonePlayerEngine, onClose: () -> Unit) {
    val errorMessage = state.errorMessage ?: return
    Box(
        modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.8f)),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(horizontal = 32.dp),
        ) {
            Icon(Icons.Filled.WarningAmber, contentDescription = null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(40.dp))
            Spacer(Modifier.height(10.dp))
            Text("Playback failed", style = MaterialTheme.typography.titleMedium, color = Color.White)
            Spacer(Modifier.height(6.dp))
            Text(
                text = errorMessage,
                style = MaterialTheme.typography.bodyMedium,
                color = Color.White.copy(alpha = 0.75f),
                textAlign = TextAlign.Center,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = listOfNotNull(engine.label, state.currentTitle).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = Color.White.copy(alpha = 0.55f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = onClose) { Text("Close", color = Color.White.copy(alpha = 0.85f)) }
                if (state.isPlaylist && state.currentIndex < state.episodeTitles.size - 1) {
                    FilledTonalButton(onClick = { state.goToEpisode(state.currentIndex + 1) }) {
                        Text("Next episode")
                    }
                }
                Button(onClick = {
                    state.errorMessage = null
                    state.player.prepare()
                }) { Text("Retry") }
            }
        }
    }
}

/** Unlock affordance, shown briefly when the locked screen is tapped. */
@Composable
internal fun UnlockOverlay(state: PlayerScreenState) {
    AnimatedVisibility(
        visible = state.locked && state.showUnlock,
        enter = fadeIn(),
        exit = fadeOut()
    ) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                GlassControl(
                    onClick = {
                        state.locked = false
                        state.showUnlock = false
                        state.controlsVisible = true
                        state.markInteraction()
                    },
                    diameter = 56.dp,
                    background = Color.Black.copy(alpha = 0.6f)
                ) {
                    Icon(Icons.Filled.LockOpen, "Unlock", tint = Color.White, modifier = Modifier.size(26.dp))
                }
                OverlayPill("Controls locked · tap to unlock")
            }
        }
    }
}

/** A dark rounded label floating over video. */
@Composable
internal fun OverlayPill(text: String, bold: Boolean = false) {
    Surface(color = Color.Black.copy(alpha = 0.72f), shape = RoundedCornerShape(50)) {
        Text(
            text = text,
            color = Color.White,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = if (bold) FontWeight.SemiBold else FontWeight.Normal,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
        )
    }
}

/** A notice about which decoder is playing, with an optional action (e.g. Use ExoPlayer). */
internal data class EngineNotice(val title: String, val detail: String? = null, val actionLabel: String? = null)

@Composable
internal fun BoxScope.EngineNoticeCard(notice: EngineNotice?, onAction: () -> Unit, onDismiss: () -> Unit) {
    LaunchedEffect(notice) {
        if (notice != null) {
            delay(6_000)
            onDismiss()
        }
    }
    AnimatedVisibility(
        visible = notice != null,
        modifier = Modifier
            .align(Alignment.BottomCenter)
            .windowInsetsPadding(WindowInsets.navigationBars.union(WindowInsets.displayCutout))
            .padding(start = 16.dp, end = 16.dp, bottom = 120.dp),
        enter = fadeIn(),
        exit = fadeOut(),
    ) {
        val shown = notice ?: return@AnimatedVisibility
        SnackCard {
            Icon(Icons.Filled.SwapHoriz, contentDescription = null, tint = MaterialTheme.colorScheme.onSurface)
            Column(Modifier.weight(1f)) {
                Text(shown.title, style = MaterialTheme.typography.titleSmall)
                shown.detail?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            shown.actionLabel?.let { TextButton(onClick = onAction) { Text(it) } }
        }
    }
}

/** "Frame saved to Gallery", with a thumbnail and View. */
@Composable
internal fun BoxScope.CapturedFrameSnackbar(state: PlayerScreenState) {
    val frame = state.capturedFrame
    LaunchedEffect(frame) {
        if (frame != null) {
            delay(4_000)
            state.capturedFrame = null
        }
    }
    AnimatedVisibility(
        visible = frame != null,
        modifier = Modifier
            .align(Alignment.BottomCenter)
            .windowInsetsPadding(WindowInsets.navigationBars.union(WindowInsets.displayCutout))
            .padding(start = 16.dp, end = 16.dp, bottom = 24.dp),
        enter = fadeIn(),
        exit = fadeOut(),
    ) {
        val shown = frame ?: return@AnimatedVisibility
        SnackCard {
            Image(
                bitmap = shown.preview.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(width = 64.dp, height = 36.dp).clip(RoundedCornerShape(6.dp)),
            )
            Text("Frame saved to Gallery", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            TextButton(onClick = {
                state.capturedFrame = null
                runCatching {
                    state.context.startActivity(
                        Intent(Intent.ACTION_VIEW, shown.uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    )
                }
            }) { Text("View") }
        }
    }
}

@Composable
private fun SnackCard(content: @Composable RowScope.() -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer,
        contentColor = MaterialTheme.colorScheme.onSurface,
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.widthIn(max = 460.dp).fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(start = 12.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            content = content,
        )
    }
}
