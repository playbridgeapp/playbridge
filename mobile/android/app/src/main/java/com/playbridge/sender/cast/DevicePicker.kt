package com.playbridge.sender.cast

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Cast
import androidx.compose.material.icons.filled.Smartphone
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.playbridge.sender.connection.ConnectionViewModel
import com.playbridge.sender.connection.WebSocketClient
import com.playbridge.sender.model.TvDevice
import org.koin.androidx.compose.koinViewModel

private val ConnectedGreen = Color(0xFF4CAF50)
private val ConnectingOrange = Color(0xFFFF9800)

internal fun googleCastPickerConnectionComplete(phase: SessionPhase): Boolean =
    phase == SessionPhase.CONNECTED || phase == SessionPhase.PLAYING

/**
 * The shared device/destination chip used by the sender destination controls
 * and the Cast sheet. Replaces the three hand-rolled `ChipDropdown` selectors: instead of a
 * dropdown it opens [PlayOnSheet] on tap.
 *
 * Presentation-only and self-contained — it reads the active [ConnectionViewModel] for its label
 * and status, so callers don't thread device lists/handlers down. Host-specific side effects go
 * through [onPickedThisDevice] / [onPickedDevice].
 *
 * @param showThisDevice include a "This Device" (play on phone) entry — false in the Cast sheet.
 * @param castStatusLabel use cast-target status wording ("<device name>" when connected,
 *   "Not connected" otherwise) instead of the default "Watching on: …" framing. True in the Cast sheet.
 */
@Composable
fun DeviceChip(
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
    val externalConnected = externalSelected && castSessionState.isReadyForPlayback
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
        PlayOnSheet(
            onDismiss = { showPicker = false },
            showThisDevice = showThisDevice,
            onPickedThisDevice = onPickedThisDevice,
            onPickedDevice = onPickedDevice
        )
    }
}
