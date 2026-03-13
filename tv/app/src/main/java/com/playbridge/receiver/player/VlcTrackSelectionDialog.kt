package com.playbridge.receiver.player

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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.*
import org.videolan.libvlc.MediaPlayer.TrackDescription

const val VLC_TAB_AUDIO = 1
const val VLC_TAB_SUBTITLE = 2
const val VLC_TAB_SPEED = 100
const val VLC_TAB_SCALING = 101

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun VlcTrackSelectionDialog(
    audioTracks: List<TrackDescription>,
    currentAudioTrack: Int,
    subtitleTracks: List<TrackDescription>,
    currentSubtitleTrack: Int,
    externalSubtitleUrls: List<String> = emptyList(),
    currentExternalSubtitleUrl: String? = null,
    currentPlaybackSpeed: Float = 1.0f,
    currentVideoScalingMode: String = "Fit", // e.g. "Fit", "Fill", "16:9", "4:3", "Center"
    onDismiss: () -> Unit,
    onAudioTrackSelected: (Int) -> Unit,
    onSubtitleTrackSelected: (Int) -> Unit,
    onExternalSubtitleSelected: (String?) -> Unit,
    onPlaybackSpeedSelected: (Float) -> Unit = {},
    onVideoScalingSelected: (String) -> Unit = {}
) {
    var selectedTab by remember { mutableStateOf(VLC_TAB_AUDIO) }

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
            // Sidebar tabs
            Column(
                modifier = Modifier
                    .width(100.dp)
                    .fillMaxHeight()
                    .padding(end = 8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = "Settings",
                    style = MaterialTheme.typography.titleSmall,
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(bottom = 8.dp, start = 4.dp)
                )

                TrackTypeButton(
                    text = "Audio",
                    isSelected = selectedTab == VLC_TAB_AUDIO,
                    onClick = { selectedTab = VLC_TAB_AUDIO }
                )
                TrackTypeButton(
                    text = "Subtitles",
                    isSelected = selectedTab == VLC_TAB_SUBTITLE,
                    onClick = { selectedTab = VLC_TAB_SUBTITLE }
                )
                TrackTypeButton(
                    text = "Speed",
                    isSelected = selectedTab == VLC_TAB_SPEED,
                    onClick = { selectedTab = VLC_TAB_SPEED }
                )
                TrackTypeButton(
                    text = "Scaling",
                    isSelected = selectedTab == VLC_TAB_SCALING,
                    onClick = { selectedTab = VLC_TAB_SCALING }
                )
            }

            // Divider
            Box(
                modifier = Modifier
                    .width(1.dp)
                    .fillMaxHeight()
                    .background(Color.Gray.copy(alpha = 0.3f))
            )

            // Track List
            when (selectedTab) {
                VLC_TAB_AUDIO -> {
                    VlcTrackList(
                        tracks = audioTracks,
                        currentTrackId = currentAudioTrack,
                        onTrackSelected = onAudioTrackSelected
                    )
                }
                VLC_TAB_SUBTITLE -> {
                    VlcSubtitleTrackList(
                        tracks = subtitleTracks,
                        currentTrackId = currentSubtitleTrack,
                        subtitleUrls = externalSubtitleUrls,
                        currentSubtitleUrl = currentExternalSubtitleUrl,
                        onTrackSelected = onSubtitleTrackSelected,
                        onExternalSubtitleSelected = onExternalSubtitleSelected
                    )
                }
                VLC_TAB_SPEED -> {
                    VlcPlaybackSpeedList(
                        currentSpeed = currentPlaybackSpeed,
                        onSpeedSelected = onPlaybackSpeedSelected
                    )
                }
                VLC_TAB_SCALING -> {
                    VlcVideoScalingList(
                        currentMode = currentVideoScalingMode,
                        onModeSelected = onVideoScalingSelected
                    )
                }
            }
        }
    }

    androidx.activity.compose.BackHandler { onDismiss() }
}

@Composable
fun VlcTrackList(
    tracks: List<TrackDescription>,
    currentTrackId: Int,
    onTrackSelected: (Int) -> Unit
) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(start = 8.dp),
        contentPadding = PaddingValues(vertical = 4.dp)
    ) {
        items(tracks) { track ->
            val isSelected = track.id == currentTrackId
            TrackItem(
                name = track.name ?: "Track ${track.id}",
                isSelected = isSelected,
                onClick = { onTrackSelected(track.id) }
            )
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

@Composable
fun VlcSubtitleTrackList(
    tracks: List<TrackDescription>,
    currentTrackId: Int,
    subtitleUrls: List<String>,
    currentSubtitleUrl: String?,
    onTrackSelected: (Int) -> Unit,
    onExternalSubtitleSelected: (String?) -> Unit
) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(start = 8.dp),
        contentPadding = PaddingValues(vertical = 4.dp)
    ) {
        // "Off" Option (VLC usually uses ID -1 for off)
        item {
            val isOffSelected = currentTrackId == -1 && currentSubtitleUrl == null
            TrackItem(
                name = "Off",
                isSelected = isOffSelected,
                onClick = {
                    onExternalSubtitleSelected(null)
                    onTrackSelected(-1)
                }
            )
        }

        // Embedded Tracks
        if (tracks.isNotEmpty()) {
            item {
                Text("Embedded", color = Color.Gray, fontSize = 11.sp,
                    modifier = Modifier.padding(vertical = 6.dp, horizontal = 4.dp))
            }
            items(tracks) { track ->
                // Skip the "Disable" track as we handle it above, usually it has ID -1
                if (track.id != -1) {
                    val isSelected = track.id == currentTrackId && currentSubtitleUrl == null
                    TrackItem(
                        name = track.name ?: "Subtitle ${track.id}",
                        isSelected = isSelected,
                        onClick = {
                            onExternalSubtitleSelected(null)
                            onTrackSelected(track.id)
                        }
                    )
                }
            }
        }

        // External Subtitles
        if (subtitleUrls.isNotEmpty()) {
            item {
                Text("External", color = Color.Gray, fontSize = 11.sp,
                    modifier = Modifier.padding(vertical = 6.dp, horizontal = 4.dp))
            }
            items(subtitleUrls) { url ->
                val filename = try {
                    val path = android.net.Uri.parse(url).path ?: ""
                    val name = path.substringAfterLast('/')
                    if (name.isNotEmpty()) java.net.URLDecoder.decode(name, "UTF-8") else "Subtitle"
                } catch (e: Exception) {
                    "Subtitle"
                }

                val isSelected = currentSubtitleUrl == url

                TrackItem(
                    name = filename,
                    isSelected = isSelected,
                    onClick = {
                        onTrackSelected(-1) // Disable embedded
                        onExternalSubtitleSelected(url)
                    }
                )
            }
        }
    }
}

@Composable
fun VlcPlaybackSpeedList(
    currentSpeed: Float,
    onSpeedSelected: (Float) -> Unit
) {
    val speeds = listOf(
        0.5f to "0.5x",
        0.75f to "0.75x",
        1.0f to "1.0x (Normal)",
        1.25f to "1.25x",
        1.5f to "1.5x",
        2.0f to "2.0x"
    )

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(start = 8.dp),
        contentPadding = PaddingValues(vertical = 4.dp)
    ) {
        items(speeds) { (speed, label) ->
            TrackItem(
                name = label,
                isSelected = speed == currentSpeed,
                onClick = { onSpeedSelected(speed) }
            )
        }
    }
}

@Composable
fun VlcVideoScalingList(
    currentMode: String,
    onModeSelected: (String) -> Unit
) {
    val modes = listOf(
        "Fit" to "Fit to Screen",
        "Fill" to "Crop to Fill",
        "16:9" to "16:9",
        "4:3" to "4:3",
        "Center" to "Center"
    )

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(start = 8.dp),
        contentPadding = PaddingValues(vertical = 4.dp)
    ) {
        items(modes) { (mode, label) ->
            TrackItem(
                name = label,
                isSelected = mode == currentMode,
                onClick = { onModeSelected(mode) }
            )
        }
    }
}