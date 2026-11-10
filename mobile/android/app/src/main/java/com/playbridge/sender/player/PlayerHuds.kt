package com.playbridge.sender.player

import java.util.Locale
import kotlin.math.abs
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.media3.common.Format
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.delay

/** Glassy vertical HUD card showing an icon, a vertical progress bar and a percentage label (volume / brightness). */
@Composable
internal fun BoxScope.GestureHud(
    icon: @Composable () -> Unit,
    fraction: Float,
    label: String,
    alignment: Alignment
) {
    Surface(
        modifier = Modifier
            .align(alignment)
            .padding(horizontal = 48.dp),
        color = Color.Black.copy(alpha = 0.55f),
        shape = RoundedCornerShape(18.dp)
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            icon()
            Box(
                modifier = Modifier
                    .width(8.dp)
                    .height(140.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(Color.White.copy(alpha = 0.3f))
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .fillMaxHeight(fraction.coerceIn(0f, 1f))
                        .align(Alignment.BottomCenter)
                        .background(Color.White)
                )
            }
            Text(label, color = Color.White, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
        }
    }
}

/** Centered HUD showing the scrub target and signed delta during a horizontal seek drag. */
@Composable
internal fun BoxScope.SeekHud(deltaMs: Long, targetMs: Long, durationMs: Long) {
    Surface(
        modifier = Modifier.align(Alignment.Center),
        color = Color.Black.copy(alpha = 0.55f),
        shape = RoundedCornerShape(18.dp)
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 14.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                "${formatTime(targetMs)} / ${formatTime(durationMs)}",
                color = Color.White,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            val sign = if (deltaMs >= 0) "+" else "-"
            Text(
                "$sign${formatTime(abs(deltaMs))}",
                color = Color.White.copy(alpha = 0.8f),
                style = MaterialTheme.typography.labelMedium
            )
        }
    }
}


@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable
internal fun StatsOverlay(
    player: Player,
    engine: PhonePlayerEngine,
    activeVideoFormat: androidx.media3.common.Format?,
    activeAudioFormat: androidx.media3.common.Format?,
    positionMs: Long,
    bufferedMs: Long,
    speed: Float,
    onClose: () -> Unit
) {
    val streamUrl = remember(player.currentMediaItem) {
        player.currentMediaItem?.localConfiguration?.uri?.toString() ?: "Unknown"
    }

    // Refresh state periodically for counters
    var tick by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(1000)
            tick++
        }
    }

    val counters = (player.unwrapped() as? ExoPlayer)?.videoDecoderCounters
    val rendered = remember(tick) { counters?.renderedOutputBufferCount ?: 0 }
    val dropped = remember(tick) { counters?.droppedBufferCount ?: 0 }
    val skipped = remember(tick) { counters?.skippedOutputBufferCount ?: 0 }

    Surface(
        color = Color.Black.copy(alpha = 0.85f),
        shape = RoundedCornerShape(12.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = 0.2f)),
        modifier = Modifier
            .padding(16.dp)
            .widthIn(max = 420.dp)
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Playback Statistics (MPV style)",
                    style = MaterialTheme.typography.titleSmall,
                    color = Color.Green,
                    fontWeight = FontWeight.Bold
                )
                IconButton(
                    onClick = onClose,
                    modifier = Modifier.size(24.dp)
                ) {
                    Icon(
                        imageVector = androidx.compose.material.icons.Icons.Default.Close,
                        contentDescription = "Close Stats",
                        tint = Color.White.copy(alpha = 0.6f),
                        modifier = Modifier.size(16.dp)
                    )
                }
            }

            Spacer(Modifier.height(2.dp))

            StatItem(label = "Player", value = engine.label)
            StatItem(label = "Source URL", value = streamUrl, isUrl = true)
            StatItem(
                label = "Resolution",
                value = activeVideoFormat?.let { "${it.width}x${it.height}" } ?: "${player.videoSize.width}x${player.videoSize.height}"
            )
            StatItem(label = "Video Codec", value = activeVideoFormat?.codecs ?: activeVideoFormat?.sampleMimeType ?: "Unknown")
            StatItem(label = "Audio Codec", value = activeAudioFormat?.codecs ?: activeAudioFormat?.sampleMimeType ?: "Unknown")
            StatItem(
                label = "Buffer Duration",
                value = "${String.format(Locale.US, "%.1f", (bufferedMs - positionMs).coerceAtLeast(0L) / 1000f)}s (Max: 300.0s)"
            )
            StatItem(label = "Playback Speed", value = "${speed}x")
            if (counters != null) {
                StatItem(
                    label = "Decoder Performance",
                    value = "Rendered: $rendered / Dropped: $dropped / Skipped: $skipped"
                )
            }
        }
    }
}

@Composable
internal fun StatItem(label: String, value: String, isUrl: Boolean = false) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Top
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = Color.White.copy(alpha = 0.6f),
            modifier = Modifier.weight(1f)
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            color = Color.White,
            fontWeight = FontWeight.Medium,
            maxLines = if (isUrl) 1 else 3,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(2f),
            textAlign = androidx.compose.ui.text.style.TextAlign.End
        )
    }
}
