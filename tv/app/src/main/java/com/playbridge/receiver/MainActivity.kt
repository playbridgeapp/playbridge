package com.playbridge.receiver

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
import com.playbridge.receiver.pairing.PairingStore
import com.playbridge.receiver.server.ServerService
import com.playbridge.receiver.server.WebSocketServer
import com.playbridge.receiver.ui.HomeScreen
import com.playbridge.receiver.ui.LibraryScreen
import com.playbridge.receiver.ui.PairingScreen
import com.playbridge.receiver.ui.SettingsScreen
import com.playbridge.receiver.ui.theme.PlayBridgeTVTheme
import com.playbridge.receiver.data.HistoryStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

class MainActivity : ComponentActivity() {

    private lateinit var pairingStore: PairingStore
    private lateinit var historyStore: HistoryStore
    
    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        // Start service regardless of notification permission
        ServerService.start(this)
    }
    
    @OptIn(ExperimentalTvMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        pairingStore = PairingStore(applicationContext)
        historyStore = HistoryStore(applicationContext)
        
        // Request notification permission on Android 13+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            } else {
                ServerService.start(this)
            }
        } else {
            ServerService.start(this)
        }

        // SYSTEM_ALERT_WINDOW lets us keep a tiny invisible overlay window visible while
        // the phone is connected. This makes callingUidHasNonAppVisibleWindow=true in
        // Android's BAL check, allowing startActivity() from ServerService to work even
        // when the TV app is backgrounded (pressed Home). Without this, Android 14+ blocks
        // background activity launches entirely, even from foreground services.
        if (!Settings.canDrawOverlays(this)) {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            startActivity(intent)
        }

        
        // Preload ad blocker filters in background so they're ready when browser opens
        com.playbridge.receiver.browser.AdBlocker.preload(applicationContext)

        setContent {
            PlayBridgeTVTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    shape = RectangleShape
                ) {
                    MainContent(
                        pairingStore = pairingStore,
                        historyStore = historyStore
                    )
                }
            }
        }
    }
}

enum class Screen {
    Home,
    Pairing,
    Library,
    Settings
}

@Composable
fun MainContent(
    pairingStore: PairingStore,
    historyStore: HistoryStore
) {
    // Initial State determination
    var currentScreen by remember { mutableStateOf(Screen.Home) }
    var isInitialCheckDone by remember { mutableStateOf(false) }
    
    val connectionState by ServerService.connectionState.collectAsState()
    val connectedCount by ServerService.connectedClientCount.collectAsState()
    val pairedDevices by pairingStore.pairedDevices.collectAsState(initial = emptyList())
    
    // Check initial state once
    LaunchedEffect(pairedDevices) {
        if (!isInitialCheckDone && pairedDevices.isNotEmpty()) {
            currentScreen = Screen.Library
            isInitialCheckDone = true
        } else if (!isInitialCheckDone && pairedDevices.isEmpty()) {
            // Keep default Home
             isInitialCheckDone = true
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
    
    // Auto-navigate to Library when a phone connects (if not already there)
    LaunchedEffect(connectionState) {
        if (connectionState is WebSocketServer.ConnectionState.Connected) {
             // If we are on Home (Pairing status page), go to Library
             if (currentScreen == Screen.Home) {
                 currentScreen = Screen.Library
             }
        }
    }
    
    when (currentScreen) {
        Screen.Home -> {
            HomeScreen(
                connectionState = connectionState,
                serverIp = serverIp,
                serverPort = serverPort,
                connectedCount = connectedCount,
                deviceId = deviceId,
                onShowPairing = { currentScreen = Screen.Pairing }
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
                        com.playbridge.receiver.player.VlcPlayerActivity::class.java
                    } else {
                        com.playbridge.receiver.player.ExoPlayerActivity::class.java
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
                                val decoded = com.playbridge.protocol.protocolJson.decodeFromString(
                                    kotlinx.serialization.builtins.ListSerializer(com.playbridge.protocol.PlayPayload.serializer()),
                                    item.playlistJson
                                )
                                com.playbridge.receiver.player.PlaylistStore.currentPlaylist = decoded
                                putExtra(ServerService.EXTRA_IS_PLAYLIST, true)
                                putExtra(ServerService.EXTRA_PLAYLIST_INDEX, item.playlistIndex)
                            } catch (e: Exception) {
                                com.playbridge.receiver.player.PlaylistStore.currentPlaylist = null
                            }
                        } else {
                            com.playbridge.receiver.player.PlaylistStore.currentPlaylist = null
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
        Screen.Pairing -> {
            PairingScreen(
                ip = serverIp ?: "unknown",
                port = serverPort ?: 8765,
                token = authToken,
                deviceName = deviceName,
                deviceId = deviceId,
                connectionState = connectionState,
                connectedCount = connectedCount
            )
        }
        Screen.Settings -> {
            SettingsScreen(
                onBack = { currentScreen = Screen.Library }
            )
        }
    }
    
    // Handle back button for navigation
    androidx.activity.compose.BackHandler(enabled = currentScreen != Screen.Library && pairedDevices.isNotEmpty()) {
        currentScreen = Screen.Library
    }
    // Handle back from Settings to Library
    androidx.activity.compose.BackHandler(enabled = currentScreen == Screen.Settings) {
        currentScreen = Screen.Library
    }
    // Also handle back from Pairing to Home if no history
    androidx.activity.compose.BackHandler(enabled = currentScreen == Screen.Pairing && pairedDevices.isEmpty()) {
        currentScreen = Screen.Home
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