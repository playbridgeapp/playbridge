package com.playbridge.sender.diagnostics

import com.playbridge.sender.logging.DebugNetworkLogger
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LogcatReaderTest {
    @Test
    fun `raw network diagnostics are excluded from in-app diagnostics`() {
        val line = logLine(tag = DebugNetworkLogger.LOGCAT_TAG)

        assertFalse(LogcatReader.isSafeForDiagnostics(line))
    }

    @Test
    fun `ordinary application logs remain visible in diagnostics`() {
        val line = logLine(tag = "ConnectionViewModel")

        assertTrue(LogcatReader.isSafeForDiagnostics(line))
    }

    private fun logLine(tag: String) = LogcatReader.LogLine(
        timestamp = "07-29 14:30:00.000",
        level = LogcatReader.Level.DEBUG,
        tag = tag,
        message = "message",
        raw = "raw",
    )
}
