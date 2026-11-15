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

    @Test fun decoderLogsAreNeitherCategoriesNorTerminalFailures() {
        for (prefix in listOf("vd", "ad", "cplayer", "ffmpeg", "ffmpeg/video")) {
            for (text in listOf("Failed to initialize a decoder for codec", "Error while decoding frame!")) {
                for (level in listOf(10, 20, 30)) {
                    assertNull(MpvFailureLogPolicy.classify(prefix, level, text))
                }
                assertFalse(MpvFailureLogPolicy.isTerminal(prefix, text))
            }
        }
    }

    @Test fun truncationAndNetworkLogsAreDetectedAtWarnOrHigherOnlyForFfmpegAndCore() {
        assertTrue(MpvFailureLogPolicy.isTruncationOrNetworkLog("ffmpeg", 30, "Stream ends prematurely at 1024, should be 8192"))
        assertTrue(MpvFailureLogPolicy.isTruncationOrNetworkLog("ffmpeg/mov", 20, "stream 0, offset 0x100: partial file"))
        assertTrue(MpvFailureLogPolicy.isTruncationOrNetworkLog("ffmpeg/http", 30, "Will reconnect at 4096 in 1 second(s), error=End of file."))
        assertTrue(MpvFailureLogPolicy.isTruncationOrNetworkLog("ffmpeg/https", 20, "Failed to reconnect at 4096."))
        assertTrue(MpvFailureLogPolicy.isTruncationOrNetworkLog("ffmpeg/https", 20, "HTTP error 503 Service Unavailable"))
        assertTrue(MpvFailureLogPolicy.isTruncationOrNetworkLog("ffmpeg/https", 20, "HTTP error 429 Too Many Requests"))
        assertTrue(MpvFailureLogPolicy.isTruncationOrNetworkLog("cplayer", 20, "Operation timed out"))
        assertFalse(MpvFailureLogPolicy.isTruncationOrNetworkLog("ffmpeg/https", 20, "HTTP error 404 Not Found"))
        assertFalse(MpvFailureLogPolicy.isTruncationOrNetworkLog("ffmpeg", 40, "Stream ends prematurely"))
        assertFalse(MpvFailureLogPolicy.isTruncationOrNetworkLog("sub", 20, "partial file"))
        assertFalse(MpvFailureLogPolicy.isTruncationOrNetworkLog("cplayer", 30, "Failed to recognize file format"))
    }
}
