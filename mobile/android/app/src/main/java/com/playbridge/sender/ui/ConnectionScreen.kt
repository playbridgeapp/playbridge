package com.playbridge.sender.ui

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Cast
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Gamepad
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Smartphone
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material.icons.filled.WifiOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.playbridge.sender.cast.CastSessionManager
import com.playbridge.sender.cast.browser.BrowserOnTvSection
import com.playbridge.sender.cast.browser.BrowserPairingCodeSheet
import com.playbridge.sender.cast.browser.BrowserPairingRequest
import com.playbridge.sender.cast.browser.BrowserReceiverRepository
import com.playbridge.sender.cast.browser.BrowserReceiverSheet
import com.playbridge.sender.connection.ConnectionMerge
import com.playbridge.sender.connection.ConnectionViewModel
import com.playbridge.sender.connection.NetworkStatusRepository
import com.playbridge.sender.connection.WebSocketClient
import com.playbridge.sender.model.CastProtocol
import com.playbridge.sender.model.TvDevice
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

private val OnlineGreen = Color(0xFF4CAF50)
private val WarningAmber = Color(0xFFFFA000)

/**
 * Full-screen device manager: current destination, paired PlayBridge TVs, and live network
 * discovery (sticky, quiet background rescans while open). No tabs / protocol filter chips.
 *
 * @param initialTab legacy: non-zero opens with the "Other devices" section expanded
 *   (used when the cast sheet routes here via "Find more devices").
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConnectionScreen(
    viewModel: ConnectionViewModel,
    onMenuClick: () -> Unit,
    modifier: Modifier = Modifier,
    onRemoteClick: (() -> Unit)? = null,
    initialTab: Int = 0,
) {
    val discoveredDevices by viewModel.discoveredDevices.collectAsState()
    val history by viewModel.deviceHistory.collectAsState(initial = emptyList())
    val pings by viewModel.savedDevicesOnlineStatus.collectAsState()
    val connectionState by viewModel.connectionState.collectAsState()
    val autoConnectEnabled by viewModel.autoConnectEnabled.collectAsState()
    val tvDevice by viewModel.tvDevice.collectAsState(initial = null)
    val activeExternalDevice by viewModel.activeExternalDevice.collectAsState()
    val route by viewModel.route.collectAsState()
    val isScanning by viewModel.isScanning.collectAsState()
    val networkStatus by viewModel.networkStatus.collectAsState()
    val browserRepo: BrowserReceiverRepository = koinInject()
    val browserHost by browserRepo.state.collectAsState()
    val externalMediaTitle by viewModel.externalMediaTitle.collectAsState()
    val castSessionState by viewModel.castSessionState.collectAsState()
    val lastEffectiveStreamRoute by viewModel.lastEffectiveStreamRoute.collectAsState()

    val isConnected = connectionState is WebSocketClient.ConnectionState.Connected
    val nativeSelected = route is CastSessionManager.Route.NativeTv
    val isConnecting = nativeSelected && (
        connectionState is WebSocketClient.ConnectionState.Connecting ||
            connectionState is WebSocketClient.ConnectionState.WaitingForApproval ||
            connectionState is WebSocketClient.ConnectionState.Retrying
        )

    var showBrowserSheet by remember { mutableStateOf(false) }
    var browserPairRequest by remember { mutableStateOf<BrowserPairingRequest?>(null) }

    DisposableEffect(Unit) {
        viewModel.retainDiscovery()
        onDispose { viewModel.releaseDiscovery() }
    }

    LaunchedEffect(history) {
        viewModel.pingSavedDevices(history)
    }

    val playBridgeSaved = remember(
        history,
        pings,
        discoveredDevices,
        activeExternalDevice,
        isConnected,
        tvDevice,
    ) {
        ConnectionMerge.playBridgeHistory(history).map { saved ->
            // Prefer the live discovered endpoint (DHCP / ports can change) while keeping
            // the history entry for credentials and last-connected metadata.
            val live = ConnectionMerge.withDiscoveredEndpoint(saved, discoveredDevices)
            UnifiedDevice(
                connectDevice = live,
                historyEntry = saved,
                isOnline = pings[saved.endpointKey.toString()] == true ||
                    discoveredDevices.any { ConnectionMerge.isSameDevice(it, saved) },
                lastConnected = saved.lastConnected,
            )
        }.filterNot { u -> isActiveTarget(u.connectDevice, activeExternalDevice, isConnected, tvDevice) }
            .sortedWith(
                compareByDescending<UnifiedDevice> { it.isOnline }
                    .thenByDescending { it.lastConnected ?: 0L },
            )
    }

    val recentExternal = remember(
        history,
        pings,
        discoveredDevices,
        activeExternalDevice,
        isConnected,
        tvDevice,
    ) {
        ConnectionMerge.recentExternalHistory(history).map { saved ->
            val live = ConnectionMerge.withDiscoveredEndpoint(saved, discoveredDevices)
            UnifiedDevice(
                connectDevice = live,
                historyEntry = saved,
                isOnline = pings[saved.endpointKey.toString()] == true ||
                    discoveredDevices.any { ConnectionMerge.isSameDevice(it, saved) },
                lastConnected = saved.lastConnected,
            )
        }.filterNot { u -> isActiveTarget(u.connectDevice, activeExternalDevice, isConnected, tvDevice) }
    }

    val discoveredByProtocol = remember(discoveredDevices, history, activeExternalDevice, isConnected, tvDevice) {
        discoveredDevices
            .filterNot { d -> isActiveTarget(d, activeExternalDevice, isConnected, tvDevice) }
            .map { discovered ->
                val historyEntry = history.find { ConnectionMerge.isSameDevice(it, discovered) }
                UnifiedDevice(
                    connectDevice = discovered,
                    historyEntry = historyEntry,
                    isOnline = true,
                    lastConnected = historyEntry?.lastConnected,
                )
            }
            .groupBy { it.connectDevice.resolvedProtocol }
    }

    var showManualDialog by remember { mutableStateOf(false) }
    var otherExpanded by remember { mutableStateOf(initialTab != 0) }
    var overflowOpen by remember { mutableStateOf(false) }
    val snackbarHostState = remember { SnackbarHostState() }
    val coroutineScope = rememberCoroutineScope()
    val listState = rememberLazyListState()

    LaunchedEffect(connectionState) {
        when (val state = connectionState) {
            is WebSocketClient.ConnectionState.PairingDenied ->
                coroutineScope.launch {
                    snackbarHostState.showSnackbar("${state.serverName} denied the connection")
                }
            is WebSocketClient.ConnectionState.AuthFailed ->
                coroutineScope.launch {
                    snackbarHostState.showSnackbar("Connection lost — tap the TV to reconnect")
                }
            is WebSocketClient.ConnectionState.PinMismatch ->
                coroutineScope.launch {
                    snackbarHostState.showSnackbar(
                        "Security warning: ${state.serverName}'s certificate changed. Forget the device and re-pair.",
                    )
                }
            else -> Unit
        }
    }

    LaunchedEffect(Unit) {
        viewModel.castNotices.collect { message ->
            snackbarHostState.showSnackbar(message)
        }
    }

    fun onDeviceSelected(ip: String, port: Int, name: String, uuid: String = "") =
        connectKnownOrPair(viewModel, history, ip, port, name, uuid)

    fun selectEndpoint(device: TvDevice) {
        // Heal stale saved IPs / control URLs against the current discovery set before
        // selecting (same path as DeviceConnectionSheet).
        val target = ConnectionMerge.withDiscoveredEndpoint(device, discoveredDevices)
        val ready = when (target.resolvedProtocol) {
            CastProtocol.DLNA -> viewModel.selectDlnaTarget(target)
            CastProtocol.ROKU -> true.also { viewModel.selectRokuTarget(target) }
            CastProtocol.GOOGLE_CAST -> true.also { viewModel.selectGoogleCastTarget(target) }
            CastProtocol.WEB_BROWSER -> true.also { viewModel.selectBrowserTarget(target) }
            CastProtocol.PLAYBRIDGE -> true.also {
                val alreadyLinked = isConnected && tvDevice?.let {
                    ConnectionMerge.isSameDevice(it, target)
                } == true
                if (alreadyLinked) viewModel.selectNativeRoute()
                else onDeviceSelected(target.ip, target.port, target.name, target.uuid)
            }
        }
        if (target.resolvedProtocol != CastProtocol.PLAYBRIDGE) {
            coroutineScope.launch {
                snackbarHostState.showSnackbar(
                    if (ready) "Selected ${target.name} — cast a video to play here"
                    else "${target.name} is still preparing. Try again in a moment.",
                )
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Devices") },
                navigationIcon = {
                    IconButton(
                        onClick = onMenuClick,
                        modifier = Modifier.semantics {
                            contentDescription = "Dashboard"
                            role = androidx.compose.ui.semantics.Role.Button
                        },
                    ) {
                        DashboardBlocksIcon(modifier = Modifier.size(22.dp))
                    }
                },
                actions = {
                    if (isScanning) {
                        CircularProgressIndicator(
                            modifier = Modifier
                                .padding(end = 4.dp)
                                .size(18.dp),
                            strokeWidth = 2.dp,
                        )
                    } else {
                        IconButton(onClick = { viewModel.rescan() }) {
                            Icon(
                                Icons.Default.Refresh,
                                contentDescription = "Rescan network",
                                tint = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                    if (onRemoteClick != null) {
                        IconButton(onClick = onRemoteClick) {
                            Icon(
                                Icons.Default.Gamepad,
                                contentDescription = "Remote Control",
                                tint = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                    Box {
                        IconButton(onClick = { overflowOpen = true }) {
                            Icon(Icons.Default.MoreVert, contentDescription = "More")
                        }
                        DropdownMenu(expanded = overflowOpen, onDismissRequest = { overflowOpen = false }) {
                            DropdownMenuItem(
                                text = { Text("Connect by IP…") },
                                onClick = {
                                    overflowOpen = false
                                    showManualDialog = true
                                },
                                leadingIcon = { Icon(Icons.Default.Add, contentDescription = null) },
                            )
                        }
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { paddingValues ->
        LazyColumn(
            state = listState,
            modifier = modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(horizontal = 16.dp),
            contentPadding = PaddingValues(top = 12.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // ── NOW ──────────────────────────────────────────────────────────
            item(key = "now-header") {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    LocalNetworkBanners(status = networkStatus)
                    SectionLabel("Now")
                }
            }
            item(key = "now-card") {
                NowDestinationCard(
                    connectionState = connectionState,
                    route = route,
                    tvDevice = tvDevice,
                    activeExternalDevice = activeExternalDevice,
                    autoConnectEnabled = autoConnectEnabled,
                    showAutoConnect = true,
                    onAutoConnectChange = { viewModel.setAutoConnectEnabled(it) },
                    onCastHere = { viewModel.selectNativeRoute() },
                    onDisconnectNative = {
                        viewModel.selectThisDevice()
                        viewModel.disconnect()
                    },
                    onDisconnectExternal = { viewModel.disconnectExternalTarget() },
                    isConnecting = isConnecting,
                    externalMediaTitle = externalMediaTitle,
                    externalCasting = castSessionState.phase == com.playbridge.sender.cast.SessionPhase.PLAYING ||
                        castSessionState.phase == com.playbridge.sender.cast.SessionPhase.CONNECTED ||
                        castSessionState.phase == com.playbridge.sender.cast.SessionPhase.CONNECTING,
                    streamRouteLabel = lastEffectiveStreamRoute?.label,
                )
            }

            item(key = "this-phone") {
                ThisDeviceDestinationRow(
                    selected = route is CastSessionManager.Route.ThisDevice &&
                        activeExternalDevice == null &&
                        !isConnecting,
                    onClick = { viewModel.selectThisDevice() },
                )
            }

            // ── PlayBridge ───────────────────────────────────────────────────
            item(key = "pb-header") {
                SectionLabel("PlayBridge")
            }

            if (playBridgeSaved.isEmpty() &&
                (discoveredByProtocol[CastProtocol.PLAYBRIDGE].orEmpty().isEmpty())
            ) {
                item(key = "pb-empty") {
                    EmptyHintCard(
                        if (isScanning) {
                            "Looking for PlayBridge TVs on your network…"
                        } else {
                            "No PlayBridge TVs yet. Open the PlayBridge app on your TV (same Wi‑Fi), then pull to refresh."
                        },
                    )
                }
            } else {
                items(
                    items = playBridgeSaved,
                    key = { "pb-saved-${it.connectDevice.endpointKey}" },
                ) { device ->
                    TvDeviceRow(
                        device = device,
                        showProtocolBadge = false,
                        onClick = { selectEndpoint(device.connectDevice) },
                        onRemove = device.historyEntry?.let { entry ->
                            { viewModel.removeDeviceFromHistory(entry) }
                        },
                    )
                }
                val unpairedDiscovered = discoveredByProtocol[CastProtocol.PLAYBRIDGE]
                    .orEmpty()
                    .filter { d ->
                        playBridgeSaved.none { ConnectionMerge.isSameDevice(it.connectDevice, d.connectDevice) }
                    }
                items(
                    items = unpairedDiscovered,
                    key = { "pb-new-${it.connectDevice.endpointKey}" },
                ) { device ->
                    TvDeviceRow(
                        device = device,
                        showProtocolBadge = false,
                        onClick = { selectEndpoint(device.connectDevice) },
                        onRemove = null,
                    )
                }
            }

            // ── Browser on TV (phone-hosted receiver) ─────────────────────────
            item(key = "browser-section") {
                BrowserOnTvSection(
                    hostState = browserHost,
                    networkStatus = networkStatus,
                    onOpenSetup = { showBrowserSheet = true },
                    onSelectReady = { session ->
                        selectEndpoint(
                            session.toTvDevice(browserRepo.lanHostIp(), browserHost.port),
                        )
                    },
                    onEnterCode = { request -> browserPairRequest = request },
                )
            }

            // ── Recent other (saved external shortcuts) ──────────────────────
            if (recentExternal.isNotEmpty()) {
                item(key = "recent-header") {
                    SectionLabel("Recent other")
                }
                items(
                    items = recentExternal,
                    key = { "ext-${it.connectDevice.endpointKey}" },
                ) { device ->
                    TvDeviceRow(
                        device = device,
                        showProtocolBadge = true,
                        onClick = { selectEndpoint(device.connectDevice) },
                        onRemove = device.historyEntry?.let { entry ->
                            { viewModel.removeDeviceFromHistory(entry) }
                        },
                    )
                }
            }

            // ── Other devices on this network ────────────────────────────────
            item(key = "other-header") {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { otherExpanded = !otherExpanded }
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        SectionLabel("Other devices on this network")
                        Text(
                            text = if (isScanning) "Scanning quietly…" else "DLNA, Roku, Google Cast",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Icon(
                        imageVector = if (otherExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                        contentDescription = if (otherExpanded) "Collapse" else "Expand",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            if (otherExpanded) {
                val otherProtocols = listOf(
                    CastProtocol.DLNA,
                    CastProtocol.ROKU,
                    CastProtocol.GOOGLE_CAST,
                )
                var anyOther = false
                otherProtocols.forEach { protocol ->
                    val devices = discoveredByProtocol[protocol].orEmpty()
                    if (devices.isNotEmpty()) {
                        anyOther = true
                        item(key = "proto-label-${protocol.name}") {
                            Text(
                                text = protocol.displayName,
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 4.dp),
                            )
                        }
                        items(
                            items = devices,
                            key = { "live-${it.connectDevice.endpointKey}" },
                        ) { device ->
                            TvDeviceRow(
                                device = device,
                                showProtocolBadge = false,
                                onClick = { selectEndpoint(device.connectDevice) },
                                onRemove = device.historyEntry?.let { entry ->
                                    { viewModel.removeDeviceFromHistory(entry) }
                                },
                            )
                        }
                    }
                }
                if (!anyOther) {
                    item(key = "other-empty") {
                        EmptyHintCard(
                            if (isScanning) "Looking for cast devices…"
                            else "No other devices found. Tap refresh to scan again.",
                        )
                    }
                }
            }
        }
    }

    if (showManualDialog) {
        ManualConnectionDialog(
            onDismiss = { showManualDialog = false },
            onConnect = { ip, port ->
                showManualDialog = false
                onDeviceSelected(ip, port, "Manual TV")
            },
        )
    }

    if (showBrowserSheet) {
        BrowserReceiverSheet(
            viewModel = viewModel,
            onDismiss = { showBrowserSheet = false },
        )
    }

    browserPairRequest?.let { request ->
        BrowserPairingCodeSheet(
            request = request,
            onDismiss = { browserPairRequest = null },
            onApprove = { code ->
                val result = browserRepo.approve(request.sessionId, code)
                if (result.isFailure) {
                    throw result.exceptionOrNull()
                        ?: IllegalStateException("Incorrect code")
                }
                browserPairRequest = null
            },
        )
    }
}

private fun isActiveTarget(
    device: TvDevice,
    activeExternal: TvDevice?,
    isConnected: Boolean,
    tvDevice: TvDevice?,
): Boolean {
    if (activeExternal != null && ConnectionMerge.isSameDevice(device, activeExternal)) return true
    if (isConnected && tvDevice != null && ConnectionMerge.isSameDevice(device, tvDevice)) return true
    return false
}

@Composable
internal fun SectionLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.primary,
        fontWeight = FontWeight.Bold,
    )
}

/**
 * Sticky cast/discovery banners for missing Wi‑Fi/Ethernet and active VPN.
 * Used on the Devices screen and the cast sheet.
 */
@Composable
fun LocalNetworkBanners(
    status: NetworkStatusRepository.Status,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    var vpnDismissed by remember { mutableStateOf(false) }

    // Re-show the VPN tip if the VPN toggles off then on again this session.
    LaunchedEffect(status.vpnActive) {
        if (!status.vpnActive) vpnDismissed = false
    }

    val showWifi = !status.onLocalNetwork
    val showVpn = status.onLocalNetwork && status.vpnActive && !vpnDismissed
    if (!showWifi && !showVpn) return

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (showWifi) {
            NetworkBanner(
                icon = Icons.Default.WifiOff,
                title = "Not on Wi‑Fi",
                body = "Connect this phone to the same Wi‑Fi as your TV to cast and discover devices.",
                containerColor = MaterialTheme.colorScheme.errorContainer,
                contentColor = MaterialTheme.colorScheme.onErrorContainer,
                actionLabel = "Wi‑Fi settings",
                onAction = {
                    runCatching {
                        context.startActivity(
                            Intent(Settings.ACTION_WIFI_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                        )
                    }
                },
            )
        } else if (showVpn) {
            NetworkBanner(
                icon = Icons.Default.Shield,
                title = "VPN may block casting",
                body = "Some VPNs hide your local network. If devices don’t appear, pause the VPN and try again.",
                containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
                actionLabel = null,
                onAction = null,
                onDismiss = { vpnDismissed = true },
            )
        }
    }
}

@Composable
private fun NetworkBanner(
    icon: ImageVector,
    title: String,
    body: String,
    containerColor: Color,
    contentColor: Color,
    actionLabel: String?,
    onAction: (() -> Unit)?,
    onDismiss: (() -> Unit)? = null,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = containerColor),
        shape = RoundedCornerShape(12.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                verticalAlignment = Alignment.Top,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = contentColor,
                    modifier = Modifier
                        .padding(top = 2.dp)
                        .size(22.dp),
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = contentColor,
                    )
                    Text(
                        text = body,
                        style = MaterialTheme.typography.bodySmall,
                        color = contentColor.copy(alpha = 0.9f),
                    )
                }
                if (onDismiss != null) {
                    IconButton(
                        onClick = onDismiss,
                        modifier = Modifier.size(32.dp),
                    ) {
                        Icon(
                            Icons.Default.Close,
                            contentDescription = "Dismiss",
                            tint = contentColor,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
            }
            if (actionLabel != null && onAction != null) {
                TextButton(
                    onClick = onAction,
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                ) {
                    Text(actionLabel, color = contentColor, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

@Composable
private fun EmptyHintCard(message: String) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        ),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * Active cast destination card shared by the Devices screen and the cast sheet.
 */
@Composable
fun NowDestinationCard(
    connectionState: WebSocketClient.ConnectionState,
    route: CastSessionManager.Route,
    tvDevice: TvDevice?,
    activeExternalDevice: TvDevice?,
    autoConnectEnabled: Boolean = false,
    showAutoConnect: Boolean = false,
    onAutoConnectChange: (Boolean) -> Unit = {},
    onCastHere: () -> Unit = {},
    onDisconnectNative: () -> Unit,
    onDisconnectExternal: () -> Unit,
    isConnecting: Boolean = false,
    externalMediaTitle: String? = null,
    externalCasting: Boolean = false,
    streamRouteLabel: String? = null,
) {
    val external = activeExternalDevice
    when {
        external != null -> {
            val isBrowser = external.resolvedProtocol == CastProtocol.WEB_BROWSER
            val subtitle = buildString {
                if (externalCasting && !externalMediaTitle.isNullOrBlank()) {
                    append(externalMediaTitle)
                } else if (isBrowser) {
                    append("Cast a video to play here")
                } else {
                    append("${external.ip} · cast a video to play here")
                }
                if (!streamRouteLabel.isNullOrBlank() && externalCasting) {
                    append(" · ")
                    append(streamRouteLabel)
                }
            }
            ActiveDestinationCard(
                name = external.name,
                subtitle = subtitle,
                icon = if (isBrowser) Icons.Default.Cast else Icons.Default.Cast,
                badge = external.resolvedProtocol.displayName,
                statusPill = if (externalCasting) "Casting" else "Ready",
                statusFilled = externalCasting,
                onDisconnect = onDisconnectExternal,
            )
        }
        connectionState is WebSocketClient.ConnectionState.Connected -> {
            val connected = connectionState
            val castingHere = route is CastSessionManager.Route.NativeTv
            ActiveDestinationCard(
                name = connected.serverName,
                subtitle = tvDevice?.let {
                    "${it.ip}:${if (connected.secure) (it.wssPort ?: it.port) else it.port}"
                },
                icon = Icons.Default.Tv,
                badge = null,
                statusPill = when {
                    castingHere -> if (connected.secure) "Casting" else "Casting"
                    connected.secure -> "Linked"
                    else -> "Connected"
                },
                statusFilled = castingHere,
                trailing = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Icon(
                            imageVector = if (connected.secure) Icons.Default.Lock else Icons.Default.LockOpen,
                            contentDescription = null,
                            modifier = Modifier.size(14.dp),
                            tint = if (connected.secure) OnlineGreen else WarningAmber,
                        )
                        Text(
                            text = if (connected.secure) "wss" else "ws",
                            style = MaterialTheme.typography.bodySmall,
                            color = if (connected.secure) OnlineGreen else WarningAmber,
                        )
                    }
                },
                footer = {
                    if (showAutoConnect) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(
                                checked = autoConnectEnabled,
                                onCheckedChange = onAutoConnectChange,
                            )
                            Text(
                                "Auto-connect to this TV",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                            )
                        }
                    }
                },
                onDisconnect = onDisconnectNative,
                secondaryAction = if (!castingHere) "Cast here" to onCastHere else null,
            )
        }
        isConnecting -> {
            ActiveDestinationCard(
                name = tvDevice?.name ?: "TV",
                subtitle = "Connecting…",
                icon = Icons.Default.Tv,
                badge = null,
                statusPill = "Connecting",
                statusFilled = false,
                onDisconnect = onDisconnectNative,
            )
        }
        else -> {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                ),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Icon(
                        Icons.Default.Smartphone,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Column {
                        Text(
                            "Nothing selected",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            "Pick a TV below, or play on this phone.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun ActiveDestinationCard(
    name: String,
    subtitle: String?,
    icon: ImageVector,
    badge: String?,
    statusPill: String? = null,
    statusFilled: Boolean = false,
    trailing: (@Composable () -> Unit)? = null,
    footer: (@Composable () -> Unit)? = null,
    secondaryAction: Pair<String, () -> Unit>? = null,
    onDisconnect: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Icon(
                    icon,
                    contentDescription = null,
                    modifier = Modifier.size(32.dp),
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                )
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    // Name alone on the first line so protocol/status chips never truncate it.
                    Text(
                        text = name,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    if (badge != null || statusPill != null) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            if (badge != null) ProtocolBadge(badge)
                            if (statusPill != null) {
                                StatusPill(statusPill, filled = statusFilled)
                            }
                        }
                    }
                    if (subtitle != null) {
                        Text(
                            text = subtitle,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f),
                        )
                    }
                    trailing?.invoke()
                }
            }
            footer?.invoke()
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
            ) {
                if (secondaryAction != null) {
                    TextButton(onClick = secondaryAction.second) {
                        Text(secondaryAction.first)
                    }
                }
                Button(
                    onClick = onDisconnect,
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                ) {
                    Text("Disconnect")
                }
            }
        }
    }
}

@Composable
fun ThisDeviceDestinationRow(selected: Boolean, onClick: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Icon(
                Icons.Default.Smartphone,
                contentDescription = null,
                modifier = Modifier.size(28.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
            Text(
                "This phone",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
            )
            if (selected) {
                Icon(Icons.Default.Check, contentDescription = "Selected", tint = OnlineGreen)
            }
        }
    }
}

@Composable
fun FindMoreDevicesRow(onClick: () -> Unit) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        color = Color.Transparent,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(
                Icons.Default.Add,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
            Text(
                text = "Find more devices…",
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
fun ProtocolBadge(text: String) {
    Surface(
        color = MaterialTheme.colorScheme.tertiaryContainer,
        contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
        shape = RoundedCornerShape(4.dp),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
        )
    }
}

@Composable
fun StatusPill(text: String, filled: Boolean) {
    val bg = if (filled) OnlineGreen.copy(alpha = 0.18f) else MaterialTheme.colorScheme.surfaceVariant
    val fg = if (filled) OnlineGreen else MaterialTheme.colorScheme.onSurfaceVariant
    Surface(color = bg, contentColor = fg, shape = RoundedCornerShape(50)) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
        )
    }
}

/**
 * A device in a destination list: the device to connect with, whether it's reachable,
 * and (if saved) its history entry + last-connected time.
 */
data class UnifiedDevice(
    val connectDevice: TvDevice,
    val historyEntry: TvDevice?,
    val isOnline: Boolean,
    val lastConnected: Long?,
) {
    val isKnown: Boolean get() = historyEntry != null
}

/**
 * Connect to a TV, reusing a saved pairing token when we have one (silent reconnect) and otherwise
 * connecting with an empty token so [WebSocketClient] sends a pairing request. Shared by
 * [ConnectionScreen] and the device-picker sheet.
 */
fun connectKnownOrPair(
    viewModel: ConnectionViewModel,
    history: List<TvDevice>,
    ip: String,
    port: Int,
    name: String,
    uuid: String = "",
) {
    val nativeHistory = ConnectionMerge.playBridgeHistory(history)
    val existing = if (uuid.isNotEmpty()) {
        nativeHistory.find { it.uuid == uuid } ?: nativeHistory.find { it.ip == ip && it.port == port }
    } else {
        nativeHistory.find { it.ip == ip && it.port == port }
    }
    if (existing != null && existing.token.isNotEmpty()) {
        viewModel.connect(
            existing.copy(
                name = name,
                ip = ip,
                port = port,
                uuid = if (uuid.isNotEmpty()) uuid else existing.uuid,
            ),
        )
    } else {
        viewModel.connect(TvDevice(ip = ip, port = port, token = "", name = name, uuid = uuid))
    }
}

fun formatLastSeen(millis: Long): String {
    val diff = System.currentTimeMillis() - millis
    return when {
        diff < 60_000L -> "Last seen just now"
        diff < 3_600_000L -> "Last seen ${diff / 60_000L}m ago"
        diff < 86_400_000L -> "Last seen ${diff / 3_600_000L}h ago"
        diff < 7 * 86_400_000L -> "Last seen ${diff / 86_400_000L}d ago"
        else -> "Saved"
    }
}

@Composable
fun TvDeviceRow(
    device: UnifiedDevice,
    onClick: () -> Unit,
    onRemove: (() -> Unit)? = null,
    showProtocolBadge: Boolean = true,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
        ),
    ) {
        Row(
            modifier = Modifier
                .padding(16.dp)
                .fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                modifier = Modifier.weight(1f),
            ) {
                Icon(
                    imageVector = when (device.connectDevice.resolvedProtocol) {
                        CastProtocol.DLNA, CastProtocol.GOOGLE_CAST, CastProtocol.WEB_BROWSER ->
                            Icons.Default.Cast
                        else -> Icons.Default.Tv
                    },
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(28.dp),
                )

                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    // Full-width title: protocol badge lives on the meta row so long
                    // TV names (e.g. "Samsung QN90B Living Room") are not clipped.
                    Text(
                        text = device.connectDevice.name,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        text = if (device.connectDevice.port > 0) {
                            "${device.connectDevice.ip}:${device.connectDevice.port}"
                        } else {
                            device.connectDevice.ip
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier.padding(top = 2.dp),
                    ) {
                        val dotColor = if (device.isOnline) OnlineGreen
                        else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(dotColor),
                        )
                        val statusText = when {
                            device.isOnline && !device.isKnown -> "Online · New"
                            device.isOnline -> "Online"
                            device.lastConnected != null -> formatLastSeen(device.lastConnected)
                            else -> "Saved"
                        }
                        Text(
                            text = statusText,
                            style = MaterialTheme.typography.bodySmall,
                            color = if (device.isOnline) OnlineGreen
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (showProtocolBadge) {
                            ProtocolBadge(device.connectDevice.resolvedProtocol.displayName)
                        }
                    }
                }
            }

            if (onRemove != null) {
                IconButton(
                    onClick = onRemove,
                    modifier = Modifier.semantics {
                        contentDescription = "Remove"
                        role = androidx.compose.ui.semantics.Role.Button
                    },
                ) {
                    Icon(
                        imageVector = Icons.Default.Delete,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
    }
}

@Composable
fun ManualConnectionDialog(
    onDismiss: () -> Unit,
    onConnect: (String, Int) -> Unit,
) {
    var ip by remember { mutableStateOf("") }
    var port by remember {
        mutableStateOf(com.playbridge.shared.protocol.Config.DEFAULT_PORT.toString())
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Connect by IP") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                OutlinedTextField(
                    value = ip,
                    onValueChange = { ip = it },
                    label = { Text("IP Address") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = port,
                    onValueChange = { if (it.all { char -> char.isDigit() }) port = it },
                    label = { Text("Port") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val portInt = port.toIntOrNull()
                    if (ip.isNotEmpty() && portInt != null) {
                        onConnect(ip, portInt)
                    }
                },
                enabled = ip.isNotEmpty() && port.isNotEmpty(),
            ) {
                Text("Connect")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        },
    )
}
