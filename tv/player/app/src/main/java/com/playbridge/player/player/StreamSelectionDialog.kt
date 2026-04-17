package com.playbridge.player.player

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.*
import com.playbridge.player.stremio.QualityRanker
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
    preferredQuality: String? = null,
    preferredAddonName: String? = null,
    onStreamSelected: (stream: ScoredStremioStream) -> Unit,
    onRefresh: () -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val listState = rememberLazyListState()
    val focusRequester = remember { FocusRequester() }

    // State for filtering - auto-selected based on passed preferences or browser_prefs
    var selectedRank by remember {
        val prefs = context.getSharedPreferences("browser_prefs", Context.MODE_PRIVATE)
        val prefQuality = preferredQuality ?: prefs.getString("auto_stream_quality", null)
        val rank = if (!prefQuality.isNullOrBlank()) QualityRanker.targetRank(prefQuality) else 0
        mutableStateOf<Int?>(rank.takeIf { it > 0 })
    }
    var selectedAddon by remember {
        val prefs = context.getSharedPreferences("browser_prefs", Context.MODE_PRIVATE)
        val prefAddonName = preferredAddonName ?: prefs.getString("auto_stream_addon_name", null)
        mutableStateOf<String?>(prefAddonName?.takeIf { it.isNotBlank() })
    }

    val addons = remember(streams) {
        streams.mapNotNull { it.addonName }.distinct().sorted()
    }

    val filteredStreams = remember(streams, selectedRank, selectedAddon) {
        streams.filter { stream ->
            (selectedRank == null || stream.rank == selectedRank) &&
            (selectedAddon == null || stream.addonName == selectedAddon)
        }
    }

    // Scroll to and focus the current item on open or filter change
    val currentIndex = remember(filteredStreams, currentUrl) {
        filteredStreams.indexOfFirst { it.url == currentUrl }.coerceAtLeast(0)
    }

    LaunchedEffect(currentIndex, selectedRank, selectedAddon) {
        if (currentIndex >= 0 && filteredStreams.isNotEmpty()) {
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
                .width(440.dp)
                .fillMaxHeight(0.9f)
                .padding(end = 24.dp)
                .background(Color(0xF21A1A2E), RoundedCornerShape(14.dp))
                .padding(16.dp)
        ) {
            // Header
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Select Stream",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.Bold
                )

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "${filteredStreams.size} / ${streams.size} sources",
                        color = Color.Gray,
                        fontSize = 13.sp
                    )

                    Spacer(modifier = Modifier.width(12.dp))

                    var isRefreshFocused by remember { mutableStateOf(false) }
                    Box(
                        modifier = Modifier
                            .size(28.dp)
                            .clip(CircleShape)
                            .background(if (isRefreshFocused) Color.White.copy(alpha = 0.1f) else Color.Transparent)
                            .onFocusChanged { isRefreshFocused = it.isFocused }
                            .clickable { onRefresh() }
                            .focusable(),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "↻",
                            color = if (isRefreshFocused) Color(0xFF00D9FF) else Color.Gray,
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }

            // ── Quality Chips ──
            Text(
                text = "Quality",
                style = MaterialTheme.typography.labelSmall,
                color = Color.Gray,
                modifier = Modifier.padding(bottom = 4.dp, start = 4.dp)
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                val qualities = listOf(
                    null to "All",
                    4 to "4K",
                    3 to "1080p",
                    2 to "720p",
                    1 to "SD"
                )
                qualities.forEach { (rank, label) ->
                    val isSelected = selectedRank == rank
                    StreamFilterChip(
                        selected = isSelected,
                        onClick = { selectedRank = rank },
                        label = label
                    )
                }
            }

            // ── Addon Chips ──
            if (addons.isNotEmpty()) {
                Text(
                    text = "Addons",
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.Gray,
                    modifier = Modifier.padding(bottom = 4.dp, start = 4.dp)
                )
                // LazyRow for many addons to avoid overflow
                androidx.compose.foundation.lazy.LazyRow(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = PaddingValues(horizontal = 4.dp)
                ) {
                    item {
                        StreamFilterChip(
                            selected = selectedAddon == null,
                            onClick = { selectedAddon = null },
                            label = "All"
                        )
                    }
                    items(addons) { addon ->
                        StreamFilterChip(
                            selected = selectedAddon == addon,
                            onClick = { selectedAddon = addon },
                            label = addon
                        )
                    }
                }
            }

            // Stream list
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                itemsIndexed(filteredStreams) { index, stream ->
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
                            .then(if (index == currentIndex && filteredStreams.isNotEmpty()) Modifier.focusRequester(focusRequester) else Modifier)
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
                            val qualityShort = when(stream.rank) {
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
                                    text = qualityShort,
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

                            // Addon Name (in the row too for clarity when filtering is Off)
                            Text(
                                text = stream.addonName ?: stream.name ?: "",
                                fontSize = 11.sp,
                                color = Color.Gray
                            )
                        }
                    }
                }

                if (filteredStreams.isEmpty()) {
                    item {
                        Box(modifier = Modifier.fillMaxWidth().padding(top = 32.dp), contentAlignment = Alignment.Center) {
                            Text(text = "No matching streams", color = Color.Gray)
                        }
                    }
                }
            }
        }
    }

    // Back handler to dismiss
    androidx.activity.compose.BackHandler { onDismiss() }
}

@Composable
private fun StreamFilterChip(
    selected: Boolean,
    onClick: () -> Unit,
    label: String
) {
    var isFocused by remember { mutableStateOf(false) }

    val bgColor = when {
        selected -> Color(0xFF00D9FF).copy(alpha = 0.15f)
        isFocused -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)
        else -> Color(0xFF1E1E38)
    }
    val themeColor = Color(0xFF00D9FF)
    val borderColor = when {
        selected -> themeColor.copy(alpha = 0.5f)
        isFocused -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.15f)
        else -> Color.Transparent
    }

    Box(
        modifier = Modifier
            .height(34.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(bgColor)
            .border(1.dp, borderColor, RoundedCornerShape(8.dp))
            .onFocusChanged { isFocused = it.isFocused }
            .clickable { onClick() }
            .focusable()
            .padding(horizontal = 12.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = if (selected) "✓ $label" else label,
            color = if (selected) themeColor else MaterialTheme.colorScheme.onSurface,
            fontSize = 12.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal
        )
    }
}
