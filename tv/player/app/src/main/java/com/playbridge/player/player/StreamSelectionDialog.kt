package com.playbridge.player.player

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
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
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.SurfaceDefaults
import androidx.tv.material3.Text
import com.playbridge.shared.stremio.SourceTypeRanker
import com.playbridge.shared.stremio.ScoredStremioStream
import com.playbridge.player.ui.player.*

/**
 * Side-panel stream selection dialog.
 * Lists all available Stremio streams for the current episode, ranked by score.
 *
 * Filter UI is a single row of three dropdown chips — Quality, Addon, Source — to mirror
 * the phone's StreamPickerSheet. D-pad friendly: each chip and menu item is an
 * individually focusable Box.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun StreamSelectionDialog(
    streams: List<ScoredStremioStream>,
    currentUrl: String?,
    isLoading: Boolean = false,
    preferredQuality: String? = null,
    preferredAddonName: String? = null,
    preferredSourceTypeKeys: List<String>? = null,
    onStreamSelected: (stream: ScoredStremioStream) -> Unit,
    onRefresh: () -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val listState = rememberLazyListState()
    val focusRequester = remember { FocusRequester() }

    // ── Filter state ──
    // Seeded from nav/payload preferences, with fallback to stored browser_prefs.
    var selectedRank by remember(preferredQuality) {
        val prefs = context.getSharedPreferences("browser_prefs", Context.MODE_PRIVATE)
        val prefQuality = preferredQuality ?: prefs.getString("auto_stream_quality", null)
        val rank = if (!prefQuality.isNullOrBlank()) {
            when (prefQuality.lowercase()) {
                "2160p", "4k", "uhd" -> 4
                "1080p", "1080"      -> 3
                "720p",  "720"       -> 2
                else -> 0
            }
        } else 0
        mutableStateOf<Int?>(rank.takeIf { it > 0 })
    }
    var selectedAddon by remember(preferredAddonName) {
        val prefs = context.getSharedPreferences("browser_prefs", Context.MODE_PRIVATE)
        val prefAddonName = preferredAddonName ?: prefs.getString("auto_stream_addon_name", null)
        mutableStateOf<String?>(prefAddonName?.takeIf { it.isNotBlank() })
    }
    var selectedSourceTypes by remember(preferredSourceTypeKeys) {
        mutableStateOf(
            (preferredSourceTypeKeys ?: emptyList())
                .map { it.trim().lowercase() }
                .filter { it.isNotEmpty() }
                .toSet()
        )
    }

    val addons = remember(streams) {
        streams.mapNotNull { it.addonName }.distinct().sorted()
    }

    val filteredStreams = remember(streams, selectedRank, selectedAddon, selectedSourceTypes) {
        streams.filter { stream ->
            val rankOk = selectedRank == null || stream.rank == selectedRank
            val addonOk = selectedAddon == null || stream.addonName == selectedAddon
            val sourceOk = selectedSourceTypes.isEmpty() ||
                SourceTypeRanker.matches(stream.name, stream.title, null, selectedSourceTypes)
            rankOk && addonOk && sourceOk
        }
    }

    // Scroll to and focus the current item on open or filter change
    val currentIndex = remember(filteredStreams, currentUrl) {
        filteredStreams.indexOfFirst { it.url == currentUrl }.coerceAtLeast(0)
    }

    LaunchedEffect(currentIndex, selectedRank, selectedAddon, selectedSourceTypes) {
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
                            text = if (isLoading) "⌛" else "↻",
                            color = if (isRefreshFocused) Color(0xFF00D9FF) else Color.Gray,
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.then(if (isLoading) Modifier else Modifier) // could add animation here
                        )
                    }
                }
            }

            // ── Single row of 3 dropdown chips ──
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Quality (single-select)
                val qualities = listOf(
                    null to "All",
                    4 to "4K",
                    3 to "1080p",
                    2 to "720p",
                    1 to "SD"
                )
                val qualityValueLabel = qualities.firstOrNull { it.first == selectedRank }?.second ?: "All"
                FilterDropdownChip(
                    label = "Quality",
                    valueText = qualityValueLabel,
                    isCustom = selectedRank != null,
                    modifier = Modifier.weight(1f)
                ) { dismiss ->
                    qualities.forEach { (rank, label) ->
                        FilterDropdownItem(
                            label = label,
                            selected = selectedRank == rank,
                            onClick = {
                                selectedRank = rank
                                dismiss()
                            }
                        )
                    }
                }

                // Addon (single-select). Disabled visually when none available.
                val addonValueLabel = selectedAddon ?: "All"
                FilterDropdownChip(
                    label = "Addon",
                    valueText = addonValueLabel,
                    isCustom = selectedAddon != null,
                    enabled = addons.isNotEmpty(),
                    modifier = Modifier.weight(1f)
                ) { dismiss ->
                    FilterDropdownItem(
                        label = "All Addons",
                        selected = selectedAddon == null,
                        onClick = {
                            selectedAddon = null
                            dismiss()
                        }
                    )
                    addons.forEach { addon ->
                        FilterDropdownItem(
                            label = addon,
                            selected = selectedAddon == addon,
                            onClick = {
                                selectedAddon = addon
                                dismiss()
                            }
                        )
                    }
                }

                // Source type (multi-select). Menu stays open between toggles.
                val sourceValueText = when (selectedSourceTypes.size) {
                    0 -> "Any"
                    1 -> SourceTypeRanker.labelOf(selectedSourceTypes.first())
                    else -> "${SourceTypeRanker.labelOf(selectedSourceTypes.first())} +${selectedSourceTypes.size - 1}"
                }
                FilterDropdownChip(
                    label = "Source",
                    valueText = sourceValueText,
                    isCustom = selectedSourceTypes.isNotEmpty(),
                    modifier = Modifier.weight(1f)
                ) { _ ->
                    if (selectedSourceTypes.isNotEmpty()) {
                        FilterDropdownItem(
                            label = "Clear selection",
                            selected = false,
                            onClick = { selectedSourceTypes = emptySet() }
                        )
                    }
                    SourceTypeRanker.ORDERED_LABELS.forEach { (key, label) ->
                        val isSelected = key in selectedSourceTypes
                        FilterDropdownItem(
                            label = label,
                            selected = isSelected,
                            onClick = {
                                selectedSourceTypes = if (isSelected) selectedSourceTypes - key
                                else selectedSourceTypes + key
                            }
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

                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .then(if (index == currentIndex && filteredStreams.isNotEmpty()) Modifier.focusRequester(focusRequester) else Modifier)
                            .onFocusChanged { isFocused = it.isFocused }
                    ) {
                        StreamItem(
                            stream = stream,
                            isCurrent = stream.url == currentUrl,
                            isAutoPick = stream == streams.firstOrNull(),
                            onClick = { onStreamSelected(stream) }
                        )
                    }
                }

                if (isLoading) {
                    item {
                        Box(modifier = Modifier.fillMaxWidth().padding(top = 32.dp), contentAlignment = Alignment.Center) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(text = "Resolving streams...", color = Color(0xFF00D9FF), fontWeight = FontWeight.Bold)
                                Spacer(modifier = Modifier.height(8.dp))
                                Text(text = "Fetching sources from addons", color = Color.Gray, fontSize = 12.sp)
                            }
                        }
                    }
                } else if (filteredStreams.isEmpty()) {
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
