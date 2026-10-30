package com.playbridge.sender.ui

import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.zIndex
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.playbridge.sender.browser.BridgedApp

internal data class DashboardReorderTile(
    val id: String,
    val title: String,
    /** The tile's own icon and gradient, so the list reads like the Dashboard. */
    val icon: ImageVector = Icons.Default.Apps,
    val gradient: List<Color> = emptyList(),
    val app: BridgedApp? = null,
    val subtitle: String? = null,
)

/**
 * Customize home: the same drag-handle / exact-position workflow as iOS, plus a show or
 * hide switch per tile. Browser is always shown.
 */
@Composable
internal fun DashboardReorderDialog(
    tiles: List<DashboardReorderTile>,
    onReorder: (List<String>) -> Unit,
    onChoosePosition: (String) -> Unit,
    onDismiss: () -> Unit,
    hidden: Set<String> = emptySet(),
    onToggleHidden: (id: String, hide: Boolean) -> Unit = { _, _ -> },
    onReset: (() -> Unit)? = null,
) {
    val listState = rememberLazyListState()
    val haptics = LocalHapticFeedback.current
    val density = LocalDensity.current
    val edgeSize = with(density) { 56.dp.toPx() }
    val maxScrollSpeed = with(density) { 600.dp.toPx() }
    val commit by rememberUpdatedState(onReorder)
    val choosePosition by rememberUpdatedState(onChoosePosition)
    val savedIds by rememberUpdatedState(tiles.map { it.id })
    val byId = tiles.associateBy { it.id }
    var order by remember { mutableStateOf(savedIds) }
    var draggingId by remember { mutableStateOf<String?>(null) }
    var draggedCenter by remember { mutableFloatStateOf(0f) }

    // Exact-position moves and installed-app changes can arrive while the popup is
    // open. Keep a drag's preview stable, but cancel if its tile is removed.
    LaunchedEffect(savedIds, draggingId == null) {
        if (draggingId == null || draggingId !in savedIds) {
            draggingId = null
            order = savedIds
        } else {
            order = DashboardTileOrder.reconcile(order, savedIds)
        }
    }

    fun moveDraggedTile() {
        val id = draggingId ?: return
        val rows = listState.layoutInfo.visibleItemsInfo.mapNotNull { item ->
            val key = item.key as? String ?: return@mapNotNull null
            DashboardReorderRowBounds(key, item.index, item.offset, item.size)
        }
        val target = DashboardReorderDrag.targetIndex(id, order, rows, draggedCenter) ?: return
        val firstIndex = listState.firstVisibleItemIndex
        val firstOffset = listState.firstVisibleItemScrollOffset
        order = DashboardTileOrder.move(order, id, target)
        // Stable keys otherwise anchor the first visible tile while it changes
        // position, making the whole list jump underneath the user's finger.
        listState.requestScrollToItem(firstIndex, firstOffset)
    }

    fun finishDrag(save: Boolean) {
        if (draggingId == null) return
        if (save) {
            commit(DashboardTileOrder.reconcile(order, savedIds))
        } else {
            val firstIndex = listState.firstVisibleItemIndex
            val firstOffset = listState.firstVisibleItemScrollOffset
            order = savedIds
            listState.requestScrollToItem(firstIndex, firstOffset)
        }
        draggingId = null
    }

    // Continue scrolling while a handle is held near an edge, even if the finger
    // stops moving. Disposal/end/cancel cancels this frame-scoped loop immediately.
    LaunchedEffect(draggingId) {
        if (draggingId == null) return@LaunchedEffect
        var previousFrame = withFrameNanos { it }
        while (draggingId != null) {
            val frame = withFrameNanos { it }
            val seconds = ((frame - previousFrame) / 1_000_000_000f).coerceAtMost(0.05f)
            previousFrame = frame
            val layout = listState.layoutInfo
            val speed = DashboardReorderDrag.edgeScrollSpeed(
                draggedCenter, layout.viewportStartOffset, layout.viewportEndOffset, edgeSize, maxScrollSpeed,
            )
            if (speed != 0f) listState.scrollBy(speed * seconds)
            moveDraggedTile()
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.surface)
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(horizontal = 16.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().height(56.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onDismiss, modifier = Modifier.offset(x = (-12).dp)) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Done")
                }
                Text(
                    "Customize home",
                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                    modifier = Modifier.weight(1f),
                )
                if (onReset != null) TextButton(onClick = onReset) { Text("Reset") }
            }
            Text(
                "TILES",
                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold, letterSpacing = 0.8.sp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp, bottom = 8.dp),
            )
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f, fill = false)
                    .clip(RoundedCornerShape(16.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainer),
            ) {
                itemsIndexed(order, key = { _, id -> id }) { index, id ->
                    val tile = byId[id] ?: return@itemsIndexed
                    val isDragging = draggingId == id
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 56.dp)
                            .animateItem(placementSpec = if (isDragging) null else spring(dampingRatio = 1f))
                            .zIndex(if (isDragging) 1f else 0f)
                            .graphicsLayer {
                                translationY = if (isDragging) {
                                    val row = listState.layoutInfo.visibleItemsInfo.find { it.key == id }
                                    if (row != null) draggedCenter - (row.offset + row.size / 2f) else 0f
                                } else 0f
                                shadowElevation = if (isDragging) 6.dp.toPx() else 0f
                            }
                            .background(
                                if (isDragging) MaterialTheme.colorScheme.surfaceContainerHigh
                                else MaterialTheme.colorScheme.surfaceContainer
                            )
                            .padding(end = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            Icons.Default.DragHandle,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier
                                .size(48.dp)
                                .semantics { contentDescription = "Drag ${tile.title} to reorder" }
                                .clickable(onClickLabel = "Choose position for ${tile.title}") {
                                    if (draggingId == null) choosePosition(id)
                                }
                                .pointerInput(id) {
                                    detectDragGestures(
                                        onDragStart = {
                                            if (draggingId == null) {
                                                val row = listState.layoutInfo.visibleItemsInfo.find { it.key == id }
                                                if (row != null) {
                                                    draggedCenter = row.offset + row.size / 2f
                                                    draggingId = id
                                                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                                }
                                            }
                                        },
                                        onDrag = { change, delta ->
                                            change.consume()
                                            if (draggingId == id) {
                                                draggedCenter += delta.y
                                                moveDraggedTile()
                                            }
                                        },
                                        onDragEnd = { if (draggingId == id) finishDrag(save = true) },
                                        onDragCancel = { if (draggingId == id) finishDrag(save = false) },
                                    )
                                }
                                .padding(12.dp),
                        )
                        Row(
                            modifier = Modifier.weight(1f).clickable(
                                onClickLabel = "Choose position for ${tile.title}",
                            ) { if (draggingId == null) choosePosition(id) }.padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(32.dp)
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(
                                        if (tile.gradient.size >= 2) Brush.linearGradient(tile.gradient)
                                        else SolidColor(MaterialTheme.colorScheme.surfaceContainerHighest)
                                    ),
                                contentAlignment = Alignment.Center,
                            ) {
                                if (tile.app != null) {
                                    BridgedAppIcon(tile.app, modifier = Modifier.fillMaxSize())
                                } else {
                                    Icon(
                                        tile.icon, contentDescription = null, modifier = Modifier.size(18.dp),
                                        tint = if (tile.gradient.size >= 2) Color.White else MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                            Column(modifier = Modifier.weight(1f)) {
                                Text(tile.title, style = MaterialTheme.typography.bodyLarge)
                                if (tile.subtitle != null) Text(
                                    tile.subtitle,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        if (id in DashboardTileOrder.ALWAYS_SHOWN) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                modifier = Modifier.semantics(mergeDescendants = true) {},
                            ) {
                                Icon(Icons.Default.Lock, contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(16.dp))
                                Text("Always shown", style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        } else {
                            Switch(
                                checked = id !in hidden,
                                onCheckedChange = { show -> onToggleHidden(id, !show) },
                                modifier = Modifier.semantics { contentDescription = "Show ${tile.title}" },
                            )
                        }
                    }
                    if (index < order.lastIndex) HorizontalDivider(color = MaterialTheme.colorScheme.surfaceContainerHighest)
                }
            }
            Text(
                "Drag the handles to reorder, or tap a tile to choose its exact position. Switch a tile off to hide it. Changes are saved automatically.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 12.dp, bottom = 16.dp),
            )
        }
    }
}
