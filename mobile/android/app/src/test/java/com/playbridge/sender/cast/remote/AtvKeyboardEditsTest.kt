package com.playbridge.sender.cast.remote

import com.playbridge.sender.cast.atv.ATV_KEY_SHORT
import com.playbridge.sender.cast.atv.atvKeyName
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AtvKeyboardEditsTest {
    @Test fun insertsSuffixAndDeletesCodePoints() {
        assertEquals(listOf(RemoteAction.ReplaceText("h")), atvKeyboardEdits("", "h"))
        assertEquals(listOf(RemoteAction.ReplaceText("e")), atvKeyboardEdits("h", "he"))
        assertEquals(listOf(RemoteAction.DeleteText), atvKeyboardEdits("he", "h"))
        val cleared = atvKeyboardEdits("he", "")!!
        assertTrue(cleared.all { it == RemoteAction.DeleteText })
        assertEquals(2, cleared.size)
        assertEquals(null, atvKeyboardEdits("x".repeat(80), ""))
        assertEquals(ATV_TEXT_DELETE_CAP, atvKeyboardEdits("x".repeat(64), "")!!.size)
    }

    @Test fun enterDeleteAndArrowsUseShortAndroidNames() {
        assertEquals("KEYCODE_ENTER", atvKeyName(RemoteKey.Enter))
        assertEquals("KEYCODE_DEL", atvKeyName(RemoteKey.Delete))
        assertEquals("DPAD_UP", atvKeyName(RemoteKey.Up))
        assertEquals("DPAD_DOWN", atvKeyName(RemoteKey.Down))
        assertEquals("DPAD_LEFT", atvKeyName(RemoteKey.Left))
        assertEquals("DPAD_RIGHT", atvKeyName(RemoteKey.Right))
        assertEquals("short", ATV_KEY_SHORT)
    }
}
