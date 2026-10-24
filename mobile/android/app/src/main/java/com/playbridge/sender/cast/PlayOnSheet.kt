package com.playbridge.sender.cast

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Cast
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Laptop
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SettingsRemote
import androidx.compose.material.icons.filled.Smartphone
import androidx.compose.material.icons.filled.Speaker
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SheetValue
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.playbridge.sender.cast.browser.BrowserReceiverRepository
import com.playbridge.sender.cast.browser.BrowserReceiverSheet
import com.playbridge.sender.cast.browser.BrowserHostStopAction
import com.playbridge.sender.connection.ConnectionMerge
import com.playbridge.sender.connection.ConnectionViewModel
import com.playbridge.sender.connection.WebSocketClient
import com.playbridge.sender.diagnostics.CastAttempt
import com.playbridge.sender.diagnostics.CastAttemptDiagnostics
import com.playbridge.sender.diagnostics.shareCastAttempt
import com.playbridge.sender.model.CastProtocol
import com.playbridge.sender.model.TvDevice
import com.playbridge.sender.ui.LocalNetworkBanners
import kotlinx.coroutines.launch
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject

internal val LocalPlayOnRemoteVisible = staticCompositionLocalOf { false }

internal val LocalPlayOnRemote = staticCompositionLocalOf<(() -> Unit)?> { null }

/** Avoid stacking a second sheet when pairing starts from an already open picker. */
internal object PlayOnSheetPresence {
    var count by mutableIntStateOf(0)
}

internal enum class PlayOnPage { Devices, Pair, Add, Manual, Actions, Configure, Browser, Roku, Dlna, FullRemote }

internal fun WebSocketClient.ConnectionState.needsPairingPage(): Boolean =
    this is WebSocketClient.ConnectionState.WaitingForCodeInput ||
        this is WebSocketClient.ConnectionState.VerifyingCode ||
        this is WebSocketClient.ConnectionState.WaitingForApproval ||
        this is WebSocketClient.ConnectionState.PairingDenied

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlayOnSheet(
    onDismiss: () -> Unit,
    showThisDevice: Boolean = true,
    onPickedThisDevice: (() -> Unit)? = null,
    onPickedDevice: ((TvDevice) -> Unit)? = null,
    playBridgeOnly: Boolean = false,
    picking: Boolean = true,
    startPairing: Boolean = false,
) {
    val viewModel: ConnectionViewModel = koinViewModel()
    val history by viewModel.deviceHistory.collectAsState(initial = emptyList())
    val remoteRows by viewModel.remoteTVDirectory.rows.collectAsState()
    val discovered by viewModel.discoveredDevices.collectAsState()
    val pings by viewModel.savedDevicesOnlineStatus.collectAsState()
    val connection by viewModel.connectionState.collectAsState()
    val tv by viewModel.tvDevice.collectAsState(initial = null)
    val selectedRemote by viewModel.selectedRemoteDevice.collectAsState()
    val external by viewModel.activeExternalDevice.collectAsState()
    val route by viewModel.route.collectAsState()
    val network by viewModel.networkStatus.collectAsState()
    val scanning by viewModel.isScanning.collectAsState()
    val castState by viewModel.castSessionState.collectAsState()
    val title by viewModel.externalMediaTitle.collectAsState()
    val streamRoute by viewModel.lastEffectiveStreamRoute.collectAsState()
    val browser: BrowserReceiverRepository = koinInject()
    val host by browser.state.collectAsState()
    val diagnostics: CastAttemptDiagnostics = koinInject()
    val attempts by diagnostics.attempts.collectAsState()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val openRemote = LocalPlayOnRemote.current
    var page by remember { mutableStateOf(if (startPairing) PlayOnPage.Pair else PlayOnPage.Devices) }
    var managed by remember { mutableStateOf<PlayOnDevice?>(null) }
    var unavailableExpanded by remember { mutableStateOf(false) }
    var vpnInfo by remember { mutableStateOf(false) }
    var pendingGoogle by remember { mutableStateOf<TvDevice?>(null) }
    var pendingNative by remember { mutableStateOf<TvDevice?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }
    val connected = connection is WebSocketClient.ConnectionState.Connected
    val nativeSelected = route is CastSessionManager.Route.NativeTv
    val nativeRow = remoteRows.firstOrNull { row ->
        (selectedRemote ?: tv)?.let { ConnectionMerge.isSameDevice(row.device, it) } == true
    }
    val groups = groupPlayOnDevices(history, discovered, remoteRows, pings,
        if (host.running) host.ready.map { it.toTvDevice(browser.lanHostIp(), host.port) } else emptyList(), playBridgeOnly)
    val nowDevice = (external ?: nativeRow?.device ?: tv).takeIf {
        external != null || (nativeSelected && tv != null) || connected || nativeRow?.status == "TV remote only"
    }
    // The Now card already shows the current device, so the lists don't repeat it.
    fun notNow(row: PlayOnDevice) = nowDevice == null || !ConnectionMerge.isSameDevice(row.device, nowDevice)
    val saved = groups.saved.filter(::notNow)
    val unavailable = groups.unavailable.filter(::notNow)
    val recentFailure = attempts.firstOrNull {
        it.outcome == CastAttempt.AttemptOutcome.FAILED &&
            it.id == viewModel.castSessionManager.currentExternalDiagnosticAttemptId &&
            System.currentTimeMillis() - it.startedAtMs < 5 * 60 * 1000L
    }
    DisposableEffect(viewModel) {
        PlayOnSheetPresence.count++
        viewModel.retainDiscovery()
        viewModel.pingSavedDevices()
        onDispose { viewModel.releaseDiscovery(); PlayOnSheetPresence.count-- }
    }
    LaunchedEffect(Unit) { viewModel.castNotices.collect { notice = it } }
    fun finish(device: TvDevice) {
        if (picking) { onPickedDevice?.invoke(device); onDismiss() }
        else page = PlayOnPage.Devices
    }
    fun cancelPairing() {
        if (page == PlayOnPage.Pair && (connection.needsPairingPage() || pendingNative != null)) {
            pendingNative = null
            viewModel.cancelConnectingToThisDevice()
        }
    }
    fun dismiss() {
        cancelPairing()
        val pending = pendingGoogle
        if (pending != null && external?.let { ConnectionMerge.isSameDevice(pending, it) } == true &&
            !googleCastPickerConnectionComplete(castState.phase)) viewModel.disconnectExternalTarget()
        pendingGoogle = null
        onDismiss()
    }
    fun back() {
        cancelPairing()
        page = when (page) {
            PlayOnPage.Manual, PlayOnPage.Browser, PlayOnPage.Roku, PlayOnPage.Dlna -> PlayOnPage.Add
            PlayOnPage.FullRemote -> PlayOnPage.Actions
            else -> PlayOnPage.Devices
        }
    }
    fun pick(device: TvDevice) {
        val target = ConnectionMerge.withDiscoveredEndpoint(device, discovered)
        notice = null
        pendingGoogle = null
        pendingNative = null
        when (target.resolvedProtocol) {
            CastProtocol.GOOGLE_CAST -> { pendingGoogle = target; viewModel.selectGoogleCastTarget(target) }
            CastProtocol.PLAYBRIDGE -> {
                if (connected && tv?.let { ConnectionMerge.isSameDevice(it, target) } == true) {
                    viewModel.selectNativeRoute(); finish(target)
                } else {
                    pendingNative = target
                    viewModel.connect(target)
                    page = if (target.token.isEmpty()) PlayOnPage.Pair else PlayOnPage.Devices
                }
            }
            CastProtocol.DLNA -> if (viewModel.selectDlnaTarget(target)) finish(target)
            CastProtocol.ROKU -> { viewModel.selectRokuTarget(target); finish(target) }
            CastProtocol.WEB_BROWSER -> { viewModel.selectBrowserTarget(target); finish(target) }
        }
    }
    LaunchedEffect(connection) {
        if (connection.needsPairingPage()) page = PlayOnPage.Pair
        if (connection is WebSocketClient.ConnectionState.Connected) {
            val pending = pendingNative
            pendingNative = null
            if (pending != null || page == PlayOnPage.Pair) {
                page = PlayOnPage.Devices
                (tv?.takeIf { pending == null || ConnectionMerge.isSameDevice(it, pending) } ?: pending)?.let(::finish)
            }
        }
    }
    LaunchedEffect(pendingGoogle, external, castState.phase) {
        val pending = pendingGoogle ?: return@LaunchedEffect
        if (external?.let { ConnectionMerge.isSameDevice(it, pending) } == true &&
            googleCastPickerConnectionComplete(castState.phase)) { pendingGoogle = null; finish(pending) }
    }
    val devicesScroll = rememberScrollState()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false, confirmValueChange = { next ->
        if (next == SheetValue.Hidden && page != PlayOnPage.Devices) {
            back()
            false
        } else true
    })
    LaunchedEffect(page) { if (page != PlayOnPage.Devices) sheetState.expand() }
    ModalBottomSheet(onDismissRequest = { if (page != PlayOnPage.Devices) back() else dismiss() }, sheetState = sheetState) {
        BackHandler(enabled = page != PlayOnPage.Devices) { back() }
        Column(Modifier.fillMaxWidth().fillMaxHeight(0.94f).imePadding()) {
            Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                if (page != PlayOnPage.Devices) IconButton(onClick = ::back) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                Column(Modifier.weight(1f).padding(8.dp)) {
                    Text(when (page) {
                        PlayOnPage.Devices -> "Play on"
                        PlayOnPage.Pair -> "Pair ${(pendingNative ?: tv)?.playOnName ?: "TV"}"
                        PlayOnPage.Add -> "Add a device"
                        PlayOnPage.Manual -> "PlayBridge TV by IP address"
                        PlayOnPage.Actions -> managed?.device?.playOnName ?: "Device"
                        PlayOnPage.Configure -> "Configure TV"
                        PlayOnPage.Browser -> "Browser on a TV"
                        PlayOnPage.Roku -> "Roku by IP address"
                        PlayOnPage.Dlna -> "DLNA device by URL"
                        PlayOnPage.FullRemote -> "Full TV remote"
                    }, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold,
                        maxLines = 2, overflow = TextOverflow.Ellipsis)
                    if (page == PlayOnPage.Devices) Row(verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        if (network.onLocalNetwork) Icon(Icons.Default.Wifi, null, Modifier.size(14.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(if (network.onLocalNetwork) "Wi-Fi" else "Mobile data",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (network.onLocalNetwork && network.vpnActive) Text("VPN",
                            style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = VpnAmber,
                            modifier = Modifier.clip(RoundedCornerShape(50)).background(VpnAmber.copy(alpha = 0.16f))
                                .clickable(onClickLabel = "About VPN and casting") { vpnInfo = !vpnInfo }
                                .padding(horizontal = 8.dp, vertical = 2.dp))
                    }
                }
                if (page == PlayOnPage.Browser) BrowserHostStopAction(host.running, host.busy) {
                    scope.launch {
                        if (host.ready.isNotEmpty()) viewModel.disconnectExternalTarget()
                        browser.stopHost()
                    }
                } else FilledTonalIconButton(onClick = ::dismiss, modifier = Modifier.size(36.dp)) {
                    Icon(Icons.Default.Close, "Close Play on", Modifier.size(18.dp))
                }
            }
            AnimatedContent(targetState = page, label = "Play on page", transitionSpec = {
                val direction = if (targetState.ordinal > initialState.ordinal) 1 else -1
                slideInHorizontally { it * direction } togetherWith slideOutHorizontally { -it * direction }
            }) { currentPage ->
                // Page 1 keeps its scroll position while a sub-page is open.
                val pageScroll = if (currentPage == PlayOnPage.Devices) devicesScroll else rememberScrollState()
                Column(Modifier.fillMaxWidth().verticalScroll(pageScroll).padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    when (currentPage) {
                        PlayOnPage.Devices -> {
                            // VPN: the full tip only when it is likely the cause (nothing reachable after a search) or asked for.
                            val nothingFound = !scanning && groups.nearby.isEmpty() && groups.saved.isEmpty()
                            LocalNetworkBanners(network, vpnBanner = vpnInfo || nothingFound, vpnDismissible = false)
                            if (nowDevice != null) {
                                PlayOnNowCard(
                                    device = nowDevice,
                                    connection = connection,
                                    external = external != null,
                                    castState = castState,
                                    mediaTitle = title,
                                    nativeStatus = nativeRow?.status,
                                    nativeSelected = nativeSelected,
                                    streamRoute = streamRoute?.label,
                                    onRemote = openRemote?.let { { onDismiss(); it() } },
                                    onConfigure = if (nativeSelected && connected && external == null &&
                                        TvCapabilityOptions.playerOptions(tv).size > 2) {
                                        { page = PlayOnPage.Configure }
                                    } else null,
                                    onCastHere = viewModel::selectNativeRoute,
                                    onOpenPlayBridge = { viewModel.openPlayBridge() },
                                    onDisconnect = {
                                        pendingNative = null
                                        pendingGoogle = null
                                        if (external != null) viewModel.disconnectExternalTarget()
                                        else { viewModel.selectThisDevice(); viewModel.disconnect() }
                                    },
                                    onShareDiagnostics = recentFailure?.let { attempt ->
                                        { shareCastAttempt(context, diagnostics.report(attempt)) }
                                    },
                                )
                            }
                            PlayOnSectionLabel("Devices")
                            if (showThisDevice) PlayOnRow("This phone", "Play here", phone = true,
                                selected = route is CastSessionManager.Route.ThisDevice && external == null, onClick = {
                                    pendingNative = null; pendingGoogle = null
                                    viewModel.selectThisDevice(); onPickedThisDevice?.invoke(); if (picking) onDismiss()
                                })
                            saved.forEach { row -> PlayOnDeviceRow(row, onClick = { pick(row.device) }, onMore = { managed = row; page = PlayOnPage.Actions }) }
                            // Nearby always shows so a search can be retried once the scan window ends.
                            Row(Modifier.fillMaxWidth().padding(top = 8.dp).heightIn(min = 48.dp),
                                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                                PlayOnSectionLabel("Nearby")
                                if (scanning) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                                    Text("Searching", style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                                } else TextButton(onClick = { viewModel.rescan(); viewModel.pingSavedDevices() }) {
                                    Icon(Icons.Default.Refresh, null, Modifier.size(18.dp))
                                    Text("Search again", Modifier.padding(start = 6.dp))
                                }
                            }
                            groups.nearby.forEach { row -> PlayOnDeviceRow(row, nearby = true, onClick = { pick(row.device) },
                                onLongClick = { managed = row; page = PlayOnPage.Actions }) }
                            if (groups.nearby.isEmpty()) Text(if (scanning) "Looking for TVs on this Wi-Fi…" else "No new devices found.",
                                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            if (unavailable.isNotEmpty()) {
                                HorizontalDivider(Modifier.padding(top = 8.dp))
                                Row(Modifier.fillMaxWidth().heightIn(min = 48.dp)
                                    .clickable(onClickLabel = if (unavailableExpanded) "Collapse" else "Expand") {
                                        unavailableExpanded = !unavailableExpanded
                                    },
                                    verticalAlignment = Alignment.CenterVertically) {
                                    PlayOnSectionLabel("Not available now · ${unavailable.size}", Modifier.weight(1f))
                                    Icon(if (unavailableExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore, null,
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                if (unavailableExpanded) unavailable.forEach { row -> PlayOnDeviceRow(row, onClick = { pick(row.device) }, onMore = { managed = row; page = PlayOnPage.Actions }) }
                            }
                            if (groups.saved.isEmpty() && groups.nearby.isEmpty()) PlayOnTip("Don’t see your TV?", "Open the PlayBridge app on your TV and make sure it’s on the same Wi-Fi as this phone.")
                            OutlinedButton(onClick = { page = PlayOnPage.Add }, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
                                border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.6f)),
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.primary)) {
                                Icon(Icons.Default.Add, null); Text("Add a device", Modifier.padding(start = 8.dp), fontWeight = FontWeight.SemiBold)
                            }
                        }
                        PlayOnPage.Pair -> PlayOnPairing(connection, (pendingNative ?: tv)?.playOnName ?: "TV", viewModel::submitPairingCode) { (pendingNative ?: tv)?.let(::pick) }
                        PlayOnPage.Add -> {
                            Text("TVs on your Wi-Fi show up automatically under Nearby. Use these when one doesn’t.")
                            if (!playBridgeOnly) PlayOnRow("Browser on a TV", "Any smart TV with a web browser — no app needed",
                                icon = Icons.Default.Language, onClick = { page = PlayOnPage.Browser })
                            PlayOnRow("PlayBridge TV by IP address", "When discovery is blocked", onClick = { page = PlayOnPage.Manual })
                            if (!playBridgeOnly) {
                                PlayOnRow("Roku by IP address", "Find it under Settings › Network on the Roku",
                                    icon = Icons.Default.Cast, onClick = { page = PlayOnPage.Roku })
                                PlayOnRow("DLNA device by URL", "Paste its description URL",
                                    icon = Icons.Default.Speaker, onClick = { page = PlayOnPage.Dlna })
                            }
                            PlayOnTip("If a TV won’t show up", "1. Open PlayBridge on the TV and leave it on the home screen.\n2. Put the phone and TV on the same Wi-Fi (guest networks often block devices).\n3. Turn off VPN on this phone.")
                            TextButton(onClick = {
                                val attempt = diagnostics.latestFailed() ?: attempts.firstOrNull()
                                if (attempt != null) shareCastAttempt(context, diagnostics.report(attempt)) else notice = "No connection diagnostics yet. Try connecting to a TV first."
                            }) { Text("Share connection diagnostics") }
                        }
                        PlayOnPage.Manual, PlayOnPage.Roku, PlayOnPage.Dlna -> PlayOnManual(
                            protocol = when (currentPage) {
                                PlayOnPage.Roku -> CastProtocol.ROKU
                                PlayOnPage.Dlna -> CastProtocol.DLNA
                                else -> CastProtocol.PLAYBRIDGE
                            },
                            connect = { device ->
                                val known = history.firstOrNull { ConnectionMerge.isSameDevice(it, device) }
                                pick(known ?: device)
                            },
                        )
                        PlayOnPage.Actions -> managed?.let { row ->
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                Box(Modifier.size(44.dp).background(MaterialTheme.colorScheme.primary.copy(alpha = 0.14f), RoundedCornerShape(12.dp)),
                                    contentAlignment = Alignment.Center) {
                                    Icon(row.device.playOnIcon, null, tint = MaterialTheme.colorScheme.primary)
                                }
                                Column {
                                    Text(playOnDeviceSummary(row), style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    playOnShortId(row.device.uuid)?.let { id ->
                                        Text("ID $id", style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                }
                            }
                            HorizontalDivider()
                            val isSaved = row.saved != null || row.remotePaired
                            val noun = if (row.device.resolvedProtocol == CastProtocol.PLAYBRIDGE) "TV" else "device"
                            PlayOnRow(if (!isSaved && row.device.resolvedProtocol == CastProtocol.PLAYBRIDGE && row.device.token.isEmpty())
                                "Pair this TV" else "Play on this $noun", null,
                                icon = Icons.AutoMirrored.Filled.ArrowForward, onClick = { pick(row.device) })
                            val supportsFullRemote = row.device.resolvedProtocol == CastProtocol.PLAYBRIDGE &&
                                (row.remotePaired || "exo" in row.device.players || remoteRows.any {
                                    ConnectionMerge.isSameDevice(it.device, row.device) && it.tvReachable
                                })
                            if (supportsFullRemote) PlayOnRow("Full TV remote", "Power, volume and apps — pair once with a code",
                                icon = Icons.Default.SettingsRemote, badge = if (row.remotePaired) "Paired" else "Set up", onClick = {
                                    viewModel.selectNativeRemote(row.device)
                                    if (row.remotePaired && openRemote != null) { onDismiss(); openRemote() }
                                    else page = PlayOnPage.FullRemote
                                })
                            if (isSaved) PlayOnRow("Forget this $noun", null,
                                icon = Icons.Default.DeleteOutline, danger = true, onClick = {
                                    scope.launch {
                                        val removed = viewModel.forgetPlayOnDevice(row.device)
                                        if (removed) page = PlayOnPage.Devices
                                        else notice = "Couldn’t forget the TV. Try again."
                                    }
                                })
                        }
                        PlayOnPage.FullRemote -> managed?.let { row ->
                            PlayOnFullRemote(row.device, onCancel = { page = PlayOnPage.Actions },
                                onPaired = { page = PlayOnPage.Devices })
                        }
                        PlayOnPage.Configure -> PlayOnConfigure(tv, enabled = connected && nativeSelected && external == null)
                        PlayOnPage.Browser -> BrowserReceiverSheet(viewModel, embedded = true,
                            onDismiss = { page = PlayOnPage.Add },
                            onCastHere = { viewModel.activeExternalDevice.value?.let(::finish) })
                    }
                    notice?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                }
            }
        }
    }
}

@Composable
private fun PlayOnDeviceRow(row: PlayOnDevice, nearby: Boolean = false, onClick: () -> Unit, onMore: (() -> Unit)? = null,
    onLongClick: (() -> Unit)? = onMore) {
    val protocol = row.device.resolvedProtocol
    val ready = !nearby && row.reachable && row.remoteStatus != "TV remote only"
    PlayOnRow(row.device.playOnName, when {
        row.remoteStatus == "TV remote only" -> "PlayBridge · TV remote only"
        nearby && protocol == CastProtocol.PLAYBRIDGE -> "PlayBridge · Tap to pair"
        nearby -> protocol.displayName
        row.reachable -> "${protocol.displayName} · Ready"
        else -> "${protocol.displayName} · ${usedRelative(row.device.lastConnected)}"
    }, onClick = onClick, icon = row.device.playOnIcon, ready = ready, dimmed = !nearby && !row.reachable,
        onMore = onMore, onLongClick = onLongClick, badge = if (nearby && protocol == CastProtocol.PLAYBRIDGE) "New" else null,
        highlight = nearby && protocol == CastProtocol.PLAYBRIDGE)
}

private val VpnAmber = Color(0xFFFFB74D)

private val TvDevice.playOnIcon: ImageVector get() = when {
    isDesktopReceiver -> Icons.Default.Laptop
    resolvedProtocol == CastProtocol.GOOGLE_CAST -> Icons.Default.Cast
    resolvedProtocol == CastProtocol.DLNA -> Icons.Default.Speaker
    resolvedProtocol == CastProtocol.WEB_BROWSER -> Icons.Default.Language
    else -> Icons.Default.Tv
}

@Composable
internal fun PlayOnSectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(text.uppercase(), modifier, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun PlayOnRow(name: String, subtitle: String?, onClick: () -> Unit, phone: Boolean = false,
    icon: ImageVector = Icons.Default.Tv, ready: Boolean = false, dimmed: Boolean = false,
    selected: Boolean = false, badge: String? = null, highlight: Boolean = false, danger: Boolean = false,
    onMore: (() -> Unit)? = null, onLongClick: (() -> Unit)? = null) {
    val accent = if (danger) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
    ListItem(headlineContent = { Text(name, fontWeight = FontWeight.SemiBold, color = if (danger) accent else Color.Unspecified) },
        supportingContent = subtitle?.let { {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (ready) Spacer(Modifier.padding(end = 6.dp).size(7.dp).background(PlayOnGreen, CircleShape))
                Text(subtitle)
            }
        } },
        leadingContent = { Icon(if (phone) Icons.Default.Smartphone else icon, null, tint = accent) },
        trailingContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (selected) Icon(Icons.Default.Check, "Selected", tint = MaterialTheme.colorScheme.primary)
                if (badge != null) Text(badge, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold, modifier = Modifier
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.16f), RoundedCornerShape(50))
                        .padding(horizontal = 12.dp, vertical = 5.dp))
                if (onMore != null) IconButton(onClick = onMore) { Icon(Icons.Default.MoreVert, "More options for $name") }
            }
        },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).clip(RoundedCornerShape(14.dp))
            .then(if (highlight) Modifier.background(MaterialTheme.colorScheme.primary.copy(alpha = 0.10f)) else Modifier)
            // Long-press opens the device's details (address, ID) — handy when two devices share a name.
            .combinedClickable(onClick = onClick, onLongClick = onLongClick, onLongClickLabel = onLongClick?.let { "Device details" })
            .then(if (dimmed) Modifier.alpha(0.55f) else Modifier))
}

@Composable
private fun PlayOnTip(title: String, body: String) {
    Card(shape = RoundedCornerShape(16.dp)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, fontWeight = FontWeight.SemiBold); Text(body, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun PlayOnPairing(state: WebSocketClient.ConnectionState, name: String, submit: (String) -> Unit, retry: () -> Unit) {
    var code by remember { mutableStateOf("") }
    LaunchedEffect(state) { if (state is WebSocketClient.ConnectionState.WaitingForCodeInput && state.lastCodeWrong) code = "" }
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
        // What the TV shows; dots rather than digits so nobody types the example.
        Column(Modifier.padding(top = 4.dp).widthIn(min = 200.dp)
            .border(1.5.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerLowest, RoundedCornerShape(16.dp))
            .padding(horizontal = 28.dp, vertical = 22.dp)
            .semantics(mergeDescendants = true) { contentDescription = "Your TV shows a 6-digit code" },
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("PlayBridge on your TV", style = MaterialTheme.typography.labelMedium, color = muted)
            Text("● ● ●   ● ● ●", style = MaterialTheme.typography.headlineSmall, fontFamily = FontFamily.Monospace)
        }
        when (state) {
            is WebSocketClient.ConnectionState.WaitingForCodeInput -> {
                Text(buildAnnotatedString {
                    append("Enter the 6-digit code shown on ")
                    withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { append(name) }
                    append(".")
                }, textAlign = TextAlign.Center)
                PairingCodeInput(code, {
                    code = it
                    if (code.length == 6) submit(code)
                }, isError = state.lastCodeWrong)
                if (state.lastCodeWrong) Text("Incorrect code — ${state.attemptsLeft} ${if (state.attemptsLeft == 1) "try" else "tries"} left",
                    color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center)
                Text("Pairs automatically when all six digits are in", style = MaterialTheme.typography.bodyMedium,
                    color = muted, textAlign = TextAlign.Center)
                Text("No code on the TV? Make sure the PlayBridge app is open on it, then tap the TV again.",
                    style = MaterialTheme.typography.bodyMedium, color = muted, textAlign = TextAlign.Center)
            }
            is WebSocketClient.ConnectionState.WaitingForApproval -> Text("Approve on the TV", style = MaterialTheme.typography.titleMedium)
            is WebSocketClient.ConnectionState.PairingDenied -> {
                Text("${state.serverName} denied the connection", color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center)
                Button(onClick = retry) { Text("Try again") }
            }
            is WebSocketClient.ConnectionState.Error -> Text(state.message, color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center)
            is WebSocketClient.ConnectionState.PinMismatch -> Text("The TV’s certificate changed. Forget the device and pair again.",
                color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center)
            WebSocketClient.ConnectionState.AuthFailed -> Text("Connection lost — tap the TV to reconnect",
                color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center)
            else -> {
                CircularProgressIndicator(Modifier.size(24.dp))
                Text(if (state is WebSocketClient.ConnectionState.VerifyingCode) "Verifying code…" else "Connecting to $name…")
            }
        }
    }
}

@Composable
private fun PlayOnManual(protocol: CastProtocol, connect: (TvDevice) -> Unit) {
    var address by remember(protocol) { mutableStateOf("") }
    var port by remember(protocol) { mutableStateOf(com.playbridge.shared.protocol.Config.DEFAULT_PORT.toString()) }
    val device = manualPlayOnDevice(protocol, address, port.toIntOrNull())
    OutlinedTextField(address, { address = it.trim() },
        label = { Text(if (protocol == CastProtocol.DLNA) "Description URL" else "IP address") },
        singleLine = true, modifier = Modifier.fillMaxWidth(),
        keyboardOptions = KeyboardOptions(keyboardType = if (protocol == CastProtocol.DLNA) KeyboardType.Uri else KeyboardType.Ascii))
    if (protocol == CastProtocol.PLAYBRIDGE) OutlinedTextField(port, { port = it.filter(Char::isDigit).take(5) },
        label = { Text("Port") }, singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth())
    Button(onClick = { device?.let(connect) }, enabled = device != null, modifier = Modifier.fillMaxWidth()) { Text("Connect") }
}

@Composable
private fun PlayOnConfigure(device: TvDevice?, enabled: Boolean) {
    val settings: com.playbridge.sender.data.settings.SettingsRepository = koinInject()
    val controllers: com.playbridge.sender.cast.remote.RemoteControllerRegistry = koinInject()
    val mode by settings.tvPlayerMode.collectAsState(initial = "tv")
    val scope = rememberCoroutineScope()
    Text("${device?.playOnName ?: "TV"} · ${if (enabled) "Connected" else "Disconnected"}")
    PlayOnSectionLabel("Playback")
    Text("Video player", style = MaterialTheme.typography.titleMedium)
    val options = TvCapabilityOptions.playerOptions(device)
    val selected = TvCapabilityOptions.coerceSelection(mode, options)
    options.forEach { (id, label) ->
        ListItem(headlineContent = { Text(label) }, supportingContent = { Text(when (id) {
            "exo" -> "Default player, smooth on most streams"
            "mpv" -> "Plays more formats and subtitle styles"
            else -> "Use the TV’s ${if (id == "tv") "default player" else label}"
        }) }, leadingContent = { RadioButton(selected == id, onClick = null, enabled = enabled) },
            modifier = Modifier.clickable(enabled = enabled) {
                scope.launch {
                    settings.setTvPlayerMode(id)
                    val controller = controllers.controller.value
                    if (id != TvCapabilityOptions.TV_DEFAULT && controller?.sessionId?.endpoint == device?.endpointKey) {
                        controller?.execute(com.playbridge.sender.cast.remote.RemoteAction.SwitchEngine(id))
                    }
                }
            })
    }
    Text("Changes apply on the TV right away.", style = MaterialTheme.typography.bodySmall)
}

/** "<Protocol> · <ip>[:port] · Used …" for saved devices, "· Found nearby" for live ones. */
private fun playOnDeviceSummary(row: PlayOnDevice): String {
    val device = row.device
    val address = device.ip + (device.port.takeIf { it > 0 }?.let { ":$it" }
        ?: device.descriptionUrl?.let { runCatching { java.net.URI(it).port }.getOrNull()?.takeIf { p -> p > 0 }?.let { p -> ":$p" } }
        ?: "")
    val when_ = if (row.saved != null || row.remotePaired) usedRelative(device.lastConnected) else "Found nearby"
    return "${device.resolvedProtocol.displayName} · $address · $when_"
}

/** A short, stable ID to tell same-named devices apart ("1e376351…"). */
internal fun playOnShortId(uuid: String): String? =
    uuid.substringBefore("::").removePrefix("uuid:").take(8).takeIf { it.length >= 4 }?.let { "$it…" }

private fun usedRelative(time: Long): String {
    val days = ((System.currentTimeMillis() - time) / 86_400_000L).coerceAtLeast(0)
    return when (days) { 0L -> "Used today"; 1L -> "Used yesterday"; else -> "Used $days days ago" }
}
