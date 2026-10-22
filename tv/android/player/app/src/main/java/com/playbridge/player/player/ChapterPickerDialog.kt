package com.playbridge.player.player

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import com.playbridge.shared.player.PlayerChapter

/**
 * Compact side-panel chapter picker that overlays the video, matching [PlaylistPickerDialog].
 * The currently active chapter is highlighted and initially focused; selecting a chapter
 * seeks to its start time and closes the picker.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun ChapterPickerDialog(
    chapters: List<PlayerChapter>,
    currentPositionMs: Long,
    onChapterSelected: (index: Int) -> Unit,
    onDismiss: () -> Unit,
) {
    val rows = remember(chapters, currentPositionMs) {
        buildChapterPickerRows(chapters, currentPositionMs)
    }
    val currentIndex = remember(rows) {
        rows.indexOfFirst { it.isCurrent }
    }
    val initialFocusIndex = remember(rows.size) {
        currentChapterIndex(chapters, currentPositionMs).coerceIn(0, (rows.size - 1).coerceAtLeast(0))
    }
    val listState = rememberLazyListState()
    val focusRequester = remember { FocusRequester() }

    LaunchedEffect(initialFocusIndex) {
        if (rows.isNotEmpty()) {
            listState.scrollToItem(maxOf(0, initialFocusIndex - 2))
            try {
                focusRequester.requestFocus()
            } catch (_: Exception) {
                // Ignore focus errors if not attached yet
            }
        }
    }

    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.CenterEnd,
    ) {
        Column(
            modifier = Modifier
                .width(360.dp)
                .fillMaxHeight(0.85f)
                .padding(end = 24.dp)
                .background(Color(0xF21A1A2E), RoundedCornerShape(14.dp))
                .padding(16.dp),
        ) {
            // Header
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "Chapters",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = if (currentIndex >= 0) "${currentIndex + 1}/${rows.size}" else "${rows.size}",
                    color = Color.Gray,
                    fontSize = 13.sp,
                )
            }

            // Chapter list
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                items(rows, key = { it.index }) { row ->
                    val isCurrent = row.isCurrent
                    val isInitialFocus = row.index == initialFocusIndex
                    var isFocused by remember { mutableStateOf(false) }

                    val backgroundColor = when {
                        isCurrent -> Color(0xFF00D9FF).copy(alpha = 0.2f)
                        isFocused -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f)
                        else -> Color.Transparent
                    }

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(backgroundColor)
                            .then(if (isInitialFocus) Modifier.focusRequester(focusRequester) else Modifier)
                            .onFocusChanged { isFocused = it.isFocused }
                            .clickable { onChapterSelected(row.index) }
                            .focusable()
                            .padding(horizontal = 10.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        // Status indicator
                        Text(
                            text = if (isCurrent) "▶ " else "  ",
                            fontSize = 12.sp,
                            color = if (isCurrent) Color(0xFF00D9FF) else Color.Gray,
                        )

                        // Chapter label ("Chapter N · Title" or "Chapter N")
                        Text(
                            text = row.label,
                            fontSize = 14.sp,
                            fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Normal,
                            color = if (isCurrent) Color(0xFF00D9FF) else MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )

                        Spacer(modifier = Modifier.width(8.dp))

                        // Start time
                        Text(
                            text = row.formattedStartTime,
                            fontSize = 13.sp,
                            fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Normal,
                            color = if (isCurrent) Color(0xFF00D9FF) else Color.Gray,
                        )
                    }
                }
            }
        }
    }

    // Back handler to dismiss
    androidx.activity.compose.BackHandler { onDismiss() }
}
