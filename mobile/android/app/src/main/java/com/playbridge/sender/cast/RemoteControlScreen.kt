package com.playbridge.sender.cast
import com.playbridge.sender.library.*
import com.playbridge.sender.browser.*

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

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
    val selected: Boolean
)

/** TV player settings synced via `player_settings` messages. */
data class TvPlayerSettings(
    val speed: Float = 1.0f,
    val scaling: String = "Fit",
    val audioBoost: Boolean = false,
    val subtitleOffsetMs: Long = 0L,
    val filter: String = "NONE",
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
 * In **player mode** (`isMediaPlaying`) it shows a now-playing surface: title,
 * a live seekbar (drag to seek), transport controls, and the TV's episode list
 * (tap to jump). In **browser mode** it shows the Touchpad/D-Pad hero + browser
 * controls.
 *
 * @param isMediaPlaying Whether video/media is currently playing on the TV
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
    isMediaPlaying: Boolean,
    onBack: () -> Unit,
    onRemoteKey: (String) -> Unit,
    onMouseMove: (dx: Float, dy: Float) -> Unit,
    onMouseClick: () -> Unit,
    onMouseScroll: (dx: Float, dy: Float) -> Unit,
    onMouseDown: () -> Unit = {},
    onMouseUp: () -> Unit = {},
    onBrowserControl: (String) -> Unit = {},
    onPlayerControl: (String) -> Unit = {},
    playbackState: String? = null,
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
    onSetFilter: (String) -> Unit = {},
    onSwitchEngine: (String) -> Unit = {},
    onAddSubtitleUrl: (String) -> Unit = {},
    onSearchSubtitles: (suspend () -> List<SubtitleOption>)? = null
) {
    var showSettingsSheet by remember { mutableStateOf(false) }
    var showAddSubtitle by remember { mutableStateOf(false) }
    // Default to touchpad when no media playing (browser mode), D-Pad when playing (player mode)
    var isTouchpad by remember { mutableStateOf(!isMediaPlaying) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Remote Control") },
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
                .padding(horizontal = 20.dp)
                .padding(bottom = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            if (isMediaPlaying) {
                // ── Now Playing: title + live seekbar ──
                NowPlayingPanel(
                    title = mediaTitle,
                    episodeLabel = episodeLabelFor(currentEpisodeIndex, episodes),
                    positionMs = positionMs,
                    durationMs = durationMs,
                    onSeekTo = onSeekTo
                )

                // ── Audio / Subtitle track pickers + More ──
                TrackChipsRow(
                    audioTracks = audioTracks,
                    subtitleTracks = subtitleTracks,
                    onSelectAudio = onSelectAudio,
                    onSelectSubtitle = onSelectSubtitle,
                    onMore = { showSettingsSheet = true }
                )

                // ── Episode list (fills available space) ──
                EpisodesList(
                    episodes = episodes,
                    currentIndex = currentEpisodeIndex,
                    onJumpToEpisode = onJumpToEpisode,
                    modifier = Modifier.weight(1f)
                )

                // ── Transport controls (play/pause driven by TV state) ──
                // No Back/Home here — Stop already exits playback, so they'd be redundant.
                MediaControlRow(
                    isPlaying = playbackState == null || playbackState == "playing" || playbackState == "buffering",
                    onPlayerControl = onPlayerControl
                )
            } else {
                // ── Toggle Pill ──
                TogglePill(
                    isTouchpad = isTouchpad,
                    onToggle = { isTouchpad = it }
                )

                // ── Hero Area (fills available space) ──
                Box(modifier = Modifier.weight(1f)) {
                    if (isTouchpad) {
                        TouchpadArea(
                            onMouseMove = onMouseMove,
                            onMouseClick = onMouseClick,
                            onMouseScroll = onMouseScroll,
                            onMouseDown = onMouseDown,
                            onMouseUp = onMouseUp
                        )
                    } else {
                        DpadArea(onRemoteKey = onRemoteKey)
                    }
                }

                // ── Navigation Row ──
                NavigationRow(onRemoteKey = onRemoteKey)

                // ── Browser Controls ──
                BrowserContextRow(onBrowserControl = onBrowserControl)
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
            onSetFilter = onSetFilter,
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
}


@Composable
private fun TogglePill(isTouchpad: Boolean, onToggle: (Boolean) -> Unit) {
    val shape = RoundedCornerShape(20.dp)
    Row(
        modifier = Modifier
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(4.dp),
        horizontalArrangement = Arrangement.Center
    ) {
        PillOption(
            label = "Touchpad",
            icon = Icons.Default.TouchApp,
            selected = isTouchpad,
            onClick = { onToggle(true) }
        )
        PillOption(
            label = "D-Pad",
            icon = Icons.Default.Gamepad,
            selected = !isTouchpad,
            onClick = { onToggle(false) }
        )
    }
}

@Composable
private fun PillOption(
    label: String,
    icon: ImageVector,
    selected: Boolean,
    onClick: () -> Unit
) {
    val bg by animateColorAsState(
        if (selected) MaterialTheme.colorScheme.primary
        else Color.Transparent,
        label = "pillBg"
    )
    val fg by animateColorAsState(
        if (selected) MaterialTheme.colorScheme.onPrimary
        else MaterialTheme.colorScheme.onSurfaceVariant,
        label = "pillFg"
    )

    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(20.dp),
        color = bg,
        contentColor = fg,
        modifier = Modifier.height(40.dp)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 20.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp))
            Text(label, style = MaterialTheme.typography.labelLarge)
        }
    }
}


@Composable
private fun TouchpadArea(
    onMouseMove: (dx: Float, dy: Float) -> Unit,
    onMouseClick: () -> Unit,
    onMouseScroll: (dx: Float, dy: Float) -> Unit,
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
                            val change = event.changes.firstOrNull { it.pressed }
                            if (change != null && change.previousPressed) {
                                val delta = change.position - change.previousPosition
                                onMouseScroll(delta.x, delta.y * 2f)
                                change.consume()
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
                "1 finger: move  •  2 fingers: scroll  •  Tap: click  •  Long-press+drag: drag",
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
private fun NavigationRow(onRemoteKey: (String) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Back
        LabeledIconButton(
            icon = Icons.AutoMirrored.Filled.ArrowBack,
            label = "Back",
            tint = MaterialTheme.colorScheme.onSurface,
            onClick = { onRemoteKey("back") }
        )
        // Home
        LabeledIconButton(
            icon = Icons.Default.Home,
            label = "Home",
            tint = MaterialTheme.colorScheme.onSurface,
            onClick = { onRemoteKey("home") }
        )
    }
}


@Composable
private fun MediaControlRow(isPlaying: Boolean, onPlayerControl: (String) -> Unit) {
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
        LabeledIconButton(
            icon = Icons.Default.Replay10,
            label = "-10s",
            tint = MaterialTheme.colorScheme.onSurface,
            onClick = { onPlayerControl("seek_back") }
        )

        // Play/Pause toggle — reflects the TV's actual state
        LabeledIconButton(
            icon = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
            label = if (isPlaying) "Pause" else "Play",
            tint = MaterialTheme.colorScheme.primary,
            onClick = { onPlayerControl(if (isPlaying) "pause" else "play") }
        )

        // Seek +10s
        LabeledIconButton(
            icon = Icons.Default.Forward10,
            label = "+10s",
            tint = MaterialTheme.colorScheme.onSurface,
            onClick = { onPlayerControl("seek_forward") }
        )

        // Loop toggle
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
private fun BrowserContextRow(onBrowserControl: (String) -> Unit) {
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
    onSeekTo: (Long) -> Unit
) {
    val hasDuration = durationMs > 0L
    var dragging by remember { mutableStateOf(false) }
    var dragValue by remember { mutableStateOf(0f) }

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
            Text(
                text = formatTime(sliderValue.toLong()),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.weight(1f))
            Text(
                text = if (hasDuration) formatTime(durationMs) else "--:--",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
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
            text = "Episodes",
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
    onSelectSubtitle: (String) -> Unit,
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
            TrackChip(
                icon = Icons.Default.Subtitles,
                label = "Subs",
                tracks = subtitleTracks,
                onSelect = onSelectSubtitle,
                modifier = Modifier.weight(1f)
            )
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

/** "Episode X of N" when the playlist has more than one item. */
private fun episodeLabelFor(currentIndex: Int, episodes: List<PlaylistEpisode>): String? {
    if (episodes.size <= 1) return null
    return "Episode ${currentIndex + 1} of ${episodes.size}"
}

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
    onSetFilter: (String) -> Unit,
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

            SettingRow("Video filter (ExoPlayer)") {
                ChipGroup(
                    options = listOf(
                        "None" to "NONE", "HDR" to "HDR", "Night" to "NIGHT", "Movie" to "MOVIE",
                        "Cinema" to "CINEMA", "Action" to "ACTION", "Deep Black" to "DEEP_BLACK",
                        "Grayscale" to "GRAYSCALE", "Vivid" to "VIVID"
                    ),
                    selectedKey = settings.filter,
                    onSelect = onSetFilter
                )
            }

            SettingRow("Player engine") {
                ChipGroup(
                    options = listOf(
                        "ExoPlayer" to "internal_exo",
                        "VLC" to "internal_vlc",
                        "MPV" to "internal_mpv"
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

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add subtitle") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
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
