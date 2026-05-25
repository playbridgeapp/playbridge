package com.playbridge.player.player

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.*
import playbridge.PlayPayload

/**
 * Compact side-panel playlist picker that overlays the video.
 * The currently playing item is highlighted; clicking another item navigates to it.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun PlaylistPickerDialog(
    items: List<PlayPayload>,
    currentIndex: Int,
    onItemSelected: (index: Int) -> Unit,
    onDismiss: () -> Unit
) {
    val listState = rememberLazyListState()
    val focusRequester = remember { FocusRequester() }

    // Scroll to and focus the current item on open
    LaunchedEffect(currentIndex) {
        listState.scrollToItem(maxOf(0, currentIndex - 2))
        try {
            focusRequester.requestFocus()
        } catch (e: Exception) {
            // Ignore focus errors if not attached yet
        }
    }

    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.CenterEnd
    ) {
        Column(
            modifier = Modifier
                .width(360.dp)
                .fillMaxHeight(0.85f)
                .padding(end = 24.dp)
                .background(Color(0xF21A1A2E), RoundedCornerShape(14.dp))
                .padding(16.dp)
        ) {
            // Header
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Playlist",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "${currentIndex + 1}/${items.size}",
                    color = Color.Gray,
                    fontSize = 13.sp
                )
            }

            // Episode list
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                itemsIndexed(items) { index, item ->
                    val isCurrent = index == currentIndex
                    var isFocused by remember { mutableStateOf(false) }

                    val backgroundColor = when {
                        isCurrent -> Color(0xFF00D9FF).copy(alpha = 0.2f)
                        isFocused -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f)
                        else -> Color.Transparent
                    }

                    val isFailed = item.title?.startsWith("[FAILED]") == true

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(backgroundColor)
                            .then(if (isCurrent) Modifier.focusRequester(focusRequester) else Modifier)
                            .onFocusChanged { isFocused = it.isFocused }
                            .clickable { onItemSelected(index) }
                            .focusable()
                            .padding(horizontal = 10.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Index number
                        Text(
                            text = "${index + 1}",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (isCurrent) Color(0xFF00D9FF) else Color.Gray,
                            modifier = Modifier.width(28.dp)
                        )

                        // Status indicator
                        Text(
                            text = if (isCurrent) "▶ " else "  ",
                            fontSize = 12.sp,
                            color = if (isCurrent) Color(0xFF00D9FF) else Color.Gray
                        )

                        // Title — drop the redundant series-name prefix (e.g.
                        // "Breaking Bad S2E5 - Breakage" -> "S2E5 - Breakage") so it
                        // doesn't cut off in this narrow side panel.
                        Text(
                            text = shortEpisodeLabel(item.title) ?: "Episode ${index + 1}",
                            fontSize = 14.sp,
                            fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Normal,
                            color = when {
                                isFailed -> Color.Red.copy(alpha = 0.8f)
                                isCurrent -> Color(0xFF00D9FF)
                                else -> MaterialTheme.colorScheme.onSurface
                            },
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }
        }
    }

    // Back handler to dismiss
    androidx.activity.compose.BackHandler { onDismiss() }
}

private val EPISODE_MARKER = Regex("""S\d+\s*E\d+.*""", RegexOption.IGNORE_CASE)

/**
 * Drop the redundant series-name prefix from an episode title for this narrow
 * side panel, e.g. "Breaking Bad S2E5 - Breakage" -> "S2E5 - Breakage". A leading
 * "[FAILED]" marker is preserved; null/markerless titles pass through unchanged.
 */
private fun shortEpisodeLabel(title: String?): String? {
    if (title == null) return null
    val failedPrefix = "[FAILED] "
    return if (title.startsWith(failedPrefix)) {
        val rest = title.removePrefix(failedPrefix)
        failedPrefix + (EPISODE_MARKER.find(rest)?.value ?: rest)
    } else {
        EPISODE_MARKER.find(title)?.value ?: title
    }
}
