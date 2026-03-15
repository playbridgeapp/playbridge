package com.playbridge.receiver.ui

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
import androidx.tv.material3.Button
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.playbridge.receiver.server.WebSocketServer

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun HomeScreen(
    connectionState: WebSocketServer.ConnectionState,
    serverIp: String?,
    serverPort: Int?,
    connectedCount: Int,
    deviceId: String,
    onShowPairing: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xFF0F0F23)),
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
                color = Color.White
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
                                is WebSocketServer.ConnectionState.Stopped -> Color(0xFF666666)
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
                    color = Color.White
                )
            }
            
            // Connected device count
            if (connectedCount > 0) {
                Text(
                    text = if (connectedCount == 1) "1 device connected" else "$connectedCount devices connected",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color(0xFF00FF88),
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
                            color = Color(0xFF888888),
                            fontSize = 12.sp,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                    }
                }
            }
            
            Spacer(modifier = Modifier.height(32.dp))
            
            // Show QR Button
            Button(
                onClick = onShowPairing
            ) {
                Text(
                    text = "Show Pairing Code",
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp)
                )
            }
            
            Spacer(modifier = Modifier.height(48.dp))
            
            // Instructions
            Text(
                text = when (connectionState) {
                    is WebSocketServer.ConnectionState.Connected -> 
                        "Ready to receive videos!\nUse your phone to send content."
                    else -> 
                        "Press the button above to display the pairing QR code.\nScan it with the PlayBridge phone app."
                },
                style = MaterialTheme.typography.bodyMedium,
                color = Color.Gray,
                textAlign = TextAlign.Center
            )
        }
    }
}
