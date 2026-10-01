package com.playbridge.sender.browser

import android.widget.EditText
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.viewinterop.AndroidView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class BrowserToolbarFocusTest {
    @get:Rule val compose = createComposeRule()

    @Test fun pageInputKeepsFocusWhenSearchUpdatesUrl() {
        val url = mutableStateOf("https://streams.example/#/search")
        lateinit var pageInput: EditText
        compose.setContent {
            MaterialTheme {
                Column {
                    BrowserToolbar(
                        currentUrl = url.value,
                        isLoading = false,
                        onUrlChange = {},
                        onNavigate = {},
                        isTvConnected = false,
                        onTvClick = {},
                        onRemoteClick = {},
                        isPlayEnabled = false,
                        mediaCount = 0,
                        mediaKind = null,
                        onPlayClick = {},
                        onPlayLongClick = {},
                    )
                    AndroidView(factory = { context ->
                        EditText(context).also { pageInput = it }
                    })
                }
            }
        }
        compose.runOnIdle {
            assertTrue(pageInput.requestFocus())
        }
        for (query in listOf("a", "ab", "abc", "")) {
            compose.runOnIdle {
                pageInput.setText(query)
                url.value = "https://streams.example/#/search${if (query.isEmpty()) "" else "?q=$query"}"
            }
            compose.runOnIdle {
                assertTrue("Page input lost focus after query '$query'", pageInput.hasFocus())
                assertEquals(query, pageInput.text.toString())
            }
        }
    }

    @Test fun leavingAddressEditingStillClearsAddressFieldFocus() {
        val editing = mutableStateOf(false)
        compose.setContent {
            MaterialTheme {
                BrowserToolbar(
                    currentUrl = "https://streams.example/#/search",
                    isLoading = false,
                    onUrlChange = {},
                    onNavigate = {},
                    isTvConnected = false,
                    onTvClick = {},
                    onRemoteClick = {},
                    isPlayEnabled = false,
                    mediaCount = 0,
                    mediaKind = null,
                    onPlayClick = {},
                    onPlayLongClick = {},
                    isEditing = editing.value,
                    onEditingChange = { editing.value = it },
                )
            }
        }
        compose.onNode(hasSetTextAction()).performClick().performTextInput("a")
        compose.runOnIdle {
            assertTrue(editing.value)
            editing.value = false
        }
        compose.onNode(hasSetTextAction()).assertIsNotFocused()
    }
}
