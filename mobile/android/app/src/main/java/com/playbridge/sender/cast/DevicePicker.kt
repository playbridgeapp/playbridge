package com.playbridge.sender.cast

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Cast
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Smartphone
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
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

/**
 * The shared device/destination chip used by the Library top bar, the Library detail destination
 * row, and the Cast sheet. Replaces the three hand-rolled `ChipDropdown` selectors: instead of a
 * dropdown it opens [DeviceConnectionSheet] (a minimal connection screen) on tap.
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

    val externalSelected = route is CastSessionManager.Route.External && activeExternalDevice != null
    val nativeSelected = route is CastSessionManager.Route.NativeTv
    val isConnected = externalSelected ||
        (nativeSelected && connectionState is WebSocketClient.ConnectionState.Connected)
    val isConnecting = nativeSelected && (
        connectionState is WebSocketClient.ConnectionState.Connecting ||
            connectionState is WebSocketClient.ConnectionState.Retrying ||
            connectionState is WebSocketClient.ConnectionState.WaitingForApproval
        )

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
        DeviceConnectionSheet(
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
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeviceConnectionSheet(
    onDismiss: () -> Unit,
    onOpenAllDevices: () -> Unit,
    showThisDevice: Boolean = true,
    onPickedThisDevice: (() -> Unit)? = null,
    onPickedDevice: ((TvDevice) -> Unit)? = null,
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
        UnifiedDevice(
            connectDevice = saved,
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
        UnifiedDevice(
            connectDevice = saved,
            historyEntry = saved,
            isOnline = pings[saved.endpointKey.toString()] == true ||
                discoveredDevices.any { ConnectionMerge.isSameDevice(it, saved) },
            lastConnected = saved.lastConnected,
        )
    }.filterNot { isActive(it.connectDevice) }

    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    fun pick(device: TvDevice) {
        val ready = when (device.resolvedProtocol) {
            CastProtocol.DLNA -> viewModel.selectDlnaTarget(device)
            CastProtocol.ROKU -> true.also { viewModel.selectRokuTarget(device) }
            CastProtocol.GOOGLE_CAST -> true.also { viewModel.selectGoogleCastTarget(device) }
            CastProtocol.PLAYBRIDGE -> {
                viewModel.selectNativeRoute()
                val alreadyLinked = isConnected && tvDevice?.let {
                    ConnectionMerge.isSameDevice(it, device)
                } == true
                if (!alreadyLinked) {
                    connectKnownOrPair(
                        viewModel,
                        history,
                        device.ip,
                        device.port,
                        device.name,
                        device.uuid,
                    )
                }
                true
            }
        }
        if (ready) {
            onPickedDevice?.invoke(device)
            onDismiss()
        }
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = "Cast to",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
            )

            LocalNetworkBanners(status = networkStatus)

            NowDestinationCard(
                connectionState = connectionState,
                route = route,
                tvDevice = tvDevice,
                activeExternalDevice = activeExternalDevice,
                onCastHere = { viewModel.selectNativeRoute() },
                onDisconnectNative = {
                    viewModel.selectThisDevice()
                    viewModel.disconnect()
                    onDismiss()
                },
                onDisconnectExternal = {
                    viewModel.disconnectExternalTarget()
                    onDismiss()
                },
                isConnecting = isConnecting,
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
                    onClick = {
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
                        onClick = { pick(device.connectDevice) },
                        onRemove = device.historyEntry?.let { entry ->
                            { viewModel.removeDeviceFromHistory(entry) }
                        },
                    )
                }
            }

            if (recentExternal.isNotEmpty()) {
                SectionLabel("Recent other")
                recentExternal.forEach { device ->
                    TvDeviceRow(
                        device = device,
                        showProtocolBadge = true,
                        onClick = { pick(device.connectDevice) },
                        onRemove = device.historyEntry?.let { entry ->
                            { viewModel.removeDeviceFromHistory(entry) }
                        },
                    )
                }
            }

            FindMoreDevicesRow(onClick = {
                onDismiss()
                onOpenAllDevices()
            })
        }
    }
}
