package com.playbridge.player

import android.Manifest
import android.content.Intent
import com.playbridge.player.ui.components.CornerCardSlot
import com.playbridge.player.ui.components.DeviceGuard
import com.playbridge.player.ui.components.OverlayPermissionGuard
import com.playbridge.player.ui.components.OverlayPermissionScreen
import com.playbridge.player.ui.components.WrongDeviceScreen
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
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
import com.playbridge.player.data.historyLogKey
import com.playbridge.player.data.toSafeLogString
import com.playbridge.player.logging.FileLogger
import com.playbridge.player.ui.SettingsScreen
import com.playbridge.player.ui.settings.SettingsCategory
import com.playbridge.player.update.UpdateGate
import com.playbridge.player.ui.UserScriptApprovalPrompt
import com.playbridge.player.ui.WatchTogetherPanel
import com.playbridge.player.ui.home.BookmarkActionsPanel
import com.playbridge.player.ui.home.HomeScreen
import com.playbridge.player.ui.home.PairingRequestCard
import com.playbridge.player.ui.home.bookmarksOf
import com.playbridge.player.ui.home.resumePositionForHistoryItem
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

        if (intent?.action == ServerService.ACTION_OPEN_PAIRING ||
            intent?.action == ServerService.ACTION_OPEN_SCRIPT_APPROVAL
        ) {
            requestScreenOnForPairing("onCreate")
        }

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
            PlayBridgeTVTheme(theme = appTheme.value) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    shape = RectangleShape
                ) {
                    if (showPhoneWarning) {
                        WrongDeviceScreen(onDismiss = {
                            DeviceGuard.dismiss(this@MainActivity)
                            showPhoneWarning = false
                        })
                    } else if (showOverlayRationale) {
                        // Explain the "Display over other apps" permission before sending the
                        // user to the bare system toggle. Gated behind the wrong-device notice so
                        // the two never stack on a sideloaded phone install.
                        OverlayPermissionScreen(
                            onOpenSettings = {
                                OverlayPermissionGuard.openSettings(this@MainActivity)
                                showOverlayRationale = false
                            },
                            onDismiss = { showOverlayRationale = false }
                        )
                    } else {
                        Box(modifier = Modifier.fillMaxSize()) {
                            MainContent(
                                pairingStore = pairingStore,
                                historyStore = historyStore,
                                openPairingRequest = _openPairingRequest,
                                currentTheme = appTheme
                            )
                            // Update card (available / downloading / installing), bottom-right.
                            // Only shown once the cold-start notices above are dismissed.
                            UpdateGate(
                                com.playbridge.player.update.UpdateChecker.getInstance(this@MainActivity)
                            )
                        }
                    }
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
            requestScreenOnForPairing("onNewIntent")
            _openPairingRequest.value = true
        } else if (intent.action == ServerService.ACTION_OPEN_SCRIPT_APPROVAL) {
            requestScreenOnForPairing("scriptApproval")
        }
    }

    private fun requestScreenOnForPairing(source: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
        }
        FileLogger.i("MainActivity", "$source requested screen on for pairing")
    }
}

/** Home is the only top-level screen; the others open from it and Back returns to it.
 * Phones are managed in Settings, so Home's Phones pill opens Settings on that category. */
enum class Screen {
    Home,
    WatchTogether,
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
    var currentScreen by rememberSaveable { mutableStateOf(Screen.Home) }
    var settingsCategory by rememberSaveable { mutableStateOf(SettingsCategory.Playback) }
    var actionsFor by remember { mutableStateOf<PlaybackHistoryItem?>(null) }

    val connectionState by ServerService.connectionState.collectAsState()
    val connectedCount by ServerService.connectedClientCount.collectAsState()
    val pendingPairingRequest by ServerService.pendingPairingRequest.collectAsState()
    val pendingUserScript by ServerService.pendingUserScriptReview.collectAsState()
    val pairedDevices by pairingStore.pairedDevices.collectAsState(initial = emptyList())
    val isOnboardingDone by pairingStore.isOnboardingDone.collectAsState(initial = true)
    val history by historyStore.history.collectAsState(initial = emptyList())
    val bookmarks = remember(history) { bookmarksOf(history) }

    // Back from Settings or Watch Together returns to Home. Registered before the screens
    // so a screen's own BackHandler (for example a Settings sub-page) is consulted first.
    androidx.activity.compose.BackHandler(enabled = pendingUserScript == null && currentScreen != Screen.Home) {
        currentScreen = Screen.Home
    }

    // A pairing request arriving through ACTION_OPEN_PAIRING just needs the activity in front:
    // the request itself appears as a card over whichever screen is showing. Consume the flag.
    val shouldOpenPairing by openPairingRequest
    LaunchedEffect(shouldOpenPairing) {
        if (shouldOpenPairing) openPairingRequest.value = false
    }
    LaunchedEffect(isOnboardingDone) {
        if (!isOnboardingDone) pairingStore.setOnboardingDone(true)
    }

    var serverIp by remember { mutableStateOf<String?>(null) }
    var serverPort by remember { mutableStateOf<Int?>(null) }
    var deviceName by remember { mutableStateOf("Android TV") }

    // Load initial values
    LaunchedEffect(Unit) {
        val appCtx = currentContext.applicationContext ?: currentContext
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            serverIp = getLocalIpAddress(appCtx)
        }
    }
    LaunchedEffect(Unit) {
        pairingStore.serverPort.collect { port ->
            serverPort = port
        }
    }
    // The name phones see in their list (Android's device name), not the stored pairing name.
    LaunchedEffect(Unit) {
        deviceName = com.playbridge.player.server.advertisedDeviceName(currentContext.applicationContext)
    }

    val onPlayItem: (PlaybackHistoryItem, Boolean) -> Unit = { item, startOver ->
        // Replay runs through the same launch path as a live cast. Only unfinished items
        // resume; completed items restart from the beginning. startOver forces a restart.
        val payload = com.playbridge.shared.protocol.decodePlaylistPayloadJson(item.payloadJson)
        if (payload != null) {
            val resumePosition = if (startOver) null else resumePositionForHistoryItem(item)
            FileLogger.i(
                "LibraryPlayback",
                "Launching history entry=${historyLogKey(item.id)}, " +
                    "position=${resumePosition ?: 0L}/${item.duration}, startOver=$startOver, " +
                    "savedContext=${item.playbackContext.toSafeLogString()}",
            )
            val tvPref = currentContext
                .getSharedPreferences("browser_prefs", android.content.Context.MODE_PRIVATE)
                .getString("player_mode", "phone") ?: "phone"
            val intent = com.playbridge.player.player.PlayerLauncher.buildPlayerIntent(
                context = currentContext,
                payload = payload,
                tvPlayerMode = tvPref,
                // A zero override deliberately suppresses any stale start_position_ms that
                // arrived in the original cast payload for completed/non-resumable entries.
                overrideStartPositionMs = resumePosition ?: 0L,
                historyId = item.id,
                playbackContext = item.playbackContext,
            )
            currentContext.startActivity(intent)
        } else {
            FileLogger.e(
                "LibraryPlayback",
                "Unable to decode playlist payload for history entry=${historyLogKey(item.id)}",
            )
        }
    }

    // Background comes from the themed Surface, so Dark / AMOLED / Light are respected.
    // A script prompt replaces the shell so D-pad focus cannot escape to Deny's neighbours.
    Box(modifier = Modifier.fillMaxSize()) {
        if (pendingUserScript != null) {
            UserScriptApprovalPrompt(pendingUserScript!!)
            return@Box
        }
        when (currentScreen) {
            Screen.Home -> HomeScreen(
                deviceName = deviceName,
                address = serverIp?.let { "$it:${serverPort ?: com.playbridge.shared.protocol.Config.DEFAULT_PORT}" },
                connectionState = connectionState,
                connectedCount = connectedCount,
                pairedCount = pairedDevices.size,
                bookmarks = bookmarks,
                onPlay = { onPlayItem(it, false) },
                onBookmarkActions = { actionsFor = it },
                onWatchTogether = { currentScreen = Screen.WatchTogether },
                onPhones = { settingsCategory = SettingsCategory.Phones; currentScreen = Screen.Settings },
                onSettings = { settingsCategory = SettingsCategory.Playback; currentScreen = Screen.Settings },
                takeInitialFocus = pendingPairingRequest == null && actionsFor == null,
            )
            Screen.WatchTogether -> WatchTogetherPanel(
                requestInitialFocus = true,
                onBack = { currentScreen = Screen.Home },
            )
            Screen.Settings -> SettingsScreen(
                onThemeChanged = { theme -> currentTheme.value = theme },
                pairingStore = pairingStore,
                initialCategory = settingsCategory,
            )
        }

        // A pairing request is a card over whichever screen is showing (top-right). It keeps the
        // server's 60 second timeout and the Deny path; the server removes it when it ends.
        pendingPairingRequest?.let { request ->
            CornerCardSlot(Alignment.TopEnd) {
                PairingRequestCard(request = request, onDeny = { ServerService.denyPairing() })
            }
        }
    }

    actionsFor?.let { item ->
        BookmarkActionsPanel(
            item = item,
            onResume = { actionsFor = null; onPlayItem(item, false) },
            onStartOver = { actionsFor = null; onPlayItem(item, true) },
            // Removing a bookmark only clears the flag; the stored history entry is kept.
            onRemoveBookmark = { actionsFor = null; scope.launch { historyStore.toggleFavorite(item.id) } },
            onDismiss = { actionsFor = null },
        )
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
