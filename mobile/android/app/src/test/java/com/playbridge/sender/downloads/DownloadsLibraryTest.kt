package com.playbridge.sender.downloads

import com.playbridge.sender.data.downloads.DownloadEntity
import com.playbridge.sender.downloads.engine.DownloadStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.Locale

class DownloadsLibraryTest {
    private fun entity(
        id: String,
        title: String = id,
        status: DownloadStatus = DownloadStatus.DONE,
        bytes: Long = 0,
        total: Long = -1,
        created: Long = 0,
        url: String = "https://cdn.example.net/v/$id.m3u8",
        headers: String? = null,
    ) = DownloadEntity(
        id = id, url = url, title = title, kind = "HLS", status = status.name,
        headersJson = headers, bytesDownloaded = bytes, totalBytes = total,
        createdAt = created, updatedAt = created,
    )

    @Test fun sectionsSplitByStatus() {
        val s = downloadSections(
            listOf(
                entity("a", status = DownloadStatus.RUNNING),
                entity("b", status = DownloadStatus.PAUSED),
                entity("c", status = DownloadStatus.FAILED),
                entity("d"),
                entity("e", status = DownloadStatus.MERGING),
                entity("f", status = DownloadStatus.QUEUED),
            ),
            query = "", sort = DownloadSort.NEWEST,
        )
        assertEquals(setOf("a", "b", "e", "f"), s.inProgress.map { it.id }.toSet())
        assertEquals(listOf("c"), s.failed.map { it.id })
        assertEquals(listOf("d"), s.saved.map { it.id })
    }

    @Test fun searchIsCaseInsensitiveAndTrimmed() {
        val s = downloadSections(listOf(entity("1", "Holiday Clip"), entity("2", "Other")), "  holiday ", DownloadSort.NEWEST)
        assertEquals(listOf("1"), s.saved.map { it.id })
        assertTrue(downloadSections(listOf(entity("1", "x")), "zzz", DownloadSort.NEWEST).isEmpty)
    }

    @Test fun sortOrders() {
        val all = listOf(
            entity("a", "beta", bytes = 10, created = 2),
            entity("b", "Alpha", bytes = 30, created = 1),
            entity("c", "gamma", bytes = 20, created = 3),
        )
        assertEquals(listOf("c", "a", "b"), downloadSections(all, "", DownloadSort.NEWEST).saved.map { it.id })
        assertEquals(listOf("b", "a", "c"), downloadSections(all, "", DownloadSort.OLDEST).saved.map { it.id })
        assertEquals(listOf("b", "a", "c"), downloadSections(all, "", DownloadSort.NAME).saved.map { it.id })
        assertEquals(listOf("b", "c", "a"), downloadSections(all, "", DownloadSort.LARGEST).saved.map { it.id })
    }

    @Test fun inProgressKeepsStartOrderWhateverTheSort() {
        val all = listOf(
            entity("late", status = DownloadStatus.RUNNING, created = 5),
            entity("early", status = DownloadStatus.RUNNING, created = 1),
        )
        assertEquals(listOf("early", "late"), downloadSections(all, "", DownloadSort.NEWEST).inProgress.map { it.id })
    }

    @Test fun recordedPageWinsOverReferer() {
        val e = entity("a", headers = """{"Referer":"https://www.site.example/"}""")
            .copy(sourcePageUrl = "https://www.site.example/watch/42")
        assertEquals(DownloadSource("https://www.site.example/watch/42", exactPage = true), downloadSource(e))
        assertEquals("site.example", sourceHost(e))
    }

    @Test fun refererIsAnInexactFallback() {
        val e = entity("a", headers = """{"referer":"https://www.site.example/"}""")
        assertEquals(DownloadSource("https://www.site.example/", exactPage = false), downloadSource(e))
        assertEquals("site.example", sourceHost(e))
        assertEquals("cdn.example.net", sourceHost(entity("b")))
        assertNull(downloadSource(entity("b")))
    }

    @Test fun nonWebSourcesAreIgnored() {
        assertNull(downloadSource(entity("a", headers = """{"Referer":"javascript:alert(1)"}""")))
        assertNull(downloadSource(entity("b", headers = "not json")))
        assertNull(downloadSource(entity("c").copy(sourcePageUrl = "about:blank")))
        assertEquals(
            DownloadSource("https://r.example/", exactPage = false),
            downloadSource(entity("d", headers = """{"Referer":"https://r.example/"}""").copy(sourcePageUrl = "file:///x")),
        )
    }

    @Test fun progressDetailWithSpeedAndEta() {
        val running = entity("a", status = DownloadStatus.RUNNING, bytes = 50L * 1024 * 1024, total = 110L * 1024 * 1024)
        assertEquals("50.0 MB of 110.0 MB · 1.0 MB/s · 1 min left", progressDetail(running, 1024L * 1024))
        val unknown = entity("b", status = DownloadStatus.RUNNING, bytes = 2048)
        assertEquals("2.0 KB", progressDetail(unknown, 0))
        val paused = running.copy(status = DownloadStatus.PAUSED.name)
        assertEquals("50.0 MB of 110.0 MB", progressDetail(paused, 1024L * 1024))
    }

    @Test fun etaFormatting() {
        assertEquals("1 s", formatEta(0))
        assertEquals("45 s", formatEta(45))
        assertEquals("2 min", formatEta(100))
        assertEquals("1 h", formatEta(3_600))
        assertEquals("1 h 5 min", formatEta(3_900))
    }

    @Test fun speedSmoothing() {
        assertEquals(100L, smoothSpeed(null, 100))
        assertEquals(130L, smoothSpeed(100, 200))
        assertEquals(0L, smoothSpeed(10, -1000))
    }

    @Test fun durationFormatting() {
        assertNull(formatDuration(0))
        assertEquals("4:07", formatDuration(247_000))
        assertEquals("1:05:09", formatDuration(3_909_000))
    }

    @Test fun summaryLine() {
        val saved = listOf(entity("a", bytes = 1024L * 1024 * 1024), entity("b", bytes = 1024L * 1024 * 1024))
        assertEquals("2 videos · 2.00 GB · 10.00 GB free", librarySummary(saved, 10L * 1024 * 1024 * 1024))
        assertEquals("1 video", librarySummary(listOf(entity("a")), null))
    }

    @Test fun dayLabels() {
        val now = Calendar.getInstance().apply { set(2026, Calendar.OCTOBER, 19, 12, 0) }.timeInMillis
        val day = 24L * 60 * 60 * 1000
        assertEquals("Today", formatDay(now - 60_000, now, Locale.US))
        assertEquals("Yesterday", formatDay(now - day, now, Locale.US))
        assertEquals("Oct 12", formatDay(now - 7 * day, now, Locale.US))
        assertEquals("Oct 19, 2025", formatDay(now - 365 * day, now, Locale.US))
    }

    @Test fun renameKeepsExtensionAndStripsBadChars() {
        assertEquals("My clip.mp4", renamedDisplayName("My clip", "old name.mp4"))
        assertEquals("a_b_c.mp4", renamedDisplayName("a/b:c", "x.mp4"))
        assertEquals("Already.mp4", renamedDisplayName("Already.mp4", "x.mp4"))
        assertEquals("No ext", renamedDisplayName("No ext", null))
        assertNull(renamedDisplayName("  ..  ", "x.mp4"))
    }

    @Test fun metaLinePutsHostLast() {
        val now = Calendar.getInstance().apply { set(2026, Calendar.OCTOBER, 19, 12, 0) }.timeInMillis
        val e = entity("a", bytes = 1024L * 1024, created = now - 60_000, headers = """{"Referer":"https://site.example/x"}""")
        assertEquals("1.0 MB · Today · site.example", savedMetaLine(e, now, Locale.US))
    }
}
