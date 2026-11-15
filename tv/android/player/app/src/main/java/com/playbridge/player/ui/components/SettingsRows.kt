package com.playbridge.player.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.background
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import com.playbridge.player.ui.theme.TvRadius
import com.playbridge.player.ui.theme.TvSize
import com.playbridge.player.ui.theme.TvSpacing
import com.playbridge.player.ui.theme.TvText
import com.playbridge.player.ui.theme.TvTheme

@Composable
private fun SettingsRowFrame(
    title: String,
    description: String?,
    onClick: () -> Unit,
    modifier: Modifier,
    role: Role,
    contentDescription: String?,
    semanticsExtra: Modifier,
    trailing: @Composable (focused: Boolean) -> Unit,
) {
    val c = tvControlColors(TvTone.App, row = true)
    TvFocusSurface(
        onClick = onClick,
        modifier = modifier.fillMaxWidth().heightIn(min = TvSize.settingsRow).then(semanticsExtra),
        shape = TvRadius.optionShape,
        container = c.container,
        focusedContainer = c.focusedContainer,
        ringColor = c.ring,
        role = role,
        contentDescription = contentDescription,
        contentAlignment = Alignment.CenterStart,
    ) { focused ->
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 26.dp, vertical = TvSpacing.m),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(TvSpacing.xxl),
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(TvSpacing.xxs)) {
                Text(title, style = TvText.settingTitle, color = if (focused) c.focusedContent else c.content)
                if (description != null) {
                    Text(description, style = TvText.caption, color = if (focused) c.focusedContent else c.muted)
                }
            }
            trailing(focused)
        }
    }
}

/**
 * Settings row with a trailing on/off switch (56x32dp, fixed). The whole row toggles on OK.
 * The switch is drawn from fixed boxes; only colours change with [checked] and focus.
 */
@Composable
fun SwitchRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    description: String? = null,
    enabled: Boolean = true,
) {
    SettingsRowFrame(
        title = title,
        description = description,
        onClick = { if (enabled) onCheckedChange(!checked) },
        modifier = modifier,
        role = Role.Switch,
        contentDescription = null,
        semanticsExtra = Modifier.semantics {
            toggleableState = if (checked) ToggleableState.On else ToggleableState.Off
            stateDescription = if (checked) "On" else "Off"
        },
    ) { focused ->
        val track = when {
            checked && focused -> TvTheme.colors.onFocusFill
            checked -> TvTheme.colors.accent
            focused -> TvTheme.colors.onFocusFill.copy(alpha = 0.35f)
            else -> TvTheme.colors.outline
        }
        val thumb = when {
            checked && focused -> TvTheme.colors.focusFill
            checked -> TvTheme.colors.onAccent
            focused -> TvTheme.colors.focusFill
            else -> TvTheme.colors.onSurfaceMuted
        }
        Box(
            Modifier.size(TvSize.switchWidth, TvSize.switchHeight).background(track, CircleShape).padding(4.dp),
            contentAlignment = if (checked) Alignment.CenterEnd else Alignment.CenterStart,
        ) {
            Box(Modifier.size(TvSize.switchThumb).background(thumb, CircleShape))
        }
    }
}

/** Settings row with a title, optional [description] and a right-aligned accent [value]. Opens a picker on OK. */
@Composable
fun ValueRow(
    title: String,
    value: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    description: String? = null,
) {
    SettingsRowFrame(
        title = title,
        description = description,
        onClick = onClick,
        modifier = modifier,
        role = Role.Button,
        contentDescription = null,
        semanticsExtra = Modifier.semantics { stateDescription = value },
    ) { focused ->
        Text(
            value, style = TvText.row.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold),
            color = if (focused) TvTheme.colors.onFocusFill else TvTheme.colors.accent,
            maxLines = 1, overflow = TextOverflow.Ellipsis,
            // About 40% of a settings row: a long value is cut off instead of squeezing the description.
            modifier = Modifier.widthIn(max = 320.dp),
        )
    }
}
