package com.playbridge.player.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import com.playbridge.player.together.TvTogether
import com.playbridge.player.ui.components.LoadingBlob
import com.playbridge.player.ui.components.PillButton
import com.playbridge.player.ui.components.PillStyle
import com.playbridge.player.ui.components.ValueRow
import com.playbridge.player.ui.theme.ThemedDialog
import com.playbridge.player.ui.theme.TvRadius
import com.playbridge.player.ui.theme.TvSpacing
import com.playbridge.player.ui.theme.TvText
import com.playbridge.player.ui.theme.TvTheme
import kotlinx.coroutines.delay

/**
 * Watch Together as a full screen: join a party by code, accept an invite, and see or leave the
 * current party. Driven by [TvTogether.state].
 *
 * Fills whatever bounds it gets; in unbounded height (a lazy list) it falls back to a minimum height.
 *
 * @param requestInitialFocus move focus to the step's main button when first shown. Pass false when
 * the panel sits beside a list that should keep focus.
 * @param onBack when non-null, Back cancels a pending code (not a party) and then calls this.
 */
@Composable
fun WatchTogetherPanel(
    requestInitialFocus: Boolean = true,
    onBack: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    val colors = TvTheme.colors
    val state by TvTogether.state.collectAsState()
    var pairingUrl by remember { mutableStateOf(TvTogether.pairingUrl(context)) }
    var showUrlDialog by remember { mutableStateOf(false) }

    // Each step replaces the button that was just pressed, so focus follows to the new step's main
    // button or the TV drops it. On first show it is requested only when [requestInitialFocus].
    val focus = remember { FocusRequester() }
    var shownStep by remember { mutableStateOf<kotlin.reflect.KClass<*>?>(null) }
    LaunchedEffect(state::class) {
        val previous = shownStep
        shownStep = state::class
        if (previous != state::class && (previous != null || requestInitialFocus)) {
            kotlinx.coroutines.android.awaitFrame()
            runCatching { focus.requestFocus() }
        }
    }

    if (onBack != null) {
        BackHandler {
            if (state is TvTogether.State.Registering || state is TvTogether.State.Code) TvTogether.cancelPairing()
            onBack()
        }
    }

    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val bounded = constraints.hasBoundedHeight
        val sizing = if (bounded) Modifier.fillMaxSize() else Modifier.fillMaxWidth().heightIn(min = 520.dp)
        Column(
            modifier = sizing
                .background(colors.surface)
                .padding(start = TvSpacing.screenGutter, end = TvSpacing.screenGutter, top = TvSpacing.screenTop, bottom = 44.dp),
            verticalArrangement = Arrangement.spacedBy(TvSpacing.xxl),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(TvSpacing.m)) {
                Image(
                    rememberVectorPainter(TogetherIcons.People), null, Modifier.size(22.dp),
                    colorFilter = ColorFilter.tint(colors.onSurfaceMuted),
                )
                Text("Watch Together", style = TvText.row.copy(fontWeight = FontWeight.Normal), color = colors.onSurfaceMuted)
            }

            Box(Modifier.weight(1f, fill = bounded).fillMaxWidth()) {
                when (val current = state) {
                    TvTogether.State.Idle -> IdleView(focus)
                    TvTogether.State.Registering -> StepView(
                        title = "Getting a code…",
                        body = null,
                        spinner = true,
                    ) { PillButton("Cancel", { TvTogether.cancelPairing() }, Modifier.focusRequester(focus)) }
                    is TvTogether.State.Code -> CodeView(current, focus)
                    is TvTogether.State.Invited -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        TogetherInviteCard(
                            from = current.from,
                            relay = current.relay,
                            onJoin = { TvTogether.accept() },
                            onDecline = { TvTogether.decline() },
                            joinFocus = focus,
                        )
                    }
                    is TvTogether.State.InParty -> PartyView(current.toPartyUi(), focus)
                    is TvTogether.State.Ended -> StepView(title = current.message, body = null, spinner = false) {
                        PillButton("OK", { TvTogether.cancelPairing() }, Modifier.focusRequester(focus))
                    }
                }
            }

            when (state) {
                is TvTogether.State.InParty -> Text(
                    "Nothing from the party is saved to this TV’s bookmarks or recent casts.",
                    style = TvText.caption,
                    color = colors.onSurfaceMuted,
                )
                is TvTogether.State.Code -> Text(
                    "Already paired with a phone in the party? Add this TV from that phone instead. No code needed.",
                    style = TvText.caption,
                    color = colors.onSurfaceMuted,
                )
                else -> Unit
            }
            if (state !is TvTogether.State.InParty) {
                ValueRow(
                    title = "Pairing service",
                    value = pairingUrl.ifEmpty { "Not set" },
                    onClick = { showUrlDialog = true },
                    modifier = Modifier.widthIn(max = 640.dp),
                )
            }
        }
    }

    if (showUrlDialog) {
        PairingServiceDialog(
            initial = pairingUrl,
            onDismiss = { showUrlDialog = false },
            onSave = { draft ->
                TvTogether.setPairingUrl(context, draft)
                pairingUrl = TvTogether.pairingUrl(context)
                showUrlDialog = false
            },
        )
    }
}

@Composable
private fun StepView(
    title: String,
    body: String?,
    spinner: Boolean,
    action: @Composable () -> Unit,
) {
    val colors = TvTheme.colors
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(TvSpacing.xxxl, Alignment.CenterVertically)) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(TvSpacing.l)) {
                if (spinner) LoadingBlob(Modifier.size(36.dp))
                Text(title, style = TvText.screenTitle, color = colors.onSurface, modifier = Modifier.semantics { heading() })
            }
            if (body != null) Text(body, style = TvText.settingTitle.copy(fontWeight = FontWeight.Normal), color = colors.onSurfaceMuted, modifier = Modifier.widthIn(max = 620.dp))
        }
        Row { action() }
    }
}

@Composable
private fun IdleView(focus: FocusRequester) {
    StepView(
        title = "Watch in sync",
        body = "Watch a video in sync with friends, at home or anywhere. Someone in the party " +
            "enters this TV’s code on their phone, then you confirm here.",
        spinner = false,
    ) {
        val context = LocalContext.current
        PillButton("Join a watch party", { TvTogether.startPairing(context) }, Modifier.focusRequester(focus))
    }
}

@Composable
private fun CodeView(code: TvTogether.State.Code, focus: FocusRequester) {
    val colors = TvTheme.colors
    Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(56.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(26.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Join a watch party", style = TvText.screenTitle.copy(fontSize = 46.sp, lineHeight = 50.sp), color = colors.onSurface, modifier = Modifier.semantics { heading() })
                Text(
                    "On a phone that’s in the party, open Watch Together and enter this code. You’ll confirm here before this TV joins.",
                    style = TvText.settingTitle.copy(fontWeight = FontWeight.Normal, lineHeight = 29.sp),
                    color = colors.onSurfaceMuted,
                    modifier = Modifier.widthIn(max = 620.dp),
                )
            }
            Box(
                Modifier.height(120.dp).background(colors.container, TvRadius.cardShape).padding(horizontal = 36.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    groupPartyCode(code.code),
                    maxLines = 1,
                    softWrap = false,
                    style = TvText.code.copy(fontSize = 68.sp, lineHeight = 76.sp),
                    color = colors.accent,
                )
            }
            CodeExpiry(code.expiresAtMs)
            PillButton("Cancel", { TvTogether.cancelPairing() }, Modifier.focusRequester(focus))
        }
        Column(
            Modifier.width(380.dp).background(colors.container, TvRadius.cardShape).padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            QrCode(code.qrSize, code.qrModules, displaySize = 248.dp)
            Text("Or scan it from the party screen", style = TvText.value.copy(fontSize = 18.sp), color = colors.onSurface)
        }
    }
}

/** "Expires in 8 minutes" with a bar that drains. Ticks once a second; only this node recomposes. */
@Composable
private fun CodeExpiry(expiresAtMs: Long) {
    val colors = TvTheme.colors
    val totalMs = remember(expiresAtMs) { (expiresAtMs - System.currentTimeMillis()).coerceAtLeast(1L) }
    var remainingMs by remember(expiresAtMs) { mutableLongStateOf(totalMs) }
    LaunchedEffect(expiresAtMs) {
        while (true) {
            remainingMs = expiresAtMs - System.currentTimeMillis()
            if (remainingMs <= 0L) break
            delay(1_000L)
        }
    }
    Column(Modifier.width(520.dp), verticalArrangement = Arrangement.spacedBy(TvSpacing.s)) {
        Box(
            Modifier.fillMaxWidth().height(6.dp).drawBehind {
                val radius = CornerRadius(3.dp.toPx())
                drawRoundRect(colors.outline, size = size, cornerRadius = radius)
                val fraction = (remainingMs.toFloat() / totalMs).coerceIn(0f, 1f)
                drawRoundRect(colors.accent, size = Size(size.width * fraction, size.height), cornerRadius = radius)
            },
        )
        Text(
            text = expiryLabel(remainingMs)?.let { "Expires in $it" } ?: "This code has expired. Cancel and try again.",
            style = TvText.caption,
            color = colors.onSurfaceMuted,
        )
    }
}

/**
 * "Join Alex's watch party?" card. [WatchTogetherPanel] shows it centred; it is public so a host
 * that wants it over whatever is on screen (the mockup) can mount it in a corner.
 */
@Composable
fun TogetherInviteCard(
    from: String,
    relay: String,
    onJoin: () -> Unit,
    onDecline: () -> Unit,
    modifier: Modifier = Modifier,
    joinFocus: FocusRequester? = null,
) {
    val colors = TvTheme.colors
    Column(
        modifier = modifier.width(460.dp).background(colors.container, TvRadius.cardShape).padding(32.dp),
        verticalArrangement = Arrangement.spacedBy(22.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(TvSpacing.l)) {
            Box(Modifier.size(52.dp).background(colors.tileBlue, TvRadius.circle), contentAlignment = Alignment.Center) {
                Image(rememberVectorPainter(TogetherIcons.People), null, Modifier.size(26.dp), colorFilter = ColorFilter.tint(colors.onTileBlue))
            }
            Text(
                "Join ${from.ifBlank { "someone" }}’s watch party?",
                style = TvText.cardHeadline.copy(fontSize = 26.sp, lineHeight = 31.sp),
                color = colors.onSurface,
                modifier = Modifier.weight(1f).semantics { heading() },
            )
        }
        Text(
            "This TV will play in sync with everyone in the party. Only join parties from people you know.",
            style = TvText.value.copy(fontWeight = FontWeight.Normal, lineHeight = 26.sp),
            color = colors.onSurfaceMuted,
        )
        Column(
            Modifier.fillMaxWidth().background(colors.surface, TvRadius.rowShape).padding(horizontal = 18.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(TvSpacing.xxs),
        ) {
            Text("The party runs on", style = TvText.small, color = colors.onSurfaceMuted)
            Text(relay, style = TvText.row, color = colors.onSurface, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(TvSpacing.m)) {
            PillButton("Join", onJoin, Modifier.weight(1f).then(if (joinFocus != null) Modifier.focusRequester(joinFocus) else Modifier))
            PillButton("Not now", onDecline, Modifier.weight(1f), style = PillStyle.Secondary)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PartyView(party: TogetherPartyUi, focus: FocusRequester) {
    val colors = TvTheme.colors
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(30.dp, Alignment.CenterVertically)) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                party.partyName,
                style = TvText.pageTitle.copy(fontSize = 50.sp, lineHeight = 55.sp),
                color = colors.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.semantics { heading() },
            )
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                val line = when (val s = party.status) {
                    is PartyStatus.Connecting -> "Connecting to ${s.relay}…"
                    is PartyStatus.NowPlaying -> "Now playing: ${s.title}"
                    PartyStatus.WaitingForHost -> "Waiting for the host to pick a video…"
                }
                if (party.status !is PartyStatus.NowPlaying) LoadingBlob(Modifier.size(26.dp))
                Text(line, style = TvText.cardTitle.copy(fontSize = 22.sp, fontWeight = FontWeight.Normal), color = colors.onSurfaceMuted, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
        if (party.members.isNotEmpty()) {
            Column(verticalArrangement = Arrangement.spacedBy(TvSpacing.m)) {
                Text("In the party", style = TvText.caption, color = colors.onSurfaceMuted)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(TvSpacing.m), verticalArrangement = Arrangement.spacedBy(TvSpacing.m)) {
                    party.members.forEach { MemberChip(it) }
                }
            }
        }
        if (party.waitingFor.isNotEmpty()) {
            Text(waitingLabel(party.waitingFor), style = TvText.caption, color = colors.onSurfaceMuted)
        }
        party.notice?.let { Text(it, style = TvText.caption, color = colors.error) }
        PillButton("Leave party", { TvTogether.leave() }, Modifier.focusRequester(focus))
    }
}

@Composable
private fun MemberChip(member: PartyMemberUi) {
    val colors = TvTheme.colors
    Row(
        Modifier.height(52.dp).background(colors.container, TvRadius.pill).padding(horizontal = 22.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(member.name, style = TvText.row, color = colors.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (member.isHost) Text("Host", style = TvText.small, color = colors.onSurfaceMuted)
    }
}

@Composable
private fun PairingServiceDialog(initial: String, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    val colors = TvTheme.colors
    var draft by remember { mutableStateOf(initial) }
    ThemedDialog(onDismissRequest = onDismiss) {
        Column(
            Modifier.width(520.dp).background(colors.container, TvRadius.cardShape).padding(32.dp),
            verticalArrangement = Arrangement.spacedBy(TvSpacing.l),
        ) {
            Text("Pairing service", style = TvText.cardHeadline, color = colors.onSurface)
            Text(
                "The address of the Watch Together pairing service, for example https://pair.example.workers.dev",
                style = TvText.value.copy(fontWeight = FontWeight.Normal, lineHeight = 25.sp),
                color = colors.onSurfaceMuted,
            )
            BasicTextField(
                value = draft,
                onValueChange = { draft = it },
                singleLine = true,
                cursorBrush = SolidColor(colors.accent),
                modifier = Modifier.fillMaxWidth().background(colors.containerHigh, TvRadius.rowShape).padding(16.dp),
                textStyle = TvText.row.copy(color = colors.onSurface),
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(TvSpacing.m, Alignment.End)) {
                PillButton("Cancel", onDismiss, style = PillStyle.Secondary)
                PillButton("Save", { onSave(draft) })
            }
        }
    }
}

/** Draws [modules] ([width] rows of `1` dark, `0` light) with a white quiet zone. */
@Composable
private fun QrCode(width: Int, modules: String, displaySize: Dp) {
    if (width <= 0 || modules.length != width * width) return
    Canvas(
        modifier = Modifier
            .size(displaySize)
            .background(Color.White, androidx.compose.foundation.shape.RoundedCornerShape(12.dp))
            .padding(12.dp),
    ) {
        val cell = size.minDimension / width
        for (row in 0 until width) {
            for (column in 0 until width) {
                if (modules[row * width + column] == '1') {
                    drawRect(Color.Black, Offset(column * cell, row * cell), Size(cell, cell))
                }
            }
        }
    }
}
