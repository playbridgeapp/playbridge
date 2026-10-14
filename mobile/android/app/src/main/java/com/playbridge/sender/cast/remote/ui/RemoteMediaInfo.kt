package com.playbridge.sender.cast.remote.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.playbridge.sender.cast.MediaTrack
import com.playbridge.sender.cast.remote.RemoteAction
import com.playbridge.sender.cast.remote.RemoteContext
import com.playbridge.sender.cast.remote.RemoteLayoutSpec
import com.playbridge.sender.cast.remote.remotePressFeedback
import com.playbridge.sender.cast.remote.RemoteTitlePolicy
import com.playbridge.sender.cast.remote.RemoteSheet
import com.playbridge.sender.cast.remote.RemoteUiState

@Composable
private fun NowPlayingPanel(
    title: String?,
    subtitle: String? = null,
) {
    Column(modifier = Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = title ?: "Playing on TV",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            maxLines = 2,
            textAlign = TextAlign.Center
        )
        if (subtitle != null) Text(
            text = subtitle,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
internal fun TrackChipsRow(
    audioTracks: List<MediaTrack>,
    subtitleTracks: List<MediaTrack>,
    showSubtitles: Boolean,
    onSelectAudio: (String) -> Unit,
    onSubtitlesClick: () -> Unit,
    onMore: () -> Unit,
    showMore: Boolean = true,
) {
    val subtitleInteraction = remember { MutableInteractionSource() }
    val moreInteraction = remember { MutableInteractionSource() }
    val trackCount = (if (audioTracks.isNotEmpty()) 1 else 0) + (if (showSubtitles) 1 else 0)
    // Two track chips keep sharing the row. One chip (or More alone) stays intrinsic:
    // at least 48 dp and at most about a third of the row, so a lone "Off" does not fill it.
    BoxWithConstraints(Modifier.fillMaxWidth()) {
    val cap = maxWidth / 3
    val share = trackCount > 1
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        val chipWidth = if (share) Modifier.weight(1f) else Modifier.widthIn(min = 48.dp, max = cap)
        if (audioTracks.isNotEmpty()) {
            TrackChip(icon = Icons.Default.VolumeUp, label = "Audio", tracks = audioTracks, onSelect = onSelectAudio,
                expand = share, modifier = chipWidth)
        }
        if (showSubtitles) {
            val selected = subtitleTracks.firstOrNull { it.selected }
            val selectedName = selected?.let { subtitleOptionLabel(it, subtitleTracks) } ?: "Off"
            Surface(enabled = LocalRemoteControlsEnabled.current,
                interactionSource = subtitleInteraction,
                onClick = onSubtitlesClick,
                shape = RoundedCornerShape(20.dp),
                color = MaterialTheme.colorScheme.surfaceVariant,
                modifier = chipWidth.heightIn(min = 48.dp).remotePressFeedback(subtitleInteraction)
                    .semantics { contentDescription = "Subtitles: $selectedName" }
            ) {
                Row(modifier = Modifier.padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Icon(Icons.Default.Subtitles, contentDescription = null, modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(text = selectedName, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurface,
                        modifier = if (share) Modifier.weight(1f) else Modifier)
                }
            }
        }
        if (trackCount == 0) {
            Spacer(modifier = Modifier.weight(1f))
        }
        if (showMore) Surface(enabled = LocalRemoteControlsEnabled.current,
            interactionSource = moreInteraction,
            onClick = onMore,
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surfaceVariant,
            modifier = Modifier.heightIn(min = 48.dp).remotePressFeedback(moreInteraction)
        ) {
            Row(modifier = Modifier.padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Icon(Icons.Default.Tune, contentDescription = "More", modifier = Modifier.size(18.dp))
                Text("More", style = MaterialTheme.typography.labelLarge)
            }
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
    expand: Boolean,
    modifier: Modifier = Modifier
) {
    val interaction = remember { MutableInteractionSource() }
    var expanded by remember { mutableStateOf(false) }
    val selected = tracks.firstOrNull { it.selected }
    val selectedName = selected?.let { audioOptionLabel(it, tracks) } ?: label

    Box(modifier = modifier) {
        Row(
            modifier = Modifier
                .then(if (expand) Modifier.fillMaxWidth() else Modifier)
                .clip(RoundedCornerShape(20.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .remotePressFeedback(interaction)
                .clickable(enabled = LocalRemoteControlsEnabled.current, interactionSource = interaction, indication = null) { expanded = true }
                .semantics { contentDescription = "$label track: $selectedName" }
                .heightIn(min = 48.dp)
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(text = selectedName, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurface,
                modifier = if (expand) Modifier.weight(1f) else Modifier)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            tracks.forEach { track ->
                DropdownMenuItem(enabled = LocalRemoteControlsEnabled.current,
                    text = { Text(audioOptionLabel(track, tracks)) },
                    onClick = { onSelect(track.id); expanded = false },
                    leadingIcon = if (track.selected) { { Icon(Icons.Default.Check, contentDescription = "Selected") } } else null
                )
            }
        }
    }
}

/** Stage 3c owns title/subtitle/chips content; the INFO host belongs to 3a. */
@Composable
internal fun RemoteMediaInfo(state: RemoteUiState, layout: RemoteLayoutSpec, dispatch: (RemoteAction) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        val browser = state.context == RemoteContext.Browser
        val heading = if (browser) RemoteTitlePolicy.browserHeading(state.playback?.title) else RemoteTitlePolicy.clean(state.playback?.title)
        if (!browser || heading != null) NowPlayingPanel(heading, subtitle = if (browser) null else RemoteTitlePolicy.subtitle(state))
        if (state.context == RemoteContext.Player) TrackChipsRow(
            audioTracks = if (layout.tracks.audio) state.tracks.audio.map { it.screenTrack() } else emptyList(),
            subtitleTracks = if (layout.tracks.subtitles) state.tracks.subtitles.map { it.screenTrack() } else emptyList(),
            showSubtitles = layout.tracks.subtitles,
            onSelectAudio = { dispatch(RemoteAction.SelectAudio(it)) },
            onSubtitlesClick = { dispatch(RemoteAction.OpenSheet(RemoteSheet.Subtitles)) },
            onMore = { dispatch(RemoteAction.OpenSheet(RemoteSheet.More)) },
            showMore = layout.tracks.more,
        )
    }
}
