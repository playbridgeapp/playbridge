package com.playbridge.sender.browser

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import com.playbridge.sender.ui.theme.PlayBridgeTheme
import org.koin.compose.koinInject
import kotlinx.coroutines.launch

data class SiteSecurityInfo(
    val isSecure: Boolean,
    val host: String,
    val certIssuer: String? = null,
    val certValidUntil: String? = null
)

enum class SiteInfoPage {
    MAIN,
    CONNECTION_DETAILS,
    POPUP_BLOCKER
}

enum class SitePopupSetting {
    ALLOW,
    DEFAULT,
    BLOCK
}

@Composable
fun SiteInfoSheet(
    info: SiteSecurityInfo,
    onDismiss: () -> Unit
) {
    val settingsRepository: com.playbridge.sender.data.settings.SettingsRepository = koinInject()
    val scope = rememberCoroutineScope()

    // 1. Observe global settings and whitelists/blacklists
    val blockPopupsGlobal by settingsRepository.blockPopups.collectAsState(initial = true)
    val whitelist by settingsRepository.popupWhitelist.collectAsState(initial = emptySet())
    val blacklist by settingsRepository.popupBlacklist.collectAsState(initial = emptySet())

    // 2. Compute current site-specific status
    val currentSetting = remember(info.host, whitelist, blacklist) {
        when {
            isHostMatch(info.host, whitelist) -> SitePopupSetting.ALLOW
            isHostMatch(info.host, blacklist) -> SitePopupSetting.BLOCK
            else -> SitePopupSetting.DEFAULT
        }
    }

    var currentPage by remember { mutableStateOf(SiteInfoPage.MAIN) }

    val density = LocalDensity.current
    val statusBarHeightPx = WindowInsets.statusBars.getTop(density)
    val toolbarHeightPx = with(density) { 56.dp.roundToPx() }
    val topOffsetPx = statusBarHeightPx + toolbarHeightPx

    PlayBridgeTheme {
        Popup(
            alignment = Alignment.TopCenter,
            offset = IntOffset(0, topOffsetPx),
            onDismissRequest = onDismiss,
            properties = PopupProperties(
                focusable = true,
                dismissOnBackPress = true,
                dismissOnClickOutside = true
            )
        ) {
            Card(
                modifier = Modifier
                    .widthIn(max = 340.dp)
                    .fillMaxWidth(0.92f)
                    .padding(top = 8.dp),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    contentColor = MaterialTheme.colorScheme.onSurface
                ),
                elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp)
                ) {
                    when (currentPage) {
                        SiteInfoPage.MAIN -> {
                            // Header
                            Column(
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(
                                    text = info.host.ifBlank { "Unknown site" },
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = "Site settings",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }

                            Spacer(Modifier.height(12.dp))
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                            Spacer(Modifier.height(8.dp))

                            // 1. Connection Security Row
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { currentPage = SiteInfoPage.CONNECTION_DETAILS }
                                    .padding(vertical = 12.dp, horizontal = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = if (info.isSecure) Icons.Default.Lock else Icons.Default.Warning,
                                    contentDescription = null,
                                    tint = if (info.isSecure) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                                    modifier = Modifier.size(20.dp)
                                )
                                Spacer(Modifier.width(12.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = if (info.isSecure) "Connection is secure" else "Connection is not secure",
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                    Text(
                                        text = if (info.isSecure) "Your information is private" else "Sensitive info may be visible",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                Icon(
                                    imageVector = Icons.Default.ChevronRight,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(20.dp)
                                )
                            }

                            // 2. Pop-ups and Redirects Row
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { currentPage = SiteInfoPage.POPUP_BLOCKER }
                                    .padding(vertical = 12.dp, horizontal = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.OpenInNew,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(20.dp)
                                )
                                Spacer(Modifier.width(12.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = "Pop-ups and redirects",
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                    val statusText = when (currentSetting) {
                                        SitePopupSetting.ALLOW -> "Allowed"
                                        SitePopupSetting.BLOCK -> "Blocked"
                                        SitePopupSetting.DEFAULT -> if (blockPopupsGlobal) "Default (Block)" else "Default (Allow)"
                                    }
                                    Text(
                                        text = statusText,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                Icon(
                                    imageVector = Icons.Default.ChevronRight,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }

                        SiteInfoPage.CONNECTION_DETAILS -> {
                            // Header with Back button
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                IconButton(
                                    onClick = { currentPage = SiteInfoPage.MAIN },
                                    modifier = Modifier.size(32.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                        contentDescription = "Back",
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    text = "Connection security",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold
                                )
                            }

                            Spacer(Modifier.height(12.dp))
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                            Spacer(Modifier.height(12.dp))

                            // Content (what it currently shows)
                            Column(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Icon(
                                    imageVector = if (info.isSecure) Icons.Default.Lock else Icons.Default.LockOpen,
                                    contentDescription = null,
                                    tint = if (info.isSecure) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                                    modifier = Modifier.size(36.dp)
                                )

                                Spacer(Modifier.height(8.dp))

                                Text(
                                    text = if (info.isSecure) "Connection is secure" else "Connection is not secure",
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Bold
                                )

                                Spacer(Modifier.height(8.dp))

                                Text(
                                    text = if (info.isSecure)
                                        "Information you send to this site (passwords, card numbers) is encrypted and can't be intercepted."
                                    else
                                        "This site does not use HTTPS. Passwords and other sensitive information you enter may be visible to others.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )

                                if (info.isSecure && (info.certIssuer != null || info.certValidUntil != null)) {
                                    Spacer(Modifier.height(16.dp))
                                    Text(
                                        text = "Certificate Details",
                                        style = MaterialTheme.typography.bodySmall,
                                        fontWeight = FontWeight.Bold,
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                    Spacer(Modifier.height(6.dp))

                                    info.certIssuer?.let { issuer ->
                                        CertRow(label = "Issued by", value = issuer)
                                    }
                                    info.certValidUntil?.let { expiry ->
                                        CertRow(label = "Valid until", value = expiry)
                                    }
                                }
                            }
                        }

                        SiteInfoPage.POPUP_BLOCKER -> {
                            // Header with Back button
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                IconButton(
                                    onClick = { currentPage = SiteInfoPage.MAIN },
                                    modifier = Modifier.size(32.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                        contentDescription = "Back",
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    text = "Pop-ups and redirects",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold
                                )
                            }

                            Spacer(Modifier.height(12.dp))
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                            Spacer(Modifier.height(12.dp))

                            // Two selection options (Allowed or Blocked) with trailing checkmarks
                            Column(modifier = Modifier.fillMaxWidth()) {
                                // 1. Allowed
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            scope.launch {
                                                val isAllowed = isHostMatch(info.host, whitelist)
                                                if (isAllowed) {
                                                    // Toggle off (Default)
                                                    settingsRepository.removePopupWhitelist(info.host)
                                                    settingsRepository.removePopupBlacklist(info.host)
                                                } else {
                                                    // Toggle on
                                                    settingsRepository.addPopupWhitelist(info.host)
                                                    settingsRepository.removePopupBlacklist(info.host)
                                                }
                                            }
                                        }
                                        .padding(vertical = 12.dp, horizontal = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = "Allowed",
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = if (currentSetting == SitePopupSetting.ALLOW) FontWeight.SemiBold else FontWeight.Normal,
                                        modifier = Modifier.weight(1f)
                                    )
                                    if (currentSetting == SitePopupSetting.ALLOW) {
                                        Icon(
                                            imageVector = Icons.Default.Check,
                                            contentDescription = "Selected",
                                            tint = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.size(20.dp)
                                        )
                                    }
                                }

                                // 2. Blocked
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            scope.launch {
                                                val isBlocked = isHostMatch(info.host, blacklist)
                                                if (isBlocked) {
                                                    // Toggle off (Default)
                                                    settingsRepository.removePopupWhitelist(info.host)
                                                    settingsRepository.removePopupBlacklist(info.host)
                                                } else {
                                                    // Toggle on
                                                    settingsRepository.addPopupBlacklist(info.host)
                                                    settingsRepository.removePopupWhitelist(info.host)
                                                }
                                            }
                                        }
                                        .padding(vertical = 12.dp, horizontal = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = "Blocked",
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = if (currentSetting == SitePopupSetting.BLOCK) FontWeight.SemiBold else FontWeight.Normal,
                                        modifier = Modifier.weight(1f)
                                    )
                                    if (currentSetting == SitePopupSetting.BLOCK) {
                                        Icon(
                                            imageVector = Icons.Default.Check,
                                            contentDescription = "Selected",
                                            tint = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.size(20.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CertRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            value,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Medium
        )
    }
}

private fun isHostMatch(host: String, list: Set<String>): Boolean {
    val trimmedHost = host.trim().lowercase()
    if (trimmedHost.isBlank()) return false
    return list.any { exception ->
        val trimmedException = exception.trim().lowercase()
        trimmedException.isNotBlank() && (trimmedHost == trimmedException || trimmedHost.endsWith(".$trimmedException"))
    }
}
