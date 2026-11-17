package com.playbridge.player.ui

import com.playbridge.player.together.TvTogether
import com.playbridge.shared.together.TogetherEvent

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
    val hostId = hostMemberId?.takeIf { it.isNotBlank() }
    return TogetherPartyUi(
        partyName = host?.let { "$it’s watch party" } ?: "Watch party",
        status = when {
            !connected -> PartyStatus.Connecting(relay)
            title != null -> PartyStatus.NowPlaying(title)
            else -> PartyStatus.WaitingForHost
        },
        members = members.entries.map { (id, name) ->
            PartyMemberUi(
                name = name,
                isHost = if (hostId != null) id == hostId else (host != null && name == host),
                isWaiting = name in waitingFor,
            )
        },
        waitingFor = waitingFor,
        notice = notice,
    )
}

internal fun TvTogether.State.InParty.withMember(
    event: TogetherEvent.Member,
    selfMemberId: String?,
): TvTogether.State.InParty {
    val nextMembers = if (event.member == selfMemberId) {
        members
    } else {
        members + (event.member to event.name)
    }
    val (nextHostId, nextHostName) = when {
        event.host -> event.member to event.name.takeIf { it.isNotBlank() }
        hostMemberId == event.member -> null to null
        else -> hostMemberId to hostName
    }
    return copy(
        members = nextMembers,
        hostName = nextHostName,
        hostMemberId = nextHostId,
    )
}

internal fun TvTogether.State.InParty.withoutMember(memberId: String): TvTogether.State.InParty {
    val clearedHost = hostMemberId == memberId
    return copy(
        members = members - memberId,
        hostName = if (clearedHost) null else hostName,
        hostMemberId = if (clearedHost) null else hostMemberId,
    )
}

internal fun TvTogether.State.InParty.withWaiting(
    waitingMemberIds: List<String>,
    selfMemberId: String?,
): TvTogether.State.InParty = copy(
    waitingFor = waitingMemberIds.filter { it != selfMemberId }.map { members[it] ?: "a device" },
)

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
