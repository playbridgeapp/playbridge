package com.playbridge.sender.cast.remote

/** Tracks consumed DOWN sequences so UP never changes phone volume after the route disappears. */
internal class RemoteVolumeKeyHandler {
    private val consumedKeys = mutableMapOf<Int, (Boolean) -> Unit>()
    private var route: ((Boolean) -> Unit)? = null

    fun register(onVolume: (Boolean) -> Unit): () -> Unit {
        route = onVolume
        return { if (route === onVolume) route = null }
    }

    fun handle(keyCode: Int, down: Boolean, resumed: Boolean, repeat: Boolean = false): Boolean {
        if (keyCode != VOLUME_UP && keyCode != VOLUME_DOWN) return false
        if (!down) return consumedKeys.remove(keyCode) != null
        // A key-up may have gone to Android while backgrounded. A fresh DOWN starts a new sequence.
        if (!repeat) consumedKeys.remove(keyCode)
        val callback = route
        // Keep a held sequence on its original route; a switch cannot send its repeats to a new TV.
        if (keyCode in consumedKeys && consumedKeys[keyCode] !== callback) return true
        if (resumed && callback != null) {
            consumedKeys[keyCode] = callback
            callback(keyCode == VOLUME_UP)
            return true
        }
        // A held key whose route vanished must not leak OS repeats to phone audio.
        return keyCode in consumedKeys
    }

    companion object {
        const val VOLUME_UP = 24
        const val VOLUME_DOWN = 25
    }
}
