package com.playbridge.sender.cast.remote.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Done
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import com.playbridge.sender.cast.remote.RemoteAction
import com.playbridge.sender.cast.remote.AtvKeyboardEditor
import com.playbridge.sender.cast.remote.RemoteCommandResult
import com.playbridge.sender.cast.remote.RemoteDialogVolumeKeys
import com.playbridge.sender.cast.remote.RemoteKey

/**
 * Keyboard sheet opened from nav ⌨:
 * - Live text sent to TV as typed
 * - IME composition handled safely without broadcasting incomplete intermediate composition
 * - Delete button removes code points and synchronizes draft
 * - Enter button sends key_enter
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun RemoteKeyboardSheet(
    dispatch: (RemoteAction) -> Unit,
    onDismiss: () -> Unit = { dispatch(RemoteAction.CloseSheet) },
    imeKeys: Boolean = false,
    execute: suspend (RemoteAction) -> RemoteCommandResult = { action -> dispatch(action); RemoteCommandResult.Submitted },
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        RemoteDialogVolumeKeys()
        KeyboardSheetContent(
            dispatch = dispatch, imeKeys = imeKeys, execute = execute,
            focusReady = sheetState.currentValue == SheetValue.Expanded && sheetState.targetValue == SheetValue.Expanded,
        )
    }
}

@Composable
internal fun KeyboardSheetContent(
    dispatch: (RemoteAction) -> Unit,
    imeKeys: Boolean = false,
    execute: suspend (RemoteAction) -> RemoteCommandResult = { action -> dispatch(action); RemoteCommandResult.Submitted },
    focusReady: Boolean = true,
) {
    // A sheet never changes text authority while an edit may have reached the TV.
    val fullControl = remember { imeKeys }
    val scope = rememberCoroutineScope()
    val executeState = rememberUpdatedState(execute)
    val editor = remember(fullControl) { if (fullControl) AtvKeyboardEditor(scope) { executeState.value(it) } else null }
    DisposableEffect(editor) { onDispose { editor?.close() } }
    val draft = editor?.state?.collectAsState()?.value
    var textFieldValue by remember { mutableStateOf(TextFieldValue("")) }
    var lastCommittedText by remember { mutableStateOf("") }
    val focusRequester = remember { FocusRequester() }
    val controlsInView = remember { BringIntoViewRequester() }
    val imeBottom = WindowInsets.ime.getBottom(LocalDensity.current)

    fun commitText(newText: String) {
        if (newText == lastCommittedText) return
        lastCommittedText = newText
        dispatch(RemoteAction.ReplaceText(newText))
    }

    fun handleDelete() {
        if (editor != null) {
            editor.delete()
            return
        }
        val current = textFieldValue.text
        if (current.isNotEmpty()) {
            val newLength = current.offsetByCodePoints(current.length, -1)
            val shortened = current.substring(0, newLength)
            textFieldValue = TextFieldValue(shortened, selection = TextRange(shortened.length))
            commitText(shortened)
            // Replacement already removes the code point; a second delete would remove extra text.
        }
    }

    fun handleClear() {
        if (editor != null) {
            editor.clear()
            return
        }
        textFieldValue = TextFieldValue("")
        commitText("")
    }

    fun handleEnter() {
        if (editor != null) editor.enter() else dispatch(RemoteAction.Key(RemoteKey.Enter))
    }

    val value = draft?.value ?: textFieldValue
    val enabled = LocalRemoteControlsEnabled.current && draft?.failed != true

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .imePadding()
            .navigationBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(start = 24.dp, end = 24.dp, top = 8.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = "TV Keyboard",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = "Tap a text box on the TV, then type here — it's sent as you type.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        // Scroll the field, its supporting message and action row as one editing area.
        Column(Modifier.bringIntoViewRequester(controlsInView), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            OutlinedTextField(
                enabled = enabled,
                value = value,
                onValueChange = { newValue ->
                    if (editor != null) {
                        editor.update(newValue)
                        return@OutlinedTextField
                    }
                    textFieldValue = newValue
                    // Only broadcast to TV when composition is complete (not in the middle of IME candidate composition)
                    if (newValue.composition == null) {
                        commitText(newValue.text)
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("remote.keyboardField")
                    .focusRequester(focusRequester),
                placeholder = { Text("Type to send to TV…") },
                singleLine = true,
                isError = draft?.limitReached == true || draft?.failed == true,
                supportingText = if (editor == null) null else {
                    {
                        when {
                            draft?.failed == true -> Text("Text could not be sent. Close and reopen the keyboard.")
                            draft?.limitReached == true -> Text("Text is limited to 64 characters.")
                            draft?.busy == true -> Text("Wait for the TV before editing again.")
                        }
                    }
                },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                keyboardActions = KeyboardActions(onGo = { handleEnter() }),
                trailingIcon = {
                    if (value.text.isNotEmpty()) {
                        IconButton(
                            enabled = enabled,
                            onClick = { handleClear() },
                        ) {
                            Icon(Icons.Default.Clear, contentDescription = "Clear text")
                        }
                    }
                },
                shape = RoundedCornerShape(12.dp),
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedButton(
                    enabled = enabled && value.text.isNotEmpty(),
                    onClick = { handleDelete() },
                    modifier = Modifier.weight(1f),
                ) {
                    Text("Delete")
                }

                Button(
                    enabled = enabled,
                    onClick = { handleEnter() },
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(Icons.Default.Done, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Enter")
                }
            }
        }
    }

    LaunchedEffect(focusReady) {
        if (focusReady) {
            focusRequester.requestFocus()
        }
    }
    LaunchedEffect(focusReady, imeBottom, draft?.limitReached, draft?.failed) {
        if (focusReady) {
            // Let the changed IME inset/supporting text reach layout before measuring the editing area.
            withFrameNanos { }
            controlsInView.bringIntoView()
        }
    }
}
