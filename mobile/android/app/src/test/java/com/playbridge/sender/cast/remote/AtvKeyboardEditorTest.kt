package com.playbridge.sender.cast.remote

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AtvKeyboardEditorTest {
    @Test fun compositionDeleteDoesNotDeleteCommittedTvCharacter() = runTest {
        val commands = mutableListOf<RemoteAction>()
        val editor = AtvKeyboardEditor(backgroundScope) { commands += it; RemoteCommandResult.Submitted }
        editor.update(TextFieldValue("a", TextRange(1)))
        runCurrent()
        editor.update(TextFieldValue("ab", TextRange(2), TextRange(1, 2)))
        runCurrent()
        assertEquals(1, commands.size)
        editor.delete()
        runCurrent()
        assertEquals(listOf(RemoteAction.EditTvText(0, "a")), commands)
        assertEquals("a", editor.state.value.value.text)
        editor.update(TextFieldValue("ac", TextRange(2)))
        runCurrent()
        assertEquals(RemoteAction.EditTvText(0, "c"), commands.last())
        editor.close()
    }

    @Test fun selectionDeleteAndSurrogateBackspaceDiffFromCommittedBaseline() = runTest {
        val commands = mutableListOf<RemoteAction>()
        val editor = AtvKeyboardEditor(backgroundScope) { commands += it; RemoteCommandResult.Submitted }
        editor.update(TextFieldValue("a😀b", TextRange(1, 3)))
        runCurrent()
        editor.delete()
        runCurrent()
        assertEquals(RemoteAction.EditTvText(2, "b"), commands.last())
        editor.update(TextFieldValue("ab😀", TextRange(4)))
        runCurrent()
        editor.delete()
        runCurrent()
        assertEquals("ab", editor.state.value.value.text)
        assertEquals(RemoteAction.EditTvText(1, ""), commands.last())
        editor.close()
    }

    @Test fun rejectsEightyCharacterDraftAndClearsAllSixtyFourCommittedCharacters() = runTest {
        val commands = mutableListOf<RemoteAction>()
        val editor = AtvKeyboardEditor(backgroundScope) { commands += it; RemoteCommandResult.Submitted }
        editor.update(TextFieldValue("x".repeat(80)))
        runCurrent()
        assertTrue(editor.state.value.limitReached)
        assertEquals("", editor.state.value.value.text)
        assertTrue(commands.isEmpty())
        editor.update(TextFieldValue("x".repeat(64)))
        runCurrent()
        editor.clear()
        runCurrent()
        assertEquals(RemoteAction.EditTvText(64, ""), commands.last())
        assertEquals("", editor.state.value.value.text)
        editor.close()
    }

    @Test fun queuedReplacementAndEnterWaitForAcknowledgement() = runTest {
        val firstAck = CompletableDeferred<Unit>()
        val commands = mutableListOf<RemoteAction>()
        val editor = AtvKeyboardEditor(backgroundScope) {
            commands += it
            if (commands.size == 1) firstAck.await()
            RemoteCommandResult.Submitted
        }
        editor.update(TextFieldValue("a"))
        editor.update(TextFieldValue("b"))
        editor.enter()
        runCurrent()
        assertEquals(listOf(RemoteAction.EditTvText(0, "a")), commands)
        firstAck.complete(Unit)
        runCurrent()
        assertEquals(listOf(RemoteAction.EditTvText(0, "a"), RemoteAction.EditTvText(1, "b"), RemoteAction.EditTvText(0, "", true)), commands)
        editor.close()
    }

    @Test fun ambiguousFailureStopsQueuedEditsWithoutAdvancingOrRetrying() = runTest {
        val commands = mutableListOf<RemoteAction>()
        val editor = AtvKeyboardEditor(backgroundScope) { commands += it; RemoteCommandResult.Failed("closed") }
        editor.update(TextFieldValue("a"))
        editor.update(TextFieldValue("b"))
        runCurrent()
        assertTrue(editor.state.value.failed)
        editor.clear()
        editor.enter()
        runCurrent()
        assertEquals(1, commands.size)
        editor.close()
    }
}
