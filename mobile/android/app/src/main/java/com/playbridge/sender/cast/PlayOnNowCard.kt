package com.playbridge.sender.cast

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cast
import androidx.compose.material.icons.filled.Laptop
import androidx.compose.material.icons.filled.SettingsRemote
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.playbridge.sender.connection.WebSocketClient
import com.playbridge.sender.model.TvDevice

@Composable
internal fun PlayOnNowCard(
    device: TvDevice?,
    connection: WebSocketClient.ConnectionState,
    external: Boolean,
    castState: CastSessionState,
    mediaTitle: String?,
    nativeStatus: String?,
    nativeSelected: Boolean,
    streamRoute: String?,
    onRemote: (() -> Unit)?,
    onConfigure: (() -> Unit)?,
    onCastHere: () -> Unit,
    onOpenPlayBridge: () -> Unit,
    onDisconnect: () -> Unit,
    onShareDiagnostics: (() -> Unit)?,
) {
    val status = if (external) when (castState.phase) {
        SessionPhase.CONNECTING -> "Connecting…"
        SessionPhase.FAILED -> "Couldn’t connect"
        SessionPhase.PLAYING -> mediaTitle?.let { "Casting $it" } ?: "Casting"
        else -> "Connected · ${device?.resolvedProtocol?.displayName.orEmpty()}"
    } else when {
        // Connected but This phone is the target: the receiver is standing by, not playing.
        connection is WebSocketClient.ConnectionState.Connected && !nativeSelected -> "Connected · Standing by"
        connection is WebSocketClient.ConnectionState.Connected -> "Connected · PlayBridge"
        nativeStatus == "TV remote only" -> "TV remote only"
        connection is WebSocketClient.ConnectionState.Connecting ||
            connection is WebSocketClient.ConnectionState.Retrying -> "Connecting…"
        else -> "Couldn’t connect"
    }
    val live = (status.startsWith("Connected") || status.startsWith("Casting")) && (external || nativeSelected)
    val tint = if (live) PlayOnGreen else MaterialTheme.colorScheme.onSurfaceVariant
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = tint.copy(alpha = 0.12f),
            contentColor = MaterialTheme.colorScheme.onSurface),
        border = BorderStroke(1.dp, tint.copy(alpha = 0.35f)),
    ) {
        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(Modifier.size(44.dp).background(tint.copy(alpha = 0.18f), RoundedCornerShape(12.dp)),
                    contentAlignment = Alignment.Center) {
                    Icon(when {
                        external -> Icons.Default.Cast
                        device?.isDesktopReceiver == true -> Icons.Default.Laptop
                        else -> Icons.Default.Tv
                    }, null, Modifier.size(24.dp), tint = tint)
                }
                Column(Modifier.weight(1f)) {
                    Text(device?.playOnName ?: "TV", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold,
                        maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (live) Spacer(Modifier.padding(end = 6.dp).size(7.dp).background(PlayOnGreen, CircleShape))
                        Text(status, style = MaterialTheme.typography.bodyMedium, color = if (live) PlayOnGreen else Color.Unspecified,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    if (!streamRoute.isNullOrBlank()) Text(streamRoute, style = MaterialTheme.typography.bodySmall)
                }
            }
            if (!external && !nativeSelected && connection is WebSocketClient.ConnectionState.Connected) {
                FilledTonalButton(onClick = onCastHere, modifier = Modifier.fillMaxWidth()) {
                    Text("Play on ${device?.playOnName ?: "TV"}", maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                FlowRow(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    // Remote sits on the action row so long names and large text keep the full width above.
                    if (onRemote != null) OutlinedButton(onClick = onRemote, border = BorderStroke(1.dp, tint.copy(alpha = 0.5f)),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.onSurface),
                        contentPadding = PaddingValues(horizontal = 14.dp)) {
                        Icon(Icons.Default.SettingsRemote, null, Modifier.size(18.dp))
                        Text("Remote", Modifier.padding(start = 6.dp), maxLines = 1)
                    }
                    if (onConfigure != null) FilledTonalButton(onClick = onConfigure,
                        colors = ButtonDefaults.filledTonalButtonColors(containerColor = tint.copy(alpha = 0.2f),
                            contentColor = MaterialTheme.colorScheme.onSurface)) {
                        Icon(Icons.Default.Tune, null, Modifier.size(18.dp))
                        Text("Configure TV", Modifier.padding(start = 6.dp))
                    }
                    if (!external && nativeStatus == "TV remote only") {
                        TextButton(onClick = onOpenPlayBridge) { Text("Open PlayBridge on ${device?.playOnName ?: "TV"}") }
                    }
                    if (onShareDiagnostics != null) TextButton(onClick = onShareDiagnostics) { Text("Share diagnostics") }
                }
                TextButton(onClick = onDisconnect) { Text("Disconnect", color = MaterialTheme.colorScheme.error) }
            }
        }
    }
}

internal val PlayOnGreen = Color(0xFF46D483)
