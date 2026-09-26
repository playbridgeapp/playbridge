package com.playbridge.sender.cast

import org.junit.Assert.assertEquals
import org.junit.Test

class CastSheetTabOrderTest {
    @Test
    fun allCategoriesPrioritizeVideoSubtitlesAudioImages() {
        assertEquals(
            listOf(
                DetectedMediaKind.VIDEO,
                DetectedMediaKind.SUBTITLE,
                DetectedMediaKind.AUDIO,
                DetectedMediaKind.IMAGE,
            ),
            prioritizedCastSheetTabs(1, 1, 1, 1),
        )
    }

    @Test
    fun videoAndSubtitleHideEmptyAudioAndImagesByDefault() {
        assertEquals(
            listOf(
                DetectedMediaKind.VIDEO,
                DetectedMediaKind.SUBTITLE,
            ),
            prioritizedCastSheetTabs(1, 0, 1, 0),
        )
    }

    @Test
    fun audioSubtitleAndImagesLeadEmptyVideo() {
        assertEquals(
            listOf(
                DetectedMediaKind.SUBTITLE,
                DetectedMediaKind.AUDIO,
                DetectedMediaKind.IMAGE,
                DetectedMediaKind.VIDEO,
            ),
            prioritizedCastSheetTabs(0, 1, 1, 1),
        )
    }

    @Test
    fun imagesAloneLeadRemainingTabsInDefaultOrder() {
        assertEquals(
            listOf(
                DetectedMediaKind.IMAGE,
                DetectedMediaKind.VIDEO,
                DetectedMediaKind.SUBTITLE,
            ),
            prioritizedCastSheetTabs(0, 0, 0, 1),
        )
    }

    @Test
    fun noMediaKeepsDefaultOrder() {
        assertEquals(
            listOf(
                DetectedMediaKind.VIDEO,
                DetectedMediaKind.SUBTITLE,
            ),
            prioritizedCastSheetTabs(0, 0, 0, 0),
        )
    }

    @Test
    fun lateSpaSubtitleDetectionMovesSubtitlesBeforeAudio() {
        assertEquals(
            listOf(DetectedMediaKind.VIDEO, DetectedMediaKind.AUDIO, DetectedMediaKind.SUBTITLE),
            prioritizedCastSheetTabs(1, 1, 0, 0),
        )
        assertEquals(
            listOf(DetectedMediaKind.VIDEO, DetectedMediaKind.SUBTITLE, DetectedMediaKind.AUDIO),
            prioritizedCastSheetTabs(1, 1, 1, 0),
        )
    }

    @Test
    fun settingCanKeepEmptyAudioAndImagesVisible() {
        assertEquals(
            listOf(
                DetectedMediaKind.VIDEO,
                DetectedMediaKind.SUBTITLE,
                DetectedMediaKind.AUDIO,
                DetectedMediaKind.IMAGE,
            ),
            prioritizedCastSheetTabs(0, 0, 0, 0, hideEmptyAudioAndImages = false),
        )
    }
}
