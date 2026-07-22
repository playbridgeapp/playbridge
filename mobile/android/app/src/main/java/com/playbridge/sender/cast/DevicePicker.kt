package com.playbridge.sender.cast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Cast
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Smartphone
import androidx.compose.material.icons.filled.Tv
import androidx.compose.runtime.LaunchedEffect
import com.playbridge.sender.ui.UnifiedDevice
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.playbridge.sender.connection.ConnectionViewModel
import com.playbridge.sender.connection.ConnectionMerge
import com.playbridge.sender.connection.WebSocketClient
import com.playbridge.sender.data.settings.SettingsRepository
import com.playbridge.sender.model.TvDevice
import com.playbridge.sender.model.CastProtocol
import com.playbridge.sender.ui.TvDeviceRow
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
 * @param onOpenAllDevices open the full TV Connection screen (manual connect, DLNA, auto-connect).
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
 * Bottom-sheet device picker: a minimal version of the full TV Connection screen. Shows the active
 * cast target (with Disconnect), an optional "This Device" entry, the saved "Your TVs" list,
 * and an "All devices" link to the full discovery screen.
 *
 * Self-sources the activity [ConnectionViewModel] and drives connect/disconnect/DLNA directly;
 * [onPickedThisDevice] / [onPickedDevice] are optional host hooks after routing changes.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeviceConnectionSheet(
    onDismiss: () -> Unit,
    onOpenAllDevices: () -> Unit,
    showThisDevice: Boolean = true,
    onPickedThisDevice: (() -> Unit)? = null,
    onPickedDevice: ((TvDevice) -> Unit)? = null
) {
    val viewModel: ConnectionViewModel = koinViewModel()
    val history by viewModel.deviceHistory.collectAsState(initial = emptyList())
    val pings by viewModel.savedDevicesOnlineStatus.collectAsState()
    val connectionState by viewModel.connectionState.collectAsState()
    val tvDevice by viewModel.tvDevice.collectAsState(initial = null)
    val activeExternalDevice by viewModel.activeExternalDevice.collectAsState()
    val route by viewModel.route.collectAsState()

    val isConnected = connectionState is WebSocketClient.ConnectionState.Connected
    val nativeSelected = route is CastSessionManager.Route.NativeTv
    val isConnecting = nativeSelected && (
        connectionState is WebSocketClient.ConnectionState.Connecting ||
            connectionState is WebSocketClient.ConnectionState.WaitingForApproval ||
            connectionState is WebSocketClient.ConnectionState.Retrying
        )
    val onPhone = route is CastSessionManager.Route.ThisDevice

    // Ping saved devices when history changes or sheet is opened
    LaunchedEffect(history) {
        viewModel.pingSavedDevices(history)
    }

    val unified = history.map { saved ->
        val isOnline = pings[saved.endpointKey.toString()] == true
        UnifiedDevice(
            connectDevice = saved,
            historyEntry = saved,
            isOnline = isOnline,
            lastConnected = saved.lastConnected
        )
    }.filterNot { u ->
        activeExternalDevice?.let { ConnectionMerge.isSameDevice(u.connectDevice, it) } == true ||
            (nativeSelected && isConnected && tvDevice?.let { c ->
                ConnectionMerge.isSameDevice(u.connectDevice, c)
            } == true)
    }.sortedWith(
        compareByDescending<UnifiedDevice> { it.isOnline }
            .thenByDescending { it.lastConnected ?: Long.MAX_VALUE }
    )
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = "Cast to",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )

            // Active target card (DLNA / connected TV) or a connecting indicator.
            val external = activeExternalDevice
            when {
                external != null -> ActiveDeviceCard(
                    name = external.name,
                    subtitle = "${external.ip} · cast a video to play here",
                    icon = Icons.Default.Cast,
                    badge = external.resolvedProtocol.displayName,
                    onDisconnect = {
                        viewModel.disconnectExternalTarget()
                        onDismiss()
                    }
                )
                nativeSelected && isConnected -> {
                    val connected = connectionState as WebSocketClient.ConnectionState.Connected
                    ActiveDeviceCard(
                        name = connected.serverName,
                        subtitle = tvDevice?.let { "${it.ip}:${it.port}" },
                        icon = Icons.Default.Tv,
                        badge = CastProtocol.PLAYBRIDGE.displayName,
                        onDisconnect = {
                            // A manual disconnect is a deliberate "stop watching on TV":
                            // route back to this phone so nothing tries to reconnect.
                            viewModel.selectThisDevice()
                            viewModel.disconnect()
                            onDismiss()
                        }
                    )
                }
                // While connecting/reconnecting, the global connection popup (in
                // BrowserActivity) is the single source of truth — don't also show an
                // inline card here, which produced two overlays saying the same thing.
                isConnecting -> Unit
            }

            // Player-engine picker, shown with the connected TV it configures (native
            // sessions only — DLNA renderers pick their own player). Options reflect
            // what this TV reported at auth; the choice persists via SettingsRepository.
            if (nativeSelected && isConnected && external == null) {
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
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Player",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    playerOptions.forEach { (id, label) ->
                        FilterChip(
                            selected = id == selectedPlayer,
                            onClick = { scope.launch { settingsRepository.setTvPlayerMode(id) } },
                            label = { Text(label) }
                        )
                    }
                }
            }

            if (showThisDevice) {
                ThisDeviceRow(
                    selected = onPhone,
                    onClick = {
                        // Authoritative routing intent.
                        viewModel.selectThisDevice()
                        onPickedThisDevice?.invoke()
                        onDismiss()
                    }
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Your TVs",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold
                )
                IconButton(onClick = { viewModel.pingSavedDevices(history) }) {
                    Icon(
                        imageVector = Icons.Default.Refresh,
                        contentDescription = "Refresh TV status",
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
            }

            if (unified.isEmpty()) {
                Text(
                    text = "No saved TVs. Tap 'Set up new TV' to scan.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 8.dp)
                )
            } else {
                var selectedProtocolFilter by remember { mutableStateOf("All") }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    listOf("All", "PlayBridge", "DLNA", "Roku", "Google Cast").forEach { proto ->
                        FilterChip(
                            selected = selectedProtocolFilter == proto,
                            onClick = { selectedProtocolFilter = proto },
                            label = { Text(proto) }
                        )
                    }
                }

                val filtered = unified.filter { u ->
                    when (selectedProtocolFilter) {
                        "PlayBridge" -> u.connectDevice.resolvedProtocol == CastProtocol.PLAYBRIDGE
                        "DLNA" -> u.connectDevice.resolvedProtocol == CastProtocol.DLNA
                        "Roku" -> u.connectDevice.resolvedProtocol == CastProtocol.ROKU
                        "Google Cast" -> u.connectDevice.resolvedProtocol == CastProtocol.GOOGLE_CAST
                        else -> true
                    }
                }

                filtered.forEach { device ->
                    TvDeviceRow(
                        device = device,
                        onClick = {
                            val ready = when (device.connectDevice.resolvedProtocol) {
                                CastProtocol.DLNA -> viewModel.selectDlnaTarget(device.connectDevice)
                                CastProtocol.ROKU -> true.also {
                                    viewModel.selectRokuTarget(device.connectDevice)
                                }
                                CastProtocol.GOOGLE_CAST -> true.also {
                                    viewModel.selectGoogleCastTarget(device.connectDevice)
                                }
                                CastProtocol.PLAYBRIDGE -> {
                                    viewModel.selectNativeRoute()
                                    val alreadyLinked = isConnected && tvDevice?.let {
                                        ConnectionMerge.isSameDevice(it, device.connectDevice)
                                    } == true
                                    if (!alreadyLinked) {
                                        connectKnownOrPair(
                                            viewModel,
                                            history,
                                            device.connectDevice.ip,
                                            device.connectDevice.port,
                                            device.connectDevice.name,
                                            device.connectDevice.uuid
                                        )
                                    }
                                    true
                                }
                            }
                            if (ready) {
                                onPickedDevice?.invoke(device.connectDevice)
                                onDismiss()
                            }
                        },
                        onRemove = device.historyEntry?.let { entry ->
                            { viewModel.removeDeviceFromHistory(entry) }
                        }
                    )
                }
            }

            AllDevicesRow(onClick = onOpenAllDevices)
        }
    }
}

@Composable
private fun ActiveDeviceCard(
    name: String,
    subtitle: String?,
    icon: ImageVector,
    badge: String?,
    onDisconnect: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(32.dp), tint = MaterialTheme.colorScheme.onPrimaryContainer)
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = name,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    if (badge != null) ProtocolBadge(badge)
                }
                if (subtitle != null) {
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f)
                    )
                }
            }
            TextButton(onClick = onDisconnect) {
                Text("Disconnect", color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

@Composable
private fun ThisDeviceRow(selected: Boolean, onClick: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Icon(Icons.Default.Smartphone, contentDescription = null, modifier = Modifier.size(32.dp), tint = MaterialTheme.colorScheme.primary)
            Text("This Device", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            if (selected) {
                Icon(Icons.Default.Check, contentDescription = "Selected", tint = ConnectedGreen)
            }
        }
    }
}

@Composable
private fun AllDevicesRow(onClick: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        color = Color.Transparent
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(
                Icons.Default.Add,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint = MaterialTheme.colorScheme.primary
            )
            Text(
                text = "Set up new TV",
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@Composable
private fun ProtocolBadge(text: String) {
    Surface(
        color = MaterialTheme.colorScheme.tertiaryContainer,
        contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
        shape = RoundedCornerShape(4.dp)
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
        )
    }
}
