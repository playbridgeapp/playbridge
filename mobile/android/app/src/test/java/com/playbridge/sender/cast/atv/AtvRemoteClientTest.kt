package com.playbridge.sender.cast.atv

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
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
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class AtvRemoteClientTest {
    @Test fun abiMismatchDoesNotStart() = runBlocking<Unit> {
        val native = FakeAtvNative(abi = 2)
        val client = AtvRemoteClient(native, CoroutineScope(SupervisorJob() + Dispatchers.IO), phoneUUID = { "fixture-phone-uuid" })
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

    @Test fun commandsCarryExplicitNullsAndCorrelateByRequestId() = runBlocking<Unit> {
        val native = FakeAtvNative()
        val client = AtvRemoteClient(native, CoroutineScope(SupervisorJob() + Dispatchers.IO), phoneUUID = { "fixture-phone-uuid" })
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

    @Test fun pairingPreparationPassesExistingCredentialsAndRetainsNativeNameWithoutLogging() = runBlocking<Unit> {
        val native = FakeAtvNative()
        val client = AtvRemoteClient(native, CoroutineScope(SupervisorJob() + Dispatchers.IO), phoneUUID = { "fixture-phone-uuid" })
        try {
            val prepared = client.preparePairingIdentity(AtvClientIdentity("OLD-CERT", "OLD-KEY"))
            val request = JSONObject(native.submitted.last())
            assertTrue(request.getString("cert_pem") == "OLD-CERT")
            assertTrue(request.getString("key_pem") == "OLD-KEY")
            assertTrue(request.getString("client_name") == atvClientName(android.os.Build.MODEL.orEmpty(), "fixture-phone-uuid"))
            assertFalse(request.toString().contains("fixture-phone-uuid"))
            assertTrue(prepared.replacesLegacy && prepared.clientName == "PlayBridge (Android) fixture")
            assertTrue(!prepared.toString().contains("fixture"))
            val decoded = parseIdentity(JSONObject("""{"cert_pem":"c","key_pem":"k","fingerprint":"${"ab".repeat(32)}"}"""))
            assertFalse(decoded.replacesLegacy)
        } finally { client.close() }
    }

    @Test fun closeWaitsForBlockedNativePollAndFreesExactlyOnce() = runBlocking<Unit> {
        val native = BlockingNative(blockPoll = true)
        val owner = SupervisorJob()
        val client = AtvRemoteClient(native, CoroutineScope(owner + Dispatchers.IO), phoneUUID = { "fixture-phone-uuid" })
        try {
            client.key("HOME", null, "short")
            assertTrue(native.entered.await(2, TimeUnit.SECONDS))
            val first = async(Dispatchers.IO) { client.close() }
            val second = async(Dispatchers.IO) { client.close() }
            assertTrue(native.cancelEntered.await(2, TimeUnit.SECONDS))
            assertEquals(0, native.freeCount.get())
            native.release.countDown()
            withTimeout(2_000) { first.await(); second.await() }
            assertEquals(1, native.freeCount.get())
            assertFalse(native.freedDuringCall.get())
            assertEquals(1, native.startCount.get())
            assertTrue(runCatching { client.key("HOME", null, "short") }.exceptionOrNull() is AtvCommandException)
            assertEquals(1, native.startCount.get())
        } finally {
            native.release.countDown()
            client.close()
            owner.cancelAndJoin()
        }
    }

    @Test fun submitAndConcurrentCloseAreSerialized() = runBlocking<Unit> {
        val native = BlockingNative(blockPoll = false)
        val owner = SupervisorJob()
        val client = AtvRemoteClient(native, CoroutineScope(owner + Dispatchers.IO), phoneUUID = { "fixture-phone-uuid" })
        try {
            val submit = async(Dispatchers.IO) { runCatching { client.key("HOME", null, "short") } }
            assertTrue(native.entered.await(2, TimeUnit.SECONDS))
            val close = async(Dispatchers.IO) { client.close() }
            delay(30)
            assertEquals(0, native.freeCount.get())
            native.release.countDown()
            withTimeout(2_000) { close.await(); submit.await() }
            assertFalse(native.freedDuringCall.get())
            assertEquals(1, native.freeCount.get())
        } finally {
            native.release.countDown()
            client.close()
            owner.cancelAndJoin()
        }
    }

    @Test fun disconnectReenterIgnoresOldClosureAndStateEvents() = runBlocking<Unit> {
        val native = FakeAtvNative()
        val owner = SupervisorJob()
        val client = AtvRemoteClient(native, CoroutineScope(owner + Dispatchers.IO), phoneUUID = { "fixture-phone-uuid" })
        try {
            client.connect("host-a", "CERT", "KEY", "ab".repeat(32))
            val oldId = native.connectedId!!
            client.disconnect()
            client.connect("host-b", "CERT", "KEY", "cd".repeat(32))
            val currentId = native.connectedId!!
            withTimeout(2_000) { while (client.television.value == null) delay(1) }
            val closed = async(start = CoroutineStart.UNDISPATCHED) { runCatching { client.awaitClosed() } }
            native.emit("""{"event":"closed","request_id":"$oldId","kind":"closed"}""")
            native.emit("""{"event":"state","request_id":"$oldId","state":{"volume_level":99}}""")
            delay(50)
            assertFalse(closed.isCompleted)
            assertEquals(3, client.television.value?.volumeLevel)
            native.emit("""{"event":"closed","request_id":"$currentId","kind":"not_trusted"}""")
            val error = withTimeout(2_000) { closed.await().exceptionOrNull() } as AtvCommandException
            assertEquals("not_trusted", error.kind)
            assertNull(client.television.value)
        } finally {
            client.close()
            owner.cancelAndJoin()
        }
    }

    @Test fun replacingConnectionRetiresOldWaiterWithoutClosingTheNewOne() = runBlocking<Unit> {
        val native = FakeAtvNative()
        val owner = SupervisorJob()
        val client = AtvRemoteClient(native, CoroutineScope(owner + Dispatchers.IO), phoneUUID = { "fixture-phone-uuid" })
        try {
            client.connect("host-a", "CERT", "KEY", "ab".repeat(32))
            val oldId = native.connectedId!!
            val oldWaiter = async(start = CoroutineStart.UNDISPATCHED) { runCatching { client.awaitClosed() } }
            client.connect("host-b", "CERT", "KEY", "cd".repeat(32))
            assertEquals("closed", (oldWaiter.await().exceptionOrNull() as AtvCommandException).kind)
            val newWaiter = async(start = CoroutineStart.UNDISPATCHED) { runCatching { client.awaitClosed() } }
            native.emit("""{"event":"closed","request_id":"$oldId","kind":"closed"}""")
            delay(30)
            assertFalse(newWaiter.isCompleted)
            client.disconnect()
            withTimeout(2_000) { newWaiter.await() }
        } finally {
            client.close()
            owner.cancelAndJoin()
        }
    }
}

private class FakeAtvNative(private val abi: Int = ATV_REMOTE_ABI) : AtvNativeBoundary {
    var started = false
    var cancelled = false
    var freed = false
    val submitted = CopyOnWriteArrayList<String>()
    var connectedId: String? = null
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
        if (command.getString("command") == "connect") connectedId = id
        if (command.getString("command") == "disconnect") {
            events.add("""{"event":"closed","request_id":"$connectedId","kind":"closed"}""")
        }
        val event = when (command.getString("command")) {
            "generate_identity" -> JSONObject().put("event", "identity").put("request_id", id)
                .put("cert_pem", "NEW-CERT").put("key_pem", "NEW-KEY").put("fingerprint", "ab".repeat(32))
                .put("client_name", "PlayBridge (Android) fixture").put("replaces_legacy", true).toString()
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
    fun emit(raw: String) { events.add(raw) }
}

/** Models a JNI call that Java interruption cannot terminate. No native library is loaded. */
private class BlockingNative(private val blockPoll: Boolean) : AtvNativeBoundary {
    val entered = CountDownLatch(1)
    val cancelEntered = CountDownLatch(1)
    val release = CountDownLatch(1)
    val startCount = AtomicInteger()
    val freeCount = AtomicInteger()
    val freedDuringCall = AtomicBoolean()
    private val activeCalls = AtomicInteger()
    private val sawEvent = AtomicBoolean()
    private val events = LinkedBlockingQueue<String>()
    override fun abiVersion() = 1
    override fun start(): Long { startCount.incrementAndGet(); return 7L }
    override fun submitJson(handle: Long, commandJson: String): Boolean {
        activeCalls.incrementAndGet()
        try {
            if (!blockPoll) block()
            val id = JSONObject(commandJson).getString("request_id")
            events.add("""{"event":"operation","request_id":"$id","ok":true}""")
            return true
        } finally { activeCalls.decrementAndGet() }
    }
    override fun nextEvent(handle: Long, waitMs: Long): String? {
        activeCalls.incrementAndGet()
        try {
            events.poll()?.let { sawEvent.set(true); return it }
            if (blockPoll && sawEvent.get()) block()
            return events.poll(waitMs, TimeUnit.MILLISECONDS)?.also { sawEvent.set(true) }
        } finally { activeCalls.decrementAndGet() }
    }
    private fun block() {
        entered.countDown()
        while (release.count > 0) {
            try { release.await() } catch (_: InterruptedException) { /* JNI may ignore interruption. */ }
        }
    }
    override fun cancel(handle: Long) { cancelEntered.countDown() }
    override fun free(handle: Long) {
        freedDuringCall.set(activeCalls.get() != 0)
        freeCount.incrementAndGet()
    }
}
