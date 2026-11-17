package com.playbridge.sender.together

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.AddCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.Forward10
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.Smartphone
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Tv
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.zIndex
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.playbridge.sender.cast.isOwnReceiverMemberMatch
import com.playbridge.sender.cast.isReceiverKind
import com.playbridge.sender.connection.ConnectionCoordinator
import com.playbridge.shared.together.TogetherDeviceKind
import com.playbridge.shared.together.TogetherPolicy
import kotlinx.coroutines.delay

/** Dashboard → Watch together: start or join a party, add devices, and control it. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WatchTogetherScreen(
    coordinator: ConnectionCoordinator,
    onMenuClick: () -> Unit,
) {
    val context = LocalContext.current
    val state by PhoneTogether.state.collectAsState()
    val toast by PhoneTogether.toasts.collectAsState()
    var showSaved by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { PhoneTogether.loadSavedParties(context) }
    LaunchedEffect(toast) {
        toast?.let {
            Toast.makeText(context, it, Toast.LENGTH_SHORT).show()
            PhoneTogether.consumeToast()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Watch together") },
                navigationIcon = {
                    IconButton(onClick = onMenuClick) {
                        com.playbridge.sender.ui.DashboardBlocksIcon(modifier = Modifier.size(22.dp))
                    }
                },
                actions = {
                    IconButton(onClick = { showSaved = true }) {
                        Icon(Icons.Default.History, contentDescription = "Recent parties")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            when (val current = state) {
                PhoneTogether.State.Idle -> StartView(coordinator)
                PhoneTogether.State.Starting -> BusyView("Connecting…")
                PhoneTogether.State.GettingCode -> BusyView("Getting a code…")
                is PhoneTogether.State.Code -> CodeView(current)
                is PhoneTogether.State.Invited -> InvitedView(current)
                is PhoneTogether.State.Ended -> EndedView(current.message)
                is PhoneTogether.State.InParty -> PartyView(current.party, coordinator)
            }
            Spacer(Modifier.height(8.dp))
        }
    }

    if (showSaved) {
        SavedPartiesSheet(
            current = (state as? PhoneTogether.State.InParty)?.party?.roomId,
            watchHere = PhoneTogether.watchHereByDefault(coordinator),
            onDismiss = { showSaved = false },
        )
    }

    val switchRequest by PhoneTogether.switchRequest.collectAsState()
    if (switchRequest != null) {
        AlertDialog(
            onDismissRequest = PhoneTogether::cancelSwitch,
            title = { Text("Join another party?") },
            text = { Text("You'll leave the party you're in now.") },
            confirmButton = {
                TextButton(onClick = { PhoneTogether.confirmSwitch(context) }) { Text("Leave and join") }
            },
            dismissButton = { TextButton(onClick = PhoneTogether::cancelSwitch) { Text("Stay") } },
        )
    }
}

// -- Not in a party

@Composable
private fun StartView(coordinator: ConnectionCoordinator) {
    val context = LocalContext.current
    var watchHere by rememberSaveable { mutableStateOf(PhoneTogether.watchHereByDefault(coordinator)) }
    var joinWith by rememberSaveable { mutableIntStateOf(0) }
    var link by rememberSaveable { mutableStateOf("") }

    Column(
        modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Badge(Icons.Default.Groups)
        Text(
            "Watch in step, anywhere",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center,
        )
        Text(
            "Friends and your other devices each play their own copy. Only play, pause and seek are " +
                "shared, end-to-end encrypted.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }

    Button(
        onClick = { PhoneTogether.start(context, watchHere) },
        modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
    ) { Text("Start a watch party") }

    PartyLinksNote()

    Panel(title = "Join a party") {
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            listOf("Invite link", "Code").forEachIndexed { index, label ->
                SegmentedButton(
                    selected = joinWith == index,
                    onClick = { joinWith = index },
                    shape = SegmentedButtonDefaults.itemShape(index, 2),
                ) { Text(label) }
            }
        }
        if (joinWith == 0) {
            OutlinedTextField(
                value = link,
                onValueChange = { link = it },
                singleLine = true,
                placeholder = { Text("Paste the invite link") },
                trailingIcon = {
                    IconButton(onClick = { clipboardText(context)?.let { link = it.trim() } }) {
                        Icon(Icons.Default.ContentPaste, contentDescription = "Paste")
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            )
            FilledTonalButton(
                onClick = { PhoneTogether.join(context, link, watchHere) },
                enabled = WatchTogetherLinks.isInvite(link),
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Join") }
        } else {
            Text(
                "This phone shows a code. Someone already in the party enters it under " +
                    "Add a device, and you confirm here.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            FilledTonalButton(
                onClick = { PhoneTogether.showCode(context, watchHere) },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Show my code") }
        }
    }

    Panel { WatchHereRow(watchHere) { watchHere = it } }
    RelayPanel()
}

@Composable
private fun RelayPanel() {
    val context = LocalContext.current
    var relay by remember { mutableStateOf(PhoneTogether.relayUrl(context)) }
    var editing by remember { mutableStateOf(false) }
    val shared = relay == PhoneTogether.SHARED_RELAY

    Panel(onClick = { editing = true }) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Icon(Icons.Default.Cloud, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Column(Modifier.weight(1f)) {
                Text("Room relay", style = MaterialTheme.typography.bodyLarge)
                Text(
                    if (shared) "PlayBridge shared: up to 4 devices, 3 hours" else relayHost(relay),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    if (editing) {
        var draft by remember { mutableStateOf(if (shared) "" else relay) }
        AlertDialog(
            onDismissRequest = { editing = false },
            title = { Text("Room relay") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        "Parties you start run on this relay. Deploy your own free relay on " +
                            "Cloudflare for bigger and longer parties, or leave this empty for " +
                            "PlayBridge's shared one.",
                    )
                    OutlinedTextField(
                        value = draft,
                        onValueChange = { draft = it },
                        singleLine = true,
                        placeholder = { Text("https://…") },
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    PhoneTogether.setRelayUrl(context, draft)
                    relay = PhoneTogether.relayUrl(context)
                    editing = false
                }) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { editing = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun BusyView(label: String) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(vertical = 64.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        CircularProgressIndicator()
        Text(label, style = MaterialTheme.typography.titleMedium)
    }
}

@Composable
private fun EndedView(message: String) {
    Panel {
        Text(message, style = MaterialTheme.typography.titleMedium)
        Button(onClick = PhoneTogether::dismissEnded, modifier = Modifier.fillMaxWidth()) { Text("OK") }
    }
}

/** This phone's code, for someone in a party to enter. */
@Composable
private fun CodeView(code: PhoneTogether.State.Code) {
    // The service's expiry time, when it sent one that is still ahead.
    var secondsLeft by remember(code) { mutableLongStateOf(secondsUntil(code.expiresAtMs)) }
    LaunchedEffect(code) {
        while (secondsLeft > 0) {
            delay(1_000)
            secondsLeft = secondsUntil(code.expiresAtMs)
        }
    }

    Panel {
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text("Your code", style = MaterialTheme.typography.titleMedium)
            CodeBoxes(code.code)
            QrCode(code.qrSize, code.qrModules, displaySize = 184.dp)
            Text(
                "Ask someone in the party to open Watch together, go to Add a device, and " +
                    "enter this code or scan it.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Text(
                if (secondsLeft > 0) "Expires in ${formatTime(secondsLeft * 1000)}" else "Expires in 10 minutes",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
    OutlinedButton(onClick = PhoneTogether::cancelCode, modifier = Modifier.fillMaxWidth()) { Text("Cancel") }
}

@Composable
private fun CodeBoxes(code: String) {
    Row(
        modifier = Modifier.clearAndSetSemantics { contentDescription = "Code ${code.toList().joinToString(" ")}" },
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        code.forEachIndexed { index, char ->
            if (index == code.length / 2 && code.length % 2 == 0) Spacer(Modifier.width(8.dp))
            Box(
                modifier = Modifier
                    .size(width = 44.dp, height = 56.dp)
                    .background(MaterialTheme.colorScheme.surfaceContainerHighest, RoundedCornerShape(12.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    char.toString(),
                    style = MaterialTheme.typography.headlineSmall.copy(
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                    ),
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

@Composable
private fun QrCode(width: Int, modules: String, displaySize: Dp) {
    if (width <= 0 || modules.length != width * width) return
    Canvas(
        modifier = Modifier
            .size(displaySize)
            .clip(RoundedCornerShape(16.dp))
            .background(Color.White)
            .padding(14.dp)
            .semantics { contentDescription = "QR code for this phone's code" },
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

@Composable
private fun InvitedView(invite: PhoneTogether.State.Invited) {
    Panel {
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Badge(Icons.Default.Groups)
            Text(
                "Join ${invite.from.ifBlank { "someone" }}'s watch party?",
                style = MaterialTheme.typography.titleLarge,
                textAlign = TextAlign.Center,
            )
            Text(
                "It runs on ${invite.relay.ifBlank { "their relay" }}. Only join parties from people you know.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
        Button(onClick = PhoneTogether::acceptInvite, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) {
            Text("Join")
        }
        OutlinedButton(onClick = PhoneTogether::cancelCode, modifier = Modifier.fillMaxWidth()) { Text("Not now") }
    }
}

// -- In a party

@Composable
private fun PartyView(party: PhoneTogether.Party, coordinator: ConnectionCoordinator) {
    val canControl = !party.locked || party.host
    val queue by PhoneTogether.queue.collectAsState()
    val queueNotice by PhoneTogether.queueNotice.collectAsState()

    PartyHeader(party)
    HostAwayNote(party)
    NowPlaying(party, canControl, hasQueueNext = queue.isNotEmpty())
    if (party.canChangeVideo || queue.isNotEmpty() || queueNotice != null) {
        UpNextPanel(party, queue, queueNotice)
    }
    Panel(title = "In the party") { Members(party) }
    Panel(title = "Add a device") { AddDevice(party, coordinator) }
    Panel { WatchHereRow(party.watching, PhoneTogether::setWatchHere) }
    if (party.host) Panel(title = "Host controls") { HostControls(party) }

    OutlinedButton(
        onClick = PhoneTogether::leave,
        modifier = Modifier.fillMaxWidth(),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
    ) { Text("Leave party") }

    party.requests.firstOrNull()?.let { request ->
        AlertDialog(
            onDismissRequest = {},
            title = { Text("${memberName(party, request.from)} wants to play") },
            text = { Text(request.title + (request.site?.let { " ($it)" } ?: "")) },
            confirmButton = { TextButton(onClick = { PhoneTogether.answer(request, allowed = true) }) { Text("Allow") } },
            dismissButton = {
                Row {
                    TextButton(onClick = { PhoneTogether.answer(request, allowed = true, always = true) }) {
                        Text("Always allow")
                    }
                    TextButton(onClick = { PhoneTogether.answer(request, allowed = false) }) { Text("Deny") }
                }
            },
        )
    }
}

/**
 * Only the host can act as host, so while the host is away nobody stands in. Waits a few
 * seconds after joining, while the host's hello is still on its way.
 */
@Composable
private fun HostAwayNote(party: PhoneTogether.Party) {
    val away = !party.host && party.connected && party.members.values.none { it.host }
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(away) {
        shown = false
        if (away) {
            delay(HOST_AWAY_GRACE_MS)
            shown = true
        }
    }
    if (!away || !shown) return
    Panel {
        Text("The host is away", style = MaterialTheme.typography.titleMedium)
        Text(
            hostAwayMessage(party),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private const val HOST_AWAY_GRACE_MS = 4_000L

internal fun hostAwayMessage(party: PhoneTogether.Party): String = when {
    party.locked -> "The video keeps playing. Controls stay locked until they're back."
    party.policy == TogetherPolicy.Anyone -> "The video keeps playing, and anyone can still change it."
    else -> "The video keeps playing. Nobody can change it until they're back."
}

/** Parties this phone joined recently and can go back to, as the same member. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SavedPartiesSheet(current: String?, watchHere: Boolean, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val parties by PhoneTogether.savedParties.collectAsState()
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(30_000)
            now = System.currentTimeMillis()
            PhoneTogether.loadSavedParties(context)
        }
    }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Recent parties", style = MaterialTheme.typography.titleLarge)
            if (parties.isEmpty()) {
                Text(
                    "Parties you start or join show up here until they end, so you can go back to them.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            parties.forEach { saved ->
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(saved.title(), style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            listOf(SavedParties.endsIn(saved, now), saved.relay).joinToString(" · "),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    if (saved.roomId == current) {
                        Text("You're in it", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                    } else {
                        FilledTonalButton(onClick = {
                            PhoneTogether.rejoin(context, saved, watchHere)
                            onDismiss()
                        }) { Text(if (saved.host) "Rejoin as host" else "Rejoin") }
                        IconButton(onClick = { PhoneTogether.forgetSaved(context, saved.roomId) }) {
                            Icon(Icons.Default.Close, contentDescription = "Remove ${saved.title()}")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PartyHeader(party: PhoneTogether.Party) {
    val context = LocalContext.current
    val saved by PhoneTogether.savedParties.collectAsState()
    // While the host is away, the name this phone saw before.
    val savedHost = saved.firstOrNull { it.roomId == party.roomId }?.hostName?.let { "$it's" }
    Panel {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Badge(Icons.Default.Groups, size = 48.dp)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    if (party.host) "Your watch party" else "${hostName(party) ?: savedHost ?: "A"} watch party",
                    style = MaterialTheme.typography.titleLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Box(
                        Modifier.size(8.dp).background(
                            if (party.connected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                            CircleShape,
                        ),
                    )
                    Text(
                        if (party.connected) "${deviceCount(party.members.size)} · ${party.relay}" else "Connecting to ${party.relay}…",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Button(
                onClick = {
                    val send = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_TEXT, party.invite)
                    }
                    context.startActivity(Intent.createChooser(send, "Invite to the watch party"))
                },
                modifier = Modifier.weight(1f),
            ) {
                Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Share invite link")
            }
            FilledTonalIconButton(onClick = {
                copyText(context, party.invite)
                Toast.makeText(context, "Invite link copied", Toast.LENGTH_SHORT).show()
            }) {
                Icon(Icons.Default.ContentCopy, contentDescription = "Copy invite link")
            }
        }
    }
}

internal data class NowPlayingUiState(
    val title: String?,
    val subtitle: String?,
    val hasVideo: Boolean,
    val live: Boolean,
    val playing: Boolean,
    val positionMs: Long,
    val durationMs: Long?,
    val showSeekBar: Boolean,
    val canControl: Boolean,
    val canStop: Boolean,
    val emptyHint: String,
)

internal fun mapNowPlayingState(party: PhoneTogether.Party, positionMs: Long): NowPlayingUiState {
    val item = party.item
    val canControl = party.host || !party.locked
    val durationMs = party.durationMs?.takeIf { it > 0 }
    if (item == null) {
        return NowPlayingUiState(
            title = null,
            subtitle = null,
            hasVideo = false,
            live = false,
            playing = false,
            positionMs = 0,
            durationMs = null,
            showSeekBar = false,
            canControl = canControl,
            canStop = false,
            emptyHint = if (party.canChangeVideo) {
                "Open a video in the Browser and tap it in the cast sheet to play it for everyone."
            } else {
                "Waiting for the host to pick a video."
            },
        )
    }
    val showSeekBar = !party.live && durationMs != null
    val subtitle = when {
        party.live -> listOfNotNull(item.site, "Live").joinToString(" · ")
        showSeekBar -> item.site
        else -> listOfNotNull(item.site, formatTime(positionMs)).joinToString(" · ")
    }
    return NowPlayingUiState(
        title = item.title,
        subtitle = subtitle,
        hasVideo = true,
        live = party.live,
        playing = party.playing,
        positionMs = positionMs.coerceAtLeast(0L),
        durationMs = durationMs,
        showSeekBar = showSeekBar,
        canControl = canControl,
        canStop = party.canChangeVideo,
        emptyHint = "",
    )
}

@Composable
private fun NowPlaying(party: PhoneTogether.Party, canControl: Boolean, hasQueueNext: Boolean = false) {
    // Ticks the displayed position while playing.
    var now by remember(party.item?.id) { mutableLongStateOf(party.positionNow()) }
    LaunchedEffect(party) {
        while (true) {
            now = party.positionNow()
            delay(500)
        }
    }
    var dragging by remember(party.item?.id) { mutableStateOf(false) }
    var dragPositionMs by remember(party.item?.id) { mutableLongStateOf(now) }

    val displayedPosition = if (dragging) dragPositionMs else now
    val ui = mapNowPlayingState(party, displayedPosition)

    Panel {
        if (!ui.hasVideo) {
            Text("Nothing playing yet", style = MaterialTheme.typography.titleMedium)
            Text(
                ui.emptyHint,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            PartyLinksNote()
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    ui.title.orEmpty(),
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                ui.subtitle?.let {
                    // A live party shares play and pause only: no position, no seeking.
                    Text(
                        it,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (ui.showSeekBar) {
                val totalMs = ui.durationMs ?: 1L
                val fraction = (displayedPosition.toFloat() / totalMs.toFloat()).coerceIn(0f, 1f)
                Column(modifier = Modifier.fillMaxWidth()) {
                    Slider(
                        value = fraction,
                        onValueChange = { next ->
                            dragging = true
                            dragPositionMs = (next * totalMs).toLong().coerceIn(0L, totalMs)
                        },
                        onValueChangeFinished = {
                            val target = dragPositionMs
                            dragging = false
                            PhoneTogether.seekTo(target)
                        },
                        enabled = canControl,
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            formatTime(displayedPosition),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            formatTime(totalMs),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterHorizontally),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (!ui.live) {
                    IconButton(onClick = { PhoneTogether.seekBy(-10_000) }, enabled = canControl, modifier = Modifier.size(52.dp)) {
                        Icon(Icons.Default.Replay10, contentDescription = "Back 10 seconds", modifier = Modifier.size(30.dp))
                    }
                }
                FilledIconButton(
                    onClick = { if (ui.playing) PhoneTogether.pause() else PhoneTogether.play() },
                    enabled = canControl,
                    modifier = Modifier.size(64.dp),
                ) {
                    Icon(
                        if (ui.playing) Icons.Default.Pause else Icons.Default.PlayArrow,
                        contentDescription = if (ui.playing) "Pause" else "Play",
                        modifier = Modifier.size(34.dp),
                    )
                }
                if (!ui.live) {
                    IconButton(onClick = { PhoneTogether.seekBy(10_000) }, enabled = canControl, modifier = Modifier.size(52.dp)) {
                        Icon(Icons.Default.Forward10, contentDescription = "Forward 10 seconds", modifier = Modifier.size(30.dp))
                    }
                }
                if (party.canChangeVideo) {
                    IconButton(
                        onClick = { PhoneTogether.playNextInQueueNow() },
                        enabled = hasQueueNext,
                        modifier = Modifier.size(48.dp),
                    ) {
                        Icon(Icons.Default.SkipNext, contentDescription = "Play the next video now", modifier = Modifier.size(28.dp))
                    }
                }
            }
            if (ui.canStop) {
                FilledTonalButton(
                    onClick = { PhoneTogether.stopVideo() },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Default.Stop, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Stop video")
                }
            }
            if (!canControl) {
                Text(
                    "The host locked the controls.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (party.waitingFor.isNotEmpty()) {
            Text(
                "Waiting for ${party.waitingFor.joinToString { memberName(party, it) }}…",
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        if (party.host) {
            party.continueWithout.forEach { member ->
                FilledTonalButton(onClick = { PhoneTogether.continueWithout(member) }, modifier = Modifier.fillMaxWidth()) {
                    Text("Continue without ${memberName(party, member)}", maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        party.notice?.let {
            Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
        }
    }
}

@Composable
private fun UpNextPanel(
    party: PhoneTogether.Party,
    queue: List<PartyQueueItem>,
    queueNotice: String?,
) {
    var draggedId by remember { mutableStateOf<String?>(null) }
    var dragOffsetPx by remember { mutableFloatStateOf(0f) }
    var itemHeightPx by remember { mutableIntStateOf(0) }

    Panel {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                "UP NEXT · ${queue.size}",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.SemiBold,
            )
            if (queue.isNotEmpty() && party.canChangeVideo) {
                TextButton(onClick = PhoneTogether::clearQueue) {
                    Text("Clear")
                }
            }
        }
        queueNotice?.let { notice ->
            Text(notice, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
        if (queue.isEmpty()) {
            Text(
                "Nothing queued yet. Tap Add to party queue from the cast sheet to build Up next.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                queue.forEachIndexed { index, item ->
                    androidx.compose.runtime.key(item.id) {
                        val currentIndex by rememberUpdatedState(index)
                        val totalItems by rememberUpdatedState(queue.size)
                        val isDragged = draggedId == item.id
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .zIndex(if (isDragged) 1f else 0f)
                                .graphicsLayer { translationY = if (isDragged) dragOffsetPx else 0f }
                                .onSizeChanged { size ->
                                    if (size.height > 0) itemHeightPx = size.height
                                },
                            shape = RoundedCornerShape(14.dp),
                            color = MaterialTheme.colorScheme.surfaceContainerHigh,
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 8.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                if (party.canChangeVideo && queue.size > 1) {
                                    Box(
                                        modifier = Modifier
                                            .size(36.dp)
                                            .semantics { contentDescription = "Drag to reorder" }
                                            .pointerInput(item.id) {
                                                detectVerticalDragGestures(
                                                    onDragStart = {
                                                        draggedId = item.id
                                                        dragOffsetPx = 0f
                                                    },
                                                    onDragEnd = {
                                                        draggedId = null
                                                        dragOffsetPx = 0f
                                                    },
                                                    onDragCancel = {
                                                        draggedId = null
                                                        dragOffsetPx = 0f
                                                    },
                                                    onVerticalDrag = { change, dragAmount ->
                                                        change.consume()
                                                        dragOffsetPx += dragAmount
                                                        val step = (itemHeightPx.takeIf { it > 0 } ?: 72).toFloat()
                                                        val idx = currentIndex
                                                        if (dragOffsetPx > step * 0.6f && idx + 1 < totalItems) {
                                                            PhoneTogether.moveQueueItem(idx, idx + 1)
                                                            dragOffsetPx -= step
                                                        } else if (dragOffsetPx < -step * 0.6f && idx > 0) {
                                                            PhoneTogether.moveQueueItem(idx, idx - 1)
                                                            dragOffsetPx += step
                                                        }
                                                    },
                                                )
                                            },
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        Icon(
                                            Icons.Default.DragHandle,
                                            contentDescription = "Drag to reorder",
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.size(20.dp),
                                        )
                                    }
                                }
                                Column(Modifier.weight(1f).padding(start = if (party.canChangeVideo && queue.size > 1) 0.dp else 6.dp)) {
                                    Text(
                                        item.title,
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.Medium,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    item.site?.let { site ->
                                        Text(
                                            site,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    }
                                }
                                if (party.canChangeVideo) {
                                    IconButton(onClick = { PhoneTogether.playQueuedItemNow(item.id) }) {
                                        Icon(
                                            Icons.Default.PlayArrow,
                                            contentDescription = "Play ${item.title} now",
                                            tint = MaterialTheme.colorScheme.primary,
                                        )
                                    }
                                }
                                IconButton(onClick = { PhoneTogether.removeFromQueue(item.id) }) {
                                    Icon(
                                        Icons.Default.Close,
                                        contentDescription = "Remove ${item.title}",
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
        Text(
            "Up next is kept on this phone. The next video starts when this one ends. If you leave the party, the list goes with you.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

internal fun orderedPartyMembers(party: PhoneTogether.Party): List<Pair<String, PhoneTogether.Member>> =
    party.members.entries
        .sortedWith(
            compareByDescending<Map.Entry<String, PhoneTogether.Member>> { it.value.host }
                .thenByDescending { it.key == party.me }
                .thenBy { it.value.name.lowercase() },
        )
        .map { it.key to it.value }

@Composable
private fun Members(party: PhoneTogether.Party) {
    if (party.members.isEmpty()) {
        Text("Nobody yet.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    val sharedByMe = party.item?.sharedBy == party.me
    orderedPartyMembers(party).forEach { (id, member) ->
        Row(
            modifier = Modifier.fillMaxWidth().heightIn(min = 40.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Icon(
                when (member.kind) {
                    TogetherDeviceKind.Tv -> Icons.Default.Tv
                    TogetherDeviceKind.Phone -> Icons.Default.Smartphone
                    TogetherDeviceKind.Desktop, TogetherDeviceKind.Cli -> Icons.Default.Computer
                },
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Column(Modifier.weight(1f)) {
                Text(member.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val role = listOfNotNull(
                    "Host".takeIf { member.host },
                    "You".takeIf { id == party.me },
                    "Yours".takeIf { id != party.me && id in party.own },
                ).joinToString(" · ")
                if (role.isNotEmpty()) {
                    Text(role, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                }
            }
            if (sharedByMe && id != party.me && id !in party.own && member.kind.isReceiverKind()) {
                TextButton(onClick = { PhoneTogether.shareSignIn(id) }) { Text("Share my sign-in") }
            }
        }
    }
}

@Composable
private fun AddDevice(party: PhoneTogether.Party, coordinator: ConnectionCoordinator) {
    val context = LocalContext.current
    var code by rememberSaveable { mutableStateOf("") }
    val ownIdentities by PhoneTogether.ownTvIdentities.collectAsState()
    val connectedIdentity = coordinator.connectedTvIdentity
    val alreadyInParty = connectedIdentity != null && isOwnReceiverMemberMatch(
        name = connectedIdentity.name,
        uuid = connectedIdentity.uuid,
        endpointKey = connectedIdentity.endpointKey,
        party = party,
        ownTvIdentities = ownIdentities,
    )

    val tvName = coordinator.connectedTvName
    if (tvName != null && coordinator.tvSupportsWatchTogether && !alreadyInParty) {
        FilledTonalButton(onClick = { PhoneTogether.addConnectedTv(coordinator) }, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Default.AddCircle, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text("Add $tvName", maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        HorizontalDivider()
    } else if (tvName != null && !alreadyInParty) {
        Text(
            "$tvName needs a PlayBridge update to join.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    Text(
        "With a code: on the other TV, phone or computer, open Watch together and choose " +
            "to join with a code. Enter it here.",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = code,
            // Also takes the link from a device's QR code, so it isn't cut to code length.
            onValueChange = { code = it.take(MAX_CODE_ENTRY) },
            singleLine = true,
            placeholder = { Text("ABC-234") },
            textStyle = MaterialTheme.typography.titleMedium.copy(fontFamily = FontFamily.Monospace),
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters, autoCorrectEnabled = false),
            modifier = Modifier.weight(1f),
        )
        Button(
            onClick = {
                PhoneTogether.addByCode(context, code)
                code = ""
            },
            enabled = code.count(Char::isLetterOrDigit) >= 6,
        ) { Text("Invite") }
    }
}

@Composable
private fun HostControls(party: PhoneTogether.Party) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(Modifier.weight(1f)) {
            Text("Lock controls", style = MaterialTheme.typography.bodyLarge)
            Text(
                "Only you can play, pause and seek.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = party.locked, onCheckedChange = { PhoneTogether.lock(it) })
    }
    HorizontalDivider()
    Text("Who can change the video", style = MaterialTheme.typography.bodyLarge)
    val policies = listOf(
        TogetherPolicy.OnlyHost to "Only me",
        TogetherPolicy.Ask to "Ask me",
        TogetherPolicy.Anyone to "Anyone",
    )
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        policies.forEachIndexed { index, (policy, label) ->
            SegmentedButton(
                selected = party.policy == policy,
                onClick = { PhoneTogether.policy(policy) },
                shape = SegmentedButtonDefaults.itemShape(index, policies.size),
            ) { Text(label) }
        }
    }
}

// -- Shared pieces

/** Plays the party's video on this phone too, or only controls it. */
@Composable
private fun WatchHereRow(watching: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Icon(Icons.Default.Smartphone, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Column(Modifier.weight(1f)) {
            Text("Watch on this phone", style = MaterialTheme.typography.bodyLarge)
            Text(
                if (watching) "The video plays here, in step with everyone." else "This phone is a remote.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = watching, onCheckedChange = onChange)
    }
}

@Composable
private fun PartyLinksNote() {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Icon(
            Icons.Default.Info,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 2.dp).size(16.dp),
        )
        Text(
            "Each device loads the video itself. Public links, and sites that let several " +
                "devices watch at once, work best. Links tied to one device or one IP address, " +
                "and files on this phone, won't play for others.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun Panel(
    title: String? = null,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val shape = RoundedCornerShape(20.dp)
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
        shape = shape,
        color = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            title?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            content()
        }
    }
}

@Composable
private fun Badge(icon: ImageVector, size: Dp = 64.dp) {
    Box(
        modifier = Modifier.size(size).background(MaterialTheme.colorScheme.primaryContainer, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onPrimaryContainer,
            modifier = Modifier.size(size / 2),
        )
    }
}

private const val MAX_CODE_ENTRY = 512

private fun clipboardText(context: Context): String? {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return null
    return clipboard.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(context)?.toString()
}

private fun copyText(context: Context, text: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
    clipboard.setPrimaryClip(ClipData.newPlainText("Watch party invite", text))
}

private fun relayHost(relay: String): String =
    relay.substringAfter("://").trimEnd('/').ifEmpty { relay }

private fun secondsUntil(epochMs: Long): Long =
    ((epochMs - System.currentTimeMillis()) / 1000).coerceIn(0, 3600)

private fun deviceCount(count: Int): String = if (count == 1) "1 device" else "$count devices"

private fun hostName(party: PhoneTogether.Party): String? =
    party.members.values.firstOrNull { it.host }?.name?.let { "$it's" }

private fun memberName(party: PhoneTogether.Party, member: String): String =
    party.members[member]?.name ?: "a device"

private fun formatTime(ms: Long): String {
    val total = (ms / 1000).coerceAtLeast(0)
    val hours = total / 3600
    val minutes = (total % 3600) / 60
    val seconds = total % 60
    return if (hours > 0) "%d:%02d:%02d".format(hours, minutes, seconds) else "%d:%02d".format(minutes, seconds)
}
