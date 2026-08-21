package com.playbridge.player.player

import com.playbridge.shared.protocol.decodePlaylistPayloadJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import playbridge.PlayPayload

class PlayerLauncherHistoryIdTest {
    @Test
    fun `binge group keeps history identity stable as lazy queue grows`() {
        val first = PlayPayload(url = "https://example.invalid/1", binge_group = "series-release")
        val second = PlayPayload(url = "https://example.invalid/2", binge_group = "series-release")

        assertEquals(PlayerLauncher.historyId(listOf(first)), PlayerLauncher.historyId(listOf(first, second)))
    }

    @Test
    fun `history payload preserves the pre-play preference`() {
        val item = PlayPayload(url = "https://example.invalid/song.mp3")

        val payload = decodePlaylistPayloadJson(
            PlayerLauncher.historyPayloadJson(listOf(item), 0, skipPreplay = true),
        )

        assertTrue(payload?.skip_preplay == true)
    }

    @Test
    fun `unrelated single videos retain distinct identities`() {
        val first = PlayPayload(url = "https://example.invalid/1")
        val second = PlayPayload(url = "https://example.invalid/2")

        assertNotEquals(PlayerLauncher.historyId(listOf(first)), PlayerLauncher.historyId(listOf(second)))
    }
}
