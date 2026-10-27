package com.playbridge.sender.cast

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PhoneQueueWindowTest {

    private fun ids(range: IntRange) = range.map { "e$it" }
    private val order = ids(0..9_999)

    /** Applies [plan] to [tv] the way the receiver would. */
    private fun applyTo(tv: List<String>, plan: WindowPlan): List<String> {
        val queue = (tv - plan.removes.toSet() + plan.adds).toMutableList()
        for ((id, before) in plan.moves) {
            queue.remove(id)
            if (before == null) queue.add(id) else queue.add(queue.indexOf(before), id)
        }
        return queue
    }

    @Test
    fun startFillsTheWindowAhead() {
        val plan = PhoneQueueWindow.plan(order, 0, listOf("e0"), "e0")
        assertEquals(ids(1..20), plan.adds)
        assertTrue(plan.removes.isEmpty() && plan.moves.isEmpty())
    }

    @Test
    fun nothingToDoWhileEnoughIsQueued() {
        assertTrue(PhoneQueueWindow.plan(order, 4, ids(0..20), "e4").isEmpty)
    }

    @Test
    fun topsUpOnceTenOrFewerAreLeftAhead() {
        val plan = PhoneQueueWindow.plan(order, 10, ids(0..20), "e10")
        assertEquals(ids(21..30), plan.adds)
        assertTrue(plan.removes.isEmpty())
    }

    @Test
    fun trimsPlayedItemsDownToTen() {
        val plan = PhoneQueueWindow.plan(order, 16, ids(0..32), "e16")
        assertEquals(ids(0..5), plan.removes)
        assertTrue(plan.adds.isEmpty())
    }

    @Test
    fun windowStaysFarBelowTheTvLimit() {
        var tv = listOf("e0")
        for (cursor in 0 until 500) {
            tv = applyTo(tv, PhoneQueueWindow.plan(order, cursor, tv, "e$cursor"))
            assertTrue(tv.size <= PhoneQueueWindow.BEHIND_MAX + 1 + PhoneQueueWindow.AHEAD)
            assertEquals("e${cursor + 1}", tv[tv.indexOf("e$cursor") + 1])
        }
    }

    @Test
    fun playNextInsideTheWindowAddsThenMovesIntoPlace() {
        val edited = listOf("e0", "x", "y") + ids(1..9_999)
        val tv = ids(0..15)
        val plan = PhoneQueueWindow.plan(edited, 0, tv, "e0")
        assertEquals(listOf("x", "y"), plan.adds)
        assertTrue(plan.removes.isEmpty())
        assertEquals(listOf("e0", "x", "y") + ids(1..15), applyTo(tv, plan))
    }

    @Test
    fun addToEndOutsideTheWindowSendsNothing() {
        assertTrue(PhoneQueueWindow.plan(order + "z", 4, ids(0..20), "e4").isEmpty)
    }

    @Test
    fun endOfListAddsOnlyWhatIsLeft() {
        val short = ids(0..5)
        assertEquals(ids(4..5), PhoneQueueWindow.plan(short, 2, ids(0..3), "e2").adds)
        assertTrue(PhoneQueueWindow.plan(short, 5, short, "e5").isEmpty)
    }

    @Test
    fun itemsAnotherControllerAddedAreLeftAlone() {
        val plan = PhoneQueueWindow.plan(order, 0, listOf("e0", "foreign") + ids(1..14), "e0")
        assertTrue(plan.isEmpty)
    }

    @Test
    fun staleItemsAheadAfterAJumpBackAreRemoved() {
        // The phone jumped back to e2 while e5 (already played on the phone's list) sits ahead.
        val plan = PhoneQueueWindow.plan(listOf("e0", "e1", "e2", "e3", "e4", "e5"), 4, listOf("e4", "e5", "e1"), "e4")
        assertEquals(listOf("e1"), plan.removes)
    }

    @Test
    fun jumpBackFarDropsTheOldWindowInsteadOfFillingTheGap() {
        // Was playing around e5000; jumped to e100, which was added after the old window.
        val tv = ids(4_990..5_020) + "e100"
        val plan = PhoneQueueWindow.plan(order, 100, tv, "e100")
        assertEquals(ids(101..120), plan.adds)
        val after = applyTo(tv, plan)
        assertEquals(ids(100..120), after.drop(after.indexOf("e100")))
    }

    @Test
    fun jumpBackWithOldItemsAheadRemovesThem() {
        val plan = PhoneQueueWindow.plan(order, 100, listOf("e100") + ids(5_001..5_020), "e100")
        assertEquals(ids(5_001..5_020), plan.removes)
        assertEquals(ids(101..120), plan.adds)
    }

    @Test
    fun respectsTheTvLimit() {
        val crowded = listOf("e0") + (1..195).map { "f$it" }
        val plan = PhoneQueueWindow.plan(order, 0, crowded, "e0")
        assertEquals(ids(1..4), plan.adds)
    }

    @Test
    fun adoptsRemovalsMadeOnTheTv() {
        val tv = listOf("e0", "e1", "e2", "e4", "e5")
        val adopted = PhoneQueueWindow.adopt(ids(0..7), 1, setOf("e0", "e1", "e2", "e3", "e4", "e5"), tv, "e1")
        assertEquals(listOf("e0", "e1", "e2", "e4", "e5", "e6", "e7"), adopted)
    }

    @Test
    fun adoptsReorderingMadeOnTheTv() {
        val tv = listOf("e0", "e3", "e1", "e2")
        val adopted = PhoneQueueWindow.adopt(ids(0..5), 0, setOf("e0", "e1", "e2", "e3"), tv, "e0")
        assertEquals(listOf("e0", "e3", "e1", "e2", "e4", "e5"), adopted)
    }

    @Test
    fun adoptKeepsItemsNotYetSent() {
        val adopted = PhoneQueueWindow.adopt(ids(0..30), 0, ids(0..20).toSet(), ids(0..20), "e0")
        assertEquals(ids(0..30), adopted)
    }
}
