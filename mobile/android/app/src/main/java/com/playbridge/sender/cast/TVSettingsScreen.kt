package com.playbridge.sender.cast

import android.content.Context
import org.koin.compose.koinInject
import org.koin.androidx.compose.koinViewModel
import com.playbridge.sender.connection.ConnectionViewModel
import com.playbridge.sender.data.settings.SettingsRepository
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
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TVSettingsScreen(
    onBack: () -> Unit,
    tvIp: String? = null,
    tvPort: Int? = null
) {
    val context = LocalContext.current
    val settingsRepository: SettingsRepository = koinInject()
    val prefs = remember { context.getSharedPreferences("browser_prefs", Context.MODE_PRIVATE) }
    val scope = rememberCoroutineScope()
    val isTvAvailable = tvIp != null && tvPort != null

    // Options reflect what the connected/last-paired TV reported it supports. Capabilities are
    // persisted on the saved device, so the real list survives disconnects (see
    // TvCapabilityOptions). Before any TV is paired, only "TV Default" is offered.
    val connectionViewModel: ConnectionViewModel = koinViewModel()
    val tvDevice by connectionViewModel.tvDevice.collectAsState(initial = null)

    val playerMode by settingsRepository.tvPlayerMode.collectAsState(initial = "tv")
    var playerExpanded by remember { mutableStateOf(false) }
    val playerOptions = TvCapabilityOptions.playerOptions(tvDevice)

    var browserMode by remember {
        mutableStateOf(prefs.getString("tv_browser_mode", "tv") ?: "tv")
    }
    var browserExpanded by remember { mutableStateOf(false) }
    val browserOptions = TvCapabilityOptions.browserOptions(tvDevice)

    var isDownloading by remember { mutableStateOf(false) }
    var isClearing by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("TV") },
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
            Text("Defaults", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)

            // TV Video Player
            Box(modifier = Modifier.fillMaxWidth()) {
                OutlinedTextField(
                    readOnly = true,
                    value = playerOptions.find { it.first == playerMode }?.second ?: "TV Default",
                    onValueChange = {},
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
                                playerExpanded = false
                                scope.launch { settingsRepository.setTvPlayerMode(optionId) }
                            }
                        )
                    }
                }
            }

            // TV Browser Engine
            Box(modifier = Modifier.fillMaxWidth()) {
                OutlinedTextField(
                    readOnly = true,
                    value = browserOptions.find { it.first == browserMode }?.second ?: "TV Default",
                    onValueChange = {},
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

            Spacer(modifier = Modifier.height(8.dp))

            // Auto-switch to Remote
            val autoSwitchToRemote by settingsRepository.autoSwitchToRemote.collectAsState(initial = true)
            ListItem(
                headlineContent = { Text("Auto Switch to Remote") },
                supportingContent = { Text("Automatically open remote control after casting content") },
                trailingContent = {
                    Switch(
                        checked = autoSwitchToRemote,
                        onCheckedChange = {
                            scope.launch { settingsRepository.setAutoSwitchToRemote(it) }
                        }
                    )
                },
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                modifier = Modifier.padding(horizontal = 0.dp)
            )

            Spacer(modifier = Modifier.height(12.dp))

            Text("Diagnostics", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)

            if (!isTvAvailable) {
                Text(
                    "Connect to a TV to download or clear logs.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

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

            val response = client.newCall(
                Request.Builder().url("http://$tvIp:$tvPort/logs").get().build()
            ).execute()

            if (response.isSuccessful) {
                val body = response.body?.string() ?: "Empty log"
                val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
                val fileName = "playbridge_tv_logs_$timestamp.txt"
                val file = File(
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                    fileName
                )
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

            val response = client.newCall(
                Request.Builder().url("http://$tvIp:$tvPort/logs").delete().build()
            ).execute()

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
