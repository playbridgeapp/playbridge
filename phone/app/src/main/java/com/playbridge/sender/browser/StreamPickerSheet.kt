package com.playbridge.sender.browser

import android.content.Context
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.playbridge.sender.data.library.ResolvedStream

/**
 * Bottom sheet displaying resolved streams from Stremio addons.
 * Supports quality filter chips and incremental display (streams appear as they resolve).
 *
 * When "auto_stream_quality" pref is set (2160p / 1080p / 720p), the sheet auto-picks the
 * best matching stream as soon as all addons have responded and calls [onStreamSelected]
 * without requiring the user to tap. If "auto_stream_max_mbps" is set, only streams at or
 * below that bitrate are considered; no match = falls back to the first stream in ANY quality.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StreamPickerSheet(
    streams: List<ResolvedStream>,
    isLoading: Boolean,
    title: String,
    episodeRuntimeMinutes: Int? = null,
    forceManual: Boolean = false,
    themeColor: Color? = null,
    onStreamSelected: (ResolvedStream) -> Unit,
    onRefresh: () -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("browser_prefs", Context.MODE_PRIVATE) }
    val isDynamic = themeColor != null
    val containerColor = if (isDynamic) themeColor!! else MaterialTheme.colorScheme.surface
    val contentColor = if (isDynamic) {
        if (themeColor!!.luminance() > 0.5f) Color.Black else Color.White
    } else MaterialTheme.colorScheme.onSurface
    val accentColor = if (isDynamic) contentColor else MaterialTheme.colorScheme.primary
    val subTextColor = if (isDynamic) contentColor.copy(alpha = 0.7f) else MaterialTheme.colorScheme.onSurfaceVariant

    // Auto-select preferences
    val autoQualityKey = remember { prefs.getString("auto_stream_quality", "") ?: "" }
    val autoMaxMbps = remember {
        val raw = prefs.getString("auto_stream_max_mbps", "") ?: ""
        raw.toDoubleOrNull()
    }
    val autoAddonKey = remember { prefs.getString("auto_stream_addon", "") ?: "" }
    // Preferred source types (multi-select, persisted as CSV, e.g. "bluray,web-dl,remux").
    val autoSourceTypes = remember {
        SourceTypeFilter.parseCsv(prefs.getString("auto_stream_source_types", ""))
    }
    // Resolve the QualityFilter enum matching the pref key (e.g. "2160p" → UHD)
    val autoFilter = remember(autoQualityKey) { QualityFilter.fromKey(autoQualityKey) }

    // Track whether we already fired the auto-pick to avoid re-triggering
    var autoPickFired by remember { mutableStateOf(false) }
    // Show a brief "Auto-picking…" message while we're about to dismiss
    var autoPickTriggered by remember { mutableStateOf(false) }

    // Reset auto-pick flags if streams are cleared while loading (e.g. on manual refresh)
    LaunchedEffect(streams, isLoading) {
        if (streams.isEmpty() && isLoading) {
            autoPickFired = false
            autoPickTriggered = false
        }
    }

    // When the sheet was forced open manually (hold gesture) and auto-prefs are set,
    // compute which stream would have been auto-selected so we can badge it.
    val autoMatchStream = remember(streams, autoFilter, autoMaxMbps, episodeRuntimeMinutes, autoAddonKey, autoSourceTypes) {
        if (forceManual && autoFilter != null && streams.isNotEmpty()) {
            val best = StreamSelector.selectBest(
                streams = streams,
                preferredQuality = autoFilter,
                maxMbps = autoMaxMbps,
                runtimeMinutes = episodeRuntimeMinutes,
                preferredAddon = autoAddonKey.takeIf { it.isNotEmpty() },
                preferredSourceTypes = autoSourceTypes
            )
            best ?: streams.firstOrNull()
        } else null
    }

    // Auto-select: fires once when streams are fully loaded
    LaunchedEffect(isLoading, streams.size) {
        if (!forceManual && autoFilter != null && !isLoading && streams.isNotEmpty() && !autoPickFired) {
            autoPickFired = true
            autoPickTriggered = true

            // 1. Filter to the desired quality tier + source types + apply max-bitrate cap
            val best = StreamSelector.selectBest(
                streams = streams,
                preferredQuality = autoFilter,
                maxMbps = autoMaxMbps,
                runtimeMinutes = episodeRuntimeMinutes,
                preferredAddon = autoAddonKey.takeIf { it.isNotEmpty() },
                preferredSourceTypes = autoSourceTypes
            )

            // 2. Fallback: if no candidates in the tier, use the first stream
            val result = best ?: streams.firstOrNull()
            result?.let { onStreamSelected(it) }
        }
    }

    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    // All three dropdowns seed from saved preferences so the user's defaults are
    // pre-applied when the sheet opens. A LaunchedEffect below auto-clears any
    // preselection that matches zero streams once all addons have responded, so
    // the sheet never shows "0 streams" just because a preferred provider/quality
    // happened to return nothing for this title.
    var selectedFilter by remember { mutableStateOf(autoFilter ?: QualityFilter.ALL) }
    var selectedProvider by remember { mutableStateOf(autoAddonKey.takeIf { it.isNotEmpty() }) }
    var selectedSourceTypes by remember { mutableStateOf(autoSourceTypes) }

    val providers = remember(streams) {
        streams.map { it.addonName }.distinct().sorted()
    }

    // Once loading finishes, clear any preselected filter that yielded zero matches.
    LaunchedEffect(isLoading, streams.size) {
        if (isLoading || streams.isEmpty()) return@LaunchedEffect
        if (selectedFilter != QualityFilter.ALL &&
            streams.none { StreamSelector.matchesFilter(it, selectedFilter) }) {
            selectedFilter = QualityFilter.ALL
        }
        if (selectedProvider != null && selectedProvider !in providers) {
            selectedProvider = null
        }
        if (selectedSourceTypes.isNotEmpty() &&
            streams.none { StreamSelector.matchesSourceTypes(it, selectedSourceTypes) }) {
            selectedSourceTypes = emptySet()
        }
    }

    val filteredStreams = remember(streams, selectedFilter, selectedProvider, selectedSourceTypes) {
        streams.filter { stream ->
            StreamSelector.matchesFilter(stream, selectedFilter) &&
            (selectedProvider == null || stream.addonName == selectedProvider) &&
            StreamSelector.matchesSourceTypes(stream, selectedSourceTypes)
        }
    }

    val filterCounts = remember(streams) {
        QualityFilter.entries.associateWith { filter ->
            if (filter == QualityFilter.ALL) streams.size
            else streams.count { StreamSelector.matchesFilter(it, filter) }
        }
    }

    val sourceTypeCounts = remember(streams) {
        SourceTypeFilter.ORDERED.associateWith { type ->
            streams.count { StreamSelector.matchesSourceTypes(it, setOf(type)) }
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
        containerColor = containerColor,
        contentColor = contentColor,
        dragHandle = {
            BottomSheetDefaults.DragHandle(
                color = contentColor.copy(alpha = 0.3f)
            )
        }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 32.dp)
        ) {
            // Header
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = contentColor
                )
                if (isLoading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(24.dp).padding(4.dp),
                        strokeWidth = 2.dp,
                        color = accentColor
                    )
                } else {
                    IconButton(onClick = onRefresh) {
                        Icon(
                            Icons.Default.Refresh,
                            contentDescription = "Refresh streams",
                            tint = accentColor
                        )
                    }
                }
            }

            // Single row of three dropdown chips: Quality / Provider / Source.
            // Horizontal scroll lets small-screen devices reach all three without wrapping.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Quality: single-select.
                DropdownFilterChip(
                    label = "Quality",
                    valueText = if (selectedFilter == QualityFilter.ALL) "All" else selectedFilter.label,
                    isCustom = selectedFilter != QualityFilter.ALL,
                    enabled = streams.isNotEmpty() || isLoading,
                    themeColor = themeColor,
                    contentColor = contentColor
                ) { dismiss ->
                    QualityFilter.entries.forEach { filter ->
                        val count = filterCounts[filter] ?: 0
                        val optionLabel = if (count > 0 && filter != QualityFilter.ALL)
                            "${filter.label} ($count)" else filter.label
                        DropdownMenuItem(
                            text = { Text(optionLabel) },
                            onClick = { selectedFilter = filter; dismiss() },
                            leadingIcon = {
                                if (selectedFilter == filter) {
                                    Icon(Icons.Default.Check, contentDescription = null)
                                } else {
                                    Spacer(Modifier.size(24.dp))
                                }
                            },
                            enabled = count > 0 || filter == QualityFilter.ALL || isLoading
                        )
                    }
                }

                // Provider: single-select. Only meaningful when multiple addons returned streams.
                val providerValue = selectedProvider ?: "All"
                DropdownFilterChip(
                    label = "Provider",
                    valueText = providerValue,
                    isCustom = selectedProvider != null,
                    enabled = providers.isNotEmpty(),
                    themeColor = themeColor,
                    contentColor = contentColor
                ) { dismiss ->
                    DropdownMenuItem(
                        text = { Text("All Providers") },
                        onClick = { selectedProvider = null; dismiss() },
                        leadingIcon = {
                            if (selectedProvider == null) {
                                Icon(Icons.Default.Check, contentDescription = null)
                            } else {
                                Spacer(Modifier.size(24.dp))
                            }
                        }
                    )
                    providers.forEach { provider ->
                        DropdownMenuItem(
                            text = { Text(provider) },
                            onClick = { selectedProvider = provider; dismiss() },
                            leadingIcon = {
                                if (selectedProvider == provider) {
                                    Icon(Icons.Default.Check, contentDescription = null)
                                } else {
                                    Spacer(Modifier.size(24.dp))
                                }
                            }
                        )
                    }
                }

                // Source type: multi-select. Menu stays open so several can be toggled.
                val sourceValueText = when (selectedSourceTypes.size) {
                    0 -> "Any"
                    1 -> selectedSourceTypes.first().label
                    else -> "${selectedSourceTypes.first().label} +${selectedSourceTypes.size - 1}"
                }
                DropdownFilterChip(
                    label = "Source",
                    valueText = sourceValueText,
                    isCustom = selectedSourceTypes.isNotEmpty(),
                    enabled = streams.isNotEmpty() || isLoading,
                    themeColor = themeColor,
                    contentColor = contentColor
                ) { _ ->
                    if (selectedSourceTypes.isNotEmpty()) {
                        DropdownMenuItem(
                            text = { Text("Clear selection") },
                            onClick = { selectedSourceTypes = emptySet() },
                            leadingIcon = {
                                Icon(Icons.Default.Close, contentDescription = null, tint = contentColor)
                            }
                        )
                        HorizontalDivider(color = contentColor.copy(alpha = 0.1f))
                    }
                    SourceTypeFilter.ORDERED.forEach { type ->
                        val count = sourceTypeCounts[type] ?: 0
                        val isSelected = type in selectedSourceTypes
                        val optionLabel = if (count > 0) "${type.label} ($count)" else type.label
                        DropdownMenuItem(
                            text = { Text(optionLabel) },
                            onClick = {
                                selectedSourceTypes = if (isSelected) selectedSourceTypes - type
                                else selectedSourceTypes + type
                            },
                            leadingIcon = {
                                if (isSelected) Icon(Icons.Default.Check, contentDescription = null, tint = contentColor)
                                else Spacer(Modifier.size(24.dp))
                            },
                            enabled = count > 0 || isLoading
                        )
                    }
                }
            }

            // Stream count + loading / auto-pick indicator
            Row(
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (streams.isNotEmpty() && !autoPickTriggered) {
                    Text(
                        text = "${filteredStreams.size} stream${if (filteredStreams.size != 1) "s" else ""}",
                        style = MaterialTheme.typography.labelMedium,
                        color = accentColor
                    )
                }
                if (autoPickTriggered) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(14.dp),
                        strokeWidth = 2.dp,
                        color = accentColor
                    )
                    Text(
                        text = "Auto-picking…",
                        style = MaterialTheme.typography.labelSmall,
                        color = accentColor
                    )
                } else if (isLoading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(14.dp),
                        strokeWidth = 2.dp,
                        color = accentColor
                    )
                    Text(
                        text = "Resolving…",
                        style = MaterialTheme.typography.labelSmall,
                        color = subTextColor
                    )
                }
            }

            // Stream list (shows incrementally as streams arrive)
            if (streams.isEmpty() && isLoading) {
                // Initial loading state — no streams yet
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(200.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator(color = accentColor)
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            "Resolving streams from addons…",
                            style = MaterialTheme.typography.bodyMedium,
                            color = subTextColor
                        )
                    }
                }
            } else if (streams.isEmpty() && !isLoading) {
                // No streams found at all
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(200.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            Icons.Default.SearchOff,
                            contentDescription = null,
                            tint = subTextColor,
                            modifier = Modifier.size(48.dp)
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            "No streams found",
                            style = MaterialTheme.typography.bodyLarge,
                            color = contentColor
                        )
                        Text(
                            "Make sure you have addons installed with Real-Debrid configured",
                            style = MaterialTheme.typography.bodySmall,
                            color = subTextColor,
                            modifier = Modifier.padding(horizontal = 32.dp)
                        )
                    }
                }
            } else if (filteredStreams.isEmpty()) {
                // Streams exist but none match the active filter combination
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(120.dp),
                    contentAlignment = Alignment.Center
                ) {
                    val sourceLabel = if (selectedSourceTypes.isNotEmpty())
                        " / ${selectedSourceTypes.joinToString(" + ") { it.label }}"
                    else ""
                    Text(
                        "No ${selectedFilter.label}$sourceLabel streams found",
                        style = MaterialTheme.typography.bodyMedium,
                        color = subTextColor
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.heightIn(max = 400.dp),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    items(filteredStreams, key = { "${it.addonName}:${it.stream.url}" }) { resolved ->
                        StreamItem(
                            resolvedStream = resolved,
                            episodeRuntimeMinutes = episodeRuntimeMinutes,
                            isAutoMatch = autoMatchStream == resolved,
                            themeColor = themeColor,
                            onClick = { onStreamSelected(resolved) }
                        )
                    }
                }
            }
        }
    }
}

/**
 * Filter chip that anchors a [DropdownMenu] directly below itself. The chip
 * shows a "$label: $valueText" line plus a drop-down caret; clicking it opens
 * the menu. `isCustom = true` highlights the chip as selected (i.e. the user
 * has deviated from the default "All"/"Any" value). The [content] lambda is
 * given a `dismiss` callback — call it from single-select items to close the
 * menu after choosing; multi-select items can simply omit the call so the
 * menu stays open between toggles.
 */
@Composable
private fun DropdownFilterChip(
    label: String,
    valueText: String,
    isCustom: Boolean,
    enabled: Boolean = true,
    themeColor: Color? = null,
    contentColor: Color = MaterialTheme.colorScheme.onSurface,
    content: @Composable (dismiss: () -> Unit) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val isDynamic = themeColor != null
    Box {
        FilterChip(
            selected = isCustom,
            onClick = { expanded = true },
            enabled = enabled,
            label = { Text("$label: $valueText", maxLines = 1, overflow = TextOverflow.Ellipsis) },
            trailingIcon = {
                Icon(
                    Icons.Default.ArrowDropDown,
                    contentDescription = null
                )
            },
            colors = FilterChipDefaults.filterChipColors(
                containerColor = if (isDynamic) contentColor.copy(alpha = 0.08f) else MaterialTheme.colorScheme.surface,
                labelColor = if (isDynamic) contentColor.copy(alpha = 0.75f) else MaterialTheme.colorScheme.onSurfaceVariant,
                selectedContainerColor = if (isDynamic) contentColor.copy(alpha = 0.2f) else MaterialTheme.colorScheme.secondaryContainer,
                selectedLabelColor = contentColor,
                selectedTrailingIconColor = contentColor,
                iconColor = if (isDynamic) contentColor.copy(alpha = 0.6f) else MaterialTheme.colorScheme.primary,
                disabledContainerColor = if (isDynamic) contentColor.copy(alpha = 0.04f) else MaterialTheme.colorScheme.surface,
                disabledLabelColor = if (isDynamic) contentColor.copy(alpha = 0.3f) else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
            ),
            border = null
        )
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            containerColor = if (isDynamic) themeColor!! else MaterialTheme.colorScheme.surface,
            modifier = if (isDynamic) Modifier.border(
                1.dp,
                contentColor.copy(alpha = 0.2f),
                RoundedCornerShape(12.dp)
            ) else Modifier
        ) {
            content { expanded = false }
        }
    }
}

@Composable
private fun StreamItem(
    resolvedStream: ResolvedStream,
    episodeRuntimeMinutes: Int? = null,
    isAutoMatch: Boolean = false,
    themeColor: Color? = null,
    onClick: () -> Unit
) {
    val stream = resolvedStream.stream
    val isDynamic = themeColor != null
    val contentColor = if (isDynamic) {
        if (themeColor!!.luminance() > 0.5f) Color.Black else Color.White
    } else MaterialTheme.colorScheme.onSurface
    val subTextColor = if (isDynamic) contentColor.copy(alpha = 0.7f) else MaterialTheme.colorScheme.onSurfaceVariant
    val accentColor = if (isDynamic) contentColor else MaterialTheme.colorScheme.primary

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(20.dp),
        color = if (isDynamic) contentColor.copy(alpha = 0.1f) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Play icon
            Icon(
                Icons.Default.PlayCircle,
                contentDescription = "Play",
                tint = accentColor,
                modifier = Modifier.size(32.dp)
            )

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                // Stream name (addon name + stream quality)
                Text(
                    text = stream.displayName,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = contentColor
                )

                // Quality info from title field (often contains codec, resolution, size)
                if (stream.qualityInfo.isNotBlank()) {
                    Text(
                        text = stream.qualityInfo,
                        style = MaterialTheme.typography.bodySmall,
                        color = subTextColor
                    )
                }

                // Additional description from addons like AIOStreams
                if (!stream.description.isNullOrBlank()) {
                    Text(
                        text = stream.description,
                        style = MaterialTheme.typography.bodySmall,
                        color = subTextColor
                    )
                }

                // File size and addon name row
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.horizontalScroll(rememberScrollState())
                ) {
                    // Auto-match badge (only visible when sheet was force-opened)
                    if (isAutoMatch) {
                        Surface(
                            shape = RoundedCornerShape(20.dp),
                            color = if (isDynamic) contentColor.copy(alpha = 0.2f) else MaterialTheme.colorScheme.tertiaryContainer,
                            contentColor = if (isDynamic) contentColor else MaterialTheme.colorScheme.onTertiaryContainer
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            ) {
                                Icon(
                                    Icons.Default.AutoAwesome,
                                    contentDescription = null,
                                    modifier = Modifier.size(10.dp),
                                    tint = if (isDynamic) contentColor else MaterialTheme.colorScheme.onTertiaryContainer
                                )
                                Spacer(modifier = Modifier.width(3.dp))
                                Text(
                                    text = "Would auto-pick",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (isDynamic) contentColor else MaterialTheme.colorScheme.onTertiaryContainer
                                )
                            }
                        }
                    }

                    // File size
                    stream.fileSizeFormatted?.let { size ->
                        Surface(
                            shape = RoundedCornerShape(20.dp),
                            color = if (isDynamic) contentColor.copy(alpha = 0.15f) else MaterialTheme.colorScheme.secondaryContainer,
                            contentColor = if (isDynamic) contentColor else MaterialTheme.colorScheme.onSecondaryContainer
                        ) {
                            Text(
                                text = size,
                                style = MaterialTheme.typography.labelSmall,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                color = if (isDynamic) contentColor else MaterialTheme.colorScheme.onSecondaryContainer
                            )
                        }
                    }

                    // Estimated Mbps
                    stream.estimateMbps(episodeRuntimeMinutes)?.let { mbps ->
                        Surface(
                            shape = RoundedCornerShape(20.dp),
                            color = if (isDynamic) contentColor.copy(alpha = 0.12f) else MaterialTheme.colorScheme.surfaceContainerHighest,
                            contentColor = if (isDynamic) contentColor else MaterialTheme.colorScheme.onSurfaceVariant
                        ) {
                            Text(
                                text = mbps,
                                style = MaterialTheme.typography.labelSmall,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                color = if (isDynamic) contentColor else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    // Addon source
                    Text(
                        text = "via ${resolvedStream.addonName}",
                        style = MaterialTheme.typography.labelSmall,
                        color = subTextColor
                    )
                }
            }
        }
    }
}
