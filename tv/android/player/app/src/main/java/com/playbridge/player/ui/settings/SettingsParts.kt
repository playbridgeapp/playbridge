package com.playbridge.player.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Phone
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import com.playbridge.player.ui.components.PillButton
import com.playbridge.player.ui.components.PillStyle
import com.playbridge.player.ui.components.TvFocusSurface
import com.playbridge.player.ui.theme.TvRadius
import com.playbridge.player.ui.theme.TvSize
import com.playbridge.player.ui.theme.TvSpacing
import com.playbridge.player.ui.theme.TvText
import com.playbridge.player.ui.theme.TvTheme

/**
 * One entry of the category list (56dp). The selected category keeps a tinted fill even while
 * focus is in the rows; the focused one swaps to the focus fill and ring.
 */
@Composable
internal fun CategoryRow(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = TvTheme.colors
    TvFocusSurface(
        onClick = onClick,
        modifier = modifier.fillMaxWidth().height(TvSize.navRow),
        shape = TvRadius.rowShape,
        container = if (selected) colors.accentContainer else androidx.compose.ui.graphics.Color.Transparent,
        focusedContainer = colors.focusFill,
        ringColor = colors.focusRing,
        role = Role.Tab,
        contentAlignment = Alignment.CenterStart,
    ) { focused ->
        Text(
            label,
            modifier = Modifier.padding(horizontal = TvSpacing.xl),
            style = if (selected) TvText.rowSelected else TvText.row,
            color = when {
                focused -> colors.onFocusFill
                selected -> colors.onAccentContainer
                else -> colors.onSurfaceMuted
            },
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** A read-only row (not focusable): [title], optional [description] and a right-aligned [value]. */
@Composable
internal fun InfoRow(
    title: String,
    value: String,
    modifier: Modifier = Modifier,
    description: String? = null,
) {
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = TvSize.settingsRow)
            .background(TvTheme.colors.container, TvRadius.optionShape)
            .padding(horizontal = 26.dp, vertical = TvSpacing.m),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(TvSpacing.xxl),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(TvSpacing.xxs)) {
            Text(title, style = TvText.settingTitle, color = TvTheme.colors.onSurface)
            if (description != null) {
                Text(description, style = TvText.caption, color = TvTheme.colors.onSurfaceMuted)
            }
        }
        Text(
            value,
            style = TvText.row.copy(fontWeight = FontWeight.SemiBold),
            color = TvTheme.colors.onSurfaceMuted,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** A muted explanatory line under a group of rows. Not focusable. */
@Composable
internal fun NoteText(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        modifier = modifier.fillMaxWidth().padding(horizontal = 26.dp, vertical = TvSpacing.xs),
        style = TvText.caption,
        color = TvTheme.colors.onSurfaceMuted,
    )
}

/**
 * A paired phone (88dp): icon, name, [subtitle] and a Forget pill. Only the pill takes focus.
 */
@Composable
internal fun PhoneRow(
    name: String,
    subtitle: String,
    onForget: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier
            .fillMaxWidth()
            .height(88.dp)
            .background(TvTheme.colors.container, TvRadius.optionShape)
            .padding(horizontal = 26.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        Box(
            Modifier.size(48.dp).background(TvTheme.colors.containerHighest, TvRadius.circle),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Default.Phone, contentDescription = null, tint = TvTheme.colors.onSurfaceMuted, modifier = Modifier.size(24.dp))
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(TvSpacing.xxs)) {
            Text(
                name, style = TvText.settingTitle.copy(fontWeight = FontWeight.SemiBold),
                color = TvTheme.colors.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            Text(
                subtitle, style = TvText.caption, color = TvTheme.colors.onSurfaceMuted,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
        PillButton(
            text = "Forget",
            onClick = onForget,
            style = PillStyle.Secondary,
            height = TvSize.pillHeightCompact,
            contentDescription = "Forget $name",
        )
    }
}
