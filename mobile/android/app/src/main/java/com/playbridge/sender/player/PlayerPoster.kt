package com.playbridge.sender.player

import android.graphics.Bitmap
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage

/** What the player shows while it connects: the cast sheet's thumbnail or the website's poster. */
internal data class PlayerPoster(val bitmap: Bitmap? = null, val url: String? = null) {
    val isEmpty: Boolean get() = bitmap == null && url.isNullOrBlank()
}

/**
 * The blurred poster over the video area until the first frame, so opening never shows a black
 * screen; it fades out as the picture arrives.
 */
@Composable
internal fun PosterBackdrop(poster: PlayerPoster, state: PlayerScreenState) {
    if (poster.isEmpty) return
    AnimatedVisibility(
        visible = !state.hasRenderedFrame,
        enter = androidx.compose.animation.EnterTransition.None,
        exit = fadeOut(tween(400)),
    ) {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            val imageModifier = Modifier.fillMaxSize().blur(24.dp)
            if (poster.bitmap != null) {
                Image(poster.bitmap.asImageBitmap(), contentDescription = null, contentScale = ContentScale.Crop, modifier = imageModifier)
            } else {
                AsyncImage(model = poster.url, contentDescription = null, contentScale = ContentScale.Crop, modifier = imageModifier)
            }
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.35f)))
        }
    }
}
