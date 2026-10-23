package com.playbridge.player.ui.player

import android.view.KeyEvent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.playbridge.player.player.SkipSegment
import com.playbridge.player.player.chapterFractions
import com.playbridge.player.player.segmentFractions
import com.playbridge.shared.player.PlayerChapter
import java.util.Locale

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun PlayerSeekbar(
    position: Long,
    duration: Long,
    bufferedPosition: Long,
    onSeek: (Long) -> Unit,
    chapters: List<PlayerChapter> = emptyList(),
    segments: List<SkipSegment> = emptyList(),
    caption: String? = null,
    modifier: Modifier = Modifier
) {
    var isFocused by remember { mutableStateOf(false) }
    val progress = if (duration > 0) (position.toFloat() / duration.toFloat()).coerceIn(0f, 1f) else 0f
    val bufferProgress = if (duration > 0) (bufferedPosition.toFloat() / duration.toFloat()).coerceIn(0f, 1f) else 0f

    val elapsedText = remember(position) { formatDuration(position) }
    val remainingText = remember(position, duration) {
        val remaining = if (duration > 0) duration - position else 0
        "-${formatDuration(remaining)}"
    }
    val chapterGapFractions = remember(chapters, duration) {
        chapterFractions(chapters, duration)
    }
    val segmentRanges = remember(segments, duration) {
        segmentFractions(segments, duration)
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 48.dp)
            .onFocusChanged { isFocused = it.isFocused }
            .focusable()
            .onKeyEvent { event ->
                if (event.type == KeyEventType.KeyDown) {
                    when (event.nativeKeyEvent.keyCode) {
                        KeyEvent.KEYCODE_DPAD_LEFT -> {
                            onSeek(-10_000L)
                            true
                        }
                        KeyEvent.KEYCODE_DPAD_RIGHT -> {
                            onSeek(10_000L)
                            true
                        }
                        else -> false
                    }
                } else false
            },
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // Time labels and centered chapter / skip-segment caption
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = elapsedText,
                style = MaterialTheme.typography.labelSmall,
                color = Color.White
            )
            Box(
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 16.dp),
                contentAlignment = Alignment.Center,
            ) {
                if (!caption.isNullOrBlank()) {
                    Text(
                        text = caption,
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White.copy(alpha = 0.85f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.Center,
                    )
                }
            }
            Text(
                text = remainingText,
                style = MaterialTheme.typography.labelSmall,
                color = Color.White
            )
        }

        // The actual bar
        val activeColor = MaterialTheme.colorScheme.primary
        val segmentColor = activeColor.copy(alpha = 0.55f)
        val bufferColor = Color.White.copy(alpha = 0.6f)
        val trackColor = Color.White.copy(alpha = 0.1f)
        val barHeight = if (isFocused) 8.dp else 4.dp
        val segmentBarHeight = if (isFocused) 10.dp else 7.dp
        val canvasHeight = if (isFocused) 16.dp else 10.dp

        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(canvasHeight)
                .graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen)
        ) {
            val width = size.width
            val height = size.height
            val centerY = height / 2f
            val trackStroke = barHeight.toPx()
            val segmentStroke = segmentBarHeight.toPx()
            val gapWidth = 4.dp.toPx()

            // 1. Track (Full duration background)
            drawLine(
                color = trackColor,
                start = Offset(0f, centerY),
                end = Offset(width, centerY),
                strokeWidth = trackStroke,
                cap = StrokeCap.Round
            )

            // 2. Buffer (Line from current progress to buffered position)
            if (bufferProgress > progress) {
                drawLine(
                    color = bufferColor,
                    start = Offset(width * progress, centerY),
                    end = Offset(width * bufferProgress, centerY),
                    strokeWidth = trackStroke / 2f,
                    cap = StrokeCap.Round
                )
            }

            // 3. Skip-segment highlight bands (Intro / Recap / Credits / Preview)
            segmentRanges.forEach { range ->
                val startX = width * range.startFraction
                val endX = width * range.endFraction
                if (endX > startX) {
                    drawLine(
                        color = segmentColor,
                        start = Offset(startX, centerY),
                        end = Offset(endX, centerY),
                        strokeWidth = segmentStroke,
                        cap = StrokeCap.Butt,
                    )
                }
            }

            // 4. Progress (Active playback line)
            drawLine(
                color = activeColor,
                start = Offset(0f, centerY),
                end = Offset(width * progress, centerY),
                strokeWidth = trackStroke,
                cap = StrokeCap.Round
            )

            // 5. Chapter gaps (punched through track, buffer, segment bands, and played fill)
            chapterGapFractions.forEach { fraction ->
                val x = width * fraction
                drawLine(
                    color = Color.Transparent,
                    start = Offset(x, 0f),
                    end = Offset(x, height),
                    strokeWidth = gapWidth,
                    cap = StrokeCap.Butt,
                    blendMode = BlendMode.Clear,
                )
            }

            // 6. Focused thumb (drawn after chapter gaps so it is never notched)
            if (isFocused) {
                drawCircle(
                    color = Color.White,
                    radius = 6.dp.toPx(),
                    center = Offset(width * progress, centerY),
                )
            }
        }
    }
}

internal fun formatDuration(ms: Long): String {
    val totalSeconds = (ms / 1000).coerceAtLeast(0)
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) {
        String.format(Locale.ROOT, "%d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format(Locale.ROOT, "%02d:%02d", minutes, seconds)
    }
}
