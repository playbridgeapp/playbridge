package com.playbridge.sender.browser

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cast
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.playbridge.sender.R
import com.playbridge.sender.ui.frostedGlass
import com.playbridge.sender.ui.rememberFrostedBackdrop
import com.playbridge.sender.ui.theme.DockGlass
import kotlinx.coroutines.delay

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

/** A small visual handle with a full 48dp touch target; it never takes space from the page. */
@Composable
internal fun PlayBridgeEdgeShortcut(
    fullscreen: Boolean,
    fullscreenInteraction: Int,
    onDashboard: () -> Unit,
    onDevices: () -> Unit,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    var visible by remember(fullscreen) { mutableStateOf(!fullscreen) }
    // Nuvio's dock keeps the same charcoal glass and pale foreground in both themes.
    val glassForeground = DockGlass.foreground
    val glassIcon = DockGlass.mutedForeground
    val handleShape = RoundedCornerShape(topStart = 12.dp, bottomStart = 12.dp)
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
                    .semantics { contentDescription = "PlayBridge menu" }
                    .clickable(role = Role.Button) { expanded = !expanded },
                contentAlignment = Alignment.CenterEnd,
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
                    text = { Text("Dashboard") },
                    leadingIcon = {
                        Icon(painterResource(R.drawable.ic_dashboard_blocks), null, Modifier.size(18.dp))
                    },
                    onClick = { expanded = false; onDashboard() },
                )
                DropdownMenuItem(
                    colors = menuItemColors,
                    text = { Text("Devices") },
                    leadingIcon = { Icon(Icons.Default.Cast, null, Modifier.size(18.dp)) },
                    onClick = { expanded = false; onDevices() },
                )
                DropdownMenuItem(
                    colors = menuItemColors,
                    text = { Text("Refresh") },
                    leadingIcon = { Icon(Icons.Default.Refresh, null, Modifier.size(18.dp)) },
                    onClick = { expanded = false; onRefresh() },
                )
            }
        }
    }
}
