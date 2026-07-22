package com.playbridge.sender.cast

/** Owns the exactly-one external target invariant independently of Android lifecycle code. */
internal class CastTargetSlot {
    var target: CastTarget? = null
        private set

    fun replace(next: CastTarget) {
        if (target === next) return
        target?.release()
        target = next
    }

    fun clear() {
        target?.release()
        target = null
    }

    /** Detach ownership so the caller can perform an ordered stop-then-release. */
    fun take(): CastTarget? = target.also { target = null }
}
