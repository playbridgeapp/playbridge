package com.playbridge.sender.browser

import org.junit.Assert.assertEquals
import org.junit.Test

class RetiredRouteTest {
    @Test
    fun `retired and unknown saved routes return to dashboard`() {
        for (route in listOf("library", "debridlibrary", "addonsettings", "librarydetail|123|tv|fixture", "unknown")) {
            assertEquals(Screen.Dashboard, Screen.Saver.restore(route))
        }
    }

    @Test
    fun `retained detail routes continue to restore`() {
        assertEquals(Screen.IptvDetail(7), Screen.Saver.restore("iptvdetail|7"))
        assertEquals(Screen.CollectionDetail(8), Screen.Saver.restore("collectiondetail|8"))
        assertEquals(Screen.Browser, Screen.Saver.restore("browser"))
    }
}
