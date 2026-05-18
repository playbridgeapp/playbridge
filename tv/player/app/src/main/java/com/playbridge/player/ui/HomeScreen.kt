package com.playbridge.player.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.playbridge.player.server.WebSocketServer

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun HomeScreen(
    connectionState: WebSocketServer.ConnectionState,
    serverIp: String?,
    serverPort: Int?,
    connectedCount: Int,
    deviceId: String,
    deviceName: String,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(24.dp)
        ) {
            // App Title
            Text(
                text = "PlayBridge",
                style = MaterialTheme.typography.displayMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            
            Spacer(modifier = Modifier.height(16.dp))
            
            // Connection Status Indicator
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(16.dp)
                        .clip(CircleShape)
                        .background(
                            when (connectionState) {
                                is WebSocketServer.ConnectionState.Connected -> Color(0xFF00FF88)
                                is WebSocketServer.ConnectionState.Running -> Color(0xFFFFAA00)
                                is WebSocketServer.ConnectionState.Starting -> Color(0xFFFFAA00)
                                is WebSocketServer.ConnectionState.Error -> Color(0xFFFF4444)
                                is WebSocketServer.ConnectionState.Stopped -> MaterialTheme.colorScheme.onSurfaceVariant
                            }
                        )
                )
                
                Text(
                    text = when (connectionState) {
                        is WebSocketServer.ConnectionState.Connected -> "Phone Connected"
                        is WebSocketServer.ConnectionState.Running -> "Waiting for connection..."
                        is WebSocketServer.ConnectionState.Starting -> "Starting server..."
                        is WebSocketServer.ConnectionState.Error -> "Error: ${connectionState.message}"
                        is WebSocketServer.ConnectionState.Stopped -> "Server stopped"
                    },
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
            
            // Connected device count
            if (connectedCount > 0) {
                Text(
                    text = if (connectedCount == 1) "1 device connected" else "$connectedCount devices connected",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                    fontSize = 14.sp
                )
            }
            
            // Server Info
            if (serverIp != null && serverPort != null) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = "Server: $serverIp:$serverPort",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color(0xFF00D9FF),
                        fontSize = 16.sp
                    )
                    if (deviceId.isNotEmpty()) {
                        Text(
                            text = "ID: $deviceId",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 12.sp,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                    }
                }
            }
            
            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = when (connectionState) {
                    is WebSocketServer.ConnectionState.Connected ->
                        "Ready to receive videos!\nUse your phone to send content."
                    else ->
                        "Open PlayBridge on your phone and connect to this device."
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
        }
    }
}
