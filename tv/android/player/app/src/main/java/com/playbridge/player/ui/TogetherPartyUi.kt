package com.playbridge.player.ui

import com.playbridge.player.together.TvTogether

/** One person or TV in a watch party, as the screens show it. */
internal data class PartyMemberUi(val name: String, val isHost: Boolean, val isWaiting: Boolean)

/** What the party screens and the over-the-player chrome need, free of any UI toolkit. */
internal data class TogetherPartyUi(
    val partyName: String,
    val status: PartyStatus,
    val members: List<PartyMemberUi>,
    val waitingFor: List<String>,
    val notice: String?,
)

internal sealed interface PartyStatus {
    data class Connecting(val relay: String) : PartyStatus
    data class NowPlaying(val title: String) : PartyStatus
    data object WaitingForHost : PartyStatus
}

/** Waiting names are matched to members by name, which is all the party state carries. */
internal fun TvTogether.State.InParty.toPartyUi(): TogetherPartyUi {
    val host = hostName?.takeIf { it.isNotBlank() }
    return TogetherPartyUi(
        partyName = host?.let { "$it’s watch party" } ?: "Watch party",
        status = when {
            !connected -> PartyStatus.Connecting(relay)
            title != null -> PartyStatus.NowPlaying(title)
            else -> PartyStatus.WaitingForHost
        },
        members = members.values.map { PartyMemberUi(it, isHost = host != null && it == host, isWaiting = it in waitingFor) },
        waitingFor = waitingFor,
        notice = notice,
    )
}

/** "Waiting for Sam's TV…", "Waiting for Sam and Alex…", "Waiting for 3 devices…". */
internal fun waitingLabel(names: List<String>): String = when (names.size) {
    0 -> ""
    1 -> "Waiting for ${names[0]}…"
    2 -> "Waiting for ${names[0]} and ${names[1]}…"
    else -> "Waiting for ${names.size} devices…"
}

/** "10 minutes", "1 minute", "45 seconds", "1 second"; null once it has expired. */
internal fun expiryLabel(remainingMs: Long): String? {
    if (remainingMs <= 0L) return null
    val seconds = ((remainingMs + 999L) / 1000L).toInt()
    return if (seconds >= 60) {
        val minutes = (seconds + 59) / 60
        if (minutes == 1) "1 minute" else "$minutes minutes"
    } else {
        if (seconds == 1) "1 second" else "$seconds seconds"
    }
}

/** "418207593" becomes "418-207-593". */
internal fun groupPartyCode(code: String): String = code.chunked(3).joinToString("-")
