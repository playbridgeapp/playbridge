package com.playbridge.sender.cast.atv

import android.content.Context
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.playbridge.sender.cast.CastSessionManager
import com.playbridge.sender.cast.remote.RemoteAvailability
import com.playbridge.sender.cast.remote.readyFor
import com.playbridge.sender.connection.ConnectionStore
import com.playbridge.sender.connection.ReceiverDiscoveryRepository
import com.playbridge.sender.connection.WebSocketClient
import com.playbridge.sender.model.TvDevice
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Read-only catalog of native endpoints and paired TV reachability. Only the selected
 * endpoint's existing TvRemoteChannel can establish control. */
class RemoteTVDirectory internal constructor(
    context: Context, private val sessions: CastSessionManager, store: ConnectionStore,
    private val credentials: AtvCredentialStore, discovery: ReceiverDiscoveryRepository,
    private val channel: AtvTvRemoteChannel, private val client: WebSocketClient, private val scope: CoroutineScope,
) {
    private val browser = AtvReachabilityBrowser(context, scope)
    private val pings = MutableStateFlow<Map<String, Boolean>>(emptyMap())
    private var foreground = false
    val isForeground: Boolean get() = foreground
    private data class Inputs(val saved: List<TvDevice>, val selected: TvDevice?, val records: List<AtvTvRecord>, val discovered: List<TvDevice>, val tv: List<AtvDiscoveredDevice>)
    private val inputs = combine(store.deviceHistory, sessions.remoteDevice, credentials.records, discovery.devices, browser.devices, ::Inputs)
    val rows = combine(inputs, channel.state, client.connectionState, pings) { input, control, connection, online ->
        val receiverOnline = online.filterValues { it }.keys.toMutableSet()
        if (connection is WebSocketClient.ConnectionState.Connected) input.selected?.endpointKey?.toString()?.let(receiverOnline::add)
        RemoteTVCatalog.rows(input.saved, input.selected, input.records, input.discovered, input.tv, receiverOnline,
            control.matchedEndpoint?.takeIf { control.readyFor(it) }?.toString(),
            online.filterValues { !it }.keys - receiverOnline)
    }.stateIn(scope, SharingStarted.Eagerly, emptyList())
    val ready = combine(browser.ready, credentials.records) { scanned, records -> scanned || records.isEmpty() }
        .stateIn(scope, SharingStarted.Eagerly, false)
    init {
        scope.launch {
            ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
                override fun onStart(owner: LifecycleOwner) { foreground = true; updateDiscovery() }
                override fun onStop(owner: LifecycleOwner) { foreground = false; updateDiscovery() }
            })
            credentials.records.collect { updateDiscovery() }
        }
        scope.launch { browser.devices.collect(channel::updateDiscovery) }
    }
    private fun updateDiscovery() {
        if (foreground && credentials.records.value.isNotEmpty()) browser.start() else browser.stop()
    }
    fun updatePings(value: Map<String, Boolean>) { pings.value = value }
    fun retry() { browser.stop(); updateDiscovery(); channel.retryRemote() }
    suspend fun awaitControl(endpoint: String) {
        combine(channel.state, client.connectionState, sessions.remoteDevice) { control, receiver, selected ->
            selected?.endpointKey?.toString() != endpoint || receiver is WebSocketClient.ConnectionState.Connected ||
                control.connected && control.matchedEndpoint?.toString() == endpoint
        }.first { it }
    }
    suspend fun openPlayBridge(endpoint: String): Boolean {
        val current = sessions.remoteDevice.value?.endpointKey
        if (current?.toString() != endpoint) return false
        if (!channel.state.value.readyFor(current)) return false
        val result = channel.launchApp(RemoteAvailability.PLAYBRIDGE_PACKAGE)
        return sessions.remoteDevice.value?.endpointKey == current &&
            (result == com.playbridge.sender.cast.remote.RemoteCommandResult.Submitted || result == com.playbridge.sender.cast.remote.RemoteCommandResult.Confirmed)
    }
}
