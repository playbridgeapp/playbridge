package com.playbridge.player.player

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
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.*

data class MpvTrack(
    val id: Int,
    val type: String,       // "video", "audio", "sub"
    val title: String,
    val lang: String?,
    val codec: String?,
    val isSelected: Boolean
)

private const val MPV_TAB_AUDIO    = 0
private const val MPV_TAB_SUBTITLE = 1
private const val MPV_TAB_SPEED    = 2

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun MpvTrackSelectionDialog(
    audioTracks: List<MpvTrack>,
    subtitleTracks: List<MpvTrack>,
    externalSubtitleUrls: List<String>,
    currentExternalSubtitleUrl: String?,
    currentPlaybackSpeed: Float,
    onDismiss: () -> Unit,
    onAudioTrackSelected: (Int?) -> Unit,
    onSubtitleTrackSelected: (Int?) -> Unit,
    onExternalSubtitleSelected: (String?) -> Unit,
    onPlaybackSpeedSelected: (Float) -> Unit
) {
    var selectedTab by remember { mutableStateOf(MPV_TAB_AUDIO) }

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
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(bottom = 8.dp, start = 4.dp)
                )
                MpvTabButton("Audio",     selectedTab == MPV_TAB_AUDIO)    { selectedTab = MPV_TAB_AUDIO }
                MpvTabButton("Subtitles", selectedTab == MPV_TAB_SUBTITLE) { selectedTab = MPV_TAB_SUBTITLE }
                MpvTabButton("Speed",     selectedTab == MPV_TAB_SPEED)    { selectedTab = MPV_TAB_SPEED }
            }

            // Divider
            Box(
                modifier = Modifier
                    .width(1.dp)
                    .fillMaxHeight()
                    .background(Color.Gray.copy(alpha = 0.3f))
            )

            when (selectedTab) {
                MPV_TAB_AUDIO -> MpvTrackList(
                    tracks = audioTracks,
                    onTrackSelected = onAudioTrackSelected
                )
                MPV_TAB_SUBTITLE -> MpvSubtitleList(
                    tracks = subtitleTracks,
                    externalUrls = externalSubtitleUrls,
                    currentExternalUrl = currentExternalSubtitleUrl,
                    onTrackSelected = onSubtitleTrackSelected,
                    onExternalSelected = onExternalSubtitleSelected
                )
                MPV_TAB_SPEED -> MpvSpeedList(
                    currentSpeed = currentPlaybackSpeed,
                    onSpeedSelected = onPlaybackSpeedSelected
                )
            }
        }
    }

    androidx.activity.compose.BackHandler { onDismiss() }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun MpvTabButton(text: String, isSelected: Boolean, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val bg = when {
        isSelected -> Color(0xFF4A90D9)
        focused    -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f)
        else       -> Color.Transparent
    }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(bg)
            .onKeyEvent { event ->
                if (event.type == KeyEventType.KeyDown && event.key == Key.DirectionCenter) {
                    onClick(); true
                } else false
            }
            .onFocusChanged { focused = it.isFocused }
            .focusable()
            .clickable { onClick() }
            .padding(horizontal = 10.dp, vertical = 8.dp)
    ) {
        Text(
            text = text,
            color = if (isSelected) MaterialTheme.colorScheme.onSurface else Color.LightGray,
            fontSize = 13.sp,
            fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun MpvTrackItem(name: String, isSelected: Boolean, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val bg = when {
        isSelected -> Color(0xFF4A90D9).copy(alpha = 0.3f)
        focused    -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)
        else       -> Color.Transparent
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .background(bg)
            .onKeyEvent { event ->
                if (event.type == KeyEventType.KeyDown && event.key == Key.DirectionCenter) {
                    onClick(); true
                } else false
            }
            .onFocusChanged { focused = it.isFocused }
            .focusable()
            .clickable { onClick() }
            .padding(horizontal = 10.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (isSelected) {
            Text("✓ ", color = Color(0xFF4A90D9), fontSize = 13.sp, fontWeight = FontWeight.Bold)
        }
        Text(
            text = name,
            color = if (isSelected) MaterialTheme.colorScheme.onSurface else Color.LightGray,
            fontSize = 13.sp,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun MpvTrackList(
    tracks: List<MpvTrack>,
    onTrackSelected: (Int?) -> Unit
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(start = 8.dp),
        contentPadding = PaddingValues(vertical = 4.dp)
    ) {
        if (tracks.isEmpty()) {
            item {
                Text("No tracks available", color = Color.Gray, fontSize = 13.sp,
                    modifier = Modifier.padding(12.dp))
            }
        } else {
            items(tracks) { track ->
                MpvTrackItem(
                    name = track.title,
                    isSelected = track.isSelected,
                    onClick = { onTrackSelected(track.id) }
                )
            }
        }
    }
}

@Composable
private fun MpvSubtitleList(
    tracks: List<MpvTrack>,
    externalUrls: List<String>,
    currentExternalUrl: String?,
    onTrackSelected: (Int?) -> Unit,
    onExternalSelected: (String?) -> Unit
) {
    val noSubSelected = tracks.none { it.isSelected } && currentExternalUrl == null
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(start = 8.dp),
        contentPadding = PaddingValues(vertical = 4.dp)
    ) {
        item {
            MpvTrackItem("Off", isSelected = noSubSelected) {
                onTrackSelected(null)
                onExternalSelected(null)
            }
        }

        if (tracks.isNotEmpty()) {
            item {
                Text("Embedded", color = Color.Gray, fontSize = 11.sp,
                    modifier = Modifier.padding(vertical = 6.dp, horizontal = 4.dp))
            }
            items(tracks) { track ->
                MpvTrackItem(track.title, isSelected = track.isSelected && currentExternalUrl == null) {
                    onExternalSelected(null)
                    onTrackSelected(track.id)
                }
            }
        }

        if (externalUrls.isNotEmpty()) {
            item {
                Text("External", color = Color.Gray, fontSize = 11.sp,
                    modifier = Modifier.padding(vertical = 6.dp, horizontal = 4.dp))
            }
            items(externalUrls) { url ->
                val name = try {
                    val path = android.net.Uri.parse(url).path ?: ""
                    val n = path.substringAfterLast('/')
                    if (n.isNotEmpty()) java.net.URLDecoder.decode(n, "UTF-8") else "Subtitle"
                } catch (e: Exception) { "Subtitle" }

                MpvTrackItem(name, isSelected = currentExternalUrl == url) {
                    onTrackSelected(null)
                    onExternalSelected(url)
                }
            }
        }
    }
}

@Composable
private fun MpvSpeedList(currentSpeed: Float, onSpeedSelected: (Float) -> Unit) {
    val speeds = listOf(
        0.5f to "0.5×",
        0.75f to "0.75×",
        1.0f to "1.0× (Normal)",
        1.25f to "1.25×",
        1.5f to "1.5×",
        2.0f to "2.0×"
    )
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(start = 8.dp),
        contentPadding = PaddingValues(vertical = 4.dp)
    ) {
        items(speeds) { (speed, label) ->
            MpvTrackItem(label, isSelected = speed == currentSpeed) { onSpeedSelected(speed) }
        }
    }
}
