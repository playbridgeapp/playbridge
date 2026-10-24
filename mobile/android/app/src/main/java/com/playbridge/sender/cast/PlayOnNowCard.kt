package com.playbridge.sender.cast

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cast
import androidx.compose.material.icons.filled.Laptop
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
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
        connection is WebSocketClient.ConnectionState.Connected -> "Connected · PlayBridge"
        nativeStatus == "TV remote only" -> "TV remote only"
        connection is WebSocketClient.ConnectionState.Connecting ||
            connection is WebSocketClient.ConnectionState.Retrying -> "Connecting…"
        else -> "Couldn’t connect"
    }
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Icon(when {
                    external -> Icons.Default.Cast
                    device?.isDesktopReceiver == true -> Icons.Default.Laptop
                    else -> Icons.Default.Tv
                }, null, Modifier.size(28.dp))
                Column(Modifier.weight(1f)) {
                    Text(device?.playOnName ?: "TV", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (status.startsWith("Connected") || status.startsWith("Casting")) {
                            Spacer(Modifier.padding(end = 6.dp).size(7.dp).background(Color(0xFF46D483), CircleShape))
                        }
                        Text(status, style = MaterialTheme.typography.bodyMedium)
                    }
                    if (!streamRoute.isNullOrBlank()) Text(streamRoute, style = MaterialTheme.typography.bodySmall)
                }
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                if (onRemote != null) TextButton(onClick = onRemote) { Text("Remote") }
                if (onConfigure != null) TextButton(onClick = onConfigure) { Text("Configure TV") }
                if (!external && !nativeSelected && connection is WebSocketClient.ConnectionState.Connected) {
                    TextButton(onClick = onCastHere) { Text("Cast here") }
                }
                if (!external && nativeStatus == "TV remote only") {
                    TextButton(onClick = onOpenPlayBridge) { Text("Open PlayBridge on ${device?.playOnName ?: "TV"}") }
                }
                if (onShareDiagnostics != null) TextButton(onClick = onShareDiagnostics) { Text("Share diagnostics") }
                TextButton(onClick = onDisconnect) { Text("Disconnect", color = MaterialTheme.colorScheme.error) }
            }
        }
    }
}
