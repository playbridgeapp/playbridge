package com.playbridge.sender.browser

import android.content.Context
import android.os.Environment
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.ArrowDropUp
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit
import com.playbridge.sender.data.debrid.DebridRepository

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onAddonSettings: () -> Unit = {},
    tvIp: String? = null,
    tvPort: Int? = null
) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("browser_prefs", Context.MODE_PRIVATE) }
    var showInbuiltExtensions by remember {
        mutableStateOf(prefs.getBoolean("show_inbuilt_extensions", false))
    }
    val scope = rememberCoroutineScope()
    var isDownloading by remember { mutableStateOf(false) }
    var isClearing by remember { mutableStateOf(false) }
    val isTvAvailable = tvIp != null && tvPort != null

    Scaffold(
        contentWindowInsets = WindowInsets(0.dp),
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                },
                windowInsets = WindowInsets(0.dp)
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Existing setting: Show Inbuilt Extensions
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Show Inbuilt Extensions",
                        style = MaterialTheme.typography.bodyLarge
                    )
                    Text(
                        text = "Display system extensions in the extensions list",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = showInbuiltExtensions,
                    onCheckedChange = { checked ->
                        showInbuiltExtensions = checked
                        prefs.edit().putBoolean("show_inbuilt_extensions", checked).apply()
                    }
                )
            }

            HorizontalDivider()

            // TMDB API Key
            Text(
                text = "Library",
                style = MaterialTheme.typography.titleMedium
            )

            var tmdbApiKey by remember {
                mutableStateOf(prefs.getString("tmdb_api_key", "") ?: "")
            }
            var showApiKey by remember { mutableStateOf(false) }

            OutlinedTextField(
                value = tmdbApiKey,
                onValueChange = { newKey ->
                    tmdbApiKey = newKey
                    prefs.edit().putString("tmdb_api_key", newKey.trim()).apply()
                },
                label = { Text("TMDB API Key") },
                placeholder = { Text("Enter your TMDB API key") },
                supportingText = { Text("Free at themoviedb.org → Settings → API") },
                singleLine = true,
                visualTransformation = if (showApiKey) VisualTransformation.None else PasswordVisualTransformation(),
                trailingIcon = {
                    IconButton(onClick = { showApiKey = !showApiKey }) {
                        Icon(
                            if (showApiKey) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                            contentDescription = if (showApiKey) "Hide key" else "Show key"
                        )
                    }
                },
                modifier = Modifier.fillMaxWidth()
            )

            OutlinedButton(
                onClick = onAddonSettings,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Manage Stremio Addons")
            }

            HorizontalDivider()

            // Debrid Settings
            Text(
                text = "Debrid Services",
                style = MaterialTheme.typography.titleMedium
            )

            var debridProvider by remember {
                mutableStateOf(prefs.getString(DebridRepository.KEY_DEBRID_PROVIDER, DebridRepository.PROVIDER_NONE) ?: DebridRepository.PROVIDER_NONE)
            }
            var debridApiKey by remember {
                mutableStateOf(prefs.getString(DebridRepository.KEY_DEBRID_API_KEY, "") ?: "")
            }
            var debridExpanded by remember { mutableStateOf(false) }
            val debridOptions = listOf(
                DebridRepository.PROVIDER_NONE,
                DebridRepository.PROVIDER_REAL_DEBRID,
                DebridRepository.PROVIDER_ALL_DEBRID,
                DebridRepository.PROVIDER_PREMIUMIZE
            )

            Box(modifier = Modifier.fillMaxWidth()) {
                OutlinedTextField(
                    readOnly = true,
                    value = debridProvider,
                    onValueChange = { },
                    label = { Text("Active Provider") },
                    trailingIcon = {
                        IconButton(onClick = { debridExpanded = !debridExpanded }) {
                            Icon(
                                if (debridExpanded) Icons.Default.ArrowDropUp else Icons.Default.ArrowDropDown,
                                contentDescription = if (debridExpanded) "Collapse" else "Expand"
                            )
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                )
                // Transparent spacer overlay to capture clicks
                Spacer(
                    modifier = Modifier
                        .matchParentSize()
                        .clickable { debridExpanded = !debridExpanded }
                )
                DropdownMenu(
                    expanded = debridExpanded,
                    onDismissRequest = { debridExpanded = false },
                    modifier = Modifier.fillMaxWidth(0.9f)
                ) {
                    debridOptions.forEach { selectionOption ->
                        DropdownMenuItem(
                            text = { Text(selectionOption) },
                            onClick = {
                                debridProvider = selectionOption
                                debridExpanded = false
                                prefs.edit().putString(DebridRepository.KEY_DEBRID_PROVIDER, selectionOption).apply()
                            }
                        )
                    }
                }
            }

            if (debridProvider != DebridRepository.PROVIDER_NONE) {
                var showDebridKey by remember { mutableStateOf(false) }
                OutlinedTextField(
                    value = debridApiKey,
                    onValueChange = { newKey ->
                        debridApiKey = newKey
                        prefs.edit().putString(DebridRepository.KEY_DEBRID_API_KEY, newKey.trim()).apply()
                    },
                    label = { Text("$debridProvider API Key") },
                    placeholder = { Text("Enter API key") },
                    singleLine = true,
                    visualTransformation = if (showDebridKey) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = {
                        IconButton(onClick = { showDebridKey = !showDebridKey }) {
                            Icon(
                                if (showDebridKey) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                contentDescription = if (showDebridKey) "Hide key" else "Show key"
                            )
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                )
            }

            HorizontalDivider()

            // TV Settings Section
            Text(
                text = "TV Defaults",
                style = MaterialTheme.typography.titleMedium
            )

            // Video Player setting
            var playerMode by remember {
                mutableStateOf(prefs.getString("tv_player_mode", "tv") ?: "tv")
            }
            var playerExpanded by remember { mutableStateOf(false) }
            val playerOptions = listOf(
                "tv" to "TV Default",
                "internal" to "Internal (ExoPlayer)",
                "external" to "External Player"
            )

            Box(modifier = Modifier.fillMaxWidth()) {
                OutlinedTextField(
                    readOnly = true,
                    value = playerOptions.find { it.first == playerMode }?.second ?: "TV Default",
                    onValueChange = { },
                    label = { Text("TV Video Player") },
                    trailingIcon = {
                        IconButton(onClick = { playerExpanded = !playerExpanded }) {
                            Icon(
                                if (playerExpanded) Icons.Default.ArrowDropUp else Icons.Default.ArrowDropDown,
                                contentDescription = if (playerExpanded) "Collapse" else "Expand"
                            )
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(
                    modifier = Modifier
                        .matchParentSize()
                        .clickable { playerExpanded = !playerExpanded }
                )
                DropdownMenu(
                    expanded = playerExpanded,
                    onDismissRequest = { playerExpanded = false },
                    modifier = Modifier.fillMaxWidth(0.9f)
                ) {
                    playerOptions.forEach { (optionId, optionName) ->
                        DropdownMenuItem(
                            text = { Text(optionName) },
                            onClick = {
                                playerMode = optionId
                                playerExpanded = false
                                prefs.edit().putString("tv_player_mode", optionId).apply()
                            }
                        )
                    }
                }
            }

            // Browser Engine setting
            var browserMode by remember {
                mutableStateOf(prefs.getString("tv_browser_mode", "tv") ?: "tv")
            }
            var browserExpanded by remember { mutableStateOf(false) }
            val browserOptions = listOf(
                "tv" to "TV Default",
                "webview" to "System WebView",
                "gecko" to "GeckoView (Mozilla)"
            )

            Box(modifier = Modifier.fillMaxWidth()) {
                OutlinedTextField(
                    readOnly = true,
                    value = browserOptions.find { it.first == browserMode }?.second ?: "TV Default",
                    onValueChange = { },
                    label = { Text("TV Browser Engine") },
                    trailingIcon = {
                        IconButton(onClick = { browserExpanded = !browserExpanded }) {
                            Icon(
                                if (browserExpanded) Icons.Default.ArrowDropUp else Icons.Default.ArrowDropDown,
                                contentDescription = if (browserExpanded) "Collapse" else "Expand"
                            )
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(
                    modifier = Modifier
                        .matchParentSize()
                        .clickable { browserExpanded = !browserExpanded }
                )
                DropdownMenu(
                    expanded = browserExpanded,
                    onDismissRequest = { browserExpanded = false },
                    modifier = Modifier.fillMaxWidth(0.9f)
                ) {
                    browserOptions.forEach { (optionId, optionName) ->
                        DropdownMenuItem(
                            text = { Text(optionName) },
                            onClick = {
                                browserMode = optionId
                                browserExpanded = false
                                prefs.edit().putString("tv_browser_mode", optionId).apply()
                            }
                        )
                    }
                }
            }

            HorizontalDivider()

            // TV Logs Section
            Text(
                text = "TV Diagnostics",
                style = MaterialTheme.typography.titleMedium
            )

            if (!isTvAvailable) {
                Text(
                    text = "Connect to a TV to download or clear logs.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // Download TV Logs
            Button(
                onClick = {
                    if (tvIp == null || tvPort == null) return@Button
                    isDownloading = true
                    scope.launch {
                        downloadTvLogs(context, tvIp, tvPort)
                        isDownloading = false
                    }
                },
                enabled = isTvAvailable && !isDownloading,
                modifier = Modifier.fillMaxWidth()
            ) {
                if (isDownloading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                }
                Text(if (isDownloading) "Downloading…" else "Download TV Logs")
            }

            // Clear TV Logs
            OutlinedButton(
                onClick = {
                    if (tvIp == null || tvPort == null) return@OutlinedButton
                    isClearing = true
                    scope.launch {
                        clearTvLogs(context, tvIp, tvPort)
                        isClearing = false
                    }
                },
                enabled = isTvAvailable && !isClearing,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = MaterialTheme.colorScheme.error
                )
            ) {
                Text(if (isClearing) "Clearing…" else "Clear TV Logs")
            }
        }
    }
}

private suspend fun downloadTvLogs(context: Context, tvIp: String, tvPort: Int) {
    withContext(Dispatchers.IO) {
        try {
            val client = OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .build()

            val request = Request.Builder()
                .url("http://$tvIp:$tvPort/logs")
                .get()
                .build()

            val response = client.newCall(request).execute()

            if (response.isSuccessful) {
                val body = response.body?.string() ?: "Empty log"

                // Save to Downloads folder
                val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
                val fileName = "playbridge_tv_logs_$timestamp.txt"
                val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                val file = File(downloadsDir, fileName)
                file.writeText(body)

                withContext(Dispatchers.Main) {
                    Toast.makeText(context, "Logs saved to Downloads/$fileName", Toast.LENGTH_LONG).show()
                }
            } else {
                withContext(Dispatchers.Main) {
                    Toast.makeText(context, "Failed to download logs: ${response.code}", Toast.LENGTH_SHORT).show()
                }
            }
        } catch (e: Exception) {
            withContext(Dispatchers.Main) {
                Toast.makeText(context, "Error: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }
}

private suspend fun clearTvLogs(context: Context, tvIp: String, tvPort: Int) {
    withContext(Dispatchers.IO) {
        try {
            val client = OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .build()

            val request = Request.Builder()
                .url("http://$tvIp:$tvPort/logs")
                .delete()
                .build()

            val response = client.newCall(request).execute()

            withContext(Dispatchers.Main) {
                if (response.isSuccessful) {
                    Toast.makeText(context, "TV logs cleared", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(context, "Failed to clear logs: ${response.code}", Toast.LENGTH_SHORT).show()
                }
            }
        } catch (e: Exception) {
            withContext(Dispatchers.Main) {
                Toast.makeText(context, "Error: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }
}
