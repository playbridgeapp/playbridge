package com.playbridge.sender.cast.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteAdaptiveLayoutTest {
    private val player = RemoteAdaptiveSections(info = true, upNext = true, seek = true)
    private fun portrait(height: Int = 800, font: Float = 1f) = RemoteViewport(heightDp = height, fontScale = font)

    @Test fun normalPortraitLeavesRemainingHeightForInput() {
        val layout = RemoteAdaptiveLayout.resolve(portrait(), player)
        assertEquals(1, layout.columns)
        assertFalse(layout.collapseUpNext)
        assertFalse(layout.scrollUpper)
        assertEquals(264f, layout.inputHeightDp, 0.01f)
        assertEquals(listOf(RemoteSection.Header, RemoteSection.Info, RemoteSection.Input, RemoteSection.Seek, RemoteSection.Nav), layout.sectionOrder)
    }

    @Test fun shortPortraitCollapsesQueueBeforeScrolling() {
        val layout = RemoteAdaptiveLayout.resolve(portrait(620), player)
        assertTrue(layout.collapseUpNext)
        assertFalse(layout.scrollUpper)
        assertEquals(196f, layout.inputHeightDp, 0.01f)
    }

    @Test fun veryShortPortraitScrollsUpperAndKeepsInputMinimumAndPinnedControls() {
        val layout = RemoteAdaptiveLayout.resolve(portrait(520), player)
        assertTrue(layout.collapseUpNext)
        assertTrue(layout.scrollUpper)
        assertEquals(160f, layout.inputHeightDp, 0.01f)
        assertEquals(168f, layout.pinnedHeightDp, 0.01f)
        assertEquals(listOf(RemoteSection.Seek, RemoteSection.Nav), layout.sectionOrder.takeLast(2))
    }

    @Test fun twoHundredPercentFontUsesScrollAndNeverSqueezesInput() {
        val layout = RemoteAdaptiveLayout.resolve(portrait(font = 2f), player)
        assertTrue(layout.collapseUpNext)
        assertTrue(layout.scrollUpper)
        assertEquals(160f, layout.inputHeightDp, 0.01f)
    }

    @Test fun landscapeUsesColumnsWithInputIndependentOfRightHandMedia() {
        val layout = RemoteAdaptiveLayout.resolve(RemoteViewport(widthDp = 900, heightDp = 450), player)
        assertEquals(2, layout.columns)
        assertEquals(RemoteColumnSplit.InputLeft, layout.split)
        assertEquals(394f, layout.inputHeightDp, 0.01f)
        assertTrue(layout.collapseUpNext)
        assertFalse(layout.scrollUpper)
        assertEquals(0f, layout.columnBodyDp, 0.01f)
    }

    @Test fun shortLandscapeKeepsInputMinimumEvenWhenItsColumnMustScroll() {
        val layout = RemoteAdaptiveLayout.resolve(RemoteViewport(widthDp = 700, heightDp = 180), player)
        assertEquals(2, layout.columns)
        assertEquals(160f, layout.inputHeightDp, 0.01f)
    }

    @Test fun explicitLandscapeViewportIsHonoured() {
        assertEquals(2, RemoteAdaptiveLayout.resolve(RemoteViewport(landscape = true), RemoteAdaptiveSections()).columns)
    }

    @Test fun browserVideoKeepsToolbarAndWaveWithoutTransport() {
        val state = RemoteUiState(
            context = RemoteContext.Browser,
            playback = RemotePlayback(title = "Example", reportedState = RemotePlaybackState.Playing, durationMs = 60_000),
        )
        val layout = resolveRemoteLayout(state, portrait())
        val adaptive = RemoteAdaptiveLayout.resolve(portrait(), RemoteAdaptiveSections(info = layout.info, toolbar = layout.browserRow, seek = layout.wave))
        assertEquals(listOf(RemoteSection.Header, RemoteSection.Info, RemoteSection.Toolbar, RemoteSection.Input, RemoteSection.Seek, RemoteSection.Nav), adaptive.sectionOrder)
    }

    @Test fun browserWithoutVideoGivesFreedHeightToInput() {
        val video = RemoteAdaptiveLayout.resolve(portrait(), RemoteAdaptiveSections(info = true, toolbar = true, seek = true))
        val empty = RemoteAdaptiveLayout.resolve(portrait(), RemoteAdaptiveSections(toolbar = true))
        assertTrue(empty.inputHeightDp > video.inputHeightDp)
        assertEquals(listOf(RemoteSection.Header, RemoteSection.Toolbar, RemoteSection.Input, RemoteSection.Nav), empty.sectionOrder)
    }

    @Test fun idleStillHasHeaderInputAndNav() {
        val layout = RemoteAdaptiveLayout.resolve(portrait(), RemoteAdaptiveSections())
        assertEquals(listOf(RemoteSection.Header, RemoteSection.Input, RemoteSection.Nav), layout.sectionOrder)
        assertFalse(layout.collapseUpNext)
        assertFalse(layout.scrollUpper)
        assertEquals(672f, layout.inputHeightDp, 0.01f)
    }

    @Test fun absentSectionsDoNotReserveTheirMeasuredHeights() {
        val measured = RemoteSectionHeights(info = 1000f, upNext = 1000f, toolbar = 1000f, seek = 1000f)
        assertEquals(RemoteAdaptiveLayout.resolve(portrait(), RemoteAdaptiveSections()), RemoteAdaptiveLayout.resolve(portrait(), RemoteAdaptiveSections(), measured))
    }

    @Test fun actualMeasurementsDriveCollapseInsteadOfEstimates() {
        val measured = RemoteSectionHeights(header = 60f, info = 80f, upNext = 100f, compactUpNext = 44f, seek = 86f, nav = 48f)
        val layout = RemoteAdaptiveLayout.resolve(portrait(650), player, measured)
        assertFalse(layout.collapseUpNext)
        assertFalse(layout.scrollUpper)
        assertEquals(236f, layout.inputHeightDp, 0.01f)
    }

    @Test fun inputMinimumIncludesItsCardPaddingAndModeTabs() {
        val layout = RemoteAdaptiveLayout.resolve(portrait(520), player)
        assertEquals(160f, layout.minimumInputHeightDp, 0.01f)
        assertEquals(160f, layout.inputHeightDp, 0.01f)
        assertTrue(layout.scrollUpper)
    }

    @Test fun s25PortraitThreeEpisodePlayerKeepsExpandedQueueWithMeasuredCardsAndInsets() {
        val state = RemoteUiState(
            receiver = testConfiguration(RemoteProfile.AndroidTv).receiver,
            context = RemoteContext.Player,
            capabilities = nativeRemoteCapabilities(RemoteProfile.AndroidTv, RemoteContext.Player, RemoteMediaKind.Video),
            playback = RemotePlayback(reportedState = RemotePlaybackState.Playing),
            queue = RemoteQueueState(items = List(3) { RemoteQueueItem(it, "Episode $it") }),
        )
        val viewport = RemoteViewport(412, 892, fontScale = 1f, insetDp = 80)
        val presentation = resolveRemoteLayout(state, viewport)
        assertFalse(presentation.input)
        assertTrue(presentation.showQueue)
        assertFalse(RemoteInputMode.Pad in presentation.modes)
        val layout = RemoteAdaptiveLayout.resolve(viewport,
            RemoteAdaptiveSections(info = presentation.info, upNext = presentation.showQueue,
                input = presentation.input, seek = presentation.wave, inputPresentation = presentation.inputPresentation),
            RemoteSectionHeights(header = 56f, info = 128f, upNext = 160f, compactUpNext = 48f,
                seek = 92f, nav = 48f),
        )
        assertFalse(layout.collapseUpNext)
        assertFalse(layout.scrollUpper)
        assertEquals(0f, layout.inputHeightDp, 0.01f)
        assertEquals(0f, layout.minimumInputHeightDp, 0.01f)
    }

    @Test fun noInputReservesNoMinimumAndGivesQueueTheFreedSpace() {
        val viewport = portrait(560)
        val withInput = RemoteAdaptiveLayout.resolve(viewport, player)
        val withoutInput = RemoteAdaptiveLayout.resolve(viewport, player.copy(input = false))
        assertTrue(withInput.collapseUpNext)
        assertFalse(withoutInput.collapseUpNext)
        assertFalse(withoutInput.scrollUpper)
        assertEquals(0f, withoutInput.minimumInputHeightDp, 0.01f)
        assertEquals(0f, withoutInput.inputHeightDp, 0.01f)
        assertFalse(RemoteSection.Input in withoutInput.sectionOrder)
        val wide = RemoteAdaptiveLayout.resolve(RemoteViewport(800, 420), player.copy(input = false))
        assertEquals(2, wide.columns)
        assertEquals(RemoteColumnSplit.InfoAndQueue, wide.split)
    }

    @Test fun landscapeWithoutFullInputSplitsInfoBesideQueueOnA2340x1080Phone() {
        // 2340×1080 at 420 dpi is 891×411 dp. Status, gesture inset and the screen's
        // 16 dp vertical padding leave about 350 dp. Steady card heights, normal font.
        val phone = RemoteViewport(widthDp = 891, heightDp = 350)
        val measured = RemoteSectionHeights(header = 56f, info = 132f, upNext = 168f, compactUpNext = 48f, seek = 86f, nav = 48f)
        val absent = RemoteAdaptiveLayout.resolve(phone, player.copy(input = false), measured)
        assertEquals(2, absent.columns)
        assertEquals(RemoteColumnSplit.InfoAndQueue, absent.split)
        assertFalse(absent.collapseUpNext)
        assertFalse(absent.scrollUpper)
        // 350 - header 56 - seek 86 - nav 48 - gaps 24.
        assertEquals(136f, absent.columnBodyDp, 0.01f)
        assertTrue(absent.columnBodyDp >= measured.info!!)
        assertEquals(0f, absent.inputHeightDp, 0.01f)
        assertFalse(RemoteSection.Input in absent.sectionOrder)

        val full = RemoteAdaptiveLayout.resolve(phone, player, measured)
        assertEquals(RemoteColumnSplit.InputLeft, full.split)
        assertEquals(2, full.columns)
        assertEquals(294f, full.inputHeightDp, 0.01f)
    }

    @Test fun shortLandscapeWithoutInputDoesNotCollapseTheQueueToHideIt() {
        val layout = RemoteAdaptiveLayout.resolve(RemoteViewport(700, 240), player.copy(input = false))
        assertEquals(RemoteColumnSplit.InfoAndQueue, layout.split)
        assertEquals(2, layout.columns)
        assertFalse(layout.collapseUpNext)
        assertTrue(layout.columnBodyDp < 120f)
    }

    @Test fun unmeasuredExpandedQueueDoesNotStayCollapsedFromAnInflatedFontEstimate() {
        val layout = RemoteAdaptiveLayout.resolve(portrait(1100, font = 2f), player,
            RemoteSectionHeights(header = 88f, info = 240f, seek = 208f, nav = 48f))
        assertFalse(layout.collapseUpNext)
        assertFalse(layout.scrollUpper)
        assertEquals(316f, layout.inputHeightDp, 0.01f)
    }

    @Test fun InsetsAndImeReduceAvailableHeight() {
        val full = RemoteAdaptiveLayout.resolve(portrait(), player)
        val inset = RemoteAdaptiveLayout.resolve(portrait().copy(insetDp = 280), player)
        assertFalse(full.scrollUpper)
        assertTrue(inset.scrollUpper)
        assertTrue(inset.inputHeightDp >= 160f)
    }

    @Test fun requestedMinimumCannotDropBelow160() {
        val layout = RemoteAdaptiveLayout.resolve(portrait(200).copy(widthDp = 100, minimumInputHeightDp = 20), RemoteAdaptiveSections())
        assertEquals(160f, layout.minimumInputHeightDp, 0.01f)
        assertEquals(160f, layout.inputHeightDp, 0.01f)
    }
}
