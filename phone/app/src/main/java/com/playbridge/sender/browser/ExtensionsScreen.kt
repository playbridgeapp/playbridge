package com.playbridge.sender.browser

import android.util.Log
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import mozilla.components.feature.addons.Addon
import org.mozilla.geckoview.WebExtension

private const val TAG = "ExtensionsScreen"

/**
 * Extension management screen showing installed addons.
 * Uses WebExtensionController.list() directly to avoid network calls.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExtensionsScreen(
    session: mozilla.components.concept.engine.EngineSession,
    onBack: () -> Unit = {},
    onAddExtension: () -> Unit = {}
) {
    // Use GeckoView's WebExtension directly for installed extensions
    var installedExtensions by remember { mutableStateOf<List<WebExtension>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var showInstallDialog by remember { mutableStateOf(false) }
    var availableAddons by remember { mutableStateOf<List<Addon>>(emptyList()) }
    val scope = rememberCoroutineScope()
    var errorMessage by remember { mutableStateOf<String?>(null) }
    
    // Load installed extensions directly from GeckoRuntime (no network needed)
    LaunchedEffect(Unit) {
        try {
            Log.d(TAG, "Loading installed extensions from GeckoRuntime...")
            withContext(Dispatchers.Main) {
                Components.runtime.webExtensionController.list().then({ extensions ->
                    Log.d(TAG, "Found ${extensions?.size ?: 0} installed extensions")
                    installedExtensions = extensions?.toList() ?: emptyList()
                    isLoading = false
                    org.mozilla.geckoview.GeckoResult.fromValue(null)
                }, { error ->
                    Log.e(TAG, "Error loading extensions", error)
                    errorMessage = "Failed to load extensions: ${error?.message}"
                    isLoading = false
                    org.mozilla.geckoview.GeckoResult.fromValue(null)
                })
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load extensions", e)
            errorMessage = "Failed to load extensions: ${e.message}"
            isLoading = false
        }
    }
    
    // Timeout for loading state
    LaunchedEffect(isLoading) {
        if (isLoading) {
            kotlinx.coroutines.delay(5000)
            if (isLoading) {
                isLoading = false
            }
        }
    }
    
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        // Header with add button
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Installed Extensions",
                style = MaterialTheme.typography.titleMedium
            )
            
            FilledTonalButton(
                onClick = {
                    onAddExtension()
                }
            ) {
                Icon(Icons.Default.Add, "Add", modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(4.dp))
                Text("Add Extension")
            }
        }
        
        Spacer(modifier = Modifier.height(16.dp))
        
        // Error message
        errorMessage?.let { msg ->
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer
                ),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Default.Warning,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        msg,
                        color = MaterialTheme.colorScheme.onErrorContainer
                    )
                }
            }
            Spacer(modifier = Modifier.height(16.dp))
        }
        
        when {
            isLoading -> {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator()
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            "Loading extensions...",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
            installedExtensions.isEmpty() -> {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            Icons.Default.Settings,
                            contentDescription = null,
                            modifier = Modifier.size(64.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            "No extensions installed",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            "Tap 'Add Extension' to browse available addons",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
            else -> {
                val context = androidx.compose.ui.platform.LocalContext.current
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // Show all extensions (including built-in like video detector)
                    items(installedExtensions) { extension ->
                        ExtensionCard(
                            extension = extension,
                            onUninstall = {
                                val extensionName = extension.metaData?.name ?: extension.id
                                scope.launch {
                                    try {
                                        Components.runtime.webExtensionController.uninstall(extension).then({ _ ->
                                            android.widget.Toast.makeText(
                                                context,
                                                "$extensionName uninstalled",
                                                android.widget.Toast.LENGTH_SHORT
                                            ).show()
                                            // Refresh list
                                            Components.runtime.webExtensionController.list().then({ exts ->
                                                installedExtensions = exts?.toList() ?: emptyList()
                                                org.mozilla.geckoview.GeckoResult.fromValue(null)
                                            }, { _ ->
                                                org.mozilla.geckoview.GeckoResult.fromValue(null)
                                            })
                                        }, { error ->
                                            errorMessage = "Failed to uninstall: ${error?.message}"
                                            org.mozilla.geckoview.GeckoResult.fromValue(null)
                                        })
                                    } catch (e: Exception) {
                                        errorMessage = "Failed to uninstall: ${e.message}"
                                    }
                                }
                            }
                        )
                    }
                }
            }
        }
    }
    
    // Install addon dialog
    if (showInstallDialog) {
        AddonInstallDialog(
            availableAddons = availableAddons,
            onInstall = { addon ->
                scope.launch {
                    try {
                        Log.d(TAG, "Installing addon: ${addon.id} from ${addon.downloadUrl}")
                        Components.addonManager.installAddon(
                            url = addon.downloadUrl,
                            onSuccess = {
                                Log.d(TAG, "Addon installed successfully")
                                // Refresh list
                                Components.runtime.webExtensionController.list().accept { exts ->
                                    installedExtensions = exts?.toList() ?: emptyList()
                                }
                            },
                            onError = { e ->
                                Log.e(TAG, "Addon install error", e)
                                errorMessage = e.message
                            }
                        )
                        showInstallDialog = false
                    } catch (e: Exception) {
                        Log.e(TAG, "Install failed", e)
                        errorMessage = "Install failed: ${e.message}"
                    }
                }
            },
            onDismiss = { showInstallDialog = false }
        )
    }
}

@Composable
private fun ExtensionCard(
    extension: WebExtension,
    onUninstall: () -> Unit
) {
    var showMenu by remember { mutableStateOf(false) }
    val isEnabled = extension.metaData?.enabled ?: true
    
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Extension icon
            var iconBitmap by remember { mutableStateOf<android.graphics.Bitmap?>(null) }
            
            LaunchedEffect(extension) {
                extension.metaData?.let { meta ->
                    // Load icon with desired size (e.g., 128px)
                    // The icon field is of type org.mozilla.geckoview.Image
                    try {
                        // Reflectively check if icon field exists to avoid build errors if I'm wrong about exact field name
                        // Based on javap output: public final org.mozilla.geckoview.Image icon;
                        val iconField = meta.javaClass.getField("icon")
                        val image = iconField.get(meta) as? org.mozilla.geckoview.Image
                        
                        image?.getBitmap(128)?.then({ bitmap ->
                            iconBitmap = bitmap
                            org.mozilla.geckoview.GeckoResult.fromValue(null)
                        }, { error ->
                            Log.e("ExtensionsScreen", "Failed to load icon", error)
                            org.mozilla.geckoview.GeckoResult.fromValue(null)
                        })
                    } catch (e: Exception) {
                        Log.e("ExtensionsScreen", "Error access icon field", e)
                    }
                }
            }
            
            Surface(
                modifier = Modifier
                    .size(48.dp)
                    .clip(androidx.compose.foundation.shape.RoundedCornerShape(8.dp)),
                color = MaterialTheme.colorScheme.surfaceVariant
            ) {
                Box(contentAlignment = Alignment.Center) {
                    if (iconBitmap != null) {
                        androidx.compose.foundation.Image(
                            bitmap = iconBitmap!!.asImageBitmap(),
                            contentDescription = null,
                            modifier = Modifier.fillMaxSize(),
                            contentScale = androidx.compose.ui.layout.ContentScale.Crop
                        )
                    } else {
                        Icon(
                            Icons.Default.Extension,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
            
            Spacer(modifier = Modifier.width(16.dp))
            
            // Extension info
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = extension.metaData?.name ?: extension.id,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = extension.metaData?.version ?: "Unknown version",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                // Show enabled status
                Text(
                    text = if (isEnabled) "Enabled" else "Disabled",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (isEnabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                )
            }
            
            // Uninstall button
            IconButton(onClick = onUninstall) {
                Icon(Icons.Default.Delete, "Uninstall", tint = MaterialTheme.colorScheme.error)
            }
        }
    }
}

