package com.playbridge.sender.connection

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log
import com.playbridge.protocol.NsdConstants
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.net.InetAddress

class NsdHelper(context: Context) {
    private val nsdManager = context.getSystemService(Context.NSD_SERVICE) as NsdManager
    private var discoveryListener: NsdManager.DiscoveryListener? = null
    
    // List of discovered services (IP, Port, Name)
    private val _discoveredDevices = MutableStateFlow<List<DiscoveredDevice>>(emptyList())
    val discoveredDevices: StateFlow<List<DiscoveredDevice>> = _discoveredDevices.asStateFlow()
    
    private val foundServices = mutableMapOf<String, NsdServiceInfo>()
    
    data class DiscoveredDevice(
        val ip: String,
        val port: Int,
        val name: String,
        val uuid: String = ""
    )
    
    fun startDiscovery() {
        if (discoveryListener != null) return
        
        // Clear previous results
        _discoveredDevices.value = emptyList()
        foundServices.clear()
        
        discoveryListener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(regType: String) {
                Log.d(TAG, "Service discovery started")
            }
            
            override fun onServiceFound(service: NsdServiceInfo) {
                Log.d(TAG, "Service found: ${service.serviceName}")
                if (service.serviceType == NsdConstants.SERVICE_TYPE) {
                    nsdManager.resolveService(service, object : NsdManager.ResolveListener {
                        override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                            Log.e(TAG, "Resolve failed: $errorCode")
                        }
                        
                        override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                            Log.d(TAG, "Resolve Succeeded. ${serviceInfo}")
                            
                            val host = serviceInfo.host ?: return
                            val ip = host.hostAddress ?: return
                            val port = serviceInfo.port
                            val name = serviceInfo.serviceName.replace("\\\\032", " ") // Fix space encoding if present
                            
                            // Extract UUID if present
                            var uuid = ""
                            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
                                val uuidBytes = serviceInfo.attributes["uuid"]
                                if (uuidBytes != null) {
                                    uuid = String(uuidBytes)
                                }
                            }

                            val device = DiscoveredDevice(ip, port, name, uuid)
                            
                            // Update list
                            val currentList = _discoveredDevices.value.toMutableList()
                            // Remove existing entry for same IP if exists
                            currentList.removeAll { it.ip == ip }
                            currentList.add(device)
                            _discoveredDevices.value = currentList
                        }
                    })
                }
            }
            
            override fun onServiceLost(service: NsdServiceInfo) {
                Log.e(TAG, "service lost: $service")
                val currentList = _discoveredDevices.value.toMutableList()
                // It's hard to match exactly since we don't have IP in serviceLost, 
                // but usually name matches.
                currentList.removeAll { it.name == service.serviceName }
                _discoveredDevices.value = currentList
            }
            
            override fun onDiscoveryStopped(serviceType: String) {
                Log.i(TAG, "Discovery stopped: $serviceType")
            }
            
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.e(TAG, "Discovery failed: Error code:$errorCode")
                stopDiscovery()
            }
            
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.e(TAG, "Discovery failed: Error code:$errorCode")
                stopDiscovery()
            }
        }
        
        nsdManager.discoverServices(
            NsdConstants.SERVICE_TYPE,
            NsdManager.PROTOCOL_DNS_SD,
            discoveryListener
        )
    }
    
    fun stopDiscovery() {
        if (discoveryListener != null) {
            try {
                nsdManager.stopServiceDiscovery(discoveryListener)
            } catch (e: Exception) {
                // Ignore if already stopped
            }
            discoveryListener = null
        }
    }
    
    companion object {
        private const val TAG = "NsdHelper"
    }
}
