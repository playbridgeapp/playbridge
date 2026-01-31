package com.playbridge.sender.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.playbridge.sender.connection.WebSocketClient
import com.playbridge.sender.model.TvDevice

@Composable
fun HomeScreen(
    connectionState: WebSocketClient.ConnectionState,
    tvDevice: TvDevice?,
    onSendPing: () -> Unit,
    onDisconnect: () -> Unit,
    onRescan: () -> Unit,
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
            verticalArrangement = Arrangement.spacedBy(24.dp),
            modifier = Modifier.padding(32.dp)
        ) {
            // App Title
            Text(
                text = "PlayBridge",
                style = MaterialTheme.typography.displaySmall,
                color = MaterialTheme.colorScheme.primary
            )
            
            Spacer(modifier = Modifier.height(16.dp))
            
            // Connection Status
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
                                is WebSocketClient.ConnectionState.Connected -> Color(0xFF00FF88)
                                is WebSocketClient.ConnectionState.Connecting -> Color(0xFFFFAA00)
                                is WebSocketClient.ConnectionState.Error -> Color(0xFFFF4444)
                                is WebSocketClient.ConnectionState.Disconnected -> Color(0xFF666666)
                            }
                        )
                )
                
                Text(
                    text = when (connectionState) {
                        is WebSocketClient.ConnectionState.Connected -> "Connected to ${connectionState.serverName}"
                        is WebSocketClient.ConnectionState.Connecting -> "Connecting..."
                        is WebSocketClient.ConnectionState.Error -> "Error: ${connectionState.message}"
                        is WebSocketClient.ConnectionState.Disconnected -> "Disconnected"
                    },
                    style = MaterialTheme.typography.titleMedium
                )
            }
            
            // TV Info
            if (tvDevice != null) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = tvDevice.name,
                            style = MaterialTheme.typography.titleLarge
                        )
                        Text(
                            text = "${tvDevice.ip}:${tvDevice.port}",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }
            
            Spacer(modifier = Modifier.height(16.dp))
            
            // Actions
            if (connectionState is WebSocketClient.ConnectionState.Connected) {
                Button(
                    onClick = onSendPing,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Send Test Ping")
                }
                
                OutlinedButton(
                    onClick = onDisconnect,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Disconnect")
                }
            } else if (connectionState is WebSocketClient.ConnectionState.Disconnected || 
                       connectionState is WebSocketClient.ConnectionState.Error) {
                Button(
                    onClick = onRescan,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Scan QR Code")
                }
            }
            
            Spacer(modifier = Modifier.weight(1f))
            
            // Instructions
            Text(
                text = when (connectionState) {
                    is WebSocketClient.ConnectionState.Connected -> 
                        "Ready to send videos to your TV!\nBrowse and detect video links."
                    is WebSocketClient.ConnectionState.Connecting ->
                        "Establishing connection..."
                    is WebSocketClient.ConnectionState.Error ->
                        "Connection failed. Check that the TV app is running."
                    is WebSocketClient.ConnectionState.Disconnected ->
                        "Connect to your TV to start casting videos."
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
        }
    }
}
