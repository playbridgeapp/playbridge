package com.playbridge.player.player

import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import com.playbridge.player.ui.player.panels.PanelFocus
import com.playbridge.player.ui.player.panels.PanelRow
import com.playbridge.player.ui.player.panels.PlayerSidePanel
import com.playbridge.player.ui.player.panels.chapterPanelTitle
import com.playbridge.player.ui.player.panels.formatChapterTime
import com.playbridge.player.ui.player.panels.initialPanelFocus
import com.playbridge.shared.player.PlayerChapter

/**
 * Right-hand Chapters panel over the video: each chapter with its start time, the chapter playing
 * now marked "Now", focus starting on it. Selecting a chapter calls [onChapterSelected] with its
 * 0-based index (the caller seeks and closes). Left or Back call [onDismiss].
 */
@Composable
fun ChapterPickerDialog(
    chapters: List<PlayerChapter>,
    currentPositionMs: Long,
    onChapterSelected: (index: Int) -> Unit,
    onDismiss: () -> Unit,
) {
    val rows = remember(chapters, currentPositionMs) { buildChapterPickerRows(chapters, currentPositionMs) }
    // Position keeps ticking while the panel is open; the focus target is fixed at open time
    // (same rule as before: keyed on the row count only).
    val initialIndex = remember(rows.size) {
        currentChapterIndex(chapters, currentPositionMs).coerceIn(0, (rows.size - 1).coerceAtLeast(0))
    }
    val focus = remember(rows.size) { PanelFocus(initialIndex) }

    PlayerSidePanel(title = "Chapters", onDismiss = onDismiss, focus = focus) {
        items(rows, key = { it.index }) { row ->
            PanelRow(
                title = chapterPanelTitle(row.label),
                leading = formatChapterTime(row.startMs),
                trailing = if (row.isCurrent) "Now" else null,
                current = row.isCurrent,
                onClick = { onChapterSelected(row.index) },
                modifier = Modifier.initialPanelFocus(focus, row.index),
            )
        }
    }
}
