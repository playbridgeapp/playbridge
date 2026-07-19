package com.playbridge.player.server

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
import com.playbridge.player.logging.FileLogger
import com.playbridge.player.player.MpvProcess
import com.playbridge.player.player.RendererProcessSupervisor
import androidx.core.app.NotificationCompat
import com.playbridge.player.MainActivity
import com.playbridge.player.R
import com.playbridge.shared.logging.redactUrlForLog
import com.playbridge.shared.protocol.IncomingMessage
import com.playbridge.shared.protocol.createContextJson
import com.playbridge.player.pairing.PairingStore
import com.playbridge.player.model.PairedDevice
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
private const val MPV_PROCESS_EXIT_TIMEOUT_MS = 5_000L

// browser_prefs keys for the TV browser User-Agent override (see IncomingMessage.UserAgent).
private const val KEY_ACTIVE_UA_NAME = "active_user_agent_name"
private const val KEY_ACTIVE_UA_VALUE = "active_user_agent_value"
private const val KEY_SAVED_UAS = "saved_user_agents"

class ServerService : Service() {

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var webSocketServer: WebSocketServer? = null
    private lateinit var pairingStore: PairingStore
    private lateinit var overlayWindow: OverlayWindowHelper

    // Track what is currently active on the TV. @Volatile: written by main-thread
    // lifecycle setters and the broadcast receiver, read by the IO-dispatcher command
    // collector (handleMessage routes Remote/Mouse/BrowserControl on it).
    @Volatile
    private var activeContext: String = "idle" // "player", "browser", or "idle"
    @Volatile
    private var activePlayerEngine: String? = null
    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var pendingPlayerLaunchAfterMpvExit: Runnable? = null
    private var pendingPlayerLaunchGeneration = 0L

    private val _serverInfo = MutableStateFlow<ServerInfo?>(null)
    val serverInfo: StateFlow<ServerInfo?> = _serverInfo.asStateFlow()

    data class ServerInfo(
        val ip: String,
        val port: Int,
        val token: String
    )

    private lateinit var nsdManager: android.net.nsd.NsdManager
    private var registrationListener: android.net.nsd.NsdManager.RegistrationListener? = null

    // Receives ACTION_CONTEXT_IDLE from the tv/browser app (separate APK) when its
    // BrowserActivity is destroyed, so activeContext can be reset to "idle".
    private val contextIdleReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: android.content.Context, intent: Intent) {
            if (intent.action == ACTION_CONTEXT_IDLE) {
                setContextIdleInternal(setOf("browser", "browser_external"))
            }
        }
    }

    private val playerProcessEventReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: android.content.Context, intent: Intent) {
            if (intent.action != ACTION_PLAYER_PROCESS_EVENT) return
            when (intent.getStringExtra(EXTRA_PLAYER_PROCESS_EVENT)) {
                PLAYER_PROCESS_EVENT_STATUS -> intent.getStringExtra(EXTRA_PLAYER_STATUS_JSON)
                    ?.let(::broadcastPlaylistStatusInternal)
                PLAYER_PROCESS_EVENT_CONTEXT_PLAYER -> setContextPlayerInternal(
                    intent.getStringExtra(EXTRA_TARGET_PLAYER_ENGINE),
                )
                PLAYER_PROCESS_EVENT_CONTEXT_BROWSER -> setContextBrowserInternal()
                PLAYER_PROCESS_EVENT_CONTEXT_IDLE -> setContextIdleInternal(
                    onlyIfOneOf = setOf("player"),
                    onlyIfEngine = intent.getStringExtra(EXTRA_TARGET_PLAYER_ENGINE),
                )
                PLAYER_PROCESS_EVENT_LAUNCH_PLAYER -> playerLaunchIntent(intent)?.let {
                    launchAfterMpvProcessExit(
                        it,
                        "Launching replacement from private MPV process",
                    )
                }
            }
        }
    }

    private fun launchAfterMpvProcessExit(intent: Intent, reason: String) {
        MpvProcess.terminateRunningProcess(this)
        val generation = RendererProcessSupervisor.nextGeneration(
            RendererProcessSupervisor.Kind.MPV,
        )

        pendingPlayerLaunchAfterMpvExit?.let(mainHandler::removeCallbacks)
        val launch = Runnable {
            pendingPlayerLaunchAfterMpvExit = null
            if (_staticInstance === this &&
                RendererProcessSupervisor.isCurrentGeneration(
                    RendererProcessSupervisor.Kind.MPV,
                    generation,
                )
            ) {
                launchActivityFromBackground(intent, reason)
            }
        }
        pendingPlayerLaunchAfterMpvExit = launch
        pendingPlayerLaunchGeneration = generation

        FileLogger.i(TAG, "Waiting for private MPV process to exit before launching replacement")
        MpvProcess.awaitExit(this, MPV_PROCESS_EXIT_TIMEOUT_MS) { exited ->
            if (!exited) {
                FileLogger.e(
                    TAG,
                    "Private MPV process did not exit within ${MPV_PROCESS_EXIT_TIMEOUT_MS}ms; " +
                        "launching replacement after hard timeout",
                )
            }
            if (pendingPlayerLaunchAfterMpvExit === launch &&
                pendingPlayerLaunchGeneration == generation &&
                RendererProcessSupervisor.isCurrentGeneration(
                    RendererProcessSupervisor.Kind.MPV,
                    generation,
                )
            ) {
                launch.run()
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun playerLaunchIntent(intent: Intent): Intent? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(EXTRA_PLAYER_LAUNCH_INTENT, Intent::class.java)
        } else {
            intent.getParcelableExtra(EXTRA_PLAYER_LAUNCH_INTENT)
        }

    override fun onCreate() {
        super.onCreate()
        _staticInstance = this
        pairingStore = PairingStore(applicationContext)
        overlayWindow = OverlayWindowHelper(applicationContext)
        nsdManager = getSystemService(Context.NSD_SERVICE) as android.net.nsd.NsdManager


        createNotificationChannel()
        val filter = android.content.IntentFilter().apply {
            addAction(ACTION_CONTEXT_IDLE)
        }
        // Require the signature-protected CONTEXT_IDLE permission so only our own
        // packages (signed with the same keystore) can reset activeContext.
        val contextIdlePermission = "com.playbridge.permission.CONTEXT_IDLE"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(
                contextIdleReceiver,
                filter,
                contextIdlePermission,
                null,
                RECEIVER_EXPORTED
            )
        } else {
            registerReceiver(contextIdleReceiver, filter, contextIdlePermission, null)
        }

        val playerProcessFilter = android.content.IntentFilter(ACTION_PLAYER_PROCESS_EVENT)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(playerProcessEventReceiver, playerProcessFilter, RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(playerProcessEventReceiver, playerProcessFilter)
        }
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

    private fun registerNsdService(port: Int, wssPort: Int?, logsPort: Int?) {
        if (registrationListener != null) return // Already registered

        val deviceName = android.provider.Settings.Global.getString(
            contentResolver, android.provider.Settings.Global.DEVICE_NAME
        ) ?: android.os.Build.MODEL

        scope.launch(kotlinx.coroutines.Dispatchers.IO) {
            val deviceId = pairingStore.getOrCreateDeviceId()
            val prefs = getSharedPreferences("browser_prefs", Context.MODE_PRIVATE)
            val preferredIp = prefs.getString("preferred_ip", "auto")

            val serviceInfo = android.net.nsd.NsdServiceInfo().apply {
                serviceName = deviceName
                serviceType = com.playbridge.shared.protocol.NsdConstants.SERVICE_TYPE
                setPort(port)
                setAttribute("uuid", deviceId)
                // Advertise wss_port only when the TLS listener actually bound, so
                // senders don't get stranded preferring a wss port that isn't up.
                if (wssPort != null) {
                    setAttribute(
                        com.playbridge.shared.protocol.NsdConstants.KEY_WSS_PORT,
                        wssPort.toString()
                    )
                }
                if (logsPort != null) {
                    setAttribute(
                        com.playbridge.shared.protocol.NsdConstants.KEY_LOGS_PORT,
                        logsPort.toString()
                    )
                }
                if (preferredIp != null && preferredIp != "auto" && preferredIp.isNotEmpty()) {
                    setAttribute("custom_ip", preferredIp)
                }
            }

            doRegisterNsdService(serviceInfo, attempt = 1, maxAttempts = 4)
        }
    }

    /**
     * Attempts to register the NSD service, retrying up to [maxAttempts] times with
     * increasing back-off (3 s, 6 s, 9 s …) on FAILURE_INTERNAL_ERROR (code 0).
     *
     * This handles the race that can occur when a server restart unregisters the old
     * NSD entry asynchronously — if the mDNS daemon hasn't finished tearing down the
     * previous record when the new registration arrives, Android returns error 0 and
     * the TV silently becomes undiscoverable.  Retrying after a short delay resolves it.
     */
    private fun doRegisterNsdService(
        serviceInfo: android.net.nsd.NsdServiceInfo,
        attempt: Int,
        maxAttempts: Int
    ) {
        registrationListener = object : android.net.nsd.NsdManager.RegistrationListener {
            override fun onServiceRegistered(nsdServiceInfo: android.net.nsd.NsdServiceInfo) {
                FileLogger.i(TAG, "NSD service registered: ${nsdServiceInfo.serviceName} (attempt $attempt)")
            }

            override fun onRegistrationFailed(si: android.net.nsd.NsdServiceInfo, errorCode: Int) {
                FileLogger.e(TAG, "NSD registration failed (attempt $attempt/$maxAttempts): error $errorCode")
                registrationListener = null
                if (attempt < maxAttempts) {
                    val delayMs = 3_000L * attempt   // 3 s, 6 s, 9 s
                    FileLogger.i(TAG, "Retrying NSD registration in ${delayMs / 1000}s…")
                    scope.launch(kotlinx.coroutines.Dispatchers.IO) {
                        kotlinx.coroutines.delay(delayMs)
                        // Only retry if nobody else re-registered in the meantime
                        if (registrationListener == null) {
                            doRegisterNsdService(serviceInfo, attempt + 1, maxAttempts)
                        }
                    }
                } else {
                    FileLogger.e(TAG, "NSD registration gave up after $maxAttempts attempts — device not discoverable via NSD. Use 'Restart Server' in Settings to try again.")
                }
            }

            override fun onServiceUnregistered(arg0: android.net.nsd.NsdServiceInfo) {
                FileLogger.d(TAG, "NSD service unregistered")
            }

            override fun onUnregistrationFailed(si: android.net.nsd.NsdServiceInfo, errorCode: Int) {
                FileLogger.e(TAG, "NSD unregistration failed: $errorCode")
            }
        }

        nsdManager.registerService(
            serviceInfo,
            android.net.nsd.NsdManager.PROTOCOL_DNS_SD,
            registrationListener
        )
    }

    private fun startServer() {
        if (webSocketServer != null) return  // Already running from a previous onStartCommand
        scope.launch(kotlinx.coroutines.Dispatchers.IO) {
            val port = pairingStore.serverPort.first()
            val ip = getLocalIpAddress(applicationContext) ?: "unknown"

            val tlsDir = java.io.File(filesDir, "tls").also { it.mkdirs() }
            webSocketServer = WebSocketServer(
                port = port,
                isTokenAuthorized = { token -> pairingStore.isTokenAuthorized(token) },
                onPairingApproved = { deviceName, deviceUUID ->
                    val newToken = java.util.UUID.randomUUID().toString()
                    pairingStore.addAuthorizedToken(newToken)
                    pairingStore.addPairedDevice(
                        com.playbridge.player.model.PairedDevice(
                            id = java.util.UUID.randomUUID().toString(),
                            name = deviceName,
                            deviceUUID = deviceUUID,
                            token = newToken
                        )
                    )
                    newToken
                },
                tlsDir = tlsDir,
                // Persist and advertise only after Java-WebSocket confirms the listener
                // is bound. The SRV port and wss_port must describe the same live endpoint.
                onWssReady = { actualPort, logsPort ->
                    scope.launch {
                        pairingStore.setServerPort(actualPort)
                        _serverInfo.value = ServerInfo(ip = ip, port = actualPort, token = "")
                        registerNsdService(actualPort, actualPort, logsPort)
                    }
                },
                // Resolved per auth so a GeckoView plugin installed later is picked up
                // on the next (re)connect without restarting the server.
                capabilities = { TvCapabilityProvider.current(this@ServerService) },
            ).also { server ->
                server.start()

                // Observe connection state for notification updates and expose to UI
                launch {
                    server.connectionState.collect { state ->
                        try {
                            updateNotification(state)
                            _connectionState.value = state

                            when (state) {
                                is WebSocketServer.ConnectionState.Connected -> overlayWindow.show()
                                is WebSocketServer.ConnectionState.Running,
                                is WebSocketServer.ConnectionState.Stopped -> overlayWindow.hide()
                                is WebSocketServer.ConnectionState.Error -> {
                                    // Delay hiding the overlay window to allow error feedback to be displayed in the foreground
                                    launch {
                                        delay(3000)
                                        if (server.connectionState.value !is WebSocketServer.ConnectionState.Connected) {
                                            overlayWindow.hide()
                                        }
                                    }
                                }
                                else -> Unit
                            }

                            // PairedDevice is recorded by onPairingApproved when pairing completes.
                        } catch (e: Exception) {
                            FileLogger.e(TAG, "connectionState collector crashed on state: $state", e)
                        }
                    }
                }

                // Observe connected client count
                launch {
                    server.connectedClientCount.collect { count ->
                        try {
                            _connectedClientCount.value = count
                        } catch (e: Exception) {
                            FileLogger.e(TAG, "connectedClientCount collector crashed", e)
                        }
                    }
                }

                // Forward pendingPairingRequest to the static flow for MainActivity to observe.
                launch {
                    server.pendingPairingRequest.collect { request ->
                        _pendingPairingRequest.value = request
                    }
                }

                // Expose commands for the activity to observe
                launch {
                    server.commands.collect { command ->
                        try {
                            handleMessage(command)
                        } catch (e: Exception) {
                            FileLogger.e(TAG, "commands collector crashed on command", e)
                        }
                    }
                }

                // When the phone sends request_pairing, bring the app to the foreground showing
                // PairingScreen so the user can read the PIN before typing it on the phone.
                launch {
                    var lastPairingLaunchMs = 0L
                    val pairingCooldownMs = 8_000L  // ignore repeat signals within 8 s

                    server.connectionAttemptFlow.collect {
                        try {
                            val now = System.currentTimeMillis()

                            // ── Spam guard ──────────────────────────────────────────────────────────
                            if (now - lastPairingLaunchMs < pairingCooldownMs) {
                                FileLogger.d(TAG, "request_pairing ignored — cooldown active (${now - lastPairingLaunchMs} ms ago)")
                                return@collect
                            }

                            // ── Context guard ────────────────────────────────────────────────────────
                            when (activeContext) {
                                "player", "external_player" -> {
                                    FileLogger.d(TAG, "request_pairing ignored — video is playing")
                                    return@collect
                                }
                                "browser" -> {
                                    FileLogger.d(TAG, "request_pairing ignored — browser is active")
                                    return@collect
                                }
                            }

                            lastPairingLaunchMs = now

                            overlayWindow.show()
                            val intent = Intent(applicationContext, MainActivity::class.java).apply {
                                action = ACTION_OPEN_PAIRING
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                            }
                            launchActivityFromBackground(intent, "New device connecting — showing pairing screen")
                        } catch (e: Exception) {
                            FileLogger.e(TAG, "connectionAttemptFlow collector crashed", e)
                        }
                    }
                }
            }

            FileLogger.i(TAG, "Server startup requested at $ip beginning with port $port")
        }
    }

    private fun handleMessage(msg: IncomingMessage) {
        val isDebug = (applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0
        if (isDebug && msg !is IncomingMessage.Mouse) {
            // Incoming payloads can contain authenticated stream URLs and Cookie headers.
            // Keep debug logs structural; individual handlers log safe summaries below.
            logDebugLong(TAG, "Command received: ${msg.javaClass.simpleName}")
        }

        if (msg !is IncomingMessage.Mouse) {
            FileLogger.i(TAG, "=== MESSAGE RECEIVED ===")
            FileLogger.i(TAG, "Message type: ${msg.javaClass.simpleName}")
        }

        when (msg) {
            is IncomingMessage.Browser -> {
                val url = msg.payload.url
                val phoneBrowserMode = msg.payload.browser_mode ?: "webview"
                val tvBrowserMode = getSharedPreferences("browser_prefs", Context.MODE_PRIVATE)
                    .getString("browser_mode_override", "phone") ?: "phone"
                val browserMode = tvBrowserMode.takeIf { it == "webview" || it == "gecko" }
                    ?: phoneBrowserMode
                val desktopMode = msg.payload.desktop_mode ?: false
                // Cold-start case: a freshly launched browser reads its UA override straight
                // from prefs (live changes while already open go via ACTION_USER_AGENT_CHANGED).
                val userAgentValue = getSharedPreferences("browser_prefs", Context.MODE_PRIVATE)
                    .getString(KEY_ACTIVE_UA_VALUE, "") ?: ""

                FileLogger.i(TAG, "Browser command: ${redactUrlForLog(url)} (mode: $browserMode)")

                val useGecko = browserMode == "gecko"

                if (useGecko) {
                    if (isGeckoApkInstalled()) {
                        activeContext = "browser_external"
                        broadcastContext()
                        val browserIntent = Intent("com.playbridge.player.ACTION_BROWSER").apply {
                            setPackage("com.playbridge.geckoview.plugin")
                            putExtra("extra_url", url)
                            putExtra("extra_browser_mode", browserMode)
                            putExtra("extra_desktop_mode", desktopMode)
                            putExtra(EXTRA_USER_AGENT_VALUE, userAgentValue)
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                        }
                        launchActivityFromBackground(browserIntent, "Opening GeckoView plugin")
                    } else {
                        FileLogger.w(TAG, "GeckoView requested but plugin not installed. Falling back to internal WebView.")
                        scope.launch {
                            try {
                                webSocketServer?.broadcastStatus(
                                    "{\"type\":\"browser_fallback\",\"message\":\"GeckoView engine not installed. Using System WebView.\"}"
                                )
                            } catch (e: Exception) {
                                FileLogger.e(TAG, "Failed to send browser fallback status", e)
                            }
                        }

                        activeContext = "browser"
                        broadcastContext()
                        val browserIntent = Intent("com.playbridge.player.ACTION_BROWSER_INTERNAL").apply {
                            setPackage(packageName)
                            putExtra("extra_url", url)
                            putExtra("extra_desktop_mode", desktopMode)
                            putExtra(EXTRA_USER_AGENT_VALUE, userAgentValue)
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                        }
                        launchActivityFromBackground(browserIntent, "Opening internal WebView")
                    }
                } else {
                    activeContext = "browser"
                    broadcastContext()
                    val browserIntent = Intent("com.playbridge.player.ACTION_BROWSER_INTERNAL").apply {
                        setPackage(packageName)
                        putExtra("extra_url", url)
                        putExtra("extra_desktop_mode", desktopMode)
                        putExtra(EXTRA_USER_AGENT_VALUE, userAgentValue)
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                    }
                    launchActivityFromBackground(browserIntent, "Opening internal WebView")
                }
            }
            is IncomingMessage.Control -> {
                FileLogger.i(TAG, "Control command: ${msg.payload.command}")
                val targetPlayerEngine = activePlayerEngine
                if (msg.payload.command == "stop") {
                    activeContext = "idle"
                    broadcastContext()
                }
                val intent = Intent(ACTION_CONTROL).apply {
                    putExtra(EXTRA_COMMAND, msg.payload.command)
                    putExtra(EXTRA_TARGET_PLAYER_ENGINE, targetPlayerEngine)
                    setPackage(packageName)
                }
                sendBroadcast(intent)
                if (msg.payload.command == "stop") activePlayerEngine = null
            }
            is IncomingMessage.Remote -> {
                FileLogger.i(TAG, "Remote command: ${msg.payload.key}")
                val remoteKey = msg.payload.key
                if (remoteKey == "volume_up" || remoteKey == "volume_down" || remoteKey == "mute") {
                    // System media volume — handle centrally so it works in every context.
                    val am = getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
                    val direction = when (remoteKey) {
                        "volume_up" -> android.media.AudioManager.ADJUST_RAISE
                        "volume_down" -> android.media.AudioManager.ADJUST_LOWER
                        else -> android.media.AudioManager.ADJUST_TOGGLE_MUTE
                    }
                    am.adjustStreamVolume(
                        android.media.AudioManager.STREAM_MUSIC,
                        direction,
                        android.media.AudioManager.FLAG_SHOW_UI
                    )
                } else if (activeContext == "browser_external") {
                    val browserIntent = Intent(ACTION_REMOTE).apply {
                        putExtra(EXTRA_REMOTE_KEY, msg.payload.key)
                        setPackage("com.playbridge.geckoview.plugin")
                    }
                    sendBroadcast(browserIntent)
                } else {
                    val intent = Intent(ACTION_REMOTE).apply {
                        putExtra(EXTRA_REMOTE_KEY, msg.payload.key)
                        putExtra(EXTRA_TARGET_PLAYER_ENGINE, activePlayerEngine)
                        setPackage(packageName)
                    }
                    sendBroadcast(intent)
                }
            }
            is IncomingMessage.Mouse -> {
                val intent = Intent(ACTION_MOUSE).apply {
                    putExtra(EXTRA_MOUSE_EVENT, msg.payload.event)
                    putExtra(EXTRA_MOUSE_DX, msg.payload.dx)
                    putExtra(EXTRA_MOUSE_DY, msg.payload.dy)
                }

                when (activeContext) {
                    "browser_external" -> {
                        intent.setPackage("com.playbridge.geckoview.plugin")
                        sendBroadcast(intent)
                    }
                    "browser", "player" -> {
                        intent.putExtra(EXTRA_TARGET_PLAYER_ENGINE, activePlayerEngine)
                        intent.setPackage(packageName)
                        sendBroadcast(intent)
                    }
                    // "idle" or any unknown context: nothing on screen to receive mouse
                    // events; dropping rather than waking both packages.
                }
            }
            is IncomingMessage.BrowserControl -> {
                FileLogger.i(TAG, "Browser control: ${msg.payload.action}")
                if (activeContext == "browser_external") {
                    val browserIntent = Intent(ACTION_BROWSER_CONTROL).apply {
                        putExtra(EXTRA_BROWSER_ACTION, msg.payload.action)
                        setPackage("com.playbridge.geckoview.plugin")
                    }
                    sendBroadcast(browserIntent)
                } else {
                    val intent = Intent(ACTION_BROWSER_CONTROL).apply {
                        putExtra(EXTRA_BROWSER_ACTION, msg.payload.action)
                        setPackage(packageName)
                    }
                    sendBroadcast(intent)
                }
            }
            is IncomingMessage.ContextQuery -> {
                FileLogger.i(TAG, "Context query - responding with: $activeContext")
                scope.launch {
                    webSocketServer?.broadcastStatus(createContextJson(activeContext))
                }
                // Ask the running player to re-broadcast playlist/tracks/status so a
                // freshly (re)connected phone can repopulate its remote screen — these
                // are otherwise only sent on change events.
                sendBroadcast(Intent(ACTION_RESYNC).apply {
                    setPackage(packageName)
                    putExtra(EXTRA_TARGET_PLAYER_ENGINE, activePlayerEngine)
                })
            }
            is IncomingMessage.Playlist -> {
                val payload = msg.payload
                FileLogger.i(TAG, "=== PLAYLIST COMMAND === (${payload.items.size} items, startIndex: ${payload.start_index})")

                val prefs = getSharedPreferences("browser_prefs", Context.MODE_PRIVATE)
                val tvPref = prefs.getString("player_mode", "phone") ?: "phone"

                activeContext = "player"
                broadcastContext()

                // Single source of truth for the launch intent (sets PlaylistStore, picks the
                // engine, maps every per-item extra). History replay builds the exact same
                // intent from the stored payload — see PlayerLauncher.
                val playerIntent = com.playbridge.player.player.PlayerLauncher
                    .buildPlayerIntent(this, payload, tvPref)
                val previousPlayerEngine = activePlayerEngine
                val targetPlayerEngine = playerIntent
                    .getStringExtra(com.playbridge.player.player.PlayerHostActivity.EXTRA_RENDERER)
                    ?.takeIf { it == "mpv" || it == "exo" }
                    ?: "exo"
                activePlayerEngine = targetPlayerEngine
                pendingQueueItems.clear() // discard any stale items from a previous session
                val launchReason = "Playing playlist (${payload.items.size} items)"
                if (!com.playbridge.player.player.PlayerHostActivity.isActive() &&
                    previousPlayerEngine == "mpv" &&
                    MpvProcess.isRunning(this)
                ) {
                    // One-time migration/rollback boundary: a legacy MPV Activity can still own
                    // the private process when no permanent host exists yet. Once the host is
                    // active, replacement intents go straight to it and it owns renderer swaps.
                    launchAfterMpvProcessExit(playerIntent, launchReason)
                } else {
                    launchActivityFromBackground(playerIntent, launchReason)
                }
            }
            is IncomingMessage.QueueAdd -> {
                val item = msg.payload.item
                FileLogger.i(TAG, "=== QUEUE_ADD === title: ${item?.title}")
                if (item != null) {
                    // Buffer the item so the player can drain it even if its receiver isn't registered yet.
                    // The broadcast acts only as a wake signal — the player always reads from pendingQueueItems.
                    pendingQueueItems.add(item)
                    sendBroadcast(Intent(ACTION_QUEUE_ADD).apply {
                        setPackage(packageName)
                        putExtra(EXTRA_TARGET_PLAYER_ENGINE, activePlayerEngine)
                    })
                }
            }
            is IncomingMessage.PlaylistJump -> {
                FileLogger.i(TAG, "=== PLAYLIST_JUMP === index: ${msg.payload.index}")
                val intent = Intent(ACTION_PLAYLIST_JUMP).apply {
                    putExtra(EXTRA_PLAYLIST_JUMP_INDEX, msg.payload.index)
                    putExtra(EXTRA_TARGET_PLAYER_ENGINE, activePlayerEngine)
                    setPackage(packageName)
                }
                sendBroadcast(intent)
            }
            is IncomingMessage.Ping -> {
                // Handled by WebSocketServer
            }
            is IncomingMessage.PairingRequest,
            is IncomingMessage.PairingCommit,
            is IncomingMessage.PairingChallenge,
            is IncomingMessage.PairingReveal,
            is IncomingMessage.PairingConfirmation,
            is IncomingMessage.Auth -> {
                // Handled inside WebSocketServer's auth loop — should never reach here.
            }
            is IncomingMessage.UserScript -> {
                // Persist a user-supplied browser script (e.g. the opt-in ad-skipper we
                // don't ship) into the app's external files dir, where SystemWebViewEngine
                // picks up any *.js on the next page load. Blank content uninstalls it.
                // Sanitise the name to a bare *.js filename — no path traversal.
                val safeName = msg.name.substringAfterLast('/').substringAfterLast('\\')
                    .filter { it.isLetterOrDigit() || it == '.' || it == '_' || it == '-' }
                    .ifBlank { "user.js" }
                    .let { if (it.endsWith(".js")) it else "$it.js" }
                try {
                    val file = java.io.File(getExternalFilesDir(null), safeName)
                    if (msg.content.isBlank()) {
                        val removed = file.delete()
                        FileLogger.i(TAG, "User script uninstall: $safeName removed=$removed")
                    } else {
                        file.writeText(msg.content)
                        FileLogger.i(TAG, "User script installed: $safeName (${msg.content.length} chars)")
                    }
                } catch (e: Exception) {
                    FileLogger.w(TAG, "Failed to write user script $safeName: ${e.message}")
                }
                broadcastUserScripts()   // let the phone's manager refresh
            }
            is IncomingMessage.UserScriptQuery -> broadcastUserScripts()
            is IncomingMessage.UserAgent -> {
                val prefs = getSharedPreferences("browser_prefs", Context.MODE_PRIVATE)
                when {
                    msg.name.isBlank() -> {
                        // "Default" selected on the phone — clear the active override only;
                        // saved entries are untouched so they're still pickable later.
                        prefs.edit().remove(KEY_ACTIVE_UA_NAME).remove(KEY_ACTIVE_UA_VALUE).apply()
                        FileLogger.i(TAG, "TV user agent reset to default")
                    }
                    msg.value.isBlank() -> {
                        // Remove a saved entry by name; fall back to default if it was active.
                        saveSavedUserAgents(prefs, loadSavedUserAgents(prefs).filterNot { it.first == msg.name })
                        if (prefs.getString(KEY_ACTIVE_UA_NAME, null) == msg.name) {
                            prefs.edit().remove(KEY_ACTIVE_UA_NAME).remove(KEY_ACTIVE_UA_VALUE).apply()
                        }
                        FileLogger.i(TAG, "TV user agent removed: ${msg.name}")
                    }
                    else -> {
                        if (msg.save) {
                            val updated = loadSavedUserAgents(prefs).filterNot { it.first == msg.name } + (msg.name to msg.value)
                            saveSavedUserAgents(prefs, updated)
                        }
                        prefs.edit()
                            .putString(KEY_ACTIVE_UA_NAME, msg.name)
                            .putString(KEY_ACTIVE_UA_VALUE, msg.value)
                            .apply()
                        FileLogger.i(TAG, "TV user agent set: ${msg.name} (save=${msg.save})")
                    }
                }
                applyUserAgentLive(prefs)
                broadcastUserAgents(prefs)
            }
            is IncomingMessage.UserAgentQuery ->
                broadcastUserAgents(getSharedPreferences("browser_prefs", Context.MODE_PRIVATE))
            is IncomingMessage.Unknown -> {
                FileLogger.w(TAG, "Unknown message: ${msg.type}. Raw: ${msg.raw}")
            }
        }
    }

    private fun broadcastContext() {
        scope.launch {
            webSocketServer?.broadcastStatus(createContextJson(activeContext))
        }
    }

    /** Send the phone the names of installed user scripts (the *.js in external files dir). */
    private fun broadcastUserScripts() {
        val names = try {
            getExternalFilesDir(null)?.listFiles { f -> f.isFile && f.name.endsWith(".js") }
                ?.map { it.name }?.sorted() ?: emptyList()
        } catch (e: Exception) {
            FileLogger.w(TAG, "Failed to list user scripts: ${e.message}")
            emptyList()
        }
        scope.launch {
            webSocketServer?.broadcastStatus(
                com.playbridge.shared.protocol.createUserScriptsJson(names)
            )
        }
    }

    private fun loadSavedUserAgents(prefs: android.content.SharedPreferences): List<Pair<String, String>> {
        return try {
            val arr = org.json.JSONArray(prefs.getString(KEY_SAVED_UAS, "[]") ?: "[]")
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                o.getString("name") to o.getString("value")
            }
        } catch (e: Exception) {
            FileLogger.w(TAG, "Failed to load saved user agents: ${e.message}")
            emptyList()
        }
    }

    private fun saveSavedUserAgents(prefs: android.content.SharedPreferences, entries: List<Pair<String, String>>) {
        val arr = org.json.JSONArray()
        entries.forEach { (name, value) ->
            arr.put(org.json.JSONObject().apply { put("name", name); put("value", value) })
        }
        prefs.edit().putString(KEY_SAVED_UAS, arr.toString()).apply()
    }

    /** Push the active UA to whichever browser is currently on screen, if any. */
    private fun applyUserAgentLive(prefs: android.content.SharedPreferences) {
        val value = prefs.getString(KEY_ACTIVE_UA_VALUE, "") ?: ""
        val pkg = when (activeContext) {
            "browser_external" -> "com.playbridge.geckoview.plugin"
            "browser" -> packageName
            // "idle"/"player": nothing on screen to update live — the next browser launch
            // reads prefs fresh (see the Browser handler above).
            else -> return
        }
        val intent = Intent(ACTION_USER_AGENT_CHANGED).apply {
            putExtra(EXTRA_USER_AGENT_VALUE, value)
            setPackage(pkg)
        }
        sendBroadcast(intent)
    }

    /** Tell the phone's manager the TV's saved user agents + which one (if any) is active. */
    private fun broadcastUserAgents(prefs: android.content.SharedPreferences) {
        val active = prefs.getString(KEY_ACTIVE_UA_NAME, "") ?: ""
        val entries = loadSavedUserAgents(prefs)
        scope.launch {
            webSocketServer?.broadcastStatus(
                com.playbridge.shared.protocol.createUserAgentsJson(active, entries)
            )
        }
    }

    /**
     * Broadcast playlist status to connected phone clients.
     * Called by player activities via the static helper.
     */
    internal fun broadcastPlaylistStatusInternal(statusJson: String) {
        scope.launch {
            webSocketServer?.broadcastStatus(statusJson)
        }
    }

    internal fun setContextPlayerInternal(engine: String? = null) {
        activeContext = "player"
        if (engine != null) activePlayerEngine = engine
        broadcastContext()
        FileLogger.d(TAG, "activeContext set to player by activity lifecycle (engine=${activePlayerEngine ?: "unknown"})")
    }

    internal fun setContextBrowserInternal() {
        activeContext = "browser"
        broadcastContext()
        FileLogger.d(TAG, "activeContext set to browser by activity lifecycle")
    }

    /**
     * Reset to idle, but only if the current context is one the caller owns. This
     * prevents a torn-down activity from clobbering the context of whatever took its
     * place (e.g. browser->player handoff: the browser's onDestroy must not flip the
     * now-playing context back to idle). Pass null to force-reset unconditionally.
     */
    internal fun setContextIdleInternal(
        onlyIfOneOf: Set<String>? = null,
        onlyIfEngine: String? = null,
    ) {
        if (onlyIfOneOf != null && activeContext !in onlyIfOneOf) {
            FileLogger.d(TAG, "idle reset skipped; activeContext=$activeContext not owned by caller")
            return
        }
        if (onlyIfEngine != null && activePlayerEngine != onlyIfEngine) {
            FileLogger.d(
                TAG,
                "idle reset skipped; activePlayerEngine=${activePlayerEngine ?: "none"} not owned by $onlyIfEngine",
            )
            return
        }
        activeContext = "idle"
        activePlayerEngine = null
        broadcastContext()
        FileLogger.d(TAG, "activeContext reset to idle by activity lifecycle")
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
            FileLogger.i(TAG, "startActivity succeeded for: $description")
            return // startActivity worked — do not also fire the notification, which would
                   // deliver a second intent to the already-running activity via onNewIntent.
        } catch (e: Exception) {
            FileLogger.w(TAG, "startActivity failed, falling back to fullScreenIntent: ${e.message}")
        }

        // Attempt 2: fullScreenIntent notification — only reached if startActivity threw.
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

    private fun isGeckoApkInstalled(): Boolean {
        return try {
            packageManager.getPackageInfo("com.playbridge.geckoview.plugin", 0)
            true
        } catch (e: android.content.pm.PackageManager.NameNotFoundException) {
            false
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        // Drop the static reference so the dead service (cancelled scope, stopped server)
        // stops receiving routed calls and doesn't leak as a retained Context. Identity
        // check: a replacement instance may already have registered itself in onCreate.
        if (_staticInstance === this) _staticInstance = null
        try { unregisterReceiver(contextIdleReceiver) } catch (_: Exception) {}
        try { unregisterReceiver(playerProcessEventReceiver) } catch (_: Exception) {}
        pendingPlayerLaunchAfterMpvExit?.let(mainHandler::removeCallbacks)
        pendingPlayerLaunchAfterMpvExit = null
        if (registrationListener != null) {
            try {
                nsdManager.unregisterService(registrationListener)
            } catch (e: IllegalArgumentException) {
                // Ignore if service is not registered
            }
            registrationListener = null
        }
        webSocketServer?.stop()
        webSocketServer = null
        // Remove overlay window if still visible
        overlayWindow.hide()
        // Cancel scope after stopping server
        scope.cancel()
        super.onDestroy()
    }

    private fun logDebugLong(tag: String, message: String) {
        val maxLogSize = 2000
        var i = 0
        while (i < message.length) {
            val end = minOf(i + maxLogSize, message.length)
            Log.d(tag, message.substring(i, end))
            i += maxLogSize
        }
    }

    companion object {
        const val ACTION_PLAY = "com.playbridge.player.ACTION_PLAY"
        const val ACTION_BROWSER = "com.playbridge.player.ACTION_BROWSER"
        const val ACTION_CONTROL = "com.playbridge.player.ACTION_CONTROL"
        const val ACTION_REMOTE = "com.playbridge.player.ACTION_REMOTE"
        const val ACTION_MOUSE = "com.playbridge.player.ACTION_MOUSE"
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
        const val ACTION_BROWSER_CONTROL = "com.playbridge.player.ACTION_BROWSER_CONTROL"
        const val EXTRA_BROWSER_ACTION = "browser_action"
        // Live UA update to whichever browser (internal WebView or GeckoView plugin) is open.
        const val ACTION_USER_AGENT_CHANGED = "com.playbridge.player.ACTION_USER_AGENT_CHANGED"
        const val EXTRA_USER_AGENT_VALUE = "extra_user_agent_value"
        const val EXTRA_PLAYLIST = "playlist"
        const val EXTRA_IS_PLAYLIST = "is_playlist"
        const val EXTRA_PLAYLIST_INDEX = "playlist_index"
        const val EXTRA_PREFERRED_AUDIO_LANG = "preferred_audio_lang"
        const val EXTRA_PREFERRED_SUBTITLE_LANG = "preferred_subtitle_lang"
        const val EXTRA_EXTERNAL_SUBTITLE_URL = "external_subtitle_url"
        const val EXTRA_MAX_BITRATE_CAP_MBPS = "max_bitrate_cap_mbps" // Double: max ABR bitrate cap for ExoPlayer
        const val EXTRA_SKIP_PREPLAY = "skip_preplay"
        const val ACTION_QUEUE_ADD = "com.playbridge.player.ACTION_QUEUE_ADD"
        const val ACTION_PLAYLIST_JUMP = "com.playbridge.player.ACTION_PLAYLIST_JUMP"
        // Asks the running player to re-broadcast its now-playing snapshot
        // (playlist/tracks/status) — used to re-sync a freshly (re)connected phone.
        const val ACTION_RESYNC = "com.playbridge.player.ACTION_RESYNC"
        // Sent to MainActivity (via startActivity) to navigate to the PairingScreen.
        // Fired whenever a new device starts a connection attempt while the app is backgrounded.
        const val ACTION_OPEN_PAIRING = "com.playbridge.player.ACTION_OPEN_PAIRING"
        // Sent (as an explicit broadcast to this package) by the tv/browser app when its
        // BrowserActivity is destroyed, so ServerService can reset activeContext to "idle".
        const val ACTION_CONTEXT_IDLE = "com.playbridge.player.ACTION_CONTEXT_IDLE"
        const val ACTION_PLAYER_PROCESS_EVENT = "com.playbridge.player.ACTION_PLAYER_PROCESS_EVENT"
        const val EXTRA_PLAYER_PROCESS_EVENT = "player_process_event"
        const val EXTRA_PLAYER_STATUS_JSON = "player_status_json"
        const val EXTRA_PLAYER_LAUNCH_INTENT = "player_launch_intent"
        const val EXTRA_TARGET_PLAYER_ENGINE = "target_player_engine"
        private const val PLAYER_PROCESS_EVENT_STATUS = "status"
        private const val PLAYER_PROCESS_EVENT_CONTEXT_PLAYER = "context_player"
        private const val PLAYER_PROCESS_EVENT_CONTEXT_BROWSER = "context_browser"
        private const val PLAYER_PROCESS_EVENT_CONTEXT_IDLE = "context_idle"
        private const val PLAYER_PROCESS_EVENT_LAUNCH_PLAYER = "launch_player"
        const val EXTRA_QUEUE_ITEM_URL = "queue_item_url"
        const val EXTRA_QUEUE_ITEM_TITLE = "queue_item_title"
        const val EXTRA_QUEUE_ITEM_CONTENT_TYPE = "queue_item_content_type"
        const val EXTRA_QUEUE_ITEM_DETECTED_BY = "queue_item_detected_by"
        const val EXTRA_PLAYLIST_JUMP_INDEX = "playlist_jump_index"
        const val EXTRA_CONTENT_PAYLOAD = "content_payload"
        const val EXTRA_VISUAL_METADATA = "visual_metadata"
        const val EXTRA_START_POSITION = "extra_start_position"

        // Static flow for UI to observe connection state
        private val _connectionState = MutableStateFlow<WebSocketServer.ConnectionState>(WebSocketServer.ConnectionState.Stopped)
        val connectionState: StateFlow<WebSocketServer.ConnectionState> = _connectionState.asStateFlow()

        // Static flow for UI to observe connected client count
        private val _connectedClientCount = MutableStateFlow(0)
        val connectedClientCount: StateFlow<Int> = _connectedClientCount.asStateFlow()

        // Static flow exposing a pending pairing request so MainActivity can show Allow/Deny UI.
        private val _pendingPairingRequest = MutableStateFlow<WebSocketServer.PairingRequest?>(null)
        val pendingPairingRequest: StateFlow<WebSocketServer.PairingRequest?> = _pendingPairingRequest.asStateFlow()

        fun denyPairing() { _staticInstance?.webSocketServer?.denyPairing() }

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

        /**
         * Broadcast playlist_status to the phone from a player activity.
         */
        fun broadcastPlaylistStatus(statusJson: String) {
            _staticInstance?.broadcastPlaylistStatusInternal(statusJson)
        }

        fun broadcastPlaylistStatus(context: Context, statusJson: String) {
            val instance = _staticInstance
            if (instance != null) {
                instance.broadcastPlaylistStatusInternal(statusJson)
            } else {
                sendPlayerProcessEvent(context, PLAYER_PROCESS_EVENT_STATUS, statusJson = statusJson)
            }
        }

        /**
         * Broadcast playback status (state/position/duration/title) to the phone
         * from a player activity. Reuses the generic JSON forwarder.
         */
        fun broadcastStatus(statusJson: String) {
            _staticInstance?.broadcastPlaylistStatusInternal(statusJson)
        }

        fun broadcastStatus(context: Context, statusJson: String) {
            val instance = _staticInstance
            if (instance != null) {
                instance.broadcastPlaylistStatusInternal(statusJson)
            } else {
                sendPlayerProcessEvent(context, PLAYER_PROCESS_EVENT_STATUS, statusJson = statusJson)
            }
        }

        /**
         * Mark activeContext as "player" when a PlayerActivity starts.
         * Called from PlayerActivity.onCreate() so that videos launched directly from the TV
         * (history/favourites screen) are treated the same as phone-cast videos — the
         * request_pairing context guard will block the PairingScreen while they're playing.
         */
        fun notifyContextPlayer() {
            _staticInstance?.setContextPlayerInternal()
        }

        fun notifyContextPlayer(context: Context, engine: String) {
            val instance = _staticInstance
            if (instance != null) {
                instance.setContextPlayerInternal(engine)
            } else {
                sendPlayerProcessEvent(
                    context,
                    PLAYER_PROCESS_EVENT_CONTEXT_PLAYER,
                    engine = engine,
                )
            }
        }

        /** Mark activeContext as "browser" — called from the internal WebView's onResume. */
        fun notifyContextBrowser() {
            _staticInstance?.setContextBrowserInternal()
        }

        fun notifyContextBrowser(context: Context) {
            val instance = _staticInstance
            if (instance != null) {
                instance.setContextBrowserInternal()
            } else {
                sendPlayerProcessEvent(context, PLAYER_PROCESS_EVENT_CONTEXT_BROWSER)
            }
        }

        /**
         * Reset activeContext to "idle" from a player or browser activity when it finishes.
         * Called from PlayerActivity.onDestroy() and (via broadcast) from the TV browser app.
         * Without this, the context guard in the request_pairing handler would permanently block
         * the PairingScreen after the first playback session ends.
         */
        fun notifyContextIdle() {
            // Player-side callers (PlayerActivity/PrePlay teardown): only clear if a
            // player still owns the context, so a browser opened afterwards survives.
            _staticInstance?.setContextIdleInternal(setOf("player"))
        }

        fun notifyContextIdle(context: Context, engine: String) {
            val instance = _staticInstance
            if (instance != null) {
                instance.setContextIdleInternal(setOf("player"), onlyIfEngine = engine)
            } else {
                sendPlayerProcessEvent(
                    context,
                    PLAYER_PROCESS_EVENT_CONTEXT_IDLE,
                    engine = engine,
                )
            }
        }

        fun launchPlayerFromPrivateProcess(context: Context, intent: Intent) {
            val instance = _staticInstance
            if (instance != null) {
                instance.launchActivityFromBackground(intent, "Launching replacement player")
            } else {
                sendPlayerProcessEvent(
                    context,
                    PLAYER_PROCESS_EVENT_LAUNCH_PLAYER,
                    playerLaunchIntent = intent,
                )
            }
        }

        private fun sendPlayerProcessEvent(
            context: Context,
            event: String,
            statusJson: String? = null,
            engine: String? = null,
            playerLaunchIntent: Intent? = null,
        ) {
            context.applicationContext.sendBroadcast(
                Intent(ACTION_PLAYER_PROCESS_EVENT).apply {
                    setPackage(context.packageName)
                    putExtra(EXTRA_PLAYER_PROCESS_EVENT, event)
                    statusJson?.let { putExtra(EXTRA_PLAYER_STATUS_JSON, it) }
                    engine?.let { putExtra(EXTRA_TARGET_PLAYER_ENGINE, it) }
                    playerLaunchIntent?.let { putExtra(EXTRA_PLAYER_LAUNCH_INTENT, it) }
                },
            )
        }

        @Volatile
        private var _staticInstance: ServerService? = null

        /**
         * Items buffered here when queue_add arrives before the player's receiver is registered.
         * The player drains this after registering, and on each ACTION_QUEUE_ADD broadcast.
         */
        val pendingQueueItems = java.util.concurrent.ConcurrentLinkedQueue<playbridge.PlayPayload>()

        /**
         * Atomically drain and return all pending queue items.
         */
        fun drainPendingQueueItems(): List<playbridge.PlayPayload> {
            val items = mutableListOf<playbridge.PlayPayload>()
            while (true) items.add(pendingQueueItems.poll() ?: break)
            return items
        }

        fun drainPendingQueueItems(context: Context): List<playbridge.PlayPayload> {
            if (_staticInstance != null) return drainPendingQueueItems()
            return PlayerProcessBridgeProvider.drainPendingQueue(context)
        }
    }
}
