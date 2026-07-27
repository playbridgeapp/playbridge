package com.playbridge.sender.connection

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Live phone network posture for cast / discovery UI.
 *
 * - [onLocalNetwork]: Wi‑Fi or Ethernet is available (cellular-only / offline is false).
 * - [vpnActive]: a VPN interface is up (may hide LAN even when Wi‑Fi is connected).
 */
class NetworkStatusRepository(context: Context) {

    data class Status(
        val onLocalNetwork: Boolean = true,
        val vpnActive: Boolean = false,
    )

    private val appContext = context.applicationContext
    private val connectivity = appContext.getSystemService(ConnectivityManager::class.java)

    private val _status = MutableStateFlow(readStatus())
    val status: StateFlow<Status> = _status.asStateFlow()

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) = publish()
        override fun onLost(network: Network) = publish()
        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) = publish()
        override fun onUnavailable() = publish()
    }

    init {
        val cm = connectivity
        if (cm == null) {
            Log.w(TAG, "ConnectivityManager unavailable")
        } else {
            // Default-network callback covers transport + capability flips (VPN up/down, Wi‑Fi).
            runCatching {
                cm.registerDefaultNetworkCallback(callback)
            }.onFailure {
                Log.w(TAG, "registerDefaultNetworkCallback failed: ${it.message}")
                // Fallback: listen for any Wi‑Fi / Ethernet / VPN network events.
                runCatching {
                    val request = NetworkRequest.Builder()
                        .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                        .addTransportType(NetworkCapabilities.TRANSPORT_ETHERNET)
                        .addTransportType(NetworkCapabilities.TRANSPORT_VPN)
                        .build()
                    cm.registerNetworkCallback(request, callback)
                }.onFailure { err ->
                    Log.w(TAG, "registerNetworkCallback failed: ${err.message}")
                }
            }
            publish()
        }
    }

    private fun publish() {
        _status.value = readStatus()
    }

    private fun readStatus(): Status {
        val cm = connectivity ?: return Status(onLocalNetwork = true, vpnActive = false)
        val networks = cm.allNetworks
        var onLocal = false
        var vpn = false
        for (network in networks) {
            val caps = cm.getNetworkCapabilities(network) ?: continue
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
            ) {
                onLocal = true
            }
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) {
                vpn = true
            }
            // Active VPN path often lacks NOT_VPN even when TRANSPORT_VPN is missing on some OEMs.
            if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN) &&
                network == cm.activeNetwork
            ) {
                vpn = true
            }
        }
        // No networks at all → not on local LAN.
        if (networks.isEmpty()) {
            onLocal = false
        }
        return Status(onLocalNetwork = onLocal, vpnActive = vpn)
    }

    companion object {
        private const val TAG = "NetworkStatus"
    }
}
