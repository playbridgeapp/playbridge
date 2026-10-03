package com.playbridge.sender.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class DashboardReorderDialogTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun handleDragMovesRowsAndPersistsOnlyOnRelease() {
        val tiles = mutableStateListOf(
            DashboardReorderTile("browser", "Browser"),
            DashboardReorderTile("library", "Legacy Library"),
            DashboardReorderTile("streams", "Streams"),
        )
        var saved: List<String>? = null
        var chosen: String? = null
        compose.setContent {
            MaterialTheme {
                DashboardReorderDialog(tiles, onReorder = { ids ->
                    saved = ids
                    val reordered = ids.map { id -> tiles.first { it.id == id } }
                    tiles.clear()
                    tiles.addAll(reordered)
                }, onChoosePosition = { chosen = it }, onDismiss = {})
            }
        }
        val handle = compose.onNodeWithContentDescription("Drag Browser to reorder")
        val start = handle.fetchSemanticsNode().positionInRoot.y
        val end = compose.onNodeWithContentDescription("Drag Streams to reorder").fetchSemanticsNode().positionInRoot.y
        handle.performTouchInput { swipe(center, center + Offset(0f, end - start + 32f), 500) }
        compose.runOnIdle {
            assertEquals(listOf("library", "streams", "browser"), saved)
            assertNull(chosen)
        }
        val after = compose.onNodeWithContentDescription("Drag Browser to reorder").fetchSemanticsNode().positionInRoot.y
        val above = compose.onNodeWithContentDescription("Drag Legacy Library to reorder").fetchSemanticsNode().positionInRoot.y
        assertTrue(after > above)
    }

    @Test
    fun doneDismissesThePopup() {
        var dismissed = false
        compose.setContent {
            MaterialTheme {
                DashboardReorderDialog(
                    listOf(DashboardReorderTile("browser", "Browser")),
                    onReorder = {}, onChoosePosition = {}, onDismiss = { dismissed = true },
                )
            }
        }
        compose.onNodeWithText("Done").performClick()
        compose.runOnIdle { assertTrue(dismissed) }
    }

    @Test
    fun tappingRowStillChoosesAnExactPosition() {
        var chosen: String? = null
        compose.setContent {
            MaterialTheme {
                DashboardReorderDialog(
                    listOf(DashboardReorderTile("streams", "Streams")),
                    onReorder = {}, onChoosePosition = { chosen = it }, onDismiss = {},
                )
            }
        }
        compose.onNodeWithText("Streams").performClick()
        compose.runOnIdle { assertEquals("streams", chosen) }
    }

    @Test
    fun doubleDigitNumbersHaveAGutterAndDoNotOverflowAtLargeFontScale() {
        var gutterPx = 0f
        compose.setContent {
            val originalDensity = LocalDensity.current
            gutterPx = 12f * originalDensity.density
            CompositionLocalProvider(LocalDensity provides Density(originalDensity.density, fontScale = 2f)) {
                MaterialTheme {
                    DashboardReorderDialog(
                        (1..12).map { DashboardReorderTile("tile-$it", "Tile $it") },
                        onReorder = {}, onChoosePosition = {}, onDismiss = {},
                    )
                }
            }
        }
        compose.onNode(hasScrollAction()).performScrollToIndex(9)
        val number = compose.onNodeWithText("10", useUnmergedTree = true)
        val listBounds = compose.onNode(hasScrollAction()).fetchSemanticsNode().boundsInRoot
        val numberBounds = number.fetchSemanticsNode().boundsInRoot
        assertTrue("Number sits against the list clip", numberBounds.left - listBounds.left >= gutterPx - 1f)
        val layouts = mutableListOf<TextLayoutResult>()
        number.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { action -> action(layouts) }
        assertEquals(1, layouts.size)
        assertEquals(1, layouts.single().lineCount)
        assertFalse("Number overflows its column", layouts.single().hasVisualOverflow)
    }

    @Test
    fun cancelledDragDoesNotSaveItsPreview() {
        var saved: List<String>? = null
        compose.setContent {
            MaterialTheme {
                DashboardReorderDialog(
                    listOf(DashboardReorderTile("browser", "Browser"), DashboardReorderTile("streams", "Streams")),
                    onReorder = { saved = it }, onChoosePosition = {}, onDismiss = {},
                )
            }
        }
        val handle = compose.onNodeWithContentDescription("Drag Browser to reorder")
        val start = handle.fetchSemanticsNode().positionInRoot.y
        val end = compose.onNodeWithContentDescription("Drag Streams to reorder").fetchSemanticsNode().positionInRoot.y
        handle.performTouchInput {
            down(center)
            moveTo(center + Offset(0f, end - start + 32f), delayMillis = 100)
            cancel()
        }
        compose.runOnIdle { assertNull(saved) }
        val browser = compose.onNodeWithContentDescription("Drag Browser to reorder").fetchSemanticsNode().positionInRoot.y
        val streams = compose.onNodeWithContentDescription("Drag Streams to reorder").fetchSemanticsNode().positionInRoot.y
        assertTrue(browser < streams)
    }
}
