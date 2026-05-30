package com.playbridge.sender.library
import com.playbridge.sender.cast.*

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import androidx.compose.ui.graphics.painter.ColorPainter
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.lerp
import androidx.palette.graphics.Palette
import com.playbridge.sender.data.library.*
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.material.ripple.rememberRipple
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import kotlin.math.roundToInt
import com.playbridge.sender.model.TvDevice

// ==================== Shared Components ====================

@Composable
internal fun BackdropSection(
    backdropUrl: String?,
    logoUrl: String?,
    title: String,
    year: String,
    certification: String,
    rating: String,
    runtime: String,
    genres: List<String>,
    omdbDetails: OmdbResponse? = null,
    onColorExtracted: (Color) -> Unit = {}
) {
    val config = androidx.compose.ui.platform.LocalConfiguration.current
    val height = (config.screenHeightDp * 0.65).dp
    val context = LocalContext.current

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(height)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                .drawWithContent {
                    drawContent()
                    drawRect(
                        brush = Brush.verticalGradient(
                            colors = listOf(Color.Black, Color.Transparent),
                            startY = size.height * 0.4f,
                            endY = size.height
                        ),
                        blendMode = BlendMode.DstIn
                    )
                }
        ) {
            if (backdropUrl != null) {
                AsyncImage(
                    model = ImageRequest.Builder(context)
                        .data(backdropUrl)
                        .crossfade(true)
                        .allowHardware(false) // Required for Palette extraction
                        .build(),
                    onSuccess = { result ->
                        val bitmap = (result.result.drawable as? android.graphics.drawable.BitmapDrawable)?.bitmap
                        if (bitmap != null) {
                            Palette.from(bitmap).generate { palette ->
                                // Try to get a light vibrant or just vibrant color
                                val swatch = palette?.lightVibrantSwatch ?: palette?.vibrantSwatch ?: palette?.dominantSwatch
                                swatch?.let {
                                    val extracted = Color(it.rgb)
                                    // Make it significantly lighter (40% towards white)
                                    onColorExtracted(lerp(extracted, Color.White, 0.4f))
                                }
                            }
                        }
                    },
                    contentDescription = title,
                    contentScale = ContentScale.Crop,
                    placeholder = ColorPainter(MaterialTheme.colorScheme.surfaceVariant),
                    error = ColorPainter(MaterialTheme.colorScheme.surfaceVariant),
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.surface)
                )
            }
        }

        // Contents at bottom
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(bottom = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            if (logoUrl != null) {
                AsyncImage(
                    model = ImageRequest.Builder(LocalContext.current)
                        .data(logoUrl)
                        .crossfade(true)
                        .build(),
                    contentDescription = title,
                    contentScale = ContentScale.Fit,
                    placeholder = ColorPainter(Color.Transparent),
                    error = ColorPainter(Color.Transparent),
                    modifier = Modifier
                        .fillMaxWidth(0.7f)
                        .height(130.dp)
                )
            } else {
                Text(
                    text = title,
                    style = MaterialTheme.typography.displaySmall,
                    fontWeight = FontWeight.Black,
                    color = Color.White,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 16.dp)
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            if (genres.isNotEmpty()) {
                Text(
                    text = genres.take(3).joinToString("  "),
                    style = MaterialTheme.typography.labelLarge,
                    color = Color.White.copy(alpha = 0.7f)
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            Row(
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .padding(horizontal = 16.dp)
                    .horizontalScroll(rememberScrollState())
            ) {
                if (year.isNotBlank()) {
                    Text(
                        text = year,
                        style = MaterialTheme.typography.labelLarge,
                        color = Color.White.copy(alpha = 0.5f),
                        modifier = Modifier.align(Alignment.CenterVertically)
                    )
                }
                if (certification.isNotBlank()) {
                    Surface(
                        shape = RoundedCornerShape(4.dp),
                        color = Color.White.copy(alpha = 0.2f),
                        modifier = Modifier.align(Alignment.CenterVertically)
                    ) {
                        Text(
                            text = certification,
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = Color.White.copy(alpha = 0.7f),
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                }

                if (runtime.isNotBlank()) {
                    Text(
                        text = runtime,
                        style = MaterialTheme.typography.labelLarge,
                        color = Color.White.copy(alpha = 0.5f),
                        modifier = Modifier.align(Alignment.CenterVertically)
                    )
                }

                if (rating.isNotBlank() && rating != "0.0") {
                    RatingBadge("TMDB", rating, Color(0xFF01B4E4))
                }

                if (omdbDetails != null) {
                    val imdb = omdbDetails.imdbRating
                    val rt = omdbDetails.rottenTomatoesRating

                    if (!imdb.isNullOrBlank() && imdb != "N/A") {
                        RatingBadge("IMDb", imdb, Color(0xFFF5C518))
                    }

                    if (!rt.isNullOrBlank() && rt != "N/A") {
                        val color = if (rt.contains("%") && rt.replace("%", "").toIntOrNull() ?: 0 >= 60) {
                            Color(0xFFFA320A) // Fresh red
                        } else {
                            Color(0xFF43C00A) // Rotten green splat
                        }
                        RatingBadge("RT", rt, color)
                    }
                }
            }
        }
    }
}

@Composable
internal fun RatingBadge(provider: String, value: String, color: Color) {
    Surface(
        shape = RoundedCornerShape(4.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, color.copy(alpha = 0.5f)),
        color = Color.Transparent
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(provider, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Black, color = color)
            Spacer(modifier = Modifier.width(4.dp))
            Text(value, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = Color.White.copy(alpha = 0.8f))
        }
    }
}

/**
 * A small pill chip showing a [label] (dimmed) followed by a [value] (brighter).
 * Used at the bottom of detail screens to show IDs and metadata sources.
 */
@Composable
internal fun DetailInfoChip(label: String, value: String) {
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.2f)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Icon(
                Icons.Default.Info,
                contentDescription = null,
                tint = Color.White.copy(alpha = 0.5f),
                modifier = Modifier.size(12.dp)
            )
            Text(
                text = "$label:",
                style = MaterialTheme.typography.labelSmall,
                color = Color.White.copy(alpha = 0.5f)
            )
            Text(
                text = value,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.SemiBold,
                color = Color.White.copy(alpha = 0.8f)
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
internal fun SplitPlayButton(
    isTvResolving: Boolean = false,
    isPhoneResolving: Boolean = false,
    resolvingLabel: String = "Resolving…",
    hasAddons: Boolean,
    hasImdbId: Boolean,
    watchProviders: List<TmdbWatchProvider>,
    tvName: String? = null,
    isTvConnected: Boolean = false,
    watchOnTv: Boolean,
    onWatchOnTvChange: (Boolean) -> Unit,
    watchLabel: String = "Watch",
    availableTvDevices: List<TvDevice> = emptyList(),
    selectedTvDevice: TvDevice? = null,
    onTvDeviceSelect: ((TvDevice) -> Unit)? = null,
    onWatchOnTv: () -> Unit,
    onWatchOnTvLongClick: (() -> Unit)? = null,
    onWatchOnPhone: () -> Unit = {},
    onWatchOnPhoneLongClick: (() -> Unit)? = null,
    playerMode: String = "tv",
    onPlayerModeChange: (String) -> Unit = {},
    proxyAvailable: Boolean = false,
    proxyMode: MediaflowProxy.Mode = MediaflowProxy.Mode.OFF,
    onProxyModeChange: (MediaflowProxy.Mode) -> Unit = {},
    themeColor: Color = MaterialTheme.colorScheme.primary
) {
    // Player-mode options mirror CastSheet's "play" mode list.
    val playerOptions = listOf(
        "tv"           to "TV Default",
        "internal"     to "ExoPlayer",
        "internal_vlc" to "LibVLC",
        "internal_mpv" to "MPV",
        "external"     to "External",
        "external_mpv" to "Ext. MPV"
    )
    val selectedPlayerLabel = playerOptions.find { it.first == playerMode }?.second ?: "TV Default"
    var showProvidersSheet by remember { mutableStateOf(false) }
    var showDeviceMenu by remember { mutableStateOf(false) }

    val topProvider = watchProviders.firstOrNull()
    val isBusy = isTvResolving || isPhoneResolving
    val isResolving = if (watchOnTv) isTvResolving else isPhoneResolving
    val canPlay = hasAddons && hasImdbId || watchProviders.isNotEmpty()
    val haptic = LocalHapticFeedback.current

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // ── Mode toggle chip ──────────────────────────────────────────────────
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Watching on",
                style = MaterialTheme.typography.labelSmall,
                color = Color.White.copy(alpha = 0.4f)
            )
            val connectedGreen = Color(0xFF4CAF50)

            ChipDropdown(
                fixedWidth = 150.dp,
                selectedLabel = if (watchOnTv) {
                    if (!tvName.isNullOrBlank()) "TV ($tvName)" else "TV"
                } else "This Device",
                options = listOf("phone" to "This Device") + availableTvDevices.map { (it.uuid.ifBlank { it.ip }) to (it.name.ifBlank { it.ip }) },
                selectedValue = if (watchOnTv) (selectedTvDevice?.uuid ?: selectedTvDevice?.ip ?: "tv") else "phone",
                onSelect = { value ->
                    if (value == "phone") {
                        onWatchOnTvChange(false)
                    } else {
                        onWatchOnTvChange(true)
                        availableTvDevices.find { it.uuid == value || it.ip == value }?.let {
                            onTvDeviceSelect?.invoke(it)
                        }
                    }
                },
                toggleAction = {
                    val togglingToTv = !watchOnTv
                    onWatchOnTvChange(togglingToTv)
                    // If switching to TV and not connected, try to connect to the selected device
                    if (togglingToTv && !isTvConnected && selectedTvDevice != null) {
                        onTvDeviceSelect?.invoke(selectedTvDevice)
                    }
                },
                chipLabelColor = if (watchOnTv && isTvConnected) connectedGreen else Color.Unspecified,
                themeColor = themeColor,
                leadingIcon = {
                    Icon(
                        imageVector = if (watchOnTv) Icons.Default.Tv else Icons.Default.PhoneAndroid,
                        contentDescription = null,
                        modifier = Modifier.size(13.dp),
                        tint = if (watchOnTv && isTvConnected) connectedGreen else Color.White.copy(alpha = 0.7f)
                    )
                },
                trailingIcon = {
                    Icon(
                        imageVector = Icons.Default.SwapHoriz,
                        contentDescription = "Switch destination",
                        modifier = Modifier.size(13.dp),
                        tint = Color.White.copy(alpha = 0.45f)
                    )
                }
            )

            // Player-mode + proxy chips (only meaningful when casting to TV).
            // Mirrors the CastSheet chips so users can tweak engine / proxy
            // without having to open the bottom sheet.
            if (watchOnTv) {
                ChipDropdown(
                    selectedLabel = selectedPlayerLabel,
                    options = playerOptions,
                    selectedValue = playerMode,
                    onSelect = onPlayerModeChange,
                    themeColor = themeColor
                )
                if (proxyAvailable) {
                    ChipDropdown(
                        selectedLabel = proxyMode.label,
                        options = MediaflowProxy.Mode.entries.map { it.name to it.label },
                        selectedValue = proxyMode.name,
                        onSelect = { value -> onProxyModeChange(MediaflowProxy.Mode.valueOf(value)) },
                        themeColor = themeColor
                    )
                }
            }
        }

        // ── Single Watch button ───────────────────────────────────────────────
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .height(60.dp)
                .combinedClickable(
                    enabled = !isBusy && canPlay,
                    onClick = {
                        if (hasAddons && hasImdbId) {
                            if (watchOnTv) onWatchOnTv() else onWatchOnPhone()
                        } else if (watchProviders.isNotEmpty()) showProvidersSheet = true
                    },
                    onLongClick = if (!isBusy) {
                        {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            if (watchOnTv) onWatchOnTvLongClick?.invoke()
                            else onWatchOnPhoneLongClick?.invoke()
                        }
                    } else null
                ),
            shape = RoundedCornerShape(16.dp),
            color = if (!isBusy && canPlay)
                themeColor
            else
                themeColor.copy(alpha = 0.4f)
        ) {
            val contentColor = if (themeColor.luminance() > 0.5f) Color.Black else Color.White
            Box(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
                contentAlignment = Alignment.Center
            ) {
                // Icon anchored to the left
                Icon(
                    imageVector = if (watchOnTv) Icons.Default.Tv else Icons.Default.PhoneAndroid,
                    contentDescription = null,
                    modifier = Modifier
                        .align(Alignment.CenterStart)
                        .size(24.dp),
                    tint = if (!isBusy && canPlay)
                        contentColor
                    else
                        contentColor.copy(alpha = 0.4f)
                )
                // Text centered in the button
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    if (isResolving) {
                        Text(
                            text = resolvingLabel.ifBlank { "Resolving…" },
                            fontWeight = FontWeight.Bold,
                            style = MaterialTheme.typography.titleSmall,
                            color = contentColor.copy(alpha = 0.4f)
                        )
                    } else {
                        Text(
                            text = watchLabel,
                            fontWeight = FontWeight.Bold,
                            style = MaterialTheme.typography.titleSmall,
                            color = if (!isBusy && canPlay)
                                contentColor
                            else
                                contentColor.copy(alpha = 0.4f)
                        )
                        if (topProvider != null) {
                            Text(
                                text = topProvider.providerName,
                                style = MaterialTheme.typography.labelSmall,
                                color = contentColor.copy(alpha = 0.75f)
                            )
                        }
                    }
                }
            }
        }

        // No-addons hint when TMDB has no provider data either
        if (!hasAddons && watchProviders.isEmpty() && hasImdbId) {
            Text(
                text = "Install a Stremio addon to stream on TV",
                style = MaterialTheme.typography.labelSmall,
                color = Color.White.copy(alpha = 0.4f),
                modifier = Modifier.align(Alignment.CenterHorizontally)
            )
        }
    }

    // ── Watch Providers info sheet ────────────────────────────────────────────
    if (showProvidersSheet) {
        WatchProvidersSheet(
            providers = watchProviders,
            onDismiss = { showProvidersSheet = false }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun WatchProvidersSheet(
    providers: List<TmdbWatchProvider>,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(bottom = 32.dp)
        ) {
            Text(
                text = "Where to Watch",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(bottom = 8.dp)
            )
            Text(
                text = "Available on these streaming services",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 20.dp)
            )
            providers.forEach { provider ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    if (provider.logoUrl != null) {
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.size(48.dp)
                        ) {
                            AsyncImage(
                                model = provider.logoUrl,
                                contentDescription = provider.providerName,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize()
                            )
                        }
                    } else {
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            modifier = Modifier.size(48.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    Icons.Default.PlayCircle,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                    Text(
                        text = provider.providerName,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.SemiBold
                    )
                }
                if (provider != providers.last()) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                }
            }
        }
    }
}

@Composable
internal fun ActionButtons(
    isWatchlisted: Boolean,
    tracked: WatchlistEntity? = null,
    onToggleWatchlist: () -> Unit,
    trailerUrl: String? = null,
    onPlayTrailer: ((String) -> Unit)? = null,
    themeColor: Color = MaterialTheme.colorScheme.primary
) {
    val trailerReady = trailerUrl != null && onPlayTrailer != null
    val status = tracked?.let { WatchlistStatus.from(it.status) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 16.dp, bottom = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Status chip — shown when the item is tracked with a meaningful status
        if (status != null && status != WatchlistStatus.PLAN_TO_WATCH) {
            val statusColor = when (status) {
                WatchlistStatus.WATCHING      -> themeColor
                WatchlistStatus.COMPLETED     -> Color(0xFF4CAF50)
                WatchlistStatus.ON_HOLD       -> Color(0xFFFF9800)
                WatchlistStatus.DROPPED       -> MaterialTheme.colorScheme.error
                else -> MaterialTheme.colorScheme.secondary
            }
            val progressLabel = if (status == WatchlistStatus.WATCHING &&
                tracked.seasonProgress != null && tracked.episodeProgress != null
            ) " · S${tracked.seasonProgress} E${tracked.episodeProgress}" else ""

            Surface(
                shape = RoundedCornerShape(20.dp),
                color = statusColor.copy(alpha = 0.15f),
                border = androidx.compose.foundation.BorderStroke(1.dp, statusColor.copy(alpha = 0.6f))
            ) {
                Text(
                    text = status.displayName + progressLabel,
                    style = MaterialTheme.typography.labelMedium,
                    color = statusColor,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                )
            }
        }

        Row(
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.clickable { onToggleWatchlist() }
        ) {
            Icon(
                if (isWatchlisted) Icons.Default.Bookmark else Icons.Default.BookmarkBorder,
                contentDescription = if (isWatchlisted) "Remove from Watchlist" else "Add to Watchlist",
                tint = if (isWatchlisted) themeColor else Color.White.copy(alpha=0.7f)
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                if (isWatchlisted) "In Watchlist" else "Add to Watchlist",
                style = MaterialTheme.typography.labelSmall,
                color = if (isWatchlisted) themeColor else Color.White.copy(alpha=0.7f)
            )
        }
        Spacer(modifier = Modifier.width(48.dp))
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = if (trailerReady) Modifier.clickable { onPlayTrailer!!(trailerUrl!!) } else Modifier
        ) {
            Icon(
                Icons.Default.Movie,
                contentDescription = "Play Trailer",
                tint = if (trailerReady) Color.White else Color.White.copy(alpha = 0.3f)
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                if (trailerReady) "Play Trailer" else "No Trailer",
                style = MaterialTheme.typography.labelSmall,
                color = if (trailerReady) Color.White else Color.White.copy(alpha = 0.3f)
            )
        }
        } // close Row
    } // close outer Column
}

@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
internal fun EpisodeItem(
    episode: StremioVideo,
    hasAddon: Boolean = false,
    isResolving: Boolean = false,
    isWatched: Boolean = false,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onToggleWatched: () -> Unit = {},
    themeColor: Color = MaterialTheme.colorScheme.primary
) {
    val haptic = LocalHapticFeedback.current
    val containerColor = Color.Transparent

    val offsetX = remember { androidx.compose.animation.core.Animatable(0f) }
    val scope = rememberCoroutineScope()
    val density = androidx.compose.ui.platform.LocalDensity.current
    val maxSwipePx = with(density) { 100.dp.toPx() }
    val triggerThresholdPx = with(density) { 60.dp.toPx() }

    val currentOnToggleWatched by rememberUpdatedState(onToggleWatched)

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .pointerInput(Unit) {
                detectHorizontalDragGestures(
                    onDragEnd = {
                        if (offsetX.value < -triggerThresholdPx) {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            currentOnToggleWatched()
                        }
                        scope.launch { offsetX.animateTo(0f, androidx.compose.animation.core.spring()) }
                    },
                    onDragCancel = {
                        scope.launch { offsetX.animateTo(0f, androidx.compose.animation.core.spring()) }
                    },
                    onHorizontalDrag = { change: PointerInputChange, dragAmount: Float ->
                        change.consume()
                        val newOffset = (offsetX.value + dragAmount).coerceIn(-maxSwipePx, 0f)
                        scope.launch { offsetX.snapTo(newOffset) }
                    }
                )
            }
    ) {
        Column(
            modifier = Modifier
                .offset { androidx.compose.ui.unit.IntOffset(offsetX.value.roundToInt(), 0) }
                .fillMaxWidth()
                .background(containerColor)
                .combinedClickable(
                    enabled = hasAddon && !isResolving,
                    onClick = onClick,
                    onLongClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        onLongClick()
                    }
                )
                .padding(horizontal = 24.dp, vertical = 16.dp)
        ) {
            Row(
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Episode Image with badge
            Box(
                modifier = Modifier
                    .width(140.dp)
                    .height(80.dp)
                    .clip(RoundedCornerShape(8.dp))
            ) {
                if (episode.thumbnail != null) {
                    AsyncImage(
                        model = episode.thumbnail,
                        contentDescription = episode.title,
                        contentScale = ContentScale.Crop,
                        placeholder = ColorPainter(themeColor.copy(alpha = 0.1f)),
                        error = ColorPainter(themeColor.copy(alpha = 0.1f)),
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(themeColor.copy(alpha = 0.15f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Default.Movie, contentDescription = null, tint = themeColor.copy(alpha = 0.4f))
                    }
                }

                // Badge overlay
                Surface(
                    color = Color.Black.copy(alpha = 0.8f),
                    shape = RoundedCornerShape(bottomEnd = 8.dp),
                    modifier = Modifier.align(Alignment.TopStart)
                ) {
                    Text(
                        text = (episode.episode ?: 0).toString(),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }

                if (isResolving) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color.Black.copy(alpha = 0.4f)),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator(
                                modifier = Modifier.size(32.dp),
                                color = Color.White,
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.width(16.dp))

            // Title, Date
            Column(
                modifier = Modifier.weight(1f)
            ) {
                Text(
                    text = episode.title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(6.dp))
                val formattedDate = remember(episode.released) { formatEpisodeDate(episode.released) }
                if (formattedDate != null) {
                    val isFuture = episode.released?.let { isEpisodeFuture(it) } == true
                    Text(
                        text = formattedDate,
                        style = MaterialTheme.typography.labelMedium,
                        color = if (isFuture)
                            themeColor.copy(alpha = 0.9f)
                        else
                            Color.White.copy(alpha = 0.7f)
                    )
                }
            }

            // Watched indicator — animated pop-in
            AnimatedVisibility(
                visible = isWatched,
                enter = fadeIn() + scaleIn(initialScale = 0.6f, animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy)),
                exit = fadeOut() + scaleOut(targetScale = 0.6f)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Spacer(modifier = Modifier.width(8.dp))
                    Icon(
                        Icons.Default.CheckCircle,
                        contentDescription = "Watched",
                        tint = themeColor,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Description
        Text(
            text = episode.overview ?: "",
            style = MaterialTheme.typography.bodyMedium,
            color = Color.White.copy(alpha = 0.7f),
            maxLines = 4,
            overflow = TextOverflow.Ellipsis
        )
    }
    }
}




// ==================== Episode Date Helpers ====================

internal fun formatEpisodeDate(released: String?): String? {
    if (released.isNullOrBlank()) return null
    return try {
        val formats = listOf(
            "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'",
            "yyyy-MM-dd'T'HH:mm:ss'Z'",
            "yyyy-MM-dd'T'HH:mm:ssXXX",
            "yyyy-MM-dd"
        )
        var date: java.util.Date? = null
        for (fmt in formats) {
            date = runCatching {
                java.text.SimpleDateFormat(fmt, java.util.Locale.US).also {
                    it.isLenient = false
                }.parse(released)
            }.getOrNull()
            if (date != null) break
        }
        if (date == null) return null

        val now = java.util.Date()
        val diffMs = date.time - now.time
        val diffDays = (diffMs / (1000L * 60 * 60 * 24)).toInt()

        when {
            diffDays > 1  -> "in $diffDays days"
            diffDays == 1 -> "tomorrow"
            diffDays == 0 -> "today"
            else -> java.text.SimpleDateFormat("MMM d, yyyy", java.util.Locale.US).format(date)
        }
    } catch (_: Exception) { null }
}

internal fun isEpisodeFuture(released: String): Boolean {
    if (released.isBlank()) return false
    return try {
        val formats = listOf(
            "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'",
            "yyyy-MM-dd'T'HH:mm:ss'Z'",
            "yyyy-MM-dd'T'HH:mm:ssXXX",
            "yyyy-MM-dd"
        )
        for (fmt in formats) {
            val d = runCatching {
                java.text.SimpleDateFormat(fmt, java.util.Locale.US).also {
                    it.isLenient = false
                }.parse(released)
            }.getOrNull()
            if (d != null) return d.after(java.util.Date())
        }
        false
    } catch (_: Exception) { false }
}

// ==================== Resolution States ====================

enum class ResolutionTarget { NONE, TV, PHONE }

data class ResolutionState(
    val isResolving: Boolean = false,
    val target: ResolutionTarget = ResolutionTarget.NONE,
    val episodeId: Int? = null
)

// ==================== Playlist Data Classes ====================

@Composable
fun TranslucentBackground(backdropUrl: String?, dominantColor: Color? = null) {
    Box(modifier = Modifier.fillMaxSize()) {
        if (backdropUrl != null) {
            AsyncImage(
                model = ImageRequest.Builder(LocalContext.current)
                    .data(backdropUrl)
                    .size(400, 225) // blurred — no need for full resolution
                    .crossfade(true)
                    .build(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxSize()
                    .blur(100.dp, edgeTreatment = BlurredEdgeTreatment.Unbounded)
            )
            // Apply a uniform dark tint that retains the vibrant color of the blurred backdrop
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.65f))
            )
        } else {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.background)
            )
        }

        // Apply dominant color overlay if available
        dominantColor?.let { color ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(color.copy(alpha = 0.15f))
            )
        }
    }
}
