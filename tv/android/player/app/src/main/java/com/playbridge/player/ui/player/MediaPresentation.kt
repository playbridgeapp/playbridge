package com.playbridge.player.ui.player

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import coil3.network.NetworkHeaders
import coil3.network.httpHeaders
import coil3.request.ImageRequest
import androidx.compose.ui.platform.LocalContext
import kotlin.math.abs
import kotlin.math.roundToInt
import playbridge.PlayPayload

@Composable
fun MediaPresentation(
    payload: PlayPayload?,
    mediaKind: String,
    imageScale: Float = 1f,
    imageOffsetX: Float = 0f,
    imageOffsetY: Float = 0f,
    imageRotation: Float = 0f,
) {
    when (mediaKind) {
        "audio" -> MusicPresentation(payload)
        "image" -> ImagePresentation(
            payload,
            imageScale,
            imageOffsetX,
            imageOffsetY,
            imageRotation,
        )
    }
}

@Composable
private fun MusicPresentation(payload: PlayPayload?) {
    val metadata = payload?.visual_metadata
    val artwork = metadata?.artwork_url ?: metadata?.poster_url ?: metadata?.backdrop_url
    val shape = RoundedCornerShape(28.dp)
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.linearGradient(
                    listOf(Color(0xFF18152A), Color(0xFF090B12), Color(0xFF050609)),
                ),
            ),
        contentAlignment = Alignment.Center,
    ) {
        if (artwork != null) {
            AsyncImage(
                model = artwork,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                alpha = 0.24f,
                modifier = Modifier.fillMaxSize().blur(42.dp),
            )
        }
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        listOf(Color.Transparent, Color(0xD9050609)),
                    ),
                ),
        )
        val artworkSize = minOf(maxWidth * 0.34f, maxHeight * 0.62f)
            .coerceIn(260.dp, 440.dp)
        Row(
            modifier = Modifier.padding(start = 72.dp, end = 72.dp, bottom = 88.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(artworkSize)
                    .shadow(36.dp, shape)
                    .clip(shape)
                    .border(1.dp, Color.White.copy(alpha = 0.2f), shape),
                contentAlignment = Alignment.Center,
            ) {
                if (artwork != null) {
                    AsyncImage(
                        model = artwork,
                        contentDescription = "Album artwork",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(
                                Brush.linearGradient(
                                    listOf(Color(0xFF6D5DFB), Color(0xFF332A73), Color(0xFF151225)),
                                ),
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text("◉", fontSize = MaterialTheme.typography.displayLarge.fontSize * 2f, color = Color.White.copy(alpha = 0.3f))
                        Text("♫", style = MaterialTheme.typography.displayLarge, color = Color.White)
                    }
                }
            }
            Spacer(Modifier.size(64.dp))
            Column(
                modifier = Modifier.widthIn(max = 520.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.Start,
            ) {
                Text(
                    text = "NOW PLAYING",
                    style = MaterialTheme.typography.labelMedium,
                    color = Color(0xFFC9C5FF),
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 2.sp,
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .background(Color.White.copy(alpha = 0.1f))
                        .border(1.dp, Color.White.copy(alpha = 0.16f), RoundedCornerShape(50))
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                )
                Spacer(Modifier.size(24.dp))
                Text(
                    text = payload?.title ?: metadata?.title ?: "Music",
                    style = MaterialTheme.typography.displaySmall,
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
                metadata?.artist?.takeIf { it.isNotBlank() }?.let { artist ->
                    Spacer(Modifier.size(16.dp))
                    Text(
                        artist,
                        style = MaterialTheme.typography.headlineSmall,
                        color = Color(0xFFE0DFFF),
                        fontWeight = FontWeight.Medium,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                metadata?.album?.takeIf { it.isNotBlank() }?.let { album ->
                    Spacer(Modifier.size(8.dp))
                    Text(
                        album,
                        style = MaterialTheme.typography.titleMedium,
                        color = Color.White.copy(alpha = 0.62f),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

@Composable
private fun ImagePresentation(
    payload: PlayPayload?,
    scale: Float,
    offsetX: Float,
    offsetY: Float,
    rotation: Float,
) {
    val context = LocalContext.current
    // Keep a small amount of smoothing without continuously chasing 60 Hz input
    // several frames behind the sender.
    val animation = tween<Float>(durationMillis = 32, easing = LinearEasing)
    val animatedScale = animateFloatAsState(scale, animation, label = "imageScale")
    val animatedOffsetX = animateFloatAsState(offsetX, animation, label = "imageOffsetX")
    val animatedOffsetY = animateFloatAsState(offsetY, animation, label = "imageOffsetY")
    val animatedRotation = animateFloatAsState(rotation, animation, label = "imageRotation")
    val request = payload?.let { item ->
        val headers = NetworkHeaders.Builder().apply {
            item.headers.forEach { (name, value) -> set(name, value) }
        }.build()
        ImageRequest.Builder(context)
            .data(item.url)
            .httpHeaders(headers)
            .build()
    }
    BoxWithConstraints(
        modifier = Modifier.fillMaxSize().background(Color.Black),
        contentAlignment = Alignment.Center,
    ) {
        val quarterTurns = (animatedRotation.value / 90f).roundToInt()
        val residualRotation = animatedRotation.value - quarterTurns * 90f
        val isExactOddQuarterTurn = quarterTurns % 2 != 0 && abs(residualRotation) < 0.01f
        val imageModifier = if (isExactOddQuarterTurn) {
            // Swap the layout bounds before the 90° transform so the rotated image
            // fits the viewport instead of being clipped to its landscape bounds.
            Modifier.requiredSize(width = maxHeight, height = maxWidth)
        } else {
            Modifier.fillMaxSize()
        }
        AsyncImage(
            model = request,
            contentDescription = payload?.title ?: "Cast image",
            contentScale = ContentScale.Fit,
            modifier = imageModifier.graphicsLayer {
                scaleX = animatedScale.value
                scaleY = animatedScale.value
                translationX = animatedOffsetX.value
                translationY = animatedOffsetY.value
                rotationZ = animatedRotation.value
                clip = true
            },
        )
    }
}
