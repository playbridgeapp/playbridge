package com.playbridge.sender.cast.googlecast

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.SystemClock
import android.util.Log
import androidx.core.net.toUri
import com.playbridge.sender.cast.Capability
import com.playbridge.sender.cast.CastTarget
import com.playbridge.sender.cast.MediaItem
import com.playbridge.sender.cast.PlaybackState
import com.playbridge.sender.cast.PlaybackStatus
import com.playbridge.sender.cast.TargetKind
import com.playbridge.sender.cast.dlna.DlnaProxyHolder
import com.playbridge.sender.model.TvDevice
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.net.InetAddress

/**
 * [CastTarget] backed by Cast Core's single-owner Google Cast session worker.
 * Selecting the device launches or joins the configured receiver application;
 * loading media is a separate operation after the TV reaches its ready splash.
 *
 * Media is served through the shared [com.playbridge.sender.cast.dlna.LocalProxyServer]
 * so the Chromecast can reach streams that require request headers (Referer, Cookie, UA)
 * and local files (content://) that are only accessible from the phone.
 */
class GoogleCastTarget(
    val device: TvDevice,
    private val scope: CoroutineScope,
    private val context: Context,
) : CastTarget {

    override val id: String = device.uuid.ifEmpty { "cast://${device.ip}:${device.port}" }
    override val name: String = device.name
    override val kind: TargetKind = TargetKind.GOOGLE_CAST

    override val capabilities: Set<Capability> = setOf(
        Capability.LOAD,
        Capability.PLAY_PAUSE,
        Capability.SEEK,
        Capability.STOP,
        Capability.VOLUME,
        Capability.NOW_PLAYING,
    )

    private val connectionMutex = Mutex()
    private val monitorLock = Any()
    private val _status = MutableStateFlow(PlaybackStatus(PlaybackState.BUFFERING))
    private var pollJob: Job? = null
    private var connectionAttempt = 0

    @Volatile
    private var client: RustCastSessionClient? = null

    @Volatile
    private var released = false

    @Volatile
    private var receiverEnded = false

    /** Initial selection is CLI-like: never inherit an unknown receiver-app session. */
    @Volatile
    private var forceRelaunchPending = true

    /** Called immediately on selection so the TV can show its ready splash before LOAD. */
    fun connectReady() {
        if (released) return
        Log.d(
            TAG,
            "connectReady target=${device.name} endpoint=${device.ip}:${castPort()} " +
                "receiverEnded=$receiverEnded",
        )
        _status.value = PlaybackStatus(PlaybackState.BUFFERING)
        // The monitor owns the initial attempt as well as all retries. release() can
        // therefore cancel an in-flight connect instead of waiting for its timeout.
        startMonitoring()
    }

    /**
     * Return a ready client, replacing the entire Android adapter and native worker for
     * every failed attempt. No deferreds, command queue, socket, or Cast session survive
     * into a reconnect.
     */
    private suspend fun ensureConnected(
        forceRelaunch: Boolean = receiverEnded || forceRelaunchPending,
    ): RustCastSessionClient? = connectionMutex.withLock {
        if (released) {
            Log.d(TAG, "Ignoring ensureConnected for released target ${device.name}")
            return@withLock null
        }
        client?.takeIf { it.isReady }?.let {
            Log.v(TAG, "Reusing ready Google Cast client for ${device.name}")
            return@withLock it
        }

        val staleClient = client
        client = null
        if (staleClient != null) {
            Log.d(TAG, "Discarding non-ready Google Cast client before reconnect")
            withContext(NonCancellable) { staleClient.reset() }
        }

        val attempt = ++connectionAttempt
        val replacement = RustCastSessionClient(scope, attempt)
        client = replacement
        val startedAt = SystemClock.elapsedRealtime()
        Log.d(
            TAG,
            "Starting fresh Google Cast session #$attempt for ${device.name} " +
                "endpoint=${device.ip}:${castPort()} forceRelaunch=$forceRelaunch " +
                "receiverEnded=$receiverEnded",
        )
        try {
            withContext(Dispatchers.IO) {
                replacement.connect(
                    device.ip,
                    castPort(),
                    forceRelaunch = forceRelaunch,
                    networkHandle = localNetworkHandle(),
                )
            }
            if (released) {
                if (client === replacement) client = null
                withContext(NonCancellable) { replacement.reset() }
                null
            } else {
                receiverEnded = false
                forceRelaunchPending = false
                Log.d(
                    TAG,
                    "Google Cast session #$attempt is ready for ${device.name} " +
                        "after ${elapsedSince(startedAt)}ms",
                )
                replacement
            }
        } catch (error: CancellationException) {
            if (client === replacement) client = null
            withContext(NonCancellable) { replacement.reset() }
            Log.d(
                TAG,
                "Google Cast session #$attempt cancelled after ${elapsedSince(startedAt)}ms",
            )
            throw error
        } catch (error: Exception) {
            if (client === replacement) client = null
            withContext(NonCancellable) { replacement.reset() }
            if (error is GoogleCastReceiverEndedException) receiverEnded = true
            // If a reuse attempt could not establish a usable session, the next
            // attempt must stop/launch the receiver exactly like the CLI.
            if (!forceRelaunch || error is GoogleCastSessionInvalidException) {
                forceRelaunchPending = true
            }
            Log.w(
                TAG,
                "Google Cast session #$attempt failed for ${device.name} " +
                    "after ${elapsedSince(startedAt)}ms " +
                    "forceRelaunch=$forceRelaunch receiverEnded=$receiverEnded: " +
                    "${error.javaClass.simpleName}: ${error.message}",
                error,
            )
            if (error is GoogleCastLocalNetworkUnavailableException) throw error
            null
        }
    }

    private fun requireReadyClient(): RustCastSessionClient =
        checkNotNull(client?.takeIf { it.isReady }) { "Google Cast receiver is not ready" }

    override suspend fun load(media: MediaItem) {
        val loadStartedAt = SystemClock.elapsedRealtime()
        _status.value = PlaybackStatus(PlaybackState.BUFFERING)
        Log.d(
            TAG,
            "LOAD requested target=${device.name} source=${mediaEndpoint(media.url)} " +
                "contentType=${media.mimeType ?: "unknown"} headers=${media.headers.size} " +
                "receiverEnded=$receiverEnded",
        )
        val connectedClient = try {
            ensureConnected()
        } catch (error: GoogleCastLocalNetworkUnavailableException) {
            _status.value = PlaybackStatus(PlaybackState.ERROR)
            logLocalNetworkUnavailable(error)
            throw error
        }
        if (connectedClient == null) {
            Log.w(TAG, "LOAD aborted: no ready Google Cast client for ${device.name}")
            _status.value = PlaybackStatus(PlaybackState.ERROR)
            return
        }

        // Relay only when the receiver cannot fetch the source itself. Public HTTP(S) media
        // without custom headers goes direct to Cast, avoiding needless phone bandwidth,
        // wake time, and a single point of failure during long playback.
        val proxy = DlnaProxyHolder.proxy(context)
        val proxyUrl = if (media.url.startsWith("content://") || media.url.startsWith("file://")) {
            proxy.publishLocal(media.url.toUri(), media.mimeType)
        } else if (media.headers.isNotEmpty() ||
            (!media.url.startsWith("http://") && !media.url.startsWith("https://"))
        ) {
            proxy.publish(media.url, media.headers, media.mimeType)
        } else {
            media.url
        }
        val route = if (proxyUrl == media.url) "direct" else "phone_proxy"
        Log.d(
            TAG,
            "LOAD route=$route source=${mediaEndpoint(proxyUrl)} " +
                "startMs=${media.startPositionMs}",
        )

        try {
            try {
                loadOnClient(connectedClient, proxyUrl, media)
            } catch (error: GoogleCastSessionInvalidException) {
                // TV Back (or another receiver app replacing ours) is terminal for the
                // old session. A receiver that never acknowledges LOAD is equally
                // unusable. This explicit action gets one completely fresh retry.
                receiverEnded = error is GoogleCastReceiverEndedException
                forceRelaunchPending = true
                Log.w(
                    TAG,
                    "LOAD found invalid receiver session after ${elapsedSince(loadStartedAt)}ms " +
                        "reason=${error.javaClass.simpleName}; discarding old client and " +
                        "force-relaunching once",
                )
                _status.value = PlaybackStatus(PlaybackState.BUFFERING)
                discardClient(connectedClient)
                val freshClient = ensureConnected(forceRelaunch = true)
                    ?: throw IllegalStateException("Could not restart the Google Cast receiver")
                loadOnClient(freshClient, proxyUrl, media)
            }
            _status.value = PlaybackStatus(PlaybackState.PLAYING)
            Log.d(TAG, "LOAD accepted after ${elapsedSince(loadStartedAt)}ms route=$route")
            startMonitoring()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            _status.value = PlaybackStatus(PlaybackState.ERROR)
            Log.e(
                TAG,
                "LOAD failed after ${elapsedSince(loadStartedAt)}ms route=$route: " +
                    "${error.javaClass.simpleName}: ${error.message}",
                error,
            )
            throw error
        }
    }

    private suspend fun loadOnClient(
        connectedClient: RustCastSessionClient,
        proxyUrl: String,
        media: MediaItem,
    ) {
        withContext(Dispatchers.IO) {
            connectedClient.load(
                contentUrl = proxyUrl,
                contentType = media.mimeType,
                title = media.title,
                artUrl = media.artUrl,
                startSeconds = media.startPositionMs / 1000.0,
            )
        }
    }

    override suspend fun play() {
        withContext(Dispatchers.IO) { requireReadyClient().play() }
    }

    override suspend fun pause() {
        withContext(Dispatchers.IO) { requireReadyClient().pause() }
    }

    override suspend fun stop() {
        withContext(Dispatchers.IO) { requireReadyClient().stop() }
        _status.value = PlaybackStatus(PlaybackState.STOPPED)
    }

    override suspend fun seekTo(positionMs: Long) {
        withContext(Dispatchers.IO) { requireReadyClient().seek(positionMs / 1000.0) }
    }

    override suspend fun setVolume(percent: Int) {
        val level = (percent / 100f).coerceIn(0f, 1f)
        withContext(Dispatchers.IO) { requireReadyClient().setVolume(level) }
    }

    suspend fun adjustVolume(delta: Float) {
        val connectedClient = ensureConnected() ?: return
        val level = (connectedClient.volume + delta).coerceIn(0f, 1f)
        withContext(Dispatchers.IO) { connectedClient.setVolume(level) }
    }

    override fun status(): Flow<PlaybackStatus> = _status.asStateFlow()

    override fun release() {
        Log.d(TAG, "release target=${device.name} clientReady=${client?.isReady == true}")
        released = true
        stopMonitoring()
        scope.launch(Dispatchers.IO) {
            connectionMutex.withLock {
                val detached = client
                client = null
                runCatching { detached?.disconnect() }
            }
        }
    }

    // -----------------------------------------------------------------------
    // Polling
    // -----------------------------------------------------------------------

    private fun startMonitoring() {
        synchronized(monitorLock) {
            if (released) {
                Log.v(TAG, "Monitor not started: target released")
                return
            }
            if (pollJob?.isActive == true) {
                Log.v(TAG, "Monitor already active for ${device.name}")
                return
            }
            Log.d(TAG, "Starting Google Cast monitor for ${device.name}")
            pollJob = scope.launch(Dispatchers.IO) {
                monitorConnection()
            }
        }
    }

    private suspend fun monitorConnection() {
        var reconnectDelayMs = INITIAL_RECONNECT_DELAY_MS
        var consecutiveStatusFailures = 0
        var lastLoggedState: PlaybackState? = null
        while (scope.isActive && !released) {
            var connectedClient = client?.takeIf { it.isReady }
            if (connectedClient == null) {
                _status.value = PlaybackStatus(PlaybackState.BUFFERING)
                connectedClient = try {
                    ensureConnected()
                } catch (error: GoogleCastLocalNetworkUnavailableException) {
                    _status.value = PlaybackStatus(PlaybackState.ERROR)
                    logLocalNetworkUnavailable(error)
                    return
                }
                if (connectedClient == null) {
                    if (released) return
                    _status.value = PlaybackStatus(PlaybackState.ERROR)
                    Log.w(
                        TAG,
                        "Google Cast connect attempt unavailable; retrying in ${reconnectDelayMs}ms",
                    )
                    delay(reconnectDelayMs)
                    reconnectDelayMs = nextReconnectDelay(reconnectDelayMs)
                    continue
                }
            }

            try {
                val status = connectedClient.status()
                _status.value = PlaybackStatus(
                    state = mapState(status.state),
                    positionMs = (status.positionSeconds * 1000).toLong(),
                    durationMs = (status.durationSeconds * 1000).toLong(),
                )
                if (_status.value.state != lastLoggedState) {
                    lastLoggedState = _status.value.state
                    Log.d(
                        TAG,
                        "Google Cast state=${_status.value.state} " +
                            "positionMs=${_status.value.positionMs} " +
                            "durationMs=${_status.value.durationMs}",
                    )
                }
                reconnectDelayMs = INITIAL_RECONNECT_DELAY_MS
                consecutiveStatusFailures = 0
                delay(POLL_INTERVAL_MS)
            } catch (error: CancellationException) {
                throw error
            } catch (error: GoogleCastReceiverEndedException) {
                Log.i(
                    TAG,
                    "Google Cast receiver exited; discarding session and waiting for the next user cast",
                )
                receiverEnded = true
                forceRelaunchPending = true
                discardClient(connectedClient)
                _status.value = PlaybackStatus(PlaybackState.IDLE)
                return
            } catch (error: Exception) {
                if (googleCastStatusErrorEndsSession(error)) {
                    consecutiveStatusFailures = 0
                    Log.w(
                        TAG,
                        "Cast session is no longer usable; discarding client and retrying in " +
                            "${reconnectDelayMs}ms: ${error.javaClass.simpleName}: ${error.message}",
                        error,
                    )
                    _status.value = PlaybackStatus(PlaybackState.BUFFERING)
                    // A receiver that stopped responding needs a clean application launch.
                    // A transport loss can first rejoin an application that is still alive.
                    forceRelaunchPending = error is GoogleCastSessionUnresponsiveException
                    discardClient(connectedClient)
                } else {
                    consecutiveStatusFailures++
                    val replaceStaleSession =
                        googleCastStatusFailuresRequireFreshSession(consecutiveStatusFailures)
                    if (replaceStaleSession) {
                        Log.w(
                            TAG,
                            "Google Cast status failed $consecutiveStatusFailures times; " +
                                "discarding stale client and forcing a fresh receiver session",
                        )
                        _status.value = PlaybackStatus(PlaybackState.BUFFERING)
                        forceRelaunchPending = true
                        discardClient(connectedClient)
                        consecutiveStatusFailures = 0
                    } else {
                        Log.w(
                            TAG,
                            "Google Cast status temporarily unavailable; keeping client and retrying in " +
                                "${reconnectDelayMs}ms: " +
                                "${error.javaClass.simpleName}: ${error.message}",
                        )
                    }
                }
                delay(reconnectDelayMs)
                reconnectDelayMs = nextReconnectDelay(reconnectDelayMs)
            }
        }
    }

    private suspend fun discardClient(failedClient: RustCastSessionClient) {
        connectionMutex.withLock {
            if (client !== failedClient) {
                Log.v(TAG, "Ignoring discard request for superseded Google Cast client")
                return@withLock
            }
            Log.d(TAG, "Discarding active Google Cast client for ${device.name}")
            client = null
            withContext(NonCancellable) { failedClient.reset() }
        }
    }

    private fun stopMonitoring() {
        val job = synchronized(monitorLock) {
            val current = pollJob
            pollJob = null
            current
        }
        if (job != null) Log.d(TAG, "Stopping Google Cast monitor for ${device.name}")
        job?.cancel()
    }

    private fun castPort(): Int = device.port.takeIf { it > 0 } ?: 8009

    /**
     * Select the physical local network whose routes contain the receiver. Only return an
     * explicit handle when that network differs from this app's active network. Binding an
     * already-Wi-Fi split-tunnel app is redundant and some VPN implementations reject it.
     */
    private fun localNetworkHandle(): Long? {
        val connectivity = context.getSystemService(ConnectivityManager::class.java)
            ?: return null
        val receiverAddress = runCatching { InetAddress.getByName(device.ip) }.getOrNull()
        val candidates = connectivity.allNetworks.mapNotNull { network ->
            val capabilities = connectivity.getNetworkCapabilities(network) ?: return@mapNotNull null
            val localTransport = capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
            if (!localTransport || capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) {
                return@mapNotNull null
            }
            network to capabilities
        }
        val selected = candidates.firstOrNull { (network, _) ->
            receiverAddress != null && connectivity.getLinkProperties(network)
                ?.routes
                ?.any { route -> route.matches(receiverAddress) } == true
        } ?: candidates.firstOrNull()
        val network = selected?.first
        if (network == null) {
            Log.w(TAG, "No physical Wi-Fi/Ethernet network is available for Google Cast")
            return null
        }
        val handle = runCatching { network.networkHandle }
            .onFailure {
                Log.w(TAG, "Could not obtain Android local-network handle: ${it.message}")
            }
            .getOrNull()
            ?.takeIf { it != 0L }
        if (handle == null) {
            Log.d(
                TAG,
                "Google Cast local-network binding=unavailable " +
                    "transport=${localTransportName(selected.second)}",
            )
            return null
        }

        val activeHandle = runCatching { connectivity.activeNetwork?.networkHandle }
            .onFailure {
                Log.w(TAG, "Could not obtain Android active-network handle: ${it.message}")
            }
            .getOrNull()
            ?.takeIf { it != 0L }
        val needsExplicitBinding = googleCastNeedsExplicitNetworkBinding(
            activeNetworkHandle = activeHandle,
            selectedNetworkHandle = handle,
        )
        val activeDescription = when (activeHandle) {
            null -> "unavailable"
            handle -> "selected"
            else -> "different"
        }
        Log.d(
            TAG,
            "Google Cast local-network binding=${if (needsExplicitBinding) "selected" else "not_needed"} " +
                "active=$activeDescription " +
                "transport=${localTransportName(selected.second)}",
        )
        return handle.takeIf { needsExplicitBinding }
    }

    private fun localTransportName(capabilities: NetworkCapabilities): String = when {
        capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
        capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ethernet"
        else -> "unknown"
    }

    private fun logLocalNetworkUnavailable(error: GoogleCastLocalNetworkUnavailableException) {
        Log.e(
            TAG,
            "Google Cast cannot reach ${device.name} on the local network. " +
                "If a VPN is active, allow LAN access or exclude PlayBridge from that VPN: " +
                error.message,
        )
    }

    /** Log only the origin. Paths and query parameters may contain stream credentials. */
    private fun mediaEndpoint(url: String): String {
        val uri = runCatching { url.toUri() }.getOrNull() ?: return "unparseable"
        if (uri.scheme == "content" || uri.scheme == "file") return uri.scheme.orEmpty()
        val port = if (uri.port >= 0) ":${uri.port}" else ""
        return "${uri.scheme ?: "unknown"}://${uri.host.orEmpty()}$port"
    }

    private fun elapsedSince(startedAt: Long): Long =
        SystemClock.elapsedRealtime() - startedAt

    private fun nextReconnectDelay(currentMs: Long): Long =
        (currentMs * 2).coerceAtMost(MAX_RECONNECT_DELAY_MS)

    private fun mapState(s: String): PlaybackState = when (s.uppercase()) {
        "PLAYING" -> PlaybackState.PLAYING
        "PAUSED" -> PlaybackState.PAUSED
        "BUFFERING" -> PlaybackState.BUFFERING
        "STOPPED", "FINISHED", "IDLE" -> PlaybackState.STOPPED
        else -> PlaybackState.IDLE
    }

    companion object {
        private const val TAG = "GoogleCastTarget"
        private const val POLL_INTERVAL_MS = 1000L
        private const val INITIAL_RECONNECT_DELAY_MS = 1_000L
        private const val MAX_RECONNECT_DELAY_MS = 8_000L
    }
}

internal fun googleCastNeedsExplicitNetworkBinding(
    activeNetworkHandle: Long?,
    selectedNetworkHandle: Long,
): Boolean = selectedNetworkHandle != 0L && activeNetworkHandle != selectedNetworkHandle
