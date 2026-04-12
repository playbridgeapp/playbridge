package com.playbridge.sender.browser

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.playbridge.sender.data.library.AddonRepository
import com.playbridge.sender.data.library.InstalledAddonEntity
import com.playbridge.sender.data.library.disabledFeatureSet
import com.playbridge.sender.data.library.isFeatureEnabled
import com.playbridge.sender.data.library.parsedCatalogEntries
import com.playbridge.sender.data.library.supportsResource
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * Screen to manage installed Stremio addons.
 *
 * Features:
 *  - Paste a manifest URL to install an addon
 *  - 3-dot (MoreVert) dropdown per addon: Copy URL, Open URL, Enable/Disable, Refresh, Delete
 *  - Long-press drag handle to reorder addons (order is persisted to DB)
 *  - Disabled addons shown with muted colours and a "Disabled" chip
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddonSettingsScreen(
    addonRepository: AddonRepository,
    installedAddons: List<InstalledAddonEntity>,
    onBack: () -> Unit,
    /** Called when the user taps "Open URL" so the parent can navigate to the browser. */
    onOpenUrl: (String) -> Unit = {}
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val keyboardController = LocalSoftwareKeyboardController.current

    var addonUrl by remember { mutableStateOf("") }
    var isInstalling by remember { mutableStateOf(false) }

    // ── Drag-to-reorder state ─────────────────────────────────────────────
    // Maintain a local snapshot so swaps are immediate in the UI.
    val localList = remember { mutableStateListOf<InstalledAddonEntity>() }
    LaunchedEffect(installedAddons) {
        // Re-sync from DB only when not mid-drag to prevent position resets.
        localList.clear()
        localList.addAll(installedAddons)
    }

    var draggingIndex by remember { mutableIntStateOf(-1) }
    var dragYOffset by remember { mutableFloatStateOf(0f) }
    // Approximate height of one card in px — set on first layout.
    var itemHeightPx by remember { mutableFloatStateOf(0f) }

    val listState = rememberLazyListState()

    // ── UI ────────────────────────────────────────────────────────────────
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
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {

            // ── Install card ─────────────────────────────────────────────
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
                            keyboardActions = KeyboardActions(onDone = { keyboardController?.hide() }),
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
                                        Toast.makeText(
                                            context,
                                            "Installed: ${result.name}",
                                            Toast.LENGTH_SHORT
                                        ).show()
                                    } else {
                                        Toast.makeText(
                                            context,
                                            "Failed to install addon",
                                            Toast.LENGTH_SHORT
                                        ).show()
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

            // ── Installed addons header ───────────────────────────────────
            if (localList.isNotEmpty()) {
                item {
                    Row(
                        modifier = Modifier.padding(top = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Installed (${localList.size})",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.weight(1f)
                        )
                        Text(
                            text = "Hold ≡ to reorder",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            // ── Addon cards ──────────────────────────────────────────────
            itemsIndexed(localList, key = { _, addon -> addon.manifestUrl }) { index, addon ->

                val isDraggingThis = draggingIndex == index
                val elevation by animateDpAsState(
                    targetValue = if (isDraggingThis) 8.dp else 0.dp,
                    label = "cardElevation"
                )

                AddonCard(
                    addon = addon,
                    isDragging = isDraggingThis,
                    modifier = Modifier
                        .zIndex(if (isDraggingThis) 1f else 0f)
                        .graphicsLayer {
                            // Follow the finger smoothly between snap-swaps.
                            // Use draggingIndex (the live value) rather than the captured
                            // `index` so bounds stay correct after a previous reorder.
                            translationY = if (isDraggingThis) {
                                val gapPx = itemHeightPx + 12.dp.toPx()
                                dragYOffset.coerceIn(
                                    -(draggingIndex * gapPx),
                                    ((localList.size - 1 - draggingIndex) * gapPx)
                                )
                            } else 0f
                            shadowElevation = elevation.toPx()
                        }
                        // Capture item height for swap math
                        .onSizeChangedOnce { size ->
                            if (size.height > 0 && itemHeightPx == 0f) {
                                itemHeightPx = size.height.toFloat()
                            }
                        },
                    onDragHandleInput = Modifier.pointerInput(addon.manifestUrl) {
                        detectDragGesturesAfterLongPress(
                            onDragStart = {
                                // Always resolve the current index from the live list —
                                // the captured `index` from itemsIndexed is stale after
                                // any previous reorder because pointerInput is not
                                // restarted when only the position changes.
                                draggingIndex = localList.indexOfFirst {
                                    it.manifestUrl == addon.manifestUrl
                                }.coerceAtLeast(0)
                                dragYOffset = 0f
                            },
                            onDrag = { change, dragAmount ->
                                change.consume()
                                dragYOffset += dragAmount.y
                                val gapPx = itemHeightPx + 12.dp.toPx() // card + spacing
                                val shift = (dragYOffset / gapPx).roundToInt()
                                val targetIndex = (draggingIndex + shift).coerceIn(0, localList.lastIndex)
                                if (targetIndex != draggingIndex) {
                                    val moved = localList.removeAt(draggingIndex)
                                    localList.add(targetIndex, moved)
                                    dragYOffset -= (targetIndex - draggingIndex) * gapPx
                                    draggingIndex = targetIndex
                                }
                            },
                            onDragEnd = {
                                draggingIndex = -1
                                dragYOffset = 0f
                                // Persist new order
                                val snapshot = localList.toList()
                                scope.launch { addonRepository.reorderAddons(snapshot) }
                            },
                            onDragCancel = {
                                draggingIndex = -1
                                dragYOffset = 0f
                            }
                        )
                    },
                    onCopyUrl = {
                        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        cm.setPrimaryClip(ClipData.newPlainText("Addon URL", addon.manifestUrl))
                        Toast.makeText(context, "URL copied", Toast.LENGTH_SHORT).show()
                    },
                    onOpenUrl = { onOpenUrl(addon.manifestUrl) },
                    onConfigure = { isEnabled, disabled ->
                        scope.launch { addonRepository.configureAddon(addon, isEnabled, disabled) }
                    },
                    onRefresh = {
                        scope.launch {
                            val updated = addonRepository.refreshAddon(addon)
                            val msg = if (updated != null) "Refreshed: ${updated.name}" else "Refresh failed"
                            Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                        }
                    },
                    onDelete = {
                        scope.launch {
                            addonRepository.removeAddon(addon)
                            Toast.makeText(context, "Removed: ${addon.name}", Toast.LENGTH_SHORT).show()
                        }
                    }
                )
            }

            // ── Empty state ──────────────────────────────────────────────
            if (localList.isEmpty()) {
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

// ══════════════════════════════════════════════════════════════════════════════
// Addon card
// ══════════════════════════════════════════════════════════════════════════════

@Composable
private fun AddonCard(
    addon: InstalledAddonEntity,
    isDragging: Boolean,
    modifier: Modifier = Modifier,
    onDragHandleInput: Modifier,
    onCopyUrl: () -> Unit,
    onOpenUrl: () -> Unit,
    /** Called when the user saves the configure dialog. */
    onConfigure: (isEnabled: Boolean, disabledFeatures: Set<String>) -> Unit,
    onRefresh: () -> Unit,
    onDelete: () -> Unit
) {
    var menuExpanded by remember { mutableStateOf(false) }
    var showConfigureDialog by remember { mutableStateOf(false) }
    var showDeleteDialog by remember { mutableStateOf(false) }

    val cardAlpha = if (addon.isEnabled) 1f else 0.55f

    Card(
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isDragging)
                MaterialTheme.colorScheme.surfaceContainerHighest
            else
                MaterialTheme.colorScheme.surfaceContainerLow
        ),
        modifier = modifier.graphicsLayer { alpha = cardAlpha }
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 4.dp, end = 4.dp, top = 12.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {

            // ── Drag handle ──────────────────────────────────────────────
            Icon(
                imageVector = Icons.Default.DragHandle,
                contentDescription = "Drag to reorder",
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                modifier = Modifier
                    .size(32.dp)
                    .padding(start = 4.dp)
                    .then(onDragHandleInput)   // long-press drag attached here
            )

            Spacer(Modifier.width(8.dp))

            // ── Info column ──────────────────────────────────────────────
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = addon.name,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    if (!addon.isEnabled) {
                        Spacer(Modifier.width(6.dp))
                        DisabledChip()
                    }
                }

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

            // ── 3-dot menu ───────────────────────────────────────────────
            Box {
                IconButton(onClick = { menuExpanded = true }) {
                    Icon(
                        Icons.Default.MoreVert,
                        contentDescription = "Addon options",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                DropdownMenu(
                    expanded = menuExpanded,
                    onDismissRequest = { menuExpanded = false }
                ) {
                    // Copy URL
                    DropdownMenuItem(
                        text = { Text("Copy URL") },
                        leadingIcon = { Icon(Icons.Default.ContentCopy, null) },
                        onClick = {
                            menuExpanded = false
                            onCopyUrl()
                        }
                    )

                    // Open in browser
                    DropdownMenuItem(
                        text = { Text("Open in Browser") },
                        leadingIcon = { Icon(Icons.Default.OpenInBrowser, null) },
                        onClick = {
                            menuExpanded = false
                            onOpenUrl()
                        }
                    )

                    HorizontalDivider()

                    // Configure features
                    DropdownMenuItem(
                        text = { Text("Configure") },
                        leadingIcon = { Icon(Icons.Default.Tune, null) },
                        onClick = {
                            menuExpanded = false
                            showConfigureDialog = true
                        }
                    )

                    // Refresh metadata
                    DropdownMenuItem(
                        text = { Text("Refresh") },
                        leadingIcon = { Icon(Icons.Default.Refresh, null) },
                        onClick = {
                            menuExpanded = false
                            onRefresh()
                        }
                    )

                    HorizontalDivider()

                    // Delete
                    DropdownMenuItem(
                        text = { Text("Delete", color = MaterialTheme.colorScheme.error) },
                        leadingIcon = {
                            Icon(
                                Icons.Default.Delete,
                                null,
                                tint = MaterialTheme.colorScheme.error
                            )
                        },
                        onClick = {
                            menuExpanded = false
                            showDeleteDialog = true
                        }
                    )
                }
            }
        }
    }

    // ── Configure dialog ──────────────────────────────────────────────────
    if (showConfigureDialog) {
        AddonConfigureDialog(
            addon = addon,
            onDismiss = { showConfigureDialog = false },
            onSave = { isEnabled, disabled ->
                showConfigureDialog = false
                onConfigure(isEnabled, disabled)
            }
        )
    }

    // ── Delete confirmation dialog ────────────────────────────────────────
    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            icon = { Icon(Icons.Default.Delete, null, tint = MaterialTheme.colorScheme.error) },
            title = { Text("Remove addon?") },
            text = { Text("\"${addon.name}\" will be removed. You can reinstall it later using its URL.") },
            confirmButton = {
                TextButton(
                    onClick = { showDeleteDialog = false; onDelete() },
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error
                    )
                ) { Text("Remove") }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) { Text("Cancel") }
            }
        )
    }
}

// ══════════════════════════════════════════════════════════════════════════════
// Capability badges
// ══════════════════════════════════════════════════════════════════════════════

@Composable
private fun AddonCapabilityBadges(addon: InstalledAddonEntity) {
    val resources = listOf("stream", "catalog", "meta", "subtitles")
    val supported = resources.filter { addon.supportsResource(it) }
    if (supported.isEmpty()) return

    val catalogCount = remember(addon.catalogsJson) { addon.parsedCatalogEntries().size }
    val disabledSet = remember(addon.disabledFeatures) { addon.disabledFeatureSet() }

    Spacer(Modifier.height(6.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        supported.forEach { cap ->
            val label = when (cap) {
                "stream"    -> "Streams"
                "catalog"   -> if (catalogCount > 0) "$catalogCount Catalogs" else "Catalogs"
                "meta"      -> "Meta"
                "subtitles" -> "Subtitles"
                else        -> cap.replaceFirstChar { it.uppercaseChar() }
            }
            val featureDisabled = !addon.isEnabled || cap in disabledSet
            CapabilityBadge(label = label, cap = cap, dimmed = featureDisabled)
        }
    }
}

@Composable
private fun CapabilityBadge(label: String, cap: String, dimmed: Boolean = false) {
    val alpha = if (dimmed) 0.35f else 1f
    val containerColor = when (cap) {
        "stream"    -> MaterialTheme.colorScheme.primaryContainer
        "catalog"   -> MaterialTheme.colorScheme.tertiaryContainer
        "meta"      -> MaterialTheme.colorScheme.secondaryContainer
        "subtitles" -> Color(0xFFFFE0B2)
        else        -> MaterialTheme.colorScheme.surfaceVariant
    }.copy(alpha = alpha)
    val contentColor = when (cap) {
        "stream"    -> MaterialTheme.colorScheme.onPrimaryContainer
        "catalog"   -> MaterialTheme.colorScheme.onTertiaryContainer
        "meta"      -> MaterialTheme.colorScheme.onSecondaryContainer
        "subtitles" -> Color(0xFFE65100)
        else        -> MaterialTheme.colorScheme.onSurfaceVariant
    }.copy(alpha = alpha)
    Surface(
        shape = MaterialTheme.shapes.extraSmall,
        color = containerColor,
        contentColor = contentColor
    ) {
        Text(
            text = if (dimmed) "$label (off)" else label,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
        )
    }
}

@Composable
private fun DisabledChip() {
    Surface(
        shape = MaterialTheme.shapes.extraSmall,
        color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.6f),
        contentColor = MaterialTheme.colorScheme.onErrorContainer
    ) {
        Text(
            text = "Disabled",
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
        )
    }
}

// ══════════════════════════════════════════════════════════════════════════════
// Configure dialog
// ══════════════════════════════════════════════════════════════════════════════

private val KNOWN_RESOURCES = listOf("stream", "catalog", "meta", "subtitles")

private fun resourceLabel(resource: String) = when (resource) {
    "stream"    -> "Streams"
    "catalog"   -> "Catalogs"
    "meta"      -> "Metadata"
    "subtitles" -> "Subtitles"
    else        -> resource.replaceFirstChar { it.uppercaseChar() }
}

private fun resourceDescription(resource: String) = when (resource) {
    "stream"    -> "Resolve playable video links for movies and episodes"
    "catalog"   -> "Provide browseable content rows in the Library"
    "meta"      -> "Supply titles, posters, and descriptions"
    "subtitles" -> "Offer subtitle tracks for playback"
    else        -> ""
}

/**
 * Dialog that lets the user toggle the master switch and individual feature flags
 * for a single installed addon.
 */
@Composable
private fun AddonConfigureDialog(
    addon: InstalledAddonEntity,
    onDismiss: () -> Unit,
    onSave: (isEnabled: Boolean, disabledFeatures: Set<String>) -> Unit
) {
    // Derive supported resources from the addon's declared resource list,
    // falling back to all known types for pre-Phase-1 addons with blank resources.
    val supportedResources = remember(addon.resources) {
        if (addon.resources.isBlank()) KNOWN_RESOURCES
        else {
            try {
                val declared = kotlinx.serialization.json.Json
                    .decodeFromString<List<String>>(addon.resources)
                KNOWN_RESOURCES.filter { it in declared }
            } catch (_: Exception) { KNOWN_RESOURCES }
        }
    }

    var masterEnabled by remember { mutableStateOf(addon.isEnabled) }
    // Track which features are currently DISABLED (immutable set swapped on each toggle)
    var disabledSet by remember { mutableStateOf(addon.disabledFeatureSet()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Column {
                Text(
                    text = "Configure",
                    style = MaterialTheme.typography.titleLarge
                )
                Text(
                    text = addon.name,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(0.dp)) {

                // ── Master switch ──────────────────────────────────────
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Addon enabled",
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.Medium
                        )
                        Text(
                            text = "Disable to pause all activity from this addon",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = masterEnabled,
                        onCheckedChange = { masterEnabled = it }
                    )
                }

                if (supportedResources.isNotEmpty()) {
                    HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))

                    Text(
                        text = "Features",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 4.dp)
                    )

                    // ── Per-feature rows ───────────────────────────────
                    supportedResources.forEach { resource ->
                        val isOn = resource !in disabledSet
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = resourceLabel(resource),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = if (masterEnabled)
                                        MaterialTheme.colorScheme.onSurface
                                    else
                                        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                                )
                                val desc = resourceDescription(resource)
                                if (desc.isNotBlank()) {
                                    Text(
                                        text = desc,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(
                                            alpha = if (masterEnabled) 1f else 0.38f
                                        )
                                    )
                                }
                            }
                            Switch(
                                checked = isOn,
                                onCheckedChange = { checked ->
                                    disabledSet = if (checked) disabledSet - resource
                                                  else disabledSet + resource
                                },
                                enabled = masterEnabled
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = { onSave(masterEnabled, disabledSet) }) {
                Text("Save")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

// ══════════════════════════════════════════════════════════════════════════════
// Utility
// ══════════════════════════════════════════════════════════════════════════════

/**
 * Captures the size of the composable the first time it is measured and calls
 * [onSize]. Subsequent layout changes are ignored to keep [itemHeightPx] stable.
 */
private fun Modifier.onSizeChangedOnce(onSize: (IntSize) -> Unit): Modifier =
    this.then(
        Modifier.onGloballyPositioned { coords ->
            onSize(IntSize(coords.size.width, coords.size.height))
        }
    )
