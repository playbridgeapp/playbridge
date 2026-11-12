package com.playbridge.player.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import com.playbridge.player.data.PlaybackHistoryItem
import com.playbridge.player.server.WebSocketServer
import com.playbridge.player.ui.components.IconCircleButton
import com.playbridge.player.ui.components.PillButton
import com.playbridge.player.ui.components.PillStyle
import com.playbridge.player.ui.components.SectionHeading
import com.playbridge.player.ui.components.TvChip
import com.playbridge.player.ui.components.TvFocusSurface
import com.playbridge.player.ui.components.TvTone
import com.playbridge.player.ui.components.tvFocusable
import com.playbridge.player.ui.theme.TvRadius
import com.playbridge.player.ui.theme.TvSize
import com.playbridge.player.ui.theme.TvSpacing
import com.playbridge.player.ui.theme.TvText
import com.playbridge.player.ui.theme.TvTheme

/**
 * Home: is this TV ready, who is connected, and the bookmarks. [bookmarks] is already filtered
 * and ordered (see [bookmarksOf]). [address] is "ip:port" when known.
 *
 * [takeInitialFocus] is false while a corner card owns focus; when it flips back to true focus
 * returns to the first bookmark (or Watch Together when there are none).
 */
@Composable
fun HomeScreen(
    deviceName: String,
    address: String?,
    connectionState: WebSocketServer.ConnectionState,
    connectedCount: Int,
    pairedCount: Int,
    bookmarks: List<PlaybackHistoryItem>,
    onPlay: (PlaybackHistoryItem) -> Unit,
    onBookmarkActions: (PlaybackHistoryItem) -> Unit,
    onWatchTogether: () -> Unit,
    onPhones: () -> Unit,
    onSettings: () -> Unit,
    takeInitialFocus: Boolean = true,
) {
    val firstRun = isFirstRun(pairedCount, bookmarks.size)
    val firstFocus = remember { FocusRequester() }
    LaunchedEffect(takeInitialFocus, bookmarks.isEmpty()) {
        if (takeInitialFocus) runCatching { firstFocus.requestFocus() }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(start = TvSpacing.screenGutter, end = TvSpacing.screenGutter, top = TvSpacing.screenTop, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(if (firstRun) 28.dp else 22.dp),
    ) {
        HomeHeader(
            pairedCount = pairedCount,
            onWatchTogether = onWatchTogether,
            onPhones = onPhones,
            onSettings = onSettings,
            watchTogetherModifier = if (bookmarks.isEmpty()) Modifier.focusRequester(firstFocus) else Modifier,
        )
        if (firstRun) {
            FirstRunBody(deviceName = deviceName, address = address, connectionState = connectionState)
        } else {
            StatusBlock(
                deviceName = deviceName, address = address,
                connectionState = connectionState, connectedCount = connectedCount, big = false,
            )
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                SectionHeading("Bookmarks")
                if (bookmarks.isEmpty()) BookmarkHint() else BookmarkRow(bookmarks, onPlay, onBookmarkActions, firstFocus)
            }
        }
    }
}

@Composable
private fun HomeHeader(
    pairedCount: Int,
    onWatchTogether: () -> Unit,
    onPhones: () -> Unit,
    onSettings: () -> Unit,
    watchTogetherModifier: Modifier,
) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(
                Modifier.size(32.dp).background(TvTheme.colors.accent, RoundedCornerShapeLogo),
                contentAlignment = Alignment.Center,
            ) {
                Image(
                    rememberVectorPainter(Icons.Filled.PlayArrow), contentDescription = null,
                    modifier = Modifier.size(22.dp), colorFilter = ColorFilter.tint(TvTheme.colors.onAccent),
                )
            }
            Text("PlayBridge", style = TvText.sectionHeading, color = TvTheme.colors.onSurface)
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            PillButton(
                text = "Watch Together", onClick = onWatchTogether, style = PillStyle.Secondary,
                icon = groupIconPainter(), height = TvSize.pillHeightCompact,
                modifier = watchTogetherModifier,
            )
            if (pairedCount > 0) {
                PillButton(
                    text = "Phones", onClick = onPhones, style = PillStyle.Secondary,
                    icon = phoneIconPainter(), height = TvSize.pillHeightCompact,
                )
            }
            IconCircleButton(
                icon = rememberVectorPainter(Icons.Filled.Settings), contentDescription = "Settings",
                onClick = onSettings, size = TvSize.iconButtonSmall, tone = TvTone.App,
            )
        }
    }
}

private val RoundedCornerShapeLogo = androidx.compose.foundation.shape.RoundedCornerShape(9.dp)

@Composable
private fun StatusBlock(
    deviceName: String,
    address: String?,
    connectionState: WebSocketServer.ConnectionState,
    connectedCount: Int,
    big: Boolean,
) {
    val status = readyStatus(connectionState)
    val connected = connectedPhonesLabel(connectedCount)
    Column(verticalArrangement = Arrangement.spacedBy(if (big) 8.dp else 10.dp)) {
        Text(
            deviceName, style = TvText.pageTitle.let { if (big) it.copy(fontSize = androidx.compose.ui.unit.TextUnit(48f, androidx.compose.ui.unit.TextUnitType.Sp)) else it },
            color = TvTheme.colors.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Text(
                status.text, style = TvText.value.copy(fontWeight = FontWeight.Normal),
                color = when (status.tone) {
                    ReadyTone.Problem -> TvTheme.colors.error
                    else -> TvTheme.colors.onSurfaceMuted
                },
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false),
            )
            if (connected != null) {
                TvChip(connected, icon = phoneIconPainter())
            }
            if (address != null && !big) {
                Text(address, style = TvText.small, color = TvTheme.colors.onSurfaceDim, maxLines = 1)
            }
        }
    }
}

@Composable
private fun FirstRunBody(
    deviceName: String,
    address: String?,
    connectionState: WebSocketServer.ConnectionState,
) {
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.SpaceBetween) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(26.dp, Alignment.CenterVertically)) {
            StatusBlock(
                deviceName = deviceName, address = address,
                connectionState = connectionState, connectedCount = 0, big = true,
            )
            Column(verticalArrangement = Arrangement.spacedBy(18.dp)) {
                Step(1) { Text("Install PlayBridge on your phone", style = STEP_TEXT, color = TvTheme.colors.onSurface) }
                Step(2) { Text("Join the same Wi-Fi as this TV", style = STEP_TEXT, color = TvTheme.colors.onSurface) }
                Step(3) {
                    Text(
                        buildAnnotatedString {
                            append("Pick ")
                            withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { append(deviceName) }
                            append(" in the app and enter the code shown here")
                        },
                        style = STEP_TEXT, color = TvTheme.colors.onSurface,
                    )
                }
            }
            if (address != null) {
                Text(
                    "Not listed in the app? Add it by address: $address",
                    style = TvText.caption, color = TvTheme.colors.onSurfaceMuted,
                )
            }
        }
        BookmarkHint()
    }
}

private val STEP_TEXT = TvText.row.copy(fontSize = androidx.compose.ui.unit.TextUnit(21f, androidx.compose.ui.unit.TextUnitType.Sp), fontWeight = FontWeight.Normal)

@Composable
private fun Step(number: Int, content: @Composable () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(18.dp)) {
        Box(
            Modifier.size(44.dp).background(TvTheme.colors.accentContainer, TvRadius.circle),
            contentAlignment = Alignment.Center,
        ) {
            Text("$number", style = TvText.settingTitle, color = TvTheme.colors.onAccentContainer)
        }
        content()
    }
}

@Composable
private fun BookmarkHint() {
    Row(
        Modifier
            .fillMaxWidth()
            .height(64.dp)
            .background(TvTheme.colors.containerLow, TvRadius.optionShape)
            .padding(horizontal = 22.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Image(
            bookmarkIconPainter(), contentDescription = null,
            modifier = Modifier.size(22.dp), colorFilter = ColorFilter.tint(TvTheme.colors.onSurfaceMuted),
        )
        Text(
            "Bookmark a video while it plays and it will be waiting here, with your place saved.",
            style = TvText.value.copy(fontWeight = FontWeight.Normal), color = TvTheme.colors.onSurfaceMuted,
            maxLines = 2, overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun BookmarkRow(
    bookmarks: List<PlaybackHistoryItem>,
    onPlay: (PlaybackHistoryItem) -> Unit,
    onActions: (PlaybackHistoryItem) -> Unit,
    firstFocus: FocusRequester,
) {
    // 8dp of padding on every side keeps the focus ring from being clipped.
    LazyRow(
        horizontalArrangement = Arrangement.spacedBy(20.dp),
        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp),
    ) {
        itemsIndexed(bookmarks, key = { _, item -> item.id }) { index, item ->
            BookmarkTile(
                item = item,
                onPlay = { onPlay(item) },
                onActions = { onActions(item) },
                modifier = if (index == 0) Modifier.focusRequester(firstFocus) else Modifier,
            )
        }
    }
}

@Composable
private fun BookmarkTile(
    item: PlaybackHistoryItem,
    onPlay: () -> Unit,
    onActions: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = TvTheme.colors
    Column(Modifier.width(TvSize.bookmarkTileWidth), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(
            modifier
                .size(TvSize.bookmarkTileWidth, TvSize.bookmarkTileHeight)
                .tvFocusable(
                    onClick = onPlay, onLongClick = onActions, shape = TvRadius.tileShape,
                    container = colors.containerHigh, role = Role.Button,
                    contentDescription = bookmarkDescription(item),
                )
                .clip(TvRadius.tileShape),
        ) {
            if (item.thumbnailUrl != null) {
                val key = historyThumbnailCacheKey(item)
                AsyncImage(
                    model = ImageRequest.Builder(LocalContext.current)
                        .data(item.thumbnailUrl).memoryCacheKey(key).diskCacheKey(key).crossfade(false).build(),
                    contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop,
                )
            }
            val progress = bookmarkProgress(item)
            if (progress != null) {
                Box(Modifier.fillMaxWidth().height(6.dp).align(Alignment.BottomCenter).background(colors.surface)) {
                    Box(Modifier.fillMaxWidth(progress).height(6.dp).background(colors.accent))
                }
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                item.title ?: "Untitled", style = TvText.settingTitle.copy(fontSize = androidx.compose.ui.unit.TextUnit(18f, androidx.compose.ui.unit.TextUnitType.Sp)),
                color = colors.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            bookmarkTileLine(item)?.let {
                Text(it, style = TvText.small, color = colors.onSurfaceMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}
