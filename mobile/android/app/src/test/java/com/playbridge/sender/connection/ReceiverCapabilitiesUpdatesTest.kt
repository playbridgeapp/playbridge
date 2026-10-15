package com.playbridge.sender.connection

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.*
import org.junit.Test

class ReceiverCapabilitiesUpdatesTest {
    private fun json(value: String) = Json.parseToJsonElement(value) as JsonObject
    private val full = """{"version":1,"surfaces":{"browser":{"engines":{"gecko":{"text":["replace","enter"]}}}}}"""
    private val empty = """{"version":1,"surfaces":{}}"""
    private fun credentials(descriptor: String = full, feature: Boolean = true) = json(
        """{"capabilities":$descriptor,"features":[${if (feature) "\"receiver_capabilities_v1\"" else ""}]}""",
    )
    private fun update(revision: String, descriptor: String = empty) = json(
        """{"type":"receiver_capabilities","revision":$revision,"capabilities":$descriptor}""",
    )
    private fun subscribed(): Pair<ReceiverProtocolSession, Long> {
        val owner = ReceiverProtocolSession()
        val session = owner.begin()
        assertTrue(owner.acceptCredentials(session, credentials()))
        assertNotNull(owner.subscription(session))
        return owner to session
    }

    @Test fun optInRequiresCurrentAuthenticatedAdvertisedSupportAndIsOncePerSession() {
        val owner = ReceiverProtocolSession()
        val session = owner.begin()
        assertNull(owner.subscription(session))
        owner.observe(session, update("1"))
        assertNull(owner.state.value.descriptor)
        owner.acceptCredentials(session, credentials(feature = false))
        assertNull(owner.subscription(session))
        val before = owner.state.value
        owner.observe(session, update("1"))
        assertEquals(before, owner.state.value)
        val next = owner.begin()
        owner.acceptCredentials(next, credentials())
        assertNull(owner.subscription(session))
        assertNotNull(owner.subscription(next))
        assertNull(owner.subscription(next))
    }

    @Test fun replacementRemovesOperationsAndRejectsDuplicateAndOutOfOrderFrames() {
        val (owner, session) = subscribed()
        owner.observe(session, update("2"))
        val replaced = owner.state.value
        assertTrue(replaced.descriptor!!.surfaces.isEmpty())
        owner.observe(session, update("2", full))
        owner.observe(session, update("1", full))
        assertEquals(replaced, owner.state.value)
        owner.observe(session, update("3", full))
        assertTrue(owner.state.value.descriptor!!.surfaces.containsKey("browser"))
    }

    @Test fun futureVersionsKeepCurrentDescriptorButAdvanceOrdering() {
        val (owner, session) = subscribed()
        val before = owner.state.value
        owner.observe(session, update("10", """{"version":2,"surfaces":[]}"""))
        owner.observe(session, update("9"))
        assertEquals(before, owner.state.value)
        owner.observe(session, update("11"))
        assertTrue(owner.state.value.descriptor!!.surfaces.isEmpty())
    }

    @Test fun malformedV1FailsClosedAndOnlyANewerValidSnapshotRestoresIt() {
        val (owner, session) = subscribed()
        owner.observe(session, update("5", """{"version":1,"surfaces":[]}"""))
        assertNull(owner.state.value.descriptor)
        assertTrue(owner.state.value.malformedV1)
        owner.observe(session, update("4", full))
        assertTrue(owner.state.value.malformedV1)
        owner.observe(session, update("6", full))
        assertNotNull(owner.state.value.descriptor)
        assertFalse(owner.state.value.malformedV1)
    }

    @Test fun invalidRevisionsAndEnvelopesDoNotConsumeTheNextRevision() {
        val (owner, session) = subscribed()
        val before = owner.state.value
        for (revision in listOf("0", "-1", "1.5", "9007199254740992", "\"1\"", "true", "null")) {
            owner.observe(session, update(revision))
            assertEquals(revision, before, owner.state.value)
        }
        owner.observe(session, json("""{"type":"receiver_capabilities","revision":1}"""))
        assertEquals(before, owner.state.value)
        owner.observe(session, update("1"))
        assertTrue(owner.state.value.descriptor!!.surfaces.isEmpty())
    }

    @Test fun reconnectAndReceiverSwitchFenceOldSocketsAndResetRevision() {
        val (owner, old) = subscribed()
        owner.observe(old, update("100"))
        val next = owner.begin()
        owner.observe(old, update("101", full))
        owner.observe(next, update("101", full)) // New socket is not authenticated yet.
        assertEquals(ReceiverProtocolState(), owner.state.value)
        owner.acceptCredentials(next, credentials())
        owner.subscription(next)
        owner.observe(old, update("999"))
        assertTrue(owner.state.value.descriptor!!.surfaces.containsKey("browser"))
        owner.observe(next, update("1"))
        assertTrue(owner.state.value.descriptor!!.surfaces.isEmpty())
        owner.clear()
        owner.observe(next, update("2", full))
        assertEquals(ReceiverProtocolState(), owner.state.value)
    }

    @Test fun descriptorPushRetainsTransientStateAndDoesNotMergeOperations() {
        val (owner, session) = subscribed()
        owner.observe(session, json("""{"type":"context","active":"browser","engine":"gecko"}"""))
        owner.observe(session, json("""{"type":"volume_state","scope":"app","level":0.4}"""))
        val before = owner.state.value
        owner.observe(session, update("1"))
        assertEquals(before.contextEngine, owner.state.value.contextEngine)
        assertEquals(before.volume, owner.state.value.volume)
        assertTrue(owner.state.value.descriptor!!.surfaces.isEmpty())
    }

    @Test fun duplicateAuthCannotRewindTheSameSocketsRevisionOrSubscription() {
        val (owner, session) = subscribed()
        owner.observe(session, update("5"))
        owner.acceptCredentials(session, credentials())
        assertNull(owner.subscription(session))
        val before = owner.state.value
        owner.observe(session, update("4"))
        assertEquals(before, owner.state.value)
    }
}
