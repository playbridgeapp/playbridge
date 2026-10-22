package com.playbridge.shared.player

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MpvFailureLogPolicyTest {
    @Test fun knownNativeErrorsBecomeCodesWithoutKeepingPrivateText() {
        val cases = mapOf(
            "Failed to recognize file format. https://private.example/token" to "mpv_unsupported_format",
            "Failed to open https://private.example/token" to "mpv_load_failed",
            "HTTP error 403 Forbidden https://private.example/token" to "mpv_http_403",
            "Failed to resolve hostname private.example" to "mpv_network_failed",
            "Failed to initialize a decoder private-token" to "mpv_decoder_failure",
        )
        for ((text, code) in cases) assertEquals(code, MpvFailureLogPolicy.classify("cplayer", 20, text))
        assertTrue(MpvFailureLogPolicy.isTerminal("cplayer", "Failed to recognize file format"))
        assertFalse(MpvFailureLogPolicy.isTerminal("ffmpeg/https", "HTTP error 403"))
    }
    @Test fun warningsOtherModulesAndUrlDigitsAreNotFailures() {
        assertNull(MpvFailureLogPolicy.classify("cplayer", 30, "Failed to recognize file format"))
        assertNull(MpvFailureLogPolicy.classify("sub", 20, "Failed to open subtitle"))
        assertNull(MpvFailureLogPolicy.classify("cplayer", 20, "https://private.example/http_403"))
    }
}
