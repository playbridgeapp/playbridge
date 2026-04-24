package com.playbridge.player.ui.player

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.focusable
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import android.view.KeyEvent
import androidx.compose.ui.unit.dp
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun PlayerSeekbar(
    position: Long,
    duration: Long,
    bufferedPosition: Long,
    onSeek: (Long) -> Unit,
    modifier: Modifier = Modifier
) {
    var isFocused by remember { mutableStateOf(false) }
    val progress = if (duration > 0) position.toFloat() / duration.toFloat() else 0f
    val bufferProgress = if (duration > 0) bufferedPosition.toFloat() / duration.toFloat() else 0f

    val elapsedText = remember(position) { formatDuration(position) }
    val remainingText = remember(position, duration) { 
        val remaining = if (duration > 0) duration - position else 0
        "-${formatDuration(remaining)}"
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
        // Time labels
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = elapsedText,
                style = MaterialTheme.typography.labelSmall,
                color = Color.White
            )
            Text(
                text = remainingText,
                style = MaterialTheme.typography.labelSmall,
                color = Color.White
            )
        }

        // The actual bar
        val activeColor = MaterialTheme.colorScheme.primary
        val bufferColor = Color.White.copy(alpha = 0.6f)
        val trackColor = Color.White.copy(alpha = 0.1f)
        val barHeight = if (isFocused) 8.dp else 4.dp

        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(barHeight)
        ) {
            val width = size.width
            val height = size.height
            val centerY = height / 2

            // Track (Full duration background)
            drawLine(
                color = trackColor,
                start = Offset(0f, centerY),
                end = Offset(width, centerY),
                strokeWidth = height,
                cap = StrokeCap.Round
            )

            // Buffer (Line from current progress to buffered position)
            if (bufferProgress > progress) {
                drawLine(
                    color = bufferColor,
                    start = Offset(width * progress, centerY),
                    end = Offset(width * bufferProgress.coerceAtMost(1f), centerY),
                    strokeWidth = height / 2,
                    cap = StrokeCap.Round
                )
            }

            // Progress (Active playback line)
            drawLine(
                color = activeColor,
                start = Offset(0f, centerY),
                end = Offset(width * progress.coerceAtMost(1f), centerY),
                strokeWidth = height,
                cap = StrokeCap.Round
            )
        }
    }
}

private fun formatDuration(ms: Long): String {
    val totalSeconds = (ms / 1000).coerceAtLeast(0)
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) {
        String.format("%d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format("%02d:%02d", minutes, seconds)
    }
}
