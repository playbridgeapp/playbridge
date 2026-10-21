package com.playbridge.shared.player

import kotlin.test.Test
import kotlin.test.assertEquals

class PlayerChapterTest {
    @Test
    fun parsesMpvChapterListAndDropsUnnamedPlaceholder() {
        val raw = """[{"title":"Opening","time":0},{"title":"(unnamed)","time":312.5},{"time":600}]"""
        assertEquals(
            listOf(PlayerChapter(0L, "Opening"), PlayerChapter(312_500L), PlayerChapter(600_000L)),
            parseMpvChapterList(raw),
        )
    }

    @Test
    fun skipsMalformedEntriesAndInput() {
        assertEquals(
            listOf(PlayerChapter(5_000L)),
            parseMpvChapterList("""[{"title":"x"},{"time":"12"},"junk",{"time":5}]"""),
        )
        assertEquals(emptyList(), parseMpvChapterList("not json"))
        assertEquals(emptyList(), parseMpvChapterList("""{"time":1}"""))
    }
}
