package com.playbridge.sender.cast.remote.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import androidx.compose.material3.Text
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
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import com.playbridge.sender.cast.remote.RemoteAction
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
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        RemoteDialogVolumeKeys()
        KeyboardSheetContent(dispatch = dispatch)
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
internal fun KeyboardSheetContent(
    dispatch: (RemoteAction) -> Unit,
) {
    var textFieldValue by remember { mutableStateOf(TextFieldValue("")) }
    var lastCommittedText by remember { mutableStateOf("") }
    val focusRequester = remember { FocusRequester() }

    fun commitText(newText: String) {
        if (newText != lastCommittedText) {
            lastCommittedText = newText
            dispatch(RemoteAction.ReplaceText(newText))
        }
    }

    fun handleDelete() {
        val current = textFieldValue.text
        if (current.isNotEmpty()) {
            val newLength = current.offsetByCodePoints(current.length, -1)
            val shortened = current.substring(0, newLength)
            textFieldValue = TextFieldValue(shortened, selection = TextRange(shortened.length))
            commitText(shortened)
            dispatch(RemoteAction.DeleteText)
        }
    }

    fun handleClear() {
        textFieldValue = TextFieldValue("")
        commitText("")
    }

    fun handleEnter() {
        dispatch(RemoteAction.Key(RemoteKey.Enter))
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 8.dp),
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

        OutlinedTextField(
            enabled = LocalRemoteControlsEnabled.current,
            value = textFieldValue,
            onValueChange = { newValue ->
                textFieldValue = newValue
                // Only broadcast to TV when composition is complete (not in the middle of IME candidate composition)
                if (newValue.composition == null) {
                    commitText(newValue.text)
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .focusRequester(focusRequester),
            placeholder = { Text("Type to send to TV…") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
            keyboardActions = KeyboardActions(onGo = { handleEnter() }),
            trailingIcon = {
                if (textFieldValue.text.isNotEmpty()) {
                    IconButton(
                        enabled = LocalRemoteControlsEnabled.current,
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
                enabled = LocalRemoteControlsEnabled.current && textFieldValue.text.isNotEmpty(),
                onClick = { handleDelete() },
                modifier = Modifier.weight(1f),
            ) {
                Text("Delete")
            }

            Button(
                enabled = LocalRemoteControlsEnabled.current,
                onClick = { handleEnter() },
                modifier = Modifier.weight(1f),
            ) {
                Icon(Icons.Default.Done, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("Enter")
            }
        }
    }

    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
    }
}
