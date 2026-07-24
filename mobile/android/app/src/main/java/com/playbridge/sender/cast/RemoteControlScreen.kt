package com.playbridge.sender.cast

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.platform.LocalContext
import android.net.Uri
import com.playbridge.sender.library.*
import com.playbridge.sender.browser.*
import com.playbridge.sender.connection.WebSocketClient
import androidx.compose.foundation.Canvas
import androidx.compose.ui.geometry.Offset
import android.view.HapticFeedbackConstants
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import kotlinx.coroutines.launch
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.automirrored.filled.VolumeDown
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import androidx.compose.foundation.BorderStroke
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.interaction.MutableInteractionSource
import kotlin.math.abs
import kotlin.math.sin

/** Live playback status synced from the TV via `status` messages. */
data class TvPlaybackStatus(
    val state: String,
    val positionMs: Long,
    val durationMs: Long,
    val title: String?
)

/** An available audio or subtitle track on the TV, synced via `tracks` messages. */
data class MediaTrack(
    val id: String,
    val name: String,
    val selected: Boolean,
    val type: String? = null
)

/** TV player settings synced via `player_settings` messages. */
data class TvPlayerSettings(
    val speed: Float = 1.0f,
    val scaling: String = "Fit",
    val audioBoost: Boolean = false,
    val subtitleOffsetMs: Long = 0L,
    val engine: String = "",
    val qualityMaxHeight: Int = 0,
    val currentVideoHeight: Int = 0,
    val isLive: Boolean = false,
    val isSeekable: Boolean = true,
    val speedAvailable: Boolean = true,
    val scalingAvailable: Boolean = true,
    val audioBoostAvailable: Boolean = true,
    val qualityAvailable: Boolean = false,
)

/** A subtitle search result the user can add to the TV. */
data class SubtitleOption(
    val label: String,
    val url: String
)

/**
 * Full-screen remote control. Redesigned with a high-tech glassmorphism aesthetic,
 * horizontal episode carousels, and a segmented mode selector for excellent UX.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RemoteControlScreen(
    activeContext: String,
    onBack: () -> Unit,
    onRemoteKey: (String) -> Unit,
    onMouseMove: (dx: Float, dy: Float) -> Unit,
    onMouseClick: () -> Unit,
    onMouseScroll: (dx: Float, dy: Float) -> Unit,
    onPinchZoom: (factor: Float) -> Unit = {},
    onMouseDown: () -> Unit = {},
    onMouseUp: () -> Unit = {},
    onBrowserControl: (String) -> Unit = {},
    onPlayerControl: (String) -> Unit = {},
    playbackState: String? = null,
    externalProtocolLabel: String? = null,
    externalCapabilities: Set<Capability>? = null,
    isLive: Boolean = false,
    positionMs: Long = 0L,
    durationMs: Long = 0L,
    mediaTitle: String? = null,
    episodes: List<PlaylistEpisode> = emptyList(),
    currentEpisodeIndex: Int = 0,
    videoTracks: List<MediaTrack> = emptyList(),
    audioTracks: List<MediaTrack> = emptyList(),
    subtitleTracks: List<MediaTrack> = emptyList(),
    playerSettings: TvPlayerSettings = TvPlayerSettings(),
    onSeekTo: (Long) -> Unit = {},
    onJumpToEpisode: (Int) -> Unit = {},
    onSelectAudio: (String) -> Unit = {},
    onSelectSubtitle: (String) -> Unit = {},
    onSetVideoQuality: (Int) -> Unit = {},
    onSetSpeed: (Float) -> Unit = {},
    onSetScaling: (String) -> Unit = {},
    onToggleAudioBoost: () -> Unit = {},
    onAdjustSubtitleOffset: (Long) -> Unit = {},
    onSwitchEngine: (String) -> Unit = {},
    onAddSubtitleUrl: (String) -> Unit = {},
    onSearchSubtitles: (suspend () -> List<SubtitleOption>)? = null,
    tvName: String? = null,
    connectionState: WebSocketClient.ConnectionState = WebSocketClient.ConnectionState.Disconnected
) {
    var showSettingsSheet by remember { mutableStateOf(false) }
    var showAddSubtitle by remember { mutableStateOf(false) }
    var showSubtitlesSheet by remember { mutableStateOf(false) }
    val remoteContext = remoteContextOf(activeContext)
    val videoActive = playbackState == "playing" || playbackState == "paused" || playbackState == "buffering"
    val externalMode = externalProtocolLabel != null
    val capabilities = externalCapabilities.orEmpty()
    val supportsRemote = !externalMode || Capability.REMOTE in capabilities
    val supportsVolume = !externalMode || Capability.VOLUME in capabilities
    val supportsSeek = !externalMode || Capability.SEEK in capabilities
    val availableModes = buildList {
        add(RemoteMode.CONTEXT)
        if (supportsRemote) add(RemoteMode.DPAD)
        if (!externalMode) add(RemoteMode.TOUCHPAD)
    }
    var modeByContext by remember {
        mutableStateOf(
            mapOf(
                RemoteContext.PLAYER to RemoteMode.CONTEXT,
                RemoteContext.BROWSER to RemoteMode.TOUCHPAD,
                RemoteContext.IDLE to RemoteMode.CONTEXT
            )
        )
    }
    val selectedMode = modeByContext[remoteContext] ?: RemoteMode.CONTEXT
    val effectiveMode = selectedMode.takeIf { it in availableModes } ?: RemoteMode.CONTEXT
    fun selectMode(mode: RemoteMode) { modeByContext = modeByContext + (remoteContext to mode) }

    LaunchedEffect(remoteContext, videoActive) {
        if (remoteContext == RemoteContext.BROWSER && videoActive) {
            selectMode(RemoteMode.CONTEXT)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Remote", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Transparent
                )
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp)
                .padding(bottom = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            ConnectedTvChip(
                deviceName = tvName,
                connectionState = connectionState,
                protocolLabel = externalProtocolLabel,
            )
            Spacer(modifier = Modifier.height(12.dp))

            // Segmented Mode Selector for immediate UX clarity
            RemoteModeSegmentedRow(
                selected = effectiveMode,
                modes = availableModes,
                onSelect = { selectMode(it) },
            )
            Spacer(modifier = Modifier.height(16.dp))

            AnimatedContent(
                targetState = effectiveMode to remoteContext,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                transitionSpec = {
                    fadeIn(animationSpec = tween(220)) togetherWith fadeOut(animationSpec = tween(220))
                },
                label = "RemoteBody"
            ) { (mode, ctx) ->
                Column(
                    modifier = Modifier.fillMaxSize(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    when (mode) {
                        RemoteMode.CONTEXT -> when (ctx) {
                            RemoteContext.PLAYER -> {
                                NowPlayingPanel(title = mediaTitle)

                                if (!externalMode) {
                                    TrackChipsRow(
                                        audioTracks = audioTracks,
                                        subtitleTracks = subtitleTracks,
                                        onSelectAudio = onSelectAudio,
                                        onSubtitlesClick = { showSubtitlesSheet = true },
                                        onMore = { showSettingsSheet = true }
                                    )
                                }

                                EpisodesCarousel(
                                    episodes = episodes,
                                    currentIndex = currentEpisodeIndex,
                                    onJumpToEpisode = onJumpToEpisode,
                                    modifier = Modifier.weight(1f)
                                )

                                SeekVolumeBar(
                                    positionMs = positionMs,
                                    durationMs = durationMs,
                                    isLive = isLive,
                                    isPlaying = playbackState == null || playbackState == "playing" || playbackState == "buffering",
                                    enableVolume = supportsVolume,
                                    onSeekTo = onSeekTo,
                                    onVolumeUp = { onRemoteKey("volume_up") },
                                    onVolumeDown = { onRemoteKey("volume_down") },
                                    onPlayPauseToggle = { onPlayerControl(if (playbackState == "playing") "pause" else "play") }
                                )
                                MediaControlRow(
                                    onPlayerControl = onPlayerControl,
                                    showLoop = !externalMode,
                                    showSeek = !isLive && supportsSeek,
                                )
                            }

                            RemoteContext.BROWSER -> BrowserContextView(
                                playbackState = playbackState,
                                positionMs = positionMs,
                                durationMs = durationMs,
                                isLive = isLive,
                                dlnaMode = externalMode,
                                mediaTitle = mediaTitle,
                                onBrowserControl = onBrowserControl,
                                onRemoteKey = onRemoteKey
                            )

                            RemoteContext.IDLE -> IdleEmptyState(
                                tvName = tvName,
                                onShowControls = { selectMode(RemoteMode.TOUCHPAD) },
                                modifier = Modifier.weight(1f)
                            )
                        }

                        RemoteMode.DPAD -> {
                            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                                DpadArea(onRemoteKey = onRemoteKey)
                            }
                            ModeBottomBar(
                                ctx = ctx, dlnaMode = externalMode, isLive = isLive,
                                playbackState = playbackState, onRemoteKey = onRemoteKey,
                                onBrowserControl = onBrowserControl, onPlayerControl = onPlayerControl
                            )
                        }
                        RemoteMode.TOUCHPAD -> {
                            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                                TouchpadArea(
                                    onMouseMove = onMouseMove,
                                    onMouseClick = onMouseClick,
                                    onMouseScroll = onMouseScroll,
                                    onPinchZoom = onPinchZoom,
                                    onMouseDown = onMouseDown,
                                    onMouseUp = onMouseUp
                                )
                            }
                            ModeBottomBar(
                                ctx = ctx, dlnaMode = externalMode, isLive = isLive,
                                playbackState = playbackState, onRemoteKey = onRemoteKey,
                                onBrowserControl = onBrowserControl, onPlayerControl = onPlayerControl
                            )
                        }
                        RemoteMode.KEYBOARD -> Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                            KeyboardArea(onRemoteKey = onRemoteKey)
                        }
                    }
                }
            }
        }
    }

    if (showSettingsSheet) {
        PlayerSettingsSheet(
            settings = playerSettings,
            videoTracks = videoTracks,
            onSetVideoQuality = onSetVideoQuality,
            onSetSpeed = onSetSpeed,
            onSetScaling = onSetScaling,
            onToggleAudioBoost = onToggleAudioBoost,
            onAdjustSubtitleOffset = onAdjustSubtitleOffset,
            onSwitchEngine = onSwitchEngine,
            onAddSubtitle = { showAddSubtitle = true },
            onDismiss = { showSettingsSheet = false }
        )
    }
    if (showAddSubtitle) {
        AddSubtitleDialog(
            onSearchSubtitles = onSearchSubtitles,
            onAddUrl = { url ->
                onAddSubtitleUrl(url)
                showAddSubtitle = false
                showSettingsSheet = false
            },
            onDismiss = { showAddSubtitle = false }
        )
    }
    if (showSubtitlesSheet) {
        SubtitlesBottomSheet(
            tracks = subtitleTracks,
            onSelect = onSelectSubtitle,
            onDismiss = { showSubtitlesSheet = false }
        )
    }
}

private enum class RemoteMode(val label: String, val icon: ImageVector) {
    CONTEXT("Context", Icons.Default.Dashboard),
    DPAD("D-Pad", Icons.Default.Gamepad),
    TOUCHPAD("Touchpad", Icons.Default.TouchApp),
    KEYBOARD("Keyboard", Icons.Default.Keyboard)
}

private enum class RemoteContext { PLAYER, BROWSER, IDLE }

private fun remoteContextOf(active: String): RemoteContext = when (active) {
    "player" -> RemoteContext.PLAYER
    "browser" -> RemoteContext.BROWSER
    else -> RemoteContext.IDLE
}

/** Reusable Glassmorphic Surface Modifier */
private fun Modifier.glassSurface(
    shape: RoundedCornerShape = RoundedCornerShape(24.dp),
    alpha: Float = 0.1f
): Modifier = this
    .clip(shape)
    .background(
        Brush.verticalGradient(
            colors = listOf(
                Color.White.copy(alpha = alpha + 0.04f),
                Color.White.copy(alpha = alpha)
            )
        )
    )
    .border(1.dp, Color.White.copy(alpha = 0.15f), shape)

/** Segmented Row for switching remote modes instantly */
@Composable
private fun RemoteModeSegmentedRow(
    selected: RemoteMode,
    modes: List<RemoteMode>,
    onSelect: (RemoteMode) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .glassSurface(RoundedCornerShape(50), alpha = 0.05f)
            .padding(4.dp),
        horizontalArrangement = Arrangement.SpaceEvenly
    ) {
        modes.forEach { mode ->
            val isSelected = mode == selected
            Row(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(50))
                    .background(if (isSelected) MaterialTheme.colorScheme.primary else Color.Transparent)
                    .clickable { onSelect(mode) }
                    .padding(vertical = 8.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    mode.icon,
                    contentDescription = mode.label,
                    modifier = Modifier.size(16.dp),
                    tint = if (isSelected) Color.Black else Color.White.copy(alpha = 0.6f)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    mode.label,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                    color = if (isSelected) Color.Black else Color.White.copy(alpha = 0.6f)
                )
            }
        }
    }
}

@Composable
private fun ConnectedTvChip(
    deviceName: String?,
    connectionState: WebSocketClient.ConnectionState,
    protocolLabel: String? = null,
) {
    val isExternal = protocolLabel != null
    val isConnected = isExternal || connectionState is WebSocketClient.ConnectionState.Connected
    val isConnecting = !isExternal && (
        connectionState is WebSocketClient.ConnectionState.Connecting ||
            connectionState is WebSocketClient.ConnectionState.Retrying ||
            connectionState is WebSocketClient.ConnectionState.WaitingForApproval
        )

    val connectedGreen = Color(0xFF4CAF50)
    val connectingOrange = Color(0xFFFF9800)
    val accent = when {
        isConnected -> connectedGreen
        isConnecting -> connectingOrange
        else -> Color.White.copy(alpha = 0.6f)
    }

    val name = deviceName ?: if (isExternal) "receiver" else "TV"
    val label = when {
        isExternal -> "Casting to $name"
        isConnected -> "Watching on $name"
        isConnecting -> "Connecting to $name…"
        else -> "Not connected"
    }
    val icon = when {
        isExternal -> Icons.Default.Cast
        isConnected || isConnecting -> Icons.Default.Tv
        else -> Icons.Default.Smartphone
    }

    Surface(
        shape = RoundedCornerShape(50),
        color = accent.copy(alpha = 0.15f),
        border = BorderStroke(1.dp, accent.copy(alpha = 0.5f))
    ) {
        Row(
            modifier = Modifier.heightIn(min = 36.dp).padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(16.dp), tint = accent)
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Medium,
                color = accent,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = 240.dp)
            )
            protocolLabel?.let { ProtocolBadge(text = it, accent = accent) }
        }
    }
}

@Composable
private fun ProtocolBadge(text: String, accent: Color) {
    Surface(shape = RoundedCornerShape(4.dp), color = accent.copy(alpha = 0.25f)) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            color = accent,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
        )
    }
}

@Composable
private fun ColumnScope.BrowserContextView(
    playbackState: String?,
    positionMs: Long,
    durationMs: Long,
    isLive: Boolean,
    dlnaMode: Boolean,
    mediaTitle: String?,
    onBrowserControl: (String) -> Unit,
    onRemoteKey: (String) -> Unit
) {
    val videoActive = playbackState == "playing" || playbackState == "paused" || playbackState == "buffering"
    if (videoActive) {
        NowPlayingPanel(title = mediaTitle)
    }

    Spacer(modifier = Modifier.weight(1f))

    if (videoActive) {
        SeekVolumeBar(
            positionMs = positionMs,
            durationMs = durationMs,
            isLive = isLive,
            isPlaying = playbackState == "playing" || playbackState == "buffering",
            enableVolume = !dlnaMode,
            onSeekTo = { ms -> onBrowserControl("seek_to:$ms") },
            onVolumeUp = { onRemoteKey("volume_up") },
            onVolumeDown = { onRemoteKey("volume_down") },
            onPlayPauseToggle = { onBrowserControl("toggle_play") }
        )
    } else {
        VolumeRow(onVolumeUp = { onRemoteKey("volume_up") }, onVolumeDown = { onRemoteKey("volume_down") })
    }

    BrowserContextRow(onBrowserControl = onBrowserControl, onRemoteKey = onRemoteKey)
}

@Composable
private fun ColumnScope.ModeBottomBar(
    ctx: RemoteContext,
    dlnaMode: Boolean,
    isLive: Boolean,
    playbackState: String?,
    onRemoteKey: (String) -> Unit,
    onBrowserControl: (String) -> Unit,
    onPlayerControl: (String) -> Unit
) {
    when (ctx) {
        RemoteContext.BROWSER -> {
            VolumeRow(onVolumeUp = { onRemoteKey("volume_up") }, onVolumeDown = { onRemoteKey("volume_down") })
            BrowserContextRow(onBrowserControl = onBrowserControl, onRemoteKey = onRemoteKey)
        }
        RemoteContext.PLAYER -> MediaControlRow(onPlayerControl = onPlayerControl, showLoop = !dlnaMode, showSeek = !isLive)
        RemoteContext.IDLE -> { /* nothing to control */ }
    }
}

@Composable
private fun IdleEmptyState(
    tvName: String?,
    onShowControls: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            Icons.Default.Tv,
            contentDescription = null,
            modifier = Modifier.size(56.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.25f)
        )
        Spacer(modifier = Modifier.height(12.dp))
        Text("Nothing playing", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Text("on ${tvName ?: "your TV"}", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(modifier = Modifier.height(20.dp))
        FilledTonalButton(onClick = onShowControls) {
            Icon(Icons.Default.TouchApp, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(modifier = Modifier.width(8.dp))
            Text("Show touchpad & controls")
        }
    }
}

@Composable
private fun KeyboardArea(onRemoteKey: (String) -> Unit) {
    var text by remember { mutableStateOf("") }
    val focusRequester = remember { FocusRequester() }

    fun sendText(value: String) {
        val b64 = android.util.Base64.encodeToString(value.toByteArray(Charsets.UTF_8), android.util.Base64.NO_WRAP)
        onRemoteKey("text:$b64")
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .glassSurface(RoundedCornerShape(24.dp), alpha = 0.05f)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            "Tap a text box on the TV, then type here — it's sent as you type.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        OutlinedTextField(
            value = text,
            onValueChange = { text = it; sendText(it) },
            modifier = Modifier.fillMaxWidth().focusRequester(focusRequester),
            placeholder = { Text("Type to send to TV…") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
            keyboardActions = KeyboardActions(onGo = { onRemoteKey("key_enter") }),
            trailingIcon = {
                if (text.isNotEmpty()) {
                    IconButton(onClick = { text = ""; sendText("") }) {
                        Icon(Icons.Default.Clear, contentDescription = "Clear")
                    }
                }
            }
        )
        Button(onClick = { onRemoteKey("key_enter") }, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Default.Done, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text("Enter")
        }
    }

    LaunchedEffect(Unit) { focusRequester.requestFocus() }
}

@Composable
private fun VolumeRow(onVolumeUp: () -> Unit, onVolumeDown: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .glassSurface(RoundedCornerShape(16.dp), alpha = 0.05f)
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(onClick = onVolumeDown) { Icon(Icons.Default.Remove, contentDescription = "Volume down") }
        Icon(Icons.AutoMirrored.Filled.VolumeUp, contentDescription = null, modifier = Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        IconButton(onClick = onVolumeUp) { Icon(Icons.Default.Add, contentDescription = "Volume up") }
    }
}

private enum class DragAxis { HORIZONTAL, VERTICAL }

@Composable
private fun SeekVolumeBar(
    positionMs: Long,
    durationMs: Long,
    isLive: Boolean,
    isPlaying: Boolean,
    enableVolume: Boolean,
    onSeekTo: (Long) -> Unit,
    onVolumeUp: () -> Unit,
    onVolumeDown: () -> Unit,
    onPlayPauseToggle: () -> Unit,
) {
    val hasDuration = durationMs > 0L && !isLive
    val currentPosition by rememberUpdatedState(positionMs)

    var widthPx by remember { mutableFloatStateOf(0f) }
    var dragging by remember { mutableStateOf(false) }
    var dragMs by remember { mutableFloatStateOf(0f) }
    var activeAxis by remember { mutableStateOf<DragAxis?>(null) }
    var volumeDirection by remember { mutableStateOf<String?>(null) }
    var touchDownTime by remember { mutableLongStateOf(0L) }
    var isAggressive by remember { mutableStateOf(false) }

    val volumeStepPx = with(LocalDensity.current) { 28.dp.toPx() }
    val seekStepPx = with(LocalDensity.current) { 16.dp.toPx() }
    // The wave canvas is inset by the arrow gutters (52dp each side); absolute
    // finger-follow scrubbing maps the pointer x within that span.
    val contentInsetPx = with(LocalDensity.current) { 52.dp.toPx() }

    val view = LocalView.current
    val tick: () -> Unit = { view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK) }
    val thud: () -> Unit = { view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS) }

    val phase by rememberInfiniteTransition(label = "sineWavePhase").animateFloat(
        initialValue = 0f,
        targetValue = (2 * Math.PI).toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = if (isPlaying) 1500 else 3000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "phase"
    )

    val targetAmplitude = if (isPlaying && !dragging) 7.dp else 0.dp
    val amplitude by animateDpAsState(targetValue = targetAmplitude, animationSpec = tween(durationMillis = 300), label = "amplitude")

    val lift by animateDpAsState(targetValue = if (activeAxis != null) 12.dp else 0.dp, animationSpec = tween(durationMillis = 160), label = "seekBarLift")

    val displayMs = if (dragging && hasDuration) dragMs else currentPosition.toFloat()
    val fraction = if (hasDuration && durationMs > 0L) (displayMs / durationMs.toFloat()).coerceIn(0f, 1f) else 0f

    val primary = MaterialTheme.colorScheme.primary
    // Red "fast" tint only for hold-then-drag volume; seeking is absolute and has no fast mode.
    val activeColor by animateColorAsState(targetValue = if (isAggressive && activeAxis == DragAxis.VERTICAL) Color(0xFFFF5252) else primary, animationSpec = tween(200), label = "activeColor")
    val thumbScale by animateFloatAsState(targetValue = if (dragging) 1.5f else 1.0f, animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy), label = "thumbScale")

    val popupOffset = with(LocalDensity.current) { IntOffset(0, -180.dp.roundToPx()) }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .offset(y = -lift)
            .shadow(elevation = lift, shape = RoundedCornerShape(28.dp), clip = false)
            .height(86.dp)
            .glassSurface(RoundedCornerShape(28.dp), alpha = 0.05f)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {
                if (!dragging && activeAxis == null) onPlayPauseToggle()
            }
            .onSizeChanged { widthPx = it.width.toFloat() }
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    touchDownTime = System.currentTimeMillis()
                }
            }
            .pointerInput(hasDuration, enableVolume, durationMs) {
                var axis: DragAxis? = null
                var volAccum = 0f
                var seekAccum = 0f
                detectDragGestures(
                    onDragStart = {
                        // Hold-then-drag: horizontal becomes absolute finger-follow
                        // scrubbing, vertical becomes fast volume. A quick swipe keeps
                        // the gentle relative seek.
                        val dragStartTime = System.currentTimeMillis()
                        isAggressive = (dragStartTime - touchDownTime) >= 400L
                        if (isAggressive) thud()
                        axis = null; volAccum = 0f; seekAccum = 0f
                        dragMs = currentPosition.toFloat(); volumeDirection = null
                    },
                    onDrag = { change, drag ->
                        change.consume()
                        if (axis == null) {
                            axis = if (abs(drag.x) >= abs(drag.y)) DragAxis.HORIZONTAL else DragAxis.VERTICAL
                            activeAxis = axis
                            if (axis == DragAxis.HORIZONTAL && hasDuration) dragging = true
                            tick()
                        }
                        when (axis) {
                            DragAxis.HORIZONTAL -> if (hasDuration && widthPx > 0f) {
                                if (isAggressive) {
                                    // Scrub: map the finger's x directly onto the bar span
                                    // so the playhead tracks the finger 1:1.
                                    val span = (widthPx - 2 * contentInsetPx).coerceAtLeast(1f)
                                    val target = ((change.position.x - contentInsetPx) / span).coerceIn(0f, 1f)
                                    dragMs = target * durationMs.toFloat()
                                } else {
                                    // Relative swipe-to-seek: full width ≈ a tenth of the
                                    // runtime (clamped 2–10 min) for fine adjustments.
                                    val rangeMs = (durationMs / 10L).coerceIn(120_000L, 600_000L).toFloat()
                                    val deltaMs = (drag.x / widthPx) * rangeMs
                                    dragMs = (dragMs + deltaMs).coerceIn(0f, durationMs.toFloat())
                                }
                                seekAccum += abs(drag.x)
                                while (seekAccum >= seekStepPx) { tick(); seekAccum -= seekStepPx }
                            }
                            DragAxis.VERTICAL -> if (enableVolume) {
                                if (volumeDirection == null) volumeDirection = if (drag.y < 0) "up" else "down"
                                volAccum -= drag.y
                                val stepPx = if (isAggressive) volumeStepPx / 2.5f else volumeStepPx
                                while (volAccum >= stepPx) { onVolumeUp(); tick(); volAccum -= stepPx; volumeDirection = "up" }
                                while (volAccum <= -stepPx) { onVolumeDown(); tick(); volAccum += stepPx; volumeDirection = "down" }
                            }
                            else -> {}
                        }
                    },
                    onDragEnd = {
                        if (axis == DragAxis.HORIZONTAL && hasDuration) onSeekTo(dragMs.toLong())
                        dragging = false; activeAxis = null; axis = null; volumeDirection = null; isAggressive = false
                    },
                    onDragCancel = {
                        dragging = false; activeAxis = null; axis = null; volumeDirection = null; isAggressive = false
                    }
                )
            },
        contentAlignment = Alignment.CenterStart
    ) {
        Box(
            modifier = Modifier.fillMaxWidth().height(1.dp)
                .background(Brush.horizontalGradient(listOf(Color.Transparent, Color.White.copy(alpha = if (activeAxis == DragAxis.HORIZONTAL) 0.5f else 0.1f), Color.Transparent)))
                .align(Alignment.Center)
        )
        if (enableVolume) {
            Box(
                modifier = Modifier.fillMaxHeight().width(1.dp)
                    .background(Brush.verticalGradient(listOf(Color.Transparent, Color.White.copy(alpha = if (activeAxis == DragAxis.VERTICAL) 0.5f else 0.1f), Color.Transparent)))
                    .align(Alignment.Center)
            )
        }

        Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = null, tint = if (activeAxis == DragAxis.HORIZONTAL) activeColor else Color.White.copy(alpha = 0.3f), modifier = Modifier.align(Alignment.CenterStart).padding(start = 12.dp).size(24.dp))
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = if (activeAxis == DragAxis.HORIZONTAL) activeColor else Color.White.copy(alpha = 0.3f), modifier = Modifier.align(Alignment.CenterEnd).padding(end = 12.dp).size(24.dp))
        
        if (enableVolume) {
            Icon(Icons.Filled.KeyboardArrowUp, contentDescription = null, tint = if (activeAxis == DragAxis.VERTICAL) activeColor else Color.White.copy(alpha = 0.3f), modifier = Modifier.align(Alignment.TopCenter).padding(top = 4.dp).size(16.dp))
            Icon(Icons.Filled.KeyboardArrowDown, contentDescription = null, tint = if (activeAxis == DragAxis.VERTICAL) activeColor else Color.White.copy(alpha = 0.3f), modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 4.dp).size(16.dp))
        }

        Column(
            modifier = Modifier.fillMaxSize().padding(horizontal = 52.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.SpaceBetween,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                val seekLabel = if (activeAxis == DragAxis.HORIZONTAL) {
                    if (isAggressive) "SCRUBBING ◄►" else "SEEKING ◄►"
                } else {
                    if (isPlaying) "◄► SEEK · TAP ❙❙" else "◄► SEEK · TAP ▶"
                }
                Text(seekLabel, style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp), color = if (activeAxis == DragAxis.HORIZONTAL) activeColor else Color.White.copy(alpha = 0.5f), maxLines = 1)
                
                if (enableVolume) {
                    Text("  ·  ", style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp), color = Color.White.copy(alpha = 0.2f))
                    val volumeLabel = if (activeAxis == DragAxis.VERTICAL) {
                        if (isAggressive) "FAST VOL ▲▼" else "VOLUME ▲▼"
                    } else "▲▼ VOLUME"
                    Text(volumeLabel, style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp), color = if (activeAxis == DragAxis.VERTICAL) activeColor else Color.White.copy(alpha = 0.5f), maxLines = 1)
                }
            }

            if (hasDuration) {
                val ampPx = with(LocalDensity.current) { amplitude.toPx() }
                val wavelength = with(LocalDensity.current) { 50.dp.toPx() }
                val tickHeight = with(LocalDensity.current) { 4.dp.toPx() }
                val strokeActiveWidth = with(LocalDensity.current) { 4.dp.toPx() }
                val strokeInactiveWidth = with(LocalDensity.current) { 2.dp.toPx() }

                // Slow "breathing" of the wave height so the motion feels organic rather
                // than a fixed-height ripple; layered with the phase scroll below.
                val breath by rememberInfiniteTransition(label = "waveBreath").animateFloat(
                    initialValue = 0.8f,
                    targetValue = 1.2f,
                    animationSpec = infiniteRepeatable(
                        animation = tween(durationMillis = 2600, easing = FastOutSlowInEasing),
                        repeatMode = RepeatMode.Reverse
                    ),
                    label = "breath"
                )

                Canvas(modifier = Modifier.fillMaxWidth().height(24.dp)) {
                    val width = size.width
                    val height = size.height
                    val centerY = height / 2f
                    val amp = ampPx * breath
                    val activeWidth = width * fraction

                    val tickCount = 30
                    val tickSpacing = width / tickCount
                    for (i in 0..tickCount) {
                        val x = i * tickSpacing
                        drawLine(color = Color.White.copy(alpha = 0.1f), start = Offset(x, centerY - tickHeight), end = Offset(x, centerY + tickHeight), strokeWidth = 1f)
                    }

                    fun waveY(x: Float, p: Float, a: Float, wl: Float): Float {
                        val safeX = x.coerceIn(0f, width)
                        val envelope = if (width > 0f) sin(Math.PI * (safeX / width)).toFloat() else 0f
                        val angle = (2 * Math.PI * x / wl).toFloat() + p
                        return centerY + a * envelope * sin(angle)
                    }

                    fun buildWave(fromX: Float, toX: Float, p: Float, a: Float, wl: Float) = Path().apply {
                        moveTo(fromX, waveY(fromX, p, a, wl))
                        var x = fromX + 1f
                        while (x <= toX) { lineTo(x, waveY(x, p, a, wl)); x += 2f }
                    }

                    // Unplayed side: nearly flat — the energy lives behind the playhead.
                    if (activeWidth < width) {
                        drawPath(
                            path = buildWave(activeWidth, width, phase, amp * 0.15f, wavelength),
                            color = Color.White.copy(alpha = 0.15f),
                            style = Stroke(width = strokeInactiveWidth, cap = StrokeCap.Round)
                        )
                    }

                    if (activeWidth > 0f) {
                        val activeBrush = Brush.horizontalGradient(
                            colors = listOf(activeColor.copy(alpha = 0.8f), Color.White),
                            startX = 0f, endX = activeWidth
                        )
                        val mainWave = buildWave(0f, activeWidth, phase, amp, wavelength)

                        // Soft glow under the main wave.
                        drawPath(
                            path = mainWave,
                            color = activeColor.copy(alpha = 0.25f),
                            style = Stroke(width = strokeActiveWidth * 2.8f, cap = StrokeCap.Round)
                        )
                        // Faint counter-scrolling harmonic behind the main wave — the two
                        // layers interfere visually and read as liquid. Phase multiplier
                        // must be an integer so the infinite loop's 2π restart is seamless.
                        drawPath(
                            path = buildWave(0f, activeWidth, -phase * 2f, amp * 0.45f, wavelength * 0.55f),
                            color = activeColor.copy(alpha = 0.35f),
                            style = Stroke(width = strokeInactiveWidth, cap = StrokeCap.Round)
                        )
                        drawPath(path = mainWave, brush = activeBrush, style = Stroke(width = strokeActiveWidth, cap = StrokeCap.Round))

                        if (fraction > 0f && fraction < 1f) {
                            val thumbX = activeWidth
                            val barWidth = 3.dp.toPx() * thumbScale
                            val barHeight = 18.dp.toPx() * thumbScale
                            // Halo behind the playhead (grows with the drag thumb scale).
                            drawCircle(
                                color = activeColor.copy(alpha = 0.30f),
                                radius = 9.dp.toPx() * thumbScale,
                                center = Offset(thumbX, centerY)
                            )
                            drawLine(
                                color = Color.White,
                                start = Offset(thumbX, centerY - barHeight / 2),
                                end = Offset(thumbX, centerY + barHeight / 2),
                                strokeWidth = barWidth,
                                cap = StrokeCap.Round
                            )
                        }
                    }
                }
            } else {
                Box(modifier = Modifier.fillMaxWidth().height(2.dp).background(Color.White.copy(alpha = 0.2f)))
            }

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(if (hasDuration) formatTime(displayMs.toLong()) else formatTime(currentPosition), style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp), color = Color.White.copy(alpha = 0.7f), maxLines = 1, overflow = TextOverflow.Visible)
                Text(if (isLive) "● LIVE" else if (durationMs > 0L) formatTime(durationMs) else "--:--", style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp, fontWeight = if (isLive) FontWeight.Bold else FontWeight.Normal), color = if (isLive) activeColor else Color.White.copy(alpha = 0.7f), maxLines = 1, overflow = TextOverflow.Visible)
            }
        }
    }

    if (dragging && activeAxis == DragAxis.HORIZONTAL && hasDuration) {
        val deltaMs = dragMs.toLong() - currentPosition
        Popup(alignment = Alignment.Center, offset = popupOffset, properties = PopupProperties(focusable = false, dismissOnBackPress = false, dismissOnClickOutside = false)) {
            Surface(color = Color.Black.copy(alpha = 0.85f), shape = RoundedCornerShape(20.dp), border = BorderStroke(1.dp, activeColor.copy(alpha = 0.5f))) {
                Column(modifier = Modifier.padding(horizontal = 28.dp, vertical = 18.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(if (isAggressive) "Scrub" else "Seek", color = activeColor, style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold, letterSpacing = 1.sp))
                    Spacer(modifier = Modifier.height(6.dp))
                    Text("${formatTime(dragMs.toLong())} / ${formatTime(durationMs)}", color = Color.White, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    val sign = if (deltaMs >= 0) "+" else "-"
                    Text("$sign${formatTime(abs(deltaMs))}", color = Color.White.copy(alpha = 0.8f), style = MaterialTheme.typography.labelMedium)
                }
            }
        }
    }

    if (activeAxis == DragAxis.VERTICAL && enableVolume) {
        Popup(alignment = Alignment.Center, offset = popupOffset, properties = PopupProperties(focusable = false, dismissOnBackPress = false, dismissOnClickOutside = false)) {
            Surface(color = Color.Black.copy(alpha = 0.85f), shape = RoundedCornerShape(20.dp), border = BorderStroke(1.dp, activeColor.copy(alpha = 0.5f))) {
                Column(modifier = Modifier.padding(horizontal = 28.dp, vertical = 22.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    val volumeIcon = when (volumeDirection) { "down" -> Icons.AutoMirrored.Filled.VolumeDown else -> Icons.AutoMirrored.Filled.VolumeUp }
                    Icon(volumeIcon, contentDescription = null, tint = activeColor, modifier = Modifier.size(32.dp))
                    val volumeLabel = when (volumeDirection) {
                        "up" -> if (isAggressive) "Volume Up (Fast)" else "Volume Up"
                        "down" -> if (isAggressive) "Volume Down (Fast)" else "Volume Down"
                        else -> if (isAggressive) "Volume (Fast)" else "Volume"
                    }
                    Text(volumeLabel, color = Color.White, style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold, letterSpacing = 1.sp))
                }
            }
        }
    }
}

private enum class TwoFingerMode { UNDECIDED, SCROLL, ZOOM }

@Composable
private fun TouchpadArea(
    onMouseMove: (dx: Float, dy: Float) -> Unit,
    onMouseClick: () -> Unit,
    onMouseScroll: (dx: Float, dy: Float) -> Unit,
    onPinchZoom: (factor: Float) -> Unit = {},
    onMouseDown: () -> Unit = {},
    onMouseUp: () -> Unit = {}
) {
    var isScrolling by remember { mutableStateOf(false) }
    var isDragging by remember { mutableStateOf(false) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .glassSurface(RoundedCornerShape(24.dp), alpha = 0.05f)
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    // Two-finger gesture is locked to one mode for its whole lifetime so a
                    // scroll never flips into a zoom (or vice-versa) mid-drag. We accumulate
                    // evidence until one axis clears the slop, then commit.
                    var twoFingerMode = TwoFingerMode.UNDECIDED
                    var accumZoom = 0f
                    var accumPan = 0f
                    val gestureSlop = 24f

                    var downTime = 0L
                    var downPos = androidx.compose.ui.geometry.Offset.Zero
                    var maxMoveDist = 0f
                    val clickSlop = 15f // pixels
                    val clickTimeout = 300L // ms

                    while (true) {
                        val event = awaitPointerEvent()
                        val pointerCount = event.changes.count { it.pressed }

                        if (pointerCount >= 2) {
                            isScrolling = true
                            val pressed = event.changes.filter { it.pressed }
                            val a = pressed.getOrNull(0)
                            val b = pressed.getOrNull(1)
                            if (a != null && b != null && a.previousPressed && b.previousPressed) {
                                val curDist = (a.position - b.position).getDistance()
                                val prevDist = (a.previousPosition - b.previousPosition).getDistance()
                                val panX = ((a.position.x + b.position.x) - (a.previousPosition.x + b.previousPosition.x)) / 2f
                                val panY = ((a.position.y + b.position.y) - (a.previousPosition.y + b.previousPosition.y)) / 2f
                                val distDelta = curDist - prevDist

                                if (twoFingerMode == TwoFingerMode.UNDECIDED) {
                                    accumZoom += abs(distDelta)
                                    accumPan += abs(panX) + abs(panY)
                                    if (accumZoom > gestureSlop || accumPan > gestureSlop) {
                                        twoFingerMode = if (accumZoom > accumPan) {
                                            TwoFingerMode.ZOOM
                                        } else {
                                            TwoFingerMode.SCROLL
                                        }
                                    }
                                }

                                when (twoFingerMode) {
                                    TwoFingerMode.ZOOM ->
                                        if (prevDist > 0f) onPinchZoom(curDist / prevDist)
                                    TwoFingerMode.SCROLL ->
                                        onMouseScroll(panX, panY * 2f)
                                    TwoFingerMode.UNDECIDED -> { /* still gathering slop */ }
                                }
                                a.consume(); b.consume()
                            }
                        } else if (pointerCount == 1 && !isScrolling) {
                            val change = event.changes.first()
                            if (change.pressed && !change.previousPressed) {
                                downTime = System.currentTimeMillis()
                                downPos = change.position
                                maxMoveDist = 0f
                            }
                            if (change.pressed && change.previousPressed) {
                                val delta = change.position - change.previousPosition
                                val totalDist = (change.position - downPos).getDistance()
                                if (totalDist > maxMoveDist) {
                                    maxMoveDist = totalDist
                                }
                                onMouseMove(delta.x * 1.5f, delta.y * 1.5f)
                                change.consume()
                            }
                        } else if (pointerCount == 0) {
                            val upTime = System.currentTimeMillis()
                            val duration = upTime - downTime
                            if (!isScrolling && duration < clickTimeout && maxMoveDist < clickSlop && downTime > 0L) {
                                onMouseClick()
                            }

                            isScrolling = false
                            twoFingerMode = TwoFingerMode.UNDECIDED
                            accumZoom = 0f
                            accumPan = 0f
                            downTime = 0L
                        }
                    }
                }
            }
            .pointerInput(Unit) {
                detectDragGesturesAfterLongPress(
                    onDragStart = { isDragging = true; onMouseDown() },
                    onDrag = { change, dragAmount -> change.consume(); onMouseMove(dragAmount.x * 1.5f, dragAmount.y * 1.5f) },
                    onDragEnd = { if (isDragging) { isDragging = false; onMouseUp() } },
                    onDragCancel = { if (isDragging) { isDragging = false; onMouseUp() } }
                )
            },
        contentAlignment = Alignment.Center
    ) {
        // High-tech grid overlay for spatial awareness
        Canvas(modifier = Modifier.fillMaxSize()) {
            val gridColor = Color.White.copy(alpha = 0.05f)
            val cols = 3
            val rows = 3
            val colWidth = size.width / cols
            val rowHeight = size.height / rows
            
            for (i in 1 until cols) {
                drawLine(gridColor, Offset(i * colWidth, 0f), Offset(i * colWidth, size.height), strokeWidth = 1f)
            }
            for (i in 1 until rows) {
                drawLine(gridColor, Offset(0f, i * rowHeight), Offset(size.width, i * rowHeight), strokeWidth = 1f)
            }
        }
        
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Icon(Icons.Default.TouchApp, contentDescription = null, modifier = Modifier.size(48.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.15f))
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                "1 finger: move  •  2 fingers: scroll  •  Pinch: zoom  •  Tap: click  •  Long-press+drag: drag",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f),
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 24.dp)
            )
        }
    }
}

@Composable
private fun DpadArea(onRemoteKey: (String) -> Unit) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            DpadBtn(Icons.Default.KeyboardArrowUp, "Up") { onRemoteKey("dpad_up") }

            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                DpadBtn(Icons.AutoMirrored.Filled.KeyboardArrowLeft, "Left") { onRemoteKey("dpad_left") }

                FilledTonalButton(
                    onClick = { onRemoteKey("dpad_center") },
                    modifier = Modifier.size(80.dp).shadow(8.dp, CircleShape),
                    shape = CircleShape,
                    colors = ButtonDefaults.filledTonalButtonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary
                    )
                ) {
                    Text("OK", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                }

                DpadBtn(Icons.AutoMirrored.Filled.KeyboardArrowRight, "Right") { onRemoteKey("dpad_right") }
            }

            DpadBtn(Icons.Default.KeyboardArrowDown, "Down") { onRemoteKey("dpad_down") }
        }
    }
}

@Composable
private fun DpadBtn(icon: ImageVector, desc: String, onClick: () -> Unit) {
    FilledTonalButton(
        onClick = onClick,
        modifier = Modifier.size(64.dp).shadow(4.dp, CircleShape),
        shape = CircleShape,
        contentPadding = PaddingValues(0.dp)
    ) {
        Icon(icon, contentDescription = desc, modifier = Modifier.size(32.dp))
    }
}

@Composable
private fun MediaControlRow(
    onPlayerControl: (String) -> Unit,
    showLoop: Boolean = true,
    showSeek: Boolean = true,
) {
    var isLooping by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .glassSurface(RoundedCornerShape(16.dp), alpha = 0.05f)
            .padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (showSeek) {
            LabeledIconButton(icon = Icons.Default.Replay10, label = "-10s", tint = MaterialTheme.colorScheme.onSurface, onClick = { onPlayerControl("seek_back") })
        }
        if (showSeek) {
            LabeledIconButton(icon = Icons.Default.Forward10, label = "+10s", tint = MaterialTheme.colorScheme.onSurface, onClick = { onPlayerControl("seek_forward") })
        }
        if (showLoop) {
            LabeledIconButton(icon = Icons.Default.Repeat, label = "Loop", tint = if (isLooping) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface, onClick = {
                val next = !isLooping
                onPlayerControl(if (next) "loop_on" else "loop_off")
                isLooping = next
            })
        }
        LabeledIconButton(icon = Icons.Default.Stop, label = "Stop", tint = MaterialTheme.colorScheme.error, onClick = { onPlayerControl("stop") })
    }
}

@Composable
private fun BrowserContextRow(onBrowserControl: (String) -> Unit, onRemoteKey: (String) -> Unit) {
    var isVideoMaximized by remember { mutableStateOf(false) }
    var showMoreSheet by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .glassSurface(RoundedCornerShape(16.dp), alpha = 0.05f)
            .padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically
    ) {
        LabeledIconButton(icon = Icons.AutoMirrored.Filled.ArrowBack, label = "Back", tint = MaterialTheme.colorScheme.onSurface, onClick = { onRemoteKey("back") })
        LabeledIconButton(icon = Icons.AutoMirrored.Filled.ArrowForward, label = "Forward", tint = MaterialTheme.colorScheme.onSurfaceVariant, onClick = { onBrowserControl("forward") })
        LabeledIconButton(icon = Icons.Default.Refresh, label = "Refresh", tint = MaterialTheme.colorScheme.onSurfaceVariant, onClick = { onBrowserControl("refresh") })
        LabeledIconButton(icon = if (isVideoMaximized) Icons.Default.FullscreenExit else Icons.Default.Fullscreen, label = if (isVideoMaximized) "Restore" else "Fullscreen", tint = MaterialTheme.colorScheme.onSurfaceVariant, onClick = {
            onBrowserControl(if (isVideoMaximized) "restore_video" else "maximize_video")
            isVideoMaximized = !isVideoMaximized
        })
        LabeledIconButton(icon = Icons.Default.Home, label = "Home", tint = MaterialTheme.colorScheme.onSurface, onClick = { onRemoteKey("home") })
        LabeledIconButton(icon = Icons.Default.MoreHoriz, label = "More", tint = MaterialTheme.colorScheme.onSurfaceVariant, onClick = { showMoreSheet = true })
    }

    if (showMoreSheet) {
        BrowserMoreSheet(onBrowserControl = onBrowserControl, onDismiss = { showMoreSheet = false })
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BrowserMoreSheet(onBrowserControl: (String) -> Unit, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Text("More controls", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(start = 24.dp, bottom = 4.dp))
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 16.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            LabeledIconButton(icon = Icons.Default.Shield, label = "Ad Block", tint = MaterialTheme.colorScheme.onSurfaceVariant, onClick = { onBrowserControl("toggle_ublock"); onDismiss() })
            LabeledIconButton(icon = Icons.Default.Layers, label = "Source", tint = MaterialTheme.colorScheme.onSurfaceVariant, onClick = { onBrowserControl("video_target_cycle"); onDismiss() })
            LabeledIconButton(icon = Icons.AutoMirrored.Filled.VolumeUp, label = "Unmute", tint = MaterialTheme.colorScheme.onSurfaceVariant, onClick = { onBrowserControl("video_unmute"); onDismiss() })
            LabeledIconButton(icon = Icons.Default.Code, label = "Scripts", tint = MaterialTheme.colorScheme.onSurfaceVariant, onClick = { onBrowserControl("manage_user_scripts"); onDismiss() })
            LabeledIconButton(icon = Icons.Default.Language, label = "User Agent", tint = MaterialTheme.colorScheme.onSurfaceVariant, onClick = { onBrowserControl("manage_user_agent"); onDismiss() })
        }
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun LabeledIconButton(icon: ImageVector, label: String, tint: Color, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(horizontal = 4.dp)) {
        IconButton(onClick = onClick) { Icon(icon, contentDescription = label, tint = tint) }
        Text(label, style = MaterialTheme.typography.labelSmall, color = tint.copy(alpha = 0.7f), fontSize = 10.sp)
    }
}

@Composable
private fun NowPlayingPanel(title: String?, episodeLabel: String? = null) {
    Column(modifier = Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = title ?: "Playing on TV",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            maxLines = 2,
            textAlign = TextAlign.Center
        )
        if (episodeLabel != null) {
            Text(text = episodeLabel, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun EpisodesCarousel(
    episodes: List<PlaylistEpisode>,
    currentIndex: Int,
    onJumpToEpisode: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    if (episodes.size <= 1) {
        Spacer(modifier = modifier)
        return
    }

    val scrollState = rememberScrollState()
    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            text = "Up Next",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(vertical = 8.dp)
        )
        LazyRow(
            modifier = Modifier.fillMaxSize(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(vertical = 4.dp)
        ) {
            items(episodes) { ep ->
                val isCurrent = ep.index == currentIndex
                Column(
                    modifier = Modifier
                        .width(140.dp)
                        .height(80.dp)
                        .glassSurface(RoundedCornerShape(16.dp), alpha = if (isCurrent) 0.2f else 0.05f)
                        .border(
                            width = if (isCurrent) 1.dp else 0.dp,
                            color = if (isCurrent) MaterialTheme.colorScheme.primary else Color.Transparent,
                            shape = RoundedCornerShape(16.dp)
                        )
                        .clickable { onJumpToEpisode(ep.index) }
                        .padding(12.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    if (isCurrent) {
                        Icon(Icons.Default.PlayArrow, contentDescription = "Now playing", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                    } else {
                        Text(text = "${ep.index + 1}", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, fontWeight = FontWeight.Bold)
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = shortEpisodeLabel(ep.title),
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = if (isCurrent) FontWeight.SemiBold else FontWeight.Normal,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.Center
                    )
                }
            }
        }
    }
}

@Composable
private fun TrackChipsRow(
    audioTracks: List<MediaTrack>,
    subtitleTracks: List<MediaTrack>,
    onSelectAudio: (String) -> Unit,
    onSubtitlesClick: () -> Unit,
    onMore: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (audioTracks.isNotEmpty()) {
            TrackChip(icon = Icons.Default.VolumeUp, label = "Audio", tracks = audioTracks, onSelect = onSelectAudio, modifier = Modifier.weight(1f))
        }
        if (subtitleTracks.isNotEmpty()) {
            val selectedName = subtitleTracks.firstOrNull { it.selected }?.name ?: "—"
            Surface(
                onClick = onSubtitlesClick,
                shape = RoundedCornerShape(20.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                modifier = Modifier.weight(1f).height(40.dp)
            ) {
                Row(modifier = Modifier.padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Icon(Icons.Default.Subtitles, contentDescription = "Subtitles", modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(text = "Subs: $selectedName", style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f))
                    Icon(Icons.Default.ArrowDropDown, contentDescription = null, modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        if (audioTracks.isEmpty() && subtitleTracks.isEmpty()) {
            Spacer(modifier = Modifier.weight(1f))
        }
        Surface(
            onClick = onMore,
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
            modifier = Modifier.height(40.dp)
        ) {
            Row(modifier = Modifier.padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Icon(Icons.Default.Tune, contentDescription = "More", modifier = Modifier.size(18.dp))
                Text("More", style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}

@Composable
private fun TrackChip(
    icon: ImageVector,
    label: String,
    tracks: List<MediaTrack>,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    var expanded by remember { mutableStateOf(false) }
    val selectedName = tracks.firstOrNull { it.selected }?.name ?: "—"

    Box(modifier = modifier) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(20.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f))
                .clickable { expanded = true }
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Icon(icon, contentDescription = label, modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(text = "$label: $selectedName", style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f))
            Icon(Icons.Default.ArrowDropDown, contentDescription = null, modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            tracks.forEach { track ->
                DropdownMenuItem(
                    text = { Text(track.name) },
                    onClick = { onSelect(track.id); expanded = false },
                    leadingIcon = if (track.selected) { { Icon(Icons.Default.Check, contentDescription = "Selected") } } else null
                )
            }
        }
    }
}

private val EPISODE_MARKER = Regex("""S\d+\s*E\d+.*""", RegexOption.IGNORE_CASE)
private fun shortEpisodeLabel(title: String): String = EPISODE_MARKER.find(title)?.value ?: title

private fun formatTime(ms: Long): String {
    if (ms <= 0L) return "0:00"
    val totalSec = ms / 1000
    val h = totalSec / 3600
    val m = (totalSec % 3600) / 60
    val s = totalSec % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PlayerSettingsSheet(
    settings: TvPlayerSettings,
    videoTracks: List<MediaTrack>,
    onSetVideoQuality: (Int) -> Unit,
    onSetSpeed: (Float) -> Unit,
    onSetScaling: (String) -> Unit,
    onToggleAudioBoost: () -> Unit,
    onAdjustSubtitleOffset: (Long) -> Unit,
    onSwitchEngine: (String) -> Unit,
    onAddSubtitle: () -> Unit,
    onDismiss: () -> Unit
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            Text("Player settings", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)

            if (settings.qualityAvailable && videoTracks.count { it.id != "auto" } > 1) {
                SettingRow("Quality") {
                    ChipGroup(
                        options = videoTracks.map { track -> track.name to track.id },
                        selectedKey = if (settings.qualityMaxHeight == 0) "auto" else "max:${settings.qualityMaxHeight}",
                        onSelect = { id -> onSetVideoQuality(id.removePrefix("max:").toIntOrNull() ?: 0) },
                    )
                }
            }

            if (settings.speedAvailable) SettingRow("Speed") {
                ChipGroup(
                    options = listOf(0.25f, 0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 1.75f, 2.0f).map { (if (it == 1.0f) "1x" else "${it}x") to it.toString() },
                    selectedKey = settings.speed.toString(),
                    onSelect = { it.toFloatOrNull()?.let(onSetSpeed) }
                )
            }

            if (settings.scalingAvailable) SettingRow("Scaling") {
                ChipGroup(options = listOf("Fit" to "Fit", "Crop to fill" to "Zoom", "Stretch" to "Fill"), selectedKey = settings.scaling, onSelect = onSetScaling)
            }

            SettingRow("Subtitle offset") {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    FilledTonalButton(onClick = { onAdjustSubtitleOffset(-250L) }) { Text("−250ms") }
                    Text("${settings.subtitleOffsetMs} ms", style = MaterialTheme.typography.bodyMedium)
                    FilledTonalButton(onClick = { onAdjustSubtitleOffset(250L) }) { Text("+250ms") }
                }
            }

            if (settings.audioBoostAvailable) Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Audio boost", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                Switch(checked = settings.audioBoost, onCheckedChange = { onToggleAudioBoost() })
            }

            SettingRow("Player engine") {
                ChipGroup(options = listOf("ExoPlayer" to "exo", "MPV" to "mpv"), selectedKey = settings.engine, onSelect = onSwitchEngine)
            }

            FilledTonalButton(onClick = onAddSubtitle, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Default.Subtitles, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Add subtitle…")
            }
        }
    }
}

@Composable
private fun SettingRow(label: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        content()
    }
}

@Composable
private fun ChipGroup(options: List<Pair<String, String>>, selectedKey: String, onSelect: (String) -> Unit) {
    Row(modifier = Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { (label, key) ->
            FilterChip(selected = key == selectedKey, onClick = { onSelect(key) }, label = { Text(label) })
        }
    }
}

@Composable
private fun AddSubtitleDialog(
    onSearchSubtitles: (suspend () -> List<SubtitleOption>)?,
    onAddUrl: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var url by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<SubtitleOption>>(emptyList()) }
    var searching by remember { mutableStateOf(false) }
    var searched by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    val filePickerLauncher = rememberLauncherForActivityResult(contract = ActivityResultContracts.GetContent()) { uri: Uri? ->
        if (uri != null) {
            val fileName = runCatching {
                val cursor = context.contentResolver.query(uri, null, null, null, null)
                cursor?.use { c ->
                    val nameIndex = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                    if (nameIndex != -1 && c.moveToFirst()) c.getString(nameIndex) else null
                }
            }.getOrNull() ?: uri.lastPathSegment ?: "subtitle.srt"

            val extension = fileName.substringAfterLast('.', "").lowercase()
            val supportedExtensions = setOf("srt", "vtt", "ass", "ssa", "sub")
            if (extension !in supportedExtensions) {
                android.widget.Toast.makeText(context, "Only subtitle files (.srt, .vtt, .ass) are supported", android.widget.Toast.LENGTH_SHORT).show()
                return@rememberLauncherForActivityResult
            }

            val mime = when { fileName.endsWith(".vtt", ignoreCase = true) -> "text/vtt" else -> "application/x-subrip" }
            val proxyServer = com.playbridge.sender.cast.dlna.DlnaProxyHolder.proxy(context)
            val proxyUrl = proxyServer.publishLocal(uri, mime)
            val urlWithFragment = "$proxyUrl#${java.net.URLEncoder.encode(fileName, "UTF-8")}"
            onAddUrl(urlWithFragment)
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add subtitle") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = { filePickerLauncher.launch("*/*") }, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.filledTonalButtonColors()) {
                    Icon(imageVector = Icons.Default.UploadFile, contentDescription = "Upload local file", modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Choose Local Subtitle File")
                }
                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                OutlinedTextField(value = url, onValueChange = { url = it }, label = { Text("Subtitle URL (.srt / .vtt)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                if (onSearchSubtitles != null) {
                    FilledTonalButton(
                        onClick = {
                            searching = true
                            scope.launch {
                                results = try { onSearchSubtitles() } catch (e: Exception) { emptyList() }
                                searching = false
                                searched = true
                            }
                        },
                        enabled = !searching,
                        modifier = Modifier.fillMaxWidth()
                    ) { Text(if (searching) "Searching…" else "Search subtitles") }

                    if (searched && results.isEmpty() && !searching) {
                        Text("No subtitles found", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (results.isNotEmpty()) {
                        LazyColumn(modifier = Modifier.heightIn(max = 220.dp)) {
                            items(results) { opt ->
                                Text(opt.label, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.fillMaxWidth().clickable { onAddUrl(opt.url) }.padding(vertical = 10.dp))
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { if (url.isNotBlank()) onAddUrl(url.trim()) }, enabled = url.isNotBlank()) { Text("Add") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

private const val OFF_KEY = "__off__"
private const val EMBEDDED_KEY = "__embedded__"
private const val REMOTE_KEY = "__remote__"
private const val EXTERNAL_KEY = "__external__"

private data class SubGroup(val key: String, val label: String, val tracks: List<MediaTrack>, val hasSelected: Boolean)
private data class SubInfo(val langKey: String, val langDisplay: String, val optionLabel: String)

private val LANG_TOKENS: Map<String, String> = buildMap {
    fun add(display: String, vararg tokens: String) { tokens.forEach { put(it, display) } }
    add("English", "english", "en", "eng")
    add("Spanish", "spanish", "es", "spa", "esp")
    add("French", "french", "fr", "fre", "fra")
    add("German", "german", "de", "ger", "deu")
    add("Italian", "italian", "it", "ita")
    add("Japanese", "japanese", "ja", "jpn")
    add("Korean", "korean", "ko", "kor")
    add("Chinese", "chinese", "zh", "chi", "zho")
    add("Russian", "russian", "ru", "rus")
    add("Portuguese", "portuguese", "pt", "por")
    add("Portuguese (BR)", "portuguese (br)", "pob", "pt-br", "ptbr")
    add("Arabic", "arabic", "ar", "ara")
    add("Hindi", "hindi", "hi", "hin")
    add("Dutch", "dutch", "nl", "dut", "nld")
    add("Swedish", "swedish", "sv", "swe")
    add("Turkish", "turkish", "tr", "tur")
    add("Polish", "polish", "pl", "pol")
    add("Romanian", "romanian", "ro", "ron", "rum")
    add("Greek", "greek", "el", "ell", "gre")
    add("Czech", "czech", "cs", "cze", "ces")
    add("Danish", "danish", "da", "dan")
    add("Hungarian", "hungarian", "hu", "hun")
    add("Bulgarian", "bulgarian", "bg", "bul")
    add("Slovenian", "slovenian", "sl", "slv")
    add("Indonesian", "indonesian", "id", "ind")
    add("Hebrew", "hebrew", "he", "heb")
    add("Finnish", "finish", "fi", "fin")
    add("Serbian", "serbian", "sr", "srp")
    add("Croatian", "croatian", "hr", "hrv")
    add("Norwegian", "norwegian", "no", "nor")
    add("Ukrainian", "ukrainian", "uk", "ukr")
    add("Thai", "thai", "th", "tha")
    add("Vietnamese", "vietnamese", "vi", "vie")
    add("Persian", "persian", "farsi", "fa", "per", "fas")
}

private fun languageOf(segment: String): String? = LANG_TOKENS[segment.trim().lowercase()]

private fun classifySub(t: MediaTrack): SubInfo {
    if (t.id == "off" || t.id == "none") return SubInfo(OFF_KEY, "Off", "Off")
    val isExternal = t.type == "external_sub" || t.id.startsWith("external_") || t.id.contains("://")
    if (!isExternal) return SubInfo(EMBEDDED_KEY, "Embedded", t.name)

    if (!t.name.contains("OpenSubtitles #")) return SubInfo(REMOTE_KEY, "Phone Remote", t.name.ifBlank { "Remote Subtitle" })

    val segs = t.name.split(" · ", " • ").map { it.trim() }.filter { it.isNotEmpty() }
    val lang = segs.firstNotNullOfOrNull { languageOf(it) }
    return if (lang != null) {
        val rest = segs.filter { languageOf(it) == null }.joinToString(" • ")
        val opt = rest.ifBlank { "Add-on" }
        SubInfo(lang.lowercase(), lang, opt)
    } else {
        SubInfo(EXTERNAL_KEY, "External", t.name.ifBlank { "Subtitle" })
    }
}

private fun groupSubtitleTracks(tracks: List<MediaTrack>): List<SubGroup> {
    val off = tracks.firstOrNull { it.id == "off" || it.id == "none" }
    val byLang = LinkedHashMap<String, Pair<String, MutableList<MediaTrack>>>()
    
    tracks.filter { it.id != "off" && it.id != "none" }.forEach { t ->
        val info = classifySub(t)
        byLang.getOrPut(info.langKey) { info.langDisplay to mutableListOf() }.second.add(t)
    }
    
    val groups = mutableListOf<SubGroup>()
    if (off != null) groups.add(SubGroup(OFF_KEY, "Off", listOf(off), off.selected))
    byLang[EMBEDDED_KEY]?.let { (display, list) -> groups.add(SubGroup(EMBEDDED_KEY, display, list, list.any { it.selected })) }
    byLang[REMOTE_KEY]?.let { (display, list) -> groups.add(SubGroup(REMOTE_KEY, display, list, list.any { it.selected })) }
    byLang.filterKeys { it != EMBEDDED_KEY && it != REMOTE_KEY && it != EXTERNAL_KEY }.forEach { (k, v) -> groups.add(SubGroup(k, v.first, v.second, v.second.any { it.selected })) }
    byLang[EXTERNAL_KEY]?.let { (display, list) -> groups.add(SubGroup(EXTERNAL_KEY, display, list, list.any { it.selected })) }

    return groups
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SubtitlesBottomSheet(tracks: List<MediaTrack>, onSelect: (String) -> Unit, onDismiss: () -> Unit) {
    val groups = remember(tracks) { groupSubtitleTracks(tracks) }
    val initialGroupKey = remember(groups) {
        groups.firstOrNull { g -> g.tracks.any { it.selected } && g.key != OFF_KEY }?.key
            ?: groups.firstOrNull { it.key != OFF_KEY }?.key
            ?: groups.firstOrNull()?.key
    }
    var selectedGroupKey by remember(groups) { mutableStateOf(initialGroupKey) }
    val currentGroup = groups.firstOrNull { it.key == selectedGroupKey }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surface,
        dragHandle = { BottomSheetDefaults.DragHandle() }
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 24.dp).padding(bottom = 24.dp)
        ) {
            Text("Subtitles", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
            Spacer(modifier = Modifier.height(16.dp))

            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                groups.forEach { group ->
                    val isSelected = selectedGroupKey == group.key
                    FilterChip(
                        selected = isSelected,
                        onClick = { selectedGroupKey = group.key },
                        label = {
                            val countSuffix = if (group.key != OFF_KEY && group.tracks.isNotEmpty()) " (${group.tracks.size})" else ""
                            Text(group.label + countSuffix)
                        },
                        leadingIcon = if (group.hasSelected) { { Icon(Icons.Default.Check, "Active", Modifier.size(16.dp)) } } else null,
                        shape = RoundedCornerShape(20.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            if (currentGroup != null) {
                LazyColumn(
                    modifier = Modifier.fillMaxWidth().heightIn(max = 360.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(currentGroup.tracks, key = { it.id }) { track ->
                        val isSelected = track.selected
                        val displayLabel = if (currentGroup.key == OFF_KEY) "Off" else classifySub(track).optionLabel
                        Surface(
                            onClick = { onSelect(track.id); onDismiss() },
                            shape = RoundedCornerShape(12.dp),
                            color = if (isSelected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
                            border = if (isSelected) BorderStroke(1.dp, MaterialTheme.colorScheme.primary) else null,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(displayLabel, style = MaterialTheme.typography.bodyLarge, fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal, color = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface)
                                if (isSelected) {
                                    Icon(Icons.Default.Check, "Selected", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
