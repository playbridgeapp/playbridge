package com.playbridge.sender.browser

import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.playbridge.sender.data.library.*
import kotlinx.coroutines.launch

/**
 * Routing + detail screen for items tapped in an addon catalog.
 *
 * - Standard IMDb IDs ("tt..."): performs a TMDB /find lookup and redirects
 *   to the existing [MovieDetailScreen] or [TvShowDetailScreen].
 * - Non-standard IDs: fetches full metadata via [AddonRepository.fetchMeta] and
 *   displays a native detail view with stream resolution (Phase 4).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddonDetailScreen(
    id: String,
    type: String,
    addonRepository: AddonRepository,
    onMovieResolved: (tmdbId: Int) -> Unit,
    onTvShowResolved: (tmdbId: Int) -> Unit,
    onPlayStream: (url: String, title: String, subtitles: List<String>?) -> Unit = { _, _, _ -> },
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val tmdb = remember { TmdbRepository(context) }
    val scope = rememberCoroutineScope()

    var isLoading by remember { mutableStateOf(true) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var metaDetail by remember { mutableStateOf<StremioMetaDetail?>(null) }

    // Stream resolution state
    var resolvedStreams by remember { mutableStateOf<List<ResolvedStream>>(emptyList()) }
    var resolutionState by remember { mutableStateOf(ResolutionState()) }
    var showStreamPicker by remember { mutableStateOf(false) }
    var resolvingEpisodeId by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(id, type) {
        isLoading = true
        errorMessage = null

        when {
            id.startsWith("tt") -> {
                // Standard IMDb ID — look up TMDB ID via /find
                val result = tmdb.findByImdbId(id)
                when {
                    result == null -> {
                        errorMessage = "Could not reach TMDB. Check your API key and connection."
                    }
                    type == "movie" && result.movieResults.isNotEmpty() -> {
                        onMovieResolved(result.movieResults.first().id)
                        return@LaunchedEffect
                    }
                    type == "series" && result.tvResults.isNotEmpty() -> {
                        onTvShowResolved(result.tvResults.first().id)
                        return@LaunchedEffect
                    }
                    result.movieResults.isNotEmpty() -> {
                        // type mismatch — trust the data over the declared type
                        onMovieResolved(result.movieResults.first().id)
                        return@LaunchedEffect
                    }
                    result.tvResults.isNotEmpty() -> {
                        onTvShowResolved(result.tvResults.first().id)
                        return@LaunchedEffect
                    }
                    else -> {
                        errorMessage = "\"$id\" was not found on TMDB."
                    }
                }
            }
            else -> {
                // Non-IMDb ID — fetch meta from addon
                val meta = addonRepository.fetchMeta(type, id)
                if (meta != null) {
                    metaDetail = meta
                } else {
                    errorMessage = "Metadata for \"$id\" is not available from any installed addon."
                }
            }
        }
        isLoading = false
    }

    // Helper: start stream resolution for a given stream ID
    val startResolution = { streamId: String, forPhone: Boolean ->
        resolvingEpisodeId = streamId
        resolutionState = ResolutionState(
            isResolving = true,
            target = if (forPhone) ResolutionTarget.PHONE else ResolutionTarget.TV
        )
        resolvedStreams = emptyList()
        showStreamPicker = true
        scope.launch {
            addonRepository.resolveStreamsFlow(type, streamId).collect { latest ->
                resolvedStreams = latest
            }
            resolutionState = resolutionState.copy(isResolving = false)
            resolvingEpisodeId = null
        }
        Unit
    }

    // Stream picker sheet
    if (showStreamPicker) {
        val title = metaDetail?.name ?: id
        StreamPickerSheet(
            streams = resolvedStreams,
            isLoading = resolutionState.isResolving,
            title = title,
            forceManual = false,
            onStreamSelected = { resolved ->
                val forPhone = resolutionState.target == ResolutionTarget.PHONE
                showStreamPicker = false
                resolutionState = ResolutionState()
                val streamUrl = resolved.stream.url ?: return@StreamPickerSheet
                if (forPhone) {
                    openInExternalPlayer(context, streamUrl, null, null)
                } else {
                    onPlayStream(streamUrl, title, null)
                    Toast.makeText(context, "Sent to TV", Toast.LENGTH_SHORT).show()
                }
            },
            onDismiss = {
                showStreamPicker = false
                resolutionState = ResolutionState()
                resolvingEpisodeId = null
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(metaDetail?.name ?: id) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentAlignment = Alignment.Center
        ) {
            when {
                isLoading -> CircularProgressIndicator()

                errorMessage != null -> Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.padding(32.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Extension,
                        contentDescription = null,
                        modifier = Modifier.size(48.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                    )
                    Text(
                        text = errorMessage!!,
                        style = MaterialTheme.typography.bodyLarge,
                        textAlign = TextAlign.Center,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    OutlinedButton(onClick = onBack) { Text("Go back") }
                }

                metaDetail != null -> AddonMetaDetailContent(
                    meta = metaDetail!!,
                    type = type,
                    resolutionState = resolutionState,
                    resolvingEpisodeId = resolvingEpisodeId,
                    onWatchOnTv = { streamId -> startResolution(streamId, false) },
                    onWatchOnPhone = { streamId -> startResolution(streamId, true) }
                )
            }
        }
    }
}

// ==================== Meta Detail UI ====================

@Composable
private fun AddonMetaDetailContent(
    meta: StremioMetaDetail,
    type: String,
    resolutionState: ResolutionState,
    resolvingEpisodeId: String?,
    onWatchOnTv: (streamId: String) -> Unit,
    onWatchOnPhone: (streamId: String) -> Unit
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 32.dp)
    ) {
        // Backdrop / poster header
        item {
            val imageUrl = meta.background ?: meta.poster
            if (imageUrl != null) {
                AsyncImage(
                    model = imageUrl,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(220.dp)
                )
            }
        }

        // Title, meta chips (year · rating · runtime · genres)
        item {
            Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                Text(
                    text = meta.name,
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold
                )
                val chips = listOfNotNull(
                    meta.year,
                    meta.imdbRating?.let { "★ $it" },
                    meta.runtime
                ) + meta.genres
                if (chips.isNotEmpty()) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = chips.joinToString(" · "),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        // Description
        if (!meta.description.isNullOrBlank()) {
            item {
                Text(
                    text = meta.description,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                )
            }
        }

        // Cast
        if (meta.cast.isNotEmpty()) {
            item {
                Text(
                    text = "Cast: ${meta.cast.take(5).joinToString(", ")}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                )
            }
        }

        // Play buttons — shown for movies, or for series that have no episode list
        if (type == "movie" || meta.videos.isEmpty()) {
            item {
                Spacer(Modifier.height(8.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = { onWatchOnTv(meta.id) },
                        enabled = !resolutionState.isResolving,
                        modifier = Modifier.weight(1f)
                    ) {
                        if (resolutionState.isResolving && resolutionState.target == ResolutionTarget.TV) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.onPrimary
                            )
                        } else {
                            Icon(Icons.Default.Tv, contentDescription = null, modifier = Modifier.size(18.dp))
                        }
                        Spacer(Modifier.width(6.dp))
                        Text("Watch on TV")
                    }
                    OutlinedButton(
                        onClick = { onWatchOnPhone(meta.id) },
                        enabled = !resolutionState.isResolving,
                        modifier = Modifier.weight(1f)
                    ) {
                        if (resolutionState.isResolving && resolutionState.target == ResolutionTarget.PHONE) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.dp
                            )
                        } else {
                            Icon(Icons.Default.PhoneAndroid, contentDescription = null, modifier = Modifier.size(18.dp))
                        }
                        Spacer(Modifier.width(6.dp))
                        Text("On Phone")
                    }
                }
            }
        }

        // Episodes list (series with video entries from the addon)
        if (meta.videos.isNotEmpty()) {
            item {
                Spacer(Modifier.height(8.dp))
                HorizontalDivider()
                Text(
                    text = "Episodes",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                )
            }
            items(meta.videos) { video ->
                EpisodeItemRow(
                    video = video,
                    isResolving = resolvingEpisodeId == video.id && resolutionState.isResolving,
                    resolvingTarget = resolutionState.target,
                    onWatchOnTv = { onWatchOnTv(video.id) },
                    onWatchOnPhone = { onWatchOnPhone(video.id) }
                )
                HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
            }
        }
    }
}

@Composable
private fun EpisodeItemRow(
    video: StremioVideo,
    isResolving: Boolean,
    resolvingTarget: ResolutionTarget,
    onWatchOnTv: () -> Unit,
    onWatchOnPhone: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            val label = when {
                video.season != null && video.episode != null ->
                    "S${video.season}E${video.episode}" + if (video.title.isNotBlank()) " · ${video.title}" else ""
                video.title.isNotBlank() -> video.title
                else -> video.id
            }
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium
            )
            video.released?.take(10)?.let { date ->
                Text(
                    text = date,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        // TV button
        IconButton(
            onClick = onWatchOnTv,
            enabled = !isResolving
        ) {
            if (isResolving && resolvingTarget == ResolutionTarget.TV) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
            } else {
                Icon(Icons.Default.Tv, contentDescription = "Watch on TV")
            }
        }

        // Phone button
        IconButton(
            onClick = onWatchOnPhone,
            enabled = !isResolving
        ) {
            if (isResolving && resolvingTarget == ResolutionTarget.PHONE) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
            } else {
                Icon(Icons.Default.PhoneAndroid, contentDescription = "Watch on Phone")
            }
        }
    }
}
