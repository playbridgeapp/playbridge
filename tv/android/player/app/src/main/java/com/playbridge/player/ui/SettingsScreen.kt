package com.playbridge.player.ui

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.*
import android.app.Activity
import android.widget.Toast
import com.playbridge.player.server.ServerService
import com.playbridge.player.ui.theme.AppTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.ui.graphics.vector.ImageVector

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun SettingsScreen(
    onThemeChanged: (AppTheme) -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val prefs = remember { context.getSharedPreferences("browser_prefs", Context.MODE_PRIVATE) }
    val scope = rememberCoroutineScope()

    var selectedCategory by remember { mutableStateOf(SettingsCategory.PLAYER) }

    // Settings States
    var playerMode by remember { mutableStateOf(prefs.getString("player_mode", "phone") ?: "phone") }
    var customIp by remember { mutableStateOf(prefs.getString("preferred_ip", "") ?: "") }
    var showIpDialog by remember { mutableStateOf(false) }
    var allowInsecureWs by remember { mutableStateOf(prefs.getBoolean("allow_insecure_ws", false)) }
    var hideSoftKeyboard by remember { mutableStateOf(prefs.getBoolean("hide_soft_keyboard", false)) }
    var frameRateMatching by remember { mutableStateOf(prefs.getBoolean("frame_rate_matching", false)) }
    var tunneledPlayback by remember { mutableStateOf(prefs.getBoolean("tunneled_playback", false)) }
    var loudnessEnhancer by remember { mutableStateOf(prefs.getBoolean("loudness_enhancer", false)) }
    var isRestarting by remember { mutableStateOf(false) }
    var themeStr by remember { mutableStateOf(prefs.getString("app_theme", "DARK") ?: "DARK") }

    // GeckoView Plugin states
    var isGeckoInstalled by remember { mutableStateOf(false) }
    var showGeckoDialog by remember { mutableStateOf(false) }

    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                isGeckoInstalled = try {
                    context.packageManager.getPackageInfo("com.playbridge.browser", 0)
                    true
                } catch (e: Exception) {
                    false
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    fun restartServer() {
        isRestarting = true
        scope.launch {
            ServerService.stop(context)
            delay(1500)
            ServerService.start(context)
            delay(1200)
            isRestarting = false
        }
    }

    Row(modifier = Modifier.fillMaxSize()) {
        // --- Sidebar ---
        Column(
            modifier = Modifier
                .fillMaxHeight()
                .width(280.dp)
                .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.5f))
                .padding(top = 48.dp, start = 24.dp, end = 16.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp)
        ) {
            Text(
                text = "Settings",
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(start = 16.dp, bottom = 12.dp)
            )

            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(SettingsCategory.entries) { category ->
                    val isSelected = selectedCategory == category
                    ListItem(
                        selected = isSelected,
                        onClick = { selectedCategory = category },
                        leadingContent = {
                            Icon(category.icon, contentDescription = null, modifier = Modifier.size(20.dp))
                        },
                        headlineContent = {
                            Text(category.label)
                        },
                        colors = ListItemDefaults.colors(
                            selectedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                            contentColor = MaterialTheme.colorScheme.onSurface,
                            selectedContentColor = MaterialTheme.colorScheme.primary
                        ),
                        scale = ListItemDefaults.scale(focusedScale = 1.05f)
                    )
                }
                
            }
        }

        // --- Content Area ---
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(start = 48.dp, top = 48.dp, end = 64.dp, bottom = 48.dp)
        ) {
            LazyColumn(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(24.dp),
                contentPadding = PaddingValues(bottom = 64.dp)
            ) {
                item {
                    Text(
                        text = selectedCategory.label,
                        style = MaterialTheme.typography.displaySmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                }

                when (selectedCategory) {
                    SettingsCategory.PLAYER -> {
                        item {
                            SettingDropdownItem(
                                label = "Video Player",
                                description = "Choose preferred player engine.",
                                options = listOf(
                                    "phone" to "Use Phone Setting",
                                    "exo" to "ExoPlayer",
                                    "mpv" to "MPV",
                                ),
                                selected = playerMode,
                                onSelected = { mode ->
                                    playerMode = mode
                                    prefs.edit().putString("player_mode", mode).apply()
                                }
                            )
                        }
                        item {
                            SettingToggleItem(
                                label = "Frame Rate Matching",
                                description = "Automatically match refresh rate (API 30+).",
                                checked = frameRateMatching,
                                onCheckedChange = {
                                    frameRateMatching = it
                                    prefs.edit().putBoolean("frame_rate_matching", it).apply()
                                }
                            )
                        }
                        item {
                            SettingToggleItem(
                                label = "Loudness Enhancer",
                                description = "Boost quiet dialogue and normalize peaks.",
                                checked = loudnessEnhancer,
                                onCheckedChange = {
                                    loudnessEnhancer = it
                                    prefs.edit().putBoolean("loudness_enhancer", it).apply()
                                }
                            )
                        }
                        item {
                            SettingToggleItem(
                                label = "Tunneled Playback",
                                description = "Hardware-level sync. Fixes 4K DV issues.",
                                checked = tunneledPlayback,
                                onCheckedChange = {
                                    tunneledPlayback = it
                                    prefs.edit().putBoolean("tunneled_playback", it).apply()
                                }
                            )
                        }
                    }

                    SettingsCategory.BROWSER -> {
                        item {
                            SettingToggleItem(
                                label = "Hide on-screen keyboard",
                                description = "Don't pop up the TV keyboard when a web field is focused — type from the phone's keyboard instead (built-in WebView browser).",
                                checked = hideSoftKeyboard,
                                onCheckedChange = {
                                    hideSoftKeyboard = it
                                    prefs.edit().putBoolean("hide_soft_keyboard", it).apply()
                                }
                            )
                        }
                    }

                    SettingsCategory.NETWORK -> {
                        item {
                            SettingClickableItem(
                                label = "Custom Network IP",
                                description = if (customIp.isEmpty() || customIp == "auto") "Automatic" else customIp,
                                onClick = { showIpDialog = true }
                            )
                        }
                        item {
                            SettingToggleItem(
                                label = "Allow insecure connections (ws)",
                                description = "Off = encrypted wss only. Enable for older senders that can't use TLS (e.g. the browser extension).",
                                checked = allowInsecureWs,
                                onCheckedChange = {
                                    allowInsecureWs = it
                                    prefs.edit().putBoolean("allow_insecure_ws", it).apply()
                                    restartServer()
                                }
                            )
                        }
                        item {
                            SettingClickableItem(
                                label = if (isRestarting) "Restarting..." else "Restart Server",
                                description = "Restart WebSocket server and Discovery.",
                                enabled = !isRestarting,
                                onClick = { restartServer() }
                            )
                        }
                    }


                    SettingsCategory.APPEARANCE -> {
                        item {
                            SettingDropdownItem(
                                label = "Theme",
                                description = "App color scheme.",
                                options = AppTheme.entries.map { it.name to it.label },
                                selected = themeStr,
                                onSelected = { selected ->
                                    themeStr = selected
                                    prefs.edit().putString("app_theme", selected).apply()
                                    AppTheme.entries.find { it.name == selected }?.let {
                                        onThemeChanged(it)
                                    }
                                }
                            )
                        }
                    }

                    SettingsCategory.ABOUT -> {
                        item {
                            val versionName = try {
                                context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "unknown"
                            } catch (_: Exception) { "unknown" }
                            
                            SettingClickableItem(
                                label = "Version",
                                description = versionName,
                                enabled = false,
                                onClick = {}
                            )
                        }
                        item {
                            SettingClickableItem(
                                label = "GeckoView Engine (Optional Plugin)",
                                description = if (isGeckoInstalled) "Installed" else "Not Installed (click to learn more/sideload)",
                                onClick = {
                                    if (isGeckoInstalled) {
                                        Toast.makeText(context, "GeckoView Plugin is ready to use", Toast.LENGTH_SHORT).show()
                                    } else {
                                        showGeckoDialog = true
                                    }
                                }
                            )
                        }
                    }
                }
            }
        }
    }

    // IP Entry Dialog (Remains mostly same but styled)
    if (showIpDialog) {
        var tempIp by remember { mutableStateOf(if (customIp == "auto") "" else customIp) }
        androidx.compose.ui.window.Dialog(onDismissRequest = { showIpDialog = false }) {
            Surface(
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier.width(400.dp)
            ) {
                Column(
                    modifier = Modifier.padding(32.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Text("Enter Custom IP", style = MaterialTheme.typography.headlineSmall)
                    Text(
                        "Useful for emulator port forwarding. Leave empty for Automatic.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    // Text Field Integration
                    androidx.compose.foundation.text.BasicTextField(
                        value = tempIp,
                        onValueChange = { tempIp = it },
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.shapes.small)
                            .padding(16.dp),
                        textStyle = androidx.compose.ui.text.TextStyle(color = MaterialTheme.colorScheme.onSurface, fontSize = 16.sp)
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Button(onClick = { showIpDialog = false }, modifier = Modifier.padding(end = 8.dp)) {
                            Text("Cancel")
                        }
                        Button(onClick = {
                            val finalIp = tempIp.trim().ifEmpty { "auto" }
                            customIp = finalIp
                            prefs.edit().putString("preferred_ip", finalIp).apply()
                            showIpDialog = false
                            restartServer()
                        }) {
                            Text("Save & Restart")
                        }
                    }
                }
            }
        }
    }

    if (showGeckoDialog) {
        androidx.compose.ui.window.Dialog(onDismissRequest = { showGeckoDialog = false }) {
            Surface(
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier.width(450.dp)
            ) {
                Column(
                    modifier = Modifier.padding(32.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Text("Install GeckoView Plugin", style = MaterialTheme.typography.headlineSmall)
                    Text(
                        "GeckoView is an optional browser engine plugin that provides better compatibility with some streaming sites.\n\n" +
                        "You can download it from:\n" +
                        com.playbridge.player.BuildConfig.GECKO_PLUGIN_DOWNLOAD_URL,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Button(onClick = { showGeckoDialog = false }, modifier = Modifier.padding(end = 8.dp)) {
                            Text("Cancel")
                        }
                        Button(onClick = {
                            showGeckoDialog = false
                            try {
                                val intent = Intent(context, com.playbridge.player.browser.BrowserActivity::class.java).apply {
                                    action = "com.playbridge.player.ACTION_BROWSER_INTERNAL"
                                    putExtra("extra_url", com.playbridge.player.BuildConfig.GECKO_PLUGIN_DOWNLOAD_URL)
                                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                }
                                context.startActivity(intent)
                            } catch (e: Exception) {
                                Toast.makeText(context, "Failed to launch browser: ${e.message}", Toast.LENGTH_SHORT).show()
                            }
                        }) {
                            Text("Open Download Page")
                        }
                    }
                }
            }
        }
    }
}

enum class SettingsCategory(val label: String, val icon: ImageVector) {
    PLAYER("Player", Icons.Default.PlayArrow),
    BROWSER("Browser", Icons.Default.Search),
    NETWORK("Network", Icons.Default.Settings),
    APPEARANCE("Appearance", Icons.Default.Add),
    ABOUT("About", Icons.Default.Info)
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun SettingToggleItem(
    label: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    ListItem(
        selected = false,
        onClick = { onCheckedChange(!checked) },
        headlineContent = { Text(label) },
        supportingContent = { Text(description) },
        trailingContent = {
            Switch(checked = checked, onCheckedChange = null)
        },
        scale = ListItemDefaults.scale(focusedScale = 1.02f)
    )
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun SettingClickableItem(
    label: String,
    description: String,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    ListItem(
        selected = false,
        onClick = onClick,
        enabled = enabled,
        headlineContent = { Text(label) },
        supportingContent = { Text(description) },
        scale = ListItemDefaults.scale(focusedScale = 1.02f)
    )
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun SettingDropdownItem(
    label: String,
    description: String,
    options: List<Pair<String, String>>,
    selected: String,
    onSelected: (String) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val selectedLabel = options.find { it.first == selected }?.second ?: selected
    val focusRequester = remember { FocusRequester() }

    Column {
        ListItem(
            selected = false,
            onClick = { expanded = !expanded },
            headlineContent = { Text(label) },
            supportingContent = { Text(description) },
            trailingContent = {
                Text(selectedLabel, color = MaterialTheme.colorScheme.primary)
            },
            scale = ListItemDefaults.scale(focusedScale = 1.02f),
            modifier = Modifier.focusRequester(focusRequester)
        )

        if (expanded) {
            Surface(
                modifier = Modifier
                    .padding(horizontal = 16.dp, vertical = 8.dp)
                    .fillMaxWidth(),
                shape = MaterialTheme.shapes.small,
                colors = SurfaceDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Column {
                    options.forEach { (value, displayLabel) ->
                        ListItem(
                            selected = value == selected,
                            onClick = {
                                onSelected(value)
                                expanded = false
                                focusRequester.requestFocus()
                            },
                            headlineContent = { Text(displayLabel) },
                            trailingContent = {
                                if (value == selected) {
                                    Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(16.dp))
                                }
                            }
                        )
                    }
                }
            }
        }
    }
}
