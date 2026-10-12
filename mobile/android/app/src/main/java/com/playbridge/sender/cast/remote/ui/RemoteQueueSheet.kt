package com.playbridge.sender.cast.remote.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.playbridge.sender.cast.remote.RemoteAction
import com.playbridge.sender.cast.remote.RemoteDialogVolumeKeys
import com.playbridge.sender.cast.remote.RemoteProfile
import com.playbridge.sender.cast.remote.RemoteQueueItem
import com.playbridge.sender.cast.remote.RemoteUiState
import com.playbridge.sender.cast.remote.RemoteTitlePolicy
import com.playbridge.sender.cast.remote.Support

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
internal fun RemoteQueueSheet(
    state: RemoteUiState,
    dispatch: (RemoteAction) -> Unit,
) {
    val items = state.queue?.items.orEmpty()
    val currentIndex = state.queue?.currentIndex ?: 0
    val external = state.receiver?.profile == RemoteProfile.External
    val canJump = !external && (state.capabilities.queue.jumpIndex != Support.Unsupported)

    val currentEpisode = items.firstOrNull { it.index == currentIndex }
    val seasons = remember(items) { items.mapNotNull { it.season }.distinct().sorted() }
    var selectedSeason by remember(items) { mutableStateOf<Int?>(currentEpisode?.season ?: seasons.firstOrNull()) }

    val displayedItems = remember(items, selectedSeason) {
        if (selectedSeason != null && seasons.size > 1) {
            items.filter { it.season == selectedSeason }
        } else {
            items
        }
    }

    ModalBottomSheet(onDismissRequest = { dispatch(RemoteAction.CloseSheet) }) {
        RemoteDialogVolumeKeys()
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 24.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = "Up next (${items.size})",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
            }

            if (seasons.size > 1) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 20.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    FilterChip(
                        selected = selectedSeason == null,
                        onClick = { selectedSeason = null },
                        label = { Text("All") },
                    )
                    seasons.forEach { s ->
                        FilterChip(
                            selected = selectedSeason == s,
                            onClick = { selectedSeason = s },
                            label = { Text("Season $s") },
                        )
                    }
                }
            }

            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 480.dp)
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                items(displayedItems, key = { it.itemId ?: "${it.index}_${it.title}" }) { ep ->
                    val isCurrent = if (state.queue?.currentItemId != null) ep.itemId == state.queue.currentItemId else ep.index == currentIndex
                    val mutations = resolveQueueMutations(state, ep)
                    val watchStatus = resolveEpisodeWatchStatus(ep, state)
                    var menuExpanded by remember { mutableStateOf(false) }

                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .combinedClickable(
                                enabled = LocalRemoteControlsEnabled.current && canJump,
                                onClick = {
                                    dispatch(RemoteAction.JumpToEpisode(ep.index, ep.itemId))
                                    dispatch(RemoteAction.CloseSheet)
                                },
                                onLongClick = {
                                    if (mutations.remove || mutations.playNext) {
                                        menuExpanded = true
                                    }
                                }
                            ),
                        shape = RoundedCornerShape(12.dp),
                        color = if (isCurrent) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f) else Color.Transparent,
                    ) {
                        Box {
                            Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                                ) {
                                    Box(
                                        modifier = Modifier.size(28.dp),
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        when {
                                            isCurrent -> Icon(
                                                Icons.Default.PlayArrow,
                                                contentDescription = "Currently playing",
                                                tint = MaterialTheme.colorScheme.primary,
                                                modifier = Modifier.size(20.dp),
                                            )
                                            watchStatus is EpisodeWatchStatus.Watched -> Icon(
                                                Icons.Default.Check,
                                                contentDescription = "Watched",
                                                tint = MaterialTheme.colorScheme.secondary,
                                                modifier = Modifier.size(18.dp),
                                            )
                                            else -> Text(
                                                text = "${ep.index + 1}",
                                                style = MaterialTheme.typography.labelMedium,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                fontWeight = FontWeight.SemiBold,
                                            )
                                        }
                                    }

                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = RemoteTitlePolicy.episodeTitle(ep),
                                            style = MaterialTheme.typography.bodyMedium,
                                            fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Normal,
                                            color = if (isCurrent) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                        val subtitle = formatEpisodeBadge(ep.season, ep.episode, ep.index)
                                        if (subtitle.isNotBlank()) {
                                            Text(
                                                text = subtitle,
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                        }
                                    }

                                    if (mutations.remove || mutations.playNext) {
                                        IconButton(
                                            onClick = { menuExpanded = true },
                                            enabled = LocalRemoteControlsEnabled.current,
                                            modifier = Modifier.size(48.dp),
                                        ) {
                                            Icon(
                                                Icons.Default.MoreVert,
                                                contentDescription = "Episode options",
                                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                        }
                                    }
                                }

                                if (watchStatus is EpisodeWatchStatus.InProgress) {
                                    Spacer(Modifier.height(6.dp))
                                    LinearProgressIndicator(
                                        progress = { watchStatus.progress },
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(3.dp)
                                            .clip(RoundedCornerShape(1.5.dp)),
                                        color = MaterialTheme.colorScheme.primary,
                                        trackColor = MaterialTheme.colorScheme.surfaceVariant,
                                    )
                                }
                            }

                            if (mutations.remove || mutations.playNext) {
                                DropdownMenu(
                                    expanded = menuExpanded,
                                    onDismissRequest = { menuExpanded = false },
                                ) {
                                    if (mutations.playNext) {
                                        DropdownMenuItem(
                                            enabled = LocalRemoteControlsEnabled.current,
                                            text = { Text("Play next") },
                                            leadingIcon = { Icon(Icons.Default.PlayArrow, null) },
                                            onClick = {
                                                menuExpanded = false
                                                dispatch(RemoteAction.PlayNext(requireNotNull(ep.itemId)))
                                            },
                                        )
                                    }
                                    if (mutations.remove) DropdownMenuItem(
                                        enabled = LocalRemoteControlsEnabled.current,
                                        text = { Text("Remove") },
                                        leadingIcon = { Icon(Icons.Default.Delete, null) },
                                        onClick = {
                                            menuExpanded = false
                                            dispatch(RemoteAction.RemoveQueueItem(requireNotNull(ep.itemId)))
                                        },
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
