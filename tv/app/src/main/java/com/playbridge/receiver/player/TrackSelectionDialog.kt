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
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.Tracks
import androidx.media3.ui.AspectRatioFrameLayout

const val TAB_SPEED = 100
const val TAB_SCALING = 101

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun TrackSelectionDialog(
    tracks: Tracks,
    trackSelectionParameters: androidx.media3.common.TrackSelectionParameters,
    subtitleUrls: List<String> = emptyList(),
    currentSubtitleUrl: String? = null,
    currentPlaybackSpeed: Float = 1.0f,
    currentVideoScalingMode: Int = AspectRatioFrameLayout.RESIZE_MODE_FIT,
    onDismiss: () -> Unit,
    onTrackSelected: (Int, Format?) -> Unit,
    onExternalSubtitleSelected: (String?) -> Unit,
    onPreviewRequest: suspend (String) -> String? = { null },
    onPlaybackSpeedSelected: (Float) -> Unit = {},
    onVideoScalingSelected: (Int) -> Unit = {}
) {
    var selectedTab by remember { mutableStateOf(C.TRACK_TYPE_VIDEO) }

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
                    text = "Video",
                    isSelected = selectedTab == C.TRACK_TYPE_VIDEO,
                    onClick = { selectedTab = C.TRACK_TYPE_VIDEO }
                )
                TrackTypeButton(
                    text = "Audio",
                    isSelected = selectedTab == C.TRACK_TYPE_AUDIO,
                    onClick = { selectedTab = C.TRACK_TYPE_AUDIO }
                )
                TrackTypeButton(
                    text = "Subtitles",
                    isSelected = selectedTab == C.TRACK_TYPE_TEXT,
                    onClick = { selectedTab = C.TRACK_TYPE_TEXT }
                )
                TrackTypeButton(
                    text = "Speed",
                    isSelected = selectedTab == TAB_SPEED,
                    onClick = { selectedTab = TAB_SPEED }
                )
                TrackTypeButton(
                    text = "Scaling",
                    isSelected = selectedTab == TAB_SCALING,
                    onClick = { selectedTab = TAB_SCALING }
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
                C.TRACK_TYPE_TEXT -> {
                    SubtitleTrackList(
                        tracks = tracks,
                        trackSelectionParameters = trackSelectionParameters,
                        subtitleUrls = subtitleUrls,
                        currentSubtitleUrl = currentSubtitleUrl,
                        onTrackSelected = onTrackSelected,
                        onExternalSubtitleSelected = onExternalSubtitleSelected,
                        onPreviewRequest = onPreviewRequest
                    )
                }
                TAB_SPEED -> {
                    PlaybackSpeedList(
                        currentSpeed = currentPlaybackSpeed,
                        onSpeedSelected = onPlaybackSpeedSelected
                    )
                }
                TAB_SCALING -> {
                    VideoScalingList(
                        currentMode = currentVideoScalingMode,
                        onModeSelected = onVideoScalingSelected
                    )
                }
                else -> {
                    TrackList(
                        tracks = tracks,
                        trackSelectionParameters = trackSelectionParameters,
                        trackType = selectedTab,
                        onTrackSelected = { format -> onTrackSelected(selectedTab, format) }
                    )
                }
            }
        }
    }

    androidx.activity.compose.BackHandler { onDismiss() }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun TrackTypeButton(
    text: String,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    var isFocused by remember { mutableStateOf(false) }

    val backgroundColor = when {
        isSelected -> Color(0xFF00D9FF).copy(alpha = 0.2f)
        isFocused -> Color.White.copy(alpha = 0.1f)
        else -> Color.Transparent
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(backgroundColor)
            .onFocusChanged { isFocused = it.isFocused }
            .clickable(onClick = onClick)
            .focusable()
            .padding(horizontal = 8.dp, vertical = 10.dp)
    ) {
        Text(
            text = text,
            fontSize = 13.sp,
            color = if (isSelected) Color(0xFF00D9FF) else Color.White,
            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
        )
    }
}

@Composable
fun SubtitleTrackList(
    tracks: Tracks,
    trackSelectionParameters: androidx.media3.common.TrackSelectionParameters,
    subtitleUrls: List<String>,
    currentSubtitleUrl: String?,
    onTrackSelected: (Int, Format?) -> Unit,
    onExternalSubtitleSelected: (String?) -> Unit,
    onPreviewRequest: suspend (String) -> String?
) {
    val isTextDisabled = trackSelectionParameters.disabledTrackTypes.contains(C.TRACK_TYPE_TEXT)
    val isOffSelected = isTextDisabled && currentSubtitleUrl == null

    var focusedSubtitleUrl by remember { mutableStateOf<String?>(null) }
    var previewText by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(focusedSubtitleUrl) {
        if (focusedSubtitleUrl != null) {
            previewText = "Loading preview..."
            previewText = onPreviewRequest(focusedSubtitleUrl!!)
        } else {
            previewText = null
        }
    }

    // Embedded Tracks
    val embeddedFormats = remember(tracks, trackSelectionParameters) {
        val groups = tracks.groups.filter { it.type == C.TRACK_TYPE_TEXT }
        var override: androidx.media3.common.TrackSelectionOverride? = null
        for (group in groups) {
            val o = trackSelectionParameters.overrides[group.mediaTrackGroup]
            if (o != null) {
                override = o
                break
            }
        }

        val list = mutableListOf<SelectableFormat>()
        groups.forEach { group ->
            for (i in 0 until group.length) {
                val format = group.getTrackFormat(i)
                val isSelected = if (override != null) {
                    override.mediaTrackGroup == group.mediaTrackGroup &&
                    override.trackIndices.contains(i)
                } else {
                    group.isTrackSelected(i)
                }
                val name = buildTrackName(format)
                list.add(SelectableFormat(name, format, isSelected))
            }
        }
        list
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(start = 8.dp),
        contentPadding = PaddingValues(vertical = 4.dp)
    ) {
        // "Off" Option
        item {
            TrackItem(
                name = "Off",
                isSelected = isOffSelected,
                onClick = {
                    onExternalSubtitleSelected(null)
                    onTrackSelected(C.TRACK_TYPE_TEXT, null)
                },
                onFocus = { focusedSubtitleUrl = null }
            )
        }

        if (embeddedFormats.isNotEmpty()) {
            item {
                Text("Embedded", color = Color.Gray, fontSize = 11.sp,
                    modifier = Modifier.padding(vertical = 6.dp, horizontal = 4.dp))
            }
            items(embeddedFormats) { item ->
                TrackItem(
                    name = item.name,
                    isSelected = item.isSelected && currentSubtitleUrl == null,
                    onClick = {
                        onExternalSubtitleSelected(null)
                        onTrackSelected(C.TRACK_TYPE_TEXT, item.format)
                    },
                    onFocus = { focusedSubtitleUrl = null }
                )
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
                val isFocused = focusedSubtitleUrl == url

                TrackItem(
                    name = filename,
                    isSelected = isSelected,
                    onClick = {
                        onExternalSubtitleSelected(url)
                    },
                    onFocus = { focusedSubtitleUrl = url },
                    previewText = if (isFocused) previewText else null
                )
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun TrackList(
    tracks: Tracks,
    trackSelectionParameters: androidx.media3.common.TrackSelectionParameters,
    trackType: Int,
    onTrackSelected: (Format?) -> Unit
) {
    val trackGroups = remember(tracks, trackType) {
        tracks.groups.filter { it.type == trackType }
    }

    val formats = remember(trackGroups, trackSelectionParameters) {
        val list = mutableListOf<SelectableFormat>()

        val isTextDisabled = trackType == C.TRACK_TYPE_TEXT &&
                             trackSelectionParameters.disabledTrackTypes.contains(C.TRACK_TYPE_TEXT)

        var activeOverride: androidx.media3.common.TrackSelectionOverride? = null
        for (group in trackGroups) {
             val override = trackSelectionParameters.overrides[group.mediaTrackGroup]
             if (override != null) {
                 activeOverride = override
                 break
             }
        }

        val hasPreferredLanguage = when (trackType) {
            C.TRACK_TYPE_AUDIO -> trackSelectionParameters.preferredAudioLanguages.isNotEmpty()
            C.TRACK_TYPE_TEXT -> trackSelectionParameters.preferredTextLanguages.isNotEmpty()
            else -> false
        }

        var hasPlayerSelectedTrack = false
        if (activeOverride == null && hasPreferredLanguage) {
            for (group in trackGroups) {
                for (i in 0 until group.length) {
                    if (group.isTrackSelected(i)) {
                        hasPlayerSelectedTrack = true
                        break
                    }
                }
                if (hasPlayerSelectedTrack) break
            }
        }

        @Suppress("UnnecessaryVariable")
        val defaultName = if (trackType == C.TRACK_TYPE_TEXT) "Off" else "Auto / Default"

        val isDefaultSelected = if (trackType == C.TRACK_TYPE_TEXT) {
             isTextDisabled
        } else {
             activeOverride == null && !hasPlayerSelectedTrack
        }

        list.add(SelectableFormat(defaultName, null, isDefaultSelected))

        trackGroups.forEach { group ->
            for (i in 0 until group.length) {
                val format = group.getTrackFormat(i)

                val isSelected = if (activeOverride != null) {
                    activeOverride.mediaTrackGroup == group.mediaTrackGroup &&
                    activeOverride.trackIndices.contains(i)
                } else if (hasPreferredLanguage) {
                    group.isTrackSelected(i)
                } else {
                    false
                }

                val name = buildTrackName(format)
                list.add(SelectableFormat(name, format, isSelected))
            }
        }
        list
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(start = 8.dp),
        contentPadding = PaddingValues(vertical = 4.dp)
    ) {
        items(formats) { item ->
            TrackItem(
                name = item.name,
                isSelected = item.isSelected,
                onClick = { onTrackSelected(item.format) }
            )
        }

        if (formats.isEmpty()) {
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

data class SelectableFormat(val name: String, val format: Format?, val isSelected: Boolean)

@Composable
fun TrackItem(
    name: String,
    isSelected: Boolean,
    onClick: () -> Unit,
    onFocus: () -> Unit = {},
    previewText: String? = null
) {
    var isFocused by remember { mutableStateOf(false) }

    val backgroundColor = when {
        isFocused -> Color.White.copy(alpha = 0.1f)
        else -> Color.Transparent
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 1.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(backgroundColor)
            .onFocusChanged {
                isFocused = it.isFocused
                if (isFocused) onFocus()
            }
            .clickable(onClick = onClick)
            .focusable()
            .padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Checkmark
        Text(
            text = if (isSelected) "✓" else " ",
            color = Color(0xFF00D9FF),
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.width(20.dp)
        )

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = name,
                fontSize = 13.sp,
                color = if (isSelected) Color(0xFF00D9FF) else Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            if (previewText != null) {
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = previewText,
                    fontSize = 11.sp,
                    color = Color.LightGray,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

fun buildTrackName(format: Format): String {
    val items = mutableListOf<String>()

    if (format.height != Format.NO_VALUE) {
        items.add("${format.height}p")
    }

    format.label?.let { if (it.isNotEmpty()) items.add(it) }
    format.language?.let { if (it.isNotEmpty()) items.add(it.uppercase()) }

    if (format.bitrate != Format.NO_VALUE) {
        items.add("${format.bitrate / 1000} kbps")
    }

    if (items.isEmpty()) {
        return format.id ?: "Unknown Track"
    }

    return items.joinToString(" • ")
}

@Composable
fun PlaybackSpeedList(
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
fun VideoScalingList(
    currentMode: Int,
    onModeSelected: (Int) -> Unit
) {
    val modes = listOf(
        androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIT to "Fit",
        androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FILL to "Fill",
        androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_ZOOM to "Zoom",
        androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIXED_WIDTH to "Fixed Width",
        androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIXED_HEIGHT to "Fixed Height"
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
