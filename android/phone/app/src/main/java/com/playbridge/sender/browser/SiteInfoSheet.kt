package com.playbridge.sender.browser

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

data class SiteSecurityInfo(
    val isSecure: Boolean,
    val host: String,
    val certIssuer: String? = null,
    val certValidUntil: String? = null
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SiteInfoSheet(
    info: SiteSecurityInfo,
    onDismiss: () -> Unit
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(bottom = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Icon + status
            Icon(
                imageVector = when {
                    info.isSecure -> Icons.Default.Lock
                    else -> Icons.Default.LockOpen
                },
                contentDescription = null,
                tint = when {
                    info.isSecure -> MaterialTheme.colorScheme.primary
                    else -> MaterialTheme.colorScheme.error
                },
                modifier = Modifier.size(40.dp)
            )

            Spacer(Modifier.height(12.dp))

            Text(
                text = if (info.isSecure) "Connection is secure" else "Connection is not secure",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )

            Spacer(Modifier.height(4.dp))

            Text(
                text = info.host.ifBlank { "Unknown site" },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(Modifier.height(16.dp))

            Text(
                text = if (info.isSecure)
                    "Information you send to this site (passwords, card numbers) is encrypted and can't be intercepted."
                else
                    "This site does not use HTTPS. Passwords and other sensitive information you enter may be visible to others.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            // Certificate details (only for secure)
            if (info.isSecure && (info.certIssuer != null || info.certValidUntil != null)) {
                Spacer(Modifier.height(20.dp))
                Spacer(modifier = Modifier.height(12.dp))
                Spacer(Modifier.height(16.dp))

                Text(
                    "Certificate",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))

                info.certIssuer?.let { issuer ->
                    CertRow(label = "Issued by", value = issuer)
                }
                info.certValidUntil?.let { expiry ->
                    CertRow(label = "Valid until", value = expiry)
                }
            }

            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun CertRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
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
