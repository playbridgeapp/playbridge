package com.playbridge.player.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.tv.material3.Text
import com.playbridge.player.ui.theme.TvSpacing
import com.playbridge.player.ui.theme.TvText
import com.playbridge.player.ui.theme.TvTheme

/** Section heading (22sp semibold) with an optional muted [hint] beside it, e.g. "Bookmarks". Not focusable. */
@Composable
fun SectionHeading(
    text: String,
    modifier: Modifier = Modifier,
    hint: String? = null,
    tone: TvTone = TvTone.App,
) {
    Row(
        modifier,
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.spacedBy(TvSpacing.m),
    ) {
        Text(
            text, style = TvText.sectionHeading,
            color = if (tone == TvTone.App) TvTheme.colors.onSurface else TvTheme.player.onSurface,
            modifier = Modifier.semantics { heading() },
        )
        if (hint != null) {
            Text(
                hint, style = TvText.small,
                color = if (tone == TvTone.App) TvTheme.colors.onSurfaceMuted else TvTheme.player.onSurfaceMuted,
            )
        }
    }
}
