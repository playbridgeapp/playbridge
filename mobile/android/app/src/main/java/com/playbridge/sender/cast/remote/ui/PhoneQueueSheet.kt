package com.playbridge.sender.cast.remote.ui

import android.content.Context
import android.util.Size
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BookmarkAdd
import androidx.compose.material.icons.filled.ClearAll
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.UnfoldMore
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.core.content.edit
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.playbridge.sender.cast.MediaKind
import com.playbridge.sender.cast.MediaThumbnail
import com.playbridge.sender.cast.PhoneFilesModel
import com.playbridge.sender.cast.PhoneQueueCoordinator
import com.playbridge.sender.cast.PhoneQueueItem
import com.playbridge.sender.cast.PhoneQueueState
import com.playbridge.sender.cast.remote.PlayerSetting
import com.playbridge.sender.cast.remote.RemoteAction
import com.playbridge.sender.cast.remote.RemoteDialogVolumeKeys
import com.playbridge.sender.cast.remote.RemoteUiState
import org.koin.compose.koinInject

private val RowHeight = 68.dp
private const val PREFS = "phone_queue_sheet"
private const val SHOW_PLAYED = "show_played"

/**
 * Up next for a phone list (Remote canvas, board 31): the whole list the phone holds, not
 * just the window the TV has, laid out like a music player's queue. Played items sit behind a
 * "Played" toggle (collapsed until opened; the choice is kept), then the current item as a
 * card that fills as it plays, a line naming the list with Repeat, Save and Clear, and what's
 * next. Drag a row's handle to reorder, swipe it away to remove, tap it to play it (the sheet stays open).
 * The TV follows through [PhoneQueueCoordinator].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PhoneQueueSheet(
    state: RemoteUiState,
    dispatch: (RemoteAction) -> Unit,
    phoneQueue: PhoneQueueCoordinator = koinInject(),
) {
    val queue by phoneQueue.state.collectAsStateWithLifecycle()
    val close = { dispatch(RemoteAction.CloseSheet) }
    val prefs = LocalContext.current.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    var showPlayed by remember { mutableStateOf(prefs.getBoolean(SHOW_PLAYED, false)) }
    val played = remember(queue.items, queue.position) { queue.items.take(queue.position) }
    val upcoming = remember(queue.items, queue.position) { queue.items.drop(queue.position + 1) }
    // Local order while dragging; the phone list takes the move on release.
    var order by remember(upcoming) { mutableStateOf(upcoming) }
    var draggingId by remember { mutableStateOf<String?>(null) }
    var dragOffset by remember { mutableFloatStateOf(0f) }
    val rowPx = with(LocalDensity.current) { RowHeight.toPx() }
    val loopSupported = state.nativePolicy == null || PlayerSetting.Loop in state.capabilities.settings
    val looping = state.settings.loopEnabled == true

    // Played rows (when shown) come first; scroll so the Played toggle and current card lead.
    val toggleIndex = if (showPlayed) played.size else 0
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = toggleIndex)
    LaunchedEffect(queue.position, showPlayed) { listState.animateScrollToItem(toggleIndex) }
    val tint = rememberQueueTint(queue.current?.uri, MaterialTheme.colorScheme.primary)

    ModalBottomSheet(
        onDismissRequest = close,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = tint.top,
        contentColor = Color.White,
        dragHandle = { BottomSheetDefaults.DragHandle(color = Color.White.copy(alpha = 0.7f)) },
    ) {
        RemoteDialogVolumeKeys()
        // Light text on the artwork's dark gradient, whatever the app theme.
        MaterialTheme(
            colorScheme = MaterialTheme.colorScheme.copy(
                primary = tint.cardFill,
                onSurface = Color.White,
                onSurfaceVariant = Color.White.copy(alpha = 0.68f),
                surfaceContainerHighest = Color.White.copy(alpha = 0.12f),
            ),
        ) {
            Column(
                Modifier.fillMaxWidth().fillMaxHeight(0.92f)
                    .background(tint.gradient()),
            ) {
                Column(
                    Modifier.fillMaxWidth().padding(bottom = 8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text("Up next", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Medium)
                    Text(
                        "On ${state.receiver?.name ?: "TV"}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                LazyColumn(state = listState, modifier = Modifier.weight(1f).padding(horizontal = 12.dp)) {
                    if (showPlayed) {
                        items(played, key = { it.id }) { item ->
                            SwipeToRemove(onRemove = { phoneQueue.removeItem(item.id) }) {
                                QueueRow(
                                    item = item,
                                    muted = true,
                                    dragHandle = null,
                                    onPlay = { phoneQueue.playItem(item.id) },
                                )
                            }
                        }
                    }
                    if (played.isNotEmpty()) {
                        item(key = "played-toggle") {
                            TextButton(onClick = {
                                showPlayed = !showPlayed
                                prefs.edit { putBoolean(SHOW_PLAYED, showPlayed) }
                            }) {
                                Text("Played · ${played.size}", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
                                Icon(
                                    if (showPlayed) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                                    contentDescription = if (showPlayed) "Hide played" else "Show played",
                                    tint = MaterialTheme.colorScheme.onSurface,
                                    modifier = Modifier.padding(start = 6.dp),
                                )
                            }
                        }
                    }
                    queue.current?.let { current ->
                        item(key = current.id) {
                            CurrentCard(current, state, tint, onToggle = { dispatch(RemoteAction.TogglePlayPause) })
                        }
                    }
                    item(key = "list-line") {
                        ListLine(
                            queue = queue,
                            state = state,
                            looping = looping,
                            loopSupported = loopSupported,
                            onLoop = { dispatch(RemoteAction.SetLoop(!looping)) },
                            onSave = { close(); phoneQueue.requestSave() },
                            onClear = { phoneQueue.clearQueue() },
                        )
                    }
                    if (order.isEmpty()) {
                        item(key = "empty") {
                            Text(
                                "Nothing else is queued.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 12.dp),
                            )
                        }
                    }
                    items(order, key = { it.id }) { item ->
                        val dragging = item.id == draggingId
                        SwipeToRemove(onRemove = { phoneQueue.removeItem(item.id) }) {
                            QueueRow(
                                item = item,
                                muted = false,
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
                                            if (id != null && at >= 0) phoneQueue.moveItem(id, queue.position + 1 + at)
                                        },
                                        onDragCancel = { draggingId = null; dragOffset = 0f; order = upcoming },
                                    )
                                },
                                onPlay = { phoneQueue.playItem(item.id) },
                            )
                        }
                    }
                }

                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(Icons.Default.PhoneAndroid, contentDescription = null, tint = Color(0xFFFFB74D), modifier = Modifier.size(16.dp))
                    Text(
                        "Plays from your phone. Keep it on the same Wi-Fi.",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/** The current item: artwork doubling as play/pause, and a fill that tracks playback. */
@Composable
private fun CurrentCard(current: PhoneQueueItem, state: RemoteUiState, tint: QueueTint, onToggle: () -> Unit) {
    val playback = state.playback
    val duration = playback?.durationMs ?: 0L
    val position = playback?.displayedPositionMs ?: 0L
    val progress = if (duration > 0) (position.toFloat() / duration).coerceIn(0f, 1f) else 0f
    val fill = tint.cardFill
    val base = tint.cardBase
    val onFill = tint.onCard
    val playing = playback?.displayedPlaying == true
    Box(Modifier.fillMaxWidth().height(80.dp).clip(RoundedCornerShape(16.dp)).background(base)) {
        Box(Modifier.fillMaxHeight().fillMaxWidth(progress).background(fill))
        Row(Modifier.fillMaxSize().padding(end = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(80.dp).background(MaterialTheme.colorScheme.surfaceContainerHighest)
                    .clickable(enabled = LocalRemoteControlsEnabled.current, onClick = onToggle),
                contentAlignment = Alignment.Center,
            ) {
                MediaThumbnail(current.uri.toUri(), current.kind, Size(320, 320), Modifier.fillMaxSize())
                Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.35f)))
                Icon(
                    if (playing) Icons.Default.Pause else Icons.Default.PlayArrow,
                    contentDescription = if (playing) "Pause" else "Play",
                    tint = Color.White,
                    modifier = Modifier.size(34.dp),
                )
            }
            Column(Modifier.weight(1f).padding(start = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    current.title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = onFill,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        current.artist ?: kindLabel(current.kind),
                        style = MaterialTheme.typography.bodySmall,
                        color = onFill,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    if (duration > 0) {
                        Text(
                            "${PhoneFilesModel.clock(position)} / ${PhoneFilesModel.clock(duration)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = onFill,
                        )
                    }
                }
            }
        }
    }
}

/** "Playing from …", where the list is and the time left, with Repeat, Save and Clear. */
@Composable
private fun ListLine(
    queue: PhoneQueueState,
    state: RemoteUiState,
    looping: Boolean,
    loopSupported: Boolean,
    onLoop: () -> Unit,
    onSave: () -> Unit,
    onClear: () -> Unit,
) {
    val upcoming = queue.items.drop(queue.position + 1)
    val playback = state.playback
    val currentLeft = queue.current?.let { current ->
        val duration = playback?.durationMs?.takeIf { it > 0 } ?: current.playMs
        (duration - (playback?.displayedPositionMs ?: 0L)).coerceAtLeast(0L)
    } ?: 0L
    // Time left only when every upcoming item's length is known.
    val left = if (upcoming.all { it.playMs > 0 }) currentLeft + upcoming.sumOf { it.playMs } else null
    val enabled = LocalRemoteControlsEnabled.current
    Row(
        Modifier.fillMaxWidth().padding(start = 6.dp, top = 8.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                buildAnnotatedString {
                    append("Playing from ")
                    withStyle(SpanStyle(fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)) {
                        append(queue.source ?: "your phone")
                    }
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                buildAnnotatedString {
                    append("${queue.position + 1} / ${queue.count}")
                    if (left != null && left > 0) {
                        withStyle(SpanStyle(color = MaterialTheme.colorScheme.onSurfaceVariant)) {
                            append(" · ${PhoneFilesModel.clock(left)} left")
                        }
                    }
                },
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        IconButton(
            onClick = onLoop,
            enabled = loopSupported && enabled,
            modifier = Modifier.semantics { contentDescription = if (looping) "Repeat this, on" else "Repeat this, off" },
            colors = IconButtonDefaults.iconButtonColors(
                contentColor = if (looping) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            ),
        ) { Icon(Icons.Default.RepeatOne, contentDescription = null) }
        IconButton(onClick = onSave) {
            Icon(Icons.Default.BookmarkAdd, contentDescription = "Save as collection", tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        IconButton(onClick = onClear, enabled = upcoming.isNotEmpty() && enabled) {
            Icon(Icons.Default.ClearAll, contentDescription = "Clear queue")
        }
    }
}

/** Swipe a row away (end to start) to remove it. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SwipeToRemove(onRemove: () -> Unit, content: @Composable () -> Unit) {
    val dismiss = rememberSwipeToDismissBoxState(
        confirmValueChange = { value ->
            if (value == SwipeToDismissBoxValue.EndToStart) onRemove()
            value == SwipeToDismissBoxValue.EndToStart
        },
    )
    SwipeToDismissBox(
        state = dismiss,
        enableDismissFromStartToEnd = false,
        backgroundContent = {
            if (dismiss.dismissDirection == SwipeToDismissBoxValue.EndToStart) Box(
                Modifier.fillMaxSize().clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.errorContainer).padding(end = 20.dp),
                contentAlignment = Alignment.CenterEnd,
            ) { Icon(Icons.Default.Delete, contentDescription = "Remove", tint = MaterialTheme.colorScheme.onErrorContainer) }
        },
    ) { content() }
}

/** A list row: artwork (square for songs, wide for videos and photos), title, artist, length. */
@Composable
private fun QueueRow(
    item: PhoneQueueItem,
    muted: Boolean,
    dragHandle: Modifier?,
    onPlay: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val square = item.kind == MediaKind.AUDIO
    val titleColor = if (muted) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface
    Row(
        modifier.fillMaxWidth().height(RowHeight).clickable(enabled = LocalRemoteControlsEnabled.current, onClick = onPlay).padding(start = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            Modifier.size(if (square) 52.dp else 80.dp, if (square) 52.dp else 45.dp).clip(RoundedCornerShape(10.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHighest),
        ) {
            MediaThumbnail(item.uri.toUri(), item.kind, if (square) Size(208, 208) else Size(320, 180), Modifier.fillMaxSize())
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(
                item.title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                color = titleColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                item.artist ?: kindLabel(item.kind),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (item.durationMs > 0) {
            Text(PhoneFilesModel.clock(item.durationMs), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (dragHandle != null) {
            Box(dragHandle.size(44.dp).semantics { contentDescription = "Drag to reorder" }, contentAlignment = Alignment.Center) {
                Icon(Icons.Default.UnfoldMore, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            Box(Modifier.size(44.dp))
        }
    }
}

private fun kindLabel(kind: MediaKind): String = when (kind) {
    MediaKind.AUDIO -> "Song"
    MediaKind.VIDEO -> "Video"
    MediaKind.IMAGE -> "Photo"
}
