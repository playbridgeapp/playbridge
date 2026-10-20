package com.playbridge.sender.cast.remote

import com.playbridge.sender.cast.Capability
import com.playbridge.sender.cast.TargetKind
import kotlin.math.roundToInt

/** Broad external flags are constrained by the verified adapter surface. */
fun externalRemoteCapabilities(kind: TargetKind, advertised: Set<Capability>, volumeReported: Boolean? = null,
    volumeLevel: Double? = null, muted: Boolean? = null): RemoteCapabilities {
    val yes = Support.Supported
    val no = Support.Unsupported
    val known = kind in setOf(TargetKind.GOOGLE_CAST, TargetKind.ROKU, TargetKind.DLNA, TargetKind.WEB_BROWSER)
    fun flag(capability: Capability) = if (known && capability in advertised) yes else no
    val seek = if (kind == TargetKind.ROKU) no else flag(Capability.SEEK)
    val volume = when {
        !known -> Support.Unknown
        kind == TargetKind.ROKU -> flag(Capability.VOLUME)
        volumeReported == false -> no
        Capability.VOLUME in advertised -> yes
        kind == TargetKind.DLNA && volumeReported == null -> Support.Unknown
        else -> no
    }
    val reports = known && kind != TargetKind.ROKU && volumeReported != false
    val percent = volumeLevel?.takeIf { reports && it.isFinite() && it in 0.0..1.0 }?.let { (it * 100).roundToInt() }
    val keys = if (kind == TargetKind.ROKU && Capability.REMOTE in advertised) setOf(
        RemoteKey.Up, RemoteKey.Down, RemoteKey.Left, RemoteKey.Right, RemoteKey.Ok, RemoteKey.Back, RemoteKey.Home, RemoteKey.Enter,
    ) else emptySet()
    return RemoteCapabilities(
        transport = TransportCapabilities(flag(Capability.PLAY_PAUSE), flag(Capability.STOP), seek, seek),
        keys = keys, keyScope = if (keys.isEmpty()) ControlScope.None else ControlScope.WholeDevice,
        pointer = PointerCapabilities(no, no, no, no, no, no, no, no), text = TextCapabilities(no, no, no),
        browser = BrowserCapabilities(fullscreen = no, launch = no, managers = no),
        queue = QueueCapabilities(no, no, no, no, phoneOwned = true),
        // Load-time subtitle references are not a remote track-selection/add API.
        tracks = TrackCapabilities(no, no, no, no),
        volume = VolumeCapabilities(volume, if (kind == TargetKind.ROKU) no else volume, when (kind) {
            TargetKind.GOOGLE_CAST -> VolumeScope.ReceiverDevice
            TargetKind.DLNA -> VolumeScope.Renderer
            TargetKind.ROKU -> VolumeScope.ReceiverDevice
            TargetKind.WEB_BROWSER -> VolumeScope.Player
            else -> VolumeScope.None
        }, reportedPercent = percent, level = percent, maximum = if (percent != null) 100 else null,
            muted = muted.takeIf { reports }),
    )
}
