package com.playbridge.player.ui

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
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

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val prefs = remember { context.getSharedPreferences("browser_prefs", Context.MODE_PRIVATE) }

    // Player setting: "phone" (default), "internal", "external"
    var playerMode by remember {
        mutableStateOf(prefs.getString("player_mode", "phone") ?: "phone")
    }
    // Custom IP setting for emulator port forwarding
    var customIp by remember {
        mutableStateOf(prefs.getString("preferred_ip", "") ?: "")
    }
    var showIpDialog by remember { mutableStateOf(false) }

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
            .background(Color(0xFF0F0F23))
            .padding(48.dp)
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(0.6f),
            verticalArrangement = Arrangement.spacedBy(28.dp)
        ) {
            Text(
                text = "Settings",
                style = MaterialTheme.typography.displaySmall,
                color = Color.White,
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
                options = buildList {
                    add("phone" to "Use Phone Setting")
                    add("internal" to "Internal (ExoPlayer)")
                    add("internal_vlc" to "Internal (LibVLC)")
                    if (com.playbridge.player.BuildConfig.INCLUDE_MPV) {
                        add("internal_mpv" to "Internal (MPV)")
                        add("external_mpv" to "External (MPV)")
                    }
                    add("external" to "External Player")
                },
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

            // ── Custom Network IP Address ──
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "Custom Network IP Address",
                    style = MaterialTheme.typography.titleMedium,
                    color = Color.LightGray
                )

                var isFocused by remember { mutableStateOf(false) }

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(44.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(if (isFocused) Color(0xFF2A2A4A) else Color(0xFF1E1E38))
                        .border(
                            if (isFocused) 1.5.dp else 1.dp,
                            if (isFocused) Color(0xFF00D9FF).copy(alpha = 0.5f) else Color.White.copy(alpha = 0.08f),
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
                        color = if (isFocused) Color(0xFF00D9FF) else Color.White,
                        fontSize = 14.sp
                    )
                }

                Text(
                    text = "Set a custom IP to advertise. Useful for emulator port forwarding (e.g. adb forward). Leave empty for Automatic. (Changes apply on next connection restart)",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.Gray
                )
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
                color = Color.Gray
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
            modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.8f)),
            contentAlignment = Alignment.Center
        ) {
            Column(
                modifier = Modifier
                    .background(Color(0xFF1E1E38), RoundedCornerShape(16.dp))
                    .padding(32.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Text("Enter Custom IP", style = MaterialTheme.typography.titleLarge)
                Text(
                    "Useful for emulator port forwarding (e.g. adb forward). Leave empty for Automatic. (Changes apply on next connection restart)",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.LightGray
                )

                // We use basic compose text field, but we need to ensure the standard foundation is available
                androidx.compose.foundation.text.BasicTextField(
                    value = tempIp,
                    onValueChange = { tempIp = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color.White, RoundedCornerShape(8.dp))
                        .padding(16.dp),
                    textStyle = androidx.compose.ui.text.TextStyle(color = Color.Black, fontSize = 16.sp)
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
                    }) {
                        Text("Save")
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
            color = Color.LightGray
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
                    .background(if (isFocused) Color(0xFF2A2A4A) else Color(0xFF1E1E38))
                    .border(
                        if (isFocused) 1.5.dp else 1.dp,
                        if (isFocused) Color(0xFF00D9FF).copy(alpha = 0.5f) else Color.White.copy(alpha = 0.08f),
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
                        color = if (isFocused) Color(0xFF00D9FF) else Color.White,
                        fontSize = 14.sp
                    )
                    Text(
                        text = "▼",
                        color = if (isFocused) Color(0xFF00D9FF) else Color.Gray,
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
                    .background(Color(0xFF1E1E38))
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
            color = Color.Gray
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
                    isSelected -> Color.White.copy(alpha = 0.04f)
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
                    isSelected -> Color.White
                    else -> Color.LightGray
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
