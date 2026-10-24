package com.playbridge.sender.connection

import com.playbridge.sender.model.TvDevice
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch

/** One debounced probe owner shared by the Devices screen and cast picker. */
internal class SavedDeviceReachability(
    scope: CoroutineScope,
    saved: Flow<List<TvDevice>>,
    discovered: Flow<List<TvDevice>>,
    network: Flow<Long?>,
    probe: suspend (TvDevice) -> Boolean,
    publish: (Map<String, Boolean>) -> Unit,
) {
    private data class Request(val devices: List<TvDevice>, val network: Long?, val refresh: Int)
    private val refresh = MutableStateFlow(0)

    init {
        scope.launch {
            var currentNetwork: Long? = null
            val networks = network.distinctUntilChanged().onEach {
                currentNetwork = it
                publish(emptyMap())
            }
            combine(saved, discovered, networks, refresh) { history, live, networkId, retry ->
                Request(history.map { ConnectionMerge.withDiscoveredEndpoint(it, live) }, networkId, retry)
            }.distinctUntilChanged().collectLatest { request ->
                delay(350)
                val results = coroutineScope {
                    request.devices.map { device ->
                        async { device.endpointKey.toString() to probe(device) }
                    }.awaitAll().toMap()
                }
                if (request.network == currentNetwork) publish(results)
            }
        }
    }

    fun retry() { refresh.value += 1 }
}
