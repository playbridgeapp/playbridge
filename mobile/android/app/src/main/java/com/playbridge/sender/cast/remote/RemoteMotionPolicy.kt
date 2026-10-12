package com.playbridge.sender.cast.remote

internal data class RemoteMotionPolicy(val animatorScale: Float, val resumed: Boolean, val screenOn: Boolean) {
    val animate: Boolean get() = animatorScale.isFinite() && animatorScale > 0f && resumed && screenOn
    fun waveRuns(playing: Boolean): Boolean = animate && playing
    fun duration(ms: Int): Int = if (animate) (ms * animatorScale).toInt().coerceIn(1, 10_000) else 0
}
