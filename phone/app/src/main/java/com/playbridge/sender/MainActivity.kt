package com.playbridge.sender

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import com.playbridge.sender.connection.ConnectionStore
import com.playbridge.sender.connection.WebSocketClient
import com.playbridge.sender.model.QRCodeData
import com.playbridge.sender.model.TvDevice
import com.playbridge.sender.ui.HomeScreen
import com.playbridge.sender.ui.QRScannerScreen
import com.playbridge.sender.ui.theme.PlayBridgeTheme
import kotlinx.coroutines.launch

private const val TAG = "MainActivity"

class MainActivity : ComponentActivity() {
    
    private val webSocketClient = WebSocketClient()
    private lateinit var connectionStore: ConnectionStore
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        
        connectionStore = ConnectionStore(applicationContext)
        
        setContent {
            PlayBridgeTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    MainContent(
                        webSocketClient = webSocketClient,
                        connectionStore = connectionStore,
                        modifier = Modifier.padding(innerPadding)
                    )
                }
            }
        }
    }
    
    override fun onDestroy() {
        webSocketClient.destroy()
        super.onDestroy()
    }
}

enum class Screen {
    Scanner,
    Home
}

@Composable
fun MainContent(
    webSocketClient: WebSocketClient,
    connectionStore: ConnectionStore,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    
    var currentScreen by remember { mutableStateOf(Screen.Scanner) }
    var tvDevice by remember { mutableStateOf<TvDevice?>(null) }
    val connectionState by webSocketClient.connectionState.collectAsState()
    
    // Camera permission
    var hasCameraPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == 
                PackageManager.PERMISSION_GRANTED
        )
    }
    
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        hasCameraPermission = isGranted
    }
    
    // Request camera permission on launch
    LaunchedEffect(Unit) {
        if (!hasCameraPermission) {
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }
    
    // Load stored TV device and try to auto-connect
    LaunchedEffect(Unit) {
        connectionStore.tvDevice.collect { device ->
            if (device != null) {
                tvDevice = device
                // Auto-connect to stored device
                if (connectionState is WebSocketClient.ConnectionState.Disconnected) {
                    webSocketClient.connect(device.ip, device.port, device.token, device.name)
                    currentScreen = Screen.Home
                }
            }
        }
    }
    
    // Listen for pong responses
    LaunchedEffect(Unit) {
        webSocketClient.messages.collect { message ->
            if (message.contains("pong")) {
                Toast.makeText(context, "Pong received!", Toast.LENGTH_SHORT).show()
            }
        }
    }
    
    when (currentScreen) {
        Screen.Scanner -> {
            QRScannerScreen(
                onQRCodeScanned = { qrData ->
                    Log.i(TAG, "QR scanned: ${qrData.name}")
                    val device = TvDevice(
                        ip = qrData.ip,
                        port = qrData.port,
                        token = qrData.token,
                        name = qrData.name
                    )
                    tvDevice = device
                    
                    // Save and connect
                    scope.launch {
                        connectionStore.saveTvDevice(device)
                    }
                    webSocketClient.connect(qrData.ip, qrData.port, qrData.token, qrData.name)
                    currentScreen = Screen.Home
                },
                modifier = modifier
            )
        }
        Screen.Home -> {
            HomeScreen(
                connectionState = connectionState,
                tvDevice = tvDevice,
                onSendPing = {
                    val sent = webSocketClient.sendPing()
                    if (sent) {
                        Toast.makeText(context, "Ping sent!", Toast.LENGTH_SHORT).show()
                    }
                },
                onDisconnect = {
                    webSocketClient.disconnect()
                    scope.launch {
                        connectionStore.clearTvDevice()
                    }
                    tvDevice = null
                    currentScreen = Screen.Scanner
                },
                onRescan = {
                    currentScreen = Screen.Scanner
                },
                modifier = modifier
            )
        }
    }
}