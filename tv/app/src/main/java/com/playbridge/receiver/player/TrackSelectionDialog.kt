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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.tv.material3.*
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.Tracks

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun TrackSelectionDialog(
    tracks: Tracks,
    trackSelectionParameters: androidx.media3.common.TrackSelectionParameters,
    subtitleUrls: List<String> = emptyList(),
    currentSubtitleUrl: String? = null,
    onDismiss: () -> Unit,
    onTrackSelected: (Int, Format?) -> Unit, // for embedded tracks
    onExternalSubtitleSelected: (String?) -> Unit, // for manual subtitles
    onPreviewRequest: suspend (String) -> String? = { null }
) {
    var selectedTab by remember { mutableStateOf(C.TRACK_TYPE_VIDEO) }
    
    Dialog(onDismissRequest = onDismiss) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(32.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(Color(0xFF1E1E2E)) // Dark background
                .padding(16.dp)
        ) {
            // Sidebar for Track Types
            Column(
                modifier = Modifier
                    .width(200.dp)
                    .fillMaxHeight()
                    .padding(end = 16.dp)
            ) {
                Text(
                    text = "Tracks",
                    style = MaterialTheme.typography.titleLarge,
                    color = Color.White,
                    modifier = Modifier.padding(bottom = 16.dp)
                )
                
                TrackTypeButton(
                    text = "Video / Quality",
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
                
                Spacer(modifier = Modifier.weight(1f))
                
                Button(
                    onClick = onDismiss,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Close")
                }
            }
            
            // Divider
            Box(
                modifier = Modifier
                    .width(1.dp)
                    .fillMaxHeight()
                    .background(Color.Gray.copy(alpha = 0.5f))
            )
            
            // Track List
            if (selectedTab == C.TRACK_TYPE_TEXT) {
                SubtitleTrackList(
                    tracks = tracks,
                    trackSelectionParameters = trackSelectionParameters,
                    subtitleUrls = subtitleUrls,
                    currentSubtitleUrl = currentSubtitleUrl,
                    onTrackSelected = onTrackSelected,
                    onExternalSubtitleSelected = onExternalSubtitleSelected,
                    onPreviewRequest = onPreviewRequest
                )
            } else {
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

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun TrackTypeButton(
    text: String,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    var isFocused by remember { mutableStateOf(false) }
    
    val backgroundColor = when {
        isSelected -> MaterialTheme.colorScheme.primary
        isFocused -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f)
        else -> Color.Transparent
    }
    
    val contentColor = if (isSelected) MaterialTheme.colorScheme.onPrimary else Color.White
    
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(backgroundColor)
            .onFocusChanged { isFocused = it.isFocused }
            .clickable(onClick = onClick)
            .focusable()
            .padding(12.dp)
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = contentColor,
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
    // 1. Off / None option
    // If no external URL is selected AND track selection disabled, then "Off" is selected.
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
    
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(start = 16.dp),
        contentPadding = PaddingValues(vertical = 8.dp)
    ) {
        // "Off" Option
        item {
            TrackItem(
                name = "Off",
                isSelected = isOffSelected,
                onClick = { 
                    onExternalSubtitleSelected(null) // Clear external
                    onTrackSelected(C.TRACK_TYPE_TEXT, null) // Setup internal to "Off" (or disabled)
                },
                onFocus = { focusedSubtitleUrl = null }
            )
        }
        
        // 2. Embedded Tracks
        val trackGroups = tracks.groups.filter { it.type == C.TRACK_TYPE_TEXT }
        
        // Find active override for embedded
        var activeOverride: androidx.media3.common.TrackSelectionOverride? = null
        for (group in trackGroups) {
             val override = trackSelectionParameters.overrides[group.mediaTrackGroup]
             if (override != null) {
                 activeOverride = override
                 break
             }
        }

        if (trackGroups.isNotEmpty()) {
            item {
                Text("Embedded", color = Color.Gray, modifier = Modifier.padding(vertical = 8.dp))
            }
            trackGroups.forEach { group ->
                for (i in 0 until group.length) {
                    val format = group.getTrackFormat(i)
                    val isSelected = activeOverride != null && 
                                     activeOverride.mediaTrackGroup == group.mediaTrackGroup &&
                                     activeOverride.trackIndices.contains(i) &&
                                     currentSubtitleUrl == null // Ensure external is not overriding this visual
                    
                    val name = buildTrackName(format)
                    item {
                        TrackItem(
                            name = name,
                            isSelected = isSelected,
                            onClick = {
                                onExternalSubtitleSelected(null) // Clear external
                                onTrackSelected(C.TRACK_TYPE_TEXT, format) // Select internal
                            },
                            onFocus = { focusedSubtitleUrl = null }
                        )
                    }
                }
            }
        }
        
        // 3. External Subtitles
        if (subtitleUrls.isNotEmpty()) {
            item {
                Text("External", color = Color.Gray, modifier = Modifier.padding(vertical = 8.dp))
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
                        onExternalSubtitleSelected(url) // Set external
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
        
        // Check if Text is Disabled
        val isTextDisabled = trackType == C.TRACK_TYPE_TEXT && 
                             trackSelectionParameters.disabledTrackTypes.contains(C.TRACK_TYPE_TEXT)
        
        // Check for specific overrides in any of the groups for this type
        // In Media3 1.0+, overrides is a Map<TrackGroup, TrackSelectionOverride>
        var activeOverride: androidx.media3.common.TrackSelectionOverride? = null
        for (group in trackGroups) {
             val override = trackSelectionParameters.overrides[group.mediaTrackGroup]
             if (override != null) {
                 activeOverride = override
                 break
             }
        }
        
        // "Auto" / "Off" Logic
        
        @Suppress("UnnecessaryVariable")
        val defaultName = if (trackType == C.TRACK_TYPE_TEXT) "Off" else "Auto / Default"
        
        val isDefaultSelected = if (trackType == C.TRACK_TYPE_TEXT) {
             isTextDisabled
        } else {
             activeOverride == null
        }
        
        list.add(SelectableFormat(defaultName, null, isDefaultSelected))
        
        trackGroups.forEach { group ->
            for (i in 0 until group.length) {
                val format = group.getTrackFormat(i)
                
                // Determine if this specific track is the one selected by the override
                val isSelected = activeOverride != null && 
                                 activeOverride.mediaTrackGroup == group.mediaTrackGroup &&
                                 activeOverride.trackIndices.contains(i)
                
                val name = buildTrackName(format)
                list.add(SelectableFormat(name, format, isSelected))
            }
        }
        list
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(start = 16.dp),
        contentPadding = PaddingValues(vertical = 8.dp)
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
                    modifier = Modifier.padding(16.dp)
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
    
    val backgroundColor = if (isFocused) MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f) else Color.Transparent
    
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(backgroundColor)
            .onFocusChanged { 
                isFocused = it.isFocused 
                if (isFocused) onFocus()
            }
            .clickable(onClick = onClick)
            .focusable()
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Checkmark
        if (isSelected) {
            Text("✓ ", color = MaterialTheme.colorScheme.primary)
        } else {
            Spacer(modifier = Modifier.width(18.dp)) // Approximate width of checkmark
        }
        
        Column {
            Text(
                text = name,
                style = MaterialTheme.typography.bodyMedium,
                color = if (isSelected) MaterialTheme.colorScheme.primary else Color.White
            )
            
            if (previewText != null) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = previewText,
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.LightGray,
                    maxLines = 5
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
    
    // Fallback if empty
    if (items.isEmpty()) {
        return format.id ?: "Unknown Track"
    }
    
    return items.joinToString(" • ")
}
