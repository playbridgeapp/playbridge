package com.playbridge.receiver.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.playbridge.receiver.pairing.QRGenerator
import com.playbridge.receiver.server.WebSocketServer


@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun PairingScreen(
    ip: String,
    port: Int,
    token: String,
    deviceName: String,
    deviceId: String,
    connectionState: WebSocketServer.ConnectionState = WebSocketServer.ConnectionState.Stopped,
    connectedCount: Int = 0,
    modifier: Modifier = Modifier
) {
    // Use the first 4 chars of the token as the PIN for display
    // In a real app, we'd generate a separate 4-digit PIN and map it to the token
    // For this implementation, we'll assume the token passed in IS the PIN (or we derive it)
    // To make it simple, let's just display the first 4 characters of the token as the PIN
    // The server will need to handle this mapping or we just use a 4-char token.
    // For now, let's display the token directly (assuming it's short) or a substring.
    val pinDisplay = token.take(4).uppercase()

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xFF1A1A2E)),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(32.dp)
        ) {
            // Status indicator
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
                                is WebSocketServer.ConnectionState.Error -> Color(0xFFFF4444)
                                else -> Color(0xFF666666)
                            }
                        )
                )
                
                Text(
                    text = when (connectionState) {
                        is WebSocketServer.ConnectionState.Connected -> {
                            if (connectedCount == 1) "1 device connected"
                            else "$connectedCount devices connected"
                        }
                        is WebSocketServer.ConnectionState.Running -> "Ready to Connect"
                        is WebSocketServer.ConnectionState.Starting -> "Starting server..."
                        is WebSocketServer.ConnectionState.Error -> "Error: ${connectionState.message}"
                        is WebSocketServer.ConnectionState.Stopped -> "Server stopped"
                    },
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (connectionState is WebSocketServer.ConnectionState.Connected) Color(0xFF00FF88) else Color.White
                )
            }
            
            // Always show PIN for pairing additional devices
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Text(
                    text = "Enter this PIN on your phone",
                    style = MaterialTheme.typography.headlineSmall,
                    color = Color.Gray
                )
                
                // PIN Display
                Text(
                    text = pinDisplay,
                    style = MaterialTheme.typography.displayLarge,
                    fontSize = 120.sp,
                    color = Color.White,
                    letterSpacing = 24.sp,
                    fontWeight = androidx.compose.ui.text.font.FontWeight.Bold
                )
            }
            
            // Connection info
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = deviceName,
                    style = MaterialTheme.typography.titleLarge,
                    color = Color.White
                )
                
                Text(
                    text = "$ip:$port",
                    style = MaterialTheme.typography.bodyLarge,
                    color = Color(0xFF00D9FF),
                    fontSize = 24.sp
                )
                
                if (deviceId.isNotEmpty()) {
                    Text(
                        text = "ID: $deviceId",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.Gray,
                        fontSize = 12.sp,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }

                Spacer(modifier = Modifier.height(32.dp))
                
                Text(
                    text = "Or connect manually using the IP address above",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.Gray.copy(alpha = 0.7f)
                )
            }
        }
    }
}

