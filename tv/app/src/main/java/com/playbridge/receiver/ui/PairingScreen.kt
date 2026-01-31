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
    connectionState: WebSocketServer.ConnectionState = WebSocketServer.ConnectionState.Stopped,
    modifier: Modifier = Modifier
) {
    var qrBitmap by remember { mutableStateOf<Bitmap?>(null) }
    val isConnected = connectionState is WebSocketServer.ConnectionState.Connected
    
    LaunchedEffect(ip, port, token) {
        qrBitmap = QRGenerator.generate(
            ip = ip,
            port = port,
            token = token,
            deviceName = deviceName,
            size = 400
        )
    }
    
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
                        is WebSocketServer.ConnectionState.Connected -> "Phone Connected!"
                        is WebSocketServer.ConnectionState.Running -> "Waiting for connection..."
                        is WebSocketServer.ConnectionState.Starting -> "Starting server..."
                        is WebSocketServer.ConnectionState.Error -> "Error: ${connectionState.message}"
                        is WebSocketServer.ConnectionState.Stopped -> "Server stopped"
                    },
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (isConnected) Color(0xFF00FF88) else Color.White
                )
            }
            
            Text(
                text = if (isConnected) "Connected!" else "Scan to Connect",
                style = MaterialTheme.typography.headlineLarge,
                color = if (isConnected) Color(0xFF00FF88) else Color.White
            )
            
            // QR Code (dim when connected)
            qrBitmap?.let { bitmap ->
                Box(
                    modifier = Modifier
                        .size(300.dp)
                        .background(Color.White)
                        .padding(8.dp)
                ) {
                    Image(
                        bitmap = bitmap.asImageBitmap(),
                        contentDescription = "QR Code for pairing",
                        modifier = Modifier
                            .fillMaxSize()
                            .then(if (isConnected) Modifier.background(Color.White.copy(alpha = 0.7f)) else Modifier),
                        alpha = if (isConnected) 0.3f else 1f
                    )
                }
            } ?: Box(
                modifier = Modifier
                    .size(300.dp)
                    .background(Color.Gray),
                contentAlignment = Alignment.Center
            ) {
                Text("Generating...", color = Color.White)
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
                    fontSize = 18.sp
                )
                
                Spacer(modifier = Modifier.height(16.dp))
                
                Text(
                    text = if (isConnected) {
                        "Phone is connected!\nPress BACK to return home"
                    } else {
                        "Open PlayBridge on your phone\nand scan this code"
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (isConnected) Color(0xFF00FF88) else Color.Gray,
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}

