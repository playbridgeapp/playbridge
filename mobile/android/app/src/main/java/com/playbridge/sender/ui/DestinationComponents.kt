package com.playbridge.sender.ui

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.WifiOff
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.playbridge.sender.connection.NetworkStatusRepository

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
