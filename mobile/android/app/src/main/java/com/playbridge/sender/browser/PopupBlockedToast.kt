package com.playbridge.sender.browser

import android.content.Context
import android.os.Build
import android.view.accessibility.AccessibilityManager
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationEndReason
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Security
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.playbridge.sender.downloads.PendingPopup
import java.net.IDN
import kotlin.math.abs
import kotlin.math.roundToInt

data class FormattedPopupDestination(
    val host: String,
    val path: String = "",
    val isGenericScheme: Boolean = false
) {
    val fullText: String
        get() = if (path.isEmpty()) host else "$host$path"
}

data class PopupToastState(
    val latestPopup: PendingPopup,
    val blockedCount: Int = 1,
    val totalTimeoutMs: Long = 6000L,
    val remainingMs: Long = 6000L,
    val isPaused: Boolean = false
)

fun formatPopupDestinationParts(rawUrl: String?): FormattedPopupDestination {
    if (rawUrl.isNullOrBlank() || rawUrl.trim().equals("about:blank", ignoreCase = true)) {
        return FormattedPopupDestination(host = "a blank page", isGenericScheme = true)
    }

    val url = rawUrl.trim()
    val colonIndex = url.indexOf(':')
    if (colonIndex != -1) {
        val scheme = url.substring(0, colonIndex).lowercase()
        if (scheme == "about") {
            return if (url.equals("about:blank", ignoreCase = true)) {
                FormattedPopupDestination(host = "a blank page", isGenericScheme = true)
            } else {
                FormattedPopupDestination(host = "an about: page", isGenericScheme = true)
            }
        }
        if (scheme != "http" && scheme != "https") {
            return FormattedPopupDestination(host = "a $scheme: page", isGenericScheme = true)
        }
    }

    val schemeDelimiter = "://"
    val afterScheme = if (url.contains(schemeDelimiter)) {
        url.substring(url.indexOf(schemeDelimiter) + schemeDelimiter.length)
    } else {
        url
    }

    val beforeQuery = afterScheme.substringBefore('?').substringBefore('#')
    val slashIndex = beforeQuery.indexOf('/')
    val hostAndPort = if (slashIndex != -1) beforeQuery.substring(0, slashIndex) else beforeQuery
    val rawPath = if (slashIndex != -1) beforeQuery.substring(slashIndex) else ""

    val portColon = hostAndPort.lastIndexOf(':')
    val rawHost = if (portColon != -1) hostAndPort.substring(0, portColon) else hostAndPort
    val portStr = if (portColon != -1) hostAndPort.substring(portColon) else ""

    val hostUnicode = try {
        IDN.toUnicode(rawHost)
    } catch (_: Exception) {
        rawHost
    }

    val hostDisplay = if (hostUnicode.length > 30) {
        val keep = (30 - 1) / 2
        hostUnicode.take(keep) + "…" + hostUnicode.takeLast(30 - 1 - keep) + portStr
    } else {
        hostUnicode + portStr
    }

    var pathDisplay = ""
    if (rawPath.isNotEmpty() && rawPath != "/") {
        val segments = rawPath.split('/').filter { it.isNotEmpty() }
        pathDisplay = if (segments.size > 1) {
            val first = "/" + segments[0]
            if (first.length > 16) first.take(15) + "…" else "$first…"
        } else if (segments.size == 1) {
            val seg = "/" + segments[0]
            if (seg.length > 20) seg.take(16) + "…" else seg
        } else {
            ""
        }
    }

    return FormattedPopupDestination(host = hostDisplay, path = pathDisplay, isGenericScheme = false)
}

fun formatPopupDestination(rawUrl: String?): String = formatPopupDestinationParts(rawUrl).fullText

fun popupBlockedTitle(count: Int): String {
    return if (count <= 1) "Pop-up blocked" else "$count pop-ups blocked"
}

fun formatAlwaysAllowLabel(openerHost: String, maxHostLen: Int = 18): String {
    val trimmed = openerHost.trim()
    return if (trimmed.isEmpty() || trimmed.length > maxHostLen) {
        "Always allow"
    } else {
        "Always allow $trimmed"
    }
}

fun onNewBlockedPopup(
    current: PopupToastState?,
    incoming: PendingPopup,
    timeoutMs: Long = 6000L
): Pair<PopupToastState, PendingPopup?> {
    val superseded = current?.latestPopup
    val newState = PopupToastState(
        latestPopup = incoming,
        blockedCount = if (current != null) current.blockedCount + 1 else 1,
        totalTimeoutMs = timeoutMs,
        remainingMs = timeoutMs,
        isPaused = false
    )
    return Pair(newState, superseded)
}

fun tickTimeout(state: PopupToastState, deltaMs: Long): PopupToastState {
    if (state.isPaused) return state
    val newRemaining = (state.remainingMs - deltaMs).coerceAtLeast(0L)
    return state.copy(remainingMs = newRemaining)
}

fun isTimeoutExpired(state: PopupToastState): Boolean = state.remainingMs <= 0L

fun setPaused(state: PopupToastState, paused: Boolean): PopupToastState {
    return state.copy(isPaused = paused)
}

fun timeoutProgress(state: PopupToastState): Float {
    if (state.totalTimeoutMs <= 0L) return 0f
    return (state.remainingMs.toFloat() / state.totalTimeoutMs.toFloat()).coerceIn(0f, 1f)
}

fun calculateToastTimeout(context: Context?, baseMs: Long = 6000L): Long {
    if (context == null) return baseMs
    val a11yManager = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as? AccessibilityManager
        ?: return baseMs
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        a11yManager.getRecommendedTimeoutMillis(
            baseMs.toInt(),
            AccessibilityManager.FLAG_CONTENT_CONTROLS or
                AccessibilityManager.FLAG_CONTENT_ICONS or
                AccessibilityManager.FLAG_CONTENT_TEXT
        ).toLong()
    } else {
        baseMs
    }
}

@Composable
fun PopupBlockedToast(
    state: PopupToastState,
    visible: Boolean,
    onOpen: () -> Unit,
    onAlwaysAllow: () -> Unit,
    onDismiss: () -> Unit,
    onTimeout: () -> Unit,
    modifier: Modifier = Modifier
) {
    val view = LocalView.current
    LaunchedEffect(state.latestPopup) {
        view.announceForAccessibility("Pop-up blocked from ${state.latestPopup.openerHost}")
    }

    var dragOffsetX by remember(state.latestPopup) { mutableFloatStateOf(0f) }
    var dragOffsetY by remember(state.latestPopup) { mutableFloatStateOf(0f) }
    val density = LocalDensity.current
    val dismissThresholdPx = with(density) { 96.dp.toPx() }

    var isInteracting by remember(state.latestPopup) { mutableStateOf(false) }
    val progressAnimatable = remember(state.latestPopup) { Animatable(1f) }

    LaunchedEffect(state.latestPopup, isInteracting, visible) {
        if (!visible) return@LaunchedEffect
        if (isInteracting) {
            progressAnimatable.stop()
        } else {
            val startFraction = progressAnimatable.value
            val durationMs = (startFraction * state.totalTimeoutMs).toLong()
            if (durationMs > 0L) {
                val result = progressAnimatable.animateTo(
                    targetValue = 0f,
                    animationSpec = tween(
                        durationMillis = durationMs.toInt(),
                        easing = LinearEasing
                    )
                )
                if (result.endReason == AnimationEndReason.Finished && visible) {
                    onTimeout()
                }
            } else if (visible) {
                onTimeout()
            }
        }
    }

    AnimatedVisibility(
        visible = visible,
        enter = slideInVertically { it } + fadeIn(),
        exit = slideOutVertically { it } + fadeOut(),
        modifier = modifier
    ) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            tonalElevation = 6.dp,
            shadowElevation = 8.dp,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp)
                .offset { IntOffset(dragOffsetX.roundToInt(), dragOffsetY.roundToInt()) }
                .semantics {
                    liveRegion = LiveRegionMode.Polite
                }
                .pointerInput(Unit) {
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false)
                        isInteracting = true
                        do {
                            val event = awaitPointerEvent(PointerEventPass.Initial)
                        } while (event.changes.any { it.pressed })
                        isInteracting = false
                    }
                }
                .pointerInput(Unit) {
                    detectDragGestures(
                        onDragStart = { isInteracting = true },
                        onDragEnd = {
                            isInteracting = false
                            if (abs(dragOffsetX) > dismissThresholdPx || dragOffsetY > dismissThresholdPx) {
                                onDismiss()
                            } else {
                                dragOffsetX = 0f
                                dragOffsetY = 0f
                            }
                        },
                        onDragCancel = {
                            isInteracting = false
                            dragOffsetX = 0f
                            dragOffsetY = 0f
                        },
                        onDrag = { change, dragAmount ->
                            change.consume()
                            dragOffsetX += dragAmount.x
                            dragOffsetY = (dragOffsetY + dragAmount.y).coerceAtLeast(0f)
                        }
                    )
                }
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 16.dp, top = 12.dp, end = 8.dp, bottom = 4.dp),
                    verticalAlignment = Alignment.Top
                ) {
                    Icon(
                        imageVector = Icons.Default.Security,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier
                            .padding(top = 2.dp, end = 12.dp)
                            .size(24.dp)
                    )

                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = popupBlockedTitle(state.blockedCount),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface
                        )

                        Spacer(modifier = Modifier.height(2.dp))

                        val destination = remember(state.latestPopup.popupUrl) {
                            formatPopupDestinationParts(state.latestPopup.popupUrl)
                        }
                        if (destination.isGenericScheme) {
                            Text(
                                text = destination.fullText,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        } else {
                            Text(
                                text = buildAnnotatedString {
                                    withStyle(
                                        SpanStyle(
                                            fontWeight = FontWeight.Medium,
                                            color = MaterialTheme.colorScheme.onSurface
                                        )
                                    ) {
                                        append(destination.host)
                                    }
                                    withStyle(
                                        SpanStyle(
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    ) {
                                        append(destination.path)
                                    }
                                },
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }

                        Text(
                            text = "from ${state.latestPopup.openerHost}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    IconButton(
                        onClick = onDismiss,
                        modifier = Modifier.size(36.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Dismiss",
                            modifier = Modifier.size(18.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val alwaysText = remember(state.latestPopup.openerHost) {
                        formatAlwaysAllowLabel(state.latestPopup.openerHost)
                    }
                    TextButton(
                        onClick = onAlwaysAllow,
                        contentPadding = PaddingValues(horizontal = 8.dp)
                    ) {
                        Text(
                            text = alwaysText,
                            style = MaterialTheme.typography.labelMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.semantics {
                                contentDescription = "Always allow pop-ups from ${state.latestPopup.openerHost}"
                            }
                        )
                    }
                    Spacer(modifier = Modifier.width(4.dp))
                    Button(
                        onClick = onOpen,
                        contentPadding = PaddingValues(horizontal = 14.dp),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text(
                            text = "Open",
                            style = MaterialTheme.typography.labelMedium,
                            modifier = Modifier.semantics {
                                contentDescription = "Open pop-up"
                            }
                        )
                    }
                }

                LinearProgressIndicator(
                    progress = { progressAnimatable.value },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(3.dp),
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.surfaceContainerHigh
                )
            }
        }
    }
}
