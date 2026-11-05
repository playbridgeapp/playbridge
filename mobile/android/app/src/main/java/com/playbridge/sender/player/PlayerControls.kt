package com.playbridge.sender.player

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.media3.ui.AspectRatioFrameLayout

/** A circular translucent "glass" button used for the transport controls. */
@Composable
internal fun GlassControl(
    onClick: () -> Unit,
    diameter: Dp,
    modifier: Modifier = Modifier,
    background: Color = Color.White.copy(alpha = 0.14f),
    enabled: Boolean = true,
    content: @Composable () -> Unit
) {
    Box(
        modifier = modifier
            .size(diameter)
            .alpha(if (enabled) 1f else 0.4f)
            .clip(CircleShape)
            .background(background),
        contentAlignment = Alignment.Center
    ) {
        IconButton(onClick = onClick, enabled = enabled, modifier = Modifier.matchParentSize()) { content() }
    }
}

/**
 * Position ticker isolation: the 400ms positionMs/bufferedMs/durationMs reads happen
 * only inside this row, so ticks recompose the time labels + bar — not the overlay.
 * Callers must pass the MutableLongState holders (not snapshot Longs) so the reads
 * stay scoped to this composable.
 */
@Composable
internal fun SeekProgressRow(
    position: androidx.compose.runtime.MutableLongState,
    buffered: androidx.compose.runtime.MutableLongState,
    duration: androidx.compose.runtime.MutableLongState,
    isScrubbing: Boolean,
    scrubFraction: Float,
    onScrubStart: () -> Unit,
    onScrub: (Float) -> Unit,
    onScrubEnd: (Float) -> Unit,
) {
    val positionMs = position.longValue
    val bufferedMs = buffered.longValue
    val durationMs = duration.longValue
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        val shownPos = if (isScrubbing) (scrubFraction * durationMs).toLong() else positionMs
        Text(formatTime(shownPos), color = Color.White, style = MaterialTheme.typography.labelMedium)
        SeekBar(
            fraction = if (durationMs > 0) (shownPos.toFloat() / durationMs) else 0f,
            bufferedFraction = if (durationMs > 0) (bufferedMs.toFloat() / durationMs) else 0f,
            onScrubStart = onScrubStart,
            onScrub = onScrub,
            onScrubEnd = onScrubEnd,
            modifier = Modifier.weight(1f)
        )
        Text(formatTime(durationMs), color = Color.White, style = MaterialTheme.typography.labelMedium)
    }
}

/**
 * Slim YouTube-style seek bar: thin rounded track with a buffered sub-track, a white
 * progress fill and a dot thumb that grows while dragging. Tap to seek, drag to scrub.
 */
@Composable
internal fun SeekBar(
    fraction: Float,
    bufferedFraction: Float,
    onScrubStart: () -> Unit,
    onScrub: (Float) -> Unit,
    onScrubEnd: (Float) -> Unit,
    modifier: Modifier = Modifier
) {
    var dragging by remember { mutableStateOf(false) }
    var dragFrac by remember { mutableFloatStateOf(0f) }
    val shown = (if (dragging) dragFrac else fraction).coerceIn(0f, 1f)
    val thumbSize = if (dragging) 16.dp else 12.dp

    BoxWithConstraints(
        modifier = modifier
            .height(28.dp)
            .pointerInput(Unit) {
                detectHorizontalDragGestures(
                    onDragStart = { o ->
                        dragging = true
                        dragFrac = (o.x / size.width).coerceIn(0f, 1f)
                        onScrubStart(); onScrub(dragFrac)
                    },
                    onHorizontalDrag = { change, _ ->
                        dragFrac = (change.position.x / size.width).coerceIn(0f, 1f)
                        onScrub(dragFrac)
                    },
                    onDragEnd = { dragging = false; onScrubEnd(dragFrac) },
                    onDragCancel = { dragging = false }
                )
            }
            .pointerInput(Unit) {
                detectTapGestures { o ->
                    val f = (o.x / size.width).coerceIn(0f, 1f)
                    onScrubStart(); onScrubEnd(f)
                }
            },
        contentAlignment = Alignment.CenterStart
    ) {
        val w = maxWidth
        Box(
            Modifier.fillMaxWidth().height(4.dp).clip(CircleShape)
                .background(Color.White.copy(alpha = 0.3f))
        )
        if (bufferedFraction > 0f) {
            Box(
                Modifier.fillMaxWidth(bufferedFraction.coerceIn(0f, 1f)).height(4.dp).clip(CircleShape)
                    .background(Color.White.copy(alpha = 0.45f))
            )
        }
        Box(Modifier.fillMaxWidth(shown).height(4.dp).clip(CircleShape).background(Color.White))
        Box(
            Modifier
                .offset(x = (w * shown - thumbSize / 2f).coerceIn(0.dp, w - thumbSize))
                .size(thumbSize)
                .clip(CircleShape)
                .background(Color.White)
        )
    }
}


internal val SPEEDS = listOf(0.5f, 1f, 1.25f, 1.5f, 2f)

internal fun nextSpeed(current: Float): Float {
    val i = SPEEDS.indexOf(current)
    return SPEEDS[(if (i < 0) 1 else i + 1) % SPEEDS.size]
}

internal fun speedLabel(s: Float): String {
    val n = if (s % 1f == 0f) s.toInt().toString() else s.toString().trimEnd('0').trimEnd('.')
    return "${n}×"
}

internal fun nextResizeMode(current: Int): Int = when (current) {
    AspectRatioFrameLayout.RESIZE_MODE_FIT -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
    AspectRatioFrameLayout.RESIZE_MODE_ZOOM -> AspectRatioFrameLayout.RESIZE_MODE_FILL
    else -> AspectRatioFrameLayout.RESIZE_MODE_FIT
}

internal fun formatTime(ms: Long): String {
    if (ms <= 0) return "0:00"
    val totalSec = ms / 1000
    val s = totalSec % 60
    val m = (totalSec / 60) % 60
    val h = totalSec / 3600
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}
