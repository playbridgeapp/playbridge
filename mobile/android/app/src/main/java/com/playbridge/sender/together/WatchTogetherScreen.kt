package com.playbridge.sender.together

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Forward10
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Smartphone
import androidx.compose.material.icons.filled.Tv
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
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
            "Friends and your TVs each play their own copy. Only play, pause and seek are " +
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

    PartyHeader(party)
    NowPlaying(party, canControl)
    Panel(title = "In the party") { Members(party) }
    Panel(title = "Add a device") { AddDevice(coordinator) }
    Panel { WatchHereRow(party.watching, PhoneTogether::setWatchHere) }
    if (party.host) Panel(title = "Host controls") { HostControls(party) }

    OutlinedButton(
        onClick = PhoneTogether::leave,
        modifier = Modifier.fillMaxWidth(),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
    ) { Text("Leave the watch party") }

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

@Composable
private fun PartyHeader(party: PhoneTogether.Party) {
    val context = LocalContext.current
    Panel {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Badge(Icons.Default.Groups, size = 48.dp)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    if (party.host) "Your watch party" else "${hostName(party) ?: "A"} watch party",
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

@Composable
private fun NowPlaying(party: PhoneTogether.Party, canControl: Boolean) {
    // Ticks the displayed position while playing.
    var now by remember { mutableLongStateOf(party.positionNow()) }
    LaunchedEffect(party) {
        while (true) {
            now = party.positionNow()
            delay(500)
        }
    }

    Panel {
        val item = party.item
        if (item == null) {
            Text("Nothing playing yet", style = MaterialTheme.typography.titleMedium)
            Text(
                if (party.canChangeVideo) {
                    "Open a video in the Browser and tap it in the cast sheet to play it for everyone."
                } else {
                    "Waiting for the host to pick a video."
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    item.title,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                // A live party shares play and pause only: no position, no seeking.
                Text(
                    listOfNotNull(item.site, if (party.live) "Live" else formatTime(now)).joinToString(" · "),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(24.dp, Alignment.CenterHorizontally),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (!party.live) {
                    IconButton(onClick = { PhoneTogether.seekBy(-10_000) }, enabled = canControl, modifier = Modifier.size(52.dp)) {
                        Icon(Icons.Default.Replay10, contentDescription = "Back 10 seconds", modifier = Modifier.size(30.dp))
                    }
                }
                FilledIconButton(
                    onClick = { if (party.playing) PhoneTogether.pause() else PhoneTogether.play() },
                    enabled = canControl,
                    modifier = Modifier.size(64.dp),
                ) {
                    Icon(
                        if (party.playing) Icons.Default.Pause else Icons.Default.PlayArrow,
                        contentDescription = if (party.playing) "Pause" else "Play",
                        modifier = Modifier.size(34.dp),
                    )
                }
                if (!party.live) {
                    IconButton(onClick = { PhoneTogether.seekBy(10_000) }, enabled = canControl, modifier = Modifier.size(52.dp)) {
                        Icon(Icons.Default.Forward10, contentDescription = "Forward 10 seconds", modifier = Modifier.size(30.dp))
                    }
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
private fun Members(party: PhoneTogether.Party) {
    if (party.members.isEmpty()) {
        Text("Nobody yet.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    val sharedByMe = party.item?.sharedBy == party.me
    party.members.forEach { (id, member) ->
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
                    "This phone".takeIf { id == party.me },
                    "Yours".takeIf { id in party.own },
                ).joinToString(" · ")
                if (role.isNotEmpty()) {
                    Text(role, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                }
            }
            if (sharedByMe && id !in party.own && member.kind == TogetherDeviceKind.Tv) {
                TextButton(onClick = { PhoneTogether.shareSignIn(id) }) { Text("Share my sign-in") }
            }
        }
    }
}

@Composable
private fun AddDevice(coordinator: ConnectionCoordinator) {
    val context = LocalContext.current
    var code by rememberSaveable { mutableStateOf("") }

    val tvName = coordinator.connectedTvName
    if (tvName != null && coordinator.tvSupportsWatchTogether) {
        FilledTonalButton(onClick = { PhoneTogether.addConnectedTv(coordinator) }, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Default.Tv, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text("Add $tvName", maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        HorizontalDivider()
    } else if (tvName != null) {
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
