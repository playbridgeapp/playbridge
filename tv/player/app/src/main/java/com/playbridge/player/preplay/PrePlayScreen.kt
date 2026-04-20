package com.playbridge.player.preplay

import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.*
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import com.playbridge.shared.stremio.ScoredStremioStream
import com.playbridge.shared.stremio.SourceTypeRanker
import com.playbridge.shared.stremio.StremioClient
import com.playbridge.player.ui.theme.PlayBridgeTVTheme
import com.playbridge.shared.protocol.ContentPlayPayload
import kotlinx.coroutines.delay

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun PrePlayScreen(
    payload: ContentPlayPayload,
    isLaunching: Boolean = false,
    launchCountdown: Int = 0,
    onStreamSelected: (ScoredStremioStream) -> Unit,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    var streams by remember { mutableStateOf<List<ScoredStremioStream>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }

    val willAutoPick = remember(payload) {
        val prefs = context.getSharedPreferences("browser_prefs", android.content.Context.MODE_PRIVATE)
        val autoQuality = payload.defaultVideoQuality ?: prefs.getString("auto_stream_quality", "") ?: ""
        !payload.forcePicker && autoQuality.isNotEmpty()
    }

    BackHandler(onBack = onBack)

    LaunchedEffect(payload) {
        isLoading = true
        try {
            val prefs = context.getSharedPreferences("browser_prefs", android.content.Context.MODE_PRIVATE)
            val autoQuality = payload.defaultVideoQuality ?: prefs.getString("auto_stream_quality", "") ?: ""
            val autoMaxMbps = payload.maxBitrateCapMbps ?: prefs.getString("auto_stream_max_mbps", "")?.toDoubleOrNull()
            val preferredAddon = payload.preferredAddonBaseUrl ?: prefs.getString("auto_stream_addon", "") ?: ""
            val prefSourceTypesCsv = prefs.getString("auto_stream_source_types", "") ?: ""
            val sourceTypes: List<String>? = (payload.preferredSourceTypes?.takeIf { it.isNotEmpty() }
                ?: prefSourceTypesCsv.split(',').map { it.trim() }.filter { it.isNotEmpty() }.takeIf { it.isNotEmpty() })

            streams = StremioClient.resolveStreamsByContentId(
                addonBaseUrls = payload.addonBaseUrls,
                addonNames = payload.addonNames,
                contentId = payload.contentId,
                contentType = payload.contentType,
                season = payload.season,
                episode = payload.episode,
                qualityPreference = autoQuality.takeIf { it.isNotEmpty() },
                preferredAddonBaseUrl = preferredAddon.takeIf { it.isNotEmpty() },
                preferredAddonName = payload.preferredAddonName ?: prefs.getString("auto_stream_addon_name", ""),
                preferredSourceTypes = sourceTypes,
                runtimeMinutes = payload.episodeRuntimeMinutes,
                maxBitrateMbps = autoMaxMbps
            )
            if (streams.isEmpty()) {
                error = "No streams found for this title."
            }
        } catch (e: Exception) {
            error = "Resolution failed: ${e.message}"
        } finally {
            isLoading = false
        }
    }

    PlayBridgeTVTheme {
        Box(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
            // 1. Clear Backdrop on the right
            Box(modifier = Modifier.fillMaxSize()) {
                AsyncImage(
                    model = ImageRequest.Builder(context)
                        .data(payload.backdropUrl ?: payload.posterUrl)
                        .crossfade(true)
                        .build(),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
                // Opaque to transparent gradient (left to right)
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            Brush.horizontalGradient(
                                colors = listOf(
                                    Color.Black.copy(alpha = 0.98f),
                                    Color.Black.copy(alpha = 0.9f),
                                    Color.Black.copy(alpha = 0.4f),
                                    Color.Transparent
                                ),
                                startX = 0f,
                                endX = 1400f // Wider gradient for better text contrast
                            )
                        )
                )
            }

            // 2. Content Info & Resolution Status
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(48.dp),
                horizontalArrangement = Arrangement.spacedBy(48.dp)
            ) {
                // Left: Poster & Meta (Solid background area)
                Column(modifier = Modifier.weight(0.45f)) {
                    if (payload.logoUrl != null) {
                        AsyncImage(
                            model = payload.logoUrl,
                            contentDescription = payload.title,
                            modifier = Modifier.height(100.dp).fillMaxWidth(),
                            contentScale = ContentScale.Fit,
                            alignment = Alignment.CenterStart
                        )
                    } else {
                        Text(
                            text = payload.title,
                            style = MaterialTheme.typography.displayMedium,
                            fontWeight = FontWeight.Black,
                            color = Color.White
                        )
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    val metaItems = mutableListOf<String>()
                    payload.year?.let { metaItems.add(it) }
                    payload.rating?.let { metaItems.add("IMDb $it") }
                    payload.runtime?.let { metaItems.add(it) }

                    Text(
                        text = metaItems.joinToString("  •  "),
                        style = MaterialTheme.typography.labelLarge,
                        color = Color(0xFFCCCCCC)
                    )

                    if (payload.contentType == "series" && payload.season != null && payload.episode != null) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "S${payload.season} E${payload.episode}${if (payload.episodeTitle != null) " - ${payload.episodeTitle}" else ""}",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }

                    Spacer(modifier = Modifier.height(24.dp))

                    Text(
                        text = payload.overview ?: "",
                        style = MaterialTheme.typography.bodyLarge,
                        maxLines = 8,
                        overflow = TextOverflow.Ellipsis,
                        color = Color(0xFFBBBBBB)
                    )

                    Spacer(modifier = Modifier.weight(1f))

                    StatusSection(
                        isLoading = isLoading,
                        isLaunching = isLaunching,
                        countdown = launchCountdown,
                        error = error,
                        onBack = onBack
                    )
                }

                // Right: Stream List (Transparent area)
                Column(modifier = Modifier.weight(0.55f)) {
                    if (streams.isNotEmpty() && !isLaunching && !willAutoPick) {
                        // Filters (shown only when phone forced the picker via long-press).
                        // Mirrors StreamPickerSheet on phone: single row with Resolution / Addon / Source Quality.
                        //
                        // All three dropdowns preselect from the user's preferences.
                        // Payload values win over TV-side SharedPreferences, matching the
                        // precedence used elsewhere in this screen for defaultVideoQuality etc.
                        // A LaunchedEffect below auto-clears any preselected value that doesn't
                        // match any stream once resolution completes, so the user never sees
                        // "0 / N" with an impossible default (e.g. preferred addon returned nothing).
                        val initialResolutionRank = remember(payload) {
                            val p = context.getSharedPreferences("browser_prefs", android.content.Context.MODE_PRIVATE)
                            val quality = payload.defaultVideoQuality ?: p.getString("auto_stream_quality", "") ?: ""
                            when (quality.lowercase()) {
                                "2160p", "4k", "uhd" -> 4
                                "1080p", "1080"      -> 3
                                "720p",  "720"       -> 2
                                else                 -> null
                            }
                        }
                        val initialAddon = remember(payload) {
                            val p = context.getSharedPreferences("browser_prefs", android.content.Context.MODE_PRIVATE)
                            (payload.preferredAddonName ?: p.getString("auto_stream_addon_name", ""))
                                ?.takeIf { it.isNotBlank() }
                        }
                        val initialSourceTypes = remember(payload) {
                            val p = context.getSharedPreferences("browser_prefs", android.content.Context.MODE_PRIVATE)
                            val fromPayload = payload.preferredSourceTypes?.takeIf { it.isNotEmpty() }
                            val fromPrefs = p.getString("auto_stream_source_types", "")
                                ?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }
                                ?.takeIf { it.isNotEmpty() }
                            (fromPayload ?: fromPrefs ?: emptyList()).toSet()
                        }

                        var resolutionRank by remember(initialResolutionRank) {
                            mutableStateOf(initialResolutionRank)
                        }
                        var selectedAddon by remember(initialAddon) {
                            mutableStateOf(initialAddon)
                        }
                        var selectedSourceTypes by remember(initialSourceTypes) {
                            mutableStateOf(initialSourceTypes)
                        }

                        // Auto-clear filters that yielded zero matches once streams resolve.
                        LaunchedEffect(streams) {
                            if (streams.isEmpty()) return@LaunchedEffect
                            if (resolutionRank != null && streams.none { it.rank == resolutionRank }) {
                                resolutionRank = null
                            }
                            if (selectedAddon != null && streams.none { it.addonName == selectedAddon }) {
                                selectedAddon = null
                            }
                            if (selectedSourceTypes.isNotEmpty() &&
                                streams.none { SourceTypeRanker.matches(it.name, it.title, null, selectedSourceTypes) }) {
                                selectedSourceTypes = emptySet()
                            }
                        }

                        val availableAddons = remember(streams) {
                            streams.mapNotNull { it.addonName?.takeIf { n -> n.isNotBlank() } }.distinct().sorted()
                        }

                        val resolutionCounts = remember(streams) {
                            listOf(4, 3, 2, 1).associateWith { rank -> streams.count { it.rank == rank } }
                        }

                        val sourceTypeCounts = remember(streams) {
                            SourceTypeRanker.ORDERED_LABELS.associate { (key, _) ->
                                key to streams.count { SourceTypeRanker.matches(it.name, it.title, null, listOf(key)) }
                            }
                        }

                        val filteredStreams = remember(streams, resolutionRank, selectedAddon, selectedSourceTypes) {
                            streams.filter { s ->
                                (resolutionRank == null || s.rank == resolutionRank) &&
                                    (selectedAddon == null || s.addonName == selectedAddon) &&
                                    (selectedSourceTypes.isEmpty() ||
                                        SourceTypeRanker.matches(s.name, s.title, null, selectedSourceTypes))
                            }
                        }

                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                            modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp)
                        ) {
                            Text(
                                text = "Select a Stream",
                                style = MaterialTheme.typography.titleMedium,
                                color = Color.White
                            )
                            if (payload.forcePicker) {
                                Text(
                                    text = "${filteredStreams.size} / ${streams.size}",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                        }

                        if (payload.forcePicker) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .horizontalScroll(rememberScrollState())
                                    .padding(bottom = 12.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                // Resolution
                                TvDropdownFilterChip(
                                    label = "Resolution",
                                    valueText = resolutionRankLabel(resolutionRank),
                                    isCustom = resolutionRank != null
                                ) { dismiss ->
                                    DropdownMenuItem(
                                        text = { androidx.compose.material3.Text("All (${streams.size})") },
                                        onClick = { resolutionRank = null; dismiss() },
                                        leadingIcon = {
                                            if (resolutionRank == null) {
                                                androidx.compose.material3.Icon(Icons.Default.Check, contentDescription = null)
                                            } else {
                                                Spacer(Modifier.size(24.dp))
                                            }
                                        }
                                    )
                                    listOf(4, 3, 2, 1).forEach { rank ->
                                        val count = resolutionCounts[rank] ?: 0
                                        DropdownMenuItem(
                                            text = {
                                                androidx.compose.material3.Text(
                                                    "${resolutionRankLabel(rank)} ($count)"
                                                )
                                            },
                                            onClick = { resolutionRank = rank; dismiss() },
                                            leadingIcon = {
                                                if (resolutionRank == rank) {
                                                    androidx.compose.material3.Icon(Icons.Default.Check, contentDescription = null)
                                                } else {
                                                    Spacer(Modifier.size(24.dp))
                                                }
                                            },
                                            enabled = count > 0
                                        )
                                    }
                                }

                                // Addon
                                TvDropdownFilterChip(
                                    label = "Addon",
                                    valueText = selectedAddon ?: "All",
                                    isCustom = selectedAddon != null,
                                    enabled = availableAddons.isNotEmpty()
                                ) { dismiss ->
                                    DropdownMenuItem(
                                        text = { androidx.compose.material3.Text("All Addons") },
                                        onClick = { selectedAddon = null; dismiss() },
                                        leadingIcon = {
                                            if (selectedAddon == null) {
                                                androidx.compose.material3.Icon(Icons.Default.Check, contentDescription = null)
                                            } else {
                                                Spacer(Modifier.size(24.dp))
                                            }
                                        }
                                    )
                                    availableAddons.forEach { addon ->
                                        val count = streams.count { it.addonName == addon }
                                        DropdownMenuItem(
                                            text = { androidx.compose.material3.Text("$addon ($count)") },
                                            onClick = { selectedAddon = addon; dismiss() },
                                            leadingIcon = {
                                                if (selectedAddon == addon) {
                                                    androidx.compose.material3.Icon(Icons.Default.Check, contentDescription = null)
                                                } else {
                                                    Spacer(Modifier.size(24.dp))
                                                }
                                            }
                                        )
                                    }
                                }

                                // Source Quality (multi-select)
                                val sourceValueText = when (selectedSourceTypes.size) {
                                    0 -> "Any"
                                    1 -> SourceTypeRanker.labelOf(selectedSourceTypes.first())
                                    else -> "${SourceTypeRanker.labelOf(selectedSourceTypes.first())} +${selectedSourceTypes.size - 1}"
                                }
                                TvDropdownFilterChip(
                                    label = "Source Quality",
                                    valueText = sourceValueText,
                                    isCustom = selectedSourceTypes.isNotEmpty()
                                ) { _ ->
                                    if (selectedSourceTypes.isNotEmpty()) {
                                        DropdownMenuItem(
                                            text = { androidx.compose.material3.Text("Clear selection") },
                                            onClick = { selectedSourceTypes = emptySet() },
                                            leadingIcon = {
                                                androidx.compose.material3.Icon(Icons.Default.Close, contentDescription = null)
                                            }
                                        )
                                        HorizontalDivider()
                                    }
                                    SourceTypeRanker.ORDERED_LABELS.forEach { (key, label) ->
                                        val count = sourceTypeCounts[key] ?: 0
                                        val isSelected = key in selectedSourceTypes
                                        DropdownMenuItem(
                                            text = { androidx.compose.material3.Text("$label ($count)") },
                                            onClick = {
                                                selectedSourceTypes = if (isSelected) selectedSourceTypes - key
                                                else selectedSourceTypes + key
                                            },
                                            leadingIcon = {
                                                if (isSelected) {
                                                    androidx.compose.material3.Icon(Icons.Default.Check, contentDescription = null)
                                                } else {
                                                    Spacer(Modifier.size(24.dp))
                                                }
                                            },
                                            enabled = count > 0
                                        )
                                    }
                                }
                            }
                        }

                        if (filteredStreams.isEmpty()) {
                            Text(
                                text = "No streams match the selected filters.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = Color(0xFFBBBBBB),
                                modifier = Modifier.padding(top = 8.dp)
                            )
                        } else {
                            LazyColumn(
                                verticalArrangement = Arrangement.spacedBy(12.dp),
                                contentPadding = PaddingValues(bottom = 24.dp)
                            ) {
                                items(filteredStreams) { stream ->
                                    StreamItem(
                                        stream = stream,
                                        onClick = { onStreamSelected(stream) }
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun StatusSection(
    isLoading: Boolean,
    isLaunching: Boolean,
    countdown: Int,
    error: String?,
    onBack: () -> Unit
) {
    AnimatedContent(
        targetState = Triple(isLoading, isLaunching, error),
        transitionSpec = {
            fadeIn(animationSpec = tween(500)) togetherWith fadeOut(animationSpec = tween(500))
        },
        label = "StatusAnimation"
    ) { (loading, launching, err) ->
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (loading || (launching && countdown == 0)) {
                CircularProgressIndicator(
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(28.dp),
                    strokeWidth = 3.dp
                )
                Spacer(modifier = Modifier.width(16.dp))
                Text(
                    text = "Resolving streams...",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = Color.White
                )
            } else if (launching && countdown > 0) {
                Icon(
                    Icons.Default.CheckCircle,
                    contentDescription = null,
                    tint = Color(0xFF4CAF50),
                    modifier = Modifier.size(28.dp)
                )
                Spacer(modifier = Modifier.width(16.dp))
                Column {
                    Text(
                        text = "Streams ready",
                        style = MaterialTheme.typography.labelSmall,
                        color = Color(0xFF4CAF50),
                        fontWeight = FontWeight.Bold
                    )
                    StatusText(countdown)
                }
            } else if (err != null) {
                Column {
                    Text(
                        text = err,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Button(onClick = onBack) {
                        Text("Go Back", color = Color.White)
                    }
                }
            }
        }
    }
}

@Composable
private fun StatusText(countdown: Int) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = "Playing starts in ",
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            color = Color.White
        )

        AnimatedContent(
            targetState = countdown,
            transitionSpec = {
                (slideInVertically { height -> height } + fadeIn()).togetherWith(
                    slideOutVertically { height -> -height } + fadeOut()
                )
            },
            label = "CountdownAnimation"
        ) { targetCount ->
            Text(
                text = "$targetCount",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )
        }

        Text(
            text = " seconds...",
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            color = Color.White
        )
    }
}

/** Map a ScoredStremioStream.rank (1..4) to its display label. null = "All". */
private fun resolutionRankLabel(rank: Int?): String = when (rank) {
    4 -> "4K"
    3 -> "1080p"
    2 -> "720p"
    1 -> "SD"
    else -> "All"
}

/**
 * TV-friendly filter chip: a focusable Surface that anchors a DropdownMenu just
 * below itself. Mirrors the phone's `DropdownFilterChip` in StreamPickerSheet
 * so both screens look and behave the same. `isCustom = true` highlights the
 * chip as "user has deviated from the default".
 *
 * The [content] lambda is given a `dismiss` callback — call it from single-select
 * items to close the menu after choosing; multi-select items can simply omit the
 * call so the menu stays open between toggles.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun TvDropdownFilterChip(
    label: String,
    valueText: String,
    isCustom: Boolean,
    enabled: Boolean = true,
    content: @Composable (dismiss: () -> Unit) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        Surface(
            onClick = { if (enabled) expanded = true },
            shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(50)),
            scale = ClickableSurfaceDefaults.scale(focusedScale = 1.05f),
            colors = ClickableSurfaceDefaults.colors(
                containerColor = if (isCustom) {
                    MaterialTheme.colorScheme.primary.copy(alpha = 0.25f)
                } else {
                    Color.Black.copy(alpha = 0.5f)
                },
                focusedContainerColor = if (isCustom) {
                    MaterialTheme.colorScheme.primary.copy(alpha = 0.45f)
                } else {
                    MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.9f)
                },
                contentColor = Color.White,
                focusedContentColor = Color.White
            ),
            enabled = enabled
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp)
            ) {
                Text(
                    text = "$label: $valueText",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.width(4.dp))
                androidx.compose.material3.Icon(
                    Icons.Default.ArrowDropDown,
                    contentDescription = null,
                    tint = Color.White
                )
            }
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            content { expanded = false }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun StreamItem(
    stream: ScoredStremioStream,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        shape = ClickableSurfaceDefaults.shape(MaterialTheme.shapes.medium),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.05f),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Color.Black.copy(alpha = 0.4f),
            focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.9f)
        )
    ) {
        Row(
            modifier = Modifier.padding(16.dp).fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stream.name ?: "Unknown Stream",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = stream.title ?: "Untitled",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                if (stream.addonName != null) {
                    Text(
                        text = "via ${stream.addonName}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            if (stream.isTargetTier) {
                Surface(
                    shape = MaterialTheme.shapes.small,
                    colors = SurfaceDefaults.colors(
                        containerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.3f)
                    ),
                    modifier = Modifier.padding(start = 8.dp)
                ) {
                    Text(
                        text = "Preferred",
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }
    }
}
