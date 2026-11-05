package com.playbridge.sender.player

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.requiredHeight
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.playbridge.sender.cast.remote.RemoteSeekStyle
import com.playbridge.sender.cast.remote.rememberRemoteMotionPolicy
import com.playbridge.sender.cast.remote.ui.TIMELINE_BLEED
import com.playbridge.sender.cast.remote.ui.drawRemoteTimeline
import com.playbridge.sender.cast.remote.ui.remoteWaveClock

/**
 * The player's seek bar, drawn exactly like the remote's: the played line waves while playing,
 * lies flat when paused or held, and shimmers while buffering. Tap to seek, drag to scrub.
 * An active A-B loop is shaded between its two markers.
 */
@Composable
internal fun PlaybackTimeline(state: PlayerScreenState, modifier: Modifier = Modifier) {
    val motion = rememberRemoteMotionPolicy()
    var dragging by remember { mutableStateOf(false) }
    val durationMs = state.playbackDuration.longValue
    val shownMs = if (state.isScrubbing) (state.scrubFraction * durationMs).toLong() else state.playbackProgress.longValue
    val fraction = if (durationMs > 0) (shownMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f

    // The clock drifts the wave while playing and runs the shimmer while buffering.
    val waveActive = motion.waveRuns(state.playWhenReady)
    val phase = if (waveActive) remoteWaveClock() else 0f
    val waving = state.isPlaying && !state.isBuffering && !dragging
    val amplitude by animateDpAsState(
        targetValue = if (waving) RemoteSeekStyle.AMPLITUDE_DP.dp else 0.dp,
        animationSpec = tween(motion.duration(if (waving) RemoteSeekStyle.GROW_MS else RemoteSeekStyle.FLATTEN_MS)),
        label = "amplitude",
    )
    val thumbRadius by animateDpAsState(
        targetValue = if (dragging) RemoteSeekStyle.THUMB_HELD_RADIUS_DP.dp else RemoteSeekStyle.THUMB_RADIUS_DP.dp,
        animationSpec = tween(if (motion.animate) 140 else 0),
        label = "thumbRadius",
    )
    val played = MaterialTheme.colorScheme.primary
    val bleedPx = with(LocalDensity.current) { TIMELINE_BLEED.toPx() }

    fun fractionAt(x: Float, width: Int): Float = ((x - bleedPx) / (width - bleedPx * 2)).coerceIn(0f, 1f)

    Box(
        modifier = modifier
            .height(32.dp)
            .pointerInput(Unit) {
                detectHorizontalDragGestures(
                    onDragStart = { o ->
                        dragging = true
                        state.isScrubbing = true
                        state.scrubFraction = fractionAt(o.x, size.width)
                        state.markInteraction()
                    },
                    onHorizontalDrag = { change, _ ->
                        state.scrubFraction = fractionAt(change.position.x, size.width)
                        state.markInteraction()
                    },
                    onDragEnd = {
                        dragging = false
                        state.seekToFraction(state.scrubFraction)
                    },
                    onDragCancel = {
                        dragging = false
                        state.isScrubbing = false
                    },
                )
            }
            .pointerInput(Unit) {
                detectTapGestures { o ->
                    state.markInteraction()
                    state.seekToFraction(fractionAt(o.x, size.width))
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.fillMaxWidth().requiredHeight(24.dp + TIMELINE_BLEED * 2)) {
            if (durationMs <= 0L) {
                drawLine(Color.White.copy(alpha = 0.2f), Offset(bleedPx, size.height / 2f),
                    Offset(size.width - bleedPx, size.height / 2f), strokeWidth = 2.dp.toPx(), cap = StrokeCap.Round)
                return@Canvas
            }
            val trackWidth = size.width - bleedPx * 2
            val start = state.abStartMs
            if (start != null) {
                val end = state.abEndMs ?: start
                val from = bleedPx + trackWidth * (start.toFloat() / durationMs).coerceIn(0f, 1f)
                val to = bleedPx + trackWidth * (end.toFloat() / durationMs).coerceIn(0f, 1f)
                val y = size.height / 2f
                if (to > from) {
                    drawLine(played.copy(alpha = 0.25f), Offset(from, y), Offset(to, y),
                        strokeWidth = 12.dp.toPx(), cap = StrokeCap.Round)
                }
                listOfNotNull(from, state.abEndMs?.let { to }).forEach { x ->
                    drawLine(Color.White.copy(alpha = 0.85f), Offset(x, y - 10.dp.toPx()), Offset(x, y + 10.dp.toPx()),
                        strokeWidth = 2.dp.toPx())
                }
            }
            drawRemoteTimeline(
                fraction = fraction,
                durationMs = durationMs,
                bufferedMs = state.playbackBuffered.longValue,
                chapters = emptyList(),
                segments = emptyList(),
                amplitudePx = amplitude.toPx(),
                phase = phase,
                shimmer = state.isBuffering && waveActive,
                thumbRadiusPx = thumbRadius.toPx(),
                held = dragging,
                played = played,
                ink = Color.White,
                segmentColor = played,
            )
        }
    }
}
