package com.playbridge.player

import android.Manifest
import android.content.Intent
import com.playbridge.player.ui.components.DeviceGuard
import com.playbridge.player.ui.components.OverlayPermissionDialog
import com.playbridge.player.ui.components.OverlayPermissionGuard
import com.playbridge.player.ui.components.WrongDeviceDialog
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.core.content.ContextCompat
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Surface
import com.playbridge.player.pairing.PairingStore
import com.playbridge.player.server.ServerService
import com.playbridge.player.server.WebSocketServer
import com.playbridge.player.data.HistoryStore
import com.playbridge.player.data.PlaybackHistoryItem
import com.playbridge.player.ui.HistoryScreen
import com.playbridge.player.ui.FavoritesScreen
import com.playbridge.player.ui.PairingScreen
import com.playbridge.player.ui.SettingsScreen
import com.playbridge.player.ui.components.AppSidebar
import com.playbridge.player.ui.theme.AppTheme
import com.playbridge.player.ui.theme.PlayBridgeTVTheme
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import androidx.compose.runtime.saveable.rememberSaveable

class MainActivity : ComponentActivity() {

    private lateinit var pairingStore: PairingStore
    private lateinit var historyStore: HistoryStore

    // Passed into MainContent so that onNewIntent can trigger a navigation to PairingScreen
    // from outside the Compose tree (e.g. when the background service fires ACTION_OPEN_PAIRING).
    private val _openPairingRequest = mutableStateOf(false)

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { _ ->
        // Start service regardless of permission results, features will just be degraded
        ServerService.start(this)
    }

    @OptIn(ExperimentalTvMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        pairingStore = PairingStore(applicationContext)
        historyStore = HistoryStore(applicationContext)

        val permissionsToRequest = mutableListOf<String>()

        // Request notification permission on Android 13+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                permissionsToRequest.add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }



        if (permissionsToRequest.isNotEmpty()) {
            permissionLauncher.launch(permissionsToRequest.toTypedArray())
        } else {
            ServerService.start(this)
        }

        // If launched via ACTION_OPEN_PAIRING (e.g. app was not running when a phone connected),
        // signal MainContent to navigate to PairingScreen on first composition.
        if (intent?.action == ServerService.ACTION_OPEN_PAIRING) {
            _openPairingRequest.value = true
        }

        // Fire-and-forget update check on cold start; only surfaces UI if a newer build exists.
        com.playbridge.player.update.UpdateChecker.getInstance(this).check(manual = false)

        setContent {
            val appTheme = remember { mutableStateOf(AppTheme.fromPrefs(this)) }
            var showPhoneWarning by remember { mutableStateOf(DeviceGuard.shouldWarn(this)) }
            var showOverlayRationale by remember {
                mutableStateOf(OverlayPermissionGuard.isNeeded(this))
            }
            if (showPhoneWarning) {
                WrongDeviceDialog(onDismiss = {
                    DeviceGuard.dismiss(this)
                    showPhoneWarning = false
                })
            } else if (showOverlayRationale) {
                // Explain the "Display over other apps" permission before sending the
                // user to the bare system toggle. Gated behind the wrong-device dialog so
                // the two prompts never stack on a sideloaded phone install.
                OverlayPermissionDialog(
                    onOpenSettings = {
                        OverlayPermissionGuard.openSettings(this)
                        showOverlayRationale = false
                    },
                    onDismiss = { showOverlayRationale = false }
                )
            }
            // Update-available notify / download / install flow. Gated behind the
            // cold-start guard dialogs so we never stack two dialogs (bad with D-pad focus);
            // once those are dismissed the update dialog surfaces on its own.
            if (!showPhoneWarning && !showOverlayRationale) {
                com.playbridge.player.update.UpdateGate(
                    com.playbridge.player.update.UpdateChecker.getInstance(this)
                )
            }
            PlayBridgeTVTheme(theme = appTheme.value) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    shape = RectangleShape
                ) {
                    MainContent(
                        pairingStore = pairingStore,
                        historyStore = historyStore,
                        openPairingRequest = _openPairingRequest,
                        currentTheme = appTheme
                    )
                }
            }
        }
    }

    /**
     * Called when the app is already running (foreground or backgrounded) and receives a new
     * Intent — e.g. when ServerService fires ACTION_OPEN_PAIRING because a phone is connecting.
     * FLAG_ACTIVITY_SINGLE_TOP ensures this is called instead of re-creating the activity.
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.action == ServerService.ACTION_OPEN_PAIRING) {
            _openPairingRequest.value = true
        }
    }
}

// Screen.Home has been removed. The PairingScreen is now shown both on first launch
// (no devices ever paired) and when navigating to "Pair New Device" from the Library.
enum class Screen {
    Pairing,
    History,
    Favorites,
    Settings
}

@Composable
fun MainContent(
    pairingStore: PairingStore,
    historyStore: HistoryStore,
    openPairingRequest: MutableState<Boolean>,
    currentTheme: MutableState<AppTheme>
) {
    val scope = rememberCoroutineScope()
    val currentContext = androidx.compose.ui.platform.LocalContext.current
    val prefs = remember { currentContext.getSharedPreferences("browser_prefs", android.content.Context.MODE_PRIVATE) }
    var enableHistory by remember { mutableStateOf(prefs.getBoolean("enable_history", true)) }

    DisposableEffect(prefs) {
        val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == "enable_history") {
                enableHistory = prefs.getBoolean("enable_history", true)
            }
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        onDispose {
            prefs.unregisterOnSharedPreferenceChangeListener(listener)
        }
    }

    // Default to History; overridden below once we know whether any device has paired.
    var currentScreen by rememberSaveable { mutableStateOf(Screen.History) }
    var isInitialCheckDone by remember { mutableStateOf(false) }

    val connectionState by ServerService.connectionState.collectAsState()
    val connectedCount by ServerService.connectedClientCount.collectAsState()
    val pendingPairingRequest by ServerService.pendingPairingRequest.collectAsState()
    val pairedDevices by pairingStore.pairedDevices.collectAsState(initial = emptyList())
    val isOnboardingDone by pairingStore.isOnboardingDone.collectAsState(initial = true)

    LaunchedEffect(enableHistory) {
        if (!enableHistory && currentScreen == Screen.History) {
            currentScreen = Screen.Pairing
        }
    }

    // On first launch: show PairingScreen only if no device has ever connected AND onboarding not done.
    LaunchedEffect(pairedDevices, isOnboardingDone, enableHistory) {
        if (!isInitialCheckDone) {
            currentScreen = if (!enableHistory) {
                Screen.Pairing
            } else if (pairedDevices.isEmpty() && !isOnboardingDone) {
                Screen.Pairing
            } else {
                Screen.History
            }
            
            if (!isOnboardingDone) {
                pairingStore.setOnboardingDone(true)
            }
            isInitialCheckDone = true
        }
    }

    // Handle background-triggered navigation: a phone started connecting → show PairingScreen
    // so the user can see the PIN before typing it on the phone.
    val shouldOpenPairing by openPairingRequest
    LaunchedEffect(shouldOpenPairing) {
        if (shouldOpenPairing) {
            currentScreen = Screen.Pairing
            openPairingRequest.value = false
        }
    }

    var serverIp by remember { mutableStateOf<String?>(null) }
    var serverPort by remember { mutableStateOf<Int?>(null) }
    var deviceName by remember { mutableStateOf("Android TV") }
    var deviceId by remember { mutableStateOf("") }

    // Load initial values
    LaunchedEffect(Unit) {
        val appCtx = currentContext.applicationContext ?: currentContext
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            deviceId = pairingStore.getOrCreateDeviceId()
            serverIp = getLocalIpAddress(appCtx)
        }
    }
    LaunchedEffect(Unit) {
        pairingStore.serverPort.collect { port ->
            serverPort = port
        }
    }
    LaunchedEffect(Unit) {
        pairingStore.deviceName.collect { name ->
            deviceName = name
        }
    }

    // When a phone successfully connects while the PairingScreen is visible, navigate to Library.
    // (The pairing is done — now let the user see their history/favourites.)
    LaunchedEffect(connectionState) {
        if (connectionState is WebSocketServer.ConnectionState.Connected) {
            if (currentScreen == Screen.Pairing && enableHistory) {
                currentScreen = Screen.History
            }
        }
    }

    val onPlayItem: (PlaybackHistoryItem) -> Unit = { item ->
        // Replay = re-run the stored payload through the *same* launch path a live cast
        // uses, resuming at the TV's saved position. Subtitles, audio language, headers and
        // visual metadata all come back because they ride inside the payload.
        val payload = com.playbridge.shared.protocol.decodePlaylistPayloadJson(item.payloadJson)
        if (payload != null) {
            val tvPref = currentContext
                .getSharedPreferences("browser_prefs", android.content.Context.MODE_PRIVATE)
                .getString("player_mode", "phone") ?: "phone"
            val intent = com.playbridge.player.player.PlayerLauncher.buildPlayerIntent(
                context = currentContext,
                payload = payload,
                tvPlayerMode = tvPref,
                overrideStartPositionMs = item.position.takeIf { it > 0 },
            )
            currentContext.startActivity(intent)
        }
    }

    // Background comes straight from the themed Surface (colorScheme.surface), so
    // Dark / AMOLED / Light are respected with no extra gradient layer.
    Box(modifier = Modifier.fillMaxSize()) {
        Row(modifier = Modifier.fillMaxSize()) {
            AppSidebar(
                currentScreen = currentScreen,
                onScreenSelected = { currentScreen = it },
                enableHistory = enableHistory
            )

            // Content Area
            Box(modifier = Modifier.weight(1f)) {
                when (currentScreen) {
                    Screen.Pairing -> {
                        PairingScreen(
                            ip = serverIp ?: "unknown",
                            // Show the wss:// port — the address senders connect to.
                            port = serverPort ?: com.playbridge.shared.protocol.Config.DEFAULT_PORT,
                            deviceName = deviceName,
                            deviceId = deviceId,
                            connectionState = connectionState,
                            connectedCount = connectedCount,
                            pendingRequest = pendingPairingRequest,
                            onDeny = { ServerService.denyPairing() },
                            pairedDevices = pairedDevices,
                            onForget = { device -> scope.launch { pairingStore.forgetDevice(device) } },
                            onForgetAll = { scope.launch { pairingStore.forgetAllDevices() } }
                        )
                    }
                    Screen.History -> {
                        HistoryScreen(
                            historyStore = historyStore,
                            onPlayItem = onPlayItem
                        )
                    }
                    Screen.Favorites -> {
                        FavoritesScreen(
                            historyStore = historyStore,
                            onPlayItem = onPlayItem
                        )
                    }
                    Screen.Settings -> {
                        SettingsScreen(
                            onThemeChanged = { theme -> currentTheme.value = theme }
                        )
                    }
                }
            }
        }
    }

    // Universal back handler: any screen that isn't primary screen goes back to primary screen.
    val primaryScreen = if (enableHistory) Screen.History else Screen.Pairing
    androidx.activity.compose.BackHandler(enabled = currentScreen != primaryScreen) {
        currentScreen = primaryScreen
    }
}

private fun getLocalIpAddress(context: android.content.Context): String? {
    val prefs = context.getSharedPreferences("browser_prefs", android.content.Context.MODE_PRIVATE)
    val preferredIp = prefs.getString("preferred_ip", "auto")

    val allIps = mutableListOf<String>()

    var backupIp: String? = null
    try {
        val interfaces = java.net.NetworkInterface.getNetworkInterfaces()
        while (interfaces.hasMoreElements()) {
            val networkInterface = interfaces.nextElement()
            if (networkInterface.isLoopback || !networkInterface.isUp) continue

            val addresses = networkInterface.inetAddresses
            while (addresses.hasMoreElements()) {
                val address = addresses.nextElement()
                if (address is java.net.Inet4Address && !address.isLoopbackAddress) {
                    val hostAddress = address.hostAddress
                    if (hostAddress != null) {
                        allIps.add(hostAddress)
                        if (hostAddress.startsWith("192.168.")) {
                            backupIp = hostAddress // Prefer 192.168 if auto
                        } else if (backupIp == null) {
                            backupIp = hostAddress
                        }
                    }
                }
            }
        }
    } catch (e: Exception) {
        // Ignore
    }

    // Use preferred custom IP if set, otherwise use auto-selected IP
    return if (preferredIp != null && preferredIp != "auto" && preferredIp.isNotEmpty()) {
        preferredIp
    } else {
        backupIp
    }
}
