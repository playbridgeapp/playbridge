package com.playbridge.sender.browser

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.systemGestureExclusion
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cast
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SettingsRemote
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.core.content.edit
import com.playbridge.sender.R
import com.playbridge.sender.ui.frostedGlass
import com.playbridge.sender.ui.rememberFrostedBackdrop
import com.playbridge.sender.ui.theme.DockGlass
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

/** Observe AndroidView / Compose taps in the initial pass; the website still gets every event. */
@Composable
internal fun Modifier.observeFullscreenInteractions(enabled: Boolean, onInteraction: () -> Unit): Modifier {
    val currentOnInteraction by rememberUpdatedState(onInteraction)
    return pointerInput(enabled) {
        if (enabled) {
            awaitEachGesture {
                awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                currentOnInteraction()
            }
        }
    }
}

private const val EDGE_SHORTCUT_ON_RIGHT = "edge_shortcut_on_right"
private const val EDGE_SHORTCUT_Y_FRACTION = "edge_shortcut_y_fraction"

/**
 * Fills the page area and places [PlayBridgeEdgeShortcut] where the user last dropped it.
 * Dragging moves it freely; on release it snaps to the nearer side and keeps its height.
 * The position is saved, so it survives restarts.
 */
@Composable
internal fun MovablePlayBridgeEdgeShortcut(
    fullscreen: Boolean,
    fullscreenInteraction: Int,
    onDashboard: () -> Unit,
    onRemote: () -> Unit,
    onDevices: () -> Unit,
    onRefresh: () -> Unit,
    onBack: () -> Unit,
    onAppSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("browser_prefs", android.content.Context.MODE_PRIVATE) }
    var onRight by remember { mutableStateOf(prefs.getBoolean(EDGE_SHORTCUT_ON_RIGHT, true)) }
    var yFraction by remember { mutableFloatStateOf(prefs.getFloat(EDGE_SHORTCUT_Y_FRACTION, 0.5f)) }
    var dragOffset by remember { mutableStateOf<Offset?>(null) }

    BoxWithConstraints(modifier) {
        val handleSizePx = with(LocalDensity.current) { 48.dp.toPx() }
        val maxX = (constraints.maxWidth - handleSizePx).coerceAtLeast(0f)
        val maxY = (constraints.maxHeight - handleSizePx).coerceAtLeast(0f)
        val resting = Offset(if (onRight) maxX else 0f, yFraction.coerceIn(0f, 1f) * maxY)
        val shown = dragOffset ?: resting

        PlayBridgeEdgeShortcut(
            fullscreen = fullscreen,
            fullscreenInteraction = fullscreenInteraction,
            onDashboard = onDashboard,
            onRemote = onRemote,
            onDevices = onDevices,
            onRefresh = onRefresh,
            onBack = onBack,
            onAppSettings = onAppSettings,
            onRight = onRight,
            dragging = dragOffset != null,
            handleModifier = Modifier.pointerInput(maxX, maxY, resting) {
                detectDragGestures(
                    onDragStart = { dragOffset = resting },
                    onDragEnd = {
                        dragOffset?.let { position ->
                            onRight = position.x >= maxX / 2f
                            yFraction = if (maxY > 0f) position.y / maxY else 0.5f
                            prefs.edit {
                                putBoolean(EDGE_SHORTCUT_ON_RIGHT, onRight)
                                putFloat(EDGE_SHORTCUT_Y_FRACTION, yFraction)
                            }
                        }
                        dragOffset = null
                    },
                    onDragCancel = { dragOffset = null },
                ) { change, dragAmount ->
                    change.consume()
                    val current = dragOffset ?: resting
                    dragOffset = Offset(
                        x = (current.x + dragAmount.x).coerceIn(0f, maxX),
                        y = (current.y + dragAmount.y).coerceIn(0f, maxY),
                    )
                }
            },
            modifier = Modifier
                .align(Alignment.TopStart)
                .offset { IntOffset(shown.x.roundToInt(), shown.y.roundToInt()) },
        )
    }
}

/** A small visual handle with a full 48dp touch target; it never takes space from the page. */
@Composable
internal fun PlayBridgeEdgeShortcut(
    fullscreen: Boolean,
    fullscreenInteraction: Int,
    onDashboard: () -> Unit,
    onRemote: () -> Unit,
    onDevices: () -> Unit,
    onRefresh: () -> Unit,
    onBack: () -> Unit = onDashboard,
    onAppSettings: () -> Unit = {},
    onRight: Boolean = true,
    dragging: Boolean = false,
    handleModifier: Modifier = Modifier,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    var visible by remember(fullscreen) { mutableStateOf(!fullscreen) }
    // The dock keeps the same charcoal glass and pale foreground in both themes.
    val glassForeground = DockGlass.foreground
    val glassIcon = DockGlass.mutedForeground
    // The flat side sits against the screen edge; a handle being dragged is rounded all round.
    val handleShape = when {
        dragging -> RoundedCornerShape(12.dp)
        onRight -> RoundedCornerShape(topStart = 12.dp, bottomStart = 12.dp)
        else -> RoundedCornerShape(topEnd = 12.dp, bottomEnd = 12.dp)
    }
    val menuShape = RoundedCornerShape(18.dp)
    val backdrop = rememberFrostedBackdrop(
        enabled = visible,
        refreshIntervalMillis = 1_000L,
        refreshKey = expanded,
    )
    val glassRim = DockGlass.rim
    val menuItemColors = MenuDefaults.itemColors(
        textColor = glassForeground,
        leadingIconColor = glassIcon,
    )

    // Fullscreen pages expose no reliable "player controls visible" event. Observe screen
    // taps without consuming them, and offer this shortcut for the same brief interaction.
    LaunchedEffect(fullscreen, fullscreenInteraction, expanded) {
        visible = !fullscreen || fullscreenInteraction > 0
        if (fullscreen && visible && !expanded) {
            delay(3_500)
            visible = false
        }
    }

    AnimatedVisibility(
        visible = visible,
        modifier = modifier,
        enter = fadeIn(tween(120)),
        exit = fadeOut(tween(180)),
    ) {
        Box {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    // The handle sits in the system back-gesture strip; without this a tap that
                    // drifts sideways, or a drag off the edge, is taken as Back.
                    .systemGestureExclusion()
                    .then(handleModifier)
                    .semantics { contentDescription = "PlayBridge menu" }
                    .clickable(role = Role.Button) { expanded = !expanded },
                contentAlignment = if (onRight) Alignment.CenterEnd else Alignment.CenterStart,
            ) {
                Surface(
                    modifier = Modifier.size(width = 28.dp, height = 36.dp),
                    shape = handleShape,
                    color = Color.Transparent,
                    border = BorderStroke(0.75.dp, glassRim),
                    shadowElevation = 3.dp,
                ) {
                    Box(
                        modifier = Modifier.fillMaxSize().frostedGlass(backdrop, 0.55f, handleShape),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_playbridge_logo),
                            contentDescription = null,
                            tint = Color.Unspecified,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
            }
            DropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false },
                modifier = Modifier.width(156.dp).frostedGlass(backdrop, 0.65f, menuShape),
                shape = menuShape,
                containerColor = Color.Transparent,
                tonalElevation = 0.dp,
                shadowElevation = 6.dp,
                border = BorderStroke(0.75.dp, glassRim),
            ) {
                DropdownMenuItem(
                    colors = menuItemColors,
                    text = { Text("Back") },
                    leadingIcon = { Icon(Icons.AutoMirrored.Filled.ArrowBack, null, Modifier.size(18.dp)) },
                    onClick = { expanded = false; onBack() },
                )
                DropdownMenuItem(
                    colors = menuItemColors,
                    text = { Text("Dashboard") },
                    leadingIcon = {
                        Icon(painterResource(R.drawable.ic_dashboard_blocks), null, Modifier.size(18.dp))
                    },
                    onClick = { expanded = false; onDashboard() },
                )
                DropdownMenuItem(
                    colors = menuItemColors,
                    text = { Text("Remote") },
                    leadingIcon = { Icon(Icons.Default.SettingsRemote, null, Modifier.size(18.dp)) },
                    onClick = { expanded = false; onRemote() },
                )
                DropdownMenuItem(
                    colors = menuItemColors,
                    text = { Text("Play on") },
                    leadingIcon = { Icon(Icons.Default.Cast, null, Modifier.size(18.dp)) },
                    onClick = { expanded = false; onDevices() },
                )
                DropdownMenuItem(
                    colors = menuItemColors,
                    text = { Text("Reload") },
                    leadingIcon = { Icon(Icons.Default.Refresh, null, Modifier.size(18.dp)) },
                    onClick = { expanded = false; onRefresh() },
                )
                DropdownMenuItem(
                    colors = menuItemColors,
                    text = { Text("App Settings") },
                    leadingIcon = { Icon(Icons.Default.Settings, null, Modifier.size(18.dp)) },
                    onClick = { expanded = false; onAppSettings() },
                )
            }
        }
    }
}
