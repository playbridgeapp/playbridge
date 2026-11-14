package com.playbridge.player.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import com.playbridge.player.together.TvTogether
import com.playbridge.player.ui.components.LoadingBlob
import com.playbridge.player.ui.components.PillButton
import com.playbridge.player.ui.components.TvTone
import com.playbridge.player.ui.theme.TvRadius
import com.playbridge.player.ui.theme.TvSpacing
import com.playbridge.player.ui.theme.TvText
import com.playbridge.player.ui.theme.TvTheme

/*
 * Party chrome over the player (TogetherPlayer.dc.html). Self-contained pieces that take plain
 * state; none of them reads TvTogether itself, so the player decides when to show them:
 *
 *  - TogetherPartyLabel   under the media title, top-left ("Alex's watch party")
 *  - TogetherMemberChips  top-right; devices still loading are highlighted with a spinner
 *  - TogetherWaitingPill  centred over the video while anyone is still loading
 *  - TogetherEveryoneHint beside the play button: "Play, pause and seek apply to everyone"
 *  - TogetherLeaveButton  last button in the control row ("Leave party")
 *  - TogetherPlayerChrome members + waiting pill + notice in one non-focusable overlay layer
 */

/** Overlay layer: member chips top-right, waiting pill centred, notice below the pill. Never takes focus. */
@Composable
fun TogetherPlayerChrome(party: TvTogether.State.InParty, modifier: Modifier = Modifier) {
    val ui = party.toPartyUi()
    Box(modifier.fillMaxSize()) {
        TogetherMemberChips(
            names = ui.members.map { it.name },
            waitingFor = ui.waitingFor,
            modifier = Modifier.align(Alignment.TopEnd).padding(top = TvSpacing.playerEdge, end = 56.dp),
        )
        Column(
            Modifier.align(Alignment.Center),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(TvSpacing.m),
        ) {
            if (ui.waitingFor.isNotEmpty()) TogetherWaitingPill(ui.waitingFor)
            ui.notice?.let {
                Text(it, style = TvText.value, color = TvTheme.player.onSurface, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/** "Alex's watch party" line, to sit under the media title. */
@Composable
fun TogetherPartyLabel(party: TvTogether.State.InParty, modifier: Modifier = Modifier) {
    Text(
        party.toPartyUi().partyName,
        modifier = modifier,
        style = TvText.caption,
        color = TvTheme.player.onSurfaceMuted,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

/** Names in the party. Anyone in [waitingFor] is drawn light with a small spinner. Not focusable. */
@Composable
fun TogetherMemberChips(names: List<String>, waitingFor: List<String>, modifier: Modifier = Modifier) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
        names.forEach { name ->
            val waiting = name in waitingFor
            Row(
                Modifier
                    .height(40.dp)
                    .background(if (waiting) TvTheme.colors.onSurface else TvTheme.player.control, TvRadius.pill)
                    .padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (waiting) LoadingBlob(Modifier.size(16.dp))
                Text(
                    name,
                    style = TvText.caption.copy(fontSize = 16.sp, fontWeight = if (waiting) FontWeight.SemiBold else FontWeight.Medium),
                    color = if (waiting) TvTheme.colors.surface else TvTheme.player.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** "Waiting for Sam's TV…" pill. Not focusable. */
@Composable
fun TogetherWaitingPill(waitingFor: List<String>, modifier: Modifier = Modifier) {
    if (waitingFor.isEmpty()) return
    Row(
        modifier.height(76.dp).background(TvTheme.colors.container, TvRadius.pill).padding(horizontal = 32.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(TvSpacing.l),
    ) {
        LoadingBlob(Modifier.size(30.dp))
        Text(waitingLabel(waitingFor), style = TvText.cardTitle, color = TvTheme.colors.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** Caption for beside the play button. */
@Composable
fun TogetherEveryoneHint(modifier: Modifier = Modifier) {
    Text(
        "Play, pause and seek apply to everyone",
        modifier = modifier,
        style = TvText.caption,
        color = TvTheme.player.onSurfaceMuted,
    )
}

/** "Leave party" pill for the control row. Calls [onLeave], normally `TvTogether.leave()`. */
@Composable
fun TogetherLeaveButton(onLeave: () -> Unit, modifier: Modifier = Modifier) {
    PillButton(
        text = "Leave party",
        onClick = onLeave,
        modifier = modifier,
        style = com.playbridge.player.ui.components.PillStyle.Secondary,
        tone = TvTone.Player,
        icon = rememberVectorPainter(Icons.AutoMirrored.Filled.ExitToApp),
        height = 60.dp,
    )
}
