package com.playbridge.sender.cast

import android.util.Size
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.PhoneAndroid
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * Video details (boards 2 and 9). With a TV chosen it leads with "Play on <TV>"; with none it
 * leads with "Play on this phone" and offers "Play on a TV…".
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MediaDetailsSheet(
    media: PhoneMediaItem,
    tvName: String?,
    unsupportedOnTv: Boolean,
    subtitleName: String?,
    onDismiss: () -> Unit,
    onPlayOnTv: () -> Unit,
    onPlayOnPhone: () -> Unit,
    onPickTv: () -> Unit,
    onAddToCollection: () -> Unit,
    onShare: () -> Unit,
    onAddSubtitles: () -> Unit,
    onRemoveSubtitles: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(16f / 9f)
                    .clip(RoundedCornerShape(16.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh),
                contentAlignment = Alignment.Center,
            ) {
                MediaThumbnail(media, Size(640, 360), Modifier.fillMaxSize())
                IconButton(
                    onClick = onPlayOnPhone,
                    modifier = Modifier.size(56.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.55f)),
                ) {
                    Icon(Icons.Default.PlayArrow, contentDescription = "Preview on this phone", tint = Color.White, modifier = Modifier.size(28.dp))
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(media.title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                val meta = listOfNotNull(
                    PhoneFilesModel.clock(media.durationMs).takeIf { media.durationMs > 0 },
                    PhoneFilesModel.size(media.sizeBytes),
                    media.bucketName,
                ).joinToString(" · ")
                if (meta.isNotEmpty()) {
                    Text(meta, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            if (tvName != null) {
                Button(
                    onClick = onPlayOnTv,
                    enabled = !unsupportedOnTv,
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                ) {
                    Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(20.dp))
                    Text("  Play on $tvName", fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                if (unsupportedOnTv) {
                    Text(
                        "$tvName can't play this kind of file.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                Button(onClick = onPlayOnPhone, modifier = Modifier.fillMaxWidth().height(52.dp)) {
                    Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(20.dp))
                    Text("  Play on this phone", fontWeight = FontWeight.Bold)
                }
                OutlinedButton(onClick = onPickTv, modifier = Modifier.fillMaxWidth().height(52.dp)) {
                    Icon(Icons.Default.Tv, contentDescription = null, modifier = Modifier.size(20.dp))
                    Text("  Play on a TV…")
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ActionTile(Icons.AutoMirrored.Filled.PlaylistAdd, "Collection", onAddToCollection, Modifier.weight(1f))
                ActionTile(Icons.Default.Share, "Share", onShare, Modifier.weight(1f))
            }

            if (tvName != null && media.mediaKind == MediaKind.VIDEO) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .background(MaterialTheme.colorScheme.surfaceContainerHigh),
                ) {
                    if (subtitleName == null) {
                        ListRow(
                            icon = Icons.Default.Subtitles,
                            title = "Add subtitles…",
                            subtitle = "Pick an .srt or .vtt file",
                            onClick = onAddSubtitles,
                            trailing = { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant) },
                        )
                    } else {
                        ListRow(
                            icon = Icons.Default.Subtitles,
                            title = subtitleName,
                            subtitle = "Subtitles for this video",
                            onClick = onAddSubtitles,
                            trailing = {
                                IconButton(onClick = onRemoveSubtitles) {
                                    Icon(Icons.Default.Close, contentDescription = "Remove subtitles")
                                }
                            },
                        )
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                    ListRow(icon = Icons.Outlined.PhoneAndroid, title = "Play on this phone instead", onClick = onPlayOnPhone)
                }
            }
        }
    }
}

@Composable
private fun ActionTile(icon: ImageVector, label: String, onClick: () -> Unit, modifier: Modifier) {
    Column(
        modifier = modifier
            .height(72.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
        Text(label, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 6.dp))
    }
}

@Composable
private fun ListRow(
    icon: ImageVector,
    title: String,
    onClick: () -> Unit,
    subtitle: String? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            subtitle?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        trailing?.invoke()
    }
}

/** Sort and view: order, grid or list (Videos), and Browse other files. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SortAndViewSheet(
    tab: PhoneFilesTab,
    sort: SortKey,
    videosAsList: Boolean,
    onSort: (SortKey) -> Unit,
    onVideosAsList: (Boolean) -> Unit,
    onBrowseOtherFiles: () -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 24.dp)) {
            if (tab != PhoneFilesTab.PHOTOS) {
                SheetHeading("Sort")
                SortKey.entries.forEach { key -> OptionRow(key.label, sort == key) { onSort(key) } }
            }
            if (tab == PhoneFilesTab.VIDEOS) {
                SheetHeading("View")
                OptionRow("Grid", !videosAsList) { onVideosAsList(false) }
                OptionRow("List", videosAsList) { onVideosAsList(true) }
            }
            HorizontalDivider(Modifier.padding(vertical = 12.dp))
            Row(
                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable(onClick = onBrowseOtherFiles).padding(vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Icon(Icons.Outlined.Folder, contentDescription = null)
                Column {
                    Text("Browse other files", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "Downloads, SD card, USB or cloud folders",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun SheetHeading(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp, bottom = 4.dp))
}

@Composable
private fun OptionRow(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Text(label, style = MaterialTheme.typography.bodyLarge)
    }
}

/** Select mode actions (board 3); playing a selection arrives with the queue. */
@Composable
internal fun SelectionBar(
    selected: List<PhoneMediaItem>,
    modifier: Modifier,
    onAddToCollection: () -> Unit,
    onShare: () -> Unit,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 20.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        val total = selected.sumOf { it.durationMs.coerceAtLeast(0) }
        Text(
            if (total > 0) "In the order you picked · ${PhoneFilesModel.clock(total)} in total" else "In the order you picked",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onAddToCollection, modifier = Modifier.weight(1f).height(48.dp)) {
                Icon(Icons.AutoMirrored.Filled.PlaylistAdd, contentDescription = null, modifier = Modifier.size(18.dp))
                Text("  Add ${selected.size} to collection", fontWeight = FontWeight.Bold, maxLines = 1)
            }
            OutlinedButton(onClick = onShare, modifier = Modifier.height(48.dp)) {
                Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(18.dp))
                Text("  Share")
            }
        }
    }
}
