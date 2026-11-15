package com.playbridge.sender.browser

import com.playbridge.sender.together.PartyMediaSupport
import com.playbridge.sender.together.PartyQueue
import com.playbridge.sender.together.PartyQueueItem
import com.playbridge.sender.together.PartyQueueUpdate
import com.playbridge.sender.together.PhoneTogether
import com.playbridge.shared.together.TogetherItem
import com.playbridge.shared.together.TogetherPolicy
import com.playbridge.shared.together.TogetherSource
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import playbridge.PlayPayload

@OptIn(ExperimentalCoroutinesApi::class)
class PartyLinkedSessionTest {

    private class FakePartyGateway(
        initialParty: PhoneTogether.Party = defaultParty(),
        initialSelected: Boolean = true,
        private val ownAddresses: Set<String> = setOf("192.168.1.10"),
    ) : PartyPlaybackGateway {
        private val mutablePartyState = MutableStateFlow<PhoneTogether.State>(
            PhoneTogether.State.InParty(initialParty),
        )
        override val partyState: StateFlow<PhoneTogether.State> = mutablePartyState.asStateFlow()

        private val mutableTargetSelected = MutableStateFlow(initialSelected)
        override val partyTargetSelected: StateFlow<Boolean> = mutableTargetSelected.asStateFlow()

        private val mutableQueueUpdates = MutableSharedFlow<PartyQueueUpdate>(extraBufferCapacity = 64)
        override val queueUpdates: SharedFlow<PartyQueueUpdate> = mutableQueueUpdates.asSharedFlow()

        val partyQueue = PartyQueue()
        val loadedItems = mutableListOf<PartyQueueItem>()
        val requestedPlayItems = mutableListOf<PartyQueueItem>()
        var failNextLoad: Boolean = false

        override fun currentParty(): PhoneTogether.Party? =
            (mutablePartyState.value as? PhoneTogether.State.InParty)?.party

        override fun isInParty(): Boolean = mutablePartyState.value is PhoneTogether.State.InParty

        override fun isPartySelected(): Boolean = isInParty() && mutableTargetSelected.value

        override fun evaluateUrl(url: String): PartyMediaSupport.PartyUrlCheck =
            PartyMediaSupport.evaluatePartyUrl(url, ownAddresses)

        override suspend fun playLinkedInParty(
            item: PartyQueueItem,
            queueItems: List<PartyQueueItem>,
            replaceQueueIds: Set<String>,
        ): PhoneTogether.LinkedPlayResult {
            val party = currentParty() ?: return PhoneTogether.LinkedPlayResult.Error("receiver_stopped")
            if (!isPartySelected()) return PhoneTogether.LinkedPlayResult.Error("receiver_changed")
            if (evaluateUrl(item.url).rejection != null) {
                return PhoneTogether.LinkedPlayResult.Error("unsupported_target")
            }
            if (party.locked || (party.policy == TogetherPolicy.OnlyHost && !party.host)) {
                return PhoneTogether.LinkedPlayResult.Error("not_allowed")
            }
            if (failNextLoad) {
                failNextLoad = false
                return PhoneTogether.LinkedPlayResult.Error("connect_failed")
            }
            if (!party.canChangeVideo) {
                requestedPlayItems += item
                if (replaceQueueIds.isNotEmpty()) {
                    val removed = partyQueue.removeAll(replaceQueueIds)
                    if (removed.isNotEmpty()) {
                        mutableQueueUpdates.tryEmit(PartyQueueUpdate.ReplacedSessionQueue(removed, partyQueue.items))
                    }
                }
                return PhoneTogether.LinkedPlayResult.Ok(askOnly = true, queuedIds = emptyList())
            }
            loadedItems += item
            val removed = partyQueue.removeAll(replaceQueueIds)
            val validQueue = queueItems.filter { evaluateUrl(it.url).rejection == null }
            if (validQueue.isNotEmpty()) partyQueue.addAll(validQueue)
            if (removed.isNotEmpty()) {
                mutableQueueUpdates.tryEmit(PartyQueueUpdate.ReplacedSessionQueue(removed, partyQueue.items))
            } else if (validQueue.isNotEmpty()) {
                mutableQueueUpdates.tryEmit(PartyQueueUpdate.Enqueued(validQueue.map { it.id }, partyQueue.items))
            }
            return PhoneTogether.LinkedPlayResult.Ok(askOnly = false, queuedIds = validQueue.map { it.id })
        }

        override suspend fun enqueueLinkedItems(items: List<PartyQueueItem>): PhoneTogether.LinkedEnqueueResult {
            val party = currentParty() ?: return PhoneTogether.LinkedEnqueueResult.Error("receiver_stopped")
            if (!isPartySelected()) return PhoneTogether.LinkedEnqueueResult.Error("receiver_changed")
            if (party.locked || (party.policy == TogetherPolicy.OnlyHost && !party.host)) {
                return PhoneTogether.LinkedEnqueueResult.Error("not_allowed")
            }
            if (!party.canChangeVideo) {
                return PhoneTogether.LinkedEnqueueResult.Error("unsupported_queue")
            }
            val valid = items.filter { evaluateUrl(it.url).rejection == null }
            if (valid.isEmpty()) return PhoneTogether.LinkedEnqueueResult.Error("unsupported_target")
            partyQueue.addAll(valid)
            mutableQueueUpdates.tryEmit(PartyQueueUpdate.Enqueued(valid.map { it.id }, partyQueue.items))
            return PhoneTogether.LinkedEnqueueResult.Ok(valid)
        }

        override suspend fun playQueuedItemNow(queueItemId: String): Boolean {
            val party = currentParty() ?: return false
            if (!party.canChangeVideo) return false
            val target = partyQueue.remove(queueItemId) ?: return false
            loadedItems += target
            mutableQueueUpdates.tryEmit(PartyQueueUpdate.LoadedFromQueue(target, partyQueue.items))
            return true
        }

        override suspend fun replayLinkedItem(item: PartyQueueItem): PhoneTogether.LinkedPlayResult {
            val party = currentParty() ?: return PhoneTogether.LinkedPlayResult.Error("receiver_stopped")
            if (!isPartySelected()) return PhoneTogether.LinkedPlayResult.Error("receiver_changed")
            if (party.locked || (party.policy == TogetherPolicy.OnlyHost && !party.host)) {
                return PhoneTogether.LinkedPlayResult.Error("not_allowed")
            }
            if (!party.canChangeVideo) {
                requestedPlayItems += item
                return PhoneTogether.LinkedPlayResult.Ok(askOnly = true, queuedIds = emptyList())
            }
            loadedItems += item
            return PhoneTogether.LinkedPlayResult.Ok(askOnly = false, queuedIds = emptyList())
        }

        fun echoPlaying(item: PartyQueueItem, playing: Boolean = true, positionMs: Long = 0L, durationMs: Long = 120_000L) {
            val party = currentParty() ?: return
            mutablePartyState.value = PhoneTogether.State.InParty(
                party.copy(
                    item = TogetherItem(
                        id = "room_${item.id}",
                        title = item.title,
                        site = item.site,
                        source = TogetherSource.Url(item.url),
                    ),
                    playing = playing,
                    positionMs = positionMs,
                    positionAt = 0L,
                    durationMs = durationMs,
                ),
            )
        }

        fun advanceNextFromQueue(): PartyQueueItem? {
            val next = partyQueue.takeNext() ?: return null
            loadedItems += next
            mutableQueueUpdates.tryEmit(PartyQueueUpdate.LoadedFromQueue(next, partyQueue.items))
            return next
        }

        fun userRemoveFromQueue(id: String) {
            if (partyQueue.remove(id) != null) {
                mutableQueueUpdates.tryEmit(PartyQueueUpdate.Removed(id, partyQueue.items))
            }
        }

        fun userMoveQueueItem(from: Int, to: Int) {
            if (partyQueue.move(from, to)) {
                mutableQueueUpdates.tryEmit(PartyQueueUpdate.Reordered(partyQueue.items))
            }
        }

        fun userClearQueue() {
            partyQueue.clear()
            mutableQueueUpdates.tryEmit(PartyQueueUpdate.Cleared(emptyList()))
        }

        fun triggerManualPlay() {
            mutableQueueUpdates.tryEmit(PartyQueueUpdate.ManualPlay)
        }

        fun selectTarget(selected: Boolean) {
            mutableTargetSelected.value = selected
        }

        fun leaveParty(state: PhoneTogether.State = PhoneTogether.State.Idle) {
            mutablePartyState.value = state
            mutableTargetSelected.value = false
        }

        fun updatePolicy(policy: TogetherPolicy, host: Boolean? = null, locked: Boolean? = null) {
            val party = currentParty() ?: return
            mutablePartyState.value = PhoneTogether.State.InParty(
                party.copy(
                    policy = policy,
                    host = host ?: party.host,
                    locked = locked ?: party.locked,
                ),
            )
        }
    }

    private fun TestScope.newHarness(
        gateway: FakePartyGateway = FakePartyGateway(),
        window: Int = 3,
    ): Harness {
        val messages = mutableListOf<JSONObject>()
        var elapsed = 0L
        val engine = LinkedSessionEngine(
            prefetchWindow = { window },
            postLinkedMessage = { messages += it },
            scope = backgroundScope,
            clock = { testScheduler.currentTime + 1_000_000L },
        )
        return Harness(gateway, engine, messages) { elapsed }
    }

    private inner class Harness(
        val gateway: FakePartyGateway,
        val engine: LinkedSessionEngine,
        val messages: MutableList<JSONObject>,
        val elapsedClock: () -> Long,
    ) {
        fun open(
            request: LinkedPageCastOpenRequest = openRequest(),
            sink: WatchPartyLinkedSessionSink = WatchPartyLinkedSessionSink(gateway, elapsedClock),
        ) {
            engine.open(request, sink)
        }

        fun resultFor(bridgeRequestId: String): JSONObject? =
            messages.lastOrNull {
                it.optString("type") == "linked_result" &&
                    it.optString("bridgeRequestId") == bridgeRequestId
            }

        fun events(name: String): List<JSONObject> =
            messages.filter {
                it.optString("type") == "linked_event" && it.optString("event") == name
            }

        fun lastNeedRequest(): JSONObject? = events("needitems").lastOrNull()
    }

    @Test
    fun `open append supply jump replace ping and unlink full flow`() = runTest {
        val harness = newHarness(window = 3)
        val req = openRequest(
            bridgeRequestId = "req-open",
            items = listOf(linkedItem("ep1", "Episode 1")),
            destinationId = "party-dest",
        )
        harness.open(req)
        runCurrent()

        val openResult = harness.resultFor("req-open")
        assertNotNull(openResult)
        assertTrue(openResult!!.getBoolean("ok"))
        assertEquals(listOf("Episode 1"), harness.gateway.loadedItems.map { it.title })

        // Room confirms ep1 playing -> statechange emits ep1
        harness.gateway.echoPlaying(harness.gateway.loadedItems.last())
        runCurrent()
        val initialState = harness.events("statechange").last().getJSONObject("detail")
        assertEquals("playing", initialState.getString("state"))
        assertEquals(0, initialState.getInt("currentIndex"))
        assertEquals("ep1", initialState.getJSONArray("items").getJSONObject(0).getString("id"))

        // Initial demand emitted for 3 items
        val need1 = harness.lastNeedRequest()!!.getJSONObject("detail")
        assertEquals(3, need1.getInt("count"))
        assertEquals(0, need1.getInt("afterIndex"))
        assertEquals("ep1", need1.getString("afterItemId"))

        // supply adds ep2 and ep3
        harness.engine.handle(
            sessionMessage(
                bridgeRequestId = "req-supply-1",
                type = "linked_supply",
                payload = JSONObject()
                    .put("requestId", need1.getString("requestId"))
                    .put("endOfList", false)
                    .put(
                        "items",
                        JSONArray()
                            .put(itemJson("ep2", "Episode 2"))
                            .put(itemJson("ep3", "Episode 3")),
                    ),
            ),
        )
        runCurrent()
        assertTrue(harness.resultFor("req-supply-1")!!.getBoolean("ok"))
        assertEquals(listOf("Episode 2", "Episode 3"), harness.gateway.partyQueue.items.map { it.title })

        // append adds ep4 in order
        harness.engine.handle(
            sessionMessage(
                bridgeRequestId = "req-append",
                type = "linked_append",
                payload = JSONObject().put("items", JSONArray().put(itemJson("ep4", "Episode 4"))),
            ),
        )
        runCurrent()
        assertTrue(harness.resultFor("req-append")!!.getBoolean("ok"))
        assertEquals(listOf("Episode 2", "Episode 3", "Episode 4"), harness.gateway.partyQueue.items.map { it.title })

        // Advance to ep2, then jump directly to ep4 (index 3)
        val ep2QueueItem = harness.gateway.advanceNextFromQueue()!!
        runCurrent()
        harness.gateway.echoPlaying(ep2QueueItem)
        runCurrent()
        val stateAfterAdvance = harness.events("statechange").last().getJSONObject("detail")
        assertEquals(1, stateAfterAdvance.getInt("currentIndex"))
        assertEquals("ep2", stateAfterAdvance.getJSONArray("items").getJSONObject(1).getString("id"))

        // Jump to ep4 (currently last in upcoming, index 3)
        harness.engine.handle(
            sessionMessage(
                bridgeRequestId = "req-jump-4",
                type = "linked_jump",
                payload = JSONObject().put("index", 3),
            ),
        )
        runCurrent()
        assertTrue(harness.resultFor("req-jump-4")!!.getBoolean("ok"))
        assertEquals("Episode 4", harness.gateway.loadedItems.last().title)
        assertEquals(listOf("Episode 3"), harness.gateway.partyQueue.items.map { it.title })
        val stateAfterJump4 = harness.events("statechange").last().getJSONObject("detail")
        assertEquals("ep4", stateAfterJump4.getJSONArray("items").getJSONObject(stateAfterJump4.getInt("currentIndex")).getString("id"))

        // Jump back to ep1 (index 0, played history)
        harness.engine.handle(
            sessionMessage(
                bridgeRequestId = "req-jump-0",
                type = "linked_jump",
                payload = JSONObject().put("index", 0),
            ),
        )
        runCurrent()
        assertTrue(harness.resultFor("req-jump-0")!!.getBoolean("ok"))
        assertEquals("Episode 1", harness.gateway.loadedItems.last().title)

        // Add unrelated manual item to Up next; verify replace only replaces THIS session's queued items
        val manualItem = PartyQueueItem(id = "manual-1", url = "https://cdn.example.com/manual.mp4", title = "Manual")
        harness.gateway.partyQueue.add(manualItem)
        harness.engine.handle(
            sessionMessage(
                bridgeRequestId = "req-replace",
                type = "linked_replace",
                payload = JSONObject()
                    .put("startIndex", 1)
                    .put(
                        "items",
                        JSONArray()
                            .put(itemJson("r0", "Replaced 0"))
                            .put(itemJson("r1", "Replaced 1"))
                            .put(itemJson("r2", "Replaced 2")),
                    ),
            ),
        )
        runCurrent()
        assertTrue(harness.resultFor("req-replace")!!.getBoolean("ok"))
        assertEquals("Replaced 1", harness.gateway.loadedItems.last().title)
        assertEquals(listOf("Manual", "Replaced 2"), harness.gateway.partyQueue.items.map { it.title })

        // Ping succeeds while linked
        harness.engine.handle(sessionMessage(bridgeRequestId = "req-ping", type = "linked_ping"))
        runCurrent()
        assertTrue(harness.resultFor("req-ping")!!.getBoolean("ok"))

        // Unlink ends session while leaving Up next intact
        harness.engine.handle(sessionMessage(bridgeRequestId = "req-unlink", type = "linked_unlink"))
        runCurrent()
        assertTrue(harness.resultFor("req-unlink")!!.getBoolean("ok"))
        assertEquals("unlinked", harness.events("ended").last().getJSONObject("detail").getString("reason"))
        assertEquals(listOf("Manual", "Replaced 2"), harness.gateway.partyQueue.items.map { it.title })

        // Post-unlink operations receive session_ended
        harness.engine.handle(sessionMessage(bridgeRequestId = "req-after-unlink", type = "linked_ping"))
        runCurrent()
        val afterResult = harness.resultFor("req-after-unlink")!!
        assertFalse(afterResult.getBoolean("ok"))
        assertEquals("session_ended", afterResult.getString("error"))
    }

    @Test
    fun `rejects duplicate ids stale supply and item limits`() = runTest {
        val harness = newHarness(window = 10)
        harness.open(openRequest(items = listOf(linkedItem("ep1"))))
        runCurrent()

        // Intra-batch duplicate on append
        harness.engine.handle(
            sessionMessage(
                bridgeRequestId = "dup-append-same",
                type = "linked_append",
                payload = JSONObject().put("items", JSONArray().put(itemJson("ep2")).put(itemJson("ep2"))),
            ),
        )
        runCurrent()
        assertEquals("invalid_request", harness.resultFor("dup-append-same")!!.getString("error"))

        // Existing session item id on append
        harness.engine.handle(
            sessionMessage(
                bridgeRequestId = "dup-append-existing",
                type = "linked_append",
                payload = JSONObject().put("items", JSONArray().put(itemJson("ep1"))),
            ),
        )
        runCurrent()
        assertEquals("invalid_request", harness.resultFor("dup-append-existing")!!.getString("error"))

        // Stale supply requestId rejected
        harness.engine.handle(
            sessionMessage(
                bridgeRequestId = "supply-stale",
                type = "linked_supply",
                payload = JSONObject()
                    .put("requestId", "wrong-request-id")
                    .put("endOfList", false)
                    .put("items", JSONArray().put(itemJson("ep2"))),
            ),
        )
        runCurrent()
        assertEquals("stale_request", harness.resultFor("supply-stale")!!.getString("error"))

        // Valid supply works and duplicate repeat is idempotent (ok=true)
        val needId = harness.lastNeedRequest()!!.getJSONObject("detail").getString("requestId")
        val supplyMsg = sessionMessage(
            bridgeRequestId = "supply-ok",
            type = "linked_supply",
            payload = JSONObject()
                .put("requestId", needId)
                .put("endOfList", false)
                .put("items", JSONArray().put(itemJson("ep2"))),
        )
        harness.engine.handle(supplyMsg)
        runCurrent()
        assertTrue(harness.resultFor("supply-ok")!!.getBoolean("ok"))

        harness.engine.handle(
            sessionMessage(
                bridgeRequestId = "supply-repeat",
                type = "linked_supply",
                payload = supplyMsg.getJSONObject("payload"),
            ),
        )
        runCurrent()
        assertTrue(harness.resultFor("supply-repeat")!!.getBoolean("ok"))
        assertEquals(listOf("ep2"), harness.gateway.partyQueue.items.map { it.title.removePrefix("Video ") })

        // Fill to MAX_SESSION_ITEMS (200) via 50-item append batches (currently 2 items -> add 50*3 + 48 = 198 -> 200)
        var counter = 3
        repeat(3) { batch ->
            val arr = JSONArray()
            repeat(50) { arr.put(itemJson("id-${counter++}")) }
            harness.engine.handle(
                sessionMessage("fill-$batch", "linked_append", JSONObject().put("items", arr)),
            )
            runCurrent()
            assertTrue(harness.resultFor("fill-$batch")!!.getBoolean("ok"))
        }
        val finalFit = JSONArray()
        repeat(48) { finalFit.put(itemJson("id-${counter++}")) }
        harness.engine.handle(sessionMessage("fill-last", "linked_append", JSONObject().put("items", finalFit)))
        runCurrent()
        assertTrue(harness.resultFor("fill-last")!!.getBoolean("ok"))

        // Item 201 exceeds MAX_SESSION_ITEMS -> resource_limit
        harness.engine.handle(
            sessionMessage(
                "fill-overflow",
                "linked_append",
                JSONObject().put("items", JSONArray().put(itemJson("id-overflow"))),
            ),
        )
        runCurrent()
        assertEquals("resource_limit", harness.resultFor("fill-overflow")!!.getString("error"))
    }

    @Test
    fun `demand stops after endOfList`() = runTest {
        val harness = newHarness(window = 2)
        harness.open(openRequest(items = listOf(linkedItem("ep1"))))
        runCurrent()

        val need1 = harness.lastNeedRequest()!!.getJSONObject("detail").getString("requestId")
        harness.engine.handle(
            sessionMessage(
                "supply-eol",
                "linked_supply",
                JSONObject()
                    .put("requestId", need1)
                    .put("endOfList", true)
                    .put("items", JSONArray().put(itemJson("ep2"))),
            ),
        )
        runCurrent()
        assertTrue(harness.resultFor("supply-eol")!!.getBoolean("ok"))
        assertEquals(1, harness.events("needitems").size)

        // Advance to ep2 (leaving 0 upcoming) -> still no new needitems after endOfList=true
        harness.gateway.advanceNextFromQueue()
        runCurrent()
        assertEquals(1, harness.events("needitems").size)
    }

    @Test
    fun `user reorder remove and clear without triggering demand loop`() = runTest {
        val harness = newHarness(window = 3)
        harness.open(
            openRequest(
                items = listOf(linkedItem("ep1"), linkedItem("ep2"), linkedItem("ep3"), linkedItem("ep4")),
            ),
        )
        runCurrent()
        val needCountBefore = harness.events("needitems").size
        assertEquals(0, needCountBefore) // 3 ahead == window 3

        // Reorder ep3 before ep2
        harness.gateway.userMoveQueueItem(1, 0)
        runCurrent()
        val reorderedItems = harness.events("statechange").last().getJSONObject("detail").getJSONArray("items")
        assertEquals(listOf("ep1", "ep3", "ep2", "ep4"), (0 until reorderedItems.length()).map {
            reorderedItems.getJSONObject(it).getString("id")
        })

        // Remove ep4
        val ep4QueueId = harness.gateway.partyQueue.items.last().id
        harness.gateway.userRemoveFromQueue(ep4QueueId)
        runCurrent()
        val afterRemoveItems = harness.events("statechange").last().getJSONObject("detail").getJSONArray("items")
        assertEquals(listOf("ep1", "ep3", "ep2"), (0 until afterRemoveItems.length()).map {
            afterRemoveItems.getJSONObject(it).getString("id")
        })
        assertEquals(0, harness.events("needitems").size)

        // Clear queue -> state updates to 1 item and does NOT trigger demand loop even across time/playback
        harness.gateway.userClearQueue()
        runCurrent()
        advanceTimeBy(10_000)
        runCurrent()
        val afterClear = harness.events("statechange").last().getJSONObject("detail")
        assertEquals(1, afterClear.getInt("totalCount"))
        assertEquals(0, harness.events("needitems").size)
        assertTrue(harness.events("ended").isEmpty())
    }

    @Test
    fun `session ends on leave party target change permission lost and timeout`() = runTest {
        // 1. Phone leaves party
        val h1 = newHarness()
        h1.open(openRequest(bridgeRequestId = "o1"))
        runCurrent()
        h1.gateway.leaveParty(PhoneTogether.State.Ended("Party ended"))
        runCurrent()
        assertEquals("receiver_stopped", h1.events("ended").last().getJSONObject("detail").getString("reason"))

        // 2. Target changed
        val h2 = newHarness()
        h2.open(openRequest(bridgeRequestId = "o2"))
        runCurrent()
        h2.gateway.selectTarget(false)
        runCurrent()
        assertEquals("receiver_changed", h2.events("ended").last().getJSONObject("detail").getString("reason"))

        // 3. Permission lost for good (OnlyHost when not host)
        val guestParty = defaultParty(host = false, policy = TogetherPolicy.Anyone)
        val h3 = newHarness(gateway = FakePartyGateway(initialParty = guestParty))
        h3.open(openRequest(bridgeRequestId = "o3"))
        runCurrent()
        h3.gateway.updatePolicy(TogetherPolicy.OnlyHost)
        runCurrent()
        assertEquals("not_allowed", h3.events("ended").last().getJSONObject("detail").getString("reason"))

        // 4. Manual cast supersedes linked session
        val h4 = newHarness()
        h4.open(openRequest(bridgeRequestId = "o4"))
        runCurrent()
        h4.gateway.triggerManualPlay()
        runCurrent()
        assertEquals("superseded", h4.events("ended").last().getJSONObject("detail").getString("reason"))

        // 5. Newer open supersedes active session
        val h5 = newHarness()
        h5.open(openRequest(bridgeRequestId = "o5a", sessionId = "sess-a"))
        runCurrent()
        h5.open(openRequest(bridgeRequestId = "o5b", sessionId = "sess-b"))
        runCurrent()
        val endedOld = h5.events("ended").single()
        assertEquals("sess-a", endedOld.getString("sessionId"))
        assertEquals("superseded", endedOld.getJSONObject("detail").getString("reason"))
        assertTrue(h5.resultFor("o5b")!!.getBoolean("ok"))

        // 6. Idle timeout expires active session
        val h6 = newHarness()
        h6.open(openRequest(bridgeRequestId = "o6"))
        runCurrent()
        advanceTimeBy(10 * 60 * 1_000L + 5_000L)
        runCurrent()
        assertEquals("session_expired", h6.events("ended").last().getJSONObject("detail").getString("reason"))
    }

    @Test
    fun `ask policy asks host for first item rejects queueing and suppresses demand`() = runTest {
        val askParty = defaultParty(host = false, policy = TogetherPolicy.Ask, locked = false)
        val harness = newHarness(gateway = FakePartyGateway(initialParty = askParty), window = 3)
        harness.open(
            openRequest(
                bridgeRequestId = "ask-open",
                items = listOf(linkedItem("ep1", "Ep 1"), linkedItem("ep2", "Ep 2")),
            ),
        )
        runCurrent()

        // First item asked; nothing queued; open ok; NO needitems emitted
        assertTrue(harness.resultFor("ask-open")!!.getBoolean("ok"))
        assertEquals(listOf("Ep 1"), harness.gateway.requestedPlayItems.map { it.title })
        assertTrue(harness.gateway.loadedItems.isEmpty())
        assertTrue(harness.gateway.partyQueue.isEmpty)
        assertTrue(harness.events("needitems").isEmpty())

        // Append rejected with unsupported_queue without ending the session
        harness.engine.handle(
            sessionMessage(
                "ask-append",
                "linked_append",
                JSONObject().put("items", JSONArray().put(itemJson("ep3", "Ep 3"))),
            ),
        )
        runCurrent()
        val appendResult = harness.resultFor("ask-append")!!
        assertFalse(appendResult.getBoolean("ok"))
        assertEquals("unsupported_queue", appendResult.getString("error"))
        assertTrue(harness.events("ended").isEmpty())

        // Multi-item replace rejected with unsupported_queue
        harness.engine.handle(
            sessionMessage(
                "ask-replace-multi",
                "linked_replace",
                JSONObject().put("items", JSONArray().put(itemJson("ep3")).put(itemJson("ep4"))),
            ),
        )
        runCurrent()
        assertEquals("unsupported_queue", harness.resultFor("ask-replace-multi")!!.getString("error"))

        // Single-item replace asks host
        harness.engine.handle(
            sessionMessage(
                "ask-replace-single",
                "linked_replace",
                JSONObject().put("items", JSONArray().put(itemJson("ep3", "Ep 3"))),
            ),
        )
        runCurrent()
        assertTrue(harness.resultFor("ask-replace-single")!!.getBoolean("ok"))
        assertEquals(listOf("Ep 1", "Ep 3"), harness.gateway.requestedPlayItems.map { it.title })
    }

    @Test
    fun `rejects invalid or loopback items without killing session unless nothing playable`() = runTest {
        val harness = newHarness(window = 3)

        // Open with unplayable selected item -> rejected unsupported_target, no session
        harness.open(
            openRequest(
                bridgeRequestId = "bad-open",
                items = listOf(linkedItem("bad", "Local", url = "http://127.0.0.1:8080/video.mp4")),
            ),
        )
        runCurrent()
        assertEquals("unsupported_target", harness.resultFor("bad-open")!!.getString("error"))
        assertFalse(harness.engine.isActive())

        // Open with valid first item + invalid second item -> opens first, skips invalid, stays alive
        harness.open(
            openRequest(
                bridgeRequestId = "mixed-open",
                items = listOf(
                    linkedItem("ep1", "Good 1", url = "https://cdn.example.com/1.mp4"),
                    linkedItem("bad2", "Phone Local", url = "http://192.168.1.10:8080/local.mp4"),
                    linkedItem("ep3", "Good 3", url = "https://cdn.example.com/3.mp4"),
                ),
            ),
        )
        runCurrent()
        assertTrue(harness.resultFor("mixed-open")!!.getBoolean("ok"))
        assertTrue(harness.engine.isActive())
        assertEquals(listOf("Good 3"), harness.gateway.partyQueue.items.map { it.title })

        // Append with only invalid item -> rejects unsupported_target, session remains active
        harness.engine.handle(
            sessionMessage(
                "bad-append",
                "linked_append",
                JSONObject().put("items", JSONArray().put(itemJson("bad-only", "Loopback", "http://localhost:9000/a.mp4"))),
            ),
        )
        runCurrent()
        assertEquals("unsupported_target", harness.resultFor("bad-append")!!.getString("error"))
        assertTrue(harness.engine.isActive())

        // Supply with only invalid item -> rejects unsupported_target, session stays alive
        val needId = harness.lastNeedRequest()!!.getJSONObject("detail").getString("requestId")
        harness.engine.handle(
            sessionMessage(
                "bad-supply",
                "linked_supply",
                JSONObject()
                    .put("requestId", needId)
                    .put("endOfList", false)
                    .put("items", JSONArray().put(itemJson("bad-s", "File", "http://127.0.0.1/b.mp4"))),
            ),
        )
        runCurrent()
        assertEquals("unsupported_target", harness.resultFor("bad-supply")!!.getString("error"))
        assertTrue(harness.engine.isActive())

        // Append still works afterwards
        harness.engine.handle(
            sessionMessage(
                "good-append",
                "linked_append",
                JSONObject().put("items", JSONArray().put(itemJson("ep4", "Good 4"))),
            ),
        )
        runCurrent()
        assertTrue(harness.resultFor("good-append")!!.getBoolean("ok"))
        assertEquals(listOf("Good 3", "Good 4"), harness.gateway.partyQueue.items.map { it.title })
    }

    @Test
    fun `document binding rejects mismatched tab or navigation generation with session_ended`() = runTest {
        val harness = newHarness()
        harness.open(openRequest(tabId = 1, navigationGeneration = 5))
        runCurrent()

        harness.engine.handle(
            sessionMessage(bridgeRequestId = "wrong-tab", type = "linked_ping", tabId = 2, navigationGeneration = 5),
        )
        harness.engine.handle(
            sessionMessage(bridgeRequestId = "wrong-gen", type = "linked_ping", tabId = 1, navigationGeneration = 6),
        )
        runCurrent()

        assertEquals("session_ended", harness.resultFor("wrong-tab")!!.getString("error"))
        assertEquals("session_ended", harness.resultFor("wrong-gen")!!.getString("error"))
        assertTrue(harness.engine.isActive())
    }

    private fun linkedItem(
        id: String,
        title: String = "Video $id",
        url: String = "https://cdn.example.com/$id.mp4",
    ) = LinkedPageCastItem(
        id = id,
        payload = PlayPayload(
            url = url,
            title = title,
            detected_by = "linked_page",
        ),
    )

    private fun itemJson(
        id: String,
        title: String = "Video $id",
        url: String = "https://cdn.example.com/$id.mp4",
    ) = JSONObject()
        .put("id", id)
        .put("url", url)
        .put("title", title)

    private fun openRequest(
        bridgeRequestId: String = "req-open",
        sessionId: String = "sess-1",
        origin: String = "https://app.example.com",
        tabId: Int = 1,
        navigationGeneration: Long = 1L,
        items: List<LinkedPageCastItem> = listOf(linkedItem("ep1")),
        startIndex: Int = 0,
        destinationId: String? = null,
    ) = LinkedPageCastOpenRequest(
        bridgeRequestId = bridgeRequestId,
        sessionId = sessionId,
        origin = origin,
        tabId = tabId,
        navigationGeneration = navigationGeneration,
        items = items,
        startIndex = startIndex,
        playlistMetadata = null,
        skipPreplay = false,
        requestedPrivateOrigins = emptySet(),
        destinationId = destinationId,
    )

    private fun sessionMessage(
        bridgeRequestId: String,
        type: String,
        payload: JSONObject? = null,
        sessionId: String = "sess-1",
        origin: String = "https://app.example.com",
        tabId: Int = 1,
        navigationGeneration: Long = 1L,
    ) = JSONObject()
        .put("bridgeRequestId", bridgeRequestId)
        .put("type", type)
        .put("sessionId", sessionId)
        .put("origin", origin)
        .put("tabId", tabId)
        .put("navigationGeneration", navigationGeneration)
        .apply { if (payload != null) put("payload", payload) }

    companion object {
        private fun defaultParty(
            host: Boolean = true,
            policy: TogetherPolicy = TogetherPolicy.Anyone,
            locked: Boolean = false,
        ) = PhoneTogether.Party(
            roomId = "room-1",
            invite = "https://party.playbridge.app/j/room-1#k=a&h=b",
            relay = "https://party.playbridge.app",
            me = "me-1",
            host = host,
            connected = true,
            policy = policy,
            locked = locked,
        )
    }
}
