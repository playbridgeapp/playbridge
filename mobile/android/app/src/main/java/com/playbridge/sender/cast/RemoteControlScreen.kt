package com.playbridge.sender.cast

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.platform.LocalContext
import android.net.Uri
import com.playbridge.sender.library.*
import com.playbridge.sender.browser.*
import com.playbridge.sender.connection.WebSocketClient

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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import kotlinx.coroutines.launch
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
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
import kotlin.math.abs

/** Live playback status synced from the TV via `status` messages. */
data class TvPlaybackStatus(
    val state: String,       // playing | paused | buffering | ended
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
    val engine: String = ""
)

/** A subtitle search result the user can add to the TV. */
data class SubtitleOption(
    val label: String,
    val url: String
)

/**
 * Full-screen remote control.
 *
 * The body follows the TV's active context. **player** shows a now-playing surface:
 * title, a live seekbar (drag to seek), transport controls, and the TV's episode list
 * (tap to jump). **browser** shows the Touchpad/D-Pad hero + browser controls. **idle**
 * (e.g. just after Stop) shows a calm "nothing playing" state that can reveal the input
 * surface on demand. A persistent connected-TV tile anchors the top across all three.
 *
 * @param activeContext  TV's active context: "player", "browser", or "idle"
 * @param playbackState  TV playback state ("playing"/"paused"/…), null if unknown
 * @param positionMs      Current playback position from the TV
 * @param durationMs      Total duration from the TV (0 if unknown/live)
 * @param mediaTitle      Title of what's playing on the TV
 * @param episodes        The TV's current playlist (for episode selection)
 * @param currentEpisodeIndex Index of the episode currently playing
 * @param onSeekTo        Seek the TV to an absolute position (ms)
 * @param onJumpToEpisode Jump the TV playlist to the given index
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
    dlnaMode: Boolean = false,
    isLive: Boolean = false,
    positionMs: Long = 0L,
    durationMs: Long = 0L,
    mediaTitle: String? = null,
    episodes: List<PlaylistEpisode> = emptyList(),
    currentEpisodeIndex: Int = 0,
    audioTracks: List<MediaTrack> = emptyList(),
    subtitleTracks: List<MediaTrack> = emptyList(),
    playerSettings: TvPlayerSettings = TvPlayerSettings(),
    onSeekTo: (Long) -> Unit = {},
    onJumpToEpisode: (Int) -> Unit = {},
    onSelectAudio: (String) -> Unit = {},
    onSelectSubtitle: (String) -> Unit = {},
    onSetSpeed: (Float) -> Unit = {},
    onSetScaling: (String) -> Unit = {},
    onToggleAudioBoost: () -> Unit = {},
    onAdjustSubtitleOffset: (Long) -> Unit = {},
    onSwitchEngine: (String) -> Unit = {},
    onAddSubtitleUrl: (String) -> Unit = {},
    onSearchSubtitles: (suspend () -> List<SubtitleOption>)? = null,
    // Connected-device status pill: shows what you're controlling (and which protocol for DLNA).
    // It's status-only — switching/disconnecting lives in the cast picker (Library / cast sheet).
    tvName: String? = null,
    connectionState: WebSocketClient.ConnectionState = WebSocketClient.ConnectionState.Disconnected
) {
    var showSettingsSheet by remember { mutableStateOf(false) }
    var showAddSubtitle by remember { mutableStateOf(false) }
    var showSubtitlesSheet by remember { mutableStateOf(false) }
    val remoteContext = remoteContextOf(activeContext)
    // The interaction surface is chosen via the mode chip (Context / D-Pad / Touchpad /
    // Keyboard) and remembered separately per context: player defaults to Context (its
    // scrubber + controls), browser to Touchpad. Picking a mode sticks for that context.
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
    fun selectMode(mode: RemoteMode) { modeByContext = modeByContext + (remoteContext to mode) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Remote") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                },
                actions = {
                    RemoteModeChip(selected = selectedMode, onSelect = { selectMode(it) })
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
                .padding(horizontal = 20.dp)
                .padding(bottom = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Persistent connected-device status pill — the constant anchor across player/browser/
            // idle, so the controls below can crossfade without the screen feeling like it teleported.
            // Status-only: it states what you're controlling (and tags DLNA); switching/disconnecting
            // happens at the cast picker, not here (so native and DLNA read identically).
            ConnectedTvChip(
                deviceName = tvName,
                connectionState = connectionState,
                isDlna = dlnaMode
            )
            Spacer(modifier = Modifier.height(12.dp))

            AnimatedContent(
                targetState = selectedMode to remoteContext,
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
                        // Adaptive view: player controls, browser now-playing, or idle.
                        RemoteMode.CONTEXT -> when (ctx) {
                            RemoteContext.PLAYER -> {
                                // ── Now Playing: title only — the seek control lives down in the
                                //    SeekVolumeBar (swipe ◄► to seek, ▲▼ for volume). ──
                                NowPlayingPanel(
                                    title = mediaTitle,
                                    episodeLabel = null,
                                    positionMs = positionMs,
                                    durationMs = durationMs,
                                    isLive = isLive,
                                    onSeekTo = onSeekTo,
                                    showProgress = false
                                )

                                // Native-only: track pickers/settings — hidden for DLNA renderers.
                                if (!dlnaMode) {
                                    TrackChipsRow(
                                        audioTracks = audioTracks,
                                        subtitleTracks = subtitleTracks,
                                        onSelectAudio = onSelectAudio,
                                        onSubtitlesClick = { showSubtitlesSheet = true },
                                        onMore = { showSettingsSheet = true }
                                    )
                                }

                                // ── Episode list (fills available space) ──
                                EpisodesList(
                                    episodes = episodes,
                                    currentIndex = currentEpisodeIndex,
                                    onJumpToEpisode = onJumpToEpisode,
                                    modifier = Modifier.weight(1f)
                                )

                                // ── Combined seek + volume bar ──
                                // Swipe left/right to scrub, up/down for volume (volume native-only).
                                SeekVolumeBar(
                                    positionMs = positionMs,
                                    durationMs = durationMs,
                                    isLive = isLive,
                                    enableVolume = !dlnaMode,
                                    onSeekTo = onSeekTo,
                                    onVolumeUp = { onRemoteKey("volume_up") },
                                    onVolumeDown = { onRemoteKey("volume_down") }
                                )
                                MediaControlRow(
                                    isPlaying = playbackState == null || playbackState == "playing" || playbackState == "buffering",
                                    onPlayerControl = onPlayerControl,
                                    showLoop = !dlnaMode,
                                    showSeek = !isLive
                                )
                            }

                            RemoteContext.BROWSER -> BrowserContextView(
                                playbackState = playbackState,
                                positionMs = positionMs,
                                durationMs = durationMs,
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

                        // Explicit input surfaces — work in any context (the TV routes by
                        // its own context). The surface fills the space, with the current
                        // context's bottom controls kept below it for quick access.
                        RemoteMode.DPAD -> {
                            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                                DpadArea(onRemoteKey = onRemoteKey)
                            }
                            ModeBottomBar(
                                ctx = ctx, dlnaMode = dlnaMode, isLive = isLive,
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
                                ctx = ctx, dlnaMode = dlnaMode, isLive = isLive,
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


/**
 * The interaction surface the user picks from the mode chip. CONTEXT is the adaptive
 * view (player controls / browser now-playing); the others are explicit input surfaces
 * that work regardless of the TV's context (the TV routes the keys/mouse by context).
 */
private enum class RemoteMode(val label: String) {
    CONTEXT("Context"), DPAD("D-Pad"), TOUCHPAD("Touchpad"), KEYBOARD("Keyboard")
}

/** Which TV surface the remote is controlling, derived from the TV's reported context. */
private enum class RemoteContext { PLAYER, BROWSER, IDLE }

/** Dropdown chip in the app bar that selects the interaction mode. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RemoteModeChip(selected: RemoteMode, onSelect: (RemoteMode) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box(modifier = Modifier.padding(end = 8.dp)) {
        AssistChip(
            onClick = { expanded = true },
            label = { Text(selected.label) },
            trailingIcon = {
                Icon(Icons.Default.ArrowDropDown, contentDescription = "Change mode")
            }
        )
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            RemoteMode.entries.forEach { mode ->
                DropdownMenuItem(
                    text = { Text(mode.label) },
                    onClick = { onSelect(mode); expanded = false }
                )
            }
        }
    }
}

private fun remoteContextOf(active: String): RemoteContext = when (active) {
    "player" -> RemoteContext.PLAYER
    "browser" -> RemoteContext.BROWSER
    else -> RemoteContext.IDLE
}

/**
 * Persistent connected-device status pill at the top of the remote: states what you're controlling,
 * and tags the protocol ("DLNA") so the active casting flow is unmistakable. Status-only — switching
 * and disconnecting live in the cast picker. Native maps [connectionState]; DLNA ([isDlna]) is always
 * "connected" to its renderer.
 */
@Composable
private fun ConnectedTvChip(
    deviceName: String?,
    connectionState: WebSocketClient.ConnectionState,
    isDlna: Boolean = false
) {
    val isConnected = isDlna || connectionState is WebSocketClient.ConnectionState.Connected
    val isConnecting = !isDlna && (
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

    val name = deviceName ?: if (isDlna) "renderer" else "TV"
    val label = when {
        isDlna -> "Casting to $name"
        isConnected -> "Watching on $name"
        isConnecting -> "Connecting to $name…"
        else -> "Not connected"
    }
    val icon = when {
        isDlna -> Icons.Default.Cast
        isConnected || isConnecting -> Icons.Default.Tv
        else -> Icons.Default.Smartphone
    }

    Surface(
        shape = RoundedCornerShape(50),
        color = accent.copy(alpha = 0.15f),
        border = androidx.compose.foundation.BorderStroke(1.dp, accent.copy(alpha = 0.5f))
    ) {
        Row(
            modifier = Modifier
                .heightIn(min = 36.dp)
                .padding(horizontal = 14.dp),
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
            if (isDlna) ProtocolBadge(text = "DLNA", accent = accent)
        }
    }
}

/** Small uppercase protocol tag (e.g. "DLNA") so the active casting flow is unmistakable. */
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

/**
 * The browser CONTEXT view: a now-playing strip (title + scrubber + play/pause) when the
 * WebView reports a controllable video, then volume and the browser controls. The raw input
 * surfaces (touchpad/d-pad/keyboard) are now separate modes chosen from the mode chip.
 */
@Composable
private fun ColumnScope.BrowserContextView(
    playbackState: String?,
    positionMs: Long,
    durationMs: Long,
    mediaTitle: String?,
    onBrowserControl: (String) -> Unit,
    onRemoteKey: (String) -> Unit
) {
    val videoActive = playbackState == "playing" || playbackState == "paused" ||
        playbackState == "buffering"
    if (videoActive) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 4.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            NowPlayingPanel(
                title = mediaTitle,
                episodeLabel = null,
                positionMs = positionMs,
                durationMs = durationMs,
                onSeekTo = { ms -> onBrowserControl("seek_to:$ms") }
            )
            IconButton(onClick = { onBrowserControl("toggle_play") }) {
                Icon(
                    imageVector = if (playbackState == "playing") Icons.Default.Pause
                                  else Icons.Default.PlayArrow,
                    contentDescription = "Play/Pause",
                    tint = MaterialTheme.colorScheme.onSurface
                )
            }
        }
    }

    // Push the controls to the bottom; the top stays calm when nothing's playing.
    Spacer(modifier = Modifier.weight(1f))

    VolumeRow(
        onVolumeUp = { onRemoteKey("volume_up") },
        onVolumeDown = { onRemoteKey("volume_down") }
    )

    // ── Browser Controls: Back · Refresh · Ad Block · Fullscreen · Source · Home ──
    BrowserContextRow(onBrowserControl = onBrowserControl, onRemoteKey = onRemoteKey)
}

/**
 * Context-appropriate bottom controls kept beneath the D-Pad / Touchpad surfaces:
 * browser nav + volume in browser context, media transport in player context.
 */
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
            VolumeRow(
                onVolumeUp = { onRemoteKey("volume_up") },
                onVolumeDown = { onRemoteKey("volume_down") }
            )
            BrowserContextRow(onBrowserControl = onBrowserControl, onRemoteKey = onRemoteKey)
        }
        RemoteContext.PLAYER -> MediaControlRow(
            isPlaying = playbackState == null || playbackState == "playing" || playbackState == "buffering",
            onPlayerControl = onPlayerControl,
            showLoop = !dlnaMode,
            showSeek = !isLive
        )
        RemoteContext.IDLE -> { /* nothing to control */ }
    }
}

/**
 * Calm placeholder shown when the TV is idle (e.g. right after Stop). Offers to reveal the
 * input surface on demand instead of dropping the user straight onto a touchpad.
 */
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
        Text(
            "Nothing playing",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold
        )
        Text(
            "on ${tvName ?: "your TV"}",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(20.dp))
        FilledTonalButton(onClick = onShowControls) {
            Icon(Icons.Default.TouchApp, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(modifier = Modifier.width(8.dp))
            Text("Show touchpad & controls")
        }
    }
}

/**
 * Keyboard input for browsing the TV. The phone's text field streams its full contents to the
 * TV on every change (`text:<base64>`), which the TV writes into its focused web input — so
 * typing and deleting both work. The Enter button submits (`key_enter`).
 */
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
            .clip(RoundedCornerShape(20.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f))
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f), RoundedCornerShape(20.dp))
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
        Button(onClick = { onRemoteKey("key_enter") }) {
            Icon(Icons.Default.Done, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text("Enter")
        }
    }

    LaunchedEffect(Unit) { focusRequester.requestFocus() }
}

/** Slim horizontal −/＋ volume rocker shown just above the bottom controls in every context. */
@Composable
private fun VolumeRow(
    onVolumeUp: () -> Unit,
    onVolumeDown: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(onClick = onVolumeDown) {
            Icon(Icons.Default.Remove, contentDescription = "Volume down")
        }
        Icon(
            Icons.AutoMirrored.Filled.VolumeUp,
            contentDescription = null,
            modifier = Modifier.size(20.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
        IconButton(onClick = onVolumeUp) {
            Icon(Icons.Default.Add, contentDescription = "Volume up")
        }
    }
}

/** Drag-axis lock for the combined seek/volume gesture surface. */
private enum class DragAxis { HORIZONTAL, VERTICAL }

/**
 * Combined seek + volume control that sits where the volume rocker used to be (player context).
 * Swipe **left/right** to scrub the seekbar (absolute seek sent on release); swipe **up/down**
 * to step the volume. The gesture locks to whichever axis the drag starts on. Seeking needs a
 * finite duration (disabled for live / unknown-duration streams); volume is gated by
 * [enableVolume] (native receiver only — DLNA volume isn't wired).
 */
@Composable
private fun SeekVolumeBar(
    positionMs: Long,
    durationMs: Long,
    isLive: Boolean,
    enableVolume: Boolean,
    onSeekTo: (Long) -> Unit,
    onVolumeUp: () -> Unit,
    onVolumeDown: () -> Unit,
) {
    val hasDuration = durationMs > 0L && !isLive
    val currentPosition by rememberUpdatedState(positionMs)

    var widthPx by remember { mutableFloatStateOf(0f) }
    var dragging by remember { mutableStateOf(false) }
    var dragMs by remember { mutableFloatStateOf(0f) }
    var activeAxis by remember { mutableStateOf<DragAxis?>(null) }
    var volumeDirection by remember { mutableStateOf<String?>(null) } // "up" or "down"
    var touchDownTime by remember { mutableLongStateOf(0L) }
    var isAggressive by remember { mutableStateOf(false) }

    // Vertical travel required for one volume step.
    val volumeStepPx = with(LocalDensity.current) { 28.dp.toPx() }
    // Horizontal travel between seek "ticks" — keeps the scrub haptic tied to finger
    // movement so normal and fast seeking feel consistent.
    val seekStepPx = with(LocalDensity.current) { 16.dp.toPx() }

    // Haptics: a tick when an axis engages / on each volume step, and a firmer buzz
    // when "fast" (hold-then-drag) mode kicks in.
    val view = LocalView.current
    val tick: () -> Unit = { view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK) }
    val thud: () -> Unit = { view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS) }

    // The whole bar lifts (and gains a shadow) while actively seeking / adjusting volume.
    val lift by animateDpAsState(
        targetValue = if (activeAxis != null) 10.dp else 0.dp,
        animationSpec = tween(durationMillis = 160),
        label = "seekBarLift",
    )

    val displayMs = if (dragging && hasDuration) dragMs else currentPosition.toFloat()
    val fraction = if (hasDuration && durationMs > 0L)
        (displayMs / durationMs.toFloat()).coerceIn(0f, 1f) else 0f

    val primary = MaterialTheme.colorScheme.primary

    // Vertical offset to center the popups vertically on the screen (above the seekbar)
    val popupOffset = with(LocalDensity.current) {
        IntOffset(0, -180.dp.roundToPx())
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .offset(y = -lift)
            .shadow(elevation = lift, shape = RoundedCornerShape(24.dp), clip = false)
            .height(76.dp)
            .clip(RoundedCornerShape(24.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.15f))
            .border(BorderStroke(1.dp, Color.White.copy(alpha = 0.12f)), RoundedCornerShape(24.dp))
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
                        val dragStartTime = System.currentTimeMillis()
                        isAggressive = (dragStartTime - touchDownTime) >= 400L
                        // Firmer buzz to confirm the user held long enough for fast mode.
                        if (isAggressive) thud()

                        axis = null
                        volAccum = 0f
                        seekAccum = 0f
                        dragMs = currentPosition.toFloat()
                        volumeDirection = null
                    },
                    onDrag = { change, drag ->
                        change.consume()
                        if (axis == null) {
                            axis = if (abs(drag.x) >= abs(drag.y)) DragAxis.HORIZONTAL else DragAxis.VERTICAL
                            activeAxis = axis
                            if (axis == DragAxis.HORIZONTAL && hasDuration) dragging = true
                            // Light tick the moment a seek/volume gesture locks in.
                            tick()
                        }
                        when (axis) {
                            DragAxis.HORIZONTAL -> if (hasDuration && widthPx > 0f) {
                                val rangeMs = if (isAggressive) {
                                    durationMs.toFloat()
                                } else {
                                    (durationMs / 10L).coerceIn(120_000L, 600_000L).toFloat()
                                }
                                val deltaMs = (drag.x / widthPx) * rangeMs
                                dragMs = (dragMs + deltaMs).coerceIn(0f, durationMs.toFloat())
                                // Tick steadily as the finger scrubs (normal and fast alike).
                                seekAccum += abs(drag.x)
                                while (seekAccum >= seekStepPx) {
                                    tick()
                                    seekAccum -= seekStepPx
                                }
                            }
                            DragAxis.VERTICAL -> if (enableVolume) {
                                if (volumeDirection == null) {
                                    volumeDirection = if (drag.y < 0) "up" else "down"
                                }
                                volAccum -= drag.y // swipe up (negative y) raises volume
                                val stepPx = if (isAggressive) volumeStepPx / 2.5f else volumeStepPx
                                while (volAccum >= stepPx) {
                                    onVolumeUp()
                                    tick()
                                    volAccum -= stepPx
                                    volumeDirection = "up"
                                }
                                while (volAccum <= -stepPx) {
                                    onVolumeDown()
                                    tick()
                                    volAccum += stepPx
                                    volumeDirection = "down"
                                }
                            }
                            else -> {}
                        }
                    },
                    onDragEnd = {
                        if (axis == DragAxis.HORIZONTAL && hasDuration) onSeekTo(dragMs.toLong())
                        dragging = false
                        activeAxis = null
                        axis = null
                        volumeDirection = null
                        isAggressive = false
                    },
                    onDragCancel = {
                        dragging = false
                        activeAxis = null
                        axis = null
                        volumeDirection = null
                        isAggressive = false
                    },
                )
            },
        contentAlignment = Alignment.CenterStart
    ) {
        // --- Redesigned UX: Visual axis guides and guides in background ---
        // Horizontal guide line
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(Color.White.copy(alpha = 0.08f))
                .align(Alignment.Center)
        )
        // Vertical guide line
        if (enableVolume) {
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .width(1.dp)
                    .background(Color.White.copy(alpha = 0.08f))
                    .align(Alignment.Center)
            )
        }

        // --- Arrow Indicators to suggest directions ---
        // Left Seek arrow
        Icon(
            imageVector = Icons.AutoMirrored.Filled.KeyboardArrowLeft,
            contentDescription = null,
            tint = if (activeAxis == DragAxis.HORIZONTAL) primary else Color.White.copy(alpha = 0.3f),
            modifier = Modifier
                .align(Alignment.CenterStart)
                .padding(start = 8.dp)
                .size(24.dp)
        )
        // Right Seek arrow
        Icon(
            imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = if (activeAxis == DragAxis.HORIZONTAL) primary else Color.White.copy(alpha = 0.3f),
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .padding(end = 8.dp)
                .size(24.dp)
        )
        // Top Volume arrow
        if (enableVolume) {
            Icon(
                imageVector = Icons.Filled.KeyboardArrowUp,
                contentDescription = null,
                tint = if (activeAxis == DragAxis.VERTICAL) primary else Color.White.copy(alpha = 0.3f),
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 2.dp)
                    .size(14.dp)
            )
        }
        // Bottom Volume arrow
        if (enableVolume) {
            Icon(
                imageVector = Icons.Filled.KeyboardArrowDown,
                contentDescription = null,
                tint = if (activeAxis == DragAxis.VERTICAL) primary else Color.White.copy(alpha = 0.3f),
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 2.dp)
                    .size(14.dp)
            )
        }

        // --- Content Column containing labels and progress bar ---
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 36.dp).padding(top = 18.dp, bottom = 18.dp),
            verticalArrangement = Arrangement.SpaceBetween,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Top Status Label Row: seek text on left half, volume text on right half
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier.weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    val seekLabel = if (activeAxis == DragAxis.HORIZONTAL) {
                        if (isAggressive) "Fast Seeking ◄►" else "Seeking ◄►"
                    } else "Swipe ◄► Seek"
                    Text(
                        text = seekLabel,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = if (activeAxis == DragAxis.HORIZONTAL) primary else Color.White.copy(alpha = 0.6f)
                    )
                }
                if (enableVolume) {
                    Box(
                        modifier = Modifier.weight(1f),
                        contentAlignment = Alignment.Center
                    ) {
                        val volumeLabel = if (activeAxis == DragAxis.VERTICAL) {
                            if (isAggressive) "Fast Volume ▲▼" else "Adjusting Volume ▲▼"
                        } else "Swipe ▲▼ Volume"
                        Text(
                            text = volumeLabel,
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = if (activeAxis == DragAxis.VERTICAL) primary else Color.White.copy(alpha = 0.6f)
                        )
                    }
                }
            }

            // Center Progress Bar
            if (hasDuration) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(6.dp)
                        .clip(RoundedCornerShape(3.dp))
                        .background(Color.White.copy(alpha = 0.12f))
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxHeight()
                            .fillMaxWidth(fraction)
                            .background(
                                Brush.horizontalGradient(
                                    colors = listOf(primary, primary.copy(alpha = 0.7f))
                                )
                            )
                    )
                }
            } else {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(1.dp)
                        .background(Color.White.copy(alpha = 0.12f))
                )
            }

            // Bottom Time Row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = if (hasDuration) formatTime(displayMs.toLong()) else formatTime(currentPosition),
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.White.copy(alpha = 0.5f)
                )
                Text(
                    text = if (isLive) "LIVE" else if (durationMs > 0L) formatTime(durationMs) else "--:--",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.White.copy(alpha = 0.5f)
                )
            }
        }
    }

    // --- Seek HUD Overlay popup ---
    if (dragging && activeAxis == DragAxis.HORIZONTAL && hasDuration) {
        val deltaMs = dragMs.toLong() - currentPosition
        Popup(
            alignment = Alignment.Center,
            offset = popupOffset,
            properties = PopupProperties(
                focusable = false,
                dismissOnBackPress = false,
                dismissOnClickOutside = false
            )
        ) {
            Surface(
                color = Color.Black.copy(alpha = 0.75f),
                shape = RoundedCornerShape(18.dp),
                border = BorderStroke(1.dp, Color.White.copy(alpha = 0.15f))
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = if (isAggressive) "Fast Seek" else "Seek",
                        color = primary,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        "${formatTime(dragMs.toLong())} / ${formatTime(durationMs)}",
                        color = Color.White,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    val sign = if (deltaMs >= 0) "+" else "-"
                    Text(
                        "$sign${formatTime(abs(deltaMs))}",
                        color = Color.White.copy(alpha = 0.8f),
                        style = MaterialTheme.typography.labelMedium
                    )
                }
            }
        }
    }

    // --- Volume HUD Overlay popup ---
    if (activeAxis == DragAxis.VERTICAL && enableVolume) {
        Popup(
            alignment = Alignment.Center,
            offset = popupOffset,
            properties = PopupProperties(
                focusable = false,
                dismissOnBackPress = false,
                dismissOnClickOutside = false
            )
        ) {
            Surface(
                color = Color.Black.copy(alpha = 0.75f),
                shape = RoundedCornerShape(18.dp),
                border = BorderStroke(1.dp, Color.White.copy(alpha = 0.15f))
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    val volumeIcon = when (volumeDirection) {
                        "down" -> Icons.AutoMirrored.Filled.VolumeDown
                        else -> Icons.AutoMirrored.Filled.VolumeUp
                    }
                    Icon(
                        imageVector = volumeIcon,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(28.dp)
                    )
                    val volumeLabel = when (volumeDirection) {
                        "up" -> if (isAggressive) "Volume Up (Fast) ▲" else "Volume Up ▲"
                        "down" -> if (isAggressive) "Volume Down (Fast) ▼" else "Volume Down ▼"
                        else -> if (isAggressive) "Volume (Fast)" else "Volume"
                    }
                    Text(
                        volumeLabel,
                        color = Color.White,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}


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
            .clip(RoundedCornerShape(20.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f))
            .border(
                width = 1.dp,
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                shape = RoundedCornerShape(20.dp)
            )
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent()
                        val pointerCount = event.changes.count { it.pressed }

                        if (pointerCount >= 2) {
                            isScrolling = true
                            // Two fingers = scroll OR pinch-zoom. Decide per-frame by
                            // whether the fingers' separation changed more than their
                            // shared (centroid) translation: distance-dominant → zoom,
                            // translation-dominant → scroll.
                            val pressed = event.changes.filter { it.pressed }
                            val a = pressed.getOrNull(0)
                            val b = pressed.getOrNull(1)
                            if (a != null && b != null &&
                                a.previousPressed && b.previousPressed
                            ) {
                                val curDist = (a.position - b.position).getDistance()
                                val prevDist =
                                    (a.previousPosition - b.previousPosition).getDistance()
                                val panX = ((a.position.x + b.position.x) -
                                    (a.previousPosition.x + b.previousPosition.x)) / 2f
                                val panY = ((a.position.y + b.position.y) -
                                    (a.previousPosition.y + b.previousPosition.y)) / 2f
                                val distDelta = curDist - prevDist
                                if (prevDist > 0f &&
                                    kotlin.math.abs(distDelta) > kotlin.math.abs(panY) &&
                                    kotlin.math.abs(distDelta) > kotlin.math.abs(panX)
                                ) {
                                    onPinchZoom(curDist / prevDist)
                                } else {
                                    onMouseScroll(panX, panY * 2f)
                                }
                                a.consume()
                                b.consume()
                            }
                        } else if (pointerCount == 1 && !isScrolling) {
                            val change = event.changes.first()
                            if (change.pressed && change.previousPressed) {
                                val delta = change.position - change.previousPosition
                                onMouseMove(delta.x * 1.5f, delta.y * 1.5f)
                                change.consume()
                            }
                        } else if (pointerCount == 0) {
                            isScrolling = false
                        }
                    }
                }
            }
            .pointerInput(Unit) {
                detectTapGestures(onTap = { onMouseClick() })
            }
            .pointerInput(Unit) {
                // Long-press then drag → click-drag on the TV (e.g. seekbar scrubbing)
                detectDragGesturesAfterLongPress(
                    onDragStart = {
                        isDragging = true
                        onMouseDown()
                    },
                    onDrag = { change, dragAmount ->
                        change.consume()
                        onMouseMove(dragAmount.x * 1.5f, dragAmount.y * 1.5f)
                    },
                    onDragEnd = {
                        if (isDragging) {
                            isDragging = false
                            onMouseUp()
                        }
                    },
                    onDragCancel = {
                        if (isDragging) {
                            isDragging = false
                            onMouseUp()
                        }
                    }
                )
            },
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Icon(
                Icons.Default.TouchApp,
                contentDescription = null,
                modifier = Modifier.size(48.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.15f)
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                "1 finger: move  •  2 fingers: scroll  •  Pinch: zoom  •  Tap: click  •  Long-press+drag: drag",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f),
                textAlign = TextAlign.Center
            )
        }
    }
}


@Composable
private fun DpadArea(onRemoteKey: (String) -> Unit) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            // Up
            DpadBtn(Icons.Default.KeyboardArrowUp, "Up") { onRemoteKey("dpad_up") }

            // Left, OK, Right
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                DpadBtn(Icons.AutoMirrored.Filled.KeyboardArrowLeft, "Left") { onRemoteKey("dpad_left") }

                // OK button (larger, primary color)
                FilledTonalButton(
                    onClick = { onRemoteKey("dpad_center") },
                    modifier = Modifier.size(80.dp),
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

            // Down
            DpadBtn(Icons.Default.KeyboardArrowDown, "Down") { onRemoteKey("dpad_down") }
        }
    }
}

@Composable
private fun DpadBtn(icon: ImageVector, desc: String, onClick: () -> Unit) {
    FilledTonalButton(
        onClick = onClick,
        modifier = Modifier.size(64.dp),
        shape = CircleShape,
        contentPadding = PaddingValues(0.dp)
    ) {
        Icon(icon, contentDescription = desc, modifier = Modifier.size(32.dp))
    }
}


@Composable
private fun MediaControlRow(
    isPlaying: Boolean,
    onPlayerControl: (String) -> Unit,
    showLoop: Boolean = true,
    showSeek: Boolean = true,
) {
    var isLooping by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
            .padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Seek -10s
        if (showSeek) {
            LabeledIconButton(
                icon = Icons.Default.Replay10,
                label = "-10s",
                tint = MaterialTheme.colorScheme.onSurface,
                onClick = { onPlayerControl("seek_back") }
            )
        }

        // Play/Pause toggle — reflects the TV's actual state
        LabeledIconButton(
            icon = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
            label = if (isPlaying) "Pause" else "Play",
            tint = MaterialTheme.colorScheme.primary,
            onClick = { onPlayerControl(if (isPlaying) "pause" else "play") }
        )

        // Seek +10s
        if (showSeek) {
            LabeledIconButton(
                icon = Icons.Default.Forward10,
                label = "+10s",
                tint = MaterialTheme.colorScheme.onSurface,
                onClick = { onPlayerControl("seek_forward") }
            )
        }

        // Loop toggle
        if (showLoop) {
            LabeledIconButton(
                icon = Icons.Default.Repeat,
                label = "Loop",
                tint = if (isLooping) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                onClick = {
                    val next = !isLooping
                    onPlayerControl(if (next) "loop_on" else "loop_off")
                    isLooping = next
                }
            )
        }

        // Stop
        LabeledIconButton(
            icon = Icons.Default.Stop,
            label = "Stop",
            tint = MaterialTheme.colorScheme.error,
            onClick = { onPlayerControl("stop") }
        )
    }
}


@Composable
private fun BrowserContextRow(
    onBrowserControl: (String) -> Unit,
    onRemoteKey: (String) -> Unit
) {
    var isVideoMaximized by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
            .padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Back — goes back one page
        LabeledIconButton(
            icon = Icons.AutoMirrored.Filled.ArrowBack,
            label = "Back",
            tint = MaterialTheme.colorScheme.onSurface,
            onClick = { onRemoteKey("back") }
        )
        // Refresh
        LabeledIconButton(
            icon = Icons.Default.Refresh,
            label = "Refresh",
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            onClick = { onBrowserControl("refresh") }
        )
        // Ad Blocker
        LabeledIconButton(
            icon = Icons.Default.Shield,
            label = "Ad Block",
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            onClick = { onBrowserControl("toggle_ublock") }
        )
        // Maximize / Restore Video
        LabeledIconButton(
            icon = if (isVideoMaximized) Icons.Default.FullscreenExit else Icons.Default.Fullscreen,
            label = if (isVideoMaximized) "Restore" else "Fullscreen",
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            onClick = {
                onBrowserControl(if (isVideoMaximized) "restore_video" else "maximize_video")
                isVideoMaximized = !isVideoMaximized
            }
        )
        // Source — manual override for the D-pad's video target. The TV auto-follows the
        // largest on-screen video (main page or any embedded iframe); tap to cycle to the
        // next one when auto-selection picks the wrong player. Momentary, not a mode.
        LabeledIconButton(
            icon = Icons.Default.Layers,
            label = "Source",
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            onClick = { onBrowserControl("video_target_cycle") }
        )
        // Home — exits the browser
        LabeledIconButton(
            icon = Icons.Default.Home,
            label = "Home",
            tint = MaterialTheme.colorScheme.onSurface,
            onClick = { onRemoteKey("home") }
        )
    }
}


@Composable
private fun LabeledIconButton(
    icon: ImageVector,
    label: String,
    tint: Color,
    onClick: () -> Unit
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.padding(horizontal = 4.dp)
    ) {
        IconButton(onClick = onClick) {
            Icon(icon, contentDescription = label, tint = tint)
        }
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = tint.copy(alpha = 0.7f),
            fontSize = 10.sp
        )
    }
}


/**
 * Title + live seekbar. The slider tracks the TV's reported position, except while
 * the user is dragging — on release it sends an absolute seek to the TV.
 */
@Composable
private fun NowPlayingPanel(
    title: String?,
    episodeLabel: String?,
    positionMs: Long,
    durationMs: Long,
    isLive: Boolean = false,
    onSeekTo: (Long) -> Unit,
    // When false, only the title/episode label show — the seek control is rendered
    // elsewhere (the SeekVolumeBar in the player context).
    showProgress: Boolean = true
) {
    val hasDuration = durationMs > 0L
    var dragging by remember { mutableStateOf(false) }
    var dragValue by remember { mutableFloatStateOf(0f) }

    // Stop dragging once the TV's reported position catches up to where we seeked.
    LaunchedEffect(positionMs) { if (dragging) dragging = false }

    val sliderValue = (if (dragging) dragValue else positionMs.toFloat())
        .coerceIn(0f, if (hasDuration) durationMs.toFloat() else 0f)

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = title ?: "Playing on TV",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            maxLines = 2,
            textAlign = TextAlign.Center
        )
        if (episodeLabel != null) {
            Text(
                text = episodeLabel,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        if (showProgress) {
            Slider(
                value = sliderValue,
                onValueChange = {
                    dragging = true
                    dragValue = it
                },
                onValueChangeFinished = {
                    if (hasDuration) onSeekTo(dragValue.toLong())
                },
                valueRange = 0f..(if (hasDuration) durationMs.toFloat() else 1f),
                enabled = hasDuration,
                modifier = Modifier.fillMaxWidth()
            )

            Row(modifier = Modifier.fillMaxWidth()) {
                // Show the real elapsed position even when duration is unknown (DLNA streams that
                // don't report a total) — sliderValue is clamped to [0,duration] and would pin to 0.
                Text(
                    text = formatTime(if (dragging) dragValue.toLong() else positionMs),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.weight(1f))
                Text(
                    text = if (isLive) "LIVE" else if (hasDuration) formatTime(durationMs) else "--:--",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (isLive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}


/** The TV's current playlist; tapping an entry jumps the TV to that episode. */
@Composable
private fun EpisodesList(
    episodes: List<PlaylistEpisode>,
    currentIndex: Int,
    onJumpToEpisode: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    if (episodes.size <= 1) {
        // Single item (or none): nothing to choose between.
        Spacer(modifier = modifier)
        return
    }

    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            text = "Up Next",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(vertical = 8.dp)
        )
        LazyColumn(modifier = Modifier.fillMaxSize()) {
            items(episodes) { ep ->
                val isCurrent = ep.index == currentIndex
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(
                            if (isCurrent) MaterialTheme.colorScheme.primaryContainer
                            else Color.Transparent
                        )
                        .clickable { onJumpToEpisode(ep.index) }
                        .padding(horizontal = 12.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    if (isCurrent) {
                        Icon(
                            Icons.Default.PlayArrow,
                            contentDescription = "Now playing",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp)
                        )
                    } else {
                        Text(
                            text = "${ep.index + 1}",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(20.dp),
                            textAlign = TextAlign.Center
                        )
                    }
                    Text(
                        text = shortEpisodeLabel(ep.title),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = if (isCurrent) FontWeight.SemiBold else FontWeight.Normal,
                        maxLines = 1
                    )
                }
            }
        }
    }
}


/** A row of compact dropdown chips for audio/subtitle track + a "More" settings button. */
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
            TrackChip(
                icon = Icons.Default.VolumeUp,
                label = "Audio",
                tracks = audioTracks,
                onSelect = onSelectAudio,
                modifier = Modifier.weight(1f)
            )
        }
        if (subtitleTracks.isNotEmpty()) {
            val selectedName = subtitleTracks.firstOrNull { it.selected }?.name ?: "—"
            Surface(
                onClick = onSubtitlesClick,
                shape = RoundedCornerShape(20.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                modifier = Modifier.weight(1f).height(40.dp)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Icon(
                        Icons.Default.Subtitles,
                        contentDescription = "Subtitles",
                        modifier = Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = "Subs: $selectedName",
                        style = MaterialTheme.typography.labelLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f)
                    )
                    Icon(
                        Icons.Default.ArrowDropDown,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
        if (audioTracks.isEmpty() && subtitleTracks.isEmpty()) {
            Spacer(modifier = Modifier.weight(1f))
        }
        // "More" — opens the full player settings sheet.
        Surface(
            onClick = onMore,
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
            modifier = Modifier.height(40.dp)
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
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
            Icon(
                icon,
                contentDescription = label,
                modifier = Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = "$label: $selectedName",
                style = MaterialTheme.typography.labelLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f)
            )
            Icon(
                Icons.Default.ArrowDropDown,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            tracks.forEach { track ->
                DropdownMenuItem(
                    text = { Text(track.name) },
                    onClick = {
                        onSelect(track.id)
                        expanded = false
                    },
                    leadingIcon = if (track.selected) {
                        { Icon(Icons.Default.Check, contentDescription = "Selected") }
                    } else null
                )
            }
        }
    }
}


private val EPISODE_MARKER = Regex("""S\d+\s*E\d+.*""", RegexOption.IGNORE_CASE)

/**
 * Drop the redundant series-name prefix from an episode title for list display,
 * e.g. "Breaking Bad S2E5 - Breakage" -> "S2E5 - Breakage". Titles without an
 * SxEy marker (movies, single items) are left unchanged.
 */
private fun shortEpisodeLabel(title: String): String =
    EPISODE_MARKER.find(title)?.value ?: title

/** Format milliseconds as m:ss (or h:mm:ss past an hour). */
private fun formatTime(ms: Long): String {
    if (ms <= 0L) return "0:00"
    val totalSec = ms / 1000
    val h = totalSec / 3600
    val m = (totalSec % 3600) / 60
    val s = totalSec % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}


/** Full player settings: mirrors the TV's settings panel (speed/scaling/audio/subtitle/filter/engine). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PlayerSettingsSheet(
    settings: TvPlayerSettings,
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
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            Text(
                "Player settings",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )

            SettingRow("Speed") {
                ChipGroup(
                    options = listOf(0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 1.75f, 2.0f)
                        .map { (if (it == 1.0f) "1x" else "${it}x") to it.toString() },
                    selectedKey = settings.speed.toString(),
                    onSelect = { it.toFloatOrNull()?.let(onSetSpeed) }
                )
            }

            SettingRow("Scaling") {
                ChipGroup(
                    options = listOf("Fit" to "Fit", "Fill" to "Fill", "Zoom" to "Zoom"),
                    selectedKey = settings.scaling,
                    onSelect = onSetScaling
                )
            }

            SettingRow("Subtitle offset") {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    FilledTonalButton(onClick = { onAdjustSubtitleOffset(-250L) }) { Text("−250ms") }
                    Text("${settings.subtitleOffsetMs} ms", style = MaterialTheme.typography.bodyMedium)
                    FilledTonalButton(onClick = { onAdjustSubtitleOffset(250L) }) { Text("+250ms") }
                }
            }

            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Audio boost", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                Switch(checked = settings.audioBoost, onCheckedChange = { onToggleAudioBoost() })
            }

            SettingRow("Player engine") {
                ChipGroup(
                    options = listOf(
                        "ExoPlayer" to "exo",
                        "MPV" to "mpv"
                    ),
                    selectedKey = settings.engine,
                    onSelect = onSwitchEngine
                )
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
        Text(
            label,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        content()
    }
}

@Composable
private fun ChipGroup(
    options: List<Pair<String, String>>,
    selectedKey: String,
    onSelect: (String) -> Unit
) {
    Row(
        modifier = Modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        options.forEach { (label, key) ->
            FilterChip(
                selected = key == selectedKey,
                onClick = { onSelect(key) },
                label = { Text(label) }
            )
        }
    }
}

/** Add an external subtitle to the TV: paste a URL, or search (when now-playing metadata is known). */
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

    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
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

            val mime = when {
                fileName.endsWith(".vtt", ignoreCase = true) -> "text/vtt"
                else -> "application/x-subrip"
            }

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
                Button(
                    onClick = { filePickerLauncher.launch("*/*") },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.filledTonalButtonColors()
                ) {
                    Icon(
                        imageVector = Icons.Default.UploadFile,
                        contentDescription = "Upload local file",
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Choose Local Subtitle File")
                }

                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    label = { Text("Subtitle URL (.srt / .vtt)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
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
                        Text(
                            "No subtitles found",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    if (results.isNotEmpty()) {
                        LazyColumn(modifier = Modifier.heightIn(max = 220.dp)) {
                            items(results) { opt ->
                                Text(
                                    opt.label,
                                    style = MaterialTheme.typography.bodyMedium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { onAddUrl(opt.url) }
                                        .padding(vertical = 10.dp)
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { if (url.isNotBlank()) onAddUrl(url.trim()) },
                enabled = url.isNotBlank()
            ) { Text("Add") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}


private const val OFF_KEY = "__off__"
private const val EMBEDDED_KEY = "__embedded__"
private const val REMOTE_KEY = "__remote__"
private const val EXTERNAL_KEY = "__external__"

private data class SubGroup(
    val key: String,
    val label: String,
    val tracks: List<MediaTrack>,
    val hasSelected: Boolean
)

private data class SubInfo(val langKey: String, val langDisplay: String, val optionLabel: String)

// Mapping from language token to display name
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
    if (t.id == "off" || t.id == "none") {
        return SubInfo(OFF_KEY, "Off", "Off")
    }

    val isExternal = t.type == "external_sub" || t.id.startsWith("external_") || t.id.contains("://")
    if (!isExternal) {
        return SubInfo(EMBEDDED_KEY, "Embedded", t.name)
    }

    if (!t.name.contains("OpenSubtitles #")) {
        return SubInfo(REMOTE_KEY, "Phone Remote", t.name.ifBlank { "Remote Subtitle" })
    }

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
    if (off != null) {
        groups.add(SubGroup(OFF_KEY, "Off", listOf(off), off.selected))
    }

    byLang[EMBEDDED_KEY]?.let { (display, list) ->
        groups.add(SubGroup(EMBEDDED_KEY, display, list, list.any { it.selected }))
    }

    byLang[REMOTE_KEY]?.let { (display, list) ->
        groups.add(SubGroup(REMOTE_KEY, display, list, list.any { it.selected }))
    }

    byLang.filterKeys { it != EMBEDDED_KEY && it != REMOTE_KEY && it != EXTERNAL_KEY }.forEach { (k, v) ->
        groups.add(SubGroup(k, v.first, v.second, v.second.any { it.selected }))
    }

    byLang[EXTERNAL_KEY]?.let { (display, list) ->
        groups.add(SubGroup(EXTERNAL_KEY, display, list, list.any { it.selected }))
    }

    return groups
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SubtitlesBottomSheet(
    tracks: List<MediaTrack>,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val groups = remember(tracks) { groupSubtitleTracks(tracks) }
    
    // Find the group that contains the currently selected track, or fallback to the first non-Off group, or the first group
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
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 24.dp)
                .padding(bottom = 24.dp)
        ) {
            Text(
                text = "Subtitles",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )

            Spacer(modifier = Modifier.height(16.dp))

            // Categories horizontal chips row
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
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
                        leadingIcon = if (group.hasSelected) {
                            {
                                Icon(
                                    imageVector = Icons.Default.Check,
                                    contentDescription = "Active selection",
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        } else null,
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
                            onClick = {
                                onSelect(track.id)
                                onDismiss()
                            },
                            shape = RoundedCornerShape(12.dp),
                            color = if (isSelected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
                            border = if (isSelected) BorderStroke(1.dp, MaterialTheme.colorScheme.primary) else null,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = displayLabel,
                                    style = MaterialTheme.typography.bodyLarge,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                    color = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface
                                )
                                if (isSelected) {
                                    Icon(
                                        imageVector = Icons.Default.Check,
                                        contentDescription = "Selected",
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

