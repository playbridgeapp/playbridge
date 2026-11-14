package com.playbridge.player.ui.home

import android.view.KeyEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import com.playbridge.player.data.PlaybackHistoryItem
import com.playbridge.player.ui.components.TvFocusSurface
import com.playbridge.player.ui.theme.ThemedDialog
import com.playbridge.player.ui.theme.TvRadius
import com.playbridge.player.ui.theme.TvSize
import com.playbridge.player.ui.theme.TvSpacing
import com.playbridge.player.ui.theme.TvText
import com.playbridge.player.ui.theme.TvTheme

/**
 * Long-press actions for a bookmark, as a right-hand panel over a dimmed Home (a dialog window, so
 * D-pad focus cannot leave it). Back closes it. Actions: Resume / Play, Start over (only when there
 * is a place to resume from) and Remove bookmark.
 */
@Composable
fun BookmarkActionsPanel(
    item: PlaybackHistoryItem,
    onResume: () -> Unit,
    onStartOver: () -> Unit,
    onRemoveBookmark: () -> Unit,
    onDismiss: () -> Unit,
) {
    // The long press that opened this panel is still held; swallow its OK release so it does not
    // click the first action.
    var consumeCenterRelease by remember { mutableStateOf(true) }
    val firstAction = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { firstAction.requestFocus() } }

    val resume = resumePositionForHistoryItem(item)
    val colors = TvTheme.colors
    ThemedDialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.55f))
                .onPreviewKeyEvent { event ->
                    if (!consumeCenterRelease) return@onPreviewKeyEvent false
                    val code = event.nativeKeyEvent.keyCode
                    val isCenter = code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER ||
                        code == KeyEvent.KEYCODE_NUMPAD_ENTER
                    if (!isCenter) return@onPreviewKeyEvent false
                    if (event.nativeKeyEvent.action == KeyEvent.ACTION_UP) consumeCenterRelease = false
                    true
                },
        ) {
            Column(
                Modifier
                    .align(Alignment.CenterEnd)
                    .width(TvSize.sidePanelWidth)
                    .fillMaxHeight()
                    .background(colors.container)
                    .padding(horizontal = TvSpacing.panelPaddingH, vertical = TvSpacing.panelPaddingV)
                    .semantics { paneTitle = item.title ?: "Bookmark" },
                verticalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                Box(
                    Modifier.fillMaxWidth().height(230.dp).clip(TvRadius.optionShape).background(colors.containerHigh),
                ) {
                    if (item.thumbnailUrl != null) {
                        val key = historyThumbnailCacheKey(item)
                        AsyncImage(
                            model = ImageRequest.Builder(LocalContext.current)
                                .data(item.thumbnailUrl).memoryCacheKey(key).diskCacheKey(key).build(),
                            contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop,
                        )
                    }
                }
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        item.title ?: "Untitled", style = TvText.panelTitle.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.Bold),
                        color = colors.onSurface, maxLines = 2, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.semantics { heading() },
                    )
                    bookmarkPanelLine(item)?.let { Text(it, style = TvText.value.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.Normal), color = colors.onSurfaceMuted) }
                }
                Column(verticalArrangement = Arrangement.spacedBy(TvSpacing.rowGap)) {
                    ActionRow(
                        text = if (resume != null) "Resume from ${formatClock(resume)}" else "Play",
                        onClick = onResume, modifier = Modifier.focusRequester(firstAction),
                    )
                    if (resume != null) ActionRow("Start over", onStartOver)
                    ActionRow("Remove bookmark", onRemoveBookmark)
                }
            }
        }
    }
}

@Composable
private fun ActionRow(text: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val colors = TvTheme.colors
    TvFocusSurface(
        onClick = onClick,
        modifier = modifier.fillMaxWidth().height(TvSize.listRow),
        shape = TvRadius.rowShape,
        container = colors.containerHigh,
        contentAlignment = Alignment.CenterStart,
    ) { focused ->
        Text(
            text, style = TvText.row, maxLines = 1, overflow = TextOverflow.Ellipsis,
            color = if (focused) colors.onFocusFill else colors.onSurface,
            modifier = Modifier.padding(horizontal = 22.dp),
        )
    }
}
