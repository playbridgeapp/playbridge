package com.playbridge.sender.history

import com.playbridge.sender.data.history.CastHistoryDao
import com.playbridge.sender.data.history.CastHistoryEntity
import com.playbridge.sender.data.history.CommandHistoryStamp
import com.playbridge.sender.diagnostics.CastAttempt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private class FakeDao : CastHistoryDao {
    val rows = MutableStateFlow<List<CastHistoryEntity>>(emptyList())
    override suspend fun upsert(entry: CastHistoryEntity) {
        rows.value = rows.value.filterNot { it.id == entry.id } + entry
    }
    override suspend fun get(id: String) = rows.value.firstOrNull { it.id == id }
    override fun observeAll(): Flow<List<CastHistoryEntity>> = rows
    override suspend fun all() = rows.value.sortedByDescending { it.startedAtMs }
    private fun edit(id: String, f: (CastHistoryEntity) -> CastHistoryEntity) {
        rows.value = rows.value.map { if (it.id == id) f(it) else it }
    }
    override suspend fun updateProgress(
        id: String, positionMs: Long, durationMs: Long, positionReported: Boolean, finished: Boolean,
        live: Boolean, liveWatchedMs: Long, failed: Boolean, failureReason: String?,
        playlistIndex: Int?, playlistCount: Int?,
    ) = edit(id) {
        it.copy(
            positionMs = positionMs, durationMs = durationMs, positionReported = positionReported,
            finished = finished, live = live, liveWatchedMs = liveWatchedMs, failed = failed,
            failureReason = failureReason, playlistIndex = playlistIndex, playlistCount = playlistCount,
        )
    }
    override suspend fun setPreview(id: String, previewPath: String?) = edit(id) { it.copy(previewPath = previewPath) }
    override suspend fun setLinkCheck(id: String, result: String?, at: Long?) =
        edit(id) { it.copy(linkCheck = result, linkCheckedAtMs = at) }
    override suspend fun resetLink(id: String, expiresAtMs: Long?) =
        edit(id) { it.copy(expiresAtMs = expiresAtMs, linkCheck = null, linkCheckedAtMs = null) }
    override suspend fun clearPreviewPaths() { rows.value = rows.value.map { it.copy(previewPath = null) } }
    override suspend fun delete(id: String) { rows.value = rows.value.filterNot { it.id == id } }
    override suspend fun clear() { rows.value = emptyList() }
}

private class FakeReplay : ReplaySources {
    private val map = MutableStateFlow<Map<String, CastReplaySource>>(emptyMap())
    override val sources: StateFlow<Map<String, CastReplaySource>> = map
    override fun put(id: String, source: CastReplaySource) { map.value = map.value + (id to source) }
    override fun delete(id: String) { map.value = map.value - id }
    override fun retainOnly(ids: Set<String>) { map.value = map.value.filterKeys { it in ids } }
    override fun clear() { map.value = emptyMap() }
}

private class FakePreviews : PreviewStore {
    val files = linkedMapOf<String, String>()
    override fun save(id: String, key: String?): String? =
        key?.let { "/previews/$id.jpg".also { path -> files[id] = path } }
    override fun delete(id: String) { files.remove(id) }
    override fun retainOnly(ids: Set<String>) { files.keys.retainAll(ids) }
    override fun clear() { files.clear() }
    override fun sizeBytes(): Long = files.size * 1000L
}

private class FakeLegacy(var stamps: List<CommandHistoryStamp> = emptyList()) : LegacyHistory {
    override suspend fun stamps() = stamps
    override suspend fun delete(id: Long) { stamps = stamps.filterNot { it.id == id } }
    override suspend fun deleteAll() { stamps = emptyList() }
}

@OptIn(ExperimentalCoroutinesApi::class)
class CastHistoryRepositoryTest {
    private class Rig(settings: CastHistorySettings = CastHistorySettings()) {
        val dao = FakeDao()
        val replay = FakeReplay()
        val previews = FakePreviews()
        val legacy = FakeLegacy()
        val settings = MutableStateFlow(settings)
        var now = 1_000_000L
        var party = false
        var attempts: List<CastAttempt> = emptyList()
        var linkResult = LinkCheckResult.Works
        val checkedUrls = mutableListOf<String>()
        val bridgedHosts = mutableSetOf("https://bridged.example")
        val scope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher())
        val repo = CastHistoryRepository(
            dao = dao, legacy = legacy, replay = replay, previews = previews,
            config = object : CastHistorySettingsSource { override val settings = this@Rig.settings },
            bridged = BridgedOriginPolicy { url -> bridgedHosts.any { url.startsWith(it) } },
            originResolver = PageOriginResolver { null },
            linkChecker = LinkChecker { url, _ -> checkedUrls += url; linkResult },
            attempts = { attempts }, partyActive = { party }, clock = { now },
            dispatcher = UnconfinedTestDispatcher(), scope = scope,
        )
        fun source(url: String = "https://cdn.example/v.m3u8", title: String? = "Title") =
            CastReplaySource(url, title, null, null, emptyMap(), emptyList())
        fun start(
            id: String, at: Long = now, origin: CastOrigin = CastOrigin(), title: String? = "Title",
            source: CastReplaySource? = source(), sent: Boolean = true,
        ) = CastStart(id, at, "PLAYBRIDGE", "Living room", source, title, null, null, sent, origin, previewKey = source?.url)
        fun row(id: String) = dao.rows.value.firstOrNull { it.id == id }
        fun close() = scope.cancel()
    }

    private fun rig(settings: CastHistorySettings = CastHistorySettings(), body: suspend Rig.() -> Unit) =
        runBlocking {
            val rig = Rig(settings)
            try { rig.body() } finally { rig.close() }
        }

    @Test
    fun recordsACastWithItsReplaySourcePreviewHostAndReceiver() = rig {
        repo.onCastStarted(start("a", origin = CastOrigin(pageUrl = "https://site.example/watch?v=1")))
        val row = row("a")!!
        assertEquals("Title", row.title)
        assertEquals("site.example", row.host)
        assertEquals("Living room", row.receiverName)
        assertNotNull(replay.sources.value["a"])
        assertEquals("/previews/a.jpg", row.previewPath)
        assertFalse(row.failed)
    }

    @Test
    fun aCastThatWasNotSentIsRecordedAsFailed() = rig {
        repo.onCastStarted(start("a", sent = false))
        assertTrue(row("a")!!.failed)
        assertEquals(CastPositionTracker.FAIL_NOT_SENT, row("a")!!.failureReason)
    }

    @Test
    fun aBridgedAppCastLeavesNoRowNoReplaySourceNoPictureAndNoPosition() = rig {
        repo.onCastStarted(start("a", origin = CastOrigin(pageUrl = "https://bridged.example/app/play")))
        repo.onPlaybackSample(PlaybackSample(SampleState.PLAYING, 30_000, 100_000, title = "Title"), now)
        repo.onPlaybackSample(PlaybackSample(SampleState.STOPPED), now + 1)
        repo.flush()
        assertNull(row("a"))
        assertTrue(replay.sources.value.isEmpty())
        assertTrue(previews.files.isEmpty())
        // A later replace must not smuggle a source in for a cast that was gated out.
        repo.replaceReplaySource("a", source())
        assertTrue(replay.sources.value.isEmpty())
    }

    @Test
    fun websiteCastsWithAnUnknownOriginFailClosed() = rig {
        repo.onCastStarted(start("page", origin = CastOrigin(detectedBy = "page_cast")))
        repo.onCastStarted(start("linked", origin = CastOrigin(detectedBy = "LINKED_PAGE")))
        assertTrue(dao.rows.value.isEmpty())
        assertTrue(replay.sources.value.isEmpty())
    }

    @Test
    fun aWebsiteCastFromAKnownOrdinarySiteIsRecorded() = rig {
        repo.onCastStarted(start("a", origin = CastOrigin(pageUrl = "https://plain.example/x", detectedBy = "page_cast")))
        assertNotNull(row("a"))
    }

    @Test
    fun theOriginHintMarksTheNextCastAndIsThenForgotten() = rig {
        repo.noteOrigin("https://bridged.example/app", pageInitiated = true)
        repo.onCastStarted(start("a"))
        repo.onCastStarted(start("b"))
        assertNull(row("a"))
        assertNotNull(row("b"))
    }

    @Test
    fun anOldOriginHintIsNotAppliedToALaterCast() = rig {
        repo.noteOrigin("https://bridged.example/app", pageInitiated = true)
        now += CastHistoryRepository.HINT_TTL_MS + 1
        repo.onCastStarted(start("a", at = now))
        assertNotNull(row("a"))
    }

    @Test
    fun bridgedCastsAreRecordedWhenTheExclusionIsTurnedOff() = rig(CastHistorySettings(excludeBridgedApps = false)) {
        repo.onCastStarted(start("a", origin = CastOrigin(pageUrl = "https://bridged.example/app")))
        assertNotNull(row("a"))
        repo.onCastStarted(start("b", origin = CastOrigin(detectedBy = "page_cast")))
        assertNotNull(row("b"))
    }

    @Test
    fun aDisabledHistoryWritesNothing() = rig(CastHistorySettings(enabled = false)) {
        repo.onCastStarted(start("a"))
        assertTrue(dao.rows.value.isEmpty())
        assertTrue(replay.sources.value.isEmpty())
        assertTrue(previews.files.isEmpty())
    }

    @Test
    fun watchPartyPlaybackWritesNothing() = rig {
        party = true
        repo.onCastStarted(start("a"))
        assertTrue(dao.rows.value.isEmpty())
        assertTrue(replay.sources.value.isEmpty())
    }

    @Test
    fun picturesAreOnlyKeptWhenAllowedAndRemovedWhenTheSettingIsTurnedOff() = rig {
        repo.onCastStarted(start("a"))
        assertNotNull(row("a")!!.previewPath)
        settings.value = settings.value.copy(keepPreviews = false)
        assertNull(row("a")!!.previewPath)
        assertTrue(previews.files.isEmpty())
        repo.onCastStarted(start("b"))
        assertNull(row("b")!!.previewPath)
    }

    @Test
    fun positionIsSavedAgainstTheEntryItWasStartedFor() = rig {
        repo.onCastStarted(start("a"))
        repo.onPlaybackSample(PlaybackSample(SampleState.PLAYING, 42_000, 100_000, title = "Title"), now)
        val entry = repo.entries.first().single()
        assertEquals(42_000L, entry.positionMs)
        assertEquals(CastHistoryStatus.IN_PROGRESS, entry.status)
        assertEquals(0.42f, entry.progressFraction!!, 0.001f)
    }

    @Test
    fun aSecondCastDetachesTheFirstSoItsReportsNeverLandOnTheSecond() = rig {
        repo.onCastStarted(start("a", title = "A"))
        repo.onPlaybackSample(PlaybackSample(SampleState.PLAYING, 42_000, 100_000, title = "A"), now)
        now += 1_000
        repo.onCastStarted(start("b", at = now, title = "B"))
        repo.onPlaybackSample(PlaybackSample(SampleState.PLAYING, 43_000, 100_000, title = "A"), now + 1)
        assertEquals(42_000L, row("a")!!.positionMs)
        assertEquals(0L, row("b")!!.positionMs)
        repo.onPlaybackSample(PlaybackSample(SampleState.PLAYING, 2_000, 60_000, title = "B"), now + 2)
        assertEquals(2_000L, row("b")!!.positionMs)
        assertEquals(42_000L, row("a")!!.positionMs)
    }

    @Test
    fun aSkippedCastDetachesThePreviousEntryToo() = rig {
        repo.onCastStarted(start("a", title = "A"))
        repo.onPlaybackSample(PlaybackSample(SampleState.PLAYING, 42_000, 100_000, title = "A"), now)
        repo.onCastStarted(start("bridged", origin = CastOrigin(pageUrl = "https://bridged.example/x"), title = "B"))
        repo.onPlaybackSample(PlaybackSample(SampleState.PLAYING, 90_000, 100_000, title = "B"), now + 5)
        assertEquals(42_000L, row("a")!!.positionMs)
        assertNull(row("bridged"))
    }

    @Test
    fun withoutRememberPositionNoPositionIsStored() = rig(CastHistorySettings(rememberPosition = false)) {
        repo.onCastStarted(start("a"))
        repo.onPlaybackSample(PlaybackSample(SampleState.PLAYING, 42_000, 100_000, title = "Title"), now)
        val row = row("a")!!
        assertEquals(0L, row.positionMs)
        assertEquals(0L, row.durationMs)
        assertFalse(row.positionReported)
    }

    @Test
    fun aReceiverThatNeverReportsShowsUnknownProgress() = rig {
        repo.onCastStarted(start("a"))
        repo.onPlaybackSample(PlaybackSample(SampleState.PLAYING, 0, 0, title = "Title"), now)
        assertEquals(CastHistoryStatus.PROGRESS_UNKNOWN, repo.entries.first().single().status)
    }

    @Test
    fun replaySourcesLiveAsLongAsTheirEntryNotAsLongAsTheDiagnosticsList() = rig(CastHistorySettings(limit = 500)) {
        // 45 casts: more than the 40 attempts the diagnostics list keeps.
        for (i in 1..45) { now += 10; repo.onCastStarted(start("id$i", at = now)) }
        assertEquals(45, dao.rows.value.size)
        assertEquals(45, replay.sources.value.size)
        // Restarting reconciles against the history, not against a 40-item list.
        attempts = emptyList()
        settings.value = settings.value.copy(checkLinks = true) // any emission re-runs reconcile
        assertEquals(45, replay.sources.value.size)
        assertNotNull(replay.sources.value["id1"])
    }

    @Test
    fun theLimitCountsOldAndNewHistoryTogetherAndDropsTheOldest() = rig(CastHistorySettings(limit = 25)) {
        legacy.stamps = (1..20).map { CommandHistoryStamp(it.toLong(), it.toLong()) } // oldest of all
        for (i in 1..10) { now += 10; repo.onCastStarted(start("id$i", at = now)) }
        assertEquals(10, dao.rows.value.size) // 30 in total, 5 oldest legacy rows go
        assertEquals(15, legacy.stamps.size)
        assertEquals(6L, legacy.stamps.minOf { it.id })
        for (i in 11..20) { now += 10; repo.onCastStarted(start("id$i", at = now)) }
        assertEquals(20, dao.rows.value.size)
        assertEquals(5, legacy.stamps.size)
        assertEquals(16L, legacy.stamps.minOf { it.id })
        assertEquals(dao.rows.value.map { it.id }.toSet(), replay.sources.value.keys)
    }

    @Test
    fun pruningDeletesTheReplaySourceAndPictureOfEveryDroppedEntry() = rig(CastHistorySettings(limit = 25)) {
        for (i in 1..26) { now += 10; repo.onCastStarted(start("id$i", at = now)) }
        assertNull(row("id1"))
        assertNull(replay.sources.value["id1"])
        assertNull(previews.files["id1"])
        assertEquals(25, dao.rows.value.size)
    }

    @Test
    fun wouldRemoveReportsWhatALowerLimitWouldDelete() = rig(CastHistorySettings(limit = 100)) {
        legacy.stamps = listOf(CommandHistoryStamp(1, 1))
        for (i in 1..30) { now += 10; repo.onCastStarted(start("id$i", at = now)) }
        assertEquals(6, repo.wouldRemove(25))
        assertEquals(0, repo.wouldRemove(50))
        // A value between steps is judged by the step the setting would really use.
        assertEquals(6, repo.wouldRemove(30))
    }

    @Test
    fun loweringTheLimitPrunesImmediately() = rig(CastHistorySettings(limit = 100)) {
        for (i in 1..30) { now += 10; repo.onCastStarted(start("id$i", at = now)) }
        settings.value = settings.value.copy(limit = 25)
        assertEquals(25, dao.rows.value.size)
        assertEquals(25, replay.sources.value.size)
    }

    @Test
    fun startupTurnsLegacyReplaySourcesIntoEntriesAndDropsOrphans() = rig {
        replay.put("legacy", source(title = "Old cast"))
        replay.put("orphan", source())
        attempts = listOf(
            CastAttempt(
                id = "legacy", startedAtMs = 5, receiver = CastAttempt.ReceiverKind.DLNA,
                route = CastAttempt.RouteKind.DIRECT, media = CastAttempt.MediaKind.VIDEO,
                outcome = CastAttempt.AttemptOutcome.PLAYING, events = emptyList(),
            ),
        )
        settings.value = settings.value.copy(limit = 100) // re-run reconcile
        assertEquals("Old cast", row("legacy")!!.title)
        assertEquals("DLNA", row("legacy")!!.receiverKind)
        assertNull(replay.sources.value["orphan"])
        assertNotNull(replay.sources.value["legacy"])
    }

    @Test
    fun deleteAndClearRemoveEveryTraceOfTheEntries() = rig {
        repo.onCastStarted(start("a"))
        repo.onCastStarted(start("b"))
        repo.delete("a")
        assertNull(row("a")); assertNull(replay.sources.value["a"]); assertNull(previews.files["a"])
        repo.clearAll()
        assertTrue(dao.rows.value.isEmpty()); assertTrue(replay.sources.value.isEmpty()); assertTrue(previews.files.isEmpty())
    }

    @Test
    fun replayPlanResumesOnlyWhenThereIsSomethingToResume() = rig {
        repo.onCastStarted(start("a"))
        repo.onPlaybackSample(PlaybackSample(SampleState.PLAYING, 42_000, 100_000, title = "Title"), now)
        assertEquals(42_000L, repo.replayPlan("a", resume = true)!!.startPositionMs)
        assertEquals(0L, repo.replayPlan("a", resume = false)!!.startPositionMs)
        repo.onPlaybackSample(PlaybackSample(SampleState.PLAYING, 99_000, 100_000, title = "Title"), now + 1)
        assertEquals(0L, repo.replayPlan("a", resume = true)!!.startPositionMs) // finished
        assertNull(repo.replayPlan("missing", resume = true))
    }

    @Test
    fun resumeIsOffWhenRememberPositionIsOff() = rig(CastHistorySettings(rememberPosition = false)) {
        repo.onCastStarted(start("a"))
        repo.onPlaybackSample(PlaybackSample(SampleState.PLAYING, 42_000, 100_000, title = "Title"), now)
        assertEquals(0L, repo.replayPlan("a", resume = true)!!.startPositionMs)
    }

    @Test
    fun linkChecksRunOnlyWhenEnabledOrAskedFor() = rig {
        repo.onCastStarted(start("a"))
        assertNull(repo.checkLink("a"))
        assertNull(row("a")!!.linkCheck)
        linkResult = LinkCheckResult.Expired
        assertEquals(LinkCheckResult.Expired, repo.checkLink("a", force = true))
        assertEquals("Expired", row("a")!!.linkCheck)
        settings.value = settings.value.copy(checkLinks = true)
        linkResult = LinkCheckResult.Works
        assertEquals(LinkCheckResult.Works, repo.checkLink("a"))
    }

    @Test
    fun entriesReportWhetherAReplaySourceIsStillThere() = rig {
        repo.onCastStarted(start("a", source = null))
        repo.onCastStarted(start("b"))
        val entries = repo.entries.first().associateBy { it.id }
        assertFalse(entries.getValue("a").canReplay)
        assertTrue(entries.getValue("b").canReplay)
    }

    @Test
    fun aSignedLinkCarriesItsExpiry() = rig {
        repo.onCastStarted(start("a", at = 1_700_000_000_000L, source = source("https://cdn.example/v.mp4?expires=1900000000")))
        assertEquals(1_900_000_000_000L, row("a")!!.expiresAtMs)
        assertTrue(repo.entries.first().single().linkStatus(2_000_000_000_000L) is LinkStatus.Expired)
        assertEquals(LinkStatus.None, repo.entries.first().single().linkStatus(1_800_000_000_000L))
    }

    private val realNow = 1_700_000_000_000L

    private fun playlistSource() = CastReplaySource(
        "https://cdn.example/one.mp4?expires=1700100000", "T", null, null, emptyMap(), emptyList(),
        playlistPayloadJson = """{"items":[{"url":"https://cdn.example/one.mp4?expires=1700100000"},""" +
            """{"url":"https://cdn.example/two.mp4?expires=1700200000"}],"start_index":1}""",
    )

    @Test
    fun aPlaylistCastTakesItsExpiryFromTheItemThatIsPlaying() = rig {
        val s = playlistSource()
        repo.onCastStarted(CastStart("p", realNow, "PLAYBRIDGE", "TV", s, "T", 1, 2, true, previewKey = null))
        assertEquals(1_700_200_000_000L, row("p")!!.expiresAtMs)
    }

    @Test
    fun theExpiryFollowsThePlaylistToTheNextItem() = rig {
        val s = playlistSource()
        repo.onCastStarted(CastStart("p", realNow, "PLAYBRIDGE", "TV", s, "T", 0, 2, true, previewKey = null))
        assertEquals(1_700_100_000_000L, row("p")!!.expiresAtMs)
        repo.onPlaybackSample(PlaybackSample(SampleState.PLAYING, 5_000, 60_000, playlistIndex = 0, playlistCount = 2), now)
        repo.onPlaybackSample(PlaybackSample(SampleState.PLAYING, 1_000, 60_000, playlistIndex = 1, playlistCount = 2), now + 1)
        assertEquals(1_700_200_000_000L, row("p")!!.expiresAtMs)
    }

    @Test
    fun aRefusedLinkReportedByTheReceiverMarksTheCastExpired() = rig {
        repo.onCastStarted(start("a"))
        repo.onReceiverFailure("The link was refused (HTTP 403)")
        assertEquals("Expired", row("a")!!.linkCheck)
        assertEquals(now, row("a")!!.linkCheckedAtMs)
    }

    @Test
    fun otherFailuresDoNotMarkTheLinkExpired() = rig {
        repo.onCastStarted(start("a"))
        repo.onReceiverFailure("The TV couldn't reach the video server")
        repo.onReceiverFailure("The media couldn't be loaded (HTTP 500)")
        repo.onReceiverFailure(null)
        assertNull(row("a")!!.linkCheck)
    }

    @Test
    fun checkingOnOpenDoesNothingWhenTheSettingIsOff() = rig {
        repo.onCastStarted(start("a"))
        assertEquals(0, repo.checkLinksOnOpen())
        assertTrue(checkedUrls.isEmpty())
    }

    @Test
    fun checkingOnOpenOnlyChecksWhatThePolicyPicks() = rig(CastHistorySettings(checkLinks = true)) {
        now = realNow
        repo.onCastStarted(start("fresh"))
        repo.onCastStarted(start("expired", source = source("https://cdn.example/old.mp4?expires=1600000000")))
        repo.onCastStarted(start("known-dead"))
        linkResult = LinkCheckResult.Expired
        repo.checkLink("known-dead", force = true)
        checkedUrls.clear()
        linkResult = LinkCheckResult.Works
        // "known-dead" is already expired, "expired" carries an old expiry: only "fresh" is checked.
        assertEquals(1, repo.checkLinksOnOpen())
        assertEquals("Works", row("fresh")!!.linkCheck)
        // Checked moments ago, so a second open checks nothing.
        assertEquals(0, repo.checkLinksOnOpen())
    }

    @Test
    fun aPlaylistIsCheckedOnTheItemThatIsPlaying() = rig {
        repo.onCastStarted(CastStart("p", realNow, "PLAYBRIDGE", "TV", playlistSource(), "T", 1, 2, true, previewKey = null))
        repo.checkLink("p", force = true)
        assertEquals(listOf("https://cdn.example/two.mp4?expires=1700200000"), checkedUrls)
    }

    @Test
    fun theStoredCheckFeedsTheLinkStatus() = rig {
        repo.onCastStarted(start("a"))
        linkResult = LinkCheckResult.Expired
        repo.checkLink("a", force = true)
        val e = repo.entries.first().single()
        assertEquals(LinkStatus.Expired(null), linkStatus(now, e.expiresAtMs, e.linkCheck, e.linkCheckedAtMs, false))
    }

    @Test
    fun nowPlayingFollowsTheReceiverAndClearsWhenItStops() = rig {
        repo.onCastStarted(start("a"))
        assertNull(repo.nowPlayingId.value)
        repo.onPlaybackSample(PlaybackSample(SampleState.PLAYING, 6_000, 100_000, title = "Title"), now)
        assertEquals("a", repo.nowPlayingId.value)
        assertEquals(6_000L, repo.nowPlaying.value!!.positionMs)
        repo.onPlaybackSample(PlaybackSample(SampleState.PLAYING, 7_000, 100_000, title = "Title"), now + 1000)
        assertEquals(7_000L, repo.nowPlaying.value!!.positionMs)
        repo.onPlaybackSample(PlaybackSample(SampleState.PAUSED, 7_000, 100_000, title = "Title"), now + 2000)
        assertTrue(repo.nowPlaying.value!!.paused)
        repo.onPlaybackSample(PlaybackSample(SampleState.STOPPED), now + 3000)
        assertNull(repo.nowPlaying.value)
        assertNull(repo.nowPlayingId.value)
    }

    @Test
    fun removingThePlayingCastClearsNowPlaying() = rig {
        repo.onCastStarted(start("a"))
        repo.onPlaybackSample(PlaybackSample(SampleState.PLAYING, 6_000, 100_000, title = "Title"), now)
        repo.delete("a")
        assertNull(repo.nowPlayingId.value)
    }

    @Test
    fun storageCountsCastsPicturesAndSize() = rig {
        legacy.stamps = listOf(CommandHistoryStamp(1, 5), CommandHistoryStamp(2, 6))
        repo.onCastStarted(start("a"))
        repo.onCastStarted(start("b", source = source("https://cdn.example/b.m3u8")))
        val s = repo.storage()
        assertEquals(4, s.casts)
        assertEquals(2, s.pictures)
        assertEquals(2_000L, s.pictureBytes)
    }

    @Test
    fun removingPicturesKeepsTheCasts() = rig {
        repo.onCastStarted(start("a"))
        repo.removePictures()
        assertNull(row("a")!!.previewPath)
        assertTrue(previews.files.isEmpty())
        assertNotNull(row("a"))
        assertEquals(0, repo.storage().pictures)
    }

    @Test
    fun clearAllAlsoClearsTheOldCommandHistory() = rig {
        legacy.stamps = listOf(CommandHistoryStamp(1, 5))
        repo.onCastStarted(start("a"))
        repo.clearAll()
        assertTrue(legacy.stamps.isEmpty())
        assertEquals(0, repo.storage().casts)
    }

    @Test
    fun theLimitChangeCountsOldEntriesToo() = rig {
        legacy.stamps = (1L..30L).map { CommandHistoryStamp(it, it) }
        repo.onCastStarted(start("a"))
        assertEquals(6, repo.wouldRemove(25))
        assertEquals(0, repo.wouldRemove(50))
    }
}
