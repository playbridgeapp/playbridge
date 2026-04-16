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
import androidx.core.app.NotificationCompat
import com.playbridge.player.MainActivity
import com.playbridge.player.R
import com.playbridge.protocol.Command
import com.playbridge.protocol.createContextJson
import com.playbridge.player.pairing.PairingStore
import com.playbridge.player.model.PairedDevice
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import java.net.Inet4Address
import java.net.NetworkInterface
import okhttp3.Request

private const val TAG = "ServerService"
private const val CHANNEL_ID = "playbridge_server"
private const val CHANNEL_ID_LAUNCH = "playbridge_launch"
private const val NOTIFICATION_ID = 1
private const val NOTIFICATION_ID_LAUNCH = 2

class ServerService : Service() {

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var webSocketServer: WebSocketServer? = null
    private var bluetoothServer: BluetoothServer? = null
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

    // Receives ACTION_CONTEXT_IDLE from the tv/browser app (separate APK) when its
    // BrowserActivity is destroyed, so activeContext can be reset to "idle".
    private val contextIdleReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: android.content.Context, intent: Intent) {
            if (intent.action == ACTION_CONTEXT_IDLE) {
                setContextIdleInternal()
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        _staticInstance = this
        pairingStore = PairingStore(applicationContext)
        overlayWindow = OverlayWindowHelper(applicationContext)
        nsdManager = getSystemService(Context.NSD_SERVICE) as android.net.nsd.NsdManager

        // Initialize Stremio client with cache
        com.playbridge.player.stremio.StremioClient.init(applicationContext)

        createNotificationChannel()
        val filter = android.content.IntentFilter(ACTION_CONTEXT_IDLE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(contextIdleReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(contextIdleReceiver, filter)
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

    private fun registerNsdService(port: Int) {
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
                serviceType = com.playbridge.protocol.NsdConstants.SERVICE_TYPE
                setPort(port)
                setAttribute("uuid", deviceId)
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
            val token = pairingStore.getOrCreateToken()
            val port = pairingStore.serverPort.first()
            val ip = getLocalIpAddress(applicationContext) ?: "unknown"

            bluetoothServer = BluetoothServer(applicationContext) { command ->
                handleCommand(command)
            }.also { server ->
                server.start()
            }

            val subtitleDir = java.io.File(cacheDir, "subtitles").also { it.mkdirs() }
            webSocketServer = WebSocketServer(port = port, authToken = token, subtitleDir = subtitleDir).also { server ->
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

                // When the phone sends request_pairing, bring the app to the foreground showing
                // PairingScreen so the user can read the PIN before typing it on the phone.
                launch {
                    var lastPairingLaunchMs = 0L
                    val pairingCooldownMs = 8_000L  // ignore repeat signals within 8 s

                    server.connectionAttemptFlow.collect {
                        val now = System.currentTimeMillis()

                        // ── Spam guard ──────────────────────────────────────────────────────────
                        // Ignore if we launched the pairing screen very recently. This covers
                        // rapid taps on the phone or AuthFailed retries that fire quickly.
                        if (now - lastPairingLaunchMs < pairingCooldownMs) {
                            FileLogger.d(TAG, "request_pairing ignored — cooldown active (${now - lastPairingLaunchMs} ms ago)")
                            return@collect
                        }

                        // ── Context guard ────────────────────────────────────────────────────────
                        // Don't interrupt active playback or browsing. The phone still shows the
                        // PIN dialog so the user can pair manually once they're done watching.
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

                        // Raise the invisible overlay window BEFORE calling startActivity().
                        // Without this, Android 14+ BAL restrictions block the launch because
                        // connectionState is still Running (not Connected) at this point.
                        // The connectionState observer hides the overlay if auth ultimately fails.
                        overlayWindow.show()
                        val intent = Intent(applicationContext, MainActivity::class.java).apply {
                            action = ACTION_OPEN_PAIRING
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                        }
                        launchActivityFromBackground(intent, "New device connecting — showing pairing screen")
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

                com.playbridge.player.player.PlaylistStore.currentPlaylist = null

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
                val useExternalMpv = finalMode == "external_mpv"

                if (useExternalMpv) {
                    activeContext = "external_player"
                    broadcastContext()

                    // Download subtitles on the TV side (with the video's headers) before launching
                    // MPV. Serving them via the local HTTP server guarantees MPV can access them
                    // regardless of auth, redirects, or SSL quirks on the original URL.
                    scope.launch(kotlinx.coroutines.Dispatchers.IO) {
                        val subs = command.subtitles
                        val localSubUrls = if (!subs.isNullOrEmpty()) {
                            val serverPort = _serverInfo.value?.port
                            val localFiles = downloadSubtitlesToCache(subs, command.headers)
                            if (localFiles.isNotEmpty() && serverPort != null) {
                                localFiles.map { "http://127.0.0.1:$serverPort/subtitle/${it.name}" }
                            } else subs // fallback to original URLs if download failed
                        } else emptyList()

                        val intent = Intent(Intent.ACTION_VIEW).apply {
                            setClassName("is.xyz.mpv", "is.xyz.mpv.MPVActivity")
                            data = android.net.Uri.parse(command.url)

                            command.title?.let { title ->
                                putExtra(Intent.EXTRA_TITLE, title)
                                putExtra("title", title)
                            }

                            if (localSubUrls.size == 1) {
                                putExtra("sub", localSubUrls[0])
                            } else if (localSubUrls.size > 1) {
                                putExtra("sub-files", localSubUrls.joinToString("\n"))
                            }

                            val hdrs = command.headers
                            if (!hdrs.isNullOrEmpty()) {
                                val headersStr = hdrs.map { (key, value) ->
                                    val escapedValue = value.replace(",", "\\,")
                                    "$key: $escapedValue"
                                }.joinToString(",")
                                putExtra("http-header-fields", headersStr)
                            }

                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                        }

                        launchActivityFromBackground(intent, "Playing media in MPV")
                    }
                } else if (useExternalPlayer) {
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

                    val activityClass = when (finalMode) {
                        "internal_vlc" -> com.playbridge.player.player.VlcPlayerActivity::class.java
                        "internal_mpv" -> com.playbridge.player.player.MpvPlayerActivity::class.java
                        else           -> com.playbridge.player.player.ExoPlayerActivity::class.java
                    }

                    val playerIntent = Intent(this, activityClass).apply {
                        putExtra(EXTRA_URL, command.url)
                        putExtra(EXTRA_TITLE, command.title)
                        putExtra(EXTRA_CONTENT_TYPE, command.contentType)
                        command.detectedBy?.let { detectedBy ->
                            putExtra(EXTRA_DETECTED_BY, detectedBy)
                        }
                        command.subtitles?.let { subtitles ->
                            putStringArrayListExtra(EXTRA_SUBTITLES, ArrayList(subtitles))
                        }
                        command.headers?.let { headers ->
                            putExtra(EXTRA_HEADERS, HashMap(headers))
                        }
                        command.preferredAudioLanguage?.let { lang ->
                            putExtra(EXTRA_PREFERRED_AUDIO_LANG, lang)
                        }
                        command.preferredSubtitleLanguage?.let { lang ->
                            putExtra(EXTRA_PREFERRED_SUBTITLE_LANG, lang)
                        }
                        command.defaultVideoQuality?.let { quality ->
                            putExtra("default_video_quality", quality)
                        }
                        command.maxBitrateCapMbps?.let { cap ->
                            putExtra(EXTRA_MAX_BITRATE_CAP_MBPS, cap)
                        }
                        command.seriesContext?.let { seriesContext ->
                            // Serialize SeriesContext to JSON string for Intent transport.
                            // Deserialized in ExoPlayerActivity.handleIntent().
                            val json = com.playbridge.protocol.protocolJson.encodeToString(
                                com.playbridge.protocol.SeriesContext.serializer(),
                                seriesContext
                            )
                            putExtra(EXTRA_SERIES_CONTEXT, json)
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
                val browserIntent = Intent("com.playbridge.player.ACTION_BROWSER").apply {
                    putExtra("extra_url", command.url)
                    putExtra("extra_browser_mode", command.browserMode)
                    putExtra("extra_desktop_mode", command.desktopMode ?: false)
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
                // Also broadcast to browser app
                val browserIntent = Intent(ACTION_REMOTE).apply {
                    putExtra(EXTRA_REMOTE_KEY, command.key)
                    setPackage("com.playbridge.browser")
                }
                sendBroadcast(browserIntent)
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
                // Also broadcast to browser app
                val browserIntent = Intent(ACTION_MOUSE).apply {
                    putExtra(EXTRA_MOUSE_EVENT, command.event)
                    putExtra(EXTRA_MOUSE_DX, command.dx)
                    putExtra(EXTRA_MOUSE_DY, command.dy)
                    setPackage("com.playbridge.browser")
                }
                sendBroadcast(browserIntent)
            }
            is Command.BrowserControl -> {
                FileLogger.i(TAG, "Browser control: ${command.action}")
                val intent = Intent(ACTION_BROWSER_CONTROL).apply {
                    putExtra(EXTRA_BROWSER_ACTION, command.action)
                    setPackage(packageName)
                }
                sendBroadcast(intent)
                // Also broadcast to browser app
                val browserIntent = Intent(ACTION_BROWSER_CONTROL).apply {
                    putExtra(EXTRA_BROWSER_ACTION, command.action)
                    setPackage("com.playbridge.browser")
                }
                sendBroadcast(browserIntent)
            }
            is Command.ContextQuery -> {
                FileLogger.i(TAG, "Context query - responding with: $activeContext")
                scope.launch {
                    webSocketServer?.broadcastStatus(createContextJson(activeContext))
                }
            }
            is Command.Playlist -> {
                FileLogger.i(TAG, "=== PLAYLIST COMMAND === (${command.items.size} items, startIndex: ${command.startIndex})")

                // Serialize playlist items as JSON for the intent
                com.playbridge.player.player.PlaylistStore.currentPlaylist = command.items

                val prefs = getSharedPreferences("browser_prefs", Context.MODE_PRIVATE)
                val tvPref = prefs.getString("player_mode", "phone") ?: "phone"
                val playlistMode = if (tvPref == "phone") {
                    val phoneMode = command.items.firstOrNull()?.playerMode
                    if (phoneMode != null && phoneMode != "tv") phoneMode else "internal"
                } else {
                    tvPref
                }

                activeContext = if (playlistMode == "external_mpv") "external_player" else "player"
                broadcastContext()

                val activityClass = if (playlistMode == "internal_vlc") {
                    com.playbridge.player.player.VlcPlayerActivity::class.java
                } else {
                    com.playbridge.player.player.ExoPlayerActivity::class.java
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
                        if (firstItem.maxBitrateCapMbps != null) {
                            putExtra(EXTRA_MAX_BITRATE_CAP_MBPS, firstItem.maxBitrateCapMbps)
                        }
                        firstItem.seriesContext?.let { seriesContext ->
                            val json = com.playbridge.protocol.protocolJson.encodeToString(
                                com.playbridge.protocol.SeriesContext.serializer(),
                                seriesContext
                            )
                            putExtra(EXTRA_SERIES_CONTEXT, json)
                        }
                    }
                    putExtra(EXTRA_IS_PLAYLIST, true)
                    putExtra(EXTRA_PLAYLIST_INDEX, command.startIndex)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                }
                pendingQueueItems.clear() // discard any stale items from a previous session
                launchActivityFromBackground(playerIntent, "Playing playlist (${command.items.size} items)")
            }
            is Command.QueueAdd -> {
                FileLogger.i(TAG, "=== QUEUE_ADD === title: ${command.item.title}")
                // Buffer the item so the player can drain it even if its receiver isn't registered yet.
                // The broadcast acts only as a wake signal — the player always reads from pendingQueueItems.
                pendingQueueItems.add(command.item)
                sendBroadcast(Intent(ACTION_QUEUE_ADD).setPackage(packageName))
            }
            is Command.PlaylistJump -> {
                FileLogger.i(TAG, "=== PLAYLIST_JUMP === index: ${command.index}")
                val intent = Intent(ACTION_PLAYLIST_JUMP).apply {
                    putExtra(EXTRA_PLAYLIST_JUMP_INDEX, command.index)
                    setPackage(packageName)
                }
                sendBroadcast(intent)
            }
            is Command.Ping -> {
                // Handled by WebSocketServer
            }
            is Command.RequestPairing -> {
                // Handled in WebSocketServer's auth loop before this point — should never arrive here
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
     * Broadcast playlist status to connected phone clients.
     * Called by player activities via the static helper.
     */
    internal fun broadcastPlaylistStatusInternal(statusJson: String) {
        scope.launch {
            webSocketServer?.broadcastStatus(statusJson)
        }
    }

    internal fun setContextPlayerInternal() {
        activeContext = "player"
        broadcastContext()
        FileLogger.d(TAG, "activeContext set to player by activity lifecycle")
    }

    internal fun setContextIdleInternal() {
        activeContext = "idle"
        broadcastContext()
        FileLogger.d(TAG, "activeContext reset to idle by activity lifecycle")
    }

    /**
     * Downloads subtitle URLs to the local subtitle cache directory using the provided headers.
     * Returns the list of successfully downloaded [File]s in the same order as [subtitleUrls].
     */
    private fun downloadSubtitlesToCache(subtitleUrls: List<String>, headers: Map<String, String>?): List<java.io.File> {
        val subtitleDir = java.io.File(cacheDir, "subtitles").also { it.mkdirs() }
        val sniffer = com.playbridge.player.player.ContentSniffer()
        val client = sniffer.getOkHttpClient(headers, trustAllCerts = subtitleUrls.firstOrNull()?.let { sniffer.isLocalUrl(it) } ?: false)
        return subtitleUrls.mapIndexedNotNull { index, url ->
            try {
                val ext = if (url.contains(".vtt", ignoreCase = true)) "vtt" else "srt"
                val file = java.io.File(subtitleDir, "sub_$index.$ext")
                val request = Request.Builder().url(url).build()
                client.newCall(request).execute().use { response ->
                    if (response.isSuccessful) {
                        file.writeBytes(response.body!!.bytes())
                        FileLogger.i(TAG, "Downloaded subtitle [$index] → ${file.name}")
                        file
                    } else {
                        FileLogger.e(TAG, "Subtitle download failed: $url (HTTP ${response.code})")
                        null
                    }
                }
            } catch (e: Exception) {
                FileLogger.e(TAG, "Subtitle download error: $url", e)
                null
            }
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

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        try { unregisterReceiver(contextIdleReceiver) } catch (_: Exception) {}
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
        bluetoothServer?.stop()
        bluetoothServer = null
        // Remove overlay window if still visible
        overlayWindow.hide()
        // Cancel scope after stopping server
        scope.cancel()
        super.onDestroy()
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
        const val EXTRA_PLAYLIST = "playlist"
        const val EXTRA_IS_PLAYLIST = "is_playlist"
        const val EXTRA_PLAYLIST_INDEX = "playlist_index"
        const val EXTRA_PREFERRED_AUDIO_LANG = "preferred_audio_lang"
        const val EXTRA_PREFERRED_SUBTITLE_LANG = "preferred_subtitle_lang"
        const val EXTRA_EXTERNAL_SUBTITLE_URL = "external_subtitle_url"
        const val EXTRA_VIDEO_FILTER = "video_filter"
        const val EXTRA_CUSTOM_FILTER_VALUES = "custom_filter_values"
        const val EXTRA_SERIES_CONTEXT = "series_context"        // JSON-encoded SeriesContext
        const val EXTRA_MAX_BITRATE_CAP_MBPS = "max_bitrate_cap_mbps" // Double: max ABR bitrate cap for ExoPlayer
        const val ACTION_QUEUE_ADD = "com.playbridge.player.ACTION_QUEUE_ADD"
        const val ACTION_PLAYLIST_JUMP = "com.playbridge.player.ACTION_PLAYLIST_JUMP"
        // Sent to MainActivity (via startActivity) to navigate to the PairingScreen.
        // Fired whenever a new device starts a connection attempt while the app is backgrounded.
        const val ACTION_OPEN_PAIRING = "com.playbridge.player.ACTION_OPEN_PAIRING"
        // Sent (as an explicit broadcast to this package) by the tv/browser app when its
        // BrowserActivity is destroyed, so ServerService can reset activeContext to "idle".
        const val ACTION_CONTEXT_IDLE = "com.playbridge.player.ACTION_CONTEXT_IDLE"
        const val EXTRA_QUEUE_ITEM_URL = "queue_item_url"
        const val EXTRA_QUEUE_ITEM_TITLE = "queue_item_title"
        const val EXTRA_QUEUE_ITEM_CONTENT_TYPE = "queue_item_content_type"
        const val EXTRA_QUEUE_ITEM_DETECTED_BY = "queue_item_detected_by"
        const val EXTRA_PLAYLIST_JUMP_INDEX = "playlist_jump_index"

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

        /**
         * Broadcast playlist_status to the phone from a player activity.
         */
        fun broadcastPlaylistStatus(statusJson: String) {
            _staticInstance?.broadcastPlaylistStatusInternal(statusJson)
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

        /**
         * Reset activeContext to "idle" from a player or browser activity when it finishes.
         * Called from PlayerActivity.onDestroy() and (via broadcast) from the TV browser app.
         * Without this, the context guard in the request_pairing handler would permanently block
         * the PairingScreen after the first playback session ends.
         */
        fun notifyContextIdle() {
            _staticInstance?.setContextIdleInternal()
        }

        @Volatile
        private var _staticInstance: ServerService? = null

        /**
         * Items buffered here when queue_add arrives before the player's receiver is registered.
         * The player drains this after registering, and on each ACTION_QUEUE_ADD broadcast.
         */
        val pendingQueueItems = java.util.concurrent.ConcurrentLinkedQueue<com.playbridge.protocol.PlayPayload>()

        /**
         * Atomically drain and return all pending queue items.
         */
        fun drainPendingQueueItems(): List<com.playbridge.protocol.PlayPayload> {
            val items = mutableListOf<com.playbridge.protocol.PlayPayload>()
            while (true) items.add(pendingQueueItems.poll() ?: break)
            return items
        }
    }
}
