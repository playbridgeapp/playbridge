package com.playbridge.sender.browser

import org.junit.Assert.assertEquals
import org.junit.Test

class BrowserImeInsetsTest {
    @Test fun `closed keyboard keeps the toolbar on the navigation bar`() {
        assertEquals(
            BrowserBottomSpace(toolbarBottomPx = 48, engineBottomPx = 0),
            browserBottomSpace(imeBottomPx = 0, navigationBarBottomPx = 48, bottomToolbarVisible = true),
        )
    }

    @Test fun `open keyboard lifts the toolbar and does not pad the engine again`() {
        assertEquals(
            BrowserBottomSpace(toolbarBottomPx = 820, engineBottomPx = 0),
            browserBottomSpace(imeBottomPx = 820, navigationBarBottomPx = 48, bottomToolbarVisible = true),
        )
    }

    @Test fun `hidden chrome stays edge to edge while the keyboard is closed`() {
        assertEquals(
            BrowserBottomSpace(toolbarBottomPx = 0, engineBottomPx = 0),
            browserBottomSpace(imeBottomPx = 0, navigationBarBottomPx = 48, bottomToolbarVisible = false),
        )
    }

    @Test fun `hidden chrome shrinks the engine by the full keyboard`() {
        assertEquals(
            BrowserBottomSpace(toolbarBottomPx = 0, engineBottomPx = 800),
            browserBottomSpace(imeBottomPx = 800, navigationBarBottomPx = 48, bottomToolbarVisible = false),
        )
    }

    @Test fun `negative insets are treated as zero`() {
        assertEquals(
            BrowserBottomSpace(toolbarBottomPx = 0, engineBottomPx = 0),
            browserBottomSpace(imeBottomPx = -1, navigationBarBottomPx = -4, bottomToolbarVisible = false),
        )
    }
}
