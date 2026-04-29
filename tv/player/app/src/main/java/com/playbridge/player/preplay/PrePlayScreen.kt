package com.playbridge.player.preplay

import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import com.playbridge.player.ui.theme.PlayBridgeTVTheme
import com.playbridge.shared.protocol.VisualMetadata

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun PrePlayScreen(
    metadata: VisualMetadata,
    isLaunching: Boolean = false,
    launchCountdown: Int = 0,
    onStartNow: () -> Unit,
    onBack: () -> Unit
) {
    val context = LocalContext.current

    BackHandler(onBack = onBack)

    PlayBridgeTVTheme {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
        ) {
            // 1. Background Backdrop
            Box(modifier = Modifier.fillMaxSize()) {
                AsyncImage(
                    model = ImageRequest.Builder(context)
                        .data(metadata.backdropUrl ?: metadata.posterUrl)
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

            // 2. Content Info
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(48.dp),
                horizontalArrangement = Arrangement.spacedBy(48.dp)
            ) {
                Column(modifier = Modifier.weight(0.6f)) {
                    if (metadata.logoUrl != null) {
                        AsyncImage(
                            model = metadata.logoUrl,
                            contentDescription = metadata.title,
                            modifier = Modifier.height(120.dp).fillMaxWidth(),
                            contentScale = ContentScale.Fit,
                            alignment = Alignment.CenterStart
                        )
                    } else {
                        Text(
                            text = metadata.title,
                            style = MaterialTheme.typography.displayMedium,
                            fontWeight = FontWeight.Black,
                            color = Color.White
                        )
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    val metaItems = mutableListOf<String>()
                    metadata.year?.let { metaItems.add(it) }
                    metadata.rating?.let { metaItems.add("IMDb $it") }
                    metadata.runtime?.let { metaItems.add(it) }

                    Text(
                        text = metaItems.joinToString("  •  "),
                        style = MaterialTheme.typography.labelLarge,
                        color = Color(0xFFCCCCCC)
                    )

                    if (metadata.season != null && metadata.episode != null) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "S${metadata.season} E${metadata.episode}${if (metadata.episodeTitle != null) " - ${metadata.episodeTitle}" else ""}",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }

                    Spacer(modifier = Modifier.height(24.dp))

                    Text(
                        text = metadata.overview ?: "",
                        style = MaterialTheme.typography.bodyLarge,
                        maxLines = 10,
                        overflow = TextOverflow.Ellipsis,
                        color = Color(0xFFBBBBBB)
                    )

                    Spacer(modifier = Modifier.weight(1f))

                    // Status & Actions
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(24.dp)
                    ) {
                        StatusSection(
                            isLaunching = isLaunching,
                            countdown = launchCountdown
                        )
                        
                    }
                }
                
                // Right side: Poster
                Column(
                    modifier = Modifier.weight(0.4f),
                    horizontalAlignment = Alignment.End,
                    verticalArrangement = Arrangement.Center
                ) {
                    if (metadata.posterUrl != null) {
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.height(450.dp).aspectRatio(2/3f)
                        ) {
                            AsyncImage(
                                model = metadata.posterUrl,
                                contentDescription = null,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize()
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun StatusSection(
    isLaunching: Boolean,
    countdown: Int
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (countdown > 0) {
            Icon(
                Icons.Default.CheckCircle,
                contentDescription = null,
                tint = Color(0xFF4CAF50),
                modifier = Modifier.size(28.dp)
            )
            Spacer(modifier = Modifier.width(16.dp))
            Column {
                Text(
                    text = "Buffering in background...",
                    style = MaterialTheme.typography.labelSmall,
                    color = Color(0xFF4CAF50),
                    fontWeight = FontWeight.Bold
                )
                StatusText(countdown)
            }
        } else if (countdown == -1 && isLaunching) {
             CircularProgressIndicator(
                color = MaterialTheme.colorScheme.secondary,
                modifier = Modifier.size(28.dp),
                strokeWidth = 3.dp
            )
            Spacer(modifier = Modifier.width(16.dp))
            Text(
                text = "Connecting to stream...",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = Color.White
            )
        } else if (isLaunching || countdown == 0) {
             CircularProgressIndicator(
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(28.dp),
                strokeWidth = 3.dp
            )
            Spacer(modifier = Modifier.width(16.dp))
            Text(
                text = "Starting playback...",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = Color.White
            )
        }
    }
}

@Composable
private fun StatusText(countdown: Int) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = "Starts in ",
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
            text = " seconds",
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            color = Color.White
        )
    }
}
