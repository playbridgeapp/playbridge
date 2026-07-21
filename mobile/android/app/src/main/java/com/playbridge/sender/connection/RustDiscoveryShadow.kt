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
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicLong

/** Minimal JNI surface; avoids shipping JNA solely for the Rust experiment. */
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

internal data class RustDiscoverySummary(
    val playBridgeDevices: Int,
    val dlnaDevices: Int,
    val rokuDevices: Int,
    val googleCastDevices: Int,
    val errors: Int,
)

/**
 * Runs Rust discovery (mDNS + DLNA SSDP + Roku ECP + Google Cast mDNS) using native Rust cast-core engine.
 * Emits discovered devices via [discoveredDevices] StateFlow.
 */
internal class RustDiscoveryShadow(context: Context) {
    private val wifiManager = context.applicationContext.getSystemService(WifiManager::class.java)
    private var worker: Job? = null
    private val activeHandle = AtomicLong(0L)
    private var nativeUnavailableLogged = false

    private val _discoveredDevices = MutableStateFlow<List<NsdHelper.DiscoveredDevice>>(emptyList())
    val discoveredDevices: StateFlow<List<NsdHelper.DiscoveredDevice>> = _discoveredDevices.asStateFlow()

    fun start(
        scope: CoroutineScope,
        timeoutMs: Long,
        onFinished: ((RustDiscoverySummary) -> Unit)? = null,
    ) {
        stop()
        _discoveredDevices.value = emptyList()
        val multicastLock = acquireMulticastLock()
        val handle = try {
            RustDiscoveryNative.start(
                PROTOCOL_PLAYBRIDGE or PROTOCOL_DLNA or PROTOCOL_ROKU or PROTOCOL_GOOGLE_CAST,
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
        worker = scope.launch(Dispatchers.IO) {
            val deviceMap = mutableMapOf<String, NsdHelper.DiscoveredDevice>()
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
                                deviceMap[parsed.uuid.ifEmpty { parsed.ip }] = parsed
                                _discoveredDevices.value = deviceMap.values.toList()
                            }
                        }
                        "error" -> errors++
                    }
                }
            } catch (error: LinkageError) {
                Log.w(TAG, "Rust discovery stopped: ${error.javaClass.simpleName}")
            } finally {
                try {
                    val currentHandle = activeHandle.getAndSet(0L)
                    if (currentHandle != 0L) {
                        RustDiscoveryNative.cancel(currentHandle)
                        RustDiscoveryNative.free(currentHandle)
                    }
                    onFinished?.invoke(
                        RustDiscoverySummary(
                            playBridgeDevices = receivers["PlayBridge"]?.size ?: receivers["playbridge"]?.size ?: 0,
                            dlnaDevices = receivers["Dlna"]?.size ?: receivers["dlna"]?.size ?: 0,
                            rokuDevices = receivers["Roku"]?.size ?: receivers["roku"]?.size ?: 0,
                            googleCastDevices = receivers["GoogleCast"]?.size ?: receivers["google_cast"]?.size ?: 0,
                            errors = errors,
                        )
                    )
                } finally {
                    releaseMulticastLock(multicastLock)
                }
            }
        }
    }

    fun stop() {
        worker?.cancel()
        worker = null
        val handle = activeHandle.get()
        if (handle != 0L) {
            try {
                RustDiscoveryNative.cancel(handle)
            } catch (error: LinkageError) {
                Log.w(TAG, "Rust discovery cleanup failed: ${error.javaClass.simpleName}")
            }
        }
    }

    private fun parseDiscoveredDevice(receiver: JSONObject): NsdHelper.DiscoveredDevice? {
        val name = receiver.optString("name").ifEmpty { "TV Receiver" }
        val addresses = receiver.optJSONArray("addresses")
        val ip = if (addresses != null && addresses.length() > 0) addresses.getString(0) else ""
        if (ip.isEmpty()) return null

        val port = receiver.optInt("port", 0)
        val wssPort = if (receiver.has("wss_port") && !receiver.isNull("wss_port")) receiver.optInt("wss_port") else null
        val location = receiver.optString("location").takeIf { it.isNotEmpty() }
        val uuid = receiver.optString("uuid").ifEmpty { receiver.optString("id") }

        val protoStr = receiver.optString("protocol")
        val protocol = when (protoStr.lowercase(java.util.Locale.ROOT)) {
            "dlna" -> NsdHelper.TvProtocol.DLNA
            "roku" -> NsdHelper.TvProtocol.ROKU
            "googlecast", "google_cast" -> NsdHelper.TvProtocol.GOOGLE_CAST
            else -> NsdHelper.TvProtocol.PLAYBRIDGE
        }

        return NsdHelper.DiscoveredDevice(
            ip = ip,
            port = if (protocol == NsdHelper.TvProtocol.ROKU && port == 0) 8060 else if (protocol == NsdHelper.TvProtocol.GOOGLE_CAST && port == 0) 8009 else port,
            name = name,
            uuid = uuid,
            wssPort = wssPort,
            protocol = protocol,
            location = location
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
        const val TAG = "RustDiscoveryShadow"
        const val PROTOCOL_PLAYBRIDGE = 1
        const val PROTOCOL_DLNA = 2
        const val PROTOCOL_ROKU = 4
        const val PROTOCOL_GOOGLE_CAST = 16
        const val EVENT_WAIT_MS = 250L
        const val FINISH_GRACE_MS = 1_000L
        const val MULTICAST_LOCK_TAG = "playbridge:rust-discovery"
    }
}
