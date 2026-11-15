package com.playbridge.player.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import com.playbridge.player.ui.components.ListRow
import com.playbridge.player.ui.components.PillButton
import com.playbridge.player.ui.components.PillStyle
import com.playbridge.player.ui.components.TvTone
import com.playbridge.player.ui.theme.TvRadius
import com.playbridge.player.ui.theme.TvSpacing
import com.playbridge.player.ui.theme.TvText
import com.playbridge.player.ui.theme.TvTheme
import com.playbridge.player.ui.theme.ThemedDialog

@Composable
private fun SettingsDialogCard(title: String, width: androidx.compose.ui.unit.Dp, content: @Composable () -> Unit) {
    Column(
        Modifier
            .width(width)
            .background(TvTheme.colors.containerHigh, TvRadius.cardShape)
            .padding(horizontal = 32.dp, vertical = 28.dp)
            .semantics { paneTitle = title },
        verticalArrangement = Arrangement.spacedBy(TvSpacing.l),
    ) {
        Text(title, style = TvText.panelTitle, color = TvTheme.colors.onSurface, modifier = Modifier.semantics { heading() })
        content()
    }
}

/**
 * Picker for a value row: the options as [ListRow]s, the current one selected and focused first.
 * Choosing an option calls [onSelected] and closes the dialog.
 */
@Composable
internal fun OptionPickerDialog(
    title: String,
    options: List<SettingOption>,
    selected: String,
    onSelected: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val initial = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { initial.requestFocus() } }
    ThemedDialog(onDismissRequest = onDismiss) {
        SettingsDialogCard(title, 520.dp) {
            Column(
                Modifier
                    .heightIn(max = 460.dp)
                    .verticalScroll(rememberScrollState())
                    .padding(vertical = TvSpacing.s, horizontal = TvSpacing.s),
                verticalArrangement = Arrangement.spacedBy(TvSpacing.rowGap),
            ) {
                options.forEach { option ->
                    val isSelected = option.value == selected
                    ListRow(
                        title = option.label,
                        subtitle = option.description,
                        selected = isSelected,
                        tone = TvTone.App,
                        height = if (option.description != null) 68.dp else 60.dp,
                        modifier = if (isSelected) Modifier.focusRequester(initial) else Modifier,
                        onClick = {
                            onSelected(option.value)
                            onDismiss()
                        },
                    )
                }
            }
        }
    }
}

/**
 * Single-line text entry. [onSave] gets the text as typed; the caller trims and applies defaults.
 * The field is focused first; Save and Cancel are pills under it.
 */
@Composable
internal fun TextEntryDialog(
    title: String,
    message: String,
    initial: String,
    saveLabel: String,
    onSave: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var text by remember { mutableStateOf(initial) }
    var fieldFocused by remember { mutableStateOf(false) }
    val fieldFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { fieldFocus.requestFocus() } }
    ThemedDialog(onDismissRequest = onDismiss) {
        SettingsDialogCard(title, 560.dp) {
            Text(message, style = TvText.caption, color = TvTheme.colors.onSurfaceMuted)
            BasicTextField(
                value = text,
                onValueChange = { text = it },
                singleLine = true,
                textStyle = TvText.row.copy(color = TvTheme.colors.onSurface),
                cursorBrush = SolidColor(TvTheme.colors.accent),
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(fieldFocus)
                    .onFocusChanged { fieldFocused = it.isFocused }
                    .background(TvTheme.colors.container, TvRadius.rowShape)
                    .border(3.dp, if (fieldFocused) TvTheme.colors.focusRing else Color.Transparent, TvRadius.rowShape)
                    .padding(horizontal = 20.dp, vertical = 16.dp),
            )
            Row(
                Modifier.fillMaxWidth().padding(top = TvSpacing.s),
                horizontalArrangement = Arrangement.spacedBy(TvSpacing.m, Alignment.End),
            ) {
                PillButton("Cancel", onDismiss, style = PillStyle.Secondary, height = 48.dp)
                PillButton(saveLabel, { onSave(text) }, height = 48.dp)
            }
        }
    }
}

/** Yes/no question. Cancel is focused first so a stray OK press never confirms. */
@Composable
internal fun ConfirmDialog(
    title: String,
    message: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    cancelLabel: String = "Cancel",
) {
    val cancelFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { cancelFocus.requestFocus() } }
    ThemedDialog(onDismissRequest = onDismiss) {
        SettingsDialogCard(title, 520.dp) {
            Text(message, style = TvText.caption, color = TvTheme.colors.onSurfaceMuted)
            Row(
                Modifier.fillMaxWidth().padding(top = TvSpacing.s),
                horizontalArrangement = Arrangement.spacedBy(TvSpacing.m, Alignment.End),
            ) {
                PillButton(cancelLabel, onDismiss, style = PillStyle.Secondary, height = 48.dp, modifier = Modifier.focusRequester(cancelFocus))
                PillButton(confirmLabel, onConfirm, height = 48.dp)
            }
        }
    }
}

/** A dialog that hosts arbitrary [content] under a [title] with a Close pill, for existing sections. */
@Composable
internal fun ContentDialog(
    title: String,
    onDismiss: () -> Unit,
    content: @Composable () -> Unit,
) {
    ThemedDialog(onDismissRequest = onDismiss) {
        SettingsDialogCard(title, 720.dp) {
            Column(
                Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState()).padding(TvSpacing.s),
            ) { content() }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                PillButton("Close", onDismiss, style = PillStyle.Secondary, height = 48.dp)
            }
        }
    }
}
