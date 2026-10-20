package com.playbridge.sender.cast.remote

import kotlin.math.roundToInt

/** Two-second intent overlay; receiver values remain the source of truth. */
internal class RemoteVolumeOptimism(private val clock: () -> Long) {
    private var level: Double? = null
    private var muted: Boolean? = null
    private var at = Long.MIN_VALUE
    val pending get() = at != Long.MIN_VALUE && clock() - at < 2_000L
    fun clear() { level = null; muted = null; at = Long.MIN_VALUE }
    fun begin(action: RemoteAction, reported: VolumeCapabilities, step: Double, reports: Boolean) {
        if (!reports) return
        if (!pending) clear()
        val current = display(reported)
        when (action) {
            is RemoteAction.AdjustVolume -> {
                val value = current.reportedPercent ?: return
                level = ((level ?: (value / 100.0)) + action.steps * step).coerceIn(0.0, 1.0)
            }
            is RemoteAction.SetVolume -> level = action.percent.coerceIn(0, 100) / 100.0
            is RemoteAction.Key -> if (action.key == RemoteKey.Mute) muted = current.muted?.not() ?: return else return
            else -> return
        }
        at = clock()
    }
    fun display(reported: VolumeCapabilities): VolumeCapabilities {
        if (!pending) return reported
        val percent = level?.let { (it * 100).roundToInt() }
        return reported.copy(reportedPercent = percent ?: reported.reportedPercent,
            level = percent?.let { if (reported.maximum != null) (it * reported.maximum / 100.0).roundToInt() else it } ?: reported.level,
            muted = muted ?: reported.muted)
    }
}

internal fun RemoteUiState.volumeStep(): Double =
    if (fullControlSupports(RemoteAction.AdjustVolume(1))) capabilities.volume.maximum?.takeIf { it > 0 }?.let { 1.0 / it } ?: (1.0 / 16)
    else receiverProtocol.descriptor?.volume?.step ?: if (capabilities.volume.scope in setOf(VolumeScope.System, VolumeScope.SystemWithPlayerFallback)) 1.0 / 16 else .05

internal fun RemoteUiState.reportsVolume(): Boolean =
    !fullControlSupports(RemoteAction.AdjustVolume(1)) &&
        (receiverProtocol.descriptor?.volume?.operations?.contains("level_report") == true ||
            nativePolicy == null && capabilities.volume.reportedPercent != null)
