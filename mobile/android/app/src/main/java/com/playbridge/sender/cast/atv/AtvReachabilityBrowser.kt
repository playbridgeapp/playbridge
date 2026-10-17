package com.playbridge.sender.cast.atv

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Handler
import android.os.Looper
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Passive foreground service discovery only. Never opens a TV control connection. */
@Suppress("DEPRECATION")
internal class AtvReachabilityBrowser(context: Context, private val scope: CoroutineScope) {
    private val nsd = context.getSystemService(Context.NSD_SERVICE) as NsdManager
    private val handler = Handler(Looper.getMainLooper())
    private val found = linkedMapOf<String, AtvDiscoveredDevice>()
    private val instances = mutableMapOf<String, UUID>()
    private val pending = ArrayDeque<Pair<NsdServiceInfo, UUID>>()
    private var resolving = false
    private var generation = UUID.randomUUID()
    private var listener: NsdManager.DiscoveryListener? = null
    private var settle: Job? = null
    private val mutableDevices = MutableStateFlow<List<AtvDiscoveredDevice>>(emptyList())
    val devices = mutableDevices.asStateFlow()
    private val mutableReady = MutableStateFlow(false)
    val ready = mutableReady.asStateFlow()

    fun start() {
        if (listener != null) return
        val ticket = UUID.randomUUID(); generation = ticket
        mutableReady.value = false
        val active = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(type: String) = Unit
            override fun onDiscoveryStopped(type: String) = Unit
            override fun onStartDiscoveryFailed(type: String, code: Int) { handler.post { if (generation == ticket) { stop(); mutableReady.value = true } } }
            override fun onStopDiscoveryFailed(type: String, code: Int) = Unit
            override fun onServiceFound(service: NsdServiceInfo) { handler.post {
                if (generation != ticket || instances.size >= 256) return@post
                val id = UUID.randomUUID(); instances[service.serviceName] = id
                pending.removeAll { it.first.serviceName == service.serviceName }
                if (pending.size < 256) pending.addLast(service to id)
                resolveNext(ticket)
            } }
            override fun onServiceLost(service: NsdServiceInfo) { handler.post {
                if (generation != ticket) return@post
                instances.remove(service.serviceName); found.remove(service.serviceName); publish()
            } }
        }
        listener = active
        try { nsd.discoverServices("_androidtvremote2._tcp.", NsdManager.PROTOCOL_DNS_SD, active) }
        catch (_: RuntimeException) { stop(); mutableReady.value = true; return }
        settle = scope.launch { delay(4_000); if (generation == ticket) mutableReady.value = true }
    }
    fun stop() {
        generation = UUID.randomUUID(); settle?.cancel(); settle = null
        listener?.let { runCatching { nsd.stopServiceDiscovery(it) } }; listener = null
        instances.clear(); found.clear(); pending.clear(); mutableDevices.value = emptyList(); mutableReady.value = false
        // An outstanding legacy resolver is allowed to finish; its generation is fenced.
    }
    private fun publish() { mutableDevices.value = found.toSortedMap().values.toList() }
    private fun resolveNext(ticket: UUID) {
        if (resolving || generation != ticket || pending.isEmpty()) return
        val (service, id) = pending.removeFirst()
        if (instances[service.serviceName] != id) { resolveNext(ticket); return }
        resolving = true
        val resolver = object : NsdManager.ResolveListener {
            override fun onResolveFailed(info: NsdServiceInfo, code: Int) { handler.post { finished() } }
            override fun onServiceResolved(info: NsdServiceInfo) { handler.post {
                val host = info.host?.hostAddress
                if (generation == ticket && instances[service.serviceName] == id && !host.isNullOrBlank()) {
                    val txt = info.attributes.entries.map { it.key to it.value.toString(Charsets.UTF_8) }
                    found[service.serviceName] = AtvDiscoveredDevice(info.serviceName, host, info.port, listOf(host), txt)
                    publish()
                }
                finished()
            } }
            private fun finished() { resolving = false; resolveNext(generation) }
        }
        try { nsd.resolveService(service, resolver) }
        catch (_: RuntimeException) { resolving = false; resolveNext(ticket) }
    }
}
