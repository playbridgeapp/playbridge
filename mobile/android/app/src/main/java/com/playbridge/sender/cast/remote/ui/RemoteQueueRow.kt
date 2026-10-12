package com.playbridge.sender.cast.remote.ui

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.playbridge.sender.cast.remote.RemoteAction
import com.playbridge.sender.cast.remote.RemoteQueueItem
import com.playbridge.sender.cast.remote.RemoteSheet
import com.playbridge.sender.cast.remote.RemoteUiState
import com.playbridge.sender.cast.remote.RemoteTitlePolicy

/**
 * Up next row: compact keyed cards (~96–112 dp tall), current episode highlighted
 * and auto-scrolled into view with its neighbours without resetting manual scroll.
 */
@Composable
internal fun RemoteQueueRow(
    state: RemoteUiState,
    collapsed: Boolean,
    dispatch: (RemoteAction) -> Unit,
) {
    val items = state.queue?.items.orEmpty()
    if (items.size <= 1) return

    if (collapsed) {
        TextButton(
            enabled = LocalRemoteControlsEnabled.current,
            onClick = { dispatch(RemoteAction.OpenSheet(RemoteSheet.Queue)) },
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp)
                .testTag("remote.upNext.compact"),
        ) {
            Text(
                "Up next (${items.size}) ›",
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    } else {
        QueueEpisodes(state, items, dispatch)
    }
}

@Composable
private fun QueueEpisodes(
    state: RemoteUiState,
    items: List<RemoteQueueItem>,
    dispatch: (RemoteAction) -> Unit,
) {
    val currentIndex = state.queue?.currentIndex ?: 0
    val listState = rememberLazyListState()

    // Auto-scroll into view with neighbours ONLY when currentIndex changes,
    // preserving user's manual scroll across periodic 1s status updates.
    var lastObservedIndex by remember { mutableStateOf<Int?>(null) }
    LaunchedEffect(currentIndex) {
        if (lastObservedIndex != currentIndex) {
            lastObservedIndex = currentIndex
            val pos = items.indexOfFirst { it.index == currentIndex }
            if (pos >= 0) {
                val targetScroll = (pos - 1).coerceAtLeast(0)
                listState.animateScrollToItem(targetScroll)
            }
        }
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = "Up Next",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            TextButton(
                enabled = LocalRemoteControlsEnabled.current,
                onClick = { dispatch(RemoteAction.OpenSheet(RemoteSheet.Queue)) },
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
            ) {
                Text(
                    text = "All (${items.size}) ›",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }

        LazyRow(
            state = listState,
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(vertical = 2.dp),
        ) {
            items(items, key = { it.itemId ?: "${it.index}_${it.title}" }) { ep ->
                val isCurrent = ep.index == currentIndex
                val watchStatus = resolveEpisodeWatchStatus(ep, state)

                Surface(
                    modifier = Modifier
                        .width(140.dp)
                        .height(100.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .border(
                            width = if (isCurrent) 1.5.dp else 0.dp,
                            color = if (isCurrent) MaterialTheme.colorScheme.primary else Color.Transparent,
                            shape = RoundedCornerShape(12.dp),
                        )
                        .clickable(enabled = LocalRemoteControlsEnabled.current) {
                            dispatch(RemoteAction.JumpToEpisode(ep.index, ep.itemId))
                        },
                    shape = RoundedCornerShape(12.dp),
                    color = if (isCurrent) {
                        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f)
                    } else {
                        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                    },
                ) {
                    Column(
                        modifier = Modifier
                            .padding(10.dp),
                        verticalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            val badgeText = formatEpisodeBadge(ep.season, ep.episode, ep.index)
                            Text(
                                text = badgeText,
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = if (isCurrent) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                            )
                            if (watchStatus is EpisodeWatchStatus.Watched) {
                                Icon(
                                    Icons.Default.Check,
                                    contentDescription = "Watched",
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(16.dp),
                                )
                            } else if (isCurrent) {
                                Text(
                                    text = "▶",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary,
                                    fontWeight = FontWeight.Bold,
                                )
                            }
                        }

                        Text(
                            text = RemoteTitlePolicy.episodeTitle(ep),
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = if (isCurrent) FontWeight.SemiBold else FontWeight.Normal,
                            color = if (isCurrent) {
                                MaterialTheme.colorScheme.onPrimaryContainer
                            } else {
                                MaterialTheme.colorScheme.onSurface
                            },
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )

                        if (watchStatus is EpisodeWatchStatus.InProgress) {
                            LinearProgressIndicator(
                                progress = { watchStatus.progress },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(3.dp)
                                    .clip(RoundedCornerShape(1.5.dp)),
                                color = MaterialTheme.colorScheme.primary,
                                trackColor = MaterialTheme.colorScheme.surfaceVariant,
                            )
                        } else {
                            Spacer(Modifier.height(3.dp))
                        }
                    }
                }
            }
        }
    }
}
