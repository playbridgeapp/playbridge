package com.playbridge.sender.browser

import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.Color
import com.playbridge.sender.data.library.AddonRepository
import com.playbridge.sender.data.library.InstalledAddonEntity
import com.playbridge.sender.data.library.parsedCatalogEntries
import com.playbridge.sender.data.library.supportsResource
import kotlinx.coroutines.launch

/**
 * Screen to manage installed Stremio addons.
 * Users can paste a manifest URL to install addons and remove existing ones.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddonSettingsScreen(
    addonRepository: AddonRepository,
    installedAddons: List<InstalledAddonEntity>,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val keyboardController = LocalSoftwareKeyboardController.current

    var addonUrl by remember { mutableStateOf("") }
    var isInstalling by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Stremio Addons") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                }
            )
        }
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Install section
            item {
                Card(
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
                    )
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Text(
                            text = "Add Addon",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "Paste the addon URL from Stremio. For Torrentio with Real-Debrid, configure it at torrentio.strem.fun and copy the install URL.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        OutlinedTextField(
                            value = addonUrl,
                            onValueChange = { addonUrl = it },
                            label = { Text("Addon URL") },
                            placeholder = { Text("https://torrentio.strem.fun/.../manifest.json") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                            keyboardActions = KeyboardActions(
                                onDone = { keyboardController?.hide() }
                            ),
                            modifier = Modifier.fillMaxWidth()
                        )

                        Button(
                            onClick = {
                                if (addonUrl.isBlank()) return@Button
                                isInstalling = true
                                scope.launch {
                                    val result = addonRepository.installAddon(addonUrl)
                                    isInstalling = false
                                    if (result != null) {
                                        addonUrl = ""
                                        Toast.makeText(context, "Installed: ${result.name}", Toast.LENGTH_SHORT).show()
                                    } else {
                                        Toast.makeText(context, "Failed to install addon", Toast.LENGTH_SHORT).show()
                                    }
                                }
                            },
                            enabled = addonUrl.isNotBlank() && !isInstalling,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            if (isInstalling) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(18.dp),
                                    strokeWidth = 2.dp,
                                    color = MaterialTheme.colorScheme.onPrimary
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Installing…")
                            } else {
                                Icon(Icons.Default.Add, null, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Install Addon")
                            }
                        }
                    }
                }
            }

            // Installed addons header
            if (installedAddons.isNotEmpty()) {
                item {
                    Text(
                        text = "Installed (${installedAddons.size})",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }
            }

            // Installed addons list
            items(installedAddons, key = { it.manifestUrl }) { addon ->
                Card(
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = addon.name,
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold
                            )
                            if (addon.description.isNotBlank()) {
                                Text(
                                    text = addon.description,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            // Version + content types
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                if (addon.version.isNotBlank()) {
                                    Text(
                                        text = "v${addon.version}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                }
                                if (addon.types.isNotBlank()) {
                                    Text(
                                        text = addon.types,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                            // Capability badges
                            AddonCapabilityBadges(addon = addon)
                        }

                        IconButton(
                            onClick = {
                                scope.launch {
                                    addonRepository.removeAddon(addon)
                                    Toast.makeText(context, "Removed: ${addon.name}", Toast.LENGTH_SHORT).show()
                                }
                            }
                        ) {
                            Icon(
                                Icons.Default.Delete,
                                contentDescription = "Remove",
                                tint = MaterialTheme.colorScheme.error
                            )
                        }
                    }
                }
            }

            // Empty state
            if (installedAddons.isEmpty()) {

                item {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(32.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(
                                Icons.Default.Extension,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(48.dp)
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = "No addons installed",
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = "Add a Stremio addon to start streaming",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }
    }
}

// ==================== Capability Badge Composables ====================

/**
 * Renders a compact row of capability badges for an installed addon.
 * Shows which Stremio resources the addon supports (Streams, Catalog, Meta, Subtitles)
 * and the number of catalogs it exposes.
 */
@Composable
private fun AddonCapabilityBadges(addon: InstalledAddonEntity) {
    val resources = listOf("stream", "catalog", "meta", "subtitles")
    val supported = resources.filter { addon.supportsResource(it) }
    if (supported.isEmpty()) return

    val catalogCount = remember(addon.catalogsJson) {
        addon.parsedCatalogEntries().size
    }

    Spacer(Modifier.height(6.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        supported.forEach { cap ->
            val label = when (cap) {
                "stream" -> "Streams"
                "catalog" -> if (catalogCount > 0) "$catalogCount Catalogs" else "Catalogs"
                "meta" -> "Meta"
                "subtitles" -> "Subtitles"
                else -> cap.replaceFirstChar { it.uppercaseChar() }
            }
            CapabilityBadge(label = label, cap = cap)
        }
    }
}

@Composable
private fun CapabilityBadge(label: String, cap: String) {
    val containerColor = when (cap) {
        "stream" -> MaterialTheme.colorScheme.primaryContainer
        "catalog" -> MaterialTheme.colorScheme.tertiaryContainer
        "meta" -> MaterialTheme.colorScheme.secondaryContainer
        "subtitles" -> Color(0xFFFFE0B2) // amber-100
        else -> MaterialTheme.colorScheme.surfaceVariant
    }
    val contentColor = when (cap) {
        "stream" -> MaterialTheme.colorScheme.onPrimaryContainer
        "catalog" -> MaterialTheme.colorScheme.onTertiaryContainer
        "meta" -> MaterialTheme.colorScheme.onSecondaryContainer
        "subtitles" -> Color(0xFFE65100) // deep-orange
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Surface(
        shape = MaterialTheme.shapes.extraSmall,
        color = containerColor,
        contentColor = contentColor
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
        )
    }
}
