package com.playbridge.player.ui.player

import android.view.KeyEvent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.nativeKeyCode
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import com.playbridge.player.player.SkipSegment
import com.playbridge.player.player.chapterFractions
import com.playbridge.player.player.segmentFractions
import com.playbridge.player.ui.theme.Primary
import com.playbridge.player.ui.theme.TvSize
import com.playbridge.player.ui.theme.TvSpacing
import com.playbridge.player.ui.theme.TvText
import com.playbridge.player.ui.theme.TvTheme
import com.playbridge.shared.player.PlayerChapter
import kotlinx.coroutines.flow.StateFlow
import java.util.Locale

private val BarThumbRadius = 11.dp
private val BarCanvasHeight = 22.dp
private val ChapterMarkWidth = 3.dp
private val ChapterMarkHeight = 12.dp

/**
 * Seek bar on the full controls (mockup PlayerControls): an 8dp bar with elapsed and total time
 * under it. It takes focus; Left/Right seek (10 s, faster while held) and the thumb appears only
 * while focused. Nothing here changes size on focus. Position comes from [progress] and is read
 * in the draw phase, so only this bar redraws as playback moves.
 */
@Composable
fun PlayerSeekbar(
    progress: StateFlow<SeekProgress>,
    onSeek: (Long) -> Unit,
    focusRequester: FocusRequester,
    downFocus: FocusRequester,
    onFocused: () -> Unit,
    modifier: Modifier = Modifier,
    chapters: List<PlayerChapter> = emptyList(),
    segments: List<SkipSegment> = emptyList(),
) {
    val state = progress.collectAsState()
    var focused by remember { mutableStateOf(false) }
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(TvSpacing.s),
    ) {
        SeekBarCanvas(
            progress = state,
            chapters = chapters,
            segments = segments,
            showThumb = { focused },
            modifier = Modifier
                .focusRequester(focusRequester)
                .focusProperties { down = downFocus }
                .onFocusChanged {
                    focused = it.isFocused
                    if (it.isFocused) onFocused()
                }
                .onKeyEvent { event ->
                    if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
                    when (event.key.nativeKeyCode) {
                        KeyEvent.KEYCODE_DPAD_LEFT -> {
                            onSeek(-seekStepMs(event.nativeKeyEvent.repeatCount)); true
                        }
                        KeyEvent.KEYCODE_DPAD_RIGHT -> {
                            onSeek(seekStepMs(event.nativeKeyEvent.repeatCount)); true
                        }
                        else -> false
                    }
                }
                .focusable()
                .semantics { contentDescription = "Seek bar" },
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            ClockText(state) { formatClock(it.position) }
            ClockText(state) { if (it.duration > 0L) formatClock(it.duration) else "" }
        }
    }
}

/**
 * The seek bar shown alone when Left or Right is pressed with nothing on screen (mockup
 * PlayerSeek): the target time large, the chapter or skip-segment [caption] under it, the total
 * on the right, the bar, and (with [showHints]) a hint line. A phone's seek (PhoneSeeking) omits the hints. It is not focusable; keys keep going to the host.
 */
@Composable
fun SeekStrip(
    progress: StateFlow<SeekProgress>,
    modifier: Modifier = Modifier,
    chapters: List<PlayerChapter> = emptyList(),
    segments: List<SkipSegment> = emptyList(),
    caption: String? = null,
    showHints: Boolean = true,
) {
    val state = progress.collectAsState()
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = TvSpacing.screenGutter),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Bottom,
        ) {
            Column {
                ClockText(state, style = STRIP_TIME_STYLE, color = TvTheme.player.onSurface) { formatClock(it.position) }
                Text(
                    text = caption.orEmpty().ifBlank { " " },
                    style = TvText.caption,
                    color = TvTheme.player.onSurfaceMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            ClockText(state, style = TvText.row) {
                if (it.duration > 0L) formatClock(it.duration) else ""
            }
        }
        SeekBarCanvas(
            progress = state,
            chapters = chapters,
            segments = segments,
            showThumb = { true },
        )
        if (showHints) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Left and right to seek 10 seconds", style = TvText.caption, color = TvTheme.player.onSurfaceMuted)
                Text("Down for controls", style = TvText.caption, color = TvTheme.player.onSurfaceMuted)
            }
        }
    }
}

private val STRIP_TIME_STYLE = TvText.panelTitle.copy(fontSize = 34.sp, lineHeight = 38.sp)

/** A time label that re-reads [progress] itself, so its parent does not recompose as playback moves. */
@Composable
internal fun ClockText(
    progress: State<SeekProgress>,
    style: androidx.compose.ui.text.TextStyle = TvText.value,
    color: Color = TvTheme.player.onSurfaceMuted,
    text: (SeekProgress) -> String,
) {
    Text(text = text(progress.value), style = style, color = color, maxLines = 1)
}

/**
 * Track, buffered range, skip-segment bands, played part, chapter marks and (optionally) thumb.
 * Fixed 22dp tall; [showThumb] and [progress] are read while drawing, never in composition.
 */
@Composable
private fun SeekBarCanvas(
    progress: State<SeekProgress>,
    chapters: List<PlayerChapter>,
    segments: List<SkipSegment>,
    showThumb: () -> Boolean,
    modifier: Modifier = Modifier,
) {
    val durationState = remember(progress) { derivedStateOf { progress.value.duration } }
    val duration = durationState.value
    val chapterMarks = remember(chapters, duration) { chapterFractions(chapters, duration) }
    val segmentRanges = remember(segments, duration) { segmentFractions(segments, duration) }
    val trackColor = TvTheme.player.track
    val bufferColor = TvTheme.player.buffer
    val bandColor = Primary.copy(alpha = 0.45f)
    val playedColor = Primary
    val markColor = TvTheme.player.background
    val thumbColor = TvTheme.player.onSurface
    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(BarCanvasHeight),
    ) {
        val p = progress.value
        val width = size.width
        val barHeight = TvSize.seekBarHeight.toPx()
        val top = (size.height - barHeight) / 2f
        val radius = CornerRadius(barHeight / 2f)
        val fraction = if (p.duration > 0L) (p.position.toFloat() / p.duration).coerceIn(0f, 1f) else 0f
        val bufferFraction = if (p.duration > 0L) (p.buffered.toFloat() / p.duration).coerceIn(0f, 1f) else 0f

        drawRoundRect(trackColor, Offset(0f, top), Size(width, barHeight), radius)
        if (bufferFraction > 0f) {
            drawRoundRect(bufferColor, Offset(0f, top), Size(width * bufferFraction, barHeight), radius)
        }
        segmentRanges.forEach { range ->
            val startX = width * range.startFraction
            val endX = width * range.endFraction
            if (endX > startX) drawRect(bandColor, Offset(startX, top), Size(endX - startX, barHeight))
        }
        if (fraction > 0f) {
            drawRoundRect(playedColor, Offset(0f, top), Size(width * fraction, barHeight), radius)
        }
        val markW = ChapterMarkWidth.toPx()
        val markH = ChapterMarkHeight.toPx()
        chapterMarks.forEach { f ->
            drawRect(markColor, Offset(width * f - markW / 2f, (size.height - markH) / 2f), Size(markW, markH))
        }
        if (showThumb()) {
            drawCircle(thumbColor, BarThumbRadius.toPx(), Offset(width * fraction, size.height / 2f))
        }
    }
}

/** 6:42 or 1:02:03, ASCII digits. The elapsed and total labels on the controls use this. */
internal fun formatClock(ms: Long): String {
    val totalSeconds = (ms / 1000).coerceAtLeast(0)
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) {
        String.format(Locale.ROOT, "%d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format(Locale.ROOT, "%d:%02d", minutes, seconds)
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
