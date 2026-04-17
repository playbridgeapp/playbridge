package com.playbridge.player.preplay

import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
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
import com.playbridge.player.stremio.ScoredStremioStream
import com.playbridge.player.stremio.StremioClient
import com.playbridge.player.ui.theme.PlayBridgeTVTheme
import com.playbridge.protocol.ContentPlayPayload
import kotlinx.coroutines.delay

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun PrePlayScreen(
    payload: ContentPlayPayload,
    isLaunching: Boolean = false,
    onStreamSelected: (ScoredStremioStream) -> Unit,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    var streams by remember { mutableStateOf<List<ScoredStremioStream>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }

    BackHandler(onBack = onBack)

    LaunchedEffect(payload) {
        isLoading = true
        try {
            val prefs = context.getSharedPreferences("browser_prefs", android.content.Context.MODE_PRIVATE)
            val autoQuality = payload.defaultVideoQuality ?: prefs.getString("auto_stream_quality", "") ?: ""
            val preferredAddon = payload.preferredAddonBaseUrl ?: prefs.getString("auto_stream_addon", "") ?: ""

            streams = StremioClient.resolveStreamsByContentId(
                addonBaseUrls = payload.addonBaseUrls,
                addonNames = payload.addonNames,
                contentId = payload.contentId,
                contentType = payload.contentType,
                season = payload.season,
                episode = payload.episode,
                qualityPreference = autoQuality.takeIf { it.isNotEmpty() },
                preferredAddonBaseUrl = preferredAddon.takeIf { it.isNotEmpty() }
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

                    if (isLoading || isLaunching) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(28.dp),
                                strokeWidth = 3.dp
                            )
                            Spacer(modifier = Modifier.width(16.dp))
                            Text(
                                if (isLaunching) "Launching player..." else "Resolving streams...",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = Color.White
                            )
                        }
                    } else if (error != null) {
                        Text(
                            text = error!!,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Button(onClick = onBack) {
                            Text("Go Back", color = Color.White)
                        }
                    }
                }

                // Right: Stream List (Transparent area)
                Column(modifier = Modifier.weight(0.55f)) {
                    if (streams.isNotEmpty()) {
                        Text(
                            if (isLaunching) "Launching..." else "Select a Stream",
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.padding(bottom = 16.dp),
                            color = Color.White
                        )

                        LazyColumn(
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                            contentPadding = PaddingValues(bottom = 24.dp)
                        ) {
                            items(streams) { stream ->
                                StreamItem(
                                    stream = stream,
                                    onClick = { if (!isLaunching) onStreamSelected(stream) }
                                )
                            }
                        }
                    }
                }
            }
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
