package com.playbridge.receiver.player

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
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.*
import com.playbridge.protocol.PlayPayload

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

    // Scroll to current item on open
    LaunchedEffect(currentIndex) {
        listState.animateScrollToItem(maxOf(0, currentIndex - 2))
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
                    color = Color.White,
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
                    val isPast = index < currentIndex
                    var isFocused by remember { mutableStateOf(false) }

                    val backgroundColor = when {
                        isCurrent -> Color(0xFF00D9FF).copy(alpha = 0.2f)
                        isFocused -> Color.White.copy(alpha = 0.1f)
                        else -> Color.Transparent
                    }

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(backgroundColor)
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
                            text = when {
                                isCurrent -> "▶ "
                                isPast -> "✓ "
                                else -> "  "
                            },
                            fontSize = 12.sp,
                            color = if (isCurrent) Color(0xFF00D9FF) else Color.Gray
                        )

                        // Title
                        Text(
                            text = item.title ?: "Episode ${index + 1}",
                            fontSize = 14.sp,
                            fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Normal,
                            color = when {
                                isCurrent -> Color(0xFF00D9FF)
                                isPast -> Color.Gray
                                else -> Color.White
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
