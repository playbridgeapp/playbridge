package com.playbridge.sender.library

import org.junit.Assert.assertEquals
import org.junit.Test

class UniquePageTest {
    private data class Item(val id: String, val title: String = id)

    @Test
    fun overlappingPagesKeepExistingPositionsAndAppendNewIds() {
        val first = listOf(Item("a"), Item("b"))
        val next = listOf(Item("b", "updated"), Item("c"), Item("c"), Item("d"))
        assertEquals(listOf(Item("a"), Item("b"), Item("c"), Item("d")),
            mergeUniquePage(first, next) { it.id })
    }

    @Test
    fun initialAndCachedPagesAreNormalizedToo() {
        val cached = listOf(Item("a"), Item("a"), Item("b"))
        assertEquals(listOf(Item("a"), Item("b")), mergeUniquePage(emptyList(), cached) { it.id })
        assertEquals(listOf(Item("a"), Item("b")), mergeUniquePage(cached, cached) { it.id })
    }
}
