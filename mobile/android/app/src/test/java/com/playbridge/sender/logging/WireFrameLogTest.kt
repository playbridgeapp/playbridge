package com.playbridge.sender.logging

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WireFrameLogTest {
    @Test fun authLoggingOmitsDescriptorAndCredentialsEvenInDebugFrames() {
        val frame = """{"type":"auth_response","success":true,"token":"TEST_SECRET","capabilities":{"version":1,"surfaces":{"PRIVATE_SURFACE":{"engines":{}}}}}"""
        assertFalse(WireFrameLog.summary(frame).contains("PRIVATE_SURFACE"))
        assertFalse(WireFrameLog.redact(frame).contains("PRIVATE_SURFACE"))
        assertFalse(WireFrameLog.redact(frame).contains("TEST_SECRET"))
        assertFalse(WireFrameLog.redact(frame).contains("capabilities"))
    }

    @Test fun summaryKeepsTheEnvelopeTypeAndDropsThePayload() {
        val frame = """{"type":"status","title":"Secret Movie","position":5000}"""
        val summary = WireFrameLog.summary(frame)
        assertEquals("type=status bytes=${frame.toByteArray(Charsets.UTF_8).size}", summary)
        assertFalse(summary.contains("Secret"))
        assertFalse(summary.contains("5000"))
    }

    @Test fun summaryCountsUtf8BytesAndLabelsAFrameWithNoType() {
        val frame = """{"title":"映画"}"""
        assertEquals("type=unknown bytes=${frame.toByteArray(Charsets.UTF_8).size}", WireFrameLog.summary(frame))
        assertFalse(WireFrameLog.summary(frame).contains("映画"))
    }

    @Test fun redactDropsStreamQueriesAndAuthFields() {
        val frame = """{"type":"command","action":"playlist","payload":{"items":[{"url":"https://cdn.example/v.m3u8?token=secret&sig=abc","headers":{"Cookie":"sid=user-secret","Authorization":"Bearer user-secret","Referer":"https://videos.example/watch?v=1"}}]}}"""
        val redacted = WireFrameLog.redact(frame)
        assertFalse(redacted.contains("secret"))
        assertFalse(redacted.contains("sid=user-secret"))
        assertFalse(redacted.contains("Bearer user-secret"))
        assertFalse(redacted.contains("v.m3u8"))
        assertFalse(redacted.contains("videos.example"))
        assertTrue(redacted.contains("command"))
    }
}
