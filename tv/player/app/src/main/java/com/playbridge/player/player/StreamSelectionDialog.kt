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
import com.playbridge.shared.stremio.QualityRanker
import com.playbridge.shared.stremio.ScoredStremioStream
import com.playbridge.shared.stremio.SourceTypeRanker

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
    var selectedSourceTypes by remember {
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
                DropdownChip(
                    label = "Quality",
                    valueText = qualityValueLabel,
                    isCustom = selectedRank != null,
                    modifier = Modifier.weight(1f)
                ) { dismiss ->
                    qualities.forEach { (rank, label) ->
                        DropdownItem(
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
                DropdownChip(
                    label = "Addon",
                    valueText = addonValueLabel,
                    isCustom = selectedAddon != null,
                    enabled = addons.isNotEmpty(),
                    modifier = Modifier.weight(1f)
                ) { dismiss ->
                    DropdownItem(
                        label = "All Addons",
                        selected = selectedAddon == null,
                        onClick = {
                            selectedAddon = null
                            dismiss()
                        }
                    )
                    addons.forEach { addon ->
                        DropdownItem(
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
                DropdownChip(
                    label = "Source",
                    valueText = sourceValueText,
                    isCustom = selectedSourceTypes.isNotEmpty(),
                    modifier = Modifier.weight(1f)
                ) { _ ->
                    if (selectedSourceTypes.isNotEmpty()) {
                        DropdownItem(
                            label = "Clear selection",
                            selected = false,
                            onClick = { selectedSourceTypes = emptySet() }
                        )
                    }
                    SourceTypeRanker.ORDERED_LABELS.forEach { (key, label) ->
                        val isSelected = key in selectedSourceTypes
                        DropdownItem(
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
                            lineHeight = 18.sp
                        )

                        if (!stream.description.isNullOrBlank()) {
                            Text(
                                text = stream.description!!,
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                                modifier = Modifier.padding(top = 4.dp),
                                lineHeight = 16.sp
                            )
                        }

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

/**
 * Focusable chip that anchors a D-pad-friendly [Popup] menu below itself. Clicking
 * (or pressing center on D-pad) toggles the menu. The menu is dismissed when the
 * user presses back or clicks outside. Menu item focus is handled by [DropdownItem].
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun DropdownChip(
    label: String,
    valueText: String,
    isCustom: Boolean,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable (dismiss: () -> Unit) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    var isFocused by remember { mutableStateOf(false) }

    val themeColor = Color(0xFF00D9FF)
    val bgColor = when {
        !enabled -> Color(0xFF1E1E38).copy(alpha = 0.4f)
        isCustom -> themeColor.copy(alpha = 0.15f)
        isFocused -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)
        else -> Color(0xFF1E1E38)
    }
    val borderColor = when {
        isCustom -> themeColor.copy(alpha = 0.5f)
        isFocused -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.25f)
        else -> Color.Transparent
    }
    val textColor = when {
        !enabled -> Color.Gray
        isCustom -> themeColor
        else -> MaterialTheme.colorScheme.onSurface
    }

    Box(modifier = modifier) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(38.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(bgColor)
                .border(1.dp, borderColor, RoundedCornerShape(8.dp))
                .onFocusChanged { isFocused = it.isFocused }
                .clickable(enabled = enabled) { expanded = !expanded }
                .focusable(enabled = enabled)
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = "$label: $valueText",
                color = textColor,
                fontSize = 12.sp,
                fontWeight = if (isCustom) FontWeight.SemiBold else FontWeight.Normal,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false)
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text = "▾",
                color = textColor,
                fontSize = 11.sp
            )
        }

        if (expanded) {
            Popup(
                alignment = Alignment.TopStart,
                offset = androidx.compose.ui.unit.IntOffset(0, with(androidx.compose.ui.platform.LocalDensity.current) { 42.dp.roundToPx() }),
                onDismissRequest = { expanded = false },
                properties = PopupProperties(
                    focusable = true,
                    dismissOnBackPress = true,
                    dismissOnClickOutside = true
                )
            ) {
                Column(
                    modifier = Modifier
                        .widthIn(min = 160.dp)
                        .background(Color(0xFF1E1E38), RoundedCornerShape(8.dp))
                        .border(1.dp, Color(0xFF00D9FF).copy(alpha = 0.25f), RoundedCornerShape(8.dp))
                        .padding(vertical = 4.dp)
                ) {
                    content { expanded = false }
                }
            }
        }
    }
}

/** Individual item in a [DropdownChip] menu — focusable via D-pad. */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun DropdownItem(
    label: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    var isFocused by remember { mutableStateOf(false) }
    val themeColor = Color(0xFF00D9FF)
    val bgColor = when {
        isFocused -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.10f)
        else -> Color.Transparent
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(36.dp)
            .background(bgColor)
            .onFocusChanged { isFocused = it.isFocused }
            .clickable { onClick() }
            .focusable()
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = if (selected) "✓ $label" else label,
            color = if (selected) themeColor else MaterialTheme.colorScheme.onSurface,
            fontSize = 13.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}
