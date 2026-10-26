package com.playbridge.sender.cast.remote.ui

import android.util.Size
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DragIndicator
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.core.net.toUri
import com.playbridge.sender.cast.MediaThumbnail
import com.playbridge.sender.cast.PhoneFilesModel
import com.playbridge.sender.cast.PhoneQueueCoordinator
import com.playbridge.sender.cast.PhoneQueueItem
import com.playbridge.sender.cast.remote.PlayerSetting
import com.playbridge.sender.cast.remote.RemoteAction
import com.playbridge.sender.cast.remote.RemoteDialogVolumeKeys
import com.playbridge.sender.cast.remote.RemoteUiState
import kotlinx.coroutines.launch
import org.koin.compose.koinInject
import androidx.lifecycle.compose.collectAsStateWithLifecycle

private val RowHeight = 64.dp

/**
 * Up next for a phone list (Remote canvas, board 30): the whole list the phone holds, not
 * just the window the TV has. Drag to reorder, remove, Repeat this, Save as collection,
 * Clear queue; tapping an item plays it. The TV follows through [PhoneQueueCoordinator].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PhoneQueueSheet(
    state: RemoteUiState,
    dispatch: (RemoteAction) -> Unit,
    phoneQueue: PhoneQueueCoordinator = koinInject(),
) {
    val queue by phoneQueue.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val close = { dispatch(RemoteAction.CloseSheet) }
    val upcoming = remember(queue.items, queue.position) { queue.items.drop(queue.position + 1) }
    // Local order while dragging; the phone list takes the move on release.
    var order by remember(upcoming) { mutableStateOf(upcoming) }
    var draggingId by remember { mutableStateOf<String?>(null) }
    var dragOffset by remember { mutableFloatStateOf(0f) }
    val rowPx = with(LocalDensity.current) { RowHeight.toPx() }
    val loopSupported = state.nativePolicy == null || PlayerSetting.Loop in state.capabilities.settings
    val looping = state.settings.loopEnabled == true

    ModalBottomSheet(
        onDismissRequest = close,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        RemoteDialogVolumeKeys()
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Up next", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Text(
                        "On ${state.receiver?.name ?: "TV"} · ${queue.remaining} left",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(
                    onClick = close,
                    modifier = Modifier.size(44.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceContainerHighest),
                ) { Icon(Icons.Default.Close, contentDescription = "Close") }
            }

            queue.current?.let { current -> NowPlayingCard(current, state) }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = { dispatch(RemoteAction.SetLoop(!looping)) },
                    enabled = loopSupported && LocalRemoteControlsEnabled.current,
                    modifier = Modifier.weight(1f).height(40.dp).semantics { contentDescription = if (looping) "Repeat this, on" else "Repeat this, off" },
                    colors = if (looping) androidx.compose.material3.ButtonDefaults.outlinedButtonColors(
                        containerColor = MaterialTheme.colorScheme.secondaryContainer,
                    ) else androidx.compose.material3.ButtonDefaults.outlinedButtonColors(),
                ) { Text("Repeat this") }
                OutlinedButton(
                    onClick = { close(); phoneQueue.requestSave() },
                    modifier = Modifier.weight(1f).height(40.dp),
                ) { Text("Save as collection") }
            }

            if (order.isEmpty()) {
                Text(
                    "Nothing else is queued.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 12.dp),
                )
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 420.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(MaterialTheme.colorScheme.surfaceContainerHigh),
                ) {
                    itemsIndexed(order, key = { _, item -> item.id }) { index, item ->
                        val dragging = item.id == draggingId
                        QueueRow(
                            item = item,
                            last = index == order.lastIndex,
                            modifier = Modifier
                                .zIndex(if (dragging) 1f else 0f)
                                .graphicsLayer { translationY = if (dragging) dragOffset else 0f }
                                .background(if (dragging) MaterialTheme.colorScheme.surfaceContainerHighest else Color.Transparent),
                            dragHandle = Modifier.pointerInput(item.id) {
                                detectDragGestures(
                                    onDragStart = { draggingId = item.id; dragOffset = 0f },
                                    onDrag = { change, amount ->
                                        change.consume()
                                        dragOffset += amount.y
                                        val at = order.indexOfFirst { it.id == draggingId }
                                        if (dragOffset > rowPx / 2 && at in 0 until order.lastIndex) {
                                            order = order.toMutableList().apply { add(at + 1, removeAt(at)) }
                                            dragOffset -= rowPx
                                        } else if (dragOffset < -rowPx / 2 && at > 0) {
                                            order = order.toMutableList().apply { add(at - 1, removeAt(at)) }
                                            dragOffset += rowPx
                                        }
                                    },
                                    onDragEnd = {
                                        val id = draggingId
                                        val at = order.indexOfFirst { it.id == id }
                                        draggingId = null
                                        dragOffset = 0f
                                        if (id != null && at >= 0) scope.launch { phoneQueue.move(id, queue.position + 1 + at) }
                                    },
                                    onDragCancel = { draggingId = null; dragOffset = 0f; order = upcoming },
                                )
                            },
                            onPlay = {
                                val at = queue.items.indexOfFirst { it.id == item.id }
                                if (at >= 0) scope.launch { phoneQueue.jumpTo(at) }
                                close()
                            },
                            onRemove = { scope.launch { phoneQueue.remove(item.id) } },
                        )
                    }
                }
            }

            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Color(0xFF2A2418)).padding(12.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Icon(Icons.Default.PhoneAndroid, contentDescription = null, tint = Color(0xFFFFB74D), modifier = Modifier.size(18.dp))
                Text(
                    "These play from your phone. Keep it on the same Wi-Fi until the queue ends.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFFF2DFC0),
                )
            }
            if (order.isNotEmpty()) {
                TextButton(
                    onClick = { scope.launch { phoneQueue.clearUpcoming() } },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Clear queue", color = MaterialTheme.colorScheme.error) }
            }
        }
    }
}

@Composable
private fun NowPlayingCard(current: PhoneQueueItem, state: RemoteUiState) {
    val playback = state.playback
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(Modifier.size(80.dp, 45.dp).clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.surfaceContainerHighest)) {
                MediaThumbnail(current.uri.toUri(), current.kind, Size(320, 180), Modifier.fillMaxWidth().height(45.dp))
            }
            Column(Modifier.weight(1f)) {
                Text("NOW PLAYING", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = Color(0xFF46D483))
                Text(current.title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        val duration = playback?.durationMs ?: 0L
        if (duration > 0) {
            LinearProgressIndicator(
                progress = { (playback!!.displayedPositionMs.toFloat() / duration).coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)),
            )
        }
    }
}

@Composable
private fun QueueRow(
    item: PhoneQueueItem,
    last: Boolean,
    modifier: Modifier,
    dragHandle: Modifier,
    onPlay: () -> Unit,
    onRemove: () -> Unit,
) {
    Column(modifier.fillMaxWidth().height(RowHeight)) {
        Row(
            Modifier.weight(1f).fillMaxWidth().clickable(onClick = onPlay).padding(start = 2.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Box(dragHandle.size(40.dp).semantics { contentDescription = "Drag to reorder" }, contentAlignment = Alignment.Center) {
                Icon(Icons.Default.DragIndicator, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Box(Modifier.size(64.dp, 36.dp).clip(RoundedCornerShape(6.dp)).background(MaterialTheme.colorScheme.surfaceContainerHighest)) {
                MediaThumbnail(item.uri.toUri(), item.kind, Size(256, 144), Modifier.width(64.dp).height(36.dp))
            }
            Column(Modifier.weight(1f)) {
                Text(item.title, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (item.durationMs > 0) {
                    Text(PhoneFilesModel.clock(item.durationMs), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            IconButton(onClick = onRemove, modifier = Modifier.size(44.dp)) {
                Icon(Icons.Default.Close, contentDescription = "Remove from queue", tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (!last) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
    }
}
