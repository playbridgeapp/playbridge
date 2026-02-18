package com.playbridge.receiver.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
    val context = androidx.compose.ui.platform.LocalContext.current
    val prefs = remember { context.getSharedPreferences("browser_prefs", android.content.Context.MODE_PRIVATE) }
    var useGecko by remember { mutableStateOf(prefs.getBoolean("use_gecko", false)) }

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

            // Browser Engine Selection
            Text(
                text = "Browser Engine",
                style = MaterialTheme.typography.titleMedium,
                color = Color.LightGray
            )

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Button(
                    onClick = {
                        useGecko = false
                        prefs.edit().putBoolean("use_gecko", false).apply()
                    },
                    colors = androidx.tv.material3.ButtonDefaults.colors(
                        containerColor = if (!useGecko) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                        contentColor = if (!useGecko) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                ) {
                    Text("System WebView")
                }

                Button(
                    onClick = {
                        useGecko = true
                        prefs.edit().putBoolean("use_gecko", true).apply()
                    },
                    colors = androidx.tv.material3.ButtonDefaults.colors(
                        containerColor = if (useGecko) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                        contentColor = if (useGecko) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                ) {
                    Text("GeckoView (Mozilla)")
                }
            }
            
            Text(
                text = if (useGecko) "Using Mozilla GeckoView engine. Better tracking protection and modern web standards." 
                       else "Using Android System WebView. Standard system component.",
                style = MaterialTheme.typography.bodySmall,
                color = Color.Gray
            )
            
            Spacer(modifier = Modifier.height(24.dp))

            Text(
                text = "Version: 1.0.0",
                style = MaterialTheme.typography.bodyMedium,
                color = Color.Gray
            )
            
            Button(onClick = onBack) {
                Text("Back")
            }
        }
    }
}
