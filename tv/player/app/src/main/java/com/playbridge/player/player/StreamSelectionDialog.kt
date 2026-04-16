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
import com.playbridge.player.stremio.ScoredStremioStream

/**
 * Side-panel stream selection dialog.
 * Lists all available Stremio streams for the current episode, ranked by score.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun StreamSelectionDialog(
    streams: List<ScoredStremioStream>,
    currentUrl: String?,
    onStreamSelected: (stream: ScoredStremioStream) -> Unit,
    onDismiss: () -> Unit
) {
    val listState = rememberLazyListState()
    val focusRequester = remember { FocusRequester() }

    val currentIndex = streams.indexOfFirst { it.url == currentUrl }.coerceAtLeast(0)

    // Scroll to and focus the current item on open
    LaunchedEffect(currentIndex) {
        if (currentIndex >= 0) {
            listState.scrollToItem(maxOf(0, currentIndex - 2))
        }
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
                .width(420.dp)
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
                    text = "Select Stream",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "${streams.size} sources",
                    color = Color.Gray,
                    fontSize = 13.sp
                )
            }

            // Stream list
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                itemsIndexed(streams) { index, stream ->
                    val isCurrent = stream.url == currentUrl
                    var isFocused by remember { mutableStateOf(false) }

                    val backgroundColor = when {
                        isCurrent -> Color(0xFF00D9FF).copy(alpha = 0.2f)
                        isFocused -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f)
                        else -> Color.Transparent
                    }

                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(backgroundColor)
                            .then(if (index == 0 && !isCurrent) Modifier.focusRequester(focusRequester) else if (isCurrent) Modifier.focusRequester(focusRequester) else Modifier)
                            .onFocusChanged { isFocused = it.isFocused }
                            .clickable { onStreamSelected(stream) }
                            .focusable()
                            .padding(horizontal = 12.dp, vertical = 10.dp)
                    ) {
                        // Title / Release Name
                        Text(
                            text = stream.title ?: stream.name ?: "Unknown Stream",
                            fontSize = 14.sp,
                            fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Normal,
                            color = if (isCurrent) Color(0xFF00D9FF) else MaterialTheme.colorScheme.onSurface,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )

                        // Metadata Row
                        Row(
                            modifier = Modifier.padding(top = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            // Quality Badge
                            val qualityColor = when(stream.rank) {
                                4 -> Color(0xFFE91E63) // 4K
                                3 -> Color(0xFF4CAF50) // 1080p
                                2 -> Color(0xFF2196F3) // 720p
                                else -> Color.Gray
                            }
                            val qualityText = when(stream.rank) {
                                4 -> "4K"
                                3 -> "1080p"
                                2 -> "720p"
                                else -> "SD"
                            }

                            Surface(
                                shape = RoundedCornerShape(4.dp),
                                colors = SurfaceDefaults.colors(
                                    containerColor = qualityColor.copy(alpha = 0.2f)
                                ),
                                modifier = Modifier.padding(end = 8.dp)
                            ) {
                                Text(
                                    text = qualityText,
                                    fontSize = 10.sp,
                                    color = qualityColor,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
                                )
                            }

                            if (stream.isSeasonPack) {
                                Text(
                                    text = "Season Pack",
                                    fontSize = 11.sp,
                                    color = Color(0xFFFFC107),
                                    modifier = Modifier.padding(end = 8.dp)
                                )
                            }

                            if (stream.isExtras) {
                                Text(
                                    text = "Extras",
                                    fontSize = 11.sp,
                                    color = Color.Red.copy(alpha = 0.7f),
                                    modifier = Modifier.padding(end = 8.dp)
                                )
                            }

                            Spacer(modifier = Modifier.weight(1f))

                            // Addon Name
                            Text(
                                text = stream.name ?: "",
                                fontSize = 11.sp,
                                color = Color.Gray
                            )
                        }
                    }
                }
            }
        }
    }

    // Back handler to dismiss
    androidx.activity.compose.BackHandler { onDismiss() }
}
