package com.playbridge.sender.ui

import com.playbridge.sender.browser.Screen
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.LibraryBooks
import androidx.compose.material.icons.automirrored.filled.PlaylistPlay
import androidx.compose.material.icons.automirrored.filled.ScreenShare
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.LiveTv
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.text.style.TextOverflow
import com.playbridge.sender.R
import com.playbridge.sender.browser.BridgedApp
import com.playbridge.sender.browser.UblockSetupStatus
import coil.compose.AsyncImage
import kotlinx.coroutines.launch

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun DashboardScreen(
    pagerState: PagerState,
    currentScreen: Screen,
    isConnected: Boolean,
    isSecure: Boolean,
    connectedDeviceName: String?,
    onNavigate: (Screen) -> Unit,
    onExit: () -> Unit = {},
    // Close the Dashboard (top-left X) → return to the screen it was opened from.
    // Defaults to the active main screen to preserve the old behavior.
    onClose: () -> Unit = { onNavigate(currentScreen) },
    /** Global Settings entry (top-right gear). Defaults to [Screen.Settings]. */
    onSettings: () -> Unit = { onNavigate(Screen.Settings) },
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
    // ── Entrance animations ─────────────────────────────────────────────────
    var visible by remember { mutableStateOf(false) }
    var showExitConfirm by remember { mutableStateOf(false) }
    var appToInspectOrigin by rememberSaveable { mutableStateOf<String?>(null) }
    val homeScrollState = rememberScrollState()
    val pagerScope = rememberCoroutineScope()
    val selectPage: (Int) -> Unit = { page ->
        pagerScope.launch { pagerState.animateScrollToPage(page) }
    }
    BackHandler(enabled = pagerState.currentPage > 0) { selectPage(0) }
    LaunchedEffect(Unit) { visible = true }

    // One-time onboarding tour (first launch lands here; see BrowserActivity).
    val onboardingContext = androidx.compose.ui.platform.LocalContext.current
    val onboardingPrefs = remember {
        onboardingContext.getSharedPreferences("browser_prefs", android.content.Context.MODE_PRIVATE)
    }
    var showOnboarding by rememberSaveable {
        mutableStateOf(!onboardingPrefs.getBoolean("dashboard_onboarding_seen", false))
    }

    var showReorder by remember { mutableStateOf(false) }
    var tileToMove by remember { mutableStateOf<String?>(null) }
    var savedOrder by remember {
        mutableStateOf(runCatching {
            val array = org.json.JSONArray(onboardingPrefs.getString("dashboard_tile_order", "[]"))
            (0 until array.length()).map { array.getString(it) }
        }.getOrDefault(emptyList()))
    }
    val builtInItems = listOf(
        DashboardItem(
            icon = Icons.Default.Language,
            title = "Browser",
            subtitle = "Browse the web",
            screen = Screen.Browser,
            gradientColors = listOf(Color(0xFF1565C0), Color(0xFF1E88E5))
        ),
        DashboardItem(
            icon = Icons.AutoMirrored.Filled.LibraryBooks,
            title = "Library (legacy)",
            subtitle = "Deprecated · use the new Library",
            screen = Screen.Library,
            gradientColors = listOf(Color(0xFF6A1B9A), Color(0xFF8E24AA))
        ),
        DashboardItem(
            icon = Icons.Default.Tv,
            title = "Connection",
            subtitle = if (isConnected) "Connected" else "Not connected",
            screen = Screen.Connection,
            gradientColors = if (isConnected)
                listOf(Color(0xFF2E7D32), Color(0xFF43A047))
            else
                listOf(Color(0xFF424242), Color(0xFF616161))
        ),
        DashboardItem(
            icon = Icons.AutoMirrored.Filled.ScreenShare,
            title = "Screen Mirror",
            subtitle = "Share your screen",
            screen = Screen.ScreenMirror,
            gradientColors = listOf(Color(0xFF00695C), Color(0xFF00897B))
        ),
        DashboardItem(
            icon = Icons.Default.Folder,
            title = "Phone Files",
            subtitle = "Cast videos & audio",
            screen = Screen.PhoneFiles,
            gradientColors = listOf(Color(0xFF4527A0), Color(0xFF5E35B1))
        ),
        DashboardItem(
            icon = Icons.Default.Cloud,
            title = "Debrid",
            subtitle = "Cloud torrents",
            screen = Screen.DebridLibrary,
            gradientColors = listOf(Color(0xFF00838F), Color(0xFF00ACC1))
        ),
        DashboardItem(
            icon = Icons.Default.LiveTv,
            title = "IPTV",
            subtitle = "Live channels",
            screen = Screen.Iptv,
            gradientColors = listOf(Color(0xFF00695C), Color(0xFF00897B))
        ),
        DashboardItem(
            icon = Icons.AutoMirrored.Filled.PlaylistPlay,
            title = "Collections",
            subtitle = "Your playlists",
            screen = Screen.Collections,
            gradientColors = listOf(Color(0xFFAD1457), Color(0xFFD81B60))
        ),
    ).filter {
        // Debrid is FOSS-only; the Play flavor hides the tile entirely.
        com.playbridge.sender.FlavorConfig.DEBRID_SUPPORTED || it.screen != Screen.DebridLibrary
    }

    val availableItems = builtInItems + DashboardItem(
        icon = Icons.Default.History,
        title = "Cast History",
        subtitle = "Recent casts",
        screen = Screen.CastHistory,
        gradientColors = listOf(Color(0xFFE65100), Color(0xFFFB8C00)),
    ) + if (bridgedApps.isEmpty()) {
        listOf(DashboardItem(
            icon = Icons.Default.Apps,
            title = "Bridged Apps",
            subtitle = "Open Browser to add",
            screen = Screen.Browser,
            gradientColors = listOf(Color(0xFF00695C), Color(0xFF00897B)),
            id = "bridged-apps",
        ))
    } else {
        bridgedApps.map { app ->
            DashboardItem(
                icon = Icons.Default.Apps,
                title = app.name,
                subtitle = "Bridged App",
                screen = Screen.Browser,
                gradientColors = listOf(Color(0xFF00695C), Color(0xFF00897B)),
                id = "app:${app.origin}",
                app = app,
            )
        }
    }
    val availableIds = availableItems.map { it.id }
    val orderedIds = remember(savedOrder, availableIds) {
        if (!onboardingPrefs.getBoolean("migrated_dashboard_tile_order_v1", false)) {
            val migrated = DashboardTileOrder.migrateOrder(savedOrder, availableIds)
            onboardingPrefs.edit()
                .putString("dashboard_tile_order", org.json.JSONArray(migrated).toString())
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
    val pages = orderedItems.chunked(DashboardTileOrder.TILES_PER_PAGE)
    fun saveOrder(ids: List<String>) {
        savedOrder = ids
        onboardingPrefs.edit()
            .putString("dashboard_tile_order", org.json.JSONArray(ids).toString())
            .putBoolean("migrated_dashboard_tile_order_v1", true)
            .apply()
    }
    fun moveTile(id: String, position: Int) {
        saveOrder(DashboardTileOrder.move(orderedIds, id, position))
    }
    LaunchedEffect(availableIds) { saveOrder(orderedIds) }
    LaunchedEffect(pages.size) {
        if (pagerState.currentPage >= pages.size) pagerState.scrollToPage(pages.lastIndex.coerceAtLeast(0))
    }

    val logoScale by animateFloatAsState(
        targetValue = if (visible) 1f else 0.3f,
        animationSpec = spring(dampingRatio = 0.6f, stiffness = 300f),
        label = "logoScale"
    )
    val logoAlpha by animateFloatAsState(
        targetValue = if (visible) 1f else 0f,
        animationSpec = tween(400),
        label = "logoAlpha"
    )

    val surfaceColor = MaterialTheme.colorScheme.surface
    val primaryColor = MaterialTheme.colorScheme.primary
    val secondaryColor = MaterialTheme.colorScheme.secondary

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(surfaceColor)
    ) {
        // ── Static Ambient Mesh Background ───────────────────────────────────
        // Perf: was two infinite angle clocks redrawing a full-screen Canvas + new
        // radial-gradient Brushes every frame. Static blobs cost one composition.
        val staticBlob1 = remember(primaryColor) {
            Brush.radialGradient(
                colors = listOf(
                    primaryColor.copy(alpha = 0.12f),
                    primaryColor.copy(alpha = 0.04f),
                    Color.Transparent
                ),
            )
        }
        val staticBlob2 = remember(secondaryColor) {
            Brush.radialGradient(
                colors = listOf(
                    secondaryColor.copy(alpha = 0.10f),
                    secondaryColor.copy(alpha = 0.03f),
                    Color.Transparent
                ),
            )
        }

        Canvas(modifier = Modifier.fillMaxSize()) {
            val width = size.width
            val height = size.height

            // Drift Blob 1 (Top-Right-ish)
            val dx1 = width * 0.6f
            val dy1 = height * 0.25f
            drawCircle(
                brush = staticBlob1,
                center = Offset(dx1, dy1),
                radius = width * 0.8f
            )

            // Drift Blob 2 (Bottom-Left-ish)
            val dx2 = width * 0.4f
            val dy2 = height * 0.7f
            drawCircle(
                brush = staticBlob2,
                center = Offset(dx2, dy2),
                radius = width * 0.7f
            )
        }

        // The header and status stay in the vertical dashboard; only tiles page sideways.
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                // Keep the gesture inset outside the scroll viewport so even partially
                // visible tiles/page controls cannot draw against the system handle.
                .navigationBarsPadding()
                .padding(bottom = 16.dp)
                .verticalScroll(homeScrollState)
                .padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(modifier = Modifier.height(48.dp))

            // ── Logo with Pulsing Halo ───────────────────────────────────────
            val logoGlowTransition = rememberInfiniteTransition(label = "logo_glow_anim")
            val logoGlowScale by logoGlowTransition.animateFloat(
                initialValue = 1f,
                targetValue = 1.25f,
                animationSpec = infiniteRepeatable(
                    animation = tween(2200, easing = FastOutSlowInEasing),
                    repeatMode = RepeatMode.Reverse
                ),
                label = "logoGlowScale"
            )
            val logoGlowAlpha by logoGlowTransition.animateFloat(
                initialValue = 0.15f,
                targetValue = 0.35f,
                animationSpec = infiniteRepeatable(
                    animation = tween(2200, easing = FastOutSlowInEasing),
                    repeatMode = RepeatMode.Reverse
                ),
                label = "logoGlowAlpha"
            )

            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .scale(logoScale)
                    .alpha(logoAlpha)
            ) {
                // Pulsing glow ring
                Box(
                    modifier = Modifier
                        .size(76.dp)
                        .scale(logoGlowScale)
                        .clip(CircleShape)
                        .background(primaryColor.copy(alpha = logoGlowAlpha))
                )

                Image(
                    painter = painterResource(id = R.drawable.ic_playbridge_logo),
                    contentDescription = "PlayBridge Logo",
                    modifier = Modifier
                        .size(72.dp)
                        .clip(CircleShape)
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            Text(
                text = "PlayBridge",
                style = MaterialTheme.typography.headlineMedium.copy(
                    fontWeight = FontWeight.Bold,
                    letterSpacing = (-0.5).sp
                ),
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.alpha(logoAlpha)
            )

            Spacer(modifier = Modifier.height(4.dp))

            Text(
                text = "CONSOLE HUB",
                style = MaterialTheme.typography.labelSmall.copy(
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 3.sp
                ),
                color = primaryColor.copy(alpha = 0.7f),
                modifier = Modifier.alpha(logoAlpha)
            )

            // ── Interactive Connection Status Pill ────────────────────────────
            Spacer(modifier = Modifier.height(12.dp))
            val statusAlpha by animateFloatAsState(
                targetValue = if (visible) 1f else 0f,
                animationSpec = tween(600, delayMillis = 200),
                label = "statusAlpha"
            )

            val displayDeviceName = connectedDeviceName?.let {
                if (it.length > 18) it.take(15) + "..." else it
            } ?: "TV"

            // Green when connected over wss, amber when connected over plain ws.
            val accent = if (isSecure) Color(0xFF4CAF50) else Color(0xFFFFA000)
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = if (isConnected)
                    accent.copy(alpha = 0.15f)
                else
                    MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                border = BorderStroke(
                    1.dp,
                    if (isConnected) accent.copy(alpha = 0.3f)
                    else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.2f)
                ),
                modifier = Modifier
                    .alpha(statusAlpha)
                    .padding(horizontal = 16.dp)
                    .widthIn(max = 280.dp)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = ripple(bounded = true),
                        onClick = { onNavigate(Screen.Connection) }
                    )
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    if (isConnected) {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(accent)
                        )
                    } else {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f))
                        )
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = when {
                            isConnected && isSecure -> "Connected to $displayDeviceName securely"
                            isConnected -> "Connected to $displayDeviceName"
                            else -> "No device connected"
                        },
                        style = MaterialTheme.typography.labelMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        color = if (isConnected)
                            accent
                        else
                            MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(modifier = Modifier.height(36.dp))

            // Each page contains only dashboard tiles; the logo and connection stay put.
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                TextButton(onClick = { showReorder = true }) {
                    Icon(Icons.Default.SwapVert, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Reorder")
                }
            }
            // All pages share the same hierarchy, including pages of installed apps.
            HorizontalPager(
                state = pagerState,
                modifier = Modifier.fillMaxWidth().height(414.dp),
                pageSpacing = 12.dp,
                verticalAlignment = Alignment.Top,
            ) { page ->
                val pageItems = pages.getOrElse(page) { emptyList() }
                val rows = listOf(pageItems.take(2)) + pageItems.drop(2).chunked(3)
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    rows.forEachIndexed { rowIndex, rowItems ->
                        val columns = if (rowIndex == 0) 2 else 3
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            rowItems.forEach { item ->
                                key(item.id) {
                                    DashboardCard(
                                        item = item,
                                        isActive = item.app == null && item.id != "bridged-apps" &&
                                            isCurrentScreen(currentScreen, item.screen),
                                        animDelay = 0,
                                        visible = visible,
                                        modifier = Modifier.weight(1f),
                                        tall = rowIndex == 0,
                                        onClick = {
                                            item.app?.let(onOpenBridgedApp) ?: onNavigate(item.screen)
                                        },
                                        onLongClick = {
                                            if (item.app != null) appToInspectOrigin = item.app.origin
                                            else showReorder = true
                                        },
                                        onLongClickLabel = if (item.app != null) "App info for ${item.title}" else "Reorder tiles",
                                        appIconUrl = item.app?.iconUrl,
                                    )
                                }
                            }
                            repeat(columns - rowItems.size) { Spacer(Modifier.weight(1f)) }
                        }
                    }
                }
            }
            if (pages.size > 1) {
                Spacer(modifier = Modifier.height(18.dp))
                DashboardPageDots(
                    selectedPage = pagerState.currentPage,
                    pageCount = pages.size,
                    onSelect = selectPage,
                )
            }
            Spacer(modifier = Modifier.height(24.dp))
        }

        // ── Navigation Top Bar Shortcuts ─────────────────────────────────────
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 16.dp, vertical = 8.dp)
        ) {
            Row(
                modifier = Modifier.align(Alignment.TopStart),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                IconButton(
                    onClick = onClose,
                    modifier = Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f))
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Close Dashboard",
                        tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f)
                    )
                }
                IconButton(
                    onClick = { showExitConfirm = true },
                    modifier = Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)),
                ) {
                    Icon(
                        imageVector = Icons.Default.PowerSettingsNew,
                        contentDescription = "Exit PlayBridge",
                        tint = MaterialTheme.colorScheme.error,
                    )
                }
            }
            Row(
                modifier = Modifier.align(Alignment.TopEnd),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                IconButton(
                    onClick = { showOnboarding = true },
                    modifier = Modifier.size(40.dp).clip(CircleShape)
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)),
                ) {
                    Icon(Icons.Default.Info, contentDescription = stringResource(R.string.onboarding_open_guide),
                        tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f))
                }
                // Single global Settings entry — not in Browser menu or Library bottom nav.
                IconButton(
                    onClick = onSettings,
                    modifier = Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f))
                ) {
                    Icon(
                        imageVector = Icons.Default.Settings,
                        contentDescription = "Settings",
                        tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f)
                    )
                }
            }
        }

        if (showReorder) {
            DashboardReorderDialog(
                tiles = orderedItems.map { DashboardReorderTile(it.id, it.title) },
                onReorder = { ids -> saveOrder(DashboardTileOrder.reconcile(ids, availableIds)) },
                onChoosePosition = { id -> tileToMove = id },
                onDismiss = { showReorder = false },
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
                                    onClick = { moveTile(id, index); tileToMove = null },
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
                        onboardingPrefs.edit().putBoolean("dashboard_onboarding_seen", true).apply()
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
                text = { Text("This fully quits the app. Any cast that relies on PlayBridge — phone files, DLNA, or queued playback — will stop or error out.") },
                confirmButton = {
                    TextButton(
                        onClick = {
                            showExitConfirm = false
                            onExit()
                        }
                    ) {
                        Text("Exit", color = MaterialTheme.colorScheme.error)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showExitConfirm = false }) { Text("Cancel") }
                },
            )
        }
    }
}

@Composable
private fun DashboardPageDots(selectedPage: Int, pageCount: Int, onSelect: (Int) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        repeat(pageCount) { index ->
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clickable(onClickLabel = "Show dashboard page ${index + 1}") {
                        onSelect(index)
                    },
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    modifier = Modifier
                        .size(if (selectedPage == index) 10.dp else 7.dp)
                        .clip(CircleShape)
                        .background(
                            if (selectedPage == index) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f)
                        ),
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DashboardCard(
    item: DashboardItem,
    isActive: Boolean,
    animDelay: Int,
    visible: Boolean,
    modifier: Modifier = Modifier,
    tall: Boolean,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
    onLongClickLabel: String? = null,
    appIconUrl: String? = null,
) {
    // Perf: single entrance animation (alpha + offset) instead of 4 concurrent
    // per-card anims (cardAlpha/cardTranslateY/pressScale/activeScale).
    val cardAlpha by animateFloatAsState(
        targetValue = if (visible) 1f else 0f,
        animationSpec = tween(400, delayMillis = animDelay),
        label = "cardAlpha"
    )
    val cardTranslateY by animateFloatAsState(
        targetValue = if (visible) 0f else 60f,
        animationSpec = tween(500, delayMillis = animDelay, easing = FastOutSlowInEasing),
        label = "cardTranslateY"
    )

    // Tactile press scale response (no separate active-scale spring).
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val pressScale by animateFloatAsState(
        targetValue = if (isPressed) 0.95f else 1f,
        animationSpec = tween(150),
        label = "pressScale"
    )
    val finalScale = pressScale

    val height = if (tall) 150.dp else 120.dp

    // Glass-like translucent border adaptive to theme luminance
    val surfaceLuminance = MaterialTheme.colorScheme.surface.luminance()
    val isDarkTheme = surfaceLuminance < 0.5f
    val borderBrush = Brush.linearGradient(
        colors = if (isDarkTheme) {
            listOf(
                Color.White.copy(alpha = if (isActive) 0.35f else 0.15f),
                Color.White.copy(alpha = 0.03f)
            )
        } else {
            listOf(
                Color.Black.copy(alpha = if (isActive) 0.18f else 0.08f),
                Color.Black.copy(alpha = 0.02f)
            )
        }
    )

    Card(
        modifier = modifier
            .height(height)
            .graphicsLayer {
                alpha = cardAlpha
                translationY = cardTranslateY
                scaleX = finalScale
                scaleY = finalScale
            }
            .combinedClickable(
                interactionSource = interactionSource,
                indication = ripple(bounded = true),
                onClick = onClick,
                onLongClick = onLongClick,
                onLongClickLabel = onLongClickLabel,
            ),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = Color.Transparent
        ),
        border = BorderStroke(1.dp, borderBrush),
        elevation = CardDefaults.cardElevation(
            defaultElevation = if (isActive) 6.dp else 1.dp
        )
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Brush.linearGradient(item.gradientColors))
                .then(
                    if (isActive) Modifier.background(Color.White.copy(alpha = 0.12f))
                    else Modifier
                )
        ) {
            // Specular glass-like sheen overlay
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.radialGradient(
                            colors = listOf(Color.White.copy(alpha = 0.15f), Color.Transparent),
                            center = Offset(0f, 0f),
                            radius = 350f
                        )
                    )
            )

            // Subtle decorative circles
            Box(
                modifier = Modifier
                    .size(80.dp)
                    .align(Alignment.TopEnd)
                    .offset(x = 15.dp, y = (-15).dp)
                    .clip(CircleShape)
                    .background(Color.White.copy(alpha = 0.08f))
            )
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .align(Alignment.BottomStart)
                    .offset(x = (-10).dp, y = 30.dp)
                    .clip(CircleShape)
                    .background(Color.White.copy(alpha = 0.05f))
            )

            val horizontalPadding = if (tall) 16.dp else 10.dp
            val verticalPadding = if (tall) 16.dp else 12.dp

            val titleStyle = if (tall) {
                MaterialTheme.typography.titleMedium.copy(
                    fontWeight = FontWeight.SemiBold
                )
            } else {
                MaterialTheme.typography.titleSmall.copy(
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 13.sp,
                    letterSpacing = (-0.2).sp
                )
            }

            val subtitleStyle = if (tall) {
                MaterialTheme.typography.bodySmall
            } else {
                MaterialTheme.typography.labelSmall.copy(
                    fontSize = 10.sp
                )
            }

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = horizontalPadding, vertical = verticalPadding),
                verticalArrangement = Arrangement.SpaceBetween
            ) {
                // Icon with subtle background
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color.White.copy(alpha = 0.2f)),
                    contentAlignment = Alignment.Center
                ) {
                    if (appIconUrl != null) {
                        AsyncImage(
                            model = appIconUrl,
                            contentDescription = null,
                            modifier = Modifier.fillMaxSize(),
                        )
                    } else {
                        Icon(
                            imageVector = item.icon,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(22.dp)
                        )
                    }
                }

                Column {
                    Text(
                        text = item.title,
                        style = titleStyle,
                        color = Color.White,
                        maxLines = if (tall) 1 else 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = item.subtitle,
                        style = subtitleStyle,
                        color = Color.White.copy(alpha = 0.7f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            // Sleek "ACTIVE" glass chip indicator
            if (isActive) {
                Row(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(top = 12.dp, end = 12.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color.White.copy(alpha = 0.22f))
                        .padding(horizontal = 6.dp, vertical = 3.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(4.dp)
                            .clip(CircleShape)
                            .background(Color.White)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = "ACTIVE",
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontSize = 8.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 0.5.sp
                        ),
                        color = Color.White
                    )
                }
            }
        }
    }
}

private fun isCurrentScreen(current: Screen, target: Screen): Boolean {
    return when (target) {
        Screen.Library -> current == Screen.Library || current == Screen.AddonSettings
        else -> current == target
    }
}

private data class DashboardItem(
    val icon: ImageVector,
    val title: String,
    val subtitle: String,
    val screen: Screen,
    val gradientColors: List<Color>,
    val id: String = when (screen) {
        Screen.Browser -> "browser"
        Screen.Library -> "library"
        Screen.Connection -> "connection"
        Screen.ScreenMirror -> "screen-mirror"
        Screen.PhoneFiles -> "phone-files"
        Screen.DebridLibrary -> "debrid"
        Screen.Iptv -> "iptv"
        Screen.Collections -> "collections"
        Screen.CastHistory -> "cast-history"
        else -> error("Unsupported dashboard destination")
    },
    val app: BridgedApp? = null,
)
