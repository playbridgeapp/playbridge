package com.playbridge.sender.cast.remote

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

internal data class AtvKeyboardDraft(
    val value: TextFieldValue = TextFieldValue(""),
    val limitReached: Boolean = false,
    val failed: Boolean = false,
    val busy: Boolean = false,
)

/** Bounded drafts and sequential acknowledgements keep the phone baseline consistent with the TV. */
internal class AtvKeyboardEditor(scope: CoroutineScope, private val execute: suspend (RemoteAction) -> RemoteCommandResult) {
    private data class Edit(val text: String, val enter: Boolean)
    private val edits = Channel<Edit>(capacity = 64)
    private val draft = MutableStateFlow(AtvKeyboardDraft())
    val state = draft.asStateFlow()
    private var committed = ""
    private val worker = scope.launch {
        for (edit in edits) {
            val actions = atvKeyboardEdits(committed, edit.text)
            if (actions == null) {
                draft.value = draft.value.copy(limitReached = true)
                continue
            }
            val command = RemoteAction.EditTvText(
                deletes = actions.count { it == RemoteAction.DeleteText },
                text = actions.filterIsInstance<RemoteAction.ReplaceText>().singleOrNull()?.text.orEmpty(),
                enter = edit.enter,
            )
            if (actions.isEmpty() && !edit.enter) continue
            val result = try {
                execute(command)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                RemoteCommandResult.Failed("Could not send text")
            }
            if (result != RemoteCommandResult.Submitted && result != RemoteCommandResult.Confirmed) {
                draft.value = draft.value.copy(failed = true)
                edits.close()
                break // An ambiguous partial failure is never retried or redirected.
            }
            committed = edit.text
        }
    }

    fun update(value: TextFieldValue) {
        if (draft.value.failed) return
        if (value.text.codePointCount(0, value.text.length) > ATV_TEXT_DELETE_CAP) {
            draft.value = draft.value.copy(limitReached = true)
            return
        }
        if (value.composition == null && !enqueue(value.text, false)) return
        draft.value = AtvKeyboardDraft(value)
    }

    fun delete() {
        val value = draft.value.value
        val start = value.selection.min
        val end = value.selection.max
        if (start == 0 && start == end) return
        val from = if (start != end) start else value.text.offsetByCodePoints(start, -1)
        val shortened = value.text.removeRange(from, end)
        update(TextFieldValue(shortened, TextRange(from)))
    }

    fun clear() = update(TextFieldValue(""))

    fun enter() {
        val value = draft.value.value
        if (!draft.value.failed && enqueue(value.text, true)) {
            draft.value = AtvKeyboardDraft(value.copy(composition = null))
        }
    }

    private fun enqueue(text: String, enter: Boolean): Boolean {
        if (edits.trySend(Edit(text, enter)).isSuccess) return true
        draft.value = draft.value.copy(busy = true)
        return false
    }

    fun close() {
        edits.cancel()
        worker.cancel()
    }
}
