package com.playbridge.player

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
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
import com.playbridge.player.ui.LibraryScreen
import com.playbridge.player.ui.PairingScreen
import com.playbridge.player.ui.SettingsScreen
import com.playbridge.player.ui.theme.PlayBridgeTVTheme
import com.playbridge.player.data.HistoryStore
import kotlinx.coroutines.flow.first

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

        // Request bluetooth connect permission on Android 12+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                permissionsToRequest.add(Manifest.permission.BLUETOOTH_CONNECT)
            }
        }

        if (permissionsToRequest.isNotEmpty()) {
            permissionLauncher.launch(permissionsToRequest.toTypedArray())
        } else {
            ServerService.start(this)
        }

        if (!Settings.canDrawOverlays(this)) {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            startActivity(intent)
        }

        // If launched via ACTION_OPEN_PAIRING (e.g. app was not running when a phone connected),
        // signal MainContent to navigate to PairingScreen on first composition.
        if (intent?.action == ServerService.ACTION_OPEN_PAIRING) {
            _openPairingRequest.value = true
        }

        setContent {
            PlayBridgeTVTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    shape = RectangleShape
                ) {
                    MainContent(
                        pairingStore = pairingStore,
                        historyStore = historyStore,
                        openPairingRequest = _openPairingRequest
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
    Library,
    Settings
}

@Composable
fun MainContent(
    pairingStore: PairingStore,
    historyStore: HistoryStore,
    openPairingRequest: MutableState<Boolean>
) {
    // Default to Library; overridden below once we know whether any device has paired.
    var currentScreen by remember { mutableStateOf(Screen.Library) }
    var isInitialCheckDone by remember { mutableStateOf(false) }

    val connectionState by ServerService.connectionState.collectAsState()
    val connectedCount by ServerService.connectedClientCount.collectAsState()
    val pairedDevices by pairingStore.pairedDevices.collectAsState(initial = emptyList())
    val isOnboardingDone by pairingStore.isOnboardingDone.collectAsState(initial = true)

    // On first launch: show PairingScreen only if no device has ever connected AND onboarding not done.
    LaunchedEffect(pairedDevices, isOnboardingDone) {
        if (!isInitialCheckDone) {
            currentScreen = if (pairedDevices.isEmpty() && !isOnboardingDone) {
                Screen.Pairing
            } else {
                Screen.Library
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
    var authToken by remember { mutableStateOf("") }
    var deviceName by remember { mutableStateOf("Android TV") }
    var deviceId by remember { mutableStateOf("") }

    val currentContext = androidx.compose.ui.platform.LocalContext.current

    // Load initial values
    LaunchedEffect(Unit) {
        val appCtx = currentContext.applicationContext ?: currentContext
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            authToken = pairingStore.getOrCreateToken()
            deviceId = pairingStore.getOrCreateDeviceId()
            serverPort = pairingStore.serverPort.first()
            serverIp = getLocalIpAddress(appCtx)
        }
        pairingStore.deviceName.collect { name ->
            deviceName = name
        }
    }

    // When a phone successfully connects while the PairingScreen is visible, navigate to Library.
    // (The pairing is done — now let the user see their history/favourites.)
    LaunchedEffect(connectionState) {
        if (connectionState is WebSocketServer.ConnectionState.Connected) {
            if (currentScreen == Screen.Pairing) {
                currentScreen = Screen.Library
            }
        }
    }

    when (currentScreen) {
        Screen.Pairing -> {
            PairingScreen(
                ip = serverIp ?: "unknown",
                port = serverPort ?: com.playbridge.shared.protocol.Config.DEFAULT_PORT,
                token = authToken,
                deviceName = deviceName,
                deviceId = deviceId,
                connectionState = connectionState,
                connectedCount = connectedCount
            )
        }
        Screen.Library -> {
            LibraryScreen(
                historyStore = historyStore,
                deviceName = deviceName,
                connectedCount = connectedCount,
                onNavigateToPairing = { currentScreen = Screen.Pairing },
                onNavigateToSettings = { currentScreen = Screen.Settings },
                onPlayItem = { item ->
                    val prefs = currentContext.getSharedPreferences("browser_prefs", android.content.Context.MODE_PRIVATE)
                    val tvPref = prefs.getString("player_mode", "phone") ?: "phone"
                    val activityClass = if (tvPref == "internal_vlc") {
                        com.playbridge.player.player.VlcPlayerActivity::class.java
                    } else {
                        com.playbridge.player.player.ExoPlayerActivity::class.java
                    }

                    val intent = android.content.Intent(currentContext, activityClass).apply {
                        putExtra(ServerService.EXTRA_URL, item.url)
                        putExtra(ServerService.EXTRA_TITLE, item.title)
                        putExtra(ServerService.EXTRA_CONTENT_TYPE, item.contentType)
                        if (item.headers != null) {
                            putExtra(ServerService.EXTRA_HEADERS, java.util.HashMap(item.headers))
                        }
                        // Restore playlist context if this item was part of a playlist
                        if (item.playlistJson != null) {
                            try {
                                val decoded = com.playbridge.shared.protocol.protocolJson.decodeFromString(
                                    kotlinx.serialization.builtins.ListSerializer(com.playbridge.shared.protocol.PlayPayload.serializer()),
                                    item.playlistJson
                                )
                                com.playbridge.player.player.PlaylistStore.currentPlaylist = decoded
                                putExtra(ServerService.EXTRA_IS_PLAYLIST, true)
                                putExtra(ServerService.EXTRA_PLAYLIST_INDEX, item.playlistIndex)
                            } catch (e: Exception) {
                                com.playbridge.player.player.PlaylistStore.currentPlaylist = null
                            }
                        } else {
                            com.playbridge.player.player.PlaylistStore.currentPlaylist = null
                        }
                        // Restore saved selections
                        item.preferredAudioLanguage?.let { putExtra(ServerService.EXTRA_PREFERRED_AUDIO_LANG, it) }
                        item.preferredSubtitleLanguage?.let { putExtra(ServerService.EXTRA_PREFERRED_SUBTITLE_LANG, it) }
                        item.externalSubtitleUrl?.let { putExtra(ServerService.EXTRA_EXTERNAL_SUBTITLE_URL, it) }
                        item.videoFilter?.let { putExtra(ServerService.EXTRA_VIDEO_FILTER, it) }
                        item.customFilterValues?.let { vals ->
                            putExtra(ServerService.EXTRA_CUSTOM_FILTER_VALUES, floatArrayOf(vals[0], vals[1], vals[2]))
                        }
                    }
                    currentContext.startActivity(intent)
                }
            )
        }
        Screen.Settings -> {
            SettingsScreen(
                onBack = { currentScreen = Screen.Library }
            )
        }
    }

    // Universal back handler: any screen that isn't Library goes back to Library.
    // Library itself has no handler, so the system back gesture exits the app normally.
    androidx.activity.compose.BackHandler(enabled = currentScreen != Screen.Library) {
        currentScreen = Screen.Library
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
