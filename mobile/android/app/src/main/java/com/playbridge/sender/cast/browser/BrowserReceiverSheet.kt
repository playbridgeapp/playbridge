package com.playbridge.sender.cast.browser

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Language
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.playbridge.sender.connection.ConnectionViewModel
import com.playbridge.sender.connection.NetworkStatusRepository
import com.playbridge.sender.ui.LocalNetworkBanners
import com.playbridge.sender.ui.SectionLabel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.koin.compose.koinInject
import java.util.Locale
import java.util.concurrent.TimeUnit

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BrowserReceiverSheet(
    viewModel: ConnectionViewModel,
    onDismiss: () -> Unit,
    onCastHere: (() -> Unit)? = null,
) {
    val browserRepo: BrowserReceiverRepository = koinInject()
    val hostState by browserRepo.state.collectAsState()
    val networkStatus by viewModel.networkStatus.collectAsState()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var stopConfirm by remember { mutableStateOf(false) }
    var pairingRequest by remember { mutableStateOf<BrowserPairingRequest?>(null) }
    var snackMessage by remember { mutableStateOf<String?>(null) }
    var otherExpanded by remember { mutableStateOf(false) }

    // Auto-open code sheet when exactly one pending request appears.
    LaunchedEffect(hostState.pending) {
        if (pairingRequest == null && hostState.pending.size == 1) {
            pairingRequest = hostState.pending.first()
        }
    }

    LaunchedEffect(snackMessage) {
        if (snackMessage != null) {
            delay(2_500)
            snackMessage = null
        }
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = "Cast to browser",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                )
                if (hostState.running) {
                    OutlinedButton(
                        onClick = {
                            if (hostState.ready.isNotEmpty()) stopConfirm = true
                            else scope.launch {
                                browserRepo.stopHost()
                            }
                        },
                        enabled = !hostState.busy,
                    ) {
                        Text("Stop host")
                    }
                }
            }

            LocalNetworkBanners(status = networkStatus)

            if (!hostState.running) {
                Text(
                    text = "Use a browser on a TV, console, or computer as a temporary receiver. This phone will host a page on your Wi‑Fi.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Button(
                    onClick = {
                        scope.launch {
                            val result = browserRepo.startHost()
                            if (result.isFailure) {
                                snackMessage = browserRepo.state.value.lastError
                                    ?: result.exceptionOrNull()?.message
                                    ?: "Couldn't start browser host"
                            }
                        }
                    },
                    // Allow start off Wi‑Fi only with the banner above as warning; bind may still fail.
                    enabled = !hostState.busy,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    if (hostState.busy) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                        )
                    } else {
                        Text("Start browser host")
                    }
                }
                if (!networkStatus.onLocalNetwork) {
                    Text(
                        text = "Requires same Wi‑Fi as the TV.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            } else {
                BrowserHostUrlCard(
                    primaryUrl = hostState.primaryUrl,
                    port = hostState.port,
                    otherUrls = hostState.otherUrls,
                    otherExpanded = otherExpanded,
                    onToggleOther = { otherExpanded = !otherExpanded },
                    onCopy = { url ->
                        copyToClipboard(context, url)
                        snackMessage = "Link copied"
                    },
                )

                Text(
                    text = "Steps",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = "1. Connect the TV to the same Wi‑Fi\n2. Open the address above in the TV’s browser\n3. Enter the 6-digit code shown on the TV",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                SectionLabel("Waiting to pair")
                if (hostState.pending.isEmpty()) {
                    Text(
                        text = "Waiting for a browser to open the link…",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    hostState.pending.forEach { request ->
                        PendingPairRow(
                            request = request,
                            onEnterCode = { pairingRequest = request },
                        )
                    }
                }

                if (hostState.ready.isNotEmpty()) {
                    SectionLabel("Ready")
                    hostState.ready.forEach { session ->
                        ReadySessionRow(
                            session = session,
                            onCastHere = {
                                viewModel.selectBrowserTarget(
                                    session.toTvDevice(browserRepo.lanHostIp(), hostState.port),
                                )
                                onCastHere?.invoke()
                                onDismiss()
                            },
                            onForget = {
                                scope.launch { browserRepo.forget(session.receiverId) }
                            },
                        )
                    }
                }
            }

            hostState.lastError?.let { err ->
                Text(
                    text = err,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            snackMessage?.let { msg ->
                Text(
                    text = msg,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }

    if (stopConfirm) {
        AlertDialog(
            onDismissRequest = { stopConfirm = false },
            title = { Text("Stop hosting?") },
            text = { Text("The TV browser will disconnect.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        stopConfirm = false
                        scope.launch {
                            viewModel.disconnectExternalTarget()
                            browserRepo.stopHost()
                        }
                    },
                ) { Text("Stop host") }
            },
            dismissButton = {
                TextButton(onClick = { stopConfirm = false }) { Text("Cancel") }
            },
        )
    }

    pairingRequest?.let { request ->
        BrowserPairingCodeSheet(
            request = request,
            onDismiss = { pairingRequest = null },
            onApprove = { code ->
                val result = browserRepo.approve(request.sessionId, code)
                if (result.isFailure) {
                    throw result.exceptionOrNull()
                        ?: IllegalStateException("Incorrect code")
                }
                pairingRequest = null
                snackMessage = "Ready to cast"
            },
        )
    }

    // After approve, activate the browser as NOW when the host confirms connected.
    val activate by browserRepo.activateSession.collectAsState()
    LaunchedEffect(activate) {
        val session = activate ?: return@LaunchedEffect
        viewModel.selectBrowserTarget(
            session.toTvDevice(browserRepo.lanHostIp(), hostState.port),
        )
        browserRepo.clearActivateSession()
    }
}

@Composable
private fun BrowserHostUrlCard(
    primaryUrl: String?,
    port: Int,
    otherUrls: List<String>,
    otherExpanded: Boolean,
    onToggleOther: () -> Unit,
    onCopy: (String) -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f),
        ),
        shape = RoundedCornerShape(12.dp),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = "Open on the TV browser",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.75f),
            )
            Text(
                text = primaryUrl ?: "Starting…",
                style = MaterialTheme.typography.headlineSmall.copy(
                    fontFamily = FontFamily.Monospace,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                ),
                color = MaterialTheme.colorScheme.primary,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
            val host = primaryUrl?.let { runCatching { java.net.URI(it).host }.getOrNull() }
            if (host != null) {
                Text(
                    text = "IP $host  ·  port ${if (port > 0) port else runCatching { java.net.URI(primaryUrl).port }.getOrDefault(8770)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f),
                )
            }
            if (primaryUrl != null) {
                FilledTonalButton(onClick = { onCopy(primaryUrl) }) {
                    Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.size(8.dp))
                    Text("Copy link")
                }
            }
            if (otherUrls.isNotEmpty()) {
                Text(
                    text = if (otherExpanded) "Other addresses ▾" else "Other addresses ▸",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.clickable(onClick = onToggleOther),
                )
                if (otherExpanded) {
                    otherUrls.forEach { url ->
                        Text(
                            text = url,
                            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.clickable { onCopy(url) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PendingPairRow(
    request: BrowserPairingRequest,
    onEnterCode: () -> Unit,
) {
    var remaining by remember(request.sessionId, request.expiresAtMs) {
        mutableStateOf(request.remainingMs)
    }
    LaunchedEffect(request.sessionId, request.expiresAtMs) {
        while (remaining > 0) {
            delay(1_000)
            remaining = request.remainingMs
        }
    }
    Card(modifier = Modifier.fillMaxWidth()) {
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
                    text = if (remaining > 0) {
                        "${formatCountdown(remaining)} left"
                    } else {
                        "Code expired — reload the page on the TV"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Button(onClick = onEnterCode, enabled = remaining > 0) {
                Text("Enter code")
            }
        }
    }
}

@Composable
private fun ReadySessionRow(
    session: BrowserReadySession,
    onCastHere: () -> Unit,
    onForget: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Default.Language,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(Modifier.size(8.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(session.name, fontWeight = FontWeight.SemiBold)
                    Text(
                        "Ready to cast",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onCastHere) { Text("Cast here") }
                TextButton(onClick = onForget) { Text("Forget") }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BrowserPairingCodeSheet(
    request: BrowserPairingRequest,
    onDismiss: () -> Unit,
    onApprove: suspend (String) -> Unit,
) {
    var code by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var submitting by remember { mutableStateOf(false) }
    var remaining by remember(request.expiresAtMs) { mutableStateOf(request.remainingMs) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()

    LaunchedEffect(request.expiresAtMs) {
        while (remaining > 0) {
            delay(1_000)
            remaining = request.remainingMs
        }
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = "Pair browser",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = request.name,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(
                value = code,
                onValueChange = { input ->
                    code = input.filter { it.isDigit() }.take(6)
                    error = null
                },
                label = { Text("6-digit code") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                modifier = Modifier.fillMaxWidth(),
                isError = error != null,
                supportingText = {
                    when {
                        error != null -> Text(error!!)
                        remaining <= 0L -> Text("Code expired — reload the page on the TV")
                        else -> Text("Expires in ${formatCountdown(remaining)}")
                    }
                },
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = onDismiss, enabled = !submitting) { Text("Cancel") }
                Spacer(Modifier.size(8.dp))
                Button(
                    onClick = {
                        if (code.length != 6) {
                            error = "Enter the 6-digit code"
                            return@Button
                        }
                        scope.launch {
                            submitting = true
                            try {
                                onApprove(code)
                            } catch (e: Exception) {
                                error = humanizePairError(e.message)
                            } finally {
                                submitting = false
                            }
                        }
                    },
                    enabled = !submitting && remaining > 0 && code.length == 6,
                ) {
                    if (submitting) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    } else {
                        Text("Approve")
                    }
                }
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
