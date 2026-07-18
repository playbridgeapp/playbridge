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
                videoTrack("max:720"),
            ).hasSelectableVideoQualities(),
        )
        assertTrue(
            listOf(
                videoTrack("auto"),
                videoTrack("max:720"),
                videoTrack("max:1080"),
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
