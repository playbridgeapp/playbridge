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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.*
import android.app.Activity
import android.widget.Toast
import com.playbridge.player.logging.FileLogger
import com.playbridge.player.server.ServerService
import com.playbridge.player.ui.theme.AppTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.*
import androidx.compose.ui.graphics.vector.ImageVector
import com.playbridge.player.player.PlayerActivity

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
    var hideSoftKeyboard by remember { mutableStateOf(prefs.getBoolean("hide_soft_keyboard", false)) }
    var frameRateMatching by remember { mutableStateOf(prefs.getBoolean("frame_rate_matching", false)) }
    var tunneledPlayback by remember { mutableStateOf(prefs.getBoolean("tunneled_playback", false)) }
    var loudnessEnhancer by remember { mutableStateOf(prefs.getBoolean("loudness_enhancer", false)) }
    var stillWatchingEnabled by remember { mutableStateOf(prefs.getBoolean(PlayerActivity.PREF_STILL_WATCHING_ENABLED, false)) }
    var stillWatchingMinutes by remember {
        mutableStateOf(PlayerActivity.normalizeStillWatchingThreshold(prefs.getInt(PlayerActivity.PREF_STILL_WATCHING_THRESHOLD_MIN, 90)))
    }
    var stillWatchingResponseSeconds by remember {
        mutableStateOf(PlayerActivity.normalizeStillWatchingResponseSeconds(prefs.getInt(PlayerActivity.PREF_STILL_WATCHING_RESPONSE_SEC, 300)))
    }
    var enableHistory by remember { mutableStateOf(prefs.getBoolean("enable_history", true)) }
    var isRestarting by remember { mutableStateOf(false) }
    var showExitDialog by remember { mutableStateOf(false) }
    var themeStr by remember { mutableStateOf(prefs.getString("app_theme", "DARK") ?: "DARK") }

    // GeckoView Plugin states
    var isGeckoInstalled by remember { mutableStateOf(false) }
    var showGeckoDialog by remember { mutableStateOf(false) }

    // Skip segments states
    var skipProvider by remember { mutableStateOf(prefs.getString("skip_segments_provider", "both") ?: "both") }
    var introDbApiKey by remember { mutableStateOf(prefs.getString("introdb_api_key", "") ?: "") }
    var introDbApiUrl by remember { mutableStateOf(prefs.getString("introdb_api_url", "https://api.introdb.app") ?: "https://api.introdb.app") }
    var theIntroDbApiUrl by remember { mutableStateOf(prefs.getString("theintrodb_api_url", "https://api.theintrodb.org") ?: "https://api.theintrodb.org") }
    var autoSkipIntro by remember { mutableStateOf(prefs.getBoolean("auto_skip_intro", false)) }
    var autoSkipRecap by remember { mutableStateOf(prefs.getBoolean("auto_skip_recap", false)) }
    var autoSkipOutro by remember { mutableStateOf(prefs.getBoolean("auto_skip_outro", false)) }
    var showApiKeyDialog by remember { mutableStateOf(false) }
    var showApiUrlDialog by remember { mutableStateOf(false) }
    var showTheIntroDbUrlDialog by remember { mutableStateOf(false) }

    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                isGeckoInstalled = try {
                    context.packageManager.getPackageInfo("com.playbridge.geckoview.plugin", 0)
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

    fun exitApp() {
        scope.launch {
            // Stop the FGS first so the WS server closes cleanly and NSD unregisters.
            ServerService.stop(context)
            delay(300)
            (context as? Activity)?.finishAffinity()
            // Kill the process so nothing (started-sticky service, retained singletons)
            // lingers. BootReceiver will bring the server back on next boot/app launch.
            kotlin.system.exitProcess(0)
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
            // Collected here (composable scope) rather than inside the LazyColumn's
            // LazyListScope below, where @Composable calls like collectAsState aren't allowed.
            val recentLogs by FileLogger.recent.collectAsState()
            var loggingEnabled by remember { mutableStateOf(FileLogger.isEnabled()) }
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
                            SettingToggleItem(
                                label = "Still Watching Check",
                                description = "Pause after extended playback and return to idle if there is no response.",
                                checked = stillWatchingEnabled,
                                onCheckedChange = {
                                    stillWatchingEnabled = it
                                    prefs.edit().putBoolean(PlayerActivity.PREF_STILL_WATCHING_ENABLED, it).apply()
                                }
                            )
                        }
                        if (stillWatchingEnabled) item {
                            SettingDropdownItem(
                                label = "Check After",
                                description = "Active playback time before asking whether to continue.",
                                options = PlayerActivity.STILL_WATCHING_PRESETS.sorted().map { minutes ->
                                    val label = when {
                                        minutes < 60 -> "$minutes minutes"
                                        minutes % 60 == 0 -> "${minutes / 60} ${if (minutes == 60) "hour" else "hours"}"
                                        else -> "${minutes / 60.0} hours"
                                    }
                                    minutes.toString() to label
                                },
                                selected = stillWatchingMinutes.toString(),
                                onSelected = { value ->
                                    value.toIntOrNull()?.takeIf { it in PlayerActivity.STILL_WATCHING_PRESETS }?.let {
                                        stillWatchingMinutes = it
                                        prefs.edit().putInt(PlayerActivity.PREF_STILL_WATCHING_THRESHOLD_MIN, it).apply()
                                    }
                                }
                            )
                        }
                        if (stillWatchingEnabled) item {
                            SettingDropdownItem(
                                label = "Response Time",
                                description = "Time to respond before playback stops.",
                                options = PlayerActivity.STILL_WATCHING_RESPONSE_PRESETS.sorted().map { seconds ->
                                    seconds.toString() to if (seconds < 60) "$seconds seconds" else "${seconds / 60} ${if (seconds == 60) "minute" else "minutes"}"
                                },
                                selected = stillWatchingResponseSeconds.toString(),
                                onSelected = { value ->
                                    value.toIntOrNull()?.takeIf { it in PlayerActivity.STILL_WATCHING_RESPONSE_PRESETS }?.let {
                                        stillWatchingResponseSeconds = it
                                        prefs.edit().putInt(PlayerActivity.PREF_STILL_WATCHING_RESPONSE_SEC, it).apply()
                                    }
                                }
                            )
                        }
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
                                    // An explicit user choice clears any automatic block set
                                    // after a tunneled decoder crash (see ExoPlayerActivity).
                                    prefs.edit()
                                        .putBoolean("tunneled_playback", it)
                                        .remove("tunneling_auto_blocked")
                                        .apply()
                                }
                            )
                        }
                        item {
                            // Escape hatch for the persistent decoder-compatibility flags the
                            // failover ladder sets after fatal decoder errors (see
                            // ExoPlayerActivity): tunneling / async MediaCodec / Dolby Vision.
                            SettingClickableItem(
                                label = "Reset Decoder Compatibility",
                                description = "Clear automatic decoder blocks set after playback errors (tunneling, async codec, Dolby Vision)",
                                onClick = {
                                    prefs.edit()
                                        .remove("tunneling_auto_blocked")
                                        .remove("codec_async_blocked")
                                        .remove("dv_decoders_blocked")
                                        .apply()
                                    android.widget.Toast.makeText(
                                        context,
                                        "Decoder compatibility flags reset",
                                        android.widget.Toast.LENGTH_SHORT
                                    ).show()
                                }
                            )
                        }
                        item {
                            SettingToggleItem(
                                label = "Save Cast History",
                                description = "Keep track of recently played videos and your progress.",
                                checked = enableHistory,
                                onCheckedChange = {
                                    enableHistory = it
                                    prefs.edit().putBoolean("enable_history", it).apply()
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

                    SettingsCategory.INTEGRATIONS -> {
                        item {
                            SettingClickableItem(
                                label = "Skip Segments Provider",
                                description = when (skipProvider) {
                                    "introdb" -> "IntroDB only"
                                    "theintrodb" -> "TheIntroDB only"
                                    else -> "Both — IntroDB first, TheIntroDB fills gaps (movies too)"
                                },
                                onClick = {
                                    skipProvider = when (skipProvider) {
                                        "both" -> "introdb"
                                        "introdb" -> "theintrodb"
                                        else -> "both"
                                    }
                                    prefs.edit().putString("skip_segments_provider", skipProvider).apply()
                                }
                            )
                        }
                        item {
                            SettingClickableItem(
                                label = "IntroDB API Key",
                                description = if (introDbApiKey.isEmpty()) "Not Configured" else "••••••••",
                                onClick = { showApiKeyDialog = true }
                            )
                        }
                        item {
                            SettingClickableItem(
                                label = "IntroDB API URL",
                                description = introDbApiUrl,
                                onClick = { showApiUrlDialog = true }
                            )
                        }
                        item {
                            SettingClickableItem(
                                label = "TheIntroDB API URL",
                                description = theIntroDbApiUrl,
                                onClick = { showTheIntroDbUrlDialog = true }
                            )
                        }
                        item {
                            SettingToggleItem(
                                label = "Auto-Skip Intro",
                                description = "Automatically skip show intros.",
                                checked = autoSkipIntro,
                                onCheckedChange = {
                                    autoSkipIntro = it
                                    prefs.edit().putBoolean("auto_skip_intro", it).apply()
                                }
                            )
                        }
                        item {
                            SettingToggleItem(
                                label = "Auto-Skip Recap",
                                description = "Automatically skip show recaps.",
                                checked = autoSkipRecap,
                                onCheckedChange = {
                                    autoSkipRecap = it
                                    prefs.edit().putBoolean("auto_skip_recap", it).apply()
                                }
                            )
                        }
                        item {
                            SettingToggleItem(
                                label = "Auto-Skip Outro",
                                description = "Automatically skip show outros/credits.",
                                checked = autoSkipOutro,
                                onCheckedChange = {
                                    autoSkipOutro = it
                                    prefs.edit().putBoolean("auto_skip_outro", it).apply()
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

                    SettingsCategory.LOGS -> {
                        item {
                            SettingToggleItem(
                                label = "Enable Logging",
                                description = "Save app logs to this device for troubleshooting. " +
                                    "Off by default — logs can contain stream URLs and request " +
                                    "headers (including Debrid tokens), so only enable when needed.",
                                checked = loggingEnabled,
                                onCheckedChange = {
                                    loggingEnabled = it
                                    FileLogger.setEnabled(it)
                                }
                            )
                        }
                        if (loggingEnabled) {
                            item {
                                SettingClickableItem(
                                    label = "Clear Logs",
                                    description = "Delete persisted log files on this device.",
                                    onClick = { FileLogger.clearLogs() }
                                )
                            }
                            item {
                                Text(
                                    text = "Phone can pull these logs over the network from Settings → TV → Logs.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(start = 16.dp, bottom = 8.dp)
                                )
                            }
                            if (recentLogs.isEmpty()) {
                                item {
                                    Text(
                                        text = "No log entries yet.",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.padding(16.dp)
                                    )
                                }
                            } else {
                                // Newest last; show a bounded tail to keep the list snappy on TV.
                                items(recentLogs.takeLast(500)) { line ->
                                    Text(
                                        text = line,
                                        fontFamily = FontFamily.Monospace,
                                        fontSize = 12.sp,
                                        color = MaterialTheme.colorScheme.onSurface,
                                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 1.dp)
                                    )
                                }
                            }
                        } else {
                            item {
                                Text(
                                    text = "Logging is disabled. No logs are being saved to this device.",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(16.dp)
                                )
                            }
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
                            val updateChecker = remember {
                                com.playbridge.player.update.UpdateChecker.getInstance(context)
                            }
                            val updateState by updateChecker.state.collectAsState()
                            val checking = updateState is
                                com.playbridge.player.update.UpdateState.Checking
                            // The dialog/progress UI itself is rendered once at the activity
                            // root (MainActivity); here we only expose the manual trigger.
                            SettingClickableItem(
                                label = "Check for updates",
                                description = if (checking) "Checking…" else "Tap to check for a newer version",
                                enabled = !checking,
                                onClick = { updateChecker.check(manual = true) }
                            )
                        }
                        // Play flavor: never advertise the sideloaded plugin APK; only show
                        // the row when the plugin is already present (informational).
                        if (isGeckoInstalled || com.playbridge.player.FlavorConfig.SIDELOAD_LINKS_SUPPORTED) {
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
                        item {
                            SettingClickableItem(
                                label = "Exit PlayBridge",
                                description = "Quit the app and stop the server.",
                                onClick = { showExitDialog = true }
                            )
                        }
                    }
                }
            }
        }
    }

    if (showExitDialog) {
        com.playbridge.player.ui.theme.ThemedDialog(onDismissRequest = { showExitDialog = false }) {
            Surface(
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier.width(400.dp)
            ) {
                Column(
                    modifier = Modifier.padding(32.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Text("Exit PlayBridge?", style = MaterialTheme.typography.headlineSmall)
                    Text(
                        "This stops the server — the phone won't be able to cast until you open the app again.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Button(onClick = { showExitDialog = false }, modifier = Modifier.padding(end = 8.dp)) {
                            Text("Cancel")
                        }
                        Button(onClick = {
                            showExitDialog = false
                            exitApp()
                        }) {
                            Text("Exit")
                        }
                    }
                }
            }
        }
    }

    // IP Entry Dialog (Remains mostly same but styled)
    if (showIpDialog) {
        var tempIp by remember { mutableStateOf(if (customIp == "auto") "" else customIp) }
        com.playbridge.player.ui.theme.ThemedDialog(onDismissRequest = { showIpDialog = false }) {
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
        com.playbridge.player.ui.theme.ThemedDialog(onDismissRequest = { showGeckoDialog = false }) {
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

    if (showApiKeyDialog) {
        var tempKey by remember { mutableStateOf(introDbApiKey) }
        com.playbridge.player.ui.theme.ThemedDialog(onDismissRequest = { showApiKeyDialog = false }) {
            Surface(
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier.width(400.dp)
            ) {
                Column(
                    modifier = Modifier.padding(32.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Text("IntroDB API Key", style = MaterialTheme.typography.headlineSmall)
                    Text(
                        "Enter your IntroDB API key or Clerk token. Leave empty if using the public API.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    androidx.compose.foundation.text.BasicTextField(
                        value = tempKey,
                        onValueChange = { tempKey = it },
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
                        Button(onClick = { showApiKeyDialog = false }, modifier = Modifier.padding(end = 8.dp)) {
                            Text("Cancel")
                        }
                        Button(onClick = {
                            introDbApiKey = tempKey.trim()
                            prefs.edit().putString("introdb_api_key", introDbApiKey).apply()
                            showApiKeyDialog = false
                        }) {
                            Text("Save")
                        }
                    }
                }
            }
        }
    }

    if (showApiUrlDialog) {
        var tempUrl by remember { mutableStateOf(introDbApiUrl) }
        com.playbridge.player.ui.theme.ThemedDialog(onDismissRequest = { showApiUrlDialog = false }) {
            Surface(
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier.width(400.dp)
            ) {
                Column(
                    modifier = Modifier.padding(32.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Text("IntroDB API URL", style = MaterialTheme.typography.headlineSmall)
                    Text(
                        "Set custom API url for skip segments. Default is https://api.introdb.app",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    androidx.compose.foundation.text.BasicTextField(
                        value = tempUrl,
                        onValueChange = { tempUrl = it },
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
                        Button(onClick = { showApiUrlDialog = false }, modifier = Modifier.padding(end = 8.dp)) {
                            Text("Cancel")
                        }
                        Button(onClick = {
                            val finalUrl = tempUrl.trim().ifEmpty { "https://api.introdb.app" }
                            introDbApiUrl = finalUrl
                            prefs.edit().putString("introdb_api_url", finalUrl).apply()
                            showApiUrlDialog = false
                        }) {
                            Text("Save")
                        }
                    }
                }
            }
        }
    }

    if (showTheIntroDbUrlDialog) {
        var tempUrl by remember { mutableStateOf(theIntroDbApiUrl) }
        com.playbridge.player.ui.theme.ThemedDialog(onDismissRequest = { showTheIntroDbUrlDialog = false }) {
            Surface(
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier.width(400.dp)
            ) {
                Column(
                    modifier = Modifier.padding(32.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Text("TheIntroDB API URL", style = MaterialTheme.typography.headlineSmall)
                    Text(
                        "Set custom API url for TheIntroDB skip segments. Default is https://api.theintrodb.org",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    androidx.compose.foundation.text.BasicTextField(
                        value = tempUrl,
                        onValueChange = { tempUrl = it },
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
                        Button(onClick = { showTheIntroDbUrlDialog = false }, modifier = Modifier.padding(end = 8.dp)) {
                            Text("Cancel")
                        }
                        Button(onClick = {
                            val finalUrl = tempUrl.trim().ifEmpty { "https://api.theintrodb.org" }
                            theIntroDbApiUrl = finalUrl
                            prefs.edit().putString("theintrodb_api_url", finalUrl).apply()
                            showTheIntroDbUrlDialog = false
                        }) {
                            Text("Save")
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
    INTEGRATIONS("Integrations", Icons.Default.Build),
    APPEARANCE("Appearance", Icons.Default.Add),
    LOGS("Logs", Icons.AutoMirrored.Filled.List),
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
