package com.playbridge.sender.cast

import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MediaflowSettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("browser_settings", Context.MODE_PRIVATE) }

    var proxyUrl by remember {
        mutableStateOf(prefs.getString(MediaflowProxy.PREFS_KEY_URL, "") ?: "")
    }
    var proxyPassword by remember {
        mutableStateOf(prefs.getString(MediaflowProxy.PREFS_KEY_PASSWORD, "") ?: "")
    }
    var passwordVisible by remember { mutableStateOf(false) }
    var autoSelect by remember {
        mutableStateOf(prefs.getBoolean(MediaflowProxy.PREFS_KEY_AUTO_SELECT, true))
    }

    // Persist on every change, exactly like DebridSettingsScreen
    LaunchedEffect(proxyUrl, proxyPassword, autoSelect) {
        prefs.edit()
            .putString(MediaflowProxy.PREFS_KEY_URL, proxyUrl.trim())
            .putString(MediaflowProxy.PREFS_KEY_PASSWORD, proxyPassword)
            .putBoolean(MediaflowProxy.PREFS_KEY_AUTO_SELECT, autoSelect)
            .apply()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Proxy (mediaflow-proxy)") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = "Self-hosted mediaflow-proxy rewrites video URLs on the phone before " +
                       "sending them to the TV. The proxy chip in the cast sheet lets you choose " +
                       "between direct passthrough, HLS proxy, and on-the-fly transcoding.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            OutlinedTextField(
                value = proxyUrl,
                onValueChange = { proxyUrl = it },
                label = { Text("Proxy base URL") },
                placeholder = { Text("http://192.168.1.x:8888") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                modifier = Modifier.fillMaxWidth()
            )

            OutlinedTextField(
                value = proxyPassword,
                onValueChange = { proxyPassword = it },
                label = { Text("API password") },
                singleLine = true,
                visualTransformation = if (passwordVisible)
                    VisualTransformation.None else PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                trailingIcon = {
                    IconButton(onClick = { passwordVisible = !passwordVisible }) {
                        Icon(
                            if (passwordVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                            contentDescription = if (passwordVisible) "Hide password" else "Show password"
                        )
                    }
                },
                modifier = Modifier.fillMaxWidth()
            )

            HorizontalDivider()

            // Auto-select row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Auto-select proxy mode",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text(
                        text = "When the cast sheet opens, automatically pre-select the best " +
                               "proxy chip based on the stream type: HLS → HLS Proxy, " +
                               "MP4/direct → Proxy Direct. You can still override manually.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(modifier = Modifier.width(12.dp))
                Switch(
                    checked = autoSelect,
                    onCheckedChange = { autoSelect = it },
                    enabled = proxyUrl.isNotBlank()
                )
            }

            if (proxyUrl.isNotBlank()) {
                Card(colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.secondaryContainer
                )) {
                    Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            "Proxy configured — chip will appear in the cast sheet.",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSecondaryContainer
                        )
                        Text(
                            "Swagger UI: ${proxyUrl.trimEnd('/')}/docs",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSecondaryContainer
                        )
                    }
                }
            }
        }
    }
}
