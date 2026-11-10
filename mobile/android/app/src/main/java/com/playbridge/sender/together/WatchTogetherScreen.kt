package com.playbridge.sender.together

import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Forward10
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.playbridge.sender.connection.ConnectionCoordinator
import com.playbridge.shared.together.TogetherPolicy
import kotlinx.coroutines.delay

/** Dashboard → Watch together: start or join a party, add TVs, and control it. */
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
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            when (val current = state) {
                PhoneTogether.State.Idle -> StartView(coordinator)
                PhoneTogether.State.Starting -> Text("Connecting…", style = MaterialTheme.typography.titleMedium)
                is PhoneTogether.State.Ended -> {
                    Text(current.message, style = MaterialTheme.typography.titleMedium)
                    Button(onClick = PhoneTogether::dismissEnded) { Text("OK") }
                }
                is PhoneTogether.State.InParty -> PartyView(current.party, coordinator)
            }
        }
    }
}

@Composable
private fun StartView(coordinator: ConnectionCoordinator) {
    val context = LocalContext.current
    var link by remember { mutableStateOf("") }
    var watchHere by remember { mutableStateOf(PhoneTogether.watchHereByDefault(coordinator)) }
    var relay by remember { mutableStateOf(PhoneTogether.relayUrl(context)) }
    var editingRelay by remember { mutableStateOf(false) }

    Text(
        "Watch a video in sync with friends and your TVs, at home or anywhere. Each device plays " +
            "its own copy; only play, pause and seek go through the relay, end-to-end encrypted.",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    WatchHereRow(watchHere) { watchHere = it }
    Button(onClick = { PhoneTogether.start(context, watchHere) }, modifier = Modifier.fillMaxWidth()) {
        Text("Start a watch party")
    }
    HorizontalDivider()
    Text("Join with an invite link", style = MaterialTheme.typography.titleSmall)
    OutlinedTextField(
        value = link,
        onValueChange = { link = it },
        singleLine = true,
        placeholder = { Text("https://…/j/…") },
        modifier = Modifier.fillMaxWidth(),
    )
    OutlinedButton(
        onClick = { PhoneTogether.join(context, link, watchHere) },
        enabled = link.contains("/j/"),
        modifier = Modifier.fillMaxWidth(),
    ) { Text("Join") }
    HorizontalDivider()
    Text("Room relay", style = MaterialTheme.typography.titleSmall)
    Text(
        if (relay == PhoneTogether.SHARED_RELAY) {
            "PlayBridge's shared relay: up to 4 devices, 3 hours. Deploy your own free relay " +
                "on Cloudflare for bigger and longer parties."
        } else {
            relay
        },
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    TextButton(onClick = { editingRelay = true }) { Text("Change relay") }

    if (editingRelay) {
        var draft by remember { mutableStateOf(if (relay == PhoneTogether.SHARED_RELAY) "" else relay) }
        AlertDialog(
            onDismissRequest = { editingRelay = false },
            title = { Text("Room relay") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Your relay's address. Leave empty for PlayBridge's shared relay.")
                    OutlinedTextField(value = draft, onValueChange = { draft = it }, singleLine = true)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    PhoneTogether.setRelayUrl(context, draft)
                    relay = PhoneTogether.relayUrl(context)
                    editingRelay = false
                }) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { editingRelay = false }) { Text("Cancel") } },
        )
    }
}

/** Plays the party's video on this phone too, or only controls it. */
@Composable
private fun WatchHereRow(watching: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
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
private fun PartyView(party: PhoneTogether.Party, coordinator: ConnectionCoordinator) {
    val context = LocalContext.current
    var code by remember { mutableStateOf("") }
    // Ticks the displayed position while playing.
    var now by remember { mutableLongStateOf(party.positionNow()) }
    LaunchedEffect(party) {
        while (true) {
            now = party.positionNow()
            delay(500)
        }
    }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                if (party.host) "Your watch party" else "${hostName(party) ?: "A"} watch party",
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                if (party.connected) "On ${party.relay}" else "Connecting to ${party.relay}…",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedButton(onClick = {
                val send = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, party.invite)
                }
                context.startActivity(Intent.createChooser(send, "Invite to the watch party"))
            }) {
                Icon(Icons.Default.Share, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Share invite link")
            }
            WatchHereRow(party.watching, PhoneTogether::setWatchHere)
        }
    }

    // Now playing
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            val item = party.item
            if (item == null) {
                Text(
                    if (party.canChangeVideo) {
                        "Open a video in the Browser and tap it in the cast sheet to play it here."
                    } else {
                        "Waiting for the host to pick a video."
                    },
                    style = MaterialTheme.typography.bodyMedium,
                )
            } else {
                Text(item.title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                item.site?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                // A live party shares play and pause only: no position, no seeking.
                Text(if (party.live) "Live" else formatTime(now), style = MaterialTheme.typography.bodyLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (!party.live) {
                        IconButton(onClick = { PhoneTogether.seekBy(-10_000) }, enabled = !party.locked || party.host) {
                            Icon(Icons.Default.Replay10, contentDescription = "Back 10 seconds")
                        }
                    }
                    FilledIconButton(
                        onClick = { if (party.playing) PhoneTogether.pause() else PhoneTogether.play() },
                        enabled = !party.locked || party.host,
                    ) {
                        Icon(
                            if (party.playing) Icons.Default.Pause else Icons.Default.PlayArrow,
                            contentDescription = if (party.playing) "Pause" else "Play",
                        )
                    }
                    if (!party.live) {
                        IconButton(onClick = { PhoneTogether.seekBy(10_000) }, enabled = !party.locked || party.host) {
                            Icon(Icons.Default.Forward10, contentDescription = "Forward 10 seconds")
                        }
                    }
                }
            }
            if (party.waitingFor.isNotEmpty()) {
                Text("Waiting for ${party.waitingFor.joinToString { memberName(party, it) }}…")
            }
            if (party.host && party.continueWithout.isNotEmpty()) {
                party.continueWithout.forEach { member ->
                    TextButton(onClick = { PhoneTogether.continueWithout(member) }) {
                        Text("Continue without ${memberName(party, member)}")
                    }
                }
            }
            party.notice?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    }

    // Screens
    Text("Add a TV", style = MaterialTheme.typography.titleSmall)
    val tvName = coordinator.connectedTvName
    if (coordinator.tvSupportsWatchTogether && tvName != null) {
        Button(onClick = { PhoneTogether.addConnectedTv(coordinator) }, modifier = Modifier.fillMaxWidth()) {
            Text("Add $tvName")
        }
    } else if (tvName != null) {
        Text("$tvName needs a PlayBridge update to join.", style = MaterialTheme.typography.bodySmall)
    }
    Text(
        "Another TV: open Settings → Watch Together → Join a watch party on it, then enter its code.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = code,
            onValueChange = { code = it.uppercase().take(64) },
            singleLine = true,
            placeholder = { Text("ABC-234") },
            modifier = Modifier.weight(1f),
        )
        OutlinedButton(onClick = {
            PhoneTogether.addTvByCode(context, code)
            code = ""
        }, enabled = code.count(Char::isLetterOrDigit) >= 6) { Text("Invite") }
    }

    // Members
    Text("In the party", style = MaterialTheme.typography.titleSmall)
    party.members.forEach { (id, member) ->
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                member.name + when {
                    member.host -> " · host"
                    id in party.own -> " · yours"
                    else -> ""
                },
                modifier = Modifier.weight(1f),
            )
            val sharedByMe = party.item?.sharedBy == party.me
            if (sharedByMe && id !in party.own && member.kind == com.playbridge.shared.together.TogetherDeviceKind.Tv) {
                TextButton(onClick = { PhoneTogether.shareSignIn(id) }) { Text("Share my sign-in") }
            }
        }
    }

    // Host controls
    if (party.host) {
        HorizontalDivider()
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Lock controls")
                Text("Only you can play, pause and seek", style = MaterialTheme.typography.bodySmall)
            }
            Switch(checked = party.locked, onCheckedChange = { PhoneTogether.lock(it) })
        }
        Text("Who can change the video", style = MaterialTheme.typography.titleSmall)
        listOf(
            TogetherPolicy.OnlyHost to "Only me",
            TogetherPolicy.Ask to "Ask me",
            TogetherPolicy.Anyone to "Anyone",
        ).forEach { (policy, label) ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                RadioButton(selected = party.policy == policy, onClick = { PhoneTogether.policy(policy) })
                Text(label)
            }
        }
    }

    HorizontalDivider()
    OutlinedButton(onClick = PhoneTogether::leave, modifier = Modifier.fillMaxWidth()) { Text("Leave the watch party") }

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
