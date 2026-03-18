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
import com.playbridge.receiver.logging.FileLogger
import androidx.core.app.NotificationCompat
import com.playbridge.receiver.MainActivity
import com.playbridge.receiver.R
import com.playbridge.protocol.Command
import com.playbridge.protocol.createContextJson
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
private const val CHANNEL_ID_LAUNCH = "playbridge_launch"
private const val NOTIFICATION_ID = 1
private const val NOTIFICATION_ID_LAUNCH = 2

class ServerService : Service() {
    
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var webSocketServer: WebSocketServer? = null
    private lateinit var pairingStore: PairingStore
    private lateinit var overlayWindow: OverlayWindowHelper
    
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
        overlayWindow = OverlayWindowHelper(applicationContext)
        nsdManager = getSystemService(Context.NSD_SERVICE) as android.net.nsd.NsdManager
        createNotificationChannel()
    }
    
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                createNotification(),
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE or
                    android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
            )
        } else {
            startForeground(NOTIFICATION_ID, createNotification())
        }
        startServer()
        return START_STICKY
    }
    
    private fun registerNsdService(port: Int) {
        if (registrationListener != null) return // Already registered
        
        val deviceName = android.provider.Settings.Global.getString(contentResolver, android.provider.Settings.Global.DEVICE_NAME) ?: Build.MODEL
        
        scope.launch(kotlinx.coroutines.Dispatchers.IO) {
            val deviceId = pairingStore.getOrCreateDeviceId()
            val prefs = getSharedPreferences("browser_prefs", Context.MODE_PRIVATE)
            val preferredIp = prefs.getString("preferred_ip", "auto")
            
            val serviceInfo = android.net.nsd.NsdServiceInfo().apply {
                serviceName = deviceName
                serviceType = com.playbridge.protocol.NsdConstants.SERVICE_TYPE
                setPort(port)
                setAttribute("uuid", deviceId)
                if (preferredIp != null && preferredIp != "auto" && preferredIp.isNotEmpty()) {
                    setAttribute("custom_ip", preferredIp)
                }
            }
            
            registrationListener = object : android.net.nsd.NsdManager.RegistrationListener {
                override fun onServiceRegistered(NsdServiceInfo: android.net.nsd.NsdServiceInfo) {
                    FileLogger.d(TAG, "Service registered: ${NsdServiceInfo.serviceName}")
                }

                override fun onRegistrationFailed(serviceInfo: android.net.nsd.NsdServiceInfo, errorCode: Int) {
                    FileLogger.e(TAG, "Registration failed: $errorCode")
                }

                override fun onServiceUnregistered(arg0: android.net.nsd.NsdServiceInfo) {
                    FileLogger.d(TAG, "Service unregistered")
                }

                override fun onUnregistrationFailed(serviceInfo: android.net.nsd.NsdServiceInfo, errorCode: Int) {
                    FileLogger.e(TAG, "Unregistration failed: $errorCode")
                }
            }
            
            nsdManager.registerService(
                serviceInfo,
                android.net.nsd.NsdManager.PROTOCOL_DNS_SD,
                registrationListener
            )
        }
    }
    
    private fun startServer() {
        if (webSocketServer != null) return  // Already running from a previous onStartCommand
        scope.launch(kotlinx.coroutines.Dispatchers.IO) {
            val token = pairingStore.getOrCreateToken()
            val port = pairingStore.serverPort.first()
            val ip = getLocalIpAddress(applicationContext) ?: "unknown"
            
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
                        
                        // Show/hide overlay window tied to connection:
                        // When the overlay is visible, Android's BAL check sees
                        // callingUidHasNonAppVisibleWindow=true which exempts us from
                        // background activity launch restrictions on Android 14+.
                        when (state) {
                            is WebSocketServer.ConnectionState.Connected -> overlayWindow.show()
                            is WebSocketServer.ConnectionState.Running,
                            is WebSocketServer.ConnectionState.Stopped,
                            is WebSocketServer.ConnectionState.Error -> overlayWindow.hide()
                            else -> Unit
                        }

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
                
                // Observe connected client count
                launch {
                    server.connectedClientCount.collect { count ->
                        _connectedClientCount.value = count
                    }
                }
                
                // Expose commands for the activity to observe
                launch {
                    server.commands.collect { command ->
                        handleCommand(command)
                    }
                }
            }
            
            FileLogger.i(TAG, "Server started at $ip:$port")
        }
    }
    
    private fun handleCommand(command: Command) {
        if (command !is Command.Mouse) {
            FileLogger.i(TAG, "=== COMMAND RECEIVED ===")
            FileLogger.i(TAG, "Command type: ${command.javaClass.simpleName}")
        }
        
        when (command) {
            is Command.Play -> {
                FileLogger.i(TAG, "=== PLAY COMMAND ===")

                com.playbridge.receiver.player.PlaylistStore.currentPlaylist = null

                val prefs = getSharedPreferences("browser_prefs", Context.MODE_PRIVATE)
                val tvPref = if (prefs.contains("player_mode")) {
                    prefs.getString("player_mode", "phone") ?: "phone"
                } else {
                    if (prefs.getBoolean("use_external_player", false)) "external" else "phone"
                }

                val finalMode = if (tvPref == "phone") {
                    val phoneMode = command.playerMode
                    if (phoneMode != null && phoneMode != "tv") {
                        phoneMode
                    } else {
                        "internal" // Default if no phone mode provided
                    }
                } else {
                    tvPref
                }

                val useExternalPlayer = finalMode == "external"

                if (useExternalPlayer) {
                    activeContext = "external_player"
                    broadcastContext()

                    val intent = Intent(Intent.ACTION_VIEW).apply {
                        // Force a generic video/* mime type so that all video players show up in the chooser,
                        // rather than just the ones explicitly registering for strict mime types like application/x-mpegURL
                        setDataAndType(android.net.Uri.parse(command.url), "video/*")
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        
                        // Pass title to external player (many players read this specifically)
                        command.title?.let { title ->
                            putExtra(Intent.EXTRA_TITLE, title)
                            // MX Player specific title extra
                            putExtra("title", title)
                        }
                        
                        // Pass headers to external player if supported
                        command.headers?.let { headersMap ->
                            val bundle = android.os.Bundle()
                            headersMap.forEach { (key, value) ->
                                bundle.putString(key, value)
                            }
                            putExtra(android.provider.Browser.EXTRA_HEADERS, bundle)
                            // MX Player specific headers array
                            val headersArray = headersMap.flatMap { listOf(it.key, it.value) }.toTypedArray()
                            putExtra("headers", headersArray)
                        }
                    }

                    val chooserIntent = Intent.createChooser(intent, "Open video with...")
                    chooserIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                    launchActivityFromBackground(chooserIntent, "Playing media in external app")
                } else {
                    activeContext = "player"
                    broadcastContext()

                    val activityClass = if (finalMode == "internal_vlc") {
                        com.playbridge.receiver.player.VlcPlayerActivity::class.java
                    } else {
                        com.playbridge.receiver.player.ExoPlayerActivity::class.java
                    }

                    val playerIntent = Intent(this, activityClass).apply {
                        putExtra(EXTRA_URL, command.url)
                        putExtra(EXTRA_TITLE, command.title)
                        putExtra(EXTRA_CONTENT_TYPE, command.contentType)
                        if (command.detectedBy != null) {
                            putExtra(EXTRA_DETECTED_BY, command.detectedBy)
                        }
                        if (command.subtitles != null) {
                            putStringArrayListExtra(EXTRA_SUBTITLES, ArrayList(command.subtitles))
                        }
                        if (command.headers != null) {
                            putExtra(EXTRA_HEADERS, HashMap(command.headers))
                        }
                        if (command.preferredAudioLanguage != null) {
                            putExtra(EXTRA_PREFERRED_AUDIO_LANG, command.preferredAudioLanguage)
                        }
                        if (command.preferredSubtitleLanguage != null) {
                            putExtra(EXTRA_PREFERRED_SUBTITLE_LANG, command.preferredSubtitleLanguage)
                        }
                        if (command.defaultVideoQuality != null) {
                            putExtra("default_video_quality", command.defaultVideoQuality)
                        }
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                    }
                    launchActivityFromBackground(playerIntent, "Playing media")
                }
            }
            is Command.Browser -> {
                FileLogger.i(TAG, "Browser command: ${command.url}")
                activeContext = "browser"
                broadcastContext()

                val browserIntent = Intent(this, com.playbridge.receiver.browser.BrowserActivity::class.java).apply {
                    putExtra(com.playbridge.receiver.browser.BrowserActivity.EXTRA_URL, command.url)
                    putExtra(com.playbridge.receiver.browser.BrowserActivity.EXTRA_BROWSER_MODE, command.browserMode)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                }
                launchActivityFromBackground(browserIntent, "Opening browser")
            }
            is Command.Control -> {
                FileLogger.i(TAG, "Control command: ${command.command}")
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
                FileLogger.i(TAG, "Remote command: ${command.key}")
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
                FileLogger.i(TAG, "Browser control: ${command.action}")
                val intent = Intent(ACTION_BROWSER_CONTROL).apply {
                    putExtra(EXTRA_BROWSER_ACTION, command.action)
                    setPackage(packageName)
                }
                sendBroadcast(intent)
            }
            is Command.ContextQuery -> {
                FileLogger.i(TAG, "Context query - responding with: $activeContext")
                scope.launch {
                    webSocketServer?.broadcastStatus(createContextJson(activeContext))
                }
            }
            is Command.Playlist -> {
                FileLogger.i(TAG, "=== PLAYLIST COMMAND === (${command.items.size} items, startIndex: ${command.startIndex})")
                activeContext = "player"
                broadcastContext()

                // Serialize playlist items as JSON for the intent
                com.playbridge.receiver.player.PlaylistStore.currentPlaylist = command.items

                val prefs = getSharedPreferences("browser_prefs", Context.MODE_PRIVATE)
                val tvPref = prefs.getString("player_mode", "phone") ?: "phone"
                val activityClass = if (tvPref == "internal_vlc") {
                    com.playbridge.receiver.player.VlcPlayerActivity::class.java
                } else {
                    com.playbridge.receiver.player.ExoPlayerActivity::class.java
                }

                val playerIntent = Intent(this, activityClass).apply {
                    // Start with the first item (or startIndex)
                    val firstItem = command.items.getOrNull(command.startIndex) ?: command.items.firstOrNull()
                    if (firstItem != null) {
                        putExtra(EXTRA_URL, firstItem.url)
                        putExtra(EXTRA_TITLE, firstItem.title)
                        putExtra(EXTRA_CONTENT_TYPE, firstItem.contentType)
                        if (firstItem.preferredAudioLanguage != null) {
                            putExtra(EXTRA_PREFERRED_AUDIO_LANG, firstItem.preferredAudioLanguage)
                        }
                        if (firstItem.preferredSubtitleLanguage != null) {
                            putExtra(EXTRA_PREFERRED_SUBTITLE_LANG, firstItem.preferredSubtitleLanguage)
                        }
                        if (firstItem.defaultVideoQuality != null) {
                            putExtra("default_video_quality", firstItem.defaultVideoQuality)
                        }
                    }
                    putExtra(EXTRA_IS_PLAYLIST, true)
                    putExtra(EXTRA_PLAYLIST_INDEX, command.startIndex)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                }
                launchActivityFromBackground(playerIntent, "Playing playlist (${command.items.size} items)")
            }
            is Command.Ping -> {
                // Handled by WebSocketServer
            }
            is Command.Unknown -> {
                FileLogger.w(TAG, "Unknown command: ${command.type}")
            }
        }
    }
    
    private fun broadcastContext() {
        scope.launch {
            webSocketServer?.broadcastStatus(createContextJson(activeContext))
        }
    }
    
    /**
     * Launches an activity from the background (e.g. when the app is not in the foreground).
     *
     * Strategy:
     * 1. startActivity() — works because we have a `mediaPlayback` foreground service, which
     *    qualifies for the Android 10+ background-launch exemption.
     * 2. fullScreenIntent notification — belt-and-suspenders fallback for strict OEMs that
     *    ignore the exemption. Android pops this as an overlay over whatever is on screen.
     */
    private fun launchActivityFromBackground(intent: Intent, description: String) {
        // Attempt 1: direct startActivity (requires mediaPlayback foreground service type)
        try {
            startActivity(intent)
        } catch (e: Exception) {
            FileLogger.w(TAG, "startActivity failed, falling back to fullScreenIntent: ${e.message}")
        }

        // Attempt 2: fullScreenIntent notification (fires in parallel as a guaranteed fallback)
        val pendingIntent = PendingIntent.getActivity(
            this,
            intent.component.hashCode(), // unique request code per activity
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, CHANNEL_ID_LAUNCH)
            .setContentTitle("PlayBridge")
            .setContentText(description)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setFullScreenIntent(pendingIntent, true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setAutoCancel(true)
            .build()

        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID_LAUNCH, notification)
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

            // High-priority channel used when launching activities from background
            val launchChannel = NotificationChannel(
                CHANNEL_ID_LAUNCH,
                "PlayBridge Commands",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Used to bring PlayBridge to foreground when a command is received"
            }

            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
            manager.createNotificationChannel(launchChannel)
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
    
    private fun getLocalIpAddress(context: android.content.Context): String? {
        val prefs = context.getSharedPreferences("browser_prefs", android.content.Context.MODE_PRIVATE)
        val preferredIp = prefs.getString("preferred_ip", "auto")
        val allIps = mutableListOf<String>()
        var backupIp: String? = null

        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val networkInterface = interfaces.nextElement()
                if (networkInterface.isLoopback || !networkInterface.isUp) continue
                
                val addresses = networkInterface.inetAddresses
                while (addresses.hasMoreElements()) {
                    val address = addresses.nextElement()
                    if (address is Inet4Address && !address.isLoopbackAddress) {
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
            FileLogger.e(TAG, "Failed to get IP address", e)
        }

        return if (preferredIp != null && preferredIp != "auto" && preferredIp.isNotEmpty()) {
            preferredIp
        } else {
            backupIp
        }
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
        // Remove overlay window if still visible
        overlayWindow.hide()
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
        const val EXTRA_DETECTED_BY = "detected_by"
        const val EXTRA_SUBTITLES = "subtitles"
        const val EXTRA_HEADERS = "headers"
        const val EXTRA_COMMAND = "command"
        const val EXTRA_REMOTE_KEY = "remote_key"
        const val EXTRA_MOUSE_EVENT = "mouse_event"
        const val EXTRA_MOUSE_DX = "mouse_dx"
        const val EXTRA_MOUSE_DY = "mouse_dy"
        const val ACTION_BROWSER_CONTROL = "com.playbridge.receiver.ACTION_BROWSER_CONTROL"
        const val EXTRA_BROWSER_ACTION = "browser_action"
        const val EXTRA_PLAYLIST = "playlist"
        const val EXTRA_IS_PLAYLIST = "is_playlist"
        const val EXTRA_PLAYLIST_INDEX = "playlist_index"
        const val EXTRA_PREFERRED_AUDIO_LANG = "preferred_audio_lang"
        const val EXTRA_PREFERRED_SUBTITLE_LANG = "preferred_subtitle_lang"
        const val EXTRA_EXTERNAL_SUBTITLE_URL = "external_subtitle_url"
        const val EXTRA_VIDEO_FILTER = "video_filter"
        const val EXTRA_CUSTOM_FILTER_VALUES = "custom_filter_values"
        
        // Static flow for UI to observe connection state
        private val _connectionState = MutableStateFlow<WebSocketServer.ConnectionState>(WebSocketServer.ConnectionState.Stopped)
        val connectionState: StateFlow<WebSocketServer.ConnectionState> = _connectionState.asStateFlow()
        
        // Static flow for UI to observe connected client count
        private val _connectedClientCount = MutableStateFlow(0)
        val connectedClientCount: StateFlow<Int> = _connectedClientCount.asStateFlow()
        
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
