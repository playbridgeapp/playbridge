package com.playbridge.player.player

import android.os.SystemClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class StillWatchingState(val isPrompting: Boolean = false, val secondsRemaining: Int = 300)

class StillWatchingController(
    private val scope: CoroutineScope,
    private val pausePlayback: () -> Unit,
    private val resumePlayback: () -> Unit,
    private val stopPlayback: () -> Unit,
    private val nowMs: () -> Long = SystemClock::elapsedRealtime,
    graceSeconds: Int = 300,
    private val onPromptChanged: (Boolean) -> Unit = {},
) {
    private var graceSeconds = graceSeconds
    private val _state = MutableStateFlow(StillWatchingState(secondsRemaining = graceSeconds))
    val state: StateFlow<StillWatchingState> = _state.asStateFlow()

    private var enabled = true
    private var thresholdMs = 90 * 60_000L
    private var accumulatedMs = 0L
    private var playingSinceMs: Long? = null
    private var isPlaying = false
    private var thresholdJob: Job? = null
    private var countdownJob: Job? = null
    private var pausedByPrompt = false

    fun updateSettings(enabled: Boolean, thresholdMinutes: Int, graceSeconds: Int? = null) {
        accrue()
        this.enabled = enabled
        thresholdMs = thresholdMinutes.coerceAtLeast(1) * 60_000L
        graceSeconds?.let { this.graceSeconds = it.coerceAtLeast(1) }
        if (!enabled) {
            thresholdJob?.cancel()
            accumulatedMs = 0L
            if (_state.value.isPrompting) dismissAndResume()
        } else {
            if (isPlaying && !_state.value.isPrompting) playingSinceMs = nowMs()
            scheduleThreshold()
        }
    }

    fun onUserActivity() {
        if (_state.value.isPrompting) {
            continueWatching()
            return
        }
        accrue()
        accumulatedMs = 0L
        if (enabled && isPlaying) playingSinceMs = nowMs()
        scheduleThreshold()
    }

    fun onMediaChanged() {
        thresholdJob?.cancel()
        countdownJob?.cancel()
        accumulatedMs = 0L
        playingSinceMs = if (enabled && isPlaying) nowMs() else null
        pausedByPrompt = false
        _state.value = StillWatchingState(secondsRemaining = graceSeconds)
        onPromptChanged(false)
        scheduleThreshold()
    }

    fun onPlayingChanged(isPlaying: Boolean) {
        accrue()
        this.isPlaying = isPlaying
        if (isPlaying && enabled && !_state.value.isPrompting) {
            if (playingSinceMs == null) playingSinceMs = nowMs()
            scheduleThreshold()
        } else {
            thresholdJob?.cancel()
        }
    }

    fun continueWatching() {
        if (!_state.value.isPrompting) return
        countdownJob?.cancel()
        accumulatedMs = 0L
        playingSinceMs = null
        _state.value = StillWatchingState(secondsRemaining = graceSeconds)
        onPromptChanged(false)
        if (pausedByPrompt) resumePlayback()
        pausedByPrompt = false
    }

    fun stopWatching() {
        if (!_state.value.isPrompting) return
        countdownJob?.cancel()
        _state.value = StillWatchingState(secondsRemaining = graceSeconds)
        onPromptChanged(false)
        stopPlayback()
    }

    fun dispose() {
        thresholdJob?.cancel()
        countdownJob?.cancel()
        playingSinceMs = null
        isPlaying = false
    }

    private fun accrue() {
        playingSinceMs?.let { accumulatedMs += (nowMs() - it).coerceAtLeast(0L) }
        playingSinceMs = null
    }

    private fun scheduleThreshold() {
        thresholdJob?.cancel()
        if (!enabled || playingSinceMs == null || _state.value.isPrompting) return
        val elapsed = accumulatedMs + (nowMs() - playingSinceMs!!).coerceAtLeast(0L)
        val remaining = (thresholdMs - elapsed).coerceAtLeast(0L)
        thresholdJob = scope.launch {
            delay(remaining)
            accrue()
            if (enabled) showPrompt()
        }
    }

    private fun showPrompt() {
        if (_state.value.isPrompting) return
        pausedByPrompt = true
        pausePlayback()
        _state.value = StillWatchingState(isPrompting = true, secondsRemaining = graceSeconds)
        onPromptChanged(true)
        countdownJob = scope.launch {
            for (remaining in graceSeconds - 1 downTo 0) {
                delay(1000)
                if (!_state.value.isPrompting) return@launch
                _state.value = StillWatchingState(isPrompting = true, secondsRemaining = remaining)
            }
            stopWatching()
        }
    }

    private fun dismissAndResume() {
        countdownJob?.cancel()
        _state.value = StillWatchingState(secondsRemaining = graceSeconds)
        onPromptChanged(false)
        if (pausedByPrompt) resumePlayback()
        pausedByPrompt = false
    }
}
