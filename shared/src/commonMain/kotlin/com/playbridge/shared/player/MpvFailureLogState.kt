package com.playbridge.shared.player

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Only bounded categories since the last healthy playback point may explain a failure. */
internal class MpvFailureLogState {
    private var lastCode: String? = null
    private val mutableFailureCode = MutableStateFlow<String?>(null)
    val failureCode = mutableFailureCode.asStateFlow()
    private var lastPositionMs = 0L

    fun record(prefix: String, level: Int, text: String) {
        val code = MpvFailureLogPolicy.classify(prefix, level, text) ?: return
        if (code != "mpv_load_failed" || lastCode == null) lastCode = code
        if (MpvFailureLogPolicy.isTerminal(prefix, text)) mutableFailureCode.value = lastCode
    }

    fun beginFile() {
        playbackHealthy()
        lastPositionMs = 0L
    }

    fun playbackHealthy() {
        lastCode = null
        mutableFailureCode.value = null
    }

    fun positionChanged(positionMs: Long, playing: Boolean) {
        if (playing && positionMs > lastPositionMs && mutableFailureCode.value == null) playbackHealthy()
        lastPositionMs = positionMs
    }
}
