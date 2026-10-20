package com.playbridge.sender.cast.remote.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.VolumeDown
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.key
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import com.playbridge.sender.cast.remote.LocalRemoteMotionPolicy
import com.playbridge.sender.cast.remote.RemoteAction
import com.playbridge.sender.cast.remote.volumeLabel
import com.playbridge.sender.cast.remote.RemoteFeedback
import com.playbridge.sender.cast.remote.RemoteLayoutSpec
import com.playbridge.sender.cast.remote.RemotePlayback
import com.playbridge.sender.cast.remote.RemoteLearnedGesture
import com.playbridge.sender.cast.remote.RemoteSeekAxis
import com.playbridge.sender.cast.remote.RemoteSeekGesturePolicy
import com.playbridge.sender.cast.remote.RemoteTimeDisplay
import com.playbridge.sender.cast.remote.RemoteUiState
import com.playbridge.sender.cast.remote.scrubFeedback
import com.playbridge.sender.cast.remote.remotePressFeedback
import com.playbridge.sender.cast.remote.relativeRemoteSeek
import com.playbridge.sender.cast.remote.rememberRemoteHaptics
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.DisposableEffect
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import android.os.SystemClock
import com.playbridge.sender.cast.remote.RemoteVolumeDragThrottle
import com.playbridge.sender.cast.remote.volumeDragTarget
import com.playbridge.sender.cast.remote.volumeStep
import kotlin.math.PI
import kotlin.math.roundToInt
import kotlin.math.abs
import kotlin.math.sin

@Composable
internal fun SeekVolumeBar(
    positionMs: Long,
    durationMs: Long,
    isLive: Boolean,
    isSeekable: Boolean,
    isPlaying: Boolean,
    enableVolume: Boolean,
    onSeekTo: (Long) -> Unit,
    onVolumeUp: () -> Unit,
    onVolumeDown: () -> Unit,
    onPlayPauseToggle: () -> Unit,
    learnedGestures: Set<String> = emptySet(),
    timeDisplay: RemoteTimeDisplay = RemoteTimeDisplay.Duration,
    onTimeDisplay: (RemoteTimeDisplay) -> Unit = {},
    enablePlayPause: Boolean = true,
    volumeTitle: String = "Volume",
    volumePercent: Int? = null,
    volumeIncrement: Double = .05,
    absoluteVolume: Boolean = false,
    volumeRouteKey: Any? = null,
    onVolumeSet: (Int) -> Unit = {},
) {
    val enabled = LocalRemoteControlsEnabled.current
    val hasDuration = durationMs > 0L && !isLive && isSeekable
    val pressInteraction = remember { MutableInteractionSource() }
    val durationInteraction = remember { MutableInteractionSource() }
    var durationPressInWave by remember { mutableStateOf(false) }
    val currentPosition by rememberUpdatedState(positionMs)

    val density = LocalDensity.current
    var widthPx by remember { mutableFloatStateOf(0f) }
    var barHeightPx by remember { mutableFloatStateOf(with(density) { 86.dp.toPx() }) }
    var timeCenterY by remember { mutableFloatStateOf(with(density) { 64.dp.toPx() }) }
    val durationTargetPx = with(density) { 48.dp.toPx() }
    var dragging by remember { mutableStateOf(false) }
    var dragMs by remember { mutableFloatStateOf(0f) }
    var activeAxis by remember { mutableStateOf<RemoteSeekAxis?>(null) }
    var volumeDirection by remember { mutableStateOf<String?>(null) }
    var directScrub by remember { mutableStateOf(false) }
    var fastVolume by remember { mutableStateOf(false) }
    var barOriginY by remember { mutableFloatStateOf(0f) }
    var waveCenterY by remember { mutableFloatStateOf(44f) }

    val hitTolerancePx = with(LocalDensity.current) { 24.dp.toPx() }
    val touchSlopPx = LocalViewConfiguration.current.touchSlop
    val volumeStepPx = with(LocalDensity.current) { 28.dp.toPx() }
    // The wave canvas is inset by the arrow gutters (52dp each side); absolute
    // finger-follow scrubbing maps the pointer x within that span.
    val contentInsetPx = with(LocalDensity.current) { 52.dp.toPx() }

    val ink = MaterialTheme.colorScheme.onSurface
    val motion = LocalRemoteMotionPolicy.current
    val haptics = rememberRemoteHaptics()
    val latestSeek by rememberUpdatedState(onSeekTo)
    val latestPlayPause by rememberUpdatedState(onPlayPauseToggle)
    val latestVolumeUp by rememberUpdatedState(onVolumeUp)
    val latestVolumeDown by rememberUpdatedState(onVolumeDown)
    val latestVolumeSet by rememberUpdatedState(onVolumeSet)
    val currentVolume by rememberUpdatedState(volumePercent)
    val currentIncrement by rememberUpdatedState(volumeIncrement)
    val currentAbsolute by rememberUpdatedState(absoluteVolume)
    var dragVolume by remember { mutableStateOf<Int?>(null) }
    val volumeScope = rememberCoroutineScope()
    val volumeThrottle = remember(volumeRouteKey) { RemoteVolumeDragThrottle() }
    var volumeJob by remember(volumeRouteKey) { mutableStateOf<Job?>(null) }
    fun drainVolume() {
        if (volumeJob?.isActive == true) return
        volumeJob = volumeScope.launch {
            while (volumeThrottle.pending) {
                when (val action = volumeThrottle.next(SystemClock.uptimeMillis())) {
                    is RemoteAction.SetVolume -> latestVolumeSet(action.percent)
                    is RemoteAction.AdjustVolume -> if (action.steps > 0) latestVolumeUp() else latestVolumeDown()
                    else -> Unit
                }
                delay(16)
            }
        }
    }
    DisposableEffect(volumeRouteKey, enabled, enableVolume, motion.resumed) {
        onDispose { volumeJob?.cancel(); volumeThrottle.clear() }
    }

    // Remove the infinite transition from composition entirely while inactive.
    val waveActive = motion.waveRuns(isPlaying)
    val clocks = if (waveActive) remoteWaveClocks() else 0f to 1f
    val phase = clocks.first
    val breathValue = clocks.second

    val targetAmplitude = if (isPlaying && !dragging) 7.dp else 0.dp
    val amplitude by animateDpAsState(targetValue = targetAmplitude, animationSpec = tween(durationMillis = if (motion.animate) 180 else 0), label = "amplitude")

    val lift by animateDpAsState(targetValue = if (activeAxis != null) 12.dp else 0.dp, animationSpec = tween(durationMillis = if (motion.animate) 160 else 0), label = "seekBarLift")

    val displayMs = if (dragging && hasDuration) dragMs else currentPosition.toFloat()
    val fraction = if (hasDuration && durationMs > 0L) (displayMs / durationMs.toFloat()).coerceIn(0f, 1f) else 0f

    val primary = MaterialTheme.colorScheme.primary
    val activeColor = primary
    val learned = RemoteLearnedGesture.hintLearned(learnedGestures, hasDuration, enableVolume, enablePlayPause)
    val hintAlpha by animateFloatAsState(if (learned) 0f else 1f, tween(if (motion.animate) 150 else 0), label = "learnedHint")
    val thumbScale by animateFloatAsState(targetValue = if (dragging) 1.5f else 1.0f, animationSpec = tween(if (motion.animate) 120 else 0), label = "thumbScale")

    val popupOffset = with(LocalDensity.current) { IntOffset(0, -180.dp.roundToPx()) }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .offset { IntOffset(0, -lift.roundToPx()) }
            .shadow(elevation = lift, shape = RoundedCornerShape(28.dp), clip = false)
            .heightIn(min = 86.dp)
            .remoteSolidSurface(RoundedCornerShape(28.dp))
            .remotePressFeedback(pressInteraction)
            .clickable(enabled = enabled && enablePlayPause && motion.resumed, interactionSource = pressInteraction, indication = null) {
                if (!dragging && activeAxis == null) latestPlayPause()
            }
            .semantics(mergeDescendants = true) {
                contentDescription = if (volumeTitle == "App volume") "Playback seek and App volume" else "Playback seek and volume"
                stateDescription = if (isPlaying) "Playing" else "Paused"
                if (!enabled) disabled()
                if (hasDuration) {
                    progressBarRangeInfo = ProgressBarRangeInfo(displayMs, 0f..durationMs.toFloat())
                    setProgress { target -> if (!enabled) return@setProgress false; latestSeek(target.toLong().coerceIn(0L, durationMs)); true }
                }
                customActions = if (!enabled) emptyList() else buildList {
                    if (enablePlayPause) add(CustomAccessibilityAction(if (isPlaying) "Pause" else "Play") { latestPlayPause(); true })
                    if (hasDuration) {
                        add(CustomAccessibilityAction("Seek back 10 seconds") { latestSeek(relativeRemoteSeek(currentPosition, -10_000L, durationMs)); true })
                        add(CustomAccessibilityAction("Seek forward 10 seconds") { latestSeek(relativeRemoteSeek(currentPosition, 10_000L, durationMs)); true })
                    }
                }
            }
            .onSizeChanged { widthPx = it.width.toFloat(); barHeightPx = it.height.toFloat() }
            .onGloballyPositioned { barOriginY = it.positionInRoot().y }
            .pointerInput(enabled, hasDuration, enableVolume, durationMs, motion.resumed, volumeRouteKey) {
                if (!enabled || !motion.resumed) return@pointerInput
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val policy = RemoteSeekGesturePolicy(widthPx, contentInsetPx, durationMs, currentPosition,
                        down.position.x, down.position.y, waveCenterY, hitTolerancePx, touchSlopPx)
                    var axis: RemoteSeekAxis? = null
                    var appliedY = 0f
                    var volumeAccum = 0f
                    val volumeStart = currentVolume?.div(100.0)
                    val volumeStep = currentIncrement
                    val useAbsolute = currentAbsolute && volumeStart != null
                    var volumeSteps = 0.0
                    var released = false
                    var lastScrubTickMs: Long? = null
                    dragMs = currentPosition.toFloat()
                    directScrub = policy.direct
                    try {
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            if (change.isConsumed) break
                            if (!change.pressed) {
                                released = true
                                if (activeAxis != null) change.consume()
                                break
                            }
                            val dx = change.position.x - down.position.x
                            val dy = change.position.y - down.position.y
                            if (axis == null) {
                                axis = policy.lockAxis(dx, dy)
                                fastVolume = axis == RemoteSeekAxis.Vertical && change.uptimeMillis - down.uptimeMillis >= 400L
                            }
                            // An unavailable drag must still cancel the parent's tap action.
                            if (axis != null) change.consume()
                            if (axis == RemoteSeekAxis.Horizontal && hasDuration) {
                                change.consume()
                                activeAxis = axis
                                val started = !dragging
                                dragging = true
                                val previous = dragMs
                                dragMs = policy.positionAt(change.position.x).toFloat()
                                scrubFeedback(previous, dragMs, durationMs, started, change.uptimeMillis, lastScrubTickMs)?.let { feedback ->
                                    haptics.perform(feedback)
                                    lastScrubTickMs = change.uptimeMillis
                                }
                            } else if (axis == RemoteSeekAxis.Vertical && enableVolume) {
                                change.consume()
                                activeAxis = axis
                                val stepPx = if (fastVolume) volumeStepPx / 2.5f else volumeStepPx
                                val movement = -(dy - appliedY)
                                volumeAccum += movement
                                volumeSteps += movement / stepPx
                                appliedY = dy
                                dragVolume = volumeStart?.let { volumeDragTarget(it, volumeSteps, volumeStep) }
                                var steps = 0
                                while (volumeAccum >= stepPx) {
                                    haptics.perform(RemoteFeedback.VOLUME); volumeAccum -= stepPx; volumeDirection = "up"; steps++
                                }
                                while (volumeAccum <= -stepPx) {
                                    haptics.perform(RemoteFeedback.VOLUME); volumeAccum += stepPx; volumeDirection = "down"; steps--
                                }
                                if (useAbsolute) volumeThrottle.absolute(dragVolume!!)
                                else volumeThrottle.relative(steps)
                                drainVolume()
                            }
                        }
                        if (released && activeAxis == RemoteSeekAxis.Vertical && useAbsolute && dragVolume != null) {
                            volumeThrottle.absolute(dragVolume!!); drainVolume()
                        }
                        if (released && dragging && hasDuration) latestSeek(dragMs.toLong())
                    } finally {
                        dragging = false; activeAxis = null; volumeDirection = null; directScrub = false; fastVolume = false; dragVolume = null
                    }
                }
            },
        contentAlignment = Alignment.CenterStart
    ) {
        if (enableVolume) {
            Icon(Icons.Filled.KeyboardArrowUp, contentDescription = null, tint = if (activeAxis == RemoteSeekAxis.Vertical) activeColor else ink.copy(alpha = 0.3f), modifier = Modifier.align(Alignment.TopCenter).padding(top = 4.dp).size(16.dp))
            Icon(Icons.Filled.KeyboardArrowDown, contentDescription = null, tint = if (activeAxis == RemoteSeekAxis.Vertical) activeColor else ink.copy(alpha = 0.3f), modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 4.dp).size(16.dp))
        }

        Column(
            modifier = Modifier.fillMaxWidth().heightIn(min = 86.dp).padding(vertical = 14.dp),
            verticalArrangement = Arrangement.SpaceBetween,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box(Modifier.fillMaxWidth().padding(horizontal = 52.dp).heightIn(min = 16.dp)
                .testTag("remote.hintRow"), contentAlignment = Alignment.Center) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally)) {
                    if (hintAlpha > 0f) {
                        val hint = buildList {
                            if (hasDuration) add("◄► SEEK")
                            if (enablePlayPause) add("TAP")
                        }.joinToString(" · ")
                        Text(hint, modifier = Modifier.alpha(hintAlpha).testTag("remote.seekHint"),
                            style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp, fontWeight = FontWeight.Bold),
                            color = ink.copy(alpha = 0.65f))
                    }
                    // The state glyph belongs to the hint, never the canvas or its fade.
                    // 15 sp sits in the 14–16 sp range and stays inside the 16 dp hint row at normal font.
                    val glyph = with(LocalDensity.current) { 15.sp.toDp() }
                    Icon(if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow, contentDescription = null,
                        tint = ink, modifier = Modifier.size(glyph).align(Alignment.CenterVertically).testTag("remote.playGlyph"))
                    val learnedHint = learnedTimeHint(displayMs.toLong(), durationMs, isLive)
                    Box {
                        if (enableVolume && hintAlpha > 0f) Text("▲▼ VOLUME", modifier = Modifier.alpha(hintAlpha).testTag("remote.volumeHint"),
                            style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp, fontWeight = FontWeight.Bold),
                            color = ink.copy(alpha = 0.65f))
                        if (hintAlpha < 1f && learnedHint != null) Text(learnedHint,
                            modifier = Modifier.alpha(1f - hintAlpha).testTag("remote.remainingHint"),
                            style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp, fontFeatureSettings = "tnum"),
                            color = ink.copy(alpha = 0.7f))
                    }
                }
            }

            Box(Modifier.fillMaxWidth().height(24.dp).testTag("remote.waveRow")
                .onGloballyPositioned { waveCenterY = it.positionInRoot().y + it.size.height / 2f - barOriginY },
                contentAlignment = Alignment.Center) {
                if (enableVolume) {
                    Box(
                        modifier = Modifier.fillMaxHeight().width(1.dp).testTag("remote.volumeGuide")
                            .background(Brush.verticalGradient(listOf(Color.Transparent, ink.copy(alpha = if (activeAxis == RemoteSeekAxis.Vertical) 0.5f else 0.1f), Color.Transparent)))
                            .align(Alignment.Center)
                    )
                }
                Box(Modifier.fillMaxWidth().height(1.dp).testTag("remote.waveGuide")
                    .background(Brush.horizontalGradient(listOf(Color.Transparent, ink.copy(alpha = if (activeAxis == RemoteSeekAxis.Horizontal) 0.5f else 0.1f), Color.Transparent))))
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = null,
                    tint = if (activeAxis == RemoteSeekAxis.Horizontal) activeColor else ink.copy(alpha = 0.3f),
                    modifier = Modifier.align(Alignment.CenterStart).padding(start = 12.dp).size(24.dp).testTag("remote.seekLeft"))
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null,
                    tint = if (activeAxis == RemoteSeekAxis.Horizontal) activeColor else ink.copy(alpha = 0.3f),
                    modifier = Modifier.align(Alignment.CenterEnd).padding(end = 12.dp).size(24.dp).testTag("remote.seekRight"))

                if (hasDuration) {
                    val ampPx = with(LocalDensity.current) { amplitude.toPx() }
                    val wavelength = with(LocalDensity.current) { 50.dp.toPx() }
                    val tickHeight = with(LocalDensity.current) { 4.dp.toPx() }
                    val strokeActiveWidth = with(LocalDensity.current) { 4.dp.toPx() }
                    val strokeInactiveWidth = with(LocalDensity.current) { 2.dp.toPx() }

                    // Slow "breathing" of the wave height so the motion feels organic rather
                    // than a fixed-height ripple; layered with the phase scroll below.
                    // Perf: static (breathValue) when the wave is idle.
                    val breath = breathValue

                    Canvas(modifier = Modifier.fillMaxWidth().padding(horizontal = 52.dp).height(24.dp).testTag("remote.waveCanvas")) {
                        val width = size.width
                        val height = size.height
                        val centerY = height / 2f
                        val amp = ampPx * breath
                        val activeWidth = width * fraction

                        val tickCount = 30
                        val tickSpacing = width / tickCount
                        for (i in 0..tickCount) {
                            val x = i * tickSpacing
                            drawLine(color = ink.copy(alpha = 0.1f), start = Offset(x, centerY - tickHeight), end = Offset(x, centerY + tickHeight), strokeWidth = 1f)
                        }

                        fun waveY(x: Float, p: Float, a: Float, wl: Float): Float {
                            val safeX = x.coerceIn(0f, width)
                            val envelope = if (width > 0f) sin(Math.PI * (safeX / width)).toFloat() else 0f
                            val angle = (2 * Math.PI * x / wl).toFloat() + p
                            return centerY + a * envelope * sin(angle)
                        }

                        fun buildWave(fromX: Float, toX: Float, p: Float, a: Float, wl: Float) = Path().apply {
                            moveTo(fromX, waveY(fromX, p, a, wl))
                            var x = fromX + 1f
                            while (x <= toX) { lineTo(x, waveY(x, p, a, wl)); x += 2f }
                        }

                        // Unplayed side: nearly flat — the energy lives behind the playhead.
                        if (activeWidth < width) {
                            drawPath(
                                path = buildWave(activeWidth, width, phase, amp * 0.15f, wavelength),
                                color = ink.copy(alpha = 0.15f),
                                style = Stroke(width = strokeInactiveWidth, cap = StrokeCap.Round)
                            )
                        }

                        if (activeWidth > 0f) {
                            val activeBrush = Brush.horizontalGradient(
                                colors = listOf(activeColor.copy(alpha = 0.8f), ink),
                                startX = 0f, endX = activeWidth
                            )
                            val mainWave = buildWave(0f, activeWidth, phase, amp, wavelength)

                            // Soft glow under the main wave.
                            drawPath(
                                path = mainWave,
                                color = activeColor.copy(alpha = 0.25f),
                                style = Stroke(width = strokeActiveWidth * 2.8f, cap = StrokeCap.Round)
                            )
                            // Faint counter-scrolling harmonic behind the main wave — the two
                            // layers interfere visually and read as liquid. Phase multiplier
                            // must be an integer so the infinite loop's 2π restart is seamless.
                            drawPath(
                                path = buildWave(0f, activeWidth, -phase * 2f, amp * 0.45f, wavelength * 0.55f),
                                color = activeColor.copy(alpha = 0.35f),
                                style = Stroke(width = strokeInactiveWidth, cap = StrokeCap.Round)
                            )
                            drawPath(path = mainWave, brush = activeBrush, style = Stroke(width = strokeActiveWidth, cap = StrokeCap.Round))

                            if (fraction > 0f && fraction < 1f) {
                                val thumbX = activeWidth
                                val barWidth = 3.dp.toPx() * thumbScale
                                val barHeight = 18.dp.toPx() * thumbScale
                                // Halo behind the playhead (grows with the drag thumb scale).
                                drawCircle(
                                    color = activeColor.copy(alpha = 0.30f),
                                    radius = 9.dp.toPx() * thumbScale,
                                    center = Offset(thumbX, centerY)
                                )
                                drawLine(
                                    color = ink,
                                    start = Offset(thumbX, centerY - barHeight / 2),
                                    end = Offset(thumbX, centerY + barHeight / 2),
                                    strokeWidth = barWidth,
                                    cap = StrokeCap.Round
                                )
                            }
                        }
                    }
                } else {
                    Box(modifier = Modifier.fillMaxWidth().padding(horizontal = 52.dp).height(2.dp).background(ink.copy(alpha = 0.2f)).testTag("remote.waveCanvas"))
                }

            }

            Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 52.dp)
                .onGloballyPositioned { timeCenterY = it.positionInRoot().y + it.size.height / 2f - barOriginY }) {
                Text(if (hasDuration) formatTime(displayMs.toLong()) else formatTime(currentPosition),
                    modifier = Modifier.testTag("remote.elapsedTime"),
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp, fontFeatureSettings = "tnum"),
                    color = ink.copy(alpha = 0.7f), maxLines = 1, overflow = TextOverflow.Visible)
            }
        }
        // A 48 dp duration target overlays the clock row, so its hit area does not
        // make the stage-1 86 dp card taller. The text shares the elapsed baseline.
        Box(Modifier.align(Alignment.BottomEnd).padding(end = 52.dp).height(48.dp).widthIn(min = 48.dp)
            .remotePressFeedback(durationInteraction)
            .pointerInput(waveCenterY, barHeightPx) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val cardY = barHeightPx - durationTargetPx + down.position.y
                    durationPressInWave = abs(cardY - waveCenterY) <= with(density) { 12.dp.toPx() }
                    try {
                        // Clickable handles up in Main; clear the physical-touch flag in
                        // Final so subsequent accessibility clicks still toggle duration.
                        while (true) {
                            val event = awaitPointerEvent(PointerEventPass.Final)
                            if (event.changes.none { it.id == down.id && it.pressed }) break
                        }
                    } finally { durationPressInWave = false }
                }
            }
            .clickable(enabled = enabled && hasDuration && motion.resumed, interactionSource = durationInteraction, indication = null) {
                if (durationPressInWave) {
                    if (enablePlayPause) latestPlayPause()
                } else onTimeDisplay(if (timeDisplay == RemoteTimeDisplay.Duration) RemoteTimeDisplay.Remaining else RemoteTimeDisplay.Duration)
            }
            .semantics { contentDescription = if (timeDisplay == RemoteTimeDisplay.Duration) "Total duration" else "Time remaining" },
            contentAlignment = Alignment.CenterEnd) {
            Text(if (isLive) "● LIVE" else if (durationMs > 0L) {
                if (timeDisplay == RemoteTimeDisplay.Remaining) "−${formatTime((durationMs - displayMs.toLong()).coerceAtLeast(0))}" else formatTime(durationMs)
            } else "--:--",
                modifier = Modifier.offset { IntOffset(0, (timeCenterY - (barHeightPx - durationTargetPx / 2f)).roundToInt()) }
                    .testTag("remote.durationText"),
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp, fontFeatureSettings = "tnum", fontWeight = if (isLive) FontWeight.Bold else FontWeight.Normal),
                color = if (isLive) activeColor else ink.copy(alpha = 0.7f), maxLines = 1, overflow = TextOverflow.Visible)
        }
    }

    if (dragging && activeAxis == RemoteSeekAxis.Horizontal && hasDuration) {
        val deltaMs = dragMs.toLong() - currentPosition
        Popup(alignment = Alignment.Center, offset = popupOffset, properties = PopupProperties(focusable = false, dismissOnBackPress = false, dismissOnClickOutside = false)) {
            Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = RoundedCornerShape(20.dp), border = BorderStroke(1.dp, activeColor.copy(alpha = 0.5f))) {
                Column(modifier = Modifier.padding(horizontal = 28.dp, vertical = 18.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(if (directScrub) "Scrub" else "Seek", color = activeColor, style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold, letterSpacing = 1.sp))
                    Spacer(modifier = Modifier.height(6.dp))
                    Text("${formatTime(dragMs.toLong())} / ${formatTime(durationMs)}", color = ink, style = MaterialTheme.typography.titleMedium.copy(fontFeatureSettings = "tnum"), fontWeight = FontWeight.SemiBold)
                    val sign = if (deltaMs >= 0) "+" else "-"
                    Text("$sign${formatTime(abs(deltaMs))}", color = ink.copy(alpha = 0.8f), style = MaterialTheme.typography.labelMedium.copy(fontFeatureSettings = "tnum"))
                }
            }
        }
    }

    if (activeAxis == RemoteSeekAxis.Vertical && enableVolume) {
        Popup(alignment = Alignment.Center, offset = popupOffset, properties = PopupProperties(focusable = false, dismissOnBackPress = false, dismissOnClickOutside = false)) {
            Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = RoundedCornerShape(20.dp), border = BorderStroke(1.dp, activeColor.copy(alpha = 0.5f))) {
                Column(modifier = Modifier.padding(horizontal = 28.dp, vertical = 22.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    val volumeIcon = when (volumeDirection) { "down" -> Icons.AutoMirrored.Filled.VolumeDown else -> Icons.AutoMirrored.Filled.VolumeUp }
                    Icon(volumeIcon, contentDescription = null, tint = activeColor, modifier = Modifier.size(32.dp))
                    val volumeLabel = when (volumeDirection) {
                        "up" -> if (fastVolume) "$volumeTitle Up (Fast)" else "$volumeTitle Up"
                        "down" -> if (fastVolume) "$volumeTitle Down (Fast)" else "$volumeTitle Down"
                        else -> volumeTitle
                    }
                    Text(dragVolume?.let { "$volumeTitle $it/100" } ?: volumeLabel, color = ink, style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold, letterSpacing = 1.sp))
                }
            }
        }
    }
}

/** Learned hint beside the play glyph. No duration and not live returns null so the row is not a lone separator. */
internal fun learnedTimeHint(positionMs: Long, durationMs: Long, isLive: Boolean): String? = when {
    isLive -> "Live"
    durationMs > 0L -> "${formatTime((durationMs - positionMs).coerceAtLeast(0L))} left"
    else -> null
}

private fun formatTime(ms: Long): String {
    if (ms <= 0L) return "0:00"
    val totalSec = ms / 1000
    val h = totalSec / 3600
    val m = (totalSec % 3600) / 60
    val s = totalSec % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

/** The stage-1 wave and gesture implementation is preserved for 3c. */
@Composable
internal fun RemoteSeekBar(state: RemoteUiState, layout: RemoteLayoutSpec, dispatch: (RemoteAction) -> Unit) {
    val playback = state.playback ?: RemotePlayback()
    val enabled = LocalRemoteControlsEnabled.current
    val haptics = rememberRemoteHaptics()
    key(state.sessionId, playback.mediaId, playback.title, playback.mediaKind) {
    Column {
    if (state.nativePolicy != null && state.capabilities.transport.seekRelative == com.playbridge.sender.cast.remote.Support.Supported && state.capabilities.transport.seekAbsolute != com.playbridge.sender.cast.remote.Support.Supported) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            LabeledIconButton(Icons.AutoMirrored.Filled.KeyboardArrowLeft, "Seek backward", MaterialTheme.colorScheme.onSurface) { dispatch(RemoteAction.SeekBy(-10_000L)) }
            LabeledIconButton(Icons.AutoMirrored.Filled.KeyboardArrowRight, "Seek forward", MaterialTheme.colorScheme.onSurface) { dispatch(RemoteAction.SeekBy(10_000L)) }
        }
    }
    SeekVolumeBar(
        positionMs = playback.displayedPositionMs, durationMs = playback.durationMs,
        isLive = playback.isLive,
        isSeekable = playback.seekable == com.playbridge.sender.cast.remote.Support.Supported && playback.mediaKind != com.playbridge.sender.cast.remote.RemoteMediaKind.Image && (state.nativePolicy == null || state.capabilities.transport.seekAbsolute == com.playbridge.sender.cast.remote.Support.Supported),
        isPlaying = playback.displayedPlaying,
        learnedGestures = state.preferences.learnedGestures, timeDisplay = state.preferences.timeDisplay,
        onTimeDisplay = { dispatch(RemoteAction.SetTimeDisplay(it)) },
        enablePlayPause = state.capabilities.transport.playPause == com.playbridge.sender.cast.remote.Support.Supported,
        enableVolume = layout.nav.volume.enabled && playback.mediaKind != com.playbridge.sender.cast.remote.RemoteMediaKind.Image,
        volumeTitle = if (state.nativePolicy == null) "Volume" else state.volumeLabel(),
        onSeekTo = { dispatch(RemoteAction.SeekTo(it, RemoteLearnedGesture.SEEK)) },
        volumePercent = state.capabilities.volume.reportedPercent,
        volumeIncrement = state.volumeStep(),
        absoluteVolume = state.capabilities.volume.absolute == com.playbridge.sender.cast.remote.Support.Supported &&
            state.capabilities.volume.reportedPercent != null && state.fullControl?.connected != true &&
            !(state.nativePolicy?.reportedVolumeScope == "app" && state.receiverProtocol.descriptor?.volume?.scope == "system"),
        volumeRouteKey = listOf(state.sessionId, state.fullControl?.connected, state.nativePolicy?.reportedVolumeScope, state.capabilities.volume.absolute),
        onVolumeSet = { dispatch(RemoteAction.SetVolume(it)) },
        onVolumeUp = { dispatch(RemoteAction.AdjustVolume(1, RemoteLearnedGesture.VOLUME)) },
        onVolumeDown = { dispatch(RemoteAction.AdjustVolume(-1, RemoteLearnedGesture.VOLUME)) },
        onPlayPauseToggle = {
            if (enabled) { haptics.perform(RemoteFeedback.PLAY_PAUSE); dispatch(RemoteAction.TogglePlayPause) }
        },
    )
    }
    }
}

/** Called only while motion is enabled and playback is active, so no inactive infinite clock remains. */
@Composable
internal fun remoteWaveClocks(): Pair<Float, Float> {
    val transition = rememberInfiniteTransition(label = "remoteWave")
    val phase by transition.animateFloat(0f, (2 * Math.PI).toFloat(), infiniteRepeatable(tween(1500, easing = LinearEasing)), label = "phase")
    val breath by transition.animateFloat(0.8f, 1.2f, infiniteRepeatable(tween(2600), RepeatMode.Reverse), label = "breath")
    return phase to breath
}
