package com.playbridge.sender.cast

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cast
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.Smartphone
import androidx.compose.material.icons.filled.Tv
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import com.playbridge.sender.model.CastProtocol
import com.playbridge.sender.model.TvDevice
import com.playbridge.sender.together.PhoneTogether
import com.playbridge.shared.together.TogetherDeviceKind

/** Canonical target icon/tint kinds across Browser, Dashboard, Play on, and CastSheet. */
enum class TargetVisualKind {
    THIS_PHONE,
    PLAYBRIDGE_TV,
    OTHER_RECEIVER,
    WATCH_PARTY,
}

/** Approved dark-surface Watch Party colors matching `TargetIcons.dc.html` & `InParty.dc.html`. */
internal object PartyTargetColors {
    val Accent = Color(0xFFC9B8FF)
    val Surface = Color(0xFF231F3A)
    val Border = Color(0xFF40367A)
}

/**
 * Resolves the active UI target kind from [partySelected] + [route] without adding a party
 * case to transport-level [CastSessionManager.Route].
 */
internal fun resolveTargetVisualKind(
    route: CastSessionManager.Route,
    partySelected: Boolean,
    inParty: Boolean = true,
    externalProtocol: CastProtocol? = null,
): TargetVisualKind = when {
    inParty && partySelected -> TargetVisualKind.WATCH_PARTY
    route is CastSessionManager.Route.ThisDevice -> TargetVisualKind.THIS_PHONE
    route is CastSessionManager.Route.NativeTv -> TargetVisualKind.PLAYBRIDGE_TV
    route is CastSessionManager.Route.External ->
        if (externalProtocol == CastProtocol.PLAYBRIDGE) TargetVisualKind.PLAYBRIDGE_TV
        else TargetVisualKind.OTHER_RECEIVER
    else -> TargetVisualKind.THIS_PHONE
}

internal fun targetIconVector(kind: TargetVisualKind): ImageVector = when (kind) {
    TargetVisualKind.THIS_PHONE -> Icons.Default.Smartphone
    TargetVisualKind.PLAYBRIDGE_TV -> Icons.Default.Tv
    TargetVisualKind.OTHER_RECEIVER -> Icons.Default.Cast
    TargetVisualKind.WATCH_PARTY -> Icons.Default.Groups
}

/** Count of members currently in the watch party (at least 1 once joined). */
internal fun partyWatchingCount(party: PhoneTogether.Party): Int =
    party.members.size.coerceAtLeast(1)

/** Pure target-selection projection used by [PhoneTogether] + [CastSessionManager] UI sites. */
internal data class PartyTargetSelectionState(
    val partySelected: Boolean = false,
    val restoreRoute: CastSessionManager.Route? = null,
)

internal fun onEnterPartySelection(
    currentRoute: CastSessionManager.Route,
): PartyTargetSelectionState = PartyTargetSelectionState(
    partySelected = true,
    restoreRoute = currentRoute,
)

internal fun onPickNonPartyTarget(
    current: PartyTargetSelectionState,
): PartyTargetSelectionState = current.copy(partySelected = false)

internal fun onSelectPartyTarget(): PartyTargetSelectionState =
    PartyTargetSelectionState(partySelected = true)

internal fun onLeaveOrEndParty(): PartyTargetSelectionState =
    PartyTargetSelectionState(partySelected = false, restoreRoute = null)

internal fun TogetherDeviceKind.isReceiverKind(): Boolean =
    this == TogetherDeviceKind.Tv || this == TogetherDeviceKind.Desktop

/**
 * Matches whether a PlayBridge receiver ([uuid], [endpointKey], [name], optional [alternateName])
 * is one of this phone's own receivers currently in [party].
 *
 * When a receiver was added over LAN (`addConnectedTv`), [ownTvIdentities] records its `uuid` and
 * `endpointKey`, so matching compares stable IDs rather than display names (preventing collisions
 * between two receivers of the same name and surviving renames). Only `party.own` receiver members
 * (`.Tv` or `.Desktop`) without a recorded LAN identity (e.g., joined via pairing code) fall back to
 * strict display-name matching. Phones and CLIs are never matched.
 */
internal fun isOwnReceiverMemberMatch(
    name: String,
    alternateName: String? = null,
    uuid: String = "",
    endpointKey: String? = null,
    party: PhoneTogether.Party,
    ownTvIdentities: Map<String, PhoneTogether.OwnTvIdentity>,
): Boolean = party.own.any { memberId ->
    val member = party.members[memberId]
    if (member != null && !member.kind.isReceiverKind()) return@any false
    val recorded = ownTvIdentities[memberId]
    if (recorded != null) {
        when {
            recorded.uuid.isNotEmpty() && uuid.isNotEmpty() -> uuid == recorded.uuid
            recorded.endpointKey != null && endpointKey != null -> endpointKey == recorded.endpointKey
            else -> false
        }
    } else {
        member != null && member.kind.isReceiverKind() &&
            member.name.isNotEmpty() &&
            (member.name == name || (alternateName != null && member.name == alternateName))
    }
}

internal fun isOwnTvMemberMatch(
    device: TvDevice,
    party: PhoneTogether.Party,
    ownTvIdentities: Map<String, PhoneTogether.OwnTvIdentity>,
): Boolean {
    if (device.resolvedProtocol != CastProtocol.PLAYBRIDGE) return false
    return isOwnReceiverMemberMatch(
        name = device.name,
        alternateName = device.playOnName,
        uuid = device.uuid,
        endpointKey = device.endpointKey.toString(),
        party = party,
        ownTvIdentities = ownTvIdentities,
    )
}
