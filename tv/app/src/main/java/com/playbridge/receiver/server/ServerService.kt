package com.playbridge.receiver.server

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.playbridge.receiver.MainActivity
import com.playbridge.receiver.R
import com.playbridge.receiver.model.Command
import com.playbridge.receiver.model.createContextJson
import com.playbridge.receiver.pairing.PairingStore
import com.playbridge.receiver.model.PairedDevice
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import java.net.Inet4Address
import java.net.NetworkInterface

private const val TAG = "ServerService"
private const val CHANNEL_ID = "playbridge_server"
private const val NOTIFICATION_ID = 1

class ServerService : Service() {
    
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var webSocketServer: WebSocketServer? = null
    private lateinit var pairingStore: PairingStore
    
    // Track what is currently active on the TV
    private var activeContext: String = "idle" // "player", "browser", or "idle"
    
    private val _serverInfo = MutableStateFlow<ServerInfo?>(null)
    val serverInfo: StateFlow<ServerInfo?> = _serverInfo.asStateFlow()
    
    data class ServerInfo(
        val ip: String,
        val port: Int,
        val token: String
    )
    
    private lateinit var nsdManager: android.net.nsd.NsdManager
    private var registrationListener: android.net.nsd.NsdManager.RegistrationListener? = null
    
    override fun onCreate() {
        super.onCreate()
        pairingStore = PairingStore(applicationContext)
        nsdManager = getSystemService(Context.NSD_SERVICE) as android.net.nsd.NsdManager
        createNotificationChannel()
    }
    
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, createNotification())
        startServer()
        return START_STICKY
    }
    
    private fun registerNsdService(port: Int) {
        if (registrationListener != null) return // Already registered
        
        val deviceName = android.provider.Settings.Global.getString(contentResolver, android.provider.Settings.Global.DEVICE_NAME) ?: Build.MODEL
        
        val serviceInfo = android.net.nsd.NsdServiceInfo().apply {
            serviceName = deviceName
            serviceType = com.playbridge.protocol.NsdConstants.SERVICE_TYPE
            setPort(port)
        }
        
        registrationListener = object : android.net.nsd.NsdManager.RegistrationListener {
            override fun onServiceRegistered(NsdServiceInfo: android.net.nsd.NsdServiceInfo) {
                Log.d(TAG, "Service registered: ${NsdServiceInfo.serviceName}")
            }
            
            override fun onRegistrationFailed(serviceInfo: android.net.nsd.NsdServiceInfo, errorCode: Int) {
                Log.e(TAG, "Registration failed: $errorCode")
            }
            
            override fun onServiceUnregistered(arg0: android.net.nsd.NsdServiceInfo) {
                Log.d(TAG, "Service unregistered")
            }
            
            override fun onUnregistrationFailed(serviceInfo: android.net.nsd.NsdServiceInfo, errorCode: Int) {
                Log.e(TAG, "Unregistration failed: $errorCode")
            }
        }
        
        nsdManager.registerService(
            serviceInfo,
            android.net.nsd.NsdManager.PROTOCOL_DNS_SD,
            registrationListener
        )
    }
    
    private fun startServer() {
        scope.launch {
            val token = pairingStore.getOrCreateToken()
            val port = pairingStore.serverPort.first()
            val ip = getLocalIpAddress() ?: "unknown"
            
            webSocketServer = WebSocketServer(port = port, authToken = token).also { server ->
                server.start()
                
                // Register NSD service
                registerNsdService(port)
                
                _serverInfo.value = ServerInfo(ip = ip, port = port, token = token)
                
                // Observe connection state for notification updates and expose to UI
                launch {
                    server.connectionState.collect { state ->
                        updateNotification(state)
                        _connectionState.value = state
                        
                        // Persist paired device on connection
                        if (state is WebSocketServer.ConnectionState.Connected) {
                            val device = PairedDevice(
                                id = state.clientId,
                                name = "Phone (${state.clientId.take(4)})"
                            )
                            pairingStore.addPairedDevice(device)
                        }
                    }
                }
                
                // Expose commands for the activity to observe
                launch {
                    server.commands.collect { command ->
                        handleCommand(command)
                    }
                }
            }
            
            Log.i(TAG, "Server started at $ip:$port")
        }
    }
    
    private fun handleCommand(command: Command) {
        if (command !is Command.Mouse) {
            Log.i(TAG, "=== COMMAND RECEIVED ===")
            Log.i(TAG, "Command type: ${command.javaClass.simpleName}")
        }
        
        when (command) {
            is Command.Play -> {
                Log.i(TAG, "=== PLAY COMMAND ===")
                activeContext = "player"
                broadcastContext()
                
                // Clear stack to MainActivity first
                val homeIntent = Intent(this, MainActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                }
                startActivity(homeIntent)

                // Launch PlayerActivity
                val intent = Intent(this, com.playbridge.receiver.player.PlayerActivity::class.java).apply {
                    putExtra(EXTRA_URL, command.url)
                    putExtra(EXTRA_TITLE, command.title)
                    putExtra(EXTRA_CONTENT_TYPE, command.contentType)
                    if (command.headers != null) {
                        putExtra(EXTRA_HEADERS, HashMap(command.headers))
                    }
                    // No CLEAR_TASK, just NEW_TASK to add to the stack we just cleared
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) 
                }
                startActivity(intent)
            }
            is Command.Browser -> {
                Log.i(TAG, "Browser command: ${command.url}")
                activeContext = "browser"
                broadcastContext()
                
                 // Clear stack to MainActivity first
                val homeIntent = Intent(this, MainActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                }
                startActivity(homeIntent)

                val intent = Intent(this, com.playbridge.receiver.browser.BrowserActivity::class.java).apply {
                    putExtra(com.playbridge.receiver.browser.BrowserActivity.EXTRA_URL, command.url)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                startActivity(intent)
            }
            is Command.Control -> {
                Log.i(TAG, "Control command: ${command.command}")
                if (command.command == "stop") {
                    activeContext = "idle"
                    broadcastContext()
                }
                val intent = Intent(ACTION_CONTROL).apply {
                    putExtra(EXTRA_COMMAND, command.command)
                    setPackage(packageName)
                }
                sendBroadcast(intent)
            }
            is Command.Remote -> {
                Log.i(TAG, "Remote command: ${command.key}")
                val intent = Intent(ACTION_REMOTE).apply {
                    putExtra(EXTRA_REMOTE_KEY, command.key)
                    setPackage(packageName)
                }
                sendBroadcast(intent)
            }
            is Command.Mouse -> {
                // Log.i(TAG, "Mouse command: ${command.event} (${command.dx}, ${command.dy})")
                val intent = Intent(ACTION_MOUSE).apply {
                    putExtra(EXTRA_MOUSE_EVENT, command.event)
                    putExtra(EXTRA_MOUSE_DX, command.dx)
                    putExtra(EXTRA_MOUSE_DY, command.dy)
                    setPackage(packageName)
                }
                sendBroadcast(intent)
            }
            is Command.BrowserControl -> {
                Log.i(TAG, "Browser control: ${command.action}")
                val intent = Intent(ACTION_BROWSER_CONTROL).apply {
                    putExtra(EXTRA_BROWSER_ACTION, command.action)
                    setPackage(packageName)
                }
                sendBroadcast(intent)
            }
            is Command.ContextQuery -> {
                Log.i(TAG, "Context query - responding with: $activeContext")
                scope.launch {
                    webSocketServer?.broadcastStatus(createContextJson(activeContext))
                }
            }
            is Command.Ping -> {
                // Handled by WebSocketServer
            }
            is Command.Unknown -> {
                Log.w(TAG, "Unknown command: ${command.type}")
            }
        }
    }
    
    private fun broadcastContext() {
        scope.launch {
            webSocketServer?.broadcastStatus(createContextJson(activeContext))
        }
    }
    
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "PlayBridge Server",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows when PlayBridge server is running"
            }
            
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }
    
    private fun createNotification(status: String = "Starting..."): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("PlayBridge")
            .setContentText(status)
            .setSmallIcon(android.R.drawable.ic_menu_share)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }
    
    private fun updateNotification(state: WebSocketServer.ConnectionState) {
        val status = when (state) {
            is WebSocketServer.ConnectionState.Stopped -> "Stopped"
            is WebSocketServer.ConnectionState.Starting -> "Starting..."
            is WebSocketServer.ConnectionState.Running -> "Waiting for connection on port ${state.port}"
            is WebSocketServer.ConnectionState.Connected -> "Phone connected"
            is WebSocketServer.ConnectionState.Error -> "Error: ${state.message}"
        }
        
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, createNotification(status))
    }
    
    private fun getLocalIpAddress(): String? {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val networkInterface = interfaces.nextElement()
                if (networkInterface.isLoopback || !networkInterface.isUp) continue
                
                val addresses = networkInterface.inetAddresses
                while (addresses.hasMoreElements()) {
                    val address = addresses.nextElement()
                    if (address is Inet4Address && !address.isLoopbackAddress) {
                        return address.hostAddress
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get IP address", e)
        }
        return null
    }
    
    override fun onBind(intent: Intent?): IBinder? = null
    
    override fun onDestroy() {
        if (registrationListener != null) {
            try {
                nsdManager.unregisterService(registrationListener)
            } catch (e: IllegalArgumentException) {
                // Ignore if service is not registered
            }
            registrationListener = null
        }
        // Stop server synchronously
        webSocketServer?.stop()
        webSocketServer = null
        // Cancel scope after stopping server
        scope.cancel()
        super.onDestroy()
    }
    
    companion object {
        const val ACTION_PLAY = "com.playbridge.receiver.ACTION_PLAY"
        const val ACTION_BROWSER = "com.playbridge.receiver.ACTION_BROWSER"
        const val ACTION_CONTROL = "com.playbridge.receiver.ACTION_CONTROL"
        const val ACTION_REMOTE = "com.playbridge.receiver.ACTION_REMOTE"
        const val ACTION_MOUSE = "com.playbridge.receiver.ACTION_MOUSE"
        const val EXTRA_URL = "url"
        const val EXTRA_TITLE = "title"
        const val EXTRA_CONTENT_TYPE = "content_type"
        const val EXTRA_HEADERS = "headers"
        const val EXTRA_COMMAND = "command"
        const val EXTRA_REMOTE_KEY = "remote_key"
        const val EXTRA_MOUSE_EVENT = "mouse_event"
        const val EXTRA_MOUSE_DX = "mouse_dx"
        const val EXTRA_MOUSE_DY = "mouse_dy"
        const val ACTION_BROWSER_CONTROL = "com.playbridge.receiver.ACTION_BROWSER_CONTROL"
        const val EXTRA_BROWSER_ACTION = "browser_action"
        
        // Static flow for UI to observe connection state
        private val _connectionState = MutableStateFlow<WebSocketServer.ConnectionState>(WebSocketServer.ConnectionState.Stopped)
        val connectionState: StateFlow<WebSocketServer.ConnectionState> = _connectionState.asStateFlow()
        
        fun start(context: Context) {
            val intent = Intent(context, ServerService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
        
        fun stop(context: Context) {
            context.stopService(Intent(context, ServerService::class.java))
        }
    }
}
