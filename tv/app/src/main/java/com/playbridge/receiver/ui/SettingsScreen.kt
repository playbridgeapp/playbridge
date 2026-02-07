package com.playbridge.receiver.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Button
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0F0F23))
            .padding(32.dp)
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(24.dp)
        ) {
            Text(
                text = "Settings",
                style = MaterialTheme.typography.displaySmall,
                color = Color.White
            )
            
            Text(
                text = "Version: 1.0.0",
                style = MaterialTheme.typography.bodyMedium,
                color = Color.Gray
            )
            
            // Placeholder for future settings
            Text(
                text = "More settings coming soon...",
                style = MaterialTheme.typography.bodyLarge,
                color = Color.White
            )
            
            Button(onClick = onBack) {
                Text("Back")
            }
        }
    }
}
