package com.playbridge.sender.cast.dlna

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DlnaActionFailureTest {
    @Test
    fun mapsStructuredUpnpFaultToDiagnosticsFailure() {
        val description = "Rejected https://media.example/private?token=secret " + "x".repeat(160)
        val failure = dlnaActionFailureFromEvent(
            actionName = "load",
            upnp = JSONObject()
                .put("action", "SetAVTransportURI")
                .put("code", 501)
                .put("http_status", 500)
                .put("description", description),
        )

        requireNotNull(failure)
        assertEquals("SetAVTransportURI", failure.actionName)
        assertEquals(500, failure.httpStatus)
        assertEquals(501, failure.upnpCode)
        assertEquals(description, failure.upnpDescription)
        assertTrue(failure.message!!.contains("[URL redacted]"))
        assertFalse(failure.message!!.contains("https://media.example/private"))
        assertTrue(failure.message!!.length < 180)
    }

    @Test
    fun mapsHttpOnlyActionFailureAndUsesOperationAsActionFallback() {
        val failure = dlnaActionFailureFromEvent(
            actionName = "GetPositionInfo",
            upnp = JSONObject()
                .put("http_status", 500)
                .put("code", JSONObject.NULL),

        )

        requireNotNull(failure)
        assertEquals("GetPositionInfo", failure.actionName)
        assertEquals(500, failure.httpStatus)
        assertNull(failure.upnpCode)
    }

    @Test
    fun mapsTransportFailureWithNullableHttpFields() {
        val failure = dlnaActionFailureFromEvent(
            actionName = "GetPositionInfo",
            upnp = JSONObject()
                .put("action", "GetPositionInfo")
                .put("code", JSONObject.NULL)
                .put("http_status", JSONObject.NULL),
        )

        requireNotNull(failure)
        assertNull(failure.upnpCode)
        assertNull(failure.httpStatus)
    }

    @Test
    fun ignoresEventsWithoutStructuredUpnpFailure() {
        assertNull(dlnaActionFailureFromEvent("load", null))
        assertNull(dlnaActionFailureFromEvent(null, JSONObject().put("http_status", 500)))
    }
}
