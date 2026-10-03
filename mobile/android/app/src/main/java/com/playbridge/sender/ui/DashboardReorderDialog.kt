package com.playbridge.sender.ui

import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.zIndex

internal data class DashboardReorderTile(val id: String, val title: String)

/** Centered popup with the same drag-handle / exact-position workflow as iOS. */
@Composable
internal fun DashboardReorderDialog(
    tiles: List<DashboardReorderTile>,
    onReorder: (List<String>) -> Unit,
    onChoosePosition: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val listState = rememberLazyListState()
    val maxDialogHeight = (LocalConfiguration.current.screenHeightDp * 0.8f).dp
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
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = 480.dp)
                .fillMaxWidth(0.9f)
                .heightIn(max = maxDialogHeight)
                .shadow(16.dp, RoundedCornerShape(24.dp), clip = false)
                .clip(RoundedCornerShape(24.dp))
                .background(MaterialTheme.colorScheme.surface)
                .padding(16.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Reorder Tiles", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                TextButton(onClick = onDismiss) { Text("Done") }
            }
            Text(
                "The first two tiles on each page are larger.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(16.dp))
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxWidth().heightIn(max = 480.dp),
                contentPadding = PaddingValues(bottom = 16.dp),
            ) {
                itemsIndexed(order, key = { _, id -> id }) { index, id ->
                    val tile = byId[id] ?: return@itemsIndexed
                    val isDragging = draggingId == id
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
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
                                else MaterialTheme.colorScheme.surface,
                                RoundedCornerShape(12.dp),
                            )
                            .padding(start = 12.dp, end = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Row(
                            modifier = Modifier.weight(1f).clickable(
                                onClickLabel = "Choose position for ${tile.title}",
                            ) { if (draggingId == null) choosePosition(id) }.padding(vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Text(
                                "${index + 1}",
                                modifier = Modifier.widthIn(min = 32.dp),
                                color = MaterialTheme.colorScheme.primary,
                                style = MaterialTheme.typography.bodyLarge,
                                textAlign = TextAlign.End,
                                maxLines = 1,
                                softWrap = false,
                            )
                            Column(modifier = Modifier.weight(1f)) {
                                Text(tile.title, style = MaterialTheme.typography.bodyLarge)
                                Text(
                                    "${if (index % DashboardTileOrder.TILES_PER_PAGE < 2) "Large" else "Compact"} · Page ${index / DashboardTileOrder.TILES_PER_PAGE + 1}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
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
                    }
                }
                item(key = "reorder-help") {
                    Text(
                        "Drag the handles to reorder, or tap a tile to choose its exact position. Changes are saved automatically.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 12.dp, bottom = 16.dp),
                    )
                }
            }
        }
    }
}
