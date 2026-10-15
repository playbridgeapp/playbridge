package com.playbridge.player.server

import com.playbridge.shared.protocol.IncomingMessage
import com.playbridge.shared.protocol.createReceiverCapabilitiesSubscribeJson
import com.playbridge.shared.protocol.parseIncomingMessage
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReceiverCapabilityPublisherTest {
    @Test fun immediateSubscriptionWaitsForCredentialDeliveryToFinish() {
        val queued = CountDownLatch(1)
        val subscribed = CountDownLatch(1)
        val finish = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)
        val sent = CopyOnWriteArrayList<String>()
        val publisher = ReceiverCapabilityPublisher<String>({ caps(false) }, { _, frame -> sent += frame })
        try {
            val auth = executor.submit {
                publisher.authenticate("phone") {
                    queued.countDown()
                    check(finish.await(5, TimeUnit.SECONDS))
                }
            }
            assertTrue(queued.await(5, TimeUnit.SECONDS))
            val subscribe = executor.submit { subscribed.countDown(); publisher.subscribe("phone") }
            assertTrue(subscribed.await(5, TimeUnit.SECONDS))
            assertTrue(sent.isEmpty())
            finish.countDown()
            auth.get(5, TimeUnit.SECONDS)
            subscribe.get(5, TimeUnit.SECONDS)
            assertEquals(1, sent.size)
        } finally {
            finish.countDown()
            executor.shutdownNow()
        }
    }

    private fun caps(gecko: Boolean) = TvCapabilities(listOf("exo", "mpv"),
        if (gecko) listOf("webview", "gecko") else listOf("webview"))

    @Test fun noOptInOrAuthenticationMeansNoPush() {
        var available = caps(false)
        val sent = mutableListOf<String>()
        val publisher = ReceiverCapabilityPublisher<String>({ available }, { _, frame -> sent += frame })
        publisher.subscribe("pre-auth")
        publisher.authenticate("old-sender") { assertEquals(available, it) }
        available = caps(true)
        publisher.refresh()
        assertTrue(sent.isEmpty())
    }

    @Test fun installRemoveRevisionsReplaceAndNextAuthAndPairingMatch() {
        var available = caps(false)
        val sent = mutableListOf<JsonObject>()
        val publisher = ReceiverCapabilityPublisher<String>({ available }, { _, frame -> sent += Json.parseToJsonElement(frame) as JsonObject })
        publisher.authenticate("phone") { assertEquals(available, it) }
        publisher.subscribe("phone")
        publisher.subscribe("phone") // Idempotent, no revision reset or duplicate.
        available = caps(true)
        publisher.refresh()
        publisher.refresh() // Duplicate/replacing broadcasts do not fabricate changes.
        available = caps(false)
        publisher.refresh()
        assertEquals(listOf(1L, 2L, 3L), sent.map { it.getValue("revision").jsonPrimitive.long })
        assertTrue(sent.all { !it.getValue("revision").jsonPrimitive.isString })
        assertEquals(caps(true).capabilities, sent[1]["capabilities"])
        assertEquals(caps(false).capabilities, sent[2]["capabilities"])
        publisher.authenticate("next-auth") { assertEquals(sent.last()["capabilities"], it.capabilities) }
        publisher.authenticate("next-protected-pairing") { assertEquals(sent.last()["capabilities"], it.capabilities) }
        assertEquals(3, sent.size)
    }

    @Test fun subscriptionClosesAuthRaceAndRevisionsArePerSocket() {
        var available = caps(false)
        val sent = mutableListOf<Pair<String, JsonObject>>()
        val publisher = ReceiverCapabilityPublisher<String>({ available }, { conn, frame -> sent += conn to (Json.parseToJsonElement(frame) as JsonObject) })
        publisher.authenticate("first") {}
        available = caps(true) // Changed between initial auth and subscription.
        publisher.subscribe("first")
        assertEquals(available.capabilities, sent.single().second["capabilities"])
        publisher.authenticate("second") {}
        publisher.subscribe("second")
        available = caps(false)
        publisher.refresh()
        assertEquals(listOf(1L, 1L, 2L, 2L), sent.map { it.second.getValue("revision").jsonPrimitive.long })
        publisher.forget("first")
        available = caps(true)
        publisher.refresh()
        assertEquals("second", sent.last().first)
        assertEquals(3L, sent.last().second.getValue("revision").jsonPrimitive.long)
        val count = sent.size
        publisher.subscribe("first")
        assertEquals(count, sent.size)
        publisher.authenticate("first") {}
        publisher.subscribe("first")
        assertEquals(1L, sent.last().second.getValue("revision").jsonPrimitive.long)
    }

    @Test fun failedSocketDoesNotPreventOtherUpdates() {
        var available = caps(false)
        var broken = false
        val sent = mutableListOf<String>()
        val publisher = ReceiverCapabilityPublisher<String>({ available }, { conn, _ ->
            if (broken && conn == "broken") error("closed test socket")
            sent += conn
        })
        for (conn in listOf("broken", "healthy")) { publisher.authenticate(conn) {}; publisher.subscribe(conn) }
        sent.clear()
        broken = true
        available = caps(true)
        publisher.refresh()
        assertEquals(listOf("healthy"), sent)
    }

    @Test fun subscriptionUsesAnExactEmptyCommandPayload() {
        assertEquals(IncomingMessage.ReceiverCapabilitiesSubscribe, parseIncomingMessage(createReceiverCapabilitiesSubscribeJson()))
        for (payload in listOf("null", "[]", "{\"enabled\":true}")) {
            assertFalse(parseIncomingMessage("""{"type":"command","action":"receiver_capabilities_subscribe","payload":$payload}""") is IncomingMessage.ReceiverCapabilitiesSubscribe)
        }
    }
}
