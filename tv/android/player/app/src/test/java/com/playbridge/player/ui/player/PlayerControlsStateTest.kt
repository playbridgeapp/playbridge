package com.playbridge.player.ui.player

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerControlsStateTest {
    @Test
    fun videoQualityRequiresAtLeastTwoSelectableRenditions() {
        assertFalse(emptyList<UnifiedTrack>().hasSelectableVideoQualities())
        assertFalse(
            listOf(
                videoTrack("auto"),
                videoTrack("0:0"),
            ).hasSelectableVideoQualities(),
        )
        assertTrue(
            listOf(
                videoTrack("auto"),
                videoTrack("0:0"),
                videoTrack("0:1"),
            ).hasSelectableVideoQualities(),
        )
    }

    private fun videoTrack(id: String) = UnifiedTrack(
        id = id,
        name = id,
        isSelected = id == "auto",
        type = "video",
    )
}
