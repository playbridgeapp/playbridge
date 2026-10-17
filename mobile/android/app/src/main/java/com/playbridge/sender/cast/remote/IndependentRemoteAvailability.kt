package com.playbridge.sender.cast.remote

/** Phone presentation only; never grants receiver capabilities or transfers ATV credentials. */
enum class RemoteAvailability {
    Full, Receiver, TvRemoteOnly, Offline, Unpaired;
    val receiverControls: Boolean get() = this == Full || this == Receiver
    val tvControls: Boolean get() = this == Full || this == TvRemoteOnly
    val visibleControls: Set<String> get() = buildSet {
        addAll(listOf("header", "picker"))
        if (receiverControls) addAll(listOf("nowPlaying", "seek", "transport", "queue", "tracks", "receiverInput"))
        if (tvControls) addAll(listOf("pad", "back", "home", "keyboard", "volume", "mute", "tvMenu"))
        if (this@RemoteAvailability == TvRemoteOnly) add("openPlayBridge")
        if (this@RemoteAvailability == Offline) addAll(listOf("retry", "switchTV"))
    }
    companion object {
        const val RECEIVER_MISSING = "PlayBridge isn't running on this TV."
        const val OFFLINE_TITLE = "Can't reach this TV"
        const val OFFLINE_MESSAGE = "Make sure it's on and on the same Wi-Fi."
        const val PLAYBRIDGE_PACKAGE = "com.playbridge.player"
        fun resolve(receiver: Boolean, tvRemote: Boolean, paired: Boolean, ambiguous: Boolean = false): RemoteAvailability = when {
            receiver -> if (tvRemote && paired && !ambiguous) Full else Receiver
            tvRemote && paired && !ambiguous -> TvRemoteOnly
            paired || ambiguous -> Offline
            else -> Unpaired
        }
        fun pickerStatus(receiver: Boolean, tvRemote: Boolean, paired: Boolean, ambiguous: Boolean = false): String = when {
            receiver -> if (tvRemote && paired && !ambiguous) "PlayBridge · Full control" else "PlayBridge"
            tvRemote && paired && !ambiguous -> "TV remote only"
            else -> "Offline"
        }
    }
}

data class RemoteTVChoice(val endpoint: String, val paired: Boolean, val reachable: Boolean, val ambiguous: Boolean = false)
sealed interface RemoteTVSelection {
    data class Selected(val endpoint: String) : RemoteTVSelection
    data object Picker : RemoteTVSelection
    companion object {
        fun resolve(selected: String?, choices: List<RemoteTVChoice>): RemoteTVSelection {
            if (selected != null) return Selected(selected)
            val reachable = choices.filter { it.paired && it.reachable && !it.ambiguous }.map { it.endpoint }.distinct()
            return reachable.singleOrNull()?.let(::Selected) ?: Picker
        }
    }
}
