package com.playbridge.player.preplay

import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
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
import com.playbridge.player.ui.player.*
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
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                AsyncImage(
                    model = ImageRequest.Builder(context)
                        .data(payload.backdropUrl ?: payload.posterUrl)
                        .crossfade(true)
                        .build(),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .fillMaxSize()
                        .then(if (payload.forcePicker) Modifier.blur(20.dp) else Modifier)
                )

                // Global dark overlay for manual picker mode
                if (payload.forcePicker) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color.Black.copy(alpha = 0.6f))
                    )
                }

                // Opaque to transparent gradient (left to right)
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            Brush.horizontalGradient(
                                colors = listOf(
                                    Color.Black.copy(alpha = 0.98f),
                                    Color.Black.copy(alpha = 0.90f),
                                    Color.Black.copy(alpha = 0.60f),
                                    Color.Transparent
                                ),
                                startX = 0f,
                                endX = 1400f
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

                        val focusRequester = remember { androidx.compose.ui.focus.FocusRequester() }
                        LaunchedEffect(streams) {
                            if (streams.isNotEmpty() && !willAutoPick) {
                                delay(200) // Small delay for layout
                                try { focusRequester.requestFocus() } catch(_: Exception) {}
                            }
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
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 4.dp, vertical = 12.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            // Resolution
                            FilterDropdownChip(
                                label = "Resolution",
                                valueText = resolutionRankLabel(resolutionRank),
                                isCustom = resolutionRank != null
                            ) { dismiss ->
                                FilterDropdownItem(
                                    label = "All (${streams.size})",
                                    selected = resolutionRank == null,
                                    onClick = { resolutionRank = null; dismiss() }
                                )
                                listOf(4, 3, 2, 1).forEach { rank ->
                                    val count = resolutionCounts[rank] ?: 0
                                    FilterDropdownItem(
                                        label = "${resolutionRankLabel(rank)} ($count)",
                                        selected = resolutionRank == rank,
                                        enabled = count > 0,
                                        onClick = { resolutionRank = rank; dismiss() }
                                    )
                                }
                            }
 
                            // Addon
                            FilterDropdownChip(
                                label = "Addon",
                                valueText = selectedAddon ?: "All",
                                isCustom = selectedAddon != null,
                                enabled = availableAddons.isNotEmpty()
                            ) { dismiss ->
                                FilterDropdownItem(
                                    label = "All Addons",
                                    selected = selectedAddon == null,
                                    onClick = { selectedAddon = null; dismiss() }
                                )
                                availableAddons.forEach { addon ->
                                    val count = streams.count { it.addonName == addon }
                                    FilterDropdownItem(
                                        label = "$addon ($count)",
                                        selected = selectedAddon == addon,
                                        onClick = { selectedAddon = addon; dismiss() }
                                    )
                                }
                            }
 
                            // Source Quality (multi-select)
                            val sourceValueText = when (selectedSourceTypes.size) {
                                0 -> "Any"
                                1 -> SourceTypeRanker.labelOf(selectedSourceTypes.first())
                                else -> "${SourceTypeRanker.labelOf(selectedSourceTypes.first())} +${selectedSourceTypes.size - 1}"
                            }
                            FilterDropdownChip(
                                label = "Source Quality",
                                valueText = sourceValueText,
                                isCustom = selectedSourceTypes.isNotEmpty()
                            ) { _ ->
                                if (selectedSourceTypes.isNotEmpty()) {
                                    FilterDropdownItem(
                                        label = "Clear selection",
                                        selected = false,
                                        onClick = { selectedSourceTypes = emptySet() }
                                    )
                                    HorizontalDivider()
                                }
                                SourceTypeRanker.ORDERED_LABELS.forEach { (key, label) ->
                                    val count = sourceTypeCounts[key] ?: 0
                                    val isSelected = key in selectedSourceTypes
                                    FilterDropdownItem(
                                        label = "$label ($count)",
                                        selected = isSelected,
                                        enabled = count > 0,
                                        onClick = {
                                            selectedSourceTypes = if (isSelected) selectedSourceTypes - key
                                            else selectedSourceTypes + key
                                        }
                                    )
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
                                itemsIndexed(items = filteredStreams) { index, s ->
                                    StreamItem(
                                        stream = s,
                                        isAutoPick = s == streams.firstOrNull(),
                                        modifier = if (index == 0) Modifier.focusRequester(focusRequester) else Modifier,
                                        onClick = { onStreamSelected(s) }
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

    // Removed local implementations of resolutionRankLabel, TvDropdownFilterChip, and StreamItem
    // as they are now in StreamSelectionComponents.kt
