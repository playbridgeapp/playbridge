package com.playbridge.player.ui.player

import com.playbridge.player.ui.theme.TvTheme

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
import androidx.tv.material3.Text
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.height
import androidx.compose.ui.res.painterResource
import com.playbridge.player.R
import com.playbridge.player.ui.components.TvChip
import com.playbridge.player.ui.theme.TvRadius
import com.playbridge.player.ui.theme.TvSpacing
import com.playbridge.player.ui.theme.TvText
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

/**
 * The picture behind the controls for music and photos (video is drawn by the renderer).
 *
 * Music (mockup PlayerMusic): artwork, optional "From <phone>" chip, title, artist, album, then
 * [musicControls] (progress and transport buttons, owned by the overlay). Photos (mockup
 * PlayerPhoto): the photo shown whole, plus a counter pill and a hint pill while [showPhotoHints].
 * [senderName] is null until a caller supplies the phone's name; nothing invented is shown.
 */
@Composable
fun MediaPresentation(
    payload: PlayPayload?,
    mediaKind: String,
    imageScale: Float = 1f,
    imageOffsetX: Float = 0f,
    imageOffsetY: Float = 0f,
    imageRotation: Float = 0f,
    onImageShown: (String) -> Unit = {},
    senderName: String? = null,
    photoCounter: String? = null,
    showPhotoHints: Boolean = true,
    musicControls: @Composable () -> Unit = {},
) {
    when (mediaKind) {
        "audio" -> PlayerDarkSurface { MusicPresentation(payload, senderName, musicControls) }
        "image" -> Box(Modifier.fillMaxSize()) {
            ImagePresentation(
                payload,
                imageScale,
                imageOffsetX,
                imageOffsetY,
                imageRotation,
                onImageShown,
            )
            if (showPhotoHints) PhotoHints(photoCounter, senderName)
        }
    }
}

@Composable
private fun MusicPresentation(
    payload: PlayPayload?,
    senderName: String?,
    controls: @Composable () -> Unit,
) {
    val metadata = payload?.visual_metadata
    val artwork = metadata?.artwork_url ?: metadata?.poster_url ?: metadata?.backdrop_url
    val shape = TvRadius.cardShape
    Row(
        modifier = Modifier
            .fillMaxSize()
            .background(TvTheme.colors.surface)
            .padding(horizontal = 96.dp),
        horizontalArrangement = Arrangement.spacedBy(64.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(400.dp)
                .clip(shape)
                .background(TvTheme.colors.accentContainer),
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
                Text("♫", fontSize = 96.sp, color = TvTheme.colors.onAccentContainer)
            }
        }
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(28.dp),
        ) {
            senderName?.takeIf { it.isNotBlank() }?.let {
                TvChip("From $it", icon = painterResource(R.drawable.ic_player_phone))
            }
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text = payload?.title ?: metadata?.title ?: "Music",
                    style = TvText.pageTitle.copy(fontSize = 50.sp, lineHeight = 55.sp),
                    color = TvTheme.colors.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                metadata?.artist?.takeIf { it.isNotBlank() }?.let { artist ->
                    Text(
                        artist,
                        style = TvText.cardTitle,
                        color = TvTheme.colors.onSurface,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                metadata?.album?.takeIf { it.isNotBlank() }?.let { album ->
                    Text(
                        album,
                        style = TvText.row.copy(fontWeight = FontWeight.Normal),
                        color = TvTheme.colors.onSurfaceMuted,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            controls()
        }
    }
}

/** Photo counter ("3 of 24"), the phone it came from, and the Left/Right hint (mockup PlayerPhoto). */
@Composable
private fun PhotoHints(counter: String?, senderName: String?) {
    if (counter == null && senderName.isNullOrBlank()) return
    Box(Modifier.fillMaxSize().padding(horizontal = TvSpacing.screenGutter, vertical = 44.dp)) {
        Row(
            modifier = Modifier.align(Alignment.BottomStart),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            counter?.let { PhotoPill { Text(it, style = TvText.button, color = TvTheme.player.onSurface) } }
            senderName?.takeIf { it.isNotBlank() }?.let { name ->
                PhotoPill {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Image(
                            painterResource(R.drawable.ic_player_phone), null, Modifier.size(16.dp),
                            colorFilter = androidx.compose.ui.graphics.ColorFilter.tint(TvTheme.player.onSurfaceMuted),
                        )
                        Text("From $name", style = TvText.value, color = TvTheme.player.onSurfaceMuted)
                    }
                }
            }
        }
        if (counter != null) {
            PhotoPill(Modifier.align(Alignment.BottomEnd)) {
                Text("Left and right for the next photo", style = TvText.value, color = TvTheme.player.onSurfaceMuted)
            }
        }
    }
}

@Composable
private fun PhotoPill(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Box(
        modifier = modifier
            .height(44.dp)
            .background(TvTheme.player.panel, TvRadius.pill)
            .padding(horizontal = 18.dp),
        contentAlignment = Alignment.Center,
    ) { content() }
}

@Composable
private fun ImagePresentation(
    payload: PlayPayload?,
    scale: Float,
    offsetX: Float,
    offsetY: Float,
    rotation: Float,
    onImageShown: (String) -> Unit,
) {
    val context = LocalContext.current
    // Keep scale, rotation, and anchor-compensating translation on one timeline so
    // the gesture midpoint remains stationary throughout interpolation.
    val panAnimation = tween<Float>(durationMillis = 70, easing = LinearEasing)
    val animatedScale = animateFloatAsState(scale, panAnimation, label = "imageScale")
    val animatedOffsetX = animateFloatAsState(offsetX, panAnimation, label = "imageOffsetX")
    val animatedOffsetY = animateFloatAsState(offsetY, panAnimation, label = "imageOffsetY")
    val animatedRotation = animateFloatAsState(rotation, panAnimation, label = "imageRotation")
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
        modifier = Modifier.fillMaxSize().background(TvTheme.player.background),
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
            onSuccess = { payload?.url?.let(onImageShown) },
            onError = { payload?.url?.let(onImageShown) },
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
