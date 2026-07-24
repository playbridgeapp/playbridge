package com.playbridge.sender.connection

import android.content.Context
import com.playbridge.sender.model.CastProtocol
import com.playbridge.sender.model.ReceiverEndpoint
import com.playbridge.sender.model.SavedReceiverEndpoint
import com.playbridge.sender.model.TvDevice
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/**
 * Process-wide owner of Rust receiver discovery.
 *
 * Callers register an owner and desired protocols. One native scanner runs for the union of all
 * active requests, which avoids competing mDNS/SSDP sockets and multicast locks.
 */
class ReceiverDiscoveryRepository(
    context: Context,
    private val scope: CoroutineScope,
) {
    private val engine = RustReceiverDiscovery(context)
    private val requests = linkedMapOf<String, Set<CastProtocol>>()
    private var activeProtocols: Set<CastProtocol> = emptySet()

    val endpoints: StateFlow<List<ReceiverEndpoint>> = engine.discoveredEndpoints

    /** Compatibility view while phone call sites migrate from [TvDevice]. */
    val devices: StateFlow<List<TvDevice>> = endpoints
        .map { discovered ->
            discovered.map { endpoint ->
                TvDevice.fromSavedEndpoint(SavedReceiverEndpoint(endpoint = endpoint))
            }
        }
        .stateIn(scope, SharingStarted.Eagerly, emptyList())

    @Synchronized
    fun start(
        owner: String,
        protocols: Set<CastProtocol>,
        timeoutMs: Long,
        onFinished: ((RustDiscoverySummary) -> Unit)? = null,
    ) {
        val selected = protocols.ifEmpty { CastProtocol.entries.toSet() }
        requests[owner] = selected
        val union = requests.values.flatten().toSet()
        activeProtocols = union
        // A prior time-boxed native scan may already have finished even when the owner union is
        // unchanged. Treat every explicit start/rescan as a fresh window while still keeping a
        // single scanner for the union of all owners.
        engine.start(scope, timeoutMs, union, onFinished)
    }

    @Synchronized
    fun stop(owner: String) {
        requests.remove(owner)
        val union = requests.values.flatten().toSet()
        if (union == activeProtocols) return
        activeProtocols = union
        if (union.isEmpty()) {
            engine.stop()
        } else {
            engine.start(scope, DEFAULT_SCAN_WINDOW_MS, union)
        }
    }

    @Synchronized
    fun stopAll() {
        requests.clear()
        activeProtocols = emptySet()
        engine.stop()
    }

    companion object {
        const val OWNER_UI = "phone-ui"
        const val OWNER_RECONNECT = "native-reconnect"
        private const val DEFAULT_SCAN_WINDOW_MS = 15_000L
    }
}
