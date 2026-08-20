package com.playbridge.sender.browser

import org.junit.Assert.assertEquals
import org.junit.Test

class MenuSheetPagingTest {

    @Test
    fun `empty menu still has one page`() {
        assertEquals(1, menuPageCount(0))
    }

    @Test
    fun `items that fill page one stay on a single page`() {
        assertEquals(1, menuPageCount(MENU_PAGE_SIZE))
        assertEquals(1, menuPageCount(MENU_PAGE_SIZE - 1))
    }

    @Test
    fun `overflow items open a second page`() {
        assertEquals(2, menuPageCount(MENU_PAGE_SIZE + 1))
        assertEquals(2, menuPageCount(MENU_PAGE_SIZE * 2))
        assertEquals(3, menuPageCount(MENU_PAGE_SIZE * 2 + 1))
    }
}
