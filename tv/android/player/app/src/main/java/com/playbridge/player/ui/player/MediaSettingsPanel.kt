package com.playbridge.player.ui.player

import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.*

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun MediaSettingsPanel(
    state: PlayerControlsState,
    onTabSelected: (SettingsTab) -> Unit,
    onTrackSelected: (UnifiedTrack) -> Unit,
    onSpeedSelected: (Float) -> Unit,
    onScalingSelected: (String) -> Unit,
    onToggleAudioBoost: () -> Unit,
    onAdjustSubtitleDelay: (Long) -> Unit,
    onDismiss: () -> Unit
) {
    val activeTab = state.activeSettingsTab

    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.CenterEnd
    ) {
        Row(
            modifier = Modifier
                .width(420.dp)
                .fillMaxHeight(0.85f)
                .padding(end = 24.dp)
                .background(Color(0xF21A1A2E), RoundedCornerShape(14.dp))
                .padding(12.dp)
        ) {
            val sideBarFocusRequester = remember { FocusRequester() }

            // Initial focus on sidebar only once when panel opens
            LaunchedEffect(Unit) {
                sideBarFocusRequester.requestFocus()
            }

            // Sidebar tabs
            LazyColumn(
                modifier = Modifier
                    .width(110.dp)
                    .fillMaxHeight()
                    .padding(end = 8.dp)
                    .selectableGroup()
                    .focusRequester(sideBarFocusRequester),
                verticalArrangement = Arrangement.Top,
                contentPadding = PaddingValues(top = 4.dp)
            ) {
                item {
                    Text(
                        text = "Settings",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(bottom = 12.dp, start = 8.dp)
                    )
                }

                val tabs = listOf(
                    SettingsTab.VIDEO to "Video",
                    SettingsTab.AUDIO to "Audio",
                    SettingsTab.SUBTITLES to "Subtitles",
                    SettingsTab.SPEED to "Speed",
                    SettingsTab.SCALING to "Scaling"
                )

                items(tabs, key = { it.first.name }) { (tab, label) ->
                    SettingsTabButton(
                        text = label,
                        isSelected = activeTab == tab,
                        onClick = { onTabSelected(tab) }
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                }
            }

            // Divider
            Box(
                modifier = Modifier
                    .width(1.dp)
                    .fillMaxHeight()
                    .background(Color.White.copy(alpha = 0.1f))
            )

            // Content Area
            Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
                when (activeTab) {
                    SettingsTab.VIDEO -> UnifiedTrackList(state.videoTracks, onTrackSelected)
                    SettingsTab.AUDIO -> Column {
                        AudioBoostItem(isEnabled = state.isAudioBoostEnabled, onClick = onToggleAudioBoost)
                        Box(modifier = Modifier.weight(1f)) {
                            UnifiedTrackList(state.audioTracks, onTrackSelected)
                        }
                    }
                    SettingsTab.SUBTITLES -> Column {
                        SubtitleDelayControl(delayMs = state.subtitleDelayMs, onAdjust = onAdjustSubtitleDelay)
                        Box(modifier = Modifier.weight(1f)) {
                            UnifiedTrackList(state.subtitleTracks, onTrackSelected)
                        }
                    }
                    SettingsTab.SPEED -> SpeedSettingsList(state.playbackSpeed, onSpeedSelected)
                    SettingsTab.SCALING -> ScalingSettingsList(state.videoScalingMode, onScalingSelected, state.engineType)
                }
            }
        }
    }

    androidx.activity.compose.BackHandler { onDismiss() }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun SettingsTabButton(
    text: String,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        selected = isSelected,
        onClick = onClick,
        scale = SelectableSurfaceDefaults.scale(focusedScale = 1.02f),
        colors = SelectableSurfaceDefaults.colors(
            containerColor = Color.Transparent,
            focusedContainerColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.15f),
            selectedContainerColor = Color(0xFF00D9FF).copy(alpha = 0.15f),
            focusedSelectedContainerColor = Color(0xFF00D9FF).copy(alpha = 0.25f)
        ),
        shape = SelectableSurfaceDefaults.shape(RoundedCornerShape(8.dp)),
        modifier = modifier.fillMaxWidth()
    ) {
        Box(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            contentAlignment = Alignment.CenterStart
        ) {
            Text(
                text = text,
                style = MaterialTheme.typography.labelLarge,
                color = if (isSelected) Color(0xFF00D9FF) else MaterialTheme.colorScheme.onSurface,
                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium
            )
        }
    }
}

@Composable
private fun UnifiedTrackList(
    tracks: List<UnifiedTrack>,
    onTrackSelected: (UnifiedTrack) -> Unit
) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(start = 8.dp),
        contentPadding = PaddingValues(vertical = 4.dp)
    ) {
        // Split embedded (in the media) from externally added subtitle tracks so they can be
        // shown under a separator. Audio/video tabs have no external tracks, so nothing changes.
        val (embedded, external) = tracks.partition { it.type != "external_sub" }

        items(embedded, key = { it.id }) { track ->
            UnifiedTrackItem(
                track = track,
                onClick = { onTrackSelected(track) }
            )
        }

        if (external.isNotEmpty()) {
            if (embedded.isNotEmpty()) {
                item(key = "external_subs_separator") {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 4.dp, end = 8.dp, top = 8.dp, bottom = 2.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(1.dp)
                                .background(Color.White.copy(alpha = 0.15f))
                        )
                        Text(
                            text = "External subtitles",
                            color = Color.Gray,
                            fontSize = 11.sp,
                            modifier = Modifier.padding(top = 6.dp, start = 4.dp)
                        )
                    }
                }
            }
            items(external, key = { it.id }) { track ->
                UnifiedTrackItem(
                    track = track,
                    onClick = { onTrackSelected(track) }
                )
            }
        }

        if (tracks.isEmpty()) {
            item {
                Text(
                    text = "No tracks available",
                    color = Color.Gray,
                    fontSize = 13.sp,
                    modifier = Modifier.padding(12.dp)
                )
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun UnifiedTrackItem(
    track: UnifiedTrack,
    onClick: () -> Unit
) {
    Surface(
        selected = track.isSelected,
        onClick = onClick,
        scale = SelectableSurfaceDefaults.scale(focusedScale = 1.02f),
        colors = SelectableSurfaceDefaults.colors(
            containerColor = Color.Transparent,
            focusedContainerColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f),
            selectedContainerColor = Color.Transparent,
            focusedSelectedContainerColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.15f)
        ),
        shape = SelectableSurfaceDefaults.shape(RoundedCornerShape(6.dp)),
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 1.dp)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = if (track.isSelected) "✓" else " ",
                color = Color(0xFF00D9FF),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.width(22.dp)
            )

            Text(
                text = track.name,
                style = MaterialTheme.typography.labelLarge,
                color = if (track.isSelected) Color(0xFF00D9FF) else MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@Composable
private fun SpeedSettingsList(
    currentSpeed: Float,
    onSpeedSelected: (Float) -> Unit
) {
    val speeds = listOf(0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 2.0f)
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(start = 8.dp),
        contentPadding = PaddingValues(vertical = 4.dp)
    ) {
        items(speeds, key = { it.toString() }) { speed ->
            UnifiedTrackItem(
                track = UnifiedTrack(
                    id = speed.toString(),
                    name = if (speed == 1.0f) "1.0x (Normal)" else "${speed}x",
                    isSelected = speed == currentSpeed,
                    type = "speed"
                ),
                onClick = { onSpeedSelected(speed) }
            )
        }
    }
}

@Composable
private fun ScalingSettingsList(
    currentMode: String,
    onModeSelected: (String) -> Unit,
    engineType: String
) {
    val modes = when {
        engineType.contains("vlc") -> listOf("Fit", "Fill", "16:9", "4:3", "Center")
        engineType.contains("mpv") -> listOf("Fit", "Fill", "Zoom")
        else -> listOf("Fit", "Fill", "Zoom", "Fixed Width", "Fixed Height")
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(start = 8.dp),
        contentPadding = PaddingValues(vertical = 4.dp)
    ) {
        items(modes, key = { it }) { mode ->
            UnifiedTrackItem(
                track = UnifiedTrack(
                    id = mode,
                    name = mode,
                    isSelected = mode == currentMode,
                    type = "scaling"
                ),
                onClick = { onModeSelected(mode) }
            )
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun AudioBoostItem(
    isEnabled: Boolean,
    onClick: () -> Unit
) {
    Surface(
        selected = isEnabled,
        onClick = onClick,
        scale = SelectableSurfaceDefaults.scale(focusedScale = 1.02f),
        colors = SelectableSurfaceDefaults.colors(
            containerColor = Color.Transparent,
            focusedContainerColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f),
            selectedContainerColor = Color.Transparent,
            focusedSelectedContainerColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.15f)
        ),
        shape = SelectableSurfaceDefaults.shape(RoundedCornerShape(6.dp)),
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 8.dp, bottom = 8.dp)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = if (isEnabled) "✓" else " ",
                color = Color(0xFF00D9FF),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.width(22.dp)
            )

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Audio Boost (Night Mode)",
                    style = MaterialTheme.typography.labelLarge,
                    color = if (isEnabled) Color(0xFF00D9FF) else MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "Normalize loudness & boost dialogue",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                )
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun SubtitleDelayControl(
    delayMs: Long,
    onAdjust: (Long) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 8.dp, bottom = 12.dp)
            .background(Color.White.copy(alpha = 0.05f), RoundedCornerShape(8.dp))
            .padding(12.dp)
    ) {
        Text(
            text = "Subtitle Sync",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
            fontWeight = FontWeight.Bold
        )
        
        Spacer(modifier = Modifier.height(8.dp))

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Button(
                onClick = { onAdjust(-50L) },
                contentPadding = PaddingValues(horizontal = 12.dp),
                shape = ButtonDefaults.shape(RoundedCornerShape(4.dp)),
                colors = ButtonDefaults.colors(
                    containerColor = Color.White.copy(alpha = 0.1f),
                    contentColor = Color.White
                )
            ) {
                Text("-50ms", style = MaterialTheme.typography.labelSmall)
            }

            Text(
                text = if (delayMs == 0L) "Synced" else "${delayMs}ms",
                style = MaterialTheme.typography.labelLarge,
                color = if (delayMs != 0L) Color(0xFF00D9FF) else Color.White,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center
            )

            Button(
                onClick = { onAdjust(50L) },
                contentPadding = PaddingValues(horizontal = 12.dp),
                shape = ButtonDefaults.shape(RoundedCornerShape(4.dp)),
                colors = ButtonDefaults.colors(
                    containerColor = Color.White.copy(alpha = 0.1f),
                    contentColor = Color.White
                )
            ) {
                Text("+50ms", style = MaterialTheme.typography.labelSmall)
            }
        }
        
        if (delayMs != 0L) {
            Button(
                onClick = { onAdjust(-delayMs) },
                modifier = Modifier.align(Alignment.CenterHorizontally),
                scale = ButtonDefaults.scale(focusedScale = 1.05f),
                colors = ButtonDefaults.colors(
                    containerColor = Color.Transparent,
                    contentColor = Color.Gray
                )
            ) {
                Text("Reset to 0", style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}
