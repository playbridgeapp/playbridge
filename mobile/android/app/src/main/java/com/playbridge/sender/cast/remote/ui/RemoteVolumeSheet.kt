package com.playbridge.sender.cast.remote.ui

import android.os.SystemClock
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeDown
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.playbridge.sender.cast.remote.LatestVolumeThrottle
import com.playbridge.sender.cast.remote.RemoteDialogVolumeKeys
import com.playbridge.sender.cast.remote.RemoteFeedback
import com.playbridge.sender.cast.remote.RemoteVolumePill
import com.playbridge.sender.cast.remote.rememberRemoteHaptics
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** "🔊 45" / "🔇 Muted" / "🔊 —" between the − and + buttons; tap opens [RemoteVolumeSheet]. */
@Composable
internal fun RemoteVolumePillButton(pill: RemoteVolumePill, title: String, onClick: () -> Unit) {
    val description = title + when {
        pill.muted -> ", muted"
        pill.percent != null -> " ${pill.percent} of 100"
        else -> ""
    } + ", double-tap to adjust"
    Surface(
        onClick = onClick,
        shape = CircleShape,
        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.14f),
        modifier = Modifier.testTag("remote.volumePill").semantics { contentDescription = description },
    ) {
        Row(
            Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Icon(
                if (pill.muted) Icons.Default.VolumeOff else Icons.AutoMirrored.Filled.VolumeUp,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
            Text(pill.text, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
        }
    }
}

/**
 * Exact-level volume: a large slider sending the latest value (≤ 8/s plus the final value),
 * an optional mute toggle, and the phone's volume keys stepping the receiver while open.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun RemoteVolumeSheet(
    title: String,
    pill: RemoteVolumePill,
    canMute: Boolean,
    onSet: (Int) -> Unit,
    onStep: (up: Boolean) -> Unit,
    onMute: () -> Unit,
    onDismiss: () -> Unit,
) {
    val haptics = rememberRemoteHaptics()
    val scope = rememberCoroutineScope()
    val throttle = remember { LatestVolumeThrottle() }
    val latestSet by rememberUpdatedState(onSet)
    var sender by remember { mutableStateOf<Job?>(null) }
    var dragging by remember { mutableStateOf(false) }
    // After release, keep the dragged value until the receiver catches up (or 1.5 s), so a
    // report that predates the final send doesn't snap the thumb back.
    var pinned by remember { mutableStateOf(false) }
    var dragValue by remember { mutableFloatStateOf((pill.percent ?: 50).toFloat()) }
    var touched by remember { mutableStateOf(pill.percent != null) }
    val reported = pill.percent

    LaunchedEffect(reported, dragging, pinned) {
        if (!dragging && !pinned && reported != null) dragValue = reported.toFloat()
    }
    LaunchedEffect(pinned, reported) {
        if (!pinned) return@LaunchedEffect
        if (reported == dragValue.roundToInt()) pinned = false else { delay(1_500); pinned = false }
    }

    fun flush() {
        if (sender?.isActive == true) return
        sender = scope.launch {
            while (throttle.hasPending) {
                val now = SystemClock.uptimeMillis()
                val value = throttle.poll(now)
                if (value != null) latestSet(value) else delay(throttle.waitMs(now).coerceAtLeast(1))
            }
        }
    }

    val shown = if (dragging || pinned || reported == null) dragValue else reported.toFloat()

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        // Routes the phone's volume keys from this dialog window to the receiver.
        RemoteDialogVolumeKeys()
        Box(Modifier.testTag("remote.volumeSheet")) {
            Column(
                Modifier.fillMaxWidth().padding(horizontal = 24.dp).navigationBarsPadding().padding(bottom = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(16.dp))
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(
                        if (pill.muted) Icons.Default.VolumeOff else Icons.AutoMirrored.Filled.VolumeUp,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                    )
                    Text(
                        when {
                            !pill.exact -> pill.percent?.toString() ?: ""
                            touched -> "${shown.roundToInt()}"
                            else -> "—"
                        },
                        style = MaterialTheme.typography.displaySmall,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.testTag("remote.volumeValue"),
                    )
                    if (pill.muted) Text("Muted", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.error)
                }
                Spacer(Modifier.height(8.dp))
                if (pill.exact) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { pinned = false; onStep(false); haptics.perform(RemoteFeedback.VOLUME) }, modifier = Modifier.testTag("remote.volumeDown")) {
                    Icon(Icons.AutoMirrored.Filled.VolumeDown, contentDescription = "$title down")
                }
                Slider(
                    value = shown,
                    onValueChange = { value ->
                        dragging = true
                        touched = true
                        val before = dragValue.roundToInt()
                        dragValue = value
                        val now = value.roundToInt()
                        if (now / 5 != before / 5) haptics.perform(RemoteFeedback.VOLUME)
                        throttle.offer(now)
                        flush()
                    },
                    onValueChangeFinished = {
                        dragging = false
                        pinned = true
                        throttle.offer(dragValue.roundToInt())
                        flush()
                    },
                    valueRange = 0f..100f,
                    modifier = Modifier.weight(1f).testTag("remote.volumeSlider").semantics { contentDescription = title },
                )
                IconButton(onClick = { pinned = false; onStep(true); haptics.perform(RemoteFeedback.VOLUME) }, modifier = Modifier.testTag("remote.volumeUp")) {
                    Icon(Icons.AutoMirrored.Filled.VolumeUp, contentDescription = "$title up")
                }
                }
                } else {
                    // Step-only route (e.g. whole-TV control): no exact target to slide to.
                    Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                        FilledTonalIconButton(
                            onClick = { onStep(false); haptics.perform(RemoteFeedback.VOLUME) },
                            modifier = Modifier.size(64.dp).testTag("remote.volumeDown"),
                        ) { Icon(Icons.AutoMirrored.Filled.VolumeDown, contentDescription = "$title down", modifier = Modifier.size(28.dp)) }
                        FilledTonalIconButton(
                            onClick = { onStep(true); haptics.perform(RemoteFeedback.VOLUME) },
                            modifier = Modifier.size(64.dp).testTag("remote.volumeUp"),
                        ) { Icon(Icons.AutoMirrored.Filled.VolumeUp, contentDescription = "$title up", modifier = Modifier.size(28.dp)) }
                    }
                }
                if (canMute) {
                    Spacer(Modifier.height(8.dp))
                    FilledTonalButton(onClick = onMute, modifier = Modifier.testTag("remote.volumeMute")) {
                        Icon(if (pill.muted) Icons.AutoMirrored.Filled.VolumeUp else Icons.Default.VolumeOff, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.size(8.dp))
                        Text(if (pill.muted) "Unmute" else "Mute")
                    }
                }
            }
        }
    }
}
