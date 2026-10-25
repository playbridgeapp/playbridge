package com.playbridge.sender.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cast
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SettingsRemote
import androidx.compose.material.icons.filled.Smartphone
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.Canvas
import androidx.compose.ui.graphics.drawscope.Stroke
import coil.compose.AsyncImage
import com.playbridge.sender.R
import com.playbridge.sender.browser.BridgedApp
import com.playbridge.sender.browser.Screen
import com.playbridge.sender.browser.UblockSetupStatus
import com.playbridge.sender.cast.PlayOnStatus
import com.playbridge.sender.model.CastProtocol

private val ChipGreen = Color(0xFF46D483)
private val ChipAmber = Color(0xFFFFB74D)
private const val PREFS = "browser_prefs"
private const val ORDER_KEY = "dashboard_tile_order"
private const val HIDDEN_KEY = "dashboard_hidden_tiles"

@Composable
internal fun DashboardScreen(
    currentScreen: Screen,
    playOnStatus: PlayOnStatus,
    tileStates: DashboardTileStates = DashboardTileStates(),
    onNavigate: (Screen) -> Unit,
    onExit: () -> Unit = {},
    /** Global Settings entry (top bar gear). Defaults to [Screen.Settings]. */
    onSettings: () -> Unit = { onNavigate(Screen.Settings) },
    onPlayOn: () -> Unit = {},
    onRemote: () -> Unit = { onNavigate(Screen.Remote) },
    /** Switch where videos play to this phone (offered when the chosen TV drops). */
    onUseThisPhone: () -> Unit = {},
    bridgedApps: List<BridgedApp> = emptyList(),
    onOpenBridgedApp: (BridgedApp) -> Unit = {},
    onRemoveBridgedApp: (BridgedApp) -> Unit = {},
    onEditBridgedApp: (BridgedApp, String, String) -> Boolean = { _, _, _ -> false },
    ublockSetupStatus: UblockSetupStatus = UblockSetupStatus.CHECKING,
    onCheckUblock: () -> Unit = {},
    onInstallUblock: () -> Unit = {},
    onCancelUblock: () -> Unit = {},
    canFinishUblockSetup: () -> Boolean = { true },
    onOnboardingVisible: () -> Unit = {},
) {
    var visible by remember { mutableStateOf(false) }
    var showExitConfirm by remember { mutableStateOf(false) }
    var appToInspectOrigin by rememberSaveable { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) { visible = true }

    // One-time onboarding tour (first launch lands here; see BrowserActivity).
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE) }
    var showOnboarding by rememberSaveable {
        mutableStateOf(!prefs.getBoolean("dashboard_onboarding_seen", false))
    }

    var showCustomize by remember { mutableStateOf(false) }
    var tileToMove by remember { mutableStateOf<String?>(null) }
    var savedOrder by remember { mutableStateOf(readIds(prefs.getString(ORDER_KEY, "[]"))) }
    var hidden by remember { mutableStateOf(readIds(prefs.getString(HIDDEN_KEY, "[]")).toSet()) }
    val availableItems = dashboardItems(tileStates, bridgedApps)
    val availableIds = availableItems.map { it.id }
    val orderedIds = remember(savedOrder, availableIds) {
        if (!prefs.getBoolean("migrated_dashboard_tile_order_v1", false)) {
            val migrated = DashboardTileOrder.migrateOrder(savedOrder, availableIds)
            prefs.edit()
                .putString(ORDER_KEY, writeIds(migrated))
                .putBoolean("migrated_dashboard_tile_order_v1", true)
                .apply()
            savedOrder = migrated
            migrated
        } else {
            DashboardTileOrder.reconcile(savedOrder, availableIds)
        }
    }
    val itemsById = availableItems.associateBy { it.id }
    val orderedItems = orderedIds.mapNotNull { itemsById[it] }
    val visibleItems = DashboardTileOrder.visible(orderedIds, hidden).mapNotNull { itemsById[it] }
    fun saveOrder(ids: List<String>) {
        savedOrder = ids
        prefs.edit()
            .putString(ORDER_KEY, writeIds(ids))
            .putBoolean("migrated_dashboard_tile_order_v1", true)
            .apply()
    }
    fun setHidden(id: String, hide: Boolean) {
        if (id in DashboardTileOrder.ALWAYS_SHOWN) return
        hidden = if (hide) hidden + id else hidden - id
        prefs.edit().putString(HIDDEN_KEY, writeIds(hidden.toList())).apply()
    }
    LaunchedEffect(availableIds) { saveOrder(orderedIds) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                // Keep the gesture inset outside the scroll viewport so partially
                // visible tiles cannot draw against the system handle.
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            DashboardTopBar(
                onRemote = onRemote,
                onSettings = onSettings,
                onSetupGuide = { showOnboarding = true },
                onExit = { showExitConfirm = true },
            )
            DashboardDeviceChip(status = playOnStatus, onClick = onPlayOn)
            // A dropped TV stays chosen; switching to this phone is the user's call.
            if (playOnStatus.state == PlayOnStatus.State.RECONNECTING || playOnStatus.state == PlayOnStatus.State.OFFLINE) {
                LostTvNotice(tvName = playOnStatus.deviceName, onUseThisPhone = onUseThisPhone)
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "WATCH",
                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold, letterSpacing = 0.8.sp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = { showCustomize = true }) { Text("Customize") }
            }
            DashboardTileGrid(
                items = visibleItems,
                currentScreen = currentScreen,
                visible = visible,
                onOpen = { item ->
                    when {
                        item.app != null -> onOpenBridgedApp(item.app)
                        item.screen != null -> onNavigate(item.screen)
                    }
                },
                onLongPress = { item ->
                    if (item.app != null) appToInspectOrigin = item.app.origin else showCustomize = true
                },
            )
        }

        if (showCustomize) {
            DashboardReorderDialog(
                tiles = orderedItems.map {
                    DashboardReorderTile(
                        id = it.id, title = it.title, icon = it.icon, gradient = it.gradientColors,
                        iconUrl = it.app?.iconUrl, subtitle = if (it.app != null) "Web app" else null,
                    )
                },
                hidden = hidden,
                onReorder = { ids -> saveOrder(DashboardTileOrder.reconcile(ids, availableIds)) },
                onToggleHidden = ::setHidden,
                onReset = {
                    saveOrder(DashboardTileOrder.migrateOrder(emptyList(), availableIds))
                    hidden = emptySet()
                    prefs.edit().remove(HIDDEN_KEY).apply()
                },
                onChoosePosition = { id -> tileToMove = id },
                onDismiss = { showCustomize = false },
            )
        }
        tileToMove?.let { id ->
            val tile = itemsById[id]
            if (tile != null) {
                AlertDialog(
                    onDismissRequest = { tileToMove = null },
                    title = { Text("Move ${tile.title}") },
                    text = {
                        LazyColumn(modifier = Modifier.heightIn(max = 400.dp)) {
                            itemsIndexed(orderedItems, key = { _, item -> item.id }) { index, item ->
                                TextButton(
                                    onClick = {
                                        saveOrder(DashboardTileOrder.move(orderedIds, id, index))
                                        tileToMove = null
                                    },
                                    modifier = Modifier.fillMaxWidth(),
                                ) {
                                    Text("${index + 1} · ${item.title}", modifier = Modifier.fillMaxWidth())
                                }
                            }
                        }
                    },
                    confirmButton = {},
                    dismissButton = { TextButton(onClick = { tileToMove = null }) { Text("Cancel") } },
                )
            }
        }

        // First-launch full-window setup. Replay does not reset saved preferences or tile order.
        if (showOnboarding) {
            DashboardOnboardingOverlay(
                onDone = {
                    if (canFinishUblockSetup()) {
                        prefs.edit().putBoolean("dashboard_onboarding_seen", true).apply()
                        showOnboarding = false
                    } else {
                        onCancelUblock()
                    }
                },
                ublockStatus = ublockSetupStatus,
                onCheckUblock = onCheckUblock,
                onInstallUblock = onInstallUblock,
                onCancelUblock = onCancelUblock,
                onGuideShown = onOnboardingVisible,
            )
        }

        bridgedApps.firstOrNull { it.origin == appToInspectOrigin }?.let { app ->
            BridgedAppInfoDialog(
                app = app,
                onSave = { name, homeUrl -> onEditBridgedApp(app, name, homeUrl) },
                onRemove = { onRemoveBridgedApp(app); appToInspectOrigin = null },
                onDismiss = { appToInspectOrigin = null },
            )
        }
        // Confirm before fully quitting — this is a hard exit, not a background close.
        if (showExitConfirm) {
            AlertDialog(
                onDismissRequest = { showExitConfirm = false },
                title = { Text("Exit PlayBridge?") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("This fully quits the app. Anything that relies on PlayBridge will stop:")
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            listOf("Phone files on the TV", "DLNA playback", "Episodes queued from this phone").forEach {
                                Text("•  $it", color = MaterialTheme.colorScheme.onSurface)
                            }
                        }
                    }
                },
                confirmButton = {
                    Button(
                        onClick = { showExitConfirm = false; onExit() },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.error,
                            contentColor = MaterialTheme.colorScheme.onError,
                        ),
                    ) { Text("Exit") }
                },
                dismissButton = {
                    TextButton(onClick = { showExitConfirm = false }) { Text("Cancel") }
                },
            )
        }
    }
}

@Composable
private fun LostTvNotice(tvName: String, onUseThisPhone: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.inverseSurface,
        modifier = Modifier.fillMaxWidth().semantics(mergeDescendants = true) {},
    ) {
        Row(
            modifier = Modifier.padding(start = 14.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Default.Warning, contentDescription = null,
                tint = MaterialTheme.colorScheme.inverseOnSurface, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(10.dp))
            Text(
                "Lost $tvName",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.inverseOnSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onUseThisPhone) {
                Text("Use this phone", color = MaterialTheme.colorScheme.inversePrimary)
            }
        }
    }
}

private fun readIds(json: String?): List<String> = runCatching {
    val array = org.json.JSONArray(json ?: "[]")
    (0 until array.length()).map { array.getString(it) }
}.getOrDefault(emptyList())

private fun writeIds(ids: List<String>): String = org.json.JSONArray(ids).toString()

@Composable
private fun DashboardTopBar(
    onRemote: () -> Unit,
    onSettings: () -> Unit,
    onSetupGuide: () -> Unit,
    onExit: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier.fillMaxWidth().height(52.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Image(
            painter = painterResource(id = R.drawable.ic_playbridge_logo),
            contentDescription = null,
            modifier = Modifier.size(30.dp).clip(CircleShape),
        )
        Spacer(Modifier.width(10.dp))
        Text(
            "PlayBridge",
            style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold, letterSpacing = (-0.2).sp),
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        // Always shown: the Remote picks its own TV, even when videos play on this phone.
        IconButton(onClick = onRemote) {
            Icon(Icons.Default.SettingsRemote, contentDescription = "Open remote")
        }
        IconButton(onClick = onSettings) {
            Icon(Icons.Default.Settings, contentDescription = "Settings")
        }
        Box {
            IconButton(onClick = { menuOpen = true }) {
                Icon(Icons.Default.MoreVert, contentDescription = "More")
            }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.onboarding_open_guide)) },
                    leadingIcon = { Icon(Icons.Default.Info, contentDescription = null) },
                    onClick = { menuOpen = false; onSetupGuide() },
                )
                HorizontalDivider()
                DropdownMenuItem(
                    text = { Text("Exit PlayBridge", color = MaterialTheme.colorScheme.error) },
                    leadingIcon = {
                        Icon(Icons.Default.PowerSettingsNew, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                    },
                    onClick = { menuOpen = false; onExit() },
                )
            }
        }
    }
}

/** Where videos play: a chosen TV or this phone. Opens the Play on sheet. */
@Composable
internal fun DashboardDeviceChip(status: PlayOnStatus, onClick: () -> Unit) {
    val accent = when (status.state) {
        PlayOnStatus.State.CONNECTED -> if (status.unencrypted) ChipAmber else ChipGreen
        PlayOnStatus.State.CONNECTING, PlayOnStatus.State.RECONNECTING -> ChipAmber
        PlayOnStatus.State.OFFLINE -> MaterialTheme.colorScheme.error
        PlayOnStatus.State.THIS_PHONE -> null
    }
    val icon = when {
        !status.tvChosen -> Icons.Default.Smartphone
        status.receiver == null || status.receiver == CastProtocol.PLAYBRIDGE.displayName -> Icons.Default.Tv
        else -> Icons.Default.Cast
    }
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(30.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        border = BorderStroke(1.dp, accent?.copy(alpha = 0.35f) ?: MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier
            .fillMaxWidth()
            .height(60.dp)
            .semantics { contentDescription = "${status.deviceName}, ${status.statusLine}. Change where to play" },
    ) {
        Row(
            modifier = Modifier.padding(start = 10.dp, end = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(accent?.copy(alpha = 0.15f) ?: MaterialTheme.colorScheme.surfaceContainerHighest),
                contentAlignment = Alignment.Center,
            ) {
                Icon(icon, contentDescription = null, tint = accent ?: MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp))
                if (accent != null) Box(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .offset(x = (-3).dp, y = (-3).dp)
                        .size(10.dp)
                        .clip(CircleShape)
                        .background(accent),
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(status.deviceName, style = MaterialTheme.typography.titleMedium, maxLines = 1,
                    overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurface)
                Text(status.statusLine, style = MaterialTheme.typography.bodyMedium, maxLines = 1,
                    overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Icon(Icons.Default.KeyboardArrowDown, contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
        }
    }
}

/** Two large tiles, then rows of three; the whole Dashboard scrolls instead of paging. */
@Composable
private fun DashboardTileGrid(
    items: List<DashboardItem>,
    currentScreen: Screen,
    visible: Boolean,
    onOpen: (DashboardItem) -> Unit,
    onLongPress: (DashboardItem) -> Unit,
) {
    val rows = listOf(items.take(2)) + items.drop(2).chunked(3)
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        rows.forEachIndexed { rowIndex, rowItems ->
            if (rowItems.isEmpty()) return@forEachIndexed
            val columns = if (rowIndex == 0) 2 else 3
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                rowItems.forEach { item ->
                    key(item.id) {
                        val badge = when {
                            item.live -> "LIVE"
                            // The screen the Dashboard was opened from; tapping returns there.
                            item.app == null && !item.isAddPlaceholder && item.screen == currentScreen -> "OPEN"
                            else -> null
                        }
                        DashboardCard(
                            item = item,
                            badge = badge,
                            visible = visible,
                            tall = rowIndex == 0,
                            modifier = Modifier.weight(1f),
                            onClick = { onOpen(item) },
                            onLongClick = { onLongPress(item) },
                            onLongClickLabel = if (item.app != null) "App info for ${item.title}" else "Customize home",
                        )
                    }
                }
                repeat(columns - rowItems.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DashboardCard(
    item: DashboardItem,
    badge: String?,
    visible: Boolean,
    tall: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onLongClickLabel: String,
) {
    val cardAlpha by animateFloatAsState(if (visible) 1f else 0f, tween(400), label = "cardAlpha")
    val cardTranslateY by animateFloatAsState(if (visible) 0f else 60f, tween(500), label = "cardTranslateY")
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val pressScale by animateFloatAsState(if (isPressed) 0.95f else 1f, tween(150), label = "pressScale")
    val shape = RoundedCornerShape(20.dp)
    val outline = MaterialTheme.colorScheme.outline

    Box(
        modifier = modifier
            .height(if (tall) 140.dp else 112.dp)
            .graphicsLayer {
                alpha = cardAlpha
                translationY = cardTranslateY
                scaleX = pressScale
                scaleY = pressScale
            }
            .clip(shape)
            .then(
                if (item.isAddPlaceholder) Modifier
                else Modifier
                    .background(Brush.linearGradient(item.gradientColors))
                    .border(1.dp, Color.White.copy(alpha = 0.12f), shape)
            )
            .combinedClickable(
                interactionSource = interactionSource,
                indication = ripple(bounded = true),
                onClick = onClick,
                onLongClick = onLongClick,
                onLongClickLabel = onLongClickLabel,
            ),
    ) {
        if (item.isAddPlaceholder) {
            Canvas(Modifier.matchParentSize()) {
                drawRoundRect(
                    color = outline,
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(20.dp.toPx()),
                    style = Stroke(width = 1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(12f, 10f))),
                )
            }
        } else {
            // Glass sheen and the two decorative circles from the original tile design.
            Box(
                Modifier.matchParentSize().background(
                    Brush.radialGradient(
                        colors = listOf(Color.White.copy(alpha = 0.16f), Color.Transparent),
                        center = Offset(0f, 0f),
                        radius = 350f,
                    )
                )
            )
            Box(
                Modifier.size(80.dp).align(Alignment.TopEnd).offset(x = 15.dp, y = (-15).dp)
                    .clip(CircleShape).background(Color.White.copy(alpha = 0.08f))
            )
            Box(
                Modifier.size(40.dp).align(Alignment.BottomStart).offset(x = (-10).dp, y = 30.dp)
                    .clip(CircleShape).background(Color.White.copy(alpha = 0.05f))
            )
        }

        val textColor = if (item.isAddPlaceholder) MaterialTheme.colorScheme.onSurface else Color.White
        val subColor = if (item.isAddPlaceholder) MaterialTheme.colorScheme.onSurfaceVariant else Color.White.copy(alpha = 0.86f)
        Column(
            modifier = Modifier.fillMaxSize().padding(if (tall) 16.dp else 12.dp),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(
                        if (item.isAddPlaceholder) MaterialTheme.colorScheme.surfaceContainerHigh
                        else Color.White.copy(alpha = 0.2f)
                    ),
                contentAlignment = Alignment.Center,
            ) {
                val iconUrl = item.app?.iconUrl
                if (iconUrl != null) {
                    AsyncImage(model = iconUrl, contentDescription = null, modifier = Modifier.fillMaxSize())
                } else {
                    Icon(item.icon, contentDescription = null, tint = textColor, modifier = Modifier.size(22.dp))
                }
            }
            Column {
                Text(
                    text = item.title,
                    style = (if (tall) MaterialTheme.typography.titleMedium else MaterialTheme.typography.titleSmall)
                        .copy(fontWeight = FontWeight.SemiBold, fontSize = if (tall) 17.sp else 14.sp),
                    color = textColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = item.subtitle,
                    style = MaterialTheme.typography.bodySmall.copy(fontSize = if (tall) 13.sp else 12.sp),
                    color = subColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        if (badge != null) {
            Row(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(12.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(Color.Black.copy(alpha = 0.28f))
                    .padding(horizontal = 8.dp, vertical = 3.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(6.dp).clip(CircleShape).background(Color.White))
                Spacer(Modifier.width(5.dp))
                Text(
                    badge,
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.4.sp),
                    color = Color.White,
                )
            }
        }
    }
}
