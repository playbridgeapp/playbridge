package com.playbridge.sender.cast.dlna

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient

/**
 * Continuous DLNA renderer discovery: periodically runs SSDP M-SEARCH and resolves
 * each hit's device description, exposing the usable MediaRenderers as a StateFlow.
 * Runs alongside the native mDNS discovery (`connection/NsdHelper`).
 */
class DlnaDiscovery(
    context: Context,
    http: OkHttpClient,
) {
    private val ssdp = SsdpDiscovery(context)
    private val desc = DeviceDescription(http)

    private val _renderers = MutableStateFlow<List<DeviceDescription.Renderer>>(emptyList())
    val renderers: StateFlow<List<DeviceDescription.Renderer>> = _renderers.asStateFlow()

    private var job: Job? = null

    /** Begin periodic discovery on [scope]. Idempotent. */
    fun start(scope: CoroutineScope) {
        if (job?.isActive == true) return
        job = scope.launch(Dispatchers.IO) {
            while (isActive) {
                val found = ssdp.search()
                    .mapNotNull { desc.fetch(it.location) }
                    .filter { it.isUsable }
                    .distinctBy { it.udn ?: it.location }
                _renderers.value = found
                Log.d(TAG, "DLNA renderers: ${found.size}")
                delay(REFRESH_MS)
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
    }

    companion object {
        private const val TAG = "DlnaDiscovery"
        private const val REFRESH_MS = 6000L
    }
}
