package com.playbridge.sender.cast

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RemoteLanguageTokenTest {
    @Test fun finnishTokens() {
        for (token in listOf("finnish", "fi", "fin", " Finnish ")) assertEquals("Finnish", languageOf(token))
        assertNull(languageOf("finish"))
    }
}
