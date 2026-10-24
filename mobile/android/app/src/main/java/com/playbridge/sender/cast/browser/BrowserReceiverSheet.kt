package com.playbridge.sender.cast.browser

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.playbridge.sender.cast.PairingCodeInput
import com.playbridge.sender.cast.PlayOnGreen
import com.playbridge.sender.cast.PlayOnSectionLabel
import com.playbridge.sender.connection.ConnectionViewModel
import com.playbridge.sender.connection.NetworkStatusRepository
import com.playbridge.sender.ui.SectionLabel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.koin.compose.koinInject
import java.util.Locale
import java.util.concurrent.TimeUnit

@Composable
fun BrowserReceiverSheet(
    viewModel: ConnectionViewModel,
    onDismiss: () -> Unit,
    onCastHere: (() -> Unit)? = null,
    embedded: Boolean = false,
) {
    val browserRepo: BrowserReceiverRepository = koinInject()
    val hostState by browserRepo.state.collectAsState()
    val networkStatus by viewModel.networkStatus.collectAsState()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    BrowserReceiverFrame(onDismiss, embedded) {
        Column(Modifier.fillMaxWidth()) {
            if (!embedded) Row(
                Modifier.fillMaxWidth().padding(start = 12.dp, end = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onDismiss) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                Text("Browser on a TV", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold)
                BrowserHostStopAction(hostState.running, hostState.busy) {
                    scope.launch {
                        if (hostState.ready.isNotEmpty()) viewModel.disconnectExternalTarget()
                        browserRepo.stopHost()
                    }
                }
            }
            BrowserReceiverContent(
                hostState = hostState,
                onLocalNetwork = networkStatus.onLocalNetwork,
                onStart = { scope.launch { browserRepo.startHost() } },
                onCopy = { copyToClipboard(context, it) },
                onApprove = { request, code -> browserRepo.approve(request.sessionId, code).getOrThrow() },
                onPlayHere = { session ->
                    viewModel.selectBrowserTarget(session.toTvDevice(browserRepo.lanHostIp(), hostState.port))
                    onCastHere?.invoke()
                    onDismiss()
                },
                onDisconnect = { session -> scope.launch { browserRepo.disconnect(session.sessionId) } },
                onForget = { session -> scope.launch { browserRepo.forget(session.receiverId) } },
                modifier = if (embedded) Modifier else Modifier
                    .verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 28.dp),
            )
        }
    }

    // After approve, activate the browser as NOW when the host confirms connected.
    val activate by browserRepo.activateSession.collectAsState()
    LaunchedEffect(activate) {
        val session = activate ?: return@LaunchedEffect
        viewModel.selectBrowserTarget(session.toTvDevice(browserRepo.lanHostIp(), hostState.port))
        browserRepo.clearActivateSession()
        onCastHere?.invoke()
    }
}

@Composable
internal fun BrowserHostStopAction(running: Boolean, busy: Boolean, onStop: () -> Unit) {
    var confirm by remember { mutableStateOf(false) }
    if (running) TextButton(onClick = { confirm = true }, enabled = !busy) { Text("Stop") }
    if (confirm && running) AlertDialog(
        onDismissRequest = { confirm = false },
        title = { Text("Stop hosting?") },
        text = { Text("The TV browser will disconnect.") },
        confirmButton = {
            TextButton(onClick = { confirm = false; onStop() }) {
                Text("Stop host", color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = { TextButton(onClick = { confirm = false }) { Text("Cancel") } },
    )
}

@Composable
internal fun BrowserReceiverContent(
    hostState: BrowserHostState,
    onLocalNetwork: Boolean,
    onStart: () -> Unit,
    onCopy: (String) -> Unit,
    onApprove: suspend (BrowserPairingRequest, String) -> Unit,
    onPlayHere: (BrowserReadySession) -> Unit,
    onDisconnect: (BrowserReadySession) -> Unit,
    onForget: (BrowserReadySession) -> Unit,
    modifier: Modifier = Modifier,
) {
    var dismissedRequests by remember { mutableStateOf(emptySet<BrowserPairingRequest>()) }
    val pending = hostState.pending.filterNot { it in dismissedRequests }
    // Opening this page starts hosting once; stopping it must not immediately restart it.
    LaunchedEffect(onLocalNetwork) {
        if (onLocalNetwork && !hostState.running && !hostState.busy) onStart()
    }
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Use any TV browser as a receiver. This phone hosts a page on your Wi‑Fi.",
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        when {
            !onLocalNetwork -> Text("Connect this phone to Wi‑Fi to use a TV browser.",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            hostState.busy && !hostState.running -> BrowserProgress("Starting…")
            else -> {
                hostState.lastError?.let { error ->
                    Text(error, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
                    if (!hostState.running) Button(onClick = onStart) { Text("Try again") }
                }
                if (hostState.running) {
                    val address = hostState.primaryUrl?.trimEnd('/')
                    if (address != null) BrowserHostUrlCard(address, onCopy)
                    else BrowserProgress("Starting…")
                }
            }
        }
        Text("1. Connect the TV to the same Wi‑Fi\n2. Open the address above in the TV's browser\n3. Enter the 6‑digit code shown on the TV",
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (hostState.running && onLocalNetwork) {
            if (pending.isEmpty() && hostState.ready.isEmpty()) Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                shape = RoundedCornerShape(12.dp),
            ) {
                BrowserProgress("Waiting for the TV…", Modifier.padding(14.dp))
            }
            pending.forEachIndexed { index, request ->
                key(request.sessionId, request.expiresAtMs) {
                    BrowserPairingCard(request, autoFocus = index == 0,
                        onApprove = { code -> onApprove(request, code) },
                        onCancel = { dismissedRequests = dismissedRequests + request })
                }
            }
            if (hostState.ready.isNotEmpty()) {
                PlayOnSectionLabel("CONNECTED")
                hostState.ready.forEach { session ->
                    key(session.sessionId) {
                        ReadySessionRow(session, onPlayHere = { onPlayHere(session) },
                            onDisconnect = { onDisconnect(session) }, onForget = { onForget(session) })
                    }
                }
            }
        }
    }
}

@Composable
private fun BrowserProgress(text: String, modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
        Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun BrowserHostUrlCard(address: String, onCopy: (String) -> Unit) {
    Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Open this on the TV's browser", style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            SelectionContainer {
                Text(address, style = MaterialTheme.typography.headlineSmall.copy(
                    fontFamily = FontFamily.Monospace, fontSize = 20.sp, fontWeight = FontWeight.Bold))
            }
            TextButton(onClick = { onCopy(address) }) {
                Icon(Icons.Default.ContentCopy, null, Modifier.size(16.dp))
                Spacer(Modifier.size(8.dp))
                Text("Copy address")
            }
        }
    }
}

@Composable
internal fun BrowserPairingCard(
    request: BrowserPairingRequest,
    autoFocus: Boolean,
    onApprove: suspend (String) -> Unit,
    onCancel: () -> Unit,
) {
    var code by remember(request.sessionId, request.expiresAtMs) { mutableStateOf("") }
    var error by remember(request.sessionId, request.expiresAtMs) { mutableStateOf<String?>(null) }
    var submitting by remember(request.sessionId, request.expiresAtMs) { mutableStateOf(false) }
    var remaining by remember(request.sessionId, request.expiresAtMs) { mutableStateOf(request.remainingMs) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(request.sessionId, request.expiresAtMs) {
        while (remaining > 0) {
            delay(1_000)
            remaining = request.remainingMs
        }
    }
    Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp)) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Default.Language, null, tint = MaterialTheme.colorScheme.primary)
                Text("${request.name} wants to connect", fontWeight = FontWeight.SemiBold)
            }
            PairingCodeInput(code, { digits ->
                code = digits
                error = null
                if (digits.length == 6 && !submitting && remaining > 0) {
                    submitting = true
                    scope.launch {
                        try {
                            onApprove(digits)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            code = ""
                            error = humanizePairError(e.message)
                        } finally {
                            submitting = false
                        }
                    }
                }
            }, isError = error != null, enabled = !submitting && remaining > 0, autoFocus = autoFocus)
            error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
            Text(if (remaining > 0) "Expires in ${formatCountdown(remaining)}" else "Code expired — reload the page on the TV.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                if (submitting) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                TextButton(onClick = onCancel, enabled = !submitting) { Text("Cancel") }
            }
        }
    }
}

@Composable
private fun ReadySessionRow(
    session: BrowserReadySession,
    onPlayHere: () -> Unit,
    onDisconnect: () -> Unit,
    onForget: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Icon(Icons.Default.Language, null, tint = MaterialTheme.colorScheme.primary)
        Column(Modifier.weight(1f)) {
            Text(session.name, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Spacer(Modifier.size(7.dp).background(PlayOnGreen, CircleShape))
                Text("Ready", style = MaterialTheme.typography.bodySmall, color = PlayOnGreen)
            }
        }
        FilledTonalButton(onClick = onPlayHere) { Text("Play here") }
        Column {
            IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, "More options for ${session.name}") }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(text = { Text("Disconnect", color = MaterialTheme.colorScheme.error) },
                    onClick = { menu = false; onDisconnect() })
                DropdownMenuItem(text = { Text("Forget", color = MaterialTheme.colorScheme.error) },
                    onClick = { menu = false; onForget() })
            }
        }
    }
}

/** Compact section for Devices screen when host is running or as entry. */
@Composable
fun BrowserOnTvSection(
    hostState: BrowserHostState,
    @Suppress("UNUSED_PARAMETER") networkStatus: NetworkStatusRepository.Status,
    onOpenSetup: () -> Unit,
    onSelectReady: (BrowserReadySession) -> Unit,
    onEnterCode: (BrowserPairingRequest) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionLabel("Browser on TV")
        if (!hostState.running) {
            Text(
                text = "Cast to a smart TV browser without a PlayBridge app.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Button(onClick = onOpenSetup, modifier = Modifier.fillMaxWidth()) {
                Text("Cast to browser…")
            }
        } else {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onOpenSetup),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Hosting · ${hostState.primaryUrl ?: "…"}",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (hostState.pending.isNotEmpty()) {
                            Text(
                                "${hostState.pending.size} waiting to pair",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                    TextButton(onClick = onOpenSetup) { Text("Manage") }
                }
            }
            hostState.ready.forEach { session ->
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onSelectReady(session) },
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Default.Language, contentDescription = null)
                        Spacer(Modifier.size(10.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(session.name, fontWeight = FontWeight.SemiBold)
                            Text(
                                "Ready",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
            hostState.pending.forEach { request ->
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onEnterCode(request) },
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(request.name, fontWeight = FontWeight.SemiBold)
                            Text(
                                "Waiting · Enter code",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                        TextButton(onClick = { onEnterCode(request) }) { Text("Enter code") }
                    }
                }
            }
        }
    }
}

private fun formatCountdown(ms: Long): String {
    val totalSec = TimeUnit.MILLISECONDS.toSeconds(ms).coerceAtLeast(0)
    val min = totalSec / 60
    val sec = totalSec % 60
    return String.format(Locale.US, "%d:%02d", min, sec)
}

private fun copyToClipboard(context: Context, text: String) {
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    cm.setPrimaryClip(ClipData.newPlainText("Browser receiver URL", text))
}

private fun humanizePairError(raw: String?): String {
    val msg = raw.orEmpty().lowercase(Locale.US)
    return when {
        "incorrect" in msg || "invalid" in msg || "wrong" in msg || "mismatch" in msg ->
            "Incorrect code"
        "attempt" in msg || "locked" in msg || "too many" in msg ->
            "Too many attempts — reload the page on the TV"
        "expir" in msg ->
            "Code expired — reload the page on the TV"
        "not running" in msg ->
            "Browser host stopped"
        raw.isNullOrBlank() -> "Couldn't approve"
        else -> raw
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BrowserReceiverFrame(onDismiss: () -> Unit, embedded: Boolean, content: @Composable () -> Unit) {
    if (embedded) content()
    else {
        val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(onDismissRequest = onDismiss, sheetState = state) { content() }
    }
}
