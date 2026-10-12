package com.playbridge.sender.cast.atv

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

class AtvRemoteClientTest {
    @Test fun abiMismatchDoesNotStart() = runBlocking {
        val native = FakeAtvNative(abi = 2)
        val client = AtvRemoteClient(native, CoroutineScope(SupervisorJob() + Dispatchers.IO))
        try {
            val error = runCatching { client.discover(0) }.exceptionOrNull()
            assertTrue(error is AtvCommandException)
            assertFalse(native.started)
        } finally {
            client.close()
        }
        assertFalse(native.freed)
    }

    @Test fun parsesDevicesStateErrorAndPairedWithoutTheNativeLibrary() {
        val fingerprint = "cd".repeat(32)
        val devices = parseDevices(JSONObject("""{"event":"devices","request_id":"scan","devices":[{"name":"TV","host":"192.168.1.22","port":6466,"addresses":["192.168.1.22"],"txt":[["bt","C0:E5:DA:D8:C3:5F"]]}]}"""))
        assertEquals("C0:E5:DA:D8:C3:5F", devices.single().bluetoothMac)
        val state = (parseAtvEvent("""{"event":"state","request_id":"connect","state":{"is_on":false,"volume_level":3,"volume_max":100,"volume_muted":false,"volume_revision":1,"current_app":null,"model":"Hisense","vendor":null}}""") as AtvEvent.State).state
        assertFalse(state.isOn)
        assertEquals(3, state.volumeLevel)
        assertNull(state.currentApp)
        val error = parseAtvEvent("""{"event":"error","request_id":"pair","kind":"bad_code","message":"pairing code was rejected"}""") as AtvEvent.Error
        assertEquals("bad_code", error.kind)
        assertFalse(error.message.contains("D9A1B2"))
        val paired = parsePaired(JSONObject("""{"event":"paired","request_id":"pair","server_cert_der_b64":"SECRET","fingerprint":"$fingerprint","mac":"c0:e5:da:d8:c3:5f","server_name":"Living Room"}"""))
        assertEquals(fingerprint, paired.fingerprint)
        assertEquals("c0:e5:da:d8:c3:5f", paired.mac)
        val invalid = runCatching { parseAtvEvent("not-json CERTIFICATE") }.exceptionOrNull() as AtvCommandException
        assertEquals("invalid Android TV remote event", invalid.message)
        assertFalse(invalid.message!!.contains("CERTIFICATE"))
    }

    @Test fun commandsCarryExplicitNullsAndCorrelateByRequestId() = runBlocking {
        val native = FakeAtvNative()
        val client = AtvRemoteClient(native, CoroutineScope(SupervisorJob() + Dispatchers.IO))
        try {
            client.key(name = "HOME", code = null, direction = "short")
            val key = JSONObject(native.submitted.last())
            assertEquals("key", key.getString("command"))
            assertTrue(key.isNull("code"))
            assertEquals("HOME", key.getString("name"))
            assertEquals("short", key.getString("direction"))
            client.connect("192.168.1.22", "CERT", "KEY", "ab".repeat(32))
            val connect = JSONObject(native.submitted.last { JSONObject(it).getString("command") == "connect" })
            assertTrue(connect.isNull("port"))
            assertEquals("192.168.1.22", connect.getString("host"))
            withTimeout(2_000) {
                while (client.television.value?.volumeLevel != 3) delay(10)
            }
            assertEquals(false, client.television.value?.isOn)
        } finally {
            client.close()
        }
        assertTrue(native.cancelled)
        assertTrue(native.freed)
    }
}

private class FakeAtvNative(private val abi: Int = ATV_REMOTE_ABI) : AtvNativeBoundary {
    var started = false
    var cancelled = false
    var freed = false
    val submitted = mutableListOf<String>()
    private val events = LinkedBlockingQueue<String>()

    override fun abiVersion(): Int = abi
    override fun start(): Long {
        started = true
        return 7L
    }
    override fun submitJson(handle: Long, commandJson: String): Boolean {
        submitted += commandJson
        val command = JSONObject(commandJson)
        val id = command.getString("request_id")
        val event = when (command.getString("command")) {
            "key" -> """{"event":"operation","request_id":"$id","operation":"key","ok":true}"""
            "connect" -> """{"event":"connected","request_id":"$id"}"""
            else -> """{"event":"operation","request_id":"$id","operation":"other","ok":true}"""
        }
        events.add(event)
        if (command.getString("command") == "connect") {
            events.add("""{"event":"state","request_id":"$id","state":{"is_on":false,"volume_level":3,"volume_max":100,"volume_muted":false,"volume_revision":1,"current_app":null,"model":"Hisense","vendor":null}}""")
        }
        return true
    }
    override fun nextEvent(handle: Long, waitMs: Long): String? = events.poll(waitMs, TimeUnit.MILLISECONDS)
    override fun cancel(handle: Long) { cancelled = true }
    override fun free(handle: Long) { freed = true }
}
