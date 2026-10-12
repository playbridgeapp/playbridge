package com.playbridge.sender.cast.remote

import com.playbridge.sender.cast.CastSessionManager
import com.playbridge.sender.cast.TargetKind
import com.playbridge.sender.connection.WebSocketClient
import com.playbridge.sender.model.CastProtocol
import com.playbridge.sender.model.EndpointKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

sealed interface RemoteTarget { val receiver: RemoteReceiver }
data class RemoteNativeTarget(val configuration: NativeRemoteConfiguration, val identity: Any? = null) : RemoteTarget {
    override val receiver: RemoteReceiver get() = configuration.receiver
}
data class RemoteExternalTarget(override val receiver: RemoteReceiver, val kind: TargetKind, val identity: Any) : RemoteTarget

/** Selection is route-qualified: an authenticated native socket cannot steal an external route. */
class RemoteControllerRegistry(
    private val targets: StateFlow<RemoteTarget?>,
    private val transport: NativeRemoteTransport,
    private val scope: CoroutineScope,
    private val externalTransport: ExternalRemoteTransport? = null,
    private val ownsExternalRoute: (RemoteExternalTarget) -> Boolean = { true },
    private val ownsNativeRoute: () -> Boolean = { true },
) {
    private val mutableController = MutableStateFlow<RemoteController?>(null)
    val controller: StateFlow<RemoteController?> = mutableController.asStateFlow()
    private var generation = 0L
    private var selected: RemoteTarget? = null
    private var selectedSession: RemoteSessionId? = null
    private var controllerJob: Job? = null

    init {
        select(targets.value)
        scope.launch { targets.collect(::select) }
    }

    private fun select(target: RemoteTarget?) {
        if (target == selected) return
        selected = target
        selectedSession = null
        controllerJob?.cancel()
        generation++
        if (target == null) { mutableController.value = null; return }
        val session = RemoteSessionId(target.receiver.endpoint, generation)
        selectedSession = session
        val job = SupervisorJob(scope.coroutineContext[Job])
        controllerJob = job
        val child = CoroutineScope(scope.coroutineContext + job)
        val current = { selectedSession == session && targets.value == target && when (target) {
            is RemoteNativeTarget -> ownsNativeRoute()
            is RemoteExternalTarget -> ownsExternalRoute(target)
        } }
        if (target is RemoteExternalTarget) {
            val external = requireNotNull(externalTransport)
            mutableController.value = when (target.kind) {
                TargetKind.GOOGLE_CAST -> GoogleCastRemoteController(session, target, external, child, current)
                TargetKind.ROKU -> RokuRemoteController(session, target, external, child, current)
                TargetKind.DLNA -> DlnaRemoteController(session, target, external, child, current)
                TargetKind.WEB_BROWSER -> WebReceiverRemoteController(session, target, external, child, current)
                TargetKind.NATIVE -> error("Native target cannot select an external controller")
            }
            return
        }
        val config = (target as RemoteNativeTarget).configuration
        val base = when (config.receiver.profile) {
            RemoteProfile.AndroidTv -> AndroidTvRemoteController(session, config, transport, child, current)
            RemoteProfile.AppleTv -> AppleTvRemoteController(session, config, transport, child, current)
            RemoteProfile.Desktop -> DesktopRemoteController(session, config, transport, child, current)
            RemoteProfile.Cli -> CliRemoteController(session, config, transport, child, current)
            else -> UnknownPlayBridgeRemoteController(session, config, transport, child, current)
        }
        mutableController.value = ScreenMirrorRemoteController(base, transport, child, current)
    }

    companion object {
        fun create(sessions: CastSessionManager, client: WebSocketClient, transport: NativeRemoteTransport, external: ExternalRemoteTransport, scope: CoroutineScope): RemoteControllerRegistry {
            fun target(): RemoteTarget? {
                val route = sessions.route.value
                if (route is CastSessionManager.Route.External) {
                    if (sessions.activeExternalDevice.value?.endpointKey != route.endpointKey) return null
                    val active = sessions.remoteExternalTarget ?: return null
                    if (active.kind == TargetKind.NATIVE) return null
                    return RemoteExternalTarget(RemoteReceiver(route.endpointKey, route.name, RemoteProfile.External), active.kind, active)
                }
                if (route != CastSessionManager.Route.NativeTv) return null
                val active = sessions.activeTarget.value
                if (active != null && active.kind != TargetKind.NATIVE) return null
                val device = sessions.sessionState.value.device?.takeIf { it.resolvedProtocol == CastProtocol.PLAYBRIDGE }
                val endpoint = device?.endpointKey ?: active?.let { EndpointKey(CastProtocol.PLAYBRIDGE, it.id) } ?: return null
                val advertised = if (client.connectionState.value is WebSocketClient.ConnectionState.Connected) client.tvCapabilitiesState.value else WebSocketClient.TvCapabilities(emptyList(), emptyList())
                val players = advertised.players.ifEmpty { device?.players.orEmpty() }
                val config = NativeRemoteConfiguration(
                    RemoteReceiver(endpoint, device?.name ?: active?.name ?: "TV", detectRemoteProfile(players, advertised.features)),
                    players, advertised.features,
                )
                return RemoteNativeTarget(config, active)
            }
            val targets = combine(sessions.route, sessions.activeTarget, sessions.sessionState, client.tvCapabilitiesState, client.connectionState) { _, _, _, _, _ -> target() }
                .stateIn(scope, SharingStarted.Eagerly, target())
            return RemoteControllerRegistry(targets, transport, scope, external, { target ->
                val route = sessions.route.value as? CastSessionManager.Route.External
                route?.endpointKey == target.receiver.endpoint && sessions.remoteExternalTarget === target.identity
            }) { sessions.route.value == CastSessionManager.Route.NativeTv && (sessions.activeTarget.value?.kind == TargetKind.NATIVE || sessions.activeTarget.value == null) }
        }
    }
}
