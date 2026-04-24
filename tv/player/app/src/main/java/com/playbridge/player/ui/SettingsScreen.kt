package com.playbridge.player.ui

import android.content.Context
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

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val prefs = remember { context.getSharedPreferences("browser_prefs", Context.MODE_PRIVATE) }
    val scope = rememberCoroutineScope()

    // Player setting: "phone" (default), "internal", "external"
    var playerMode by remember {
        mutableStateOf(prefs.getString("player_mode", "phone") ?: "phone")
    }
    // Custom IP setting for emulator port forwarding
    var customIp by remember {
        mutableStateOf(prefs.getString("preferred_ip", "") ?: "")
    }
    var showIpDialog by remember { mutableStateOf(false) }
    var frameRateMatching by remember {
        mutableStateOf(prefs.getBoolean("frame_rate_matching", false))
    }
    var tunneledPlayback by remember {
        mutableStateOf(prefs.getBoolean("tunneled_playback", false))
    }
    var loudnessEnhancer by remember {
        mutableStateOf(prefs.getBoolean("loudness_enhancer", false))
    }

    // Tracks whether the server restart cycle is in progress
    var isRestarting by remember { mutableStateOf(false) }

    // Theme preference
    var themeStr by remember {
        mutableStateOf(prefs.getString("app_theme", "DARK") ?: "DARK")
    }

    // Restarts the WebSocket server + NSD advertisement so any changed settings (e.g. custom IP)
    // take effect immediately without having to kill and relaunch the whole app.
    fun restartServer() {
        isRestarting = true
        scope.launch {
            ServerService.stop(context)
            // 1 500 ms gap: stopService() triggers onDestroy() which calls nsdManager.unregisterService()
            // asynchronously.  If the new service registers before the mDNS daemon finishes tearing
            // down the old record it returns FAILURE_INTERNAL_ERROR (code 0) and the TV becomes
            // permanently undiscoverable.  600 ms was too tight on slower devices; 1 500 ms is safe.
            delay(1500)
            ServerService.start(context)
            delay(1200)           // Give it time to come back up before re-enabling the button
            isRestarting = false
        }
    }

    // Migrate old boolean prefs to new mode strings on first load
    LaunchedEffect(Unit) {
        if (!prefs.contains("player_mode")) {
            val oldExternal = prefs.getBoolean("use_external_player", false)
            val mode = if (oldExternal) "external" else "phone"
            prefs.edit().putString("player_mode", mode).apply()
            playerMode = mode
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(48.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth(0.6f)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(28.dp)
        ) {
            Text(
                text = "Settings",
                style = MaterialTheme.typography.displaySmall,
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.Bold
            )

            // ── Video Player ──
            SettingsDropdown(
                label = "Video Player",
                description = when (playerMode) {
                    "phone" -> "Uses whatever the phone app requests (internal or external)."
                    "internal" -> "Always plays with the built-in ExoPlayer. Supports subtitles and remote seek."
                    "internal_vlc" -> "Always plays with the built-in LibVLC player. Useful for formats ExoPlayer struggles with."
                    "internal_mpv" -> "Always plays with the built-in MPV player. Best format compatibility."
                    "external" -> "Always passes videos to other installed apps like VLC or MX Player."
                    "external_mpv" -> "Always passes videos directly to the MPV player. Supports headers and multiple subtitles."
                    else -> ""
                },
                options = listOf(
                    "phone" to "Use Phone Setting",
                    "internal" to "Internal (ExoPlayer)",
                    "internal_vlc" to "Internal (LibVLC)",
                    "internal_mpv" to "Internal (MPV)",
                    "external_mpv" to "External (MPV)",
                    "external" to "External Player",
                ),
                selected = playerMode,
                onSelected = { mode ->
                    playerMode = mode
                    prefs.edit()
                        .putString("player_mode", mode)
                        // Keep legacy pref in sync for ServerService
                        .putBoolean("use_external_player", mode == "external")
                        .apply()
                }
            )

            // ── Frame Rate Matching ──
            SettingsDropdown(
                label = "Frame Rate Matching",
                description = "Automatically matches the TV's refresh rate to the video's frame rate (e.g. 24Hz for movies). May cause a brief screen blackout (HDMI handshake). Only supported on Android 11+.",
                options = listOf(
                    "false" to "Disabled",
                    "true" to "Enabled (API 30+)"
                ),
                selected = frameRateMatching.toString(),
                onSelected = { enabled ->
                    val e = enabled.toBoolean()
                    frameRateMatching = e
                    prefs.edit().putBoolean("frame_rate_matching", e).apply()
                }
            )

            // ── Loudness Enhancer ──
            SettingsDropdown(
                label = "Loudness Enhancer (Night Mode)",
                description = "Boosts quiet dialogue and normalizes audio peaks for a better low-volume experience. Prevents 'quiet voices, loud explosions'. (+15-20dB Boost)",
                options = listOf(
                    "false" to "Disabled",
                    "true" to "Enabled"
                ),
                selected = loudnessEnhancer.toString(),
                onSelected = { enabled ->
                    val e = enabled.toBoolean()
                    loudnessEnhancer = e
                    prefs.edit().putBoolean("loudness_enhancer", e).apply()
                }
            )

            // ── Tunneled Playback ──
            SettingsDropdown(
                label = "Tunneled Playback (Fix 4K DV)",
                description = "Enables hardware-level video/audio synchronization. Recommended for resolving 'dark video' or color issues with Dolby Vision and 4K content. Can cause issues on some cheaper boxes.",
                options = listOf(
                    "false" to "Disabled",
                    "true" to "Enabled (Recommended for 4K)"
                ),
                selected = tunneledPlayback.toString(),
                onSelected = { enabled ->
                    val e = enabled.toBoolean()
                    tunneledPlayback = e
                    prefs.edit().putBoolean("tunneled_playback", e).apply()
                }
            )

            // ── Custom Network IP Address ──
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "Custom Network IP Address",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                var isFocused by remember { mutableStateOf(false) }

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(44.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .border(
                            if (isFocused) 1.5.dp else 1.dp,
                            if (isFocused) Color(0xFF00D9FF).copy(alpha = 0.5f) else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f),
                            RoundedCornerShape(10.dp)
                        )
                        .onKeyEvent { event ->
                            if (event.type == KeyEventType.KeyDown && event.key == Key.DirectionCenter) {
                                showIpDialog = true; true
                            } else false
                        }
                        .onFocusChanged { isFocused = it.isFocused }
                        .focusable()
                        .padding(horizontal = 16.dp),
                    contentAlignment = Alignment.CenterStart
                ) {
                    Text(
                        text = if (customIp.isEmpty() || customIp == "auto") "Automatic (Recommended)" else customIp,
                        color = if (isFocused) Color(0xFF00D9FF) else MaterialTheme.colorScheme.onSurface,
                        fontSize = 14.sp
                    )
                }

                Text(
                    text = "Set a custom IP to advertise. Useful for emulator port forwarding (e.g. adb forward). Leave empty for Automatic. Saving triggers an automatic server restart.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            // ── Restart Server ──
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "Server",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Button(
                    onClick = { restartServer() },
                    enabled = !isRestarting
                ) {
                    Text(if (isRestarting) "Restarting…" else "Restart Server")
                }

                Text(
                    text = "Stops and restarts the WebSocket server and network discovery (NSD). Use this after changing the Custom IP, or if phones can't connect.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // ── Appearance ──
            SettingsDropdown(
                label = "Theme",
                description = when (themeStr) {
                    "DARK"   -> "Deep indigo — easy on the eyes in dark environments."
                    "AMOLED" -> "True black — saves battery on OLED screens."
                    "LIGHT"  -> "Soft violet-white — for bright environments."
                    else     -> ""
                },
                options = AppTheme.entries.map { it.name to it.label },
                selected = themeStr,
                onSelected = { selected ->
                    themeStr = selected
                    prefs.edit().putString("app_theme", selected).apply()
                    (context as? Activity)?.recreate()
                }
            )

            Spacer(modifier = Modifier.height(8.dp))

            // ── Stream Cache ──
            var cacheHours by remember {
                mutableStateOf(prefs.getInt("stream_cache_hours", 0))
            }

            SettingsDropdown(
                label = "Stream Cache Duration",
                description = "How long to cache resolved stream links. Useful for skipping resolution when re-watching or navigating back/forward.",
                options = listOf(
                    "0" to "Disabled",
                    "1" to "1 Hour (Recommended)",
                    "6" to "6 Hours",
                    "12" to "12 Hours",
                    "24" to "24 Hours",
                    "48" to "48 Hours"
                ),
                selected = cacheHours.toString(),
                onSelected = { hours ->
                    val h = hours.toInt()
                    cacheHours = h
                    prefs.edit().putInt("stream_cache_hours", h).apply()
                    com.playbridge.shared.stremio.StremioClient.updateCacheDuration(h)
                }
            )

            Button(
                onClick = {
                    com.playbridge.shared.stremio.StremioClient.clearAllCache()
                    Toast.makeText(context, "Stream cache cleared", Toast.LENGTH_SHORT).show()
                }
            ) {
                Text("Clear Stream Cache")
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Version
            val versionName = remember {
                try {
                    context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "unknown"
                } catch (_: Exception) { "unknown" }
            }

            Text(
                text = "Version: $versionName",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Button(onClick = onBack) {
                Text("Back")
            }
        }
    }

    // IP Entry Dialog
    if (showIpDialog) {
        var tempIp by remember { mutableStateOf(if (customIp == "auto") "" else customIp) }

        androidx.compose.foundation.layout.Box(
            modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface.copy(alpha = 0.8f)),
            contentAlignment = Alignment.Center
        ) {
            Column(
                modifier = Modifier
                    .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(16.dp))
                    .padding(32.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Text("Enter Custom IP", style = MaterialTheme.typography.titleLarge)
                Text(
                    "Useful for emulator port forwarding (e.g. adb forward). Leave empty for Automatic. Saving will restart the server so the new IP takes effect immediately.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                // We use basic compose text field, but we need to ensure the standard foundation is available
                androidx.compose.foundation.text.BasicTextField(
                    value = tempIp,
                    onValueChange = { tempIp = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.onSurface, RoundedCornerShape(8.dp))
                        .padding(16.dp),
                    textStyle = androidx.compose.ui.text.TextStyle(color = MaterialTheme.colorScheme.surface, fontSize = 16.sp)
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
                        // Restart immediately so NSD re-advertises with the new IP
                        restartServer()
                    }) {
                        Text("Save & Restart")
                    }
                }
            }
        }
    }

    androidx.activity.compose.BackHandler { onBack() }
}

/**
 * A D-pad–friendly dropdown: shows the current selection as a row,
 * and expands inline (like a listbox) when the user presses Enter/Select.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun SettingsDropdown(
    label: String,
    description: String,
    options: List<Pair<String, String>>,  // value to display-label
    selected: String,
    onSelected: (String) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        if (!expanded) {
            // Collapsed: show current selection as a button
            var isFocused by remember { mutableStateOf(false) }
            val selectedLabel = options.find { it.first == selected }?.second ?: selected

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(44.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .border(
                        if (isFocused) 1.5.dp else 1.dp,
                        if (isFocused) Color(0xFF00D9FF).copy(alpha = 0.5f) else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f),
                        RoundedCornerShape(10.dp)
                    )
                    .onKeyEvent { event ->
                        if (event.type == KeyEventType.KeyDown && event.key == Key.DirectionCenter) {
                            expanded = true; true
                        } else false
                    }
                    .onFocusChanged { isFocused = it.isFocused }
                    .focusable()
                    .padding(horizontal = 16.dp),
                contentAlignment = Alignment.CenterStart
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = selectedLabel,
                        color = if (isFocused) Color(0xFF00D9FF) else MaterialTheme.colorScheme.onSurface,
                        fontSize = 14.sp
                    )
                    Text(
                        text = "▼",
                        color = if (isFocused) Color(0xFF00D9FF) else MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 12.sp
                    )
                }
            }
        } else {
            // Expanded: show all options as a list
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .border(1.dp, Color(0xFF00D9FF).copy(alpha = 0.3f), RoundedCornerShape(10.dp)),
                verticalArrangement = Arrangement.spacedBy(0.dp)
            ) {
                options.forEachIndexed { index, (value, displayLabel) ->
                    val isSelected = value == selected
                    DropdownOption(
                        label = displayLabel,
                        isSelected = isSelected,
                        isFirst = index == 0,
                        isLast = index == options.lastIndex,
                        onClick = {
                            onSelected(value)
                            expanded = false
                        },
                        onCollapse = { expanded = false },
                        autoFocus = isSelected
                    )
                }
            }
        }

        Text(
            text = description,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun DropdownOption(
    label: String,
    isSelected: Boolean,
    isFirst: Boolean,
    isLast: Boolean,
    onClick: () -> Unit,
    onCollapse: () -> Unit,
    autoFocus: Boolean
) {
    var isFocused by remember { mutableStateOf(false) }
    val focusRequester = remember { FocusRequester() }

    if (autoFocus) {
        LaunchedEffect(Unit) {
            focusRequester.requestFocus()
        }
    }

    val shape = when {
        isFirst && isLast -> RoundedCornerShape(10.dp)
        isFirst -> RoundedCornerShape(topStart = 10.dp, topEnd = 10.dp)
        isLast -> RoundedCornerShape(bottomStart = 10.dp, bottomEnd = 10.dp)
        else -> RoundedCornerShape(0.dp)
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(42.dp)
            .clip(shape)
            .background(
                when {
                    isFocused -> Color(0xFF00D9FF).copy(alpha = 0.15f)
                    isSelected -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.04f)
                    else -> Color.Transparent
                }
            )
            .then(
                if (autoFocus) Modifier.focusRequester(focusRequester)
                else Modifier
            )
            .onKeyEvent { event ->
                if (event.type == KeyEventType.KeyDown) {
                    when (event.key) {
                        Key.DirectionCenter -> { onClick(); true }
                        Key.Back -> { onCollapse(); true }
                        else -> false
                    }
                } else false
            }
            .onFocusChanged { isFocused = it.isFocused }
            .focusable()
            .padding(horizontal = 16.dp),
        contentAlignment = Alignment.CenterStart
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = label,
                color = when {
                    isFocused -> Color(0xFF00D9FF)
                    isSelected -> MaterialTheme.colorScheme.onSurface
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                },
                fontSize = 14.sp,
                fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal
            )

            if (isSelected) {
                Text(
                    text = "✓",
                    color = Color(0xFF00D9FF),
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}
