package com.playbridge.shared.browser

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class FocusedInputTest {
    @Test
    fun payloadKeepsBase64AndEmptyClear() {
        assertEquals("", focusTextPayload(""))
        assertEquals("w6nwn5GL", focusTextPayload("w6nwn5GL"))
        assertEquals("abc+/=", focusTextPayload("abc+/="))
        assertEquals("hello", focusTextPayload("hello"))
        assertNull(focusTextPayload("abc\""))
        assertNull(focusTextPayload("abc'"))
        assertNull(focusTextPayload("a b"))
        assertNull(focusTextPayload("a\\b"))
    }

    @Test
    fun resultLogDropsAnythingButTheFixedCodes() {
        assertEquals("ok", focusResultToken("ok"))
        assertEquals("no_focus", focusResultToken("no_focus"))
        assertEquals("err", focusResultToken("err"))
        assertEquals("err", focusResultToken("typed secret"))
        assertEquals("err", focusResultToken("https://videos.example/a"))
    }
}
