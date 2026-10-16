package com.playbridge.sender.connection

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

internal data class ReceiverPlaybackFailure(val sequence: Long, val message: String)

/** Native-session notice lifetime is separate from idle's playback snapshot cleanup. */
internal class ReceiverPlaybackFailures {
    private var playbackId: String? = null
    private var sequence = 0L
    private val mutableNotice = MutableStateFlow<ReceiverPlaybackFailure?>(null)
    val notice = mutableNotice.asStateFlow()

    fun reset() {
        playbackId = null
        mutableNotice.value = null
    }

    fun playlist(id: String?) {
        if (id != null && id != playbackId) {
            playbackId = id
            mutableNotice.value = null
        }
    }

    /** False drops the entire stale error status, not just its notice. */
    fun status(state: String, id: String?, error: Any?): Boolean {
        if (state == "error") {
            if (id != null && playbackId != null && id != playbackId) return false
            if (id != null) playbackId = id
            mutableNotice.value = ReceiverPlaybackFailure(++sequence, message(error))
        } else if (state in setOf("playing", "buffering", "paused")) {
            if (state == "playing" || id != null && id != playbackId) mutableNotice.value = null
            if (id != null) playbackId = id
        }
        return true
    }

    companion object {
        private val whitespace = Regex("[\\s\\p{Z}\\u0085]+")
        fun message(raw: Any?): String {
            val text = (raw as? String)?.replace(whitespace, " ")?.trim().orEmpty()
            if (text.isEmpty()) return "Playback failed on the TV"
            return text.substring(0, text.offsetByCodePoints(0, minOf(512, text.codePointCount(0, text.length))))
        }
    }
}
