package com.playbridge.sender.cast

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Cast
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Smartphone
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.playbridge.sender.cast.browser.BrowserReceiverRepository
import com.playbridge.sender.cast.browser.BrowserReceiverSheet
import com.playbridge.sender.connection.ConnectionMerge
import com.playbridge.sender.connection.ConnectionViewModel
import com.playbridge.sender.connection.WebSocketClient
import com.playbridge.sender.data.settings.SettingsRepository
import com.playbridge.sender.model.CastProtocol
import com.playbridge.sender.model.TvDevice
import com.playbridge.sender.ui.FindMoreDevicesRow
import com.playbridge.sender.ui.LocalNetworkBanners
import com.playbridge.sender.ui.NowDestinationCard
import com.playbridge.sender.ui.SectionLabel
import com.playbridge.sender.ui.ThisDeviceDestinationRow
import com.playbridge.sender.ui.TvDeviceRow
import com.playbridge.sender.ui.UnifiedDevice
import com.playbridge.sender.ui.connectKnownOrPair
import kotlinx.coroutines.launch
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject

private val ConnectedGreen = Color(0xFF4CAF50)
private val ConnectingOrange = Color(0xFFFF9800)

internal fun googleCastPickerConnectionComplete(phase: SessionPhase): Boolean =
    phase == SessionPhase.CONNECTED || phase == SessionPhase.PLAYING

@Composable
private fun DevicePickerDialogFrame(
    onDismissRequest: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    Dialog(onDismissRequest = onDismissRequest) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 720.dp),
            shape = MaterialTheme.shapes.extraLarge,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            tonalElevation = 6.dp,
            shadowElevation = 12.dp,
        ) {
            Column {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "Cast to",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )
                    IconButton(onClick = onDismissRequest) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Close",
                            modifier = Modifier.size(22.dp),
                        )
                    }
                }

                HorizontalDivider()
                content()
            }
        }
    }
}

/**
 * The shared device/destination chip used by the Library top bar, the Library detail destination
 * row, and the Cast sheet. Replaces the three hand-rolled `ChipDropdown` selectors: instead of a
 * dropdown it opens [DeviceConnectionDialog] (a minimal connection screen) on tap.
 *
 * Presentation-only and self-contained — it reads the active [ConnectionViewModel] for its label
 * and status, so callers don't thread device lists/handlers down. Host-specific side effects go
 * through [onPickedThisDevice] / [onPickedDevice].
 *
 * @param showThisDevice include a "This Device" (play on phone) entry — false in the Cast sheet.
 * @param castStatusLabel use cast-target status wording ("<device name>" when connected,
 *   "Not connected" otherwise) instead of the default "Watching on: …" framing. True in the Cast sheet.
 * @param onOpenAllDevices open the full Devices screen (discovery, manual IP, auto-connect).
 */
@Composable
fun DeviceChip(
    onOpenAllDevices: () -> Unit,
    modifier: Modifier = Modifier,
    showThisDevice: Boolean = true,
    castStatusLabel: Boolean = false,
    themeColor: Color = Color.Unspecified,
    fixedWidth: Dp? = null,
    onPickedThisDevice: (() -> Unit)? = null,
    onPickedDevice: ((TvDevice) -> Unit)? = null
) {
    val viewModel: ConnectionViewModel = koinViewModel()
    val connectionState by viewModel.connectionState.collectAsState()
    val tvDevice by viewModel.tvDevice.collectAsState(initial = null)
    val activeExternalDevice by viewModel.activeExternalDevice.collectAsState()
    val route by viewModel.route.collectAsState()
    val castSessionState by viewModel.castSessionState.collectAsState()

    val externalSelected = route is CastSessionManager.Route.External && activeExternalDevice != null
    val nativeSelected = route is CastSessionManager.Route.NativeTv
    val externalConnected = externalSelected && castSessionState.phase in setOf(
        SessionPhase.CONNECTED,
        SessionPhase.PLAYING,
    )
    val isConnected = externalConnected ||
        (nativeSelected && connectionState is WebSocketClient.ConnectionState.Connected)
    val isConnecting = if (externalSelected) {
        castSessionState.phase == SessionPhase.CONNECTING
    } else {
        nativeSelected && (
            connectionState is WebSocketClient.ConnectionState.Connecting ||
                connectionState is WebSocketClient.ConnectionState.Retrying ||
                connectionState is WebSocketClient.ConnectionState.WaitingForApproval
            )
    }

    val name = if (externalSelected) activeExternalDevice?.name else tvDevice?.name
    val label = when {
        castStatusLabel -> when {
            isConnected -> name ?: "TV"
            isConnecting -> "Connecting…"
            nativeSelected -> name ?: "TV"
            else -> "Not connected"
        }
        isConnected -> "Watching on: ${name ?: "TV"}"
        isConnecting -> "Connecting to: ${name ?: "TV"}…"
        nativeSelected -> "Watching on: ${name ?: "TV"}"
        else -> "Watching on: This Device"
    }
    val icon = when {
        externalSelected -> Icons.Default.Cast
        nativeSelected -> Icons.Default.Tv
        else -> Icons.Default.Smartphone
    }
    val iconTint = when {
        isConnected -> ConnectedGreen
        isConnecting -> ConnectingOrange
        else -> Color.White.copy(alpha = 0.7f)
    }

    // Mirror ChipDropdown's capsule styling so the chip looks identical to the old trigger.
    val accent = when {
        isConnected -> ConnectedGreen
        isConnecting -> ConnectingOrange
        else -> Color.Unspecified
    }
    val highlighted = accent != Color.Unspecified
    val labelColor = when {
        highlighted -> accent
        themeColor != Color.Unspecified -> Color.White.copy(alpha = 0.9f)
        else -> Color.White.copy(alpha = 0.75f)
    }
    val bg = when {
        highlighted -> accent.copy(alpha = 0.15f)
        themeColor != Color.Unspecified -> themeColor.copy(alpha = 0.12f)
        else -> Color.White.copy(alpha = 0.08f)
    }
    val borderColor = when {
        highlighted -> accent.copy(alpha = 0.5f)
        themeColor != Color.Unspecified -> themeColor.copy(alpha = 0.4f)
        else -> Color.White.copy(alpha = 0.2f)
    }

    var showPicker by remember { mutableStateOf(false) }

    Surface(
        modifier = modifier
            .then(if (fixedWidth != null) Modifier.width(fixedWidth) else Modifier)
            .clickable { showPicker = true },
        shape = RoundedCornerShape(50),
        color = bg,
        border = BorderStroke(1.dp, borderColor)
    ) {
        Row(
            modifier = Modifier
                .then(if (fixedWidth != null) Modifier.fillMaxWidth() else Modifier)
                .padding(horizontal = 10.dp, vertical = 5.dp),
            horizontalArrangement = Arrangement.spacedBy(5.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(13.dp), tint = iconTint)
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Medium,
                color = labelColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = if (fixedWidth != null) Modifier.weight(1f) else Modifier
            )
            Icon(
                imageVector = Icons.Default.ArrowDropDown,
                contentDescription = null,
                modifier = Modifier.size(13.dp),
                tint = labelColor.copy(alpha = 0.6f)
            )
        }
    }

    if (showPicker) {
        DeviceConnectionDialog(
            onDismiss = { showPicker = false },
            onOpenAllDevices = {
                showPicker = false
                onOpenAllDevices()
            },
            showThisDevice = showThisDevice,
            onPickedThisDevice = onPickedThisDevice,
            onPickedDevice = onPickedDevice
        )
    }
}

/**
 * Fast destination picker: current cast target, optional "This phone", paired PlayBridge
 * TVs, a short "Recent other" list, and a link into the full Devices screen for discovery.
 *
 * Self-sources the activity [ConnectionViewModel]; host hooks run after routing changes.
 */
@Composable
fun DeviceConnectionDialog(
    onDismiss: () -> Unit,
    onOpenAllDevices: () -> Unit,
    showThisDevice: Boolean = true,
    onPickedThisDevice: (() -> Unit)? = null,
    onPickedDevice: ((TvDevice) -> Unit)? = null,
    playBridgeOnly: Boolean = false,
) {
    val viewModel: ConnectionViewModel = koinViewModel()
    val history by viewModel.deviceHistory.collectAsState(initial = emptyList())
    val discoveredDevices by viewModel.discoveredDevices.collectAsState()
    val pings by viewModel.savedDevicesOnlineStatus.collectAsState()
    val connectionState by viewModel.connectionState.collectAsState()
    val tvDevice by viewModel.tvDevice.collectAsState(initial = null)
    val activeExternalDevice by viewModel.activeExternalDevice.collectAsState()
    val route by viewModel.route.collectAsState()
    val networkStatus by viewModel.networkStatus.collectAsState()
    val browserRepo: BrowserReceiverRepository = koinInject()
    val browserHost by browserRepo.state.collectAsState()
    val externalMediaTitle by viewModel.externalMediaTitle.collectAsState()
    val castSessionState by viewModel.castSessionState.collectAsState()
    val lastEffectiveStreamRoute by viewModel.lastEffectiveStreamRoute.collectAsState()
    var showBrowserSheet by remember { mutableStateOf(false) }
    var pendingGoogleCast by remember { mutableStateOf<TvDevice?>(null) }

    val isConnected = connectionState is WebSocketClient.ConnectionState.Connected
    val nativeSelected = route is CastSessionManager.Route.NativeTv
    val isConnecting = nativeSelected && (
        connectionState is WebSocketClient.ConnectionState.Connecting ||
            connectionState is WebSocketClient.ConnectionState.WaitingForApproval ||
            connectionState is WebSocketClient.ConnectionState.Retrying
        )
    val onPhone = route is CastSessionManager.Route.ThisDevice && activeExternalDevice == null

    DisposableEffect(Unit) {
        viewModel.retainDiscovery()
        onDispose { viewModel.releaseDiscovery() }
    }

    LaunchedEffect(history) {
        viewModel.pingSavedDevices(history)
    }

    fun isActive(device: TvDevice): Boolean {
        val external = activeExternalDevice
        if (external != null && ConnectionMerge.isSameDevice(device, external)) {
            return true
        }
        val linked = tvDevice
        if (isConnected && linked != null && ConnectionMerge.isSameDevice(device, linked)) {
            return true
        }
        return false
    }

    val playBridgeList = ConnectionMerge.playBridgeHistory(history).map { saved ->
        // Prefer the live discovered endpoint (DHCP/control URL can change) while
        // keeping the history entry for credentials and last-connected metadata.
        val live = ConnectionMerge.withDiscoveredEndpoint(saved, discoveredDevices)
        UnifiedDevice(
            connectDevice = live,
            historyEntry = saved,
            isOnline = pings[saved.endpointKey.toString()] == true ||
                discoveredDevices.any { ConnectionMerge.isSameDevice(it, saved) },
            lastConnected = saved.lastConnected,
        )
    }.filterNot { isActive(it.connectDevice) }
        .sortedWith(
            compareByDescending<UnifiedDevice> { it.isOnline }
                .thenByDescending { it.lastConnected ?: 0L },
        )

    val recentExternal = ConnectionMerge.recentExternalHistory(history).map { saved ->
        val live = ConnectionMerge.withDiscoveredEndpoint(saved, discoveredDevices)
        UnifiedDevice(
            connectDevice = live,
            historyEntry = saved,
            isOnline = pings[saved.endpointKey.toString()] == true ||
                discoveredDevices.any { ConnectionMerge.isSameDevice(it, saved) },
            lastConnected = saved.lastConnected,
        )
    }.filterNot { isActive(it.connectDevice) }

    fun dismissDialog() {
        val pending = pendingGoogleCast
        val active = activeExternalDevice
        val pendingConnectionIsActive = pending != null && active != null &&
            ConnectionMerge.isSameDevice(pending, active)
        pendingGoogleCast = null
        if (pendingConnectionIsActive && !googleCastPickerConnectionComplete(castSessionState.phase)) {
            viewModel.disconnectExternalTarget()
        }
        onDismiss()
    }

    LaunchedEffect(pendingGoogleCast, activeExternalDevice, castSessionState.phase) {
        val pending = pendingGoogleCast ?: return@LaunchedEffect
        val active = activeExternalDevice ?: return@LaunchedEffect
        if (
            ConnectionMerge.isSameDevice(pending, active) &&
            googleCastPickerConnectionComplete(castSessionState.phase)
        ) {
            pendingGoogleCast = null
            onPickedDevice?.invoke(pending)
            onDismiss()
        }
    }

    fun pick(device: TvDevice) {
        // Heal stale saved IPs / DLNA control URLs against the current discovery set
        // before selecting — shortcuts stay "online" by UUID but may still hold old endpoints.
        val target = ConnectionMerge.withDiscoveredEndpoint(device, discoveredDevices)
        if (target.resolvedProtocol == CastProtocol.GOOGLE_CAST) {
            pendingGoogleCast = target
            viewModel.selectGoogleCastTarget(target)
            return
        }
        pendingGoogleCast = null
        val ready = when (target.resolvedProtocol) {
            CastProtocol.DLNA -> viewModel.selectDlnaTarget(target)
            CastProtocol.ROKU -> true.also { viewModel.selectRokuTarget(target) }
            CastProtocol.GOOGLE_CAST -> false // Handled above; connection is asynchronous.
            CastProtocol.WEB_BROWSER -> true.also { viewModel.selectBrowserTarget(target) }
            CastProtocol.PLAYBRIDGE -> {
                viewModel.selectNativeRoute()
                val alreadyLinked = isConnected && tvDevice?.let {
                    ConnectionMerge.isSameDevice(it, target)
                } == true
                if (!alreadyLinked) {
                    connectKnownOrPair(
                        viewModel,
                        history,
                        target.ip,
                        target.port,
                        target.name,
                        target.uuid,
                    )
                }
                true
            }
        }
        if (ready) {
            onPickedDevice?.invoke(target)
            onDismiss()
        }
    }

    DevicePickerDialogFrame(onDismissRequest = ::dismissDialog) {
        Column(
            modifier = Modifier
                .weight(1f, fill = false)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 14.dp, vertical = 10.dp)
                .padding(bottom = 4.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            LocalNetworkBanners(status = networkStatus)

            NowDestinationCard(
                connectionState = connectionState,
                route = route,
                tvDevice = tvDevice,
                activeExternalDevice = activeExternalDevice,
                onCastHere = { viewModel.selectNativeRoute() },
                onDisconnectNative = {
                    pendingGoogleCast = null
                    viewModel.selectThisDevice()
                    viewModel.disconnect()
                    onDismiss()
                },
                onDisconnectExternal = {
                    pendingGoogleCast = null
                    viewModel.disconnectExternalTarget()
                    onDismiss()
                },
                isConnecting = isConnecting,
                externalMediaTitle = externalMediaTitle,
                externalCasting = castSessionState.phase == SessionPhase.PLAYING,
                externalConnecting = castSessionState.phase == SessionPhase.CONNECTING,
                externalFailed = castSessionState.phase == SessionPhase.FAILED,
                streamRouteLabel = lastEffectiveStreamRoute?.label,
                compact = true,
            )

            // Player-engine picker for a linked PlayBridge session (native route only).
            if (nativeSelected && isConnected && activeExternalDevice == null) {
                val settingsRepository: SettingsRepository = koinInject()
                val playerMode by settingsRepository.tvPlayerMode.collectAsState(initial = "tv")
                val scope = rememberCoroutineScope()
                val playerOptions = TvCapabilityOptions.playerOptions(tvDevice)
                val selectedPlayer = TvCapabilityOptions.coerceSelection(playerMode, playerOptions)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "Player",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    playerOptions.forEach { (id, label) ->
                        FilterChip(
                            selected = id == selectedPlayer,
                            onClick = { scope.launch { settingsRepository.setTvPlayerMode(id) } },
                            label = { Text(label) },
                        )
                    }
                }
            }

            if (showThisDevice) {
                ThisDeviceDestinationRow(
                    selected = onPhone && !isConnecting,
                    compact = true,
                    onClick = {
                        pendingGoogleCast = null
                        viewModel.selectThisDevice()
                        onPickedThisDevice?.invoke()
                        onDismiss()
                    },
                )
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SectionLabel("PlayBridge")
                IconButton(onClick = {
                    viewModel.pingSavedDevices(history)
                    viewModel.rescan()
                }) {
                    Icon(
                        imageVector = Icons.Default.Refresh,
                        contentDescription = "Refresh",
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
            }

            if (playBridgeList.isEmpty()) {
                Text(
                    text = "No PlayBridge TVs yet. Tap Find more devices to scan and pair.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 4.dp),
                )
            } else {
                playBridgeList.forEach { device ->
                    TvDeviceRow(
                        device = device,
                        showProtocolBadge = false,
                        compact = true,
                        onClick = { pick(device.connectDevice) },
                        onRemove = device.historyEntry?.let { entry ->
                            { viewModel.removeDeviceFromHistory(entry) }
                        },
                    )
                }
            }

            if (!playBridgeOnly && recentExternal.isNotEmpty()) {
                SectionLabel("Recent other")
                recentExternal.forEach { device ->
                    TvDeviceRow(
                        device = device,
                        showProtocolBadge = true,
                        compact = true,
                        onClick = { pick(device.connectDevice) },
                        onRemove = device.historyEntry?.let { entry ->
                            { viewModel.removeDeviceFromHistory(entry) }
                        },
                    )
                }
            }

            // Ready browser sessions (host running) + setup entry.
            if (!playBridgeOnly && browserHost.running && browserHost.ready.isNotEmpty()) {
                SectionLabel("Browser")
                browserHost.ready.forEach { session ->
                    val device = session.toTvDevice(browserRepo.lanHostIp(), browserHost.port)
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { pick(device) },
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 10.dp, vertical = 9.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Icon(
                                Icons.Default.Cast,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(22.dp),
                            )
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    session.name,
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.SemiBold,
                                )
                                Text(
                                    "Browser · Ready",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }

            if (!playBridgeOnly) Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { showBrowserSheet = true },
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 10.dp, vertical = 9.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Icon(
                        Icons.Default.Cast,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(22.dp),
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = if (browserHost.running && browserHost.pending.isNotEmpty()) {
                                "Cast to browser…"
                            } else {
                                "Cast to browser…"
                            },
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            text = when {
                                browserHost.pending.isNotEmpty() -> "Code waiting"
                                browserHost.running -> "Host running · manage pairing"
                                else -> "TV, console, or PC browser"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            FindMoreDevicesRow(compact = true, onClick = {
                dismissDialog()
                onOpenAllDevices()
            })
        }
    }

    if (!playBridgeOnly && showBrowserSheet) {
        BrowserReceiverSheet(
            viewModel = viewModel,
            onDismiss = { showBrowserSheet = false },
            onCastHere = {
                // Destination already selected inside the sheet; dismiss the picker too.
                pendingGoogleCast = null
                onDismiss()
            },
        )
    }
}
