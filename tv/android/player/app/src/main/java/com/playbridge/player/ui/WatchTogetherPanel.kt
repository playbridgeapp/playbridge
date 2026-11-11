package com.playbridge.player.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Button
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.ListItem
import androidx.tv.material3.ListItemDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.OutlinedButton
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.playbridge.player.together.TvTogether

/** Settings → Watch Together: join a party by code, and see or leave the current one. */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun WatchTogetherPanel() {
    val context = LocalContext.current
    val state by TvTogether.state.collectAsState()
    var pairingUrl by remember { mutableStateOf(TvTogether.pairingUrl(context)) }
    var showUrlDialog by remember { mutableStateOf(false) }

    // Each step replaces the button that was just pressed. Move focus to the new
    // step's main button, or the TV sends it to the sidebar. Not on first show, so
    // opening this category leaves focus in the category list.
    val focus = remember { FocusRequester() }
    var shownStep by remember { mutableStateOf<kotlin.reflect.KClass<*>?>(null) }
    LaunchedEffect(state::class) {
        val previous = shownStep
        shownStep = state::class
        if (previous != null && previous != state::class) {
            kotlinx.coroutines.android.awaitFrame()
            runCatching { focus.requestFocus() }
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
        when (val current = state) {
            TvTogether.State.Idle -> {
                Text(
                    "Watch a video in sync with friends, at home or anywhere. Someone in the party " +
                        "enters this TV's code on their phone, then you confirm here.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                ListItem(
                    selected = false,
                    onClick = { TvTogether.startPairing(context) },
                    headlineContent = { Text("Join a watch party") },
                    supportingContent = { Text("Show a code to join with") },
                    scale = ListItemDefaults.scale(focusedScale = 1.02f),
                    modifier = Modifier.focusRequester(focus),
                )
            }
            TvTogether.State.Registering -> {
                Text("Getting a code…", style = MaterialTheme.typography.titleMedium)
                OutlinedButton(onClick = { TvTogether.cancelPairing() }, modifier = Modifier.focusRequester(focus)) {
                    Text("Cancel")
                }
            }
            is TvTogether.State.Code -> CodeView(current, focus)
            is TvTogether.State.Invited -> InviteView(current, focus)
            is TvTogether.State.InParty -> PartyView(current, focus)
            is TvTogether.State.Ended -> {
                Text(current.message, style = MaterialTheme.typography.titleMedium)
                ListItem(
                    selected = false,
                    onClick = { TvTogether.cancelPairing() },
                    headlineContent = { Text("OK") },
                    scale = ListItemDefaults.scale(focusedScale = 1.02f),
                    modifier = Modifier.focusRequester(focus),
                )
            }
        }

        if (state !is TvTogether.State.InParty) {
            ListItem(
                selected = false,
                onClick = { showUrlDialog = true },
                headlineContent = { Text("Pairing service") },
                supportingContent = { Text(pairingUrl.ifEmpty { "Not set" }) },
                scale = ListItemDefaults.scale(focusedScale = 1.02f),
            )
        }
    }

    if (showUrlDialog) {
        var draft by remember { mutableStateOf(pairingUrl) }
        com.playbridge.player.ui.theme.ThemedDialog(onDismissRequest = { showUrlDialog = false }) {
            Surface(shape = MaterialTheme.shapes.medium, modifier = Modifier.width(440.dp)) {
                Column(modifier = Modifier.padding(32.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text("Pairing service", style = MaterialTheme.typography.headlineSmall)
                    Text(
                        "The address of the Watch Together pairing service, for example " +
                            "https://pair.example.workers.dev",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    BasicTextField(
                        value = draft,
                        onValueChange = { draft = it },
                        singleLine = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.shapes.small)
                            .padding(16.dp),
                        textStyle = TextStyle(color = MaterialTheme.colorScheme.onSurface, fontSize = 16.sp),
                    )
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        Button(onClick = { showUrlDialog = false }, modifier = Modifier.padding(end = 8.dp)) {
                            Text("Cancel")
                        }
                        Button(onClick = {
                            TvTogether.setPairingUrl(context, draft)
                            pairingUrl = TvTogether.pairingUrl(context)
                            showUrlDialog = false
                        }) {
                            Text("Save")
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun CodeView(code: TvTogether.State.Code, focus: FocusRequester) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(32.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        QrCode(code.qrSize, code.qrModules, displaySize = 180.dp)
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("On a phone in the party, open Watch Together and enter:", style = MaterialTheme.typography.bodyLarge)
            Text(
                code.code.chunked(3).joinToString("-"),
                maxLines = 1,
                softWrap = false,
                style = MaterialTheme.typography.headlineLarge.copy(
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                ),
                color = MaterialTheme.colorScheme.primary,
            )
            Text("Or scan the code. It expires in 10 minutes.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedButton(onClick = { TvTogether.cancelPairing() }, modifier = Modifier.focusRequester(focus)) {
                Text("Cancel")
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun InviteView(invite: TvTogether.State.Invited, focus: FocusRequester) {
    Text(
        "Join ${invite.from.ifBlank { "someone" }}'s watch party?",
        style = MaterialTheme.typography.headlineSmall,
    )
    Text(
        "The party runs on ${invite.relay}. Only join parties from people you know.",
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Button(onClick = { TvTogether.accept() }, modifier = Modifier.focusRequester(focus)) { Text("Join") }
        OutlinedButton(onClick = { TvTogether.decline() }) { Text("Not now") }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun PartyView(party: TvTogether.State.InParty, focus: FocusRequester) {
    val host = party.hostName?.let { "$it's watch party" } ?: "Watch party"
    Text(host, style = MaterialTheme.typography.headlineSmall)
    Text(
        when {
            !party.connected -> "Connecting to ${party.relay}…"
            party.title != null -> "Now playing: ${party.title}"
            else -> "Waiting for the host to pick a video."
        },
        style = MaterialTheme.typography.bodyLarge,
    )
    if (party.members.isNotEmpty()) {
        Text(
            "In the party: ${party.members.values.joinToString()}",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    if (party.waitingFor.isNotEmpty()) {
        Text("Waiting for ${party.waitingFor.joinToString()}…", color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    party.notice?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    ListItem(
        selected = false,
        onClick = { TvTogether.leave() },
        headlineContent = { Text("Leave the watch party") },
        scale = ListItemDefaults.scale(focusedScale = 1.02f),
        modifier = Modifier.focusRequester(focus),
    )
}

/** Draws [modules] ([width] rows of `1` dark, `0` light) with a white quiet zone. */
@Composable
private fun QrCode(width: Int, modules: String, displaySize: Dp) {
    if (width <= 0 || modules.length != width * width) return
    Canvas(
        modifier = Modifier
            .size(displaySize)
            .background(Color.White)
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
