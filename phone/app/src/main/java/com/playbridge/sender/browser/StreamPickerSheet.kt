package com.playbridge.sender.browser

import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
    onStreamSelected: (ResolvedStream) -> Unit,
    onRefresh: () -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("browser_prefs", Context.MODE_PRIVATE) }

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
    var selectedFilter by remember { mutableStateOf(QualityFilter.ALL) }
    var selectedProvider by remember { mutableStateOf<String?>(null) }
    // In-sheet source-type multi-select — seeded from saved preference so the
    // user's default choices are pre-applied when the sheet opens.
    var selectedSourceTypes by remember { mutableStateOf(autoSourceTypes) }

    val providers = remember(streams) {
        streams.map { it.addonName }.distinct().sorted()
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
        shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp)
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
                    overflow = TextOverflow.Ellipsis
                )
                if (isLoading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(24.dp).padding(4.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.primary
                    )
                } else {
                    IconButton(onClick = onRefresh) {
                        Icon(
                            Icons.Default.Refresh,
                            contentDescription = "Refresh streams",
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }

            // Quality filter chips (always visible)
            LazyRow(
                contentPadding = PaddingValues(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(bottom = 8.dp)
            ) {
                items(QualityFilter.entries) { filter ->
                    val count = filterCounts[filter] ?: 0
                    FilterChip(
                        selected = selectedFilter == filter,
                        onClick = { selectedFilter = filter },
                        label = {
                            Text(
                                if (count > 0) "${filter.label} ($count)"
                                else filter.label
                            )
                        },
                        enabled = count > 0 || isLoading
                    )
                }
            }

            // Provider filter chips (rendered below quality chips)
            if (providers.size > 1) {
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.padding(bottom = 8.dp)
                ) {
                    item {
                        FilterChip(
                            selected = selectedProvider == null,
                            onClick = { selectedProvider = null },
                            label = { Text("All Providers") }
                        )
                    }
                    items(providers) { provider ->
                        FilterChip(
                            selected = selectedProvider == provider,
                            onClick = { selectedProvider = provider },
                            label = { Text(provider) }
                        )
                    }
                }
            }

            // Source-type filter chips — multi-select (BluRay / WEB-DL / Remux / WEBRip / …).
            LazyRow(
                contentPadding = PaddingValues(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(bottom = 8.dp)
            ) {
                items(SourceTypeFilter.ORDERED) { type ->
                    val count = sourceTypeCounts[type] ?: 0
                    FilterChip(
                        selected = type in selectedSourceTypes,
                        onClick = {
                            selectedSourceTypes = if (type in selectedSourceTypes) {
                                selectedSourceTypes - type
                            } else {
                                selectedSourceTypes + type
                            }
                        },
                        label = {
                            Text(if (count > 0) "${type.label} ($count)" else type.label)
                        },
                        enabled = count > 0 || isLoading
                    )
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
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                if (autoPickTriggered) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(14.dp),
                        strokeWidth = 2.dp
                    )
                    Text(
                        text = "Auto-picking…",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                } else if (isLoading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(14.dp),
                        strokeWidth = 2.dp
                    )
                    Text(
                        text = "Resolving…",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
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
                        CircularProgressIndicator()
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            "Resolving streams from addons…",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
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
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(48.dp)
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            "No streams found",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            "Make sure you have addons installed with Real-Debrid configured",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
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
                        color = MaterialTheme.colorScheme.onSurfaceVariant
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
                            onClick = { onStreamSelected(resolved) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun StreamItem(
    resolvedStream: ResolvedStream,
    episodeRuntimeMinutes: Int? = null,
    isAutoMatch: Boolean = false,
    onClick: () -> Unit
) {
    val stream = resolvedStream.stream

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(20.dp)
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
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(32.dp)
            )

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                // Stream name (addon name + stream quality)
                Text(
                    text = stream.displayName,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold
                )

                // Quality info from title field (often contains codec, resolution, size)
                if (stream.qualityInfo.isNotBlank()) {
                    Text(
                        text = stream.qualityInfo,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                // Additional description from addons like AIOStreams
                if (!stream.description.isNullOrBlank()) {
                    Text(
                        text = stream.description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                // File size and addon name row
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Auto-match badge (only visible when sheet was force-opened)
                    if (isAutoMatch) {
                        Surface(
                            shape = RoundedCornerShape(20.dp),
                            color = MaterialTheme.colorScheme.tertiaryContainer
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            ) {
                                Icon(
                                    Icons.Default.AutoAwesome,
                                    contentDescription = null,
                                    modifier = Modifier.size(10.dp),
                                    tint = MaterialTheme.colorScheme.onTertiaryContainer
                                )
                                Spacer(modifier = Modifier.width(3.dp))
                                Text(
                                    text = "Would auto-pick",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onTertiaryContainer
                                )
                            }
                        }
                    }

                    // File size
                    stream.fileSizeFormatted?.let { size ->
                        Surface(
                            shape = RoundedCornerShape(20.dp),
                            color = MaterialTheme.colorScheme.secondaryContainer
                        ) {
                            Text(
                                text = size,
                                style = MaterialTheme.typography.labelSmall,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                    }

                    // Estimated Mbps
                    stream.estimateMbps(episodeRuntimeMinutes)?.let { mbps ->
                        Surface(
                            shape = RoundedCornerShape(20.dp),
                            color = MaterialTheme.colorScheme.surfaceContainerHighest
                        ) {
                            Text(
                                text = mbps,
                                style = MaterialTheme.typography.labelSmall,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                    }

                    // Addon source
                    Text(
                        text = "via ${resolvedStream.addonName}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}
