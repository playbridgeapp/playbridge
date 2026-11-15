package com.playbridge.sender.history

import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CastHistoryUiTest {
    private val zone = ZoneId.of("UTC")
    private val locale = Locale.US
    private fun at(y: Int, mo: Int, d: Int, h: Int = 12, mi: Int = 0) =
        ZonedDateTime.of(y, mo, d, h, mi, 0, 0, zone).toInstant().toEpochMilli()

    private val now = at(2026, 11, 15, 15, 0)
    private val h = 3_600_000L
    private val min = 60_000L

    private fun entry(
        id: String = "a",
        title: String? = "Sintel",
        host: String? = "durian.blender.org",
        receiver: String? = "Living Room TV",
        kind: String = "PLAYBRIDGE",
        startedAt: Long = now - 2 * h,
        pos: Long = 0, dur: Long = 0, reported: Boolean = false,
        finished: Boolean = false, live: Boolean = false, liveWatched: Long = 0,
        failed: Boolean = false, reason: String? = null,
        index: Int? = null, count: Int? = null,
        expiresAt: Long? = null, check: LinkCheckResult? = null, checkedAt: Long? = null,
        canReplay: Boolean = true, pageUrl: String? = "https://durian.blender.org/watch",
        preview: String? = null, mediaKind: String? = null,
    ) = CastHistoryEntry(
        id, title, host, pageUrl, receiver, kind, startedAt, pos, dur, reported, finished, live, liveWatched,
        failed, reason, index, count, preview, expiresAt, check, checkedAt, canReplay, mediaKind,
    )

    private fun row(e: CastHistoryEntry, ctx: RowContext = RowContext(now, zone, locale)) = buildRow(HistoryItem.Cast(e), ctx)

    // ---- time words ----

    @Test fun clockFormatsMinutesAndHours() {
        assertEquals("0:00", formatClock(0))
        assertEquals("2:58", formatClock(178_000))
        assertEquals("1:32:05", formatClock(5_525_000))
        assertEquals("0:00", formatClock(-5))
    }

    @Test fun agoSaysItInPlainWords() {
        fun ago(ms: Long) = formatAgo(now - ms, now, zone, locale)
        assertEquals("just now", ago(20_000))
        assertEquals("20 min ago", ago(20 * min))
        assertEquals("2 h ago", ago(2 * h))
        assertEquals("5 h ago", ago(5 * h))
        assertEquals("just now", formatAgo(now + 5000, now, zone, locale))
    }

    @Test fun agoUsesCalendarDaysBeyondADay() {
        assertEquals("Yesterday", formatAgo(at(2026, 11, 14, 8), now, zone, locale))
        assertEquals("3 days ago", formatAgo(at(2026, 11, 12, 8), now, zone, locale))
        assertEquals("last week", formatAgo(at(2026, 11, 6, 8), now, zone, locale))
        assertEquals("3 weeks ago", formatAgo(at(2026, 10, 24, 8), now, zone, locale))
        assertEquals("Sep 3", formatAgo(at(2026, 9, 3, 8), now, zone, locale))
    }

    @Test fun dayLabelsAreTodayYesterdayThenDates() {
        assertEquals("Today", dayLabel(now - h, now, zone, locale))
        assertEquals("Yesterday", dayLabel(at(2026, 11, 14, 23), now, zone, locale))
        assertEquals("Nov 10", dayLabel(at(2026, 11, 10), now, zone, locale))
        assertEquals("Dec 30, 2025", dayLabel(at(2025, 12, 30), now, zone, locale))
    }

    @Test fun watchedAndBytes() {
        assertEquals("25 min", formatWatched(25 * min))
        assertEquals("1 min", formatWatched(5_000))
        assertEquals("1 h 5 min", formatWatched(65 * min))
        assertEquals("2 h", formatWatched(120 * min))
        assertEquals("2.4 MB", formatBytes(2_400_000))
        assertEquals("310 KB", formatBytes(310_000))
        assertEquals("0 KB", formatBytes(0))
    }

    // ---- row states (every one in States and Expired) ----

    @Test fun finishedRow() {
        val r = row(entry(pos = 596_000, dur = 596_000, reported = true, finished = true, startedAt = now - 2 * h))
        assertEquals("Finished · 2 h ago", r.status)
        assertEquals("9:56", r.badge)
        assertEquals("durian.blender.org · Living Room TV", r.subtitle)
        assertEquals(RowTrailing.Menu, r.trailing)
        assertEquals(1f, r.progress!!, 0.001f)
    }

    @Test fun stoppedRow() {
        val r = row(entry(title = "Tears of Steel", pos = 178_000, dur = 734_000, reported = true, startedAt = now - 5 * h))
        assertEquals("Stopped at 2:58 · 5 h ago", r.status)
        assertEquals("12:14", r.badge)
        assertEquals(178f / 734f, r.progress!!, 0.001f)
        assertNull(r.link)
    }

    @Test fun playlistRowShowsItsPlaceAndWord() {
        val r = row(entry(pos = 521_000, dur = 730_000, reported = true, index = 2, count = 8, startedAt = now - 20 * h))
        assertEquals("3 of 8", r.badge)
        assertEquals("Playlist · Living Room TV", r.subtitle)
    }

    @Test fun playingRowNamesTheReceiverAndLeavesItsLineLive() {
        val r = row(entry(pos = 408_000, dur = 888_000, reported = true), RowContext(now, zone, locale, playingId = "a"))
        assertTrue(r.playing)
        assertEquals("Playing on Living Room TV", r.subtitle)
        assertNull(r.status)
        assertEquals(RowTrailing.Remote, r.trailing)
        assertEquals(StatusTone.Playing, r.statusTone)
    }

    @Test fun onlyTheMatchingRowIsPlaying() {
        val ctx = RowContext(now, zone, locale, playingId = "other")
        assertFalse(row(entry(pos = 20_000, dur = 100_000, reported = true), ctx).playing)
    }

    @Test fun failedRowHasShortReasonAndTryAgain() {
        val r = row(entry(title = "Spring", failed = true, reason = CastPositionTracker.FAIL_RECEIVER_ERROR, receiver = "Bedroom TV", startedAt = at(2026, 11, 14, 9)))
        assertEquals("The TV couldn't load it · Yesterday", r.status)
        assertEquals(StatusTone.Error, r.statusTone)
        assertEquals(RowTrailing.TryAgain, r.trailing)
        assertEquals(TileKind.Failed, r.tile)
        assertNull(r.badge)
    }

    @Test fun failureReasonsAreShortAndNeverRawText() {
        assertEquals("Stopped before it started", failureText(CastPositionTracker.FAIL_STOPPED_EARLY, "PLAYBRIDGE"))
        assertEquals("Couldn't reach the TV", failureText(CastPositionTracker.FAIL_NOT_SENT, "PLAYBRIDGE"))
        assertEquals("Couldn't reach the device", failureText(CastPositionTracker.FAIL_NOT_SENT, "DLNA"))
        assertEquals("The device couldn't load it", failureText(CastPositionTracker.FAIL_RECEIVER_ERROR, "ROKU"))
        assertEquals("Didn't play", failureText("some secret text", "PLAYBRIDGE"))
        assertEquals("Didn't play", failureText(null, "PLAYBRIDGE"))
    }

    @Test fun failedWithoutAReplaySourceHasNoTryAgain() {
        assertEquals(RowTrailing.Menu, row(entry(failed = true, canReplay = false)).trailing)
    }

    @Test fun noPictureFallsBackToATileByMediaKind() {
        assertEquals(TileKind.Video, row(entry(pos = 1_880_000, dur = 3_600_000, reported = true)).tile)
        assertEquals(TileKind.Audio, row(entry(mediaKind = "audio", pos = 5_000, dur = 100_000, reported = true)).tile)
        assertEquals(TileKind.Image, row(entry(mediaKind = "image")).tile)
        assertNull(row(entry(pos = 5_000, dur = 100_000, reported = true)).previewPath)
        assertEquals("/p/a.jpg", row(entry(preview = "/p/a.jpg")).previewPath)
    }

    @Test fun liveRowShowsTimeWatched() {
        val r = row(entry(title = "News channel", host = "IPTV", live = true, liveWatched = 25 * min, startedAt = now - 4 * 24 * h))
        assertEquals("Watched for 25 min · 3 days ago".replace("3", "4"), r.status)
        assertEquals("LIVE", r.badge)
        assertEquals(TileKind.Live, r.tile)
        assertNull(r.progress)
    }

    @Test fun positionNotReportedRowClaimsNoProgress() {
        val r = row(entry(title = "Podcast episode 12", host = "Audio", receiver = "Kitchen speaker", kind = "DLNA", mediaKind = "audio", startedAt = at(2026, 11, 7, 9)))
        assertEquals("Position not reported · last week", r.status)
        assertEquals("Audio · Kitchen speaker (DLNA)", r.subtitle)
        assertNull(r.progress)
        assertNull(r.badge)
    }

    @Test fun legacyRowHasWhatItHasAndNothingMore() {
        val old = HistoryItem.Old(7, "Old video", "https://x.example/v.mp4", isBrowser = false, atMs = now - 3 * h)
        val r = buildRow(old, RowContext(now, zone, locale))
        assertEquals("Old video", r.title)
        assertNull(r.previewPath); assertNull(r.progress); assertNull(r.badge)
        assertEquals(RowTrailing.OldMenu, r.trailing)
        assertEquals("old-7", r.key)
        assertEquals("https://x.example/v.mp4", buildRow(old.copy(title = null), RowContext(now, zone, locale)).title)
    }

    // ---- link states ----

    @Test fun expiresSoonRow() {
        val r = row(entry(pos = 178_000, dur = 734_000, reported = true, startedAt = now - 5 * h, expiresAt = now + 2 * h))
        assertEquals("Link expires in 2 h", r.link)
        assertEquals(StatusTone.Warning, r.linkTone)
        assertEquals(RowTrailing.Menu, r.trailing)
    }

    @Test fun expiredRowHasRefreshButtonWhenThePageIsKnown() {
        val e = entry(title = "Cosmos", pos = 521_000, dur = 730_000, reported = true, expiresAt = now - 2 * h)
        val r = row(e)
        assertEquals("Link expired 2 h ago", r.link)
        assertEquals(StatusTone.Error, r.linkTone)
        assertEquals(RowTrailing.FreshLink, r.trailing)
        assertEquals("Stopped at 8:41 · 2 h ago", r.status)
        assertEquals(RowTrailing.Menu, row(e.copy(pageUrl = null)).trailing)
    }

    @Test fun expiredFromAFailedCheckSaysLinkExpired() {
        val r = row(entry(pos = 5_000, dur = 100_000, reported = true, check = LinkCheckResult.Expired, checkedAt = now - min))
        assertEquals("Link expired", r.link)
    }

    @Test fun checkingRow() {
        val r = row(entry(pos = 190_000, dur = 500_000, reported = true), RowContext(now, zone, locale, checking = setOf("a")))
        assertEquals("Checking the link…", r.link)
    }

    @Test fun couldNotCheckRow() {
        val r = row(entry(pos = 62_000, dur = 500_000, reported = true, check = LinkCheckResult.CouldNotCheck, checkedAt = now - min))
        assertEquals("Couldn’t check · may have expired", r.link)
        assertEquals(StatusTone.Warning, r.linkTone)
    }

    @Test fun aLaterSuccessfulCheckClearsTheLinkLine() {
        val r = row(entry(pos = 62_000, dur = 500_000, reported = true, check = LinkCheckResult.Works, checkedAt = now - min))
        assertNull(r.link)
    }

    // ---- filters, search, merging ----

    private val mixed: List<HistoryItem> = listOf(
        entry("u1", pos = 100_000, dur = 500_000, reported = true),
        entry("u2", pos = 200_000, dur = 500_000, reported = true, expiresAt = now - h),
        entry("f1", failed = true, reason = CastPositionTracker.FAIL_RECEIVER_ERROR),
        entry("f2", failed = true, expiresAt = now - h),
        entry("done", pos = 500_000, dur = 500_000, reported = true, finished = true),
        entry("live", live = true),
        entry("start", pos = 3_000, dur = 500_000, reported = true),
    ).map { HistoryItem.Cast(it) } + HistoryItem.Old(1, "x", "https://x.example", false, now)

    @Test fun filterCountsFollowTheChips() {
        val c = filterCounts(mixed, now)
        assertEquals(8, c[HistoryFilter.ALL])
        assertEquals(2, c[HistoryFilter.UNFINISHED])
        assertEquals(2, c[HistoryFilter.FAILED])
        assertEquals(2, c[HistoryFilter.EXPIRED])
    }

    @Test fun oldEntriesCountOnlyUnderAll() {
        val old = HistoryItem.Old(1, "x", "u", false, now)
        HistoryFilter.entries.filter { it != HistoryFilter.ALL }.forEach { assertFalse(old.matches(it, now)) }
        assertTrue(old.matches(HistoryFilter.ALL, now))
    }

    @Test fun aLinkBeingCheckedIsNotCountedExpiredYet() {
        val e = HistoryItem.Cast(entry("u2", pos = 100_000, dur = 500_000, reported = true, expiresAt = now - h))
        assertTrue(e.matches(HistoryFilter.EXPIRED, now))
        assertFalse(e.matches(HistoryFilter.EXPIRED, now, checking = setOf("u2")))
    }

    @Test fun searchMatchesTitleSiteAndReceiver() {
        val item = HistoryItem.Cast(entry(title = "Sintel", host = "durian.blender.org", receiver = "Bedroom TV"))
        assertTrue(item.matchesQuery("sint"))
        assertTrue(item.matchesQuery("BLENDER"))
        assertTrue(item.matchesQuery("bedroom"))
        assertTrue(item.matchesQuery("  "))
        assertFalse(item.matchesQuery("elephant"))
        assertTrue(HistoryItem.Old(1, "Old thing", "https://site.example/x", false, 0).matchesQuery("site.example"))
    }

    @Test fun mergeIsNewestFirstAcrossBothKinds() {
        val merged = mergeHistory(
            listOf(entry("a", startedAt = 300), entry("b", startedAt = 100)),
            listOf(HistoryItem.Old(1, "o", "u", false, 200)),
        )
        assertEquals(listOf("cast-a", "old-1", "cast-b"), merged.map { it.key })
    }

    // ---- day groups ----

    @Test fun rowsGroupByDay() {
        val items = listOf(
            HistoryItem.Cast(entry("a", startedAt = now - h)),
            HistoryItem.Cast(entry("b", startedAt = now - 3 * h)),
            HistoryItem.Cast(entry("c", startedAt = at(2026, 11, 14, 20))),
            HistoryItem.Old(1, "o", "u", false, at(2026, 11, 10, 9)),
        )
        val rows = items.map { buildRow(it, RowContext(now, zone, locale)) }
        val groups = groupByDay(rows, now, zone, locale)
        assertEquals(listOf("Today", "Yesterday", "Nov 10"), groups.map { it.label })
        assertEquals(listOf(2, 1, 1), groups.map { it.rows.size })
        assertEquals(3, groups.map { it.key }.toSet().size)
    }

    // ---- last watched ----

    @Test fun lastWatchedIsTheNewestUnfinishedReplayableCast() {
        val list = listOf(
            entry("new-done", finished = true, pos = 100_000, dur = 100_000, reported = true, startedAt = 900),
            entry("new-failed", failed = true, startedAt = 800),
            entry("new-noreplay", pos = 100_000, dur = 500_000, reported = true, canReplay = false, startedAt = 700),
            entry("pick", pos = 100_000, dur = 500_000, reported = true, startedAt = 600),
            entry("older", pos = 100_000, dur = 500_000, reported = true, startedAt = 500),
        )
        assertEquals("pick", lastWatched(list)!!.id)
        assertEquals("older", lastWatched(list, playingId = "pick")!!.id)
    }

    @Test fun lastWatchedIsAbsentWithoutOne() {
        assertNull(lastWatched(emptyList()))
        assertNull(lastWatched(listOf(entry(finished = true, pos = 1, dur = 1, reported = true), entry(failed = true))))
        // Under ten seconds in is not worth resuming.
        assertNull(lastWatched(listOf(entry(pos = 3_000, dur = 100_000, reported = true))))
    }

    // ---- actions ----

    private val seekTv = TargetInfo("Living Room TV", canQueue = true)

    @Test fun unfinishedCastOffersEverything() {
        val e = entry(pos = 178_000, dur = 734_000, reported = true)
        assertEquals(
            listOf(
                HistoryAction.Resume, HistoryAction.PlayFromStart, HistoryAction.ChooseWhere, HistoryAction.AddToQueue,
                HistoryAction.OpenPage, HistoryAction.SaveToCollection, HistoryAction.Share, HistoryAction.Remove,
            ),
            actionsFor(e, seekTv),
        )
    }

    @Test fun queueOnlyWhenTheTargetHasOne() {
        val e = entry(pos = 178_000, dur = 734_000, reported = true)
        assertFalse(HistoryAction.AddToQueue in actionsFor(e, TargetInfo("TV", canQueue = false)))
        assertFalse(HistoryAction.AddToQueue in actionsFor(e, TargetInfo()))
    }

    @Test fun pageOnlyWhenKnown() {
        assertFalse(HistoryAction.OpenPage in actionsFor(entry(pageUrl = null), seekTv))
        assertTrue(HistoryAction.OpenPage in actionsFor(entry(), seekTv))
    }

    @Test fun finishedAndUnreportedCastsHaveNoResume() {
        assertFalse(HistoryAction.Resume in actionsFor(entry(finished = true, pos = 5, dur = 5, reported = true), seekTv))
        assertFalse(HistoryAction.Resume in actionsFor(entry(), seekTv))
        assertFalse(HistoryAction.Resume in actionsFor(entry(live = true), seekTv))
    }

    @Test fun withoutAReplaySourceOnlyPageShareAndRemoveRemain() {
        assertEquals(
            listOf(HistoryAction.OpenPage, HistoryAction.Share, HistoryAction.Remove),
            actionsFor(entry(pos = 100_000, dur = 500_000, reported = true, canReplay = false), seekTv),
        )
    }

    @Test fun resumeNamesTheTarget() {
        val e = entry(pos = 178_000, dur = 734_000, reported = true)
        assertEquals("Resume from 2:58", resumeLabel(e))
        assertEquals("On Living Room TV, the device selected now", resumeSubtitle(seekTv))
        assertEquals("Choose a device to continue", resumeSubtitle(TargetInfo()))
        assertTrue(resumeSubtitle(TargetInfo("Roku", canSeekOnLoad = false)).contains("plays from the start"))
    }

    @Test fun sheetDetailLines() {
        val e = entry(title = "Tears of Steel", receiver = "Bedroom TV", pos = 178_000, dur = 734_000, reported = true, startedAt = at(2026, 11, 15, 14, 5))
        assertEquals("Stopped at 2:58 of 12:14", stoppedDetail(e))
        assertEquals("Bedroom TV · today, 14:05", playedOnDetail(e, now, zone, locale))
        assertEquals("Bedroom TV · yesterday, 09:00", playedOnDetail(e.copy(startedAtMs = at(2026, 11, 14, 9)), now, zone, locale))
        assertEquals("Position not reported", stoppedDetail(entry()))
    }

    // ---- expired sheet ----

    @Test fun expiredSheetOffersFreshLinkOnlyWithAPage() {
        val e = entry(pos = 521_000, dur = 730_000, reported = true, expiresAt = now - h)
        assertEquals(
            listOf(ExpiredAction.FreshLink, ExpiredAction.TryOldLink, ExpiredAction.CheckAgain, ExpiredAction.Remove),
            expiredActionsFor(e),
        )
        assertEquals(
            listOf(ExpiredAction.TryOldLink, ExpiredAction.CheckAgain, ExpiredAction.Remove),
            expiredActionsFor(e.copy(pageUrl = null)),
        )
        assertEquals(listOf(ExpiredAction.FreshLink, ExpiredAction.Remove), expiredActionsFor(e.copy(canReplay = false)))
    }

    @Test fun expiredNoticeSaysWhenItIsKnown() {
        assertEquals("This link expired yesterday at 21:40", expiredNotice(at(2026, 11, 14, 21, 40), now, zone, locale))
        assertEquals("This link expired today at 09:15", expiredNotice(at(2026, 11, 15, 9, 15), now, zone, locale))
        assertEquals("This link expired on Nov 3 at 08:00", expiredNotice(at(2026, 11, 3, 8), now, zone, locale))
        assertEquals("This link has expired", expiredNotice(null, now, zone, locale))
    }

    @Test fun freshLinkNoteAndToastCarryWhereYouStopped() {
        val e = entry(host = "cloud.blender.org", pos = 521_000, dur = 730_000, reported = true)
        assertEquals("Opens cloud.blender.org and finds the video again. You stopped at 8:41.", freshLinkNote(e))
        assertEquals("Find the video again. You stopped at 8:41.", freshLinkToast(e))
        val none = entry(host = null)
        assertEquals("Opens the page it came from and finds the video again.", freshLinkNote(none))
        assertEquals("Find the video again.", freshLinkToast(none))
    }

    // ---- settings maths ----

    @Test fun limitRemovalMaths() {
        assertEquals(13, removedByLimit(38, 25))
        assertEquals(0, removedByLimit(25, 25))
        assertEquals(0, removedByLimit(10, 25))
        assertEquals(1, removedByLimit(26, 25))
    }

    @Test fun limitHintNamesWhatTheSmallestChoiceRemoves() {
        assertEquals("38 kept now. Choosing 25 removes the 13 oldest right away, after you confirm.", limitHint(38))
        assertNull(limitHint(25))
        assertNull(limitHint(0))
    }

    @Test fun storageLineCountsCastsAndPictures() {
        assertEquals("38 casts · 31 pictures", storageLine(CastHistoryStorage(38, 31, 2_400_000)))
        assertEquals("1 cast · 1 picture", storageLine(CastHistoryStorage(1, 1, 0)))
        assertEquals("0 casts · 0 pictures", storageLine(CastHistoryStorage(0, 0, 0)))
    }

    @Test fun receiverLabelsNameTheProtocolForNonTvs() {
        assertEquals("Living Room TV", receiverLabel("Living Room TV", "PLAYBRIDGE"))
        assertEquals("Kitchen speaker (DLNA)", receiverLabel("Kitchen speaker", "DLNA"))
        assertEquals("Google Cast", receiverLabel(null, "GOOGLE_CAST"))
        assertEquals("TV", receiverLabel(" ", "PLAYBRIDGE"))
    }
}
