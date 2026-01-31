package com.playbridge.receiver

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
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
import com.playbridge.receiver.ui.PairingScreen
import com.playbridge.receiver.ui.theme.PlayBridgeTVTheme
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

class MainActivity : ComponentActivity() {
    
    private lateinit var pairingStore: PairingStore
    
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
        
        setContent {
            PlayBridgeTVTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    shape = RectangleShape
                ) {
                    MainContent(pairingStore = pairingStore)
                }
            }
        }
    }
}

enum class Screen {
    Home,
    Pairing
}

@Composable
fun MainContent(pairingStore: PairingStore) {
    var currentScreen by remember { mutableStateOf(Screen.Pairing) }
    val connectionState by ServerService.connectionState.collectAsState()
    var serverIp by remember { mutableStateOf<String?>(null) }
    var serverPort by remember { mutableStateOf<Int?>(null) }
    var authToken by remember { mutableStateOf("") }
    var deviceName by remember { mutableStateOf("Android TV") }
    
    // Load initial values
    LaunchedEffect(Unit) {
        authToken = pairingStore.getOrCreateToken()
        serverPort = pairingStore.serverPort.first()
        serverIp = getLocalIpAddress()
        pairingStore.deviceName.collect { name ->
            deviceName = name
        }
    }
    
    // Auto-navigate to Home when a phone connects
    LaunchedEffect(connectionState) {
        if (connectionState is WebSocketServer.ConnectionState.Connected) {
            currentScreen = Screen.Home
        }
    }
    
    when (currentScreen) {
        Screen.Home -> {
            HomeScreen(
                connectionState = connectionState,
                serverIp = serverIp,
                serverPort = serverPort,
                onShowPairing = { currentScreen = Screen.Pairing }
            )
        }
        Screen.Pairing -> {
            PairingScreen(
                ip = serverIp ?: "unknown",
                port = serverPort ?: 8765,
                token = authToken,
                deviceName = deviceName,
                connectionState = connectionState
            )
        }
    }
    
    // Handle back button for navigation
    androidx.activity.compose.BackHandler(enabled = currentScreen == Screen.Pairing) {
        currentScreen = Screen.Home
    }
}

private fun getLocalIpAddress(): String? {
    try {
        val interfaces = java.net.NetworkInterface.getNetworkInterfaces()
        while (interfaces.hasMoreElements()) {
            val networkInterface = interfaces.nextElement()
            if (networkInterface.isLoopback || !networkInterface.isUp) continue
            
            val addresses = networkInterface.inetAddresses
            while (addresses.hasMoreElements()) {
                val address = addresses.nextElement()
                if (address is java.net.Inet4Address && !address.isLoopbackAddress) {
                    return address.hostAddress
                }
            }
        }
    } catch (e: Exception) {
        // Ignore
    }
    return null
}