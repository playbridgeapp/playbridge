package com.playbridge.sender.cast

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Cast
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * Now-playing surface for a DLNA cast — shown on the Remote route when a DLNA
 * renderer is the active target (the native [RemoteControlScreen] is too WS-coupled
 * to reuse). Driven by the polled [PlaybackStatus]; controls call back into the VM.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DlnaNowPlayingScreen(
    deviceName: String,
    status: PlaybackStatus?,
    onPlay: () -> Unit,
    onPause: () -> Unit,
    onSeekTo: (Long) -> Unit,
    onStop: () -> Unit,
    onBack: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Casting via DLNA") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { pad ->
        Column(
            modifier = Modifier.padding(pad).fillMaxSize().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Icon(
                imageVector = Icons.Default.Cast,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(72.dp),
            )
            Spacer(Modifier.height(16.dp))
            Text(deviceName, style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(32.dp))

            val durationMs = status?.durationMs ?: 0L
            val positionMs = status?.positionMs ?: 0L
            var scrub by remember { mutableStateOf<Float?>(null) }
            val progress = scrub ?: if (durationMs > 0) {
                (positionMs.toFloat() / durationMs).coerceIn(0f, 1f)
            } else {
                0f
            }
            Slider(
                value = progress,
                onValueChange = { scrub = it },
                onValueChangeFinished = {
                    onSeekTo(((scrub ?: 0f) * durationMs).toLong())
                    scrub = null
                },
                enabled = durationMs > 0,
            )
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(clock(positionMs), style = MaterialTheme.typography.bodySmall)
                Text(clock(durationMs), style = MaterialTheme.typography.bodySmall)
            }

            Spacer(Modifier.height(24.dp))
            val playing = status?.state == PlaybackState.PLAYING
            FilledIconButton(
                onClick = { if (playing) onPause() else onPlay() },
                modifier = Modifier.size(72.dp),
            ) {
                Icon(
                    imageVector = if (playing) Icons.Default.Pause else Icons.Default.PlayArrow,
                    contentDescription = if (playing) "Pause" else "Play",
                    modifier = Modifier.size(40.dp),
                )
            }

            Spacer(Modifier.height(32.dp))
            Button(
                onClick = onStop,
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
            ) {
                Text("Stop")
            }
        }
    }
}

private fun clock(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    val h = s / 3600
    val m = (s % 3600) / 60
    val sec = s % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%d:%02d".format(m, sec)
}
