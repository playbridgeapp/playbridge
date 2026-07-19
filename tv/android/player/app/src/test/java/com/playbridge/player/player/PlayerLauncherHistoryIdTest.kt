package com.playbridge.player.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
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
    fun `unrelated single videos retain distinct identities`() {
        val first = PlayPayload(url = "https://example.invalid/1")
        val second = PlayPayload(url = "https://example.invalid/2")

        assertNotEquals(PlayerLauncher.historyId(listOf(first)), PlayerLauncher.historyId(listOf(second)))
    }
}
