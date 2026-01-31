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
import com.playbridge.receiver.pairing.PairingStore
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
    
    private val _serverInfo = MutableStateFlow<ServerInfo?>(null)
    val serverInfo: StateFlow<ServerInfo?> = _serverInfo.asStateFlow()
    
    data class ServerInfo(
        val ip: String,
        val port: Int,
        val token: String
    )
    
    override fun onCreate() {
        super.onCreate()
        pairingStore = PairingStore(applicationContext)
        createNotificationChannel()
    }
    
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, createNotification())
        startServer()
        return START_STICKY
    }
    
    private fun startServer() {
        scope.launch {
            val token = pairingStore.getOrCreateToken()
            val port = pairingStore.serverPort.first()
            val ip = getLocalIpAddress() ?: "unknown"
            
            webSocketServer = WebSocketServer(port = port, authToken = token).also { server ->
                server.start()
                
                _serverInfo.value = ServerInfo(ip = ip, port = port, token = token)
                
                // Observe connection state for notification updates and expose to UI
                launch {
                    server.connectionState.collect { state ->
                        updateNotification(state)
                        _connectionState.value = state
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
        when (command) {
            is Command.Play -> {
                Log.i(TAG, "Play command: ${command.url}")
                // TODO: Launch player activity with URL
                val intent = Intent(ACTION_PLAY).apply {
                    putExtra(EXTRA_URL, command.url)
                    putExtra(EXTRA_TITLE, command.title)
                    setPackage(packageName)
                }
                sendBroadcast(intent)
            }
            is Command.Browser -> {
                Log.i(TAG, "Browser command: ${command.url}")
                // TODO: Launch browser activity with URL
                val intent = Intent(ACTION_BROWSER).apply {
                    putExtra(EXTRA_URL, command.url)
                    setPackage(packageName)
                }
                sendBroadcast(intent)
            }
            is Command.Control -> {
                Log.i(TAG, "Control command: ${command.command}")
                val intent = Intent(ACTION_CONTROL).apply {
                    putExtra(EXTRA_COMMAND, command.command)
                    setPackage(packageName)
                }
                sendBroadcast(intent)
            }
            is Command.Ping -> {
                // Handled by WebSocketServer
            }
            is Command.Unknown -> {
                Log.w(TAG, "Unknown command: ${command.type}")
            }
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
        webSocketServer?.stop()
        scope.cancel()
        super.onDestroy()
    }
    
    companion object {
        const val ACTION_PLAY = "com.playbridge.receiver.ACTION_PLAY"
        const val ACTION_BROWSER = "com.playbridge.receiver.ACTION_BROWSER"
        const val ACTION_CONTROL = "com.playbridge.receiver.ACTION_CONTROL"
        const val EXTRA_URL = "url"
        const val EXTRA_TITLE = "title"
        const val EXTRA_COMMAND = "command"
        
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
