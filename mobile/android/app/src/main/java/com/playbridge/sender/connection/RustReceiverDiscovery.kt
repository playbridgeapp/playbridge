package com.playbridge.sender.connection

import android.content.Context
import android.net.wifi.WifiManager
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import com.playbridge.sender.cast.dlna.DeviceDescription
import com.playbridge.sender.cast.dlna.DlnaProxyHolder
import com.playbridge.sender.model.CastProtocol
import com.playbridge.sender.model.EndpointKey
import com.playbridge.sender.model.ReceiverEndpoint
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.ConcurrentHashMap

/** Minimal JNI discovery surface; avoids shipping JNA on Android. */
internal object RustDiscoveryNative {
    init {
        try {
            System.loadLibrary("playbridge_cast_core_ffi")
        } catch (e: Throwable) {
            Log.w("RustDiscoveryNative", "Native library playbridge_cast_core_ffi unavailable: ${e.message}")
        }
    }

    external fun start(protocolMask: Int, timeoutMs: Long): Long
    external fun nextEvent(handle: Long, waitMs: Long): String?
    external fun cancel(handle: Long)
    external fun free(handle: Long)
}

data class RustDiscoverySummary(
    val playBridgeDevices: Int,
    val dlnaDevices: Int,
    val rokuDevices: Int,
    val googleCastDevices: Int,
    val errors: Int,
)

/**
 * Runs Rust discovery (mDNS + DLNA SSDP + Roku ECP + Google Cast mDNS) using native Rust cast-core engine.
 * Emits protocol-qualified receivers via [discoveredEndpoints].
 *
 * Results are sticky across rescans: starting a new window seeds from the last known map and never
 * flashes an empty list. Entries age out after [STICKY_TTL_MS] without a fresh sighting.
 */
internal class RustReceiverDiscovery(context: Context) {
    private val wifiManager = context.applicationContext.getSystemService(WifiManager::class.java)
    private var worker: Job? = null
    private val activeHandle = AtomicLong(0L)
    private var nativeUnavailableLogged = false
    private val descriptionParser = DeviceDescription(DlnaProxyHolder.httpClient)
    private val descriptionCache = ConcurrentHashMap<String, DeviceDescription.Renderer>()
    private val descriptionsInFlight = ConcurrentHashMap.newKeySet<String>()

    /** Process-lifetime discovery cache keyed by [EndpointKey] string. */
    private val stickyEndpoints = ConcurrentHashMap<String, StickyEndpoint>()

    private val _discoveredEndpoints = MutableStateFlow<List<ReceiverEndpoint>>(emptyList())
    val discoveredEndpoints: StateFlow<List<ReceiverEndpoint>> = _discoveredEndpoints.asStateFlow()

    fun start(
        scope: CoroutineScope,
        timeoutMs: Long,
        protocols: Set<CastProtocol> = CastProtocol.entries.toSet(),
        onFinished: ((RustDiscoverySummary) -> Unit)? = null,
    ) {
        stop()
        // Keep prior results visible while the new scan fills in — Web Video Caster style.
        publishSticky()
        val multicastLock = acquireMulticastLock()
        val handle = try {
            RustDiscoveryNative.start(
                protocols.fold(0) { mask, protocol -> mask or protocol.discoveryMask },
                timeoutMs
            )
        } catch (error: LinkageError) {
            releaseMulticastLock(multicastLock)
            if (!nativeUnavailableLogged) {
                Log.w(TAG, "Rust discovery unavailable: ${error.javaClass.simpleName}")
                nativeUnavailableLogged = true
            }
            return
        }
        if (handle == 0L) {
            releaseMulticastLock(multicastLock)
            Log.w(TAG, "Rust discovery worker could not start")
            return
        }
        activeHandle.set(handle)
        val scanJob = scope.launch(Dispatchers.IO) {
            // Description enrichment runs in child IO coroutines while discovery continues.
            // Keep the authoritative map concurrent so a later mDNS/SSDP event cannot replace an
            // enriched DLNA endpoint with the earlier description-only snapshot.
            val deviceMap = ConcurrentHashMap<String, ReceiverEndpoint>()
            stickyEndpoints.forEach { (key, sticky) -> deviceMap[key] = sticky.endpoint }
            val receivers = mutableMapOf<String, MutableSet<String>>()
            var errors = 0
            val deadline = SystemClock.elapsedRealtime() + timeoutMs + FINISH_GRACE_MS
            try {
                while (isActive && SystemClock.elapsedRealtime() < deadline) {
                    val event = RustDiscoveryNative.nextEvent(handle, EVENT_WAIT_MS) ?: continue
                    val json = JSONObject(event)
                    when (json.optString("event")) {
                        "found", "updated" -> {
                            val receiver = json.optJSONObject("receiver") ?: continue
                            val protocolStr = receiver.optString("protocol")
                            val id = receiver.optString("id")
                            if (id.isNotEmpty()) receivers.getOrPut(protocolStr) { mutableSetOf() }.add(id)

                            val parsed = parseDiscoveredDevice(receiver)
                            if (parsed != null) {
                                val key = parsed.key.toString()
                                deviceMap[key] = parsed
                                rememberSticky(key, parsed)
                                publishMap(deviceMap)
                                if (parsed.protocol == CastProtocol.DLNA &&
                                    parsed.descriptionUrl != null && parsed.controlUrl == null &&
                                    descriptionsInFlight.add(parsed.descriptionUrl)
                                ) {
                                    val location = parsed.descriptionUrl
                                    launch(Dispatchers.IO) {
                                        try {
                                            val description = descriptionParser.fetch(location)
                                            if (description != null) {
                                                descriptionCache[location] = description
                                                deviceMap.computeIfPresent(key) { _, endpoint ->
                                                    if (endpoint.descriptionUrl == location) {
                                                        endpoint.copy(
                                                            name = description.friendlyName,
                                                            controlUrl = description.avTransportControlUrl,
                                                            renderingControlUrl = description.renderingControlControlUrl,
                                                        )
                                                    } else {
                                                        endpoint
                                                    }
                                                }
                                                deviceMap[key]?.let { rememberSticky(key, it) }
                                                publishMap(deviceMap)
                                            }
                                        } finally {
                                            descriptionsInFlight.remove(location)
                                        }
                                    }
                                }
                            }
                        }
                        "error" -> errors++
                    }
                }
            } catch (error: LinkageError) {
                Log.w(TAG, "Rust discovery stopped: ${error.javaClass.simpleName}")
            } finally {
                pruneSticky()
                publishSticky()
                onFinished?.invoke(
                    RustDiscoverySummary(
                        playBridgeDevices = receivers["PlayBridge"]?.size ?: receivers["playbridge"]?.size ?: 0,
                        dlnaDevices = receivers["Dlna"]?.size ?: receivers["dlna"]?.size ?: 0,
                        rokuDevices = receivers["Roku"]?.size ?: receivers["roku"]?.size ?: 0,
                        googleCastDevices = receivers["GoogleCast"]?.size ?: receivers["google_cast"]?.size ?: 0,
                        errors = errors,
                    )
                )
            }
        }
        // Completion handlers run even when a newly-created coroutine is cancelled before its
        // body starts. That guarantees both the raw Rust handle and multicast lock are released.
        scanJob.invokeOnCompletion {
            activeHandle.compareAndSet(handle, 0L)
            try {
                RustDiscoveryNative.cancel(handle)
                RustDiscoveryNative.free(handle)
            } catch (error: LinkageError) {
                Log.w(TAG, "Rust discovery cleanup failed: ${error.javaClass.simpleName}")
            } finally {
                releaseMulticastLock(multicastLock)
            }
        }
        worker = scanJob
    }

    fun stop() {
        worker?.cancel()
        worker = null
        val handle = activeHandle.getAndSet(0L)
        if (handle != 0L) {
            try {
                RustDiscoveryNative.cancel(handle)
            } catch (error: LinkageError) {
                Log.w(TAG, "Rust discovery cleanup failed: ${error.javaClass.simpleName}")
            }
        }
        // Leave sticky results published so the UI does not collapse when a scan window ends.
        pruneSticky()
        publishSticky()
    }

    private data class StickyEndpoint(
        val endpoint: ReceiverEndpoint,
        val lastSeenElapsedMs: Long,
    )

    private fun rememberSticky(key: String, endpoint: ReceiverEndpoint) {
        stickyEndpoints[key] = StickyEndpoint(endpoint, SystemClock.elapsedRealtime())
    }

    private fun pruneSticky() {
        val now = SystemClock.elapsedRealtime()
        stickyEndpoints.entries.removeIf { now - it.value.lastSeenElapsedMs > STICKY_TTL_MS }
    }

    private fun publishSticky() {
        _discoveredEndpoints.value = stickyEndpoints.values.map { it.endpoint }
    }

    private fun publishMap(deviceMap: ConcurrentHashMap<String, ReceiverEndpoint>) {
        _discoveredEndpoints.value = deviceMap.values.toList()
    }

    private fun parseDiscoveredDevice(receiver: JSONObject): ReceiverEndpoint? {
        val name = receiver.optString("name").ifEmpty { "TV Receiver" }
        val addresses = receiver.optJSONArray("addresses")
        val addressList = buildList {
            if (addresses != null) {
                for (index in 0 until addresses.length()) {
                    addresses.optString(index).takeIf(String::isNotBlank)?.let(::add)
                }
            }
        }.distinct()
        val orderedAddresses = addressList.sortedBy { it.contains(':') }
        val ip = orderedAddresses.firstOrNull().orEmpty()
        if (ip.isEmpty()) return null

        val port = receiver.optInt("port", 0)
        val wssPort = if (receiver.has("wss_port") && !receiver.isNull("wss_port")) receiver.optInt("wss_port") else null
        val location = receiver.optString("location").takeIf { it.isNotEmpty() }
        val uuid = receiver.optString("uuid").ifEmpty { receiver.optString("id") }

        val protoStr = receiver.optString("protocol")
        val protocol = when (protoStr.lowercase(java.util.Locale.ROOT)) {
            "dlna" -> CastProtocol.DLNA
            "roku" -> CastProtocol.ROKU
            "googlecast", "google_cast" -> CastProtocol.GOOGLE_CAST
            else -> CastProtocol.PLAYBRIDGE
        }
        val effectivePort = when {
            port > 0 -> port
            protocol == CastProtocol.ROKU -> 8060
            protocol == CastProtocol.GOOGLE_CAST -> 8009
            else -> 0
        }
        val description = location?.takeIf { protocol == CastProtocol.DLNA }
            ?.let(descriptionCache::get)

        return ReceiverEndpoint(
            key = EndpointKey(protocol, uuid.ifEmpty { "$ip:$effectivePort" }),
            name = description?.friendlyName ?: name,
            addresses = orderedAddresses,
            port = effectivePort.takeIf { it > 0 },
            wssPort = wssPort,
            logsPort = if (receiver.has("logs_port") && !receiver.isNull("logs_port")) {
                receiver.optInt("logs_port")
            } else {
                null
            },
            descriptionUrl = location.takeIf { protocol == CastProtocol.DLNA },
            controlUrl = description?.avTransportControlUrl,
            renderingControlUrl = description?.renderingControlControlUrl,
        )
    }

    private fun acquireMulticastLock(): WifiManager.MulticastLock? = try {
        wifiManager?.createMulticastLock(MULTICAST_LOCK_TAG)?.apply {
            setReferenceCounted(false)
            acquire()
        }
    } catch (error: RuntimeException) {
        Log.w(TAG, "Could not acquire multicast lock: ${error.javaClass.simpleName}")
        null
    }

    private fun releaseMulticastLock(lock: WifiManager.MulticastLock?) {
        if (lock?.isHeld == true) runCatching { lock.release() }
    }

    companion object {
        const val TAG = "RustReceiverDiscovery"
        const val PROTOCOL_PLAYBRIDGE = 1
        const val PROTOCOL_DLNA = 2
        const val PROTOCOL_ROKU = 4
        const val PROTOCOL_GOOGLE_CAST = 16
        const val EVENT_WAIT_MS = 250L
        const val FINISH_GRACE_MS = 1_000L
        /** Keep last-seen devices across rescans so the list never flashes empty. */
        const val STICKY_TTL_MS = 180_000L
        const val MULTICAST_LOCK_TAG = "playbridge:rust-discovery"
    }
}
