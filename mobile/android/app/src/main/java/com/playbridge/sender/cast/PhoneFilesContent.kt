package com.playbridge.sender.cast

import android.graphics.Bitmap
import android.os.Build
import android.util.LruCache
import android.util.Size
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Audiotrack
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Photo
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.Slideshow
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private val OnTvGreen = Color(0xFF46D483)

// ---- Videos (board 1) ------------------------------------------------------------------------

@Composable
internal fun VideosTab(
    uiState: PhoneFilesUiState,
    folders: List<PhoneFolder>,
    videos: List<PhoneMediaItem>,
    onTvUri: String?,
    bottomPadding: Dp,
    callbacks: PhoneFilesCallbacks,
) {
    LazyVerticalGrid(
        state = uiState.videoGrid,
        columns = GridCells.Fixed(if (uiState.videosAsList) 1 else 2),
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = bottomPadding),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                FolderChips(folders, uiState.folderId) { uiState.folderId = it }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        uiState.sort.label,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    if (callbacks.playListAvailable && videos.isNotEmpty() && !uiState.selecting) {
                        SmallActionButton("Play all", Icons.Default.PlayArrow) { callbacks.onPlayList(videos, false, "All videos") }
                    }
                }
            }
        }
        if (videos.isEmpty()) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                EmptyMessage(if (uiState.query.isNotBlank()) "No matches" else "No videos here")
            }
        }
        items(videos, key = { it.uri.toString() }) { video ->
            val key = video.uri.toString()
            val order = uiState.selection.indexOf(key).takeIf { it >= 0 }?.plus(1)
            if (uiState.videosAsList) {
                MediaRow(
                    media = video,
                    subtitle = listOfNotNull(
                        PhoneFilesModel.clock(video.durationMs).takeIf { video.durationMs > 0 },
                        video.bucketName,
                        PhoneFilesModel.size(video.sizeBytes),
                    ).joinToString(" · "),
                    onTv = key == onTvUri,
                    selectionOrder = order,
                    selecting = uiState.selecting,
                    callbacks = callbacks,
                )
            } else {
                VideoCard(video, onTv = key == onTvUri, selectionOrder = order, selecting = uiState.selecting, callbacks = callbacks)
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun VideoCard(
    video: PhoneMediaItem,
    onTv: Boolean,
    selectionOrder: Int?,
    selecting: Boolean,
    callbacks: PhoneFilesCallbacks,
) {
    Column(
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .combinedClickable(
                onClick = { callbacks.onOpen(video) },
                onLongClick = { callbacks.onLongPress(video) },
            ),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                .selectionOutline(selectionOrder != null, RoundedCornerShape(12.dp)),
        ) {
            MediaThumbnail(video, Size(320, 180), Modifier.fillMaxSize())
            if (onTv) OnTvBadge(Modifier.align(Alignment.TopStart).padding(6.dp))
            if (video.durationMs > 0) {
                Text(
                    PhoneFilesModel.clock(video.durationMs),
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(6.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(Color.Black.copy(alpha = 0.7f))
                        .padding(horizontal = 5.dp, vertical = 2.dp),
                )
            }
            if (selecting) SelectionMarker(selectionOrder, Modifier.align(Alignment.TopStart).padding(8.dp))
        }
        Text(
            video.title,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        val meta = listOfNotNull(video.bucketName, PhoneFilesModel.size(video.sizeBytes)).joinToString(" · ")
        if (meta.isNotEmpty()) {
            Text(
                meta,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

// ---- Music (boards 5 and 6) ------------------------------------------------------------------

@Composable
internal fun MusicTab(
    uiState: PhoneFilesUiState,
    songs: List<PhoneMediaItem>,
    onTvUri: String?,
    bottomPadding: Dp,
    callbacks: PhoneFilesCallbacks,
) {
    val filtered = remember(songs, uiState.query, uiState.sort) {
        PhoneFilesModel.sorted(songs.filter { PhoneFilesModel.matches(it, uiState.query.trim()) }, uiState.sort)
    }
    val viewChips: @Composable () -> Unit = {
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            MusicView.entries.forEach { view ->
                PillChip(view.label, selected = uiState.musicView == view) { uiState.musicView = view }
            }
        }
    }
    when (uiState.musicView) {
        MusicView.SONGS -> LazyColumn(
            state = uiState.musicList,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = bottomPadding),
        ) {
            item { viewChips() }
            if (callbacks.playListAvailable && filtered.isNotEmpty() && !uiState.selecting) {
                item { PlayShuffleRow(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 8.dp), playLabel = "Play all", shuffleLabel = "Shuffle all", onPlay = { callbacks.onPlayList(filtered, false, "All songs") }, onShuffle = { callbacks.onPlayList(filtered, true, "All songs") }) }
            }
            if (filtered.isEmpty()) item { EmptyMessage(if (uiState.query.isNotBlank()) "No matches" else "No music on this phone") }
            items(filtered, key = { it.uri.toString() }) { song ->
                val key = song.uri.toString()
                Box(Modifier.padding(horizontal = 8.dp)) {
                    MediaRow(
                        media = song,
                        subtitle = listOfNotNull(
                            song.artist,
                            PhoneFilesModel.clock(song.durationMs).takeIf { song.durationMs > 0 },
                        ).joinToString(" · "),
                        onTv = key == onTvUri,
                        selectionOrder = uiState.selection.indexOf(key).takeIf { it >= 0 }?.plus(1),
                        selecting = uiState.selecting,
                        callbacks = callbacks,
                    )
                }
            }
        }
        MusicView.ARTISTS -> {
            val artists = remember(filtered) { PhoneFilesModel.artists(filtered) }
            LazyColumn(
                state = uiState.musicList,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = bottomPadding),
            ) {
                item { viewChips() }
                if (artists.isEmpty()) item { EmptyMessage("No artists") }
                items(artists, key = { it.key }) { artist ->
                    GroupRow(artist.title, PhoneFilesModel.songCount(artist.songs.size), artist.songs.first()) {
                        uiState.openGroupKey = artist.key
                    }
                }
            }
        }
        MusicView.ALBUMS, MusicView.FOLDERS -> {
            val groups = remember(filtered, uiState.musicView) {
                if (uiState.musicView == MusicView.ALBUMS) PhoneFilesModel.albums(filtered)
                else PhoneFilesModel.musicFolders(filtered)
            }
            Column(Modifier.fillMaxSize()) {
            viewChips()
            LazyVerticalGrid(
                state = uiState.musicGrid,
                columns = GridCells.Fixed(2),
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = bottomPadding),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                if (groups.isEmpty()) {
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        EmptyMessage(if (uiState.musicView == MusicView.ALBUMS) "No albums" else "No folders")
                    }
                }
                items(groups, key = { it.key }) { group ->
                    AlbumCard(group) { uiState.openGroupKey = group.key }
                }
            }
            }
        }
    }
}

@Composable
private fun AlbumCard(group: SongGroup<PhoneMediaItem>, onClick: () -> Unit) {
    Column(
        modifier = Modifier.clip(RoundedCornerShape(12.dp)).clickable(onClick = onClick),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHigh),
        ) {
            MediaThumbnail(group.songs.first(), Size(256, 256), Modifier.fillMaxSize())
        }
        Text(group.title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(
            listOfNotNull(group.subtitle, PhoneFilesModel.songCount(group.songs.size)).joinToString(" · "),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun GroupRow(title: String, subtitle: String, art: PhoneMediaItem, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(Modifier.size(48.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceContainerHigh)) {
            MediaThumbnail(art, Size(128, 128), Modifier.fillMaxSize())
        }
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** Album, artist or folder page (board 6). */
@Composable
internal fun SongGroupPage(
    group: SongGroup<PhoneMediaItem>,
    listState: LazyListState,
    playingUri: String?,
    tvName: String?,
    selection: List<String>,
    bottomPadding: Dp,
    onBack: () -> Unit,
    callbacks: PhoneFilesCallbacks,
) {
    LazyColumn(state = listState, modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = bottomPadding)) {
        item {
            Row(Modifier.fillMaxWidth().height(56.dp).padding(start = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back to music")
                }
            }
        }
        item {
            Row(
                modifier = Modifier.padding(horizontal = 16.dp).padding(bottom = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.Bottom,
            ) {
                Box(Modifier.size(132.dp).clip(RoundedCornerShape(16.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh)) {
                    MediaThumbnail(group.songs.first(), Size(320, 320), Modifier.fillMaxSize())
                }
                Column(Modifier.padding(bottom = 4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(group.title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    group.subtitle?.let {
                        Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                    }
                    Text(
                        "${PhoneFilesModel.songCount(group.songs.size)} · ${PhoneFilesModel.minutes(group.totalDurationMs)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        if (callbacks.playListAvailable && selection.isEmpty()) {
            item {
                PlayShuffleRow(
                    Modifier.padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 8.dp),
                    playLabel = "Play", shuffleLabel = "Shuffle",
                    onPlay = { callbacks.onPlayList(group.songs, false, group.title) },
                    onShuffle = { callbacks.onPlayList(group.songs, true, group.title) },
                )
            }
        }
        itemsIndexed(group.songs, key = { _, song -> song.uri.toString() }) { index, song ->
            val key = song.uri.toString()
            val playing = key == playingUri
            Box(Modifier.padding(horizontal = 8.dp)) {
                MediaRow(
                    media = song,
                    subtitle = if (playing && tvName != null) "Playing on $tvName"
                    else PhoneFilesModel.clock(song.durationMs).takeIf { song.durationMs > 0 }.orEmpty(),
                    onTv = playing,
                    selectionOrder = selection.indexOf(key).takeIf { it >= 0 }?.plus(1),
                    selecting = selection.isNotEmpty(),
                    callbacks = callbacks,
                    leadingNumber = song.trackNumber?.takeIf { group.key.startsWith("album:") }?.let { it % 1000 } ?: (index + 1),
                )
            }
        }
    }
}

// ---- Photos (board 7) ------------------------------------------------------------------------

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun PhotosTab(
    uiState: PhoneFilesUiState,
    folders: List<PhoneFolder>,
    photos: List<PhoneMediaItem>,
    onTvUri: String?,
    bottomPadding: Dp,
    callbacks: PhoneFilesCallbacks,
) {
    val sections = remember(photos) { PhoneFilesModel.photoSections(photos) }
    LazyVerticalGrid(
        state = uiState.photoGrid,
        columns = GridCells.Fixed(3),
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = bottomPadding),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                FolderChips(folders, uiState.folderId) { uiState.folderId = it }
                if (callbacks.slideshowAvailable && photos.isNotEmpty() && !uiState.selecting) {
                    val name = folders.firstOrNull { it.id == uiState.folderId }?.name
                    Button(
                        onClick = { callbacks.onSlideshow(name ?: "All photos", photos) },
                        modifier = Modifier.fillMaxWidth().height(48.dp),
                    ) {
                        Icon(Icons.Default.Slideshow, contentDescription = null, modifier = Modifier.size(18.dp))
                        Text("  Slideshow of ${name ?: "all photos"} on TV", fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
        if (photos.isEmpty()) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                EmptyMessage(if (uiState.query.isNotBlank()) "No matches" else "No photos here")
            }
        }
        sections.forEach { section ->
            item(span = { GridItemSpan(maxLineSpan) }, key = "section:${section.label}") {
                Text(
                    section.label.uppercase(),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 12.dp, bottom = 6.dp),
                )
            }
            items(section.items, key = { it.uri.toString() }) { photo ->
                val key = photo.uri.toString()
                val order = uiState.selection.indexOf(key).takeIf { it >= 0 }?.plus(1)
                val onTv = key == onTvUri
                Box(
                    modifier = Modifier
                        .aspectRatio(1f)
                        .clip(RoundedCornerShape(6.dp))
                        .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                        .then(if (onTv) Modifier.border(3.dp, OnTvGreen, RoundedCornerShape(6.dp)) else Modifier)
                        .selectionOutline(order != null, RoundedCornerShape(6.dp))
                        .combinedClickable(
                            onClick = { callbacks.onOpen(photo) },
                            onLongClick = { callbacks.onLongPress(photo) },
                        )
                        .semantics { contentDescription = if (onTv) "Photo, showing on TV" else "Photo" },
                ) {
                    MediaThumbnail(photo, Size(256, 256), Modifier.fillMaxSize())
                    if (onTv) OnTvBadge(Modifier.align(Alignment.BottomStart).padding(5.dp))
                    if (uiState.selecting) SelectionMarker(order, Modifier.align(Alignment.TopStart).padding(6.dp))
                }
            }
        }
    }
}

// ---- Access (board 10) -----------------------------------------------------------------------

@Composable
internal fun AccessCard(kind: MediaKind, blocked: Boolean, onAllow: () -> Unit, onBrowseOtherFiles: () -> Unit) {
    val noun = when (kind) {
        MediaKind.VIDEO -> "videos"
        MediaKind.AUDIO -> "music"
        MediaKind.IMAGE -> "photos"
    }
    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(20.dp))
                .background(MaterialTheme.colorScheme.surfaceContainer)
                .padding(horizontal = 16.dp, vertical = 18.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("Let PlayBridge see your $noun", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(
                if (blocked) "Access was turned off. Allow it in Settings under Permissions."
                else "Files stay on your phone. PlayBridge only plays them on the device you pick.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Button(onClick = onAllow) { Text(if (blocked) "Open settings" else "Allow access to $noun") }
        }
        TextButton(onClick = onBrowseOtherFiles) { Text("Or pick a file to play") }
    }
}

/** Android 14 "Select photos and videos" state. */
@Composable
internal fun PickedAccessNote(onChange: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, top = 12.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .padding(start = 14.dp, top = 4.dp, bottom = 4.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(Icons.Outlined.PhotoLibrary, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Text("Showing the videos and photos you picked", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        TextButton(onClick = onChange) { Text("Change", fontWeight = FontWeight.Bold) }
    }
}

// ---- Shared pieces ---------------------------------------------------------------------------

@Composable
internal fun FolderChips(folders: List<PhoneFolder>, selectedId: Long?, onSelect: (Long?) -> Unit) {
    if (folders.size < 2) return
    Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        PillChip("All", selected = selectedId == null) { onSelect(null) }
        folders.forEach { folder ->
            PillChip(folder.name, selected = selectedId == folder.id) { onSelect(folder.id) }
        }
    }
}

@Composable
internal fun PillChip(label: String, selected: Boolean, onClick: () -> Unit) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = if (selected) FontWeight.Bold else null) },
        shape = RoundedCornerShape(18.dp),
        colors = FilterChipDefaults.filterChipColors(
            selectedContainerColor = MaterialTheme.colorScheme.primary,
            selectedLabelColor = MaterialTheme.colorScheme.onPrimary,
        ),
        modifier = Modifier.heightIn(min = 36.dp),
    )
}

/** A list row for songs, and for videos in list view. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun MediaRow(
    media: PhoneMediaItem,
    subtitle: String,
    onTv: Boolean,
    selectionOrder: Int?,
    selecting: Boolean,
    callbacks: PhoneFilesCallbacks,
    leadingNumber: Int? = null,
) {
    var menuOpen by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .combinedClickable(onClick = { callbacks.onOpen(media) }, onLongClick = { callbacks.onLongPress(media) })
            .padding(start = 8.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (selecting) SelectionMarker(selectionOrder, Modifier)
        if (leadingNumber != null) {
            Text(
                "$leadingNumber",
                style = MaterialTheme.typography.bodyMedium,
                color = if (onTv) OnTvGreen else MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.End,
                modifier = Modifier.width(22.dp),
            )
        } else {
            val wide = media.mediaKind == MediaKind.VIDEO
            Box(
                Modifier
                    .size(width = if (wide) 80.dp else 44.dp, height = if (wide) 45.dp else 44.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh),
            ) {
                MediaThumbnail(media, Size(160, 128), Modifier.fillMaxSize())
            }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                media.title,
                style = MaterialTheme.typography.bodyLarge,
                color = if (onTv) OnTvGreen else MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (subtitle.isNotEmpty()) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (!selecting) {
            Box {
                IconButton(onClick = { menuOpen = true }) {
                    Icon(Icons.Default.MoreVert, contentDescription = "More for ${media.title}", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    if (callbacks.queueEnabled) {
                        DropdownMenuItem(text = { Text("Play next") }, onClick = {
                            menuOpen = false
                            callbacks.onPlayNext(media)
                        })
                        DropdownMenuItem(text = { Text("Add to queue") }, onClick = {
                            menuOpen = false
                            callbacks.onAddToQueue(media)
                        })
                    }
                    DropdownMenuItem(text = { Text("Add to collection") }, onClick = {
                        menuOpen = false
                        callbacks.onAddToCollection(media)
                    })
                    DropdownMenuItem(text = { Text("Play on this phone") }, onClick = {
                        menuOpen = false
                        callbacks.onPlayOnPhone(media)
                    })
                    DropdownMenuItem(text = { Text("Select") }, onClick = {
                        menuOpen = false
                        callbacks.onLongPress(media)
                    })
                }
            }
        } else {
            Spacer(Modifier.width(12.dp))
        }
    }
}

@Composable
private fun OnTvBadge(modifier: Modifier) {
    Text(
        "ON TV",
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.onSecondaryContainer,
        modifier = modifier
            .clip(RoundedCornerShape(4.dp))
            .background(MaterialTheme.colorScheme.secondaryContainer)
            .padding(horizontal = 6.dp, vertical = 2.dp),
    )
}

/** Pick-order number when selected, an empty ring otherwise (board 3). */
@Composable
internal fun SelectionMarker(order: Int?, modifier: Modifier) {
    if (order != null) {
        Box(
            modifier.size(26.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary),
            contentAlignment = Alignment.Center,
        ) {
            Text("$order", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onPrimary)
        }
    } else {
        Box(
            modifier
                .size(24.dp)
                .clip(CircleShape)
                .background(Color.Black.copy(alpha = 0.3f))
                .border(2.dp, Color.White, CircleShape),
        )
    }
}

@Composable
private fun Modifier.selectionOutline(selected: Boolean, shape: RoundedCornerShape): Modifier =
    if (selected) border(3.dp, MaterialTheme.colorScheme.primary, shape) else this

@Composable
internal fun EmptyMessage(text: String) {
    Box(Modifier.fillMaxWidth().padding(vertical = 48.dp), contentAlignment = Alignment.Center) {
        Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Small in-memory cache so scrolling back doesn't re-decode thumbnails. */
private object ThumbnailCache : LruCache<String, Bitmap>(32 * 1024 * 1024) {
    override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
}

/** Video frame, photo or album art loaded via ContentResolver; icon fallback. */
@Composable
internal fun MediaThumbnail(media: PhoneMediaItem, size: Size, modifier: Modifier) =
    MediaThumbnail(media.uri, media.mediaKind, size, modifier)

/** Thumbnail of a phone file by its content URI (Up next, the mini player). */
@Composable
internal fun MediaThumbnail(uri: android.net.Uri, kind: MediaKind, size: Size, modifier: Modifier) {
    val context = LocalContext.current
    val cacheKey = "$uri@${size.width}x${size.height}"
    val bitmap by produceState(initialValue = ThumbnailCache.get(cacheKey), cacheKey) {
        if (value != null || Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return@produceState
        value = withContext(Dispatchers.IO) {
            runCatching { context.contentResolver.loadThumbnail(uri, size, null) }.getOrNull()
                ?.also { ThumbnailCache.put(cacheKey, it) }
        }
    }
    Box(modifier, contentAlignment = Alignment.Center) {
        val bmp = bitmap
        if (bmp != null) {
            Image(bitmap = bmp.asImageBitmap(), contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        } else {
            Icon(
                imageVector = when (kind) {
                    MediaKind.AUDIO -> Icons.Default.Audiotrack
                    MediaKind.IMAGE -> Icons.Default.Photo
                    MediaKind.VIDEO -> Icons.Default.Movie
                },
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(24.dp),
            )
        }
    }
}

@Composable
private fun PlayShuffleRow(
    modifier: Modifier,
    playLabel: String,
    shuffleLabel: String,
    onPlay: () -> Unit,
    onShuffle: () -> Unit,
) {
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = onPlay, modifier = Modifier.weight(1f).height(44.dp)) {
            Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
            Text("  $playLabel", fontWeight = FontWeight.Bold)
        }
        OutlinedButton(onClick = onShuffle, modifier = Modifier.weight(1f).height(44.dp)) {
            Icon(Icons.Default.Shuffle, contentDescription = null, modifier = Modifier.size(18.dp))
            Text("  $shuffleLabel")
        }
    }
}

@Composable
private fun SmallActionButton(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .height(36.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSecondaryContainer)
        Text(label, style = MaterialTheme.typography.labelLarge)
    }
}
