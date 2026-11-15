package com.playbridge.sender.history

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/*
 * Pure presentation logic for the cast history screens: what each row says, which filter it falls
 * under, how rows group by day, and which actions are offered. No Android types, no clock: callers
 * pass the time so every state is testable on the JVM. Nothing here reads or prints a URL.
 */

enum class HistoryFilter(val label: String) {
    ALL("All"), UNFINISHED("Unfinished"), FAILED("Failed"), EXPIRED("Expired"),
}

/** One thing in the list: a remembered cast, or an entry from the old command history. */
sealed interface HistoryItem {
    val key: String
    val atMs: Long

    data class Cast(val entry: CastHistoryEntry) : HistoryItem {
        override val key get() = "cast-${entry.id}"
        override val atMs get() = entry.startedAtMs
    }

    /** An old `command_history` row: a title and a link, no picture and no position. */
    data class Old(
        val id: Long,
        val title: String?,
        val url: String,
        val isBrowser: Boolean,
        override val atMs: Long,
    ) : HistoryItem {
        override val key get() = "old-$id"
    }
}

enum class StatusTone { Normal, Error, Warning, Playing }

/** What the end of a row offers. */
enum class RowTrailing {
    /** The overflow button, which opens the actions sheet. */
    Menu,
    /** "Try again" button for a cast that didn't play. */
    TryAgain,
    /** Refresh button on a cast whose link expired and whose page is known. */
    FreshLink,
    /** The cast is playing now: open the remote. */
    Remote,
    /** Old entry: its own small menu (cast again, save, remove). */
    OldMenu,
}

enum class TileKind { Video, Audio, Image, Live, Failed, Page }

data class HistoryRow(
    val item: HistoryItem,
    val title: String,
    /** "site · receiver", or "Playing on <receiver>" for the cast playing now. */
    val subtitle: String,
    /** Null for the playing row, whose line is live. */
    val status: String?,
    val statusTone: StatusTone,
    /** Link line under the status, with its own tone. */
    val link: String?,
    val linkTone: StatusTone,
    val badge: String?,
    val progress: Float?,
    val previewPath: String?,
    val tile: TileKind,
    val trailing: RowTrailing,
    val playing: Boolean,
    /** What a screen reader hears for the whole row. */
    val description: String,
) {
    val key: String get() = item.key
}

/** Everything a row depends on besides the entry itself. */
data class RowContext(
    val nowMs: Long,
    val zone: ZoneId = ZoneId.systemDefault(),
    val locale: Locale = Locale.getDefault(),
    val playingId: String? = null,
    val checking: Set<String> = emptySet(),
)

// ---- time words -----------------------------------------------------------------------------

/** "2:58", "1:32:05". */
fun formatClock(ms: Long): String {
    val total = (ms.coerceAtLeast(0) / 1000)
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) "%d:%02d:%02d".format(Locale.ROOT, h, m, s) else "%d:%02d".format(Locale.ROOT, m, s)
}

/** "just now", "20 min ago", "2 h ago", "Yesterday", "3 days ago", "last week", "3 weeks ago", "Oct 3". */
fun formatAgo(atMs: Long, nowMs: Long, zone: ZoneId = ZoneId.systemDefault(), locale: Locale = Locale.getDefault()): String {
    val diff = nowMs - atMs
    if (diff < 0) return "just now"
    val minutes = diff / 60_000
    val hours = diff / 3_600_000
    if (minutes < 1) return "just now"
    if (minutes < 60) return "$minutes min ago"
    if (hours < 24) return "$hours h ago"
    val days = daysBetween(atMs, nowMs, zone)
    return when {
        days <= 1 -> "Yesterday"
        days < 7 -> "$days days ago"
        days < 14 -> "last week"
        days < 30 -> "${days / 7} weeks ago"
        else -> dateLabel(atMs, zone, locale)
    }
}

internal fun daysBetween(atMs: Long, nowMs: Long, zone: ZoneId): Long =
    java.time.temporal.ChronoUnit.DAYS.between(
        Instant.ofEpochMilli(atMs).atZone(zone).toLocalDate(),
        Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate(),
    )

private fun dateLabel(atMs: Long, zone: ZoneId, locale: Locale): String =
    DateTimeFormatter.ofPattern("MMM d", locale).format(Instant.ofEpochMilli(atMs).atZone(zone))

/** "Today", "Yesterday", then "Nov 10"; older than the current year adds the year. */
fun dayLabel(atMs: Long, nowMs: Long, zone: ZoneId = ZoneId.systemDefault(), locale: Locale = Locale.getDefault()): String {
    val days = daysBetween(atMs, nowMs, zone)
    return when {
        days <= 0 -> "Today"
        days == 1L -> "Yesterday"
        else -> {
            val at = Instant.ofEpochMilli(atMs).atZone(zone)
            val sameYear = at.year == Instant.ofEpochMilli(nowMs).atZone(zone).year
            DateTimeFormatter.ofPattern(if (sameYear) "MMM d" else "MMM d, yyyy", locale).format(at)
        }
    }
}

private fun dayStart(atMs: Long, zone: ZoneId): LocalDate = Instant.ofEpochMilli(atMs).atZone(zone).toLocalDate()

data class DayGroup(val label: String, val rows: List<HistoryRow>) {
    val key: String get() = "day-$label"
}

/** Groups rows (already newest first) under Today, Yesterday, then dates. */
fun groupByDay(rows: List<HistoryRow>, nowMs: Long, zone: ZoneId = ZoneId.systemDefault(), locale: Locale = Locale.getDefault()): List<DayGroup> {
    val groups = ArrayList<Pair<LocalDate, MutableList<HistoryRow>>>()
    for (row in rows) {
        val day = dayStart(row.item.atMs, zone)
        val last = groups.lastOrNull()
        if (last != null && last.first == day) last.second += row else groups += day to mutableListOf(row)
    }
    return groups.map { (_, list) -> DayGroup(dayLabel(list.first().item.atMs, nowMs, zone, locale), list) }
}

/** "25 min", "1 h 5 min", "3 min" — how long a live stream was watched. */
fun formatWatched(ms: Long): String {
    val minutes = (ms.coerceAtLeast(0) / 60_000).coerceAtLeast(1)
    return if (minutes < 60) "$minutes min" else {
        val h = minutes / 60
        val m = minutes % 60
        if (m == 0L) "$h h" else "$h h $m min"
    }
}

/** "2.4 MB", "310 KB", "0 KB". */
fun formatBytes(bytes: Long): String {
    val b = bytes.coerceAtLeast(0)
    return when {
        b >= 1_000_000 -> "%.1f MB".format(Locale.ROOT, b / 1_000_000.0)
        else -> "${(b + 999) / 1000} KB"
    }
}

// ---- receiver and failure words ---------------------------------------------------------------

/** "Living Room TV", "Kitchen speaker (DLNA)". */
fun receiverLabel(name: String?, kind: String): String {
    val suffix = when (kind) {
        "DLNA" -> "DLNA"
        "ROKU" -> "Roku"
        "GOOGLE_CAST" -> "Google Cast"
        "WEB_BROWSER" -> "browser"
        else -> null
    }
    val base = name?.takeIf { it.isNotBlank() }
    return when {
        base != null && suffix != null -> "$base ($suffix)"
        base != null -> base
        suffix != null -> suffix
        else -> "TV"
    }
}

private fun isTv(kind: String) = kind == "PLAYBRIDGE"

/** A short reason, never raw receiver text. */
fun failureText(reason: String?, receiverKind: String): String {
    val device = if (isTv(receiverKind)) "The TV" else "The device"
    return when (reason) {
        CastPositionTracker.FAIL_RECEIVER_ERROR -> "$device couldn't load it"
        CastPositionTracker.FAIL_STOPPED_EARLY -> "Stopped before it started"
        CastPositionTracker.FAIL_NOT_SENT -> if (isTv(receiverKind)) "Couldn't reach the TV" else "Couldn't reach the device"
        else -> "Didn't play"
    }
}

// ---- rows -----------------------------------------------------------------------------------

private fun tileOf(entry: CastHistoryEntry): TileKind = when {
    entry.failed -> TileKind.Failed
    entry.live -> TileKind.Live
    entry.mediaKind.equals("audio", true) -> TileKind.Audio
    entry.mediaKind.equals("image", true) -> TileKind.Image
    else -> TileKind.Video
}

private fun CastHistoryEntry.isPlaylist() = playlistCount != null && playlistCount > 1

/** "site · receiver"; a playlist says "Playlist" where the site would be. */
fun castSubtitle(entry: CastHistoryEntry): String {
    val receiver = receiverLabel(entry.receiverName, entry.receiverKind)
    val lead = if (entry.isPlaylist()) "Playlist" else entry.host
    return if (lead != null) "$lead · $receiver" else receiver
}

private fun badgeOf(entry: CastHistoryEntry): String? = when {
    entry.failed -> null
    entry.isPlaylist() -> "${(entry.playlistIndex ?: 0) + 1} of ${entry.playlistCount}"
    entry.live -> "LIVE"
    entry.durationMs > 0 -> formatClock(entry.durationMs)
    else -> null
}

private fun statusOf(entry: CastHistoryEntry, ctx: RowContext): Pair<String, StatusTone> {
    val ago = formatAgo(entry.startedAtMs, ctx.nowMs, ctx.zone, ctx.locale)
    return when (entry.status) {
        CastHistoryStatus.FAILED -> "${failureText(entry.failureReason, entry.receiverKind)} · $ago" to StatusTone.Error
        CastHistoryStatus.FINISHED -> "Finished · $ago" to StatusTone.Normal
        CastHistoryStatus.LIVE -> "Watched for ${formatWatched(entry.liveWatchedMs)} · $ago" to StatusTone.Normal
        CastHistoryStatus.IN_PROGRESS, CastHistoryStatus.NOT_STARTED ->
            "Stopped at ${formatClock(entry.positionMs)} · $ago" to StatusTone.Normal
        CastHistoryStatus.PROGRESS_UNKNOWN -> "Position not reported · $ago" to StatusTone.Normal
    }
}

/** One row for any history item. */
fun buildRow(item: HistoryItem, ctx: RowContext): HistoryRow = when (item) {
    is HistoryItem.Cast -> castRow(item, ctx)
    is HistoryItem.Old -> oldRow(item, ctx)
}

private fun oldRow(item: HistoryItem.Old, ctx: RowContext): HistoryRow {
    val title = item.title?.takeIf { it.isNotBlank() } ?: item.url
    val status = formatAgo(item.atMs, ctx.nowMs, ctx.zone, ctx.locale)
    return HistoryRow(
        item = item, title = title,
        subtitle = if (item.isBrowser) "Page" else "Link",
        status = status, statusTone = StatusTone.Normal, link = null, linkTone = StatusTone.Normal,
        badge = null, progress = null, previewPath = null,
        tile = if (item.isBrowser) TileKind.Page else TileKind.Video,
        trailing = RowTrailing.OldMenu, playing = false,
        description = "$title. $status",
    )
}

private fun castRow(item: HistoryItem.Cast, ctx: RowContext): HistoryRow {
    val entry = item.entry
    val title = entry.title?.takeIf { it.isNotBlank() } ?: entry.host ?: "Untitled cast"
    val playing = entry.id == ctx.playingId
    val link = entry.linkStatus(ctx.nowMs, entry.id in ctx.checking)
    if (playing) {
        val receiver = receiverLabel(entry.receiverName, entry.receiverKind)
        return HistoryRow(
            item = item, title = title, subtitle = "Playing on $receiver", status = null,
            statusTone = StatusTone.Playing, link = null, linkTone = StatusTone.Normal,
            badge = badgeOf(entry), progress = entry.progressFraction, previewPath = entry.previewPath,
            tile = tileOf(entry), trailing = RowTrailing.Remote, playing = true,
            description = "$title. Playing on $receiver",
        )
    }
    val (status, tone) = statusOf(entry, ctx)
    val linkText = linkStatusLabel(link, ctx.nowMs)
    val linkTone = when (link) {
        is LinkStatus.Expired -> StatusTone.Error
        is LinkStatus.ExpiresSoon, LinkStatus.CouldNotCheck -> StatusTone.Warning
        else -> StatusTone.Normal
    }
    val expired = link is LinkStatus.Expired
    val trailing = when {
        expired && entry.pageUrl != null -> RowTrailing.FreshLink
        entry.failed && entry.canReplay -> RowTrailing.TryAgain
        else -> RowTrailing.Menu
    }
    val subtitle = castSubtitle(entry)
    return HistoryRow(
        item = item, title = title, subtitle = subtitle, status = status, statusTone = tone,
        link = linkText, linkTone = linkTone, badge = badgeOf(entry), progress = entry.progressFraction,
        previewPath = entry.previewPath, tile = tileOf(entry), trailing = trailing, playing = false,
        description = listOfNotNull(title, subtitle, status, linkText).joinToString(". "),
    )
}

// ---- filters, search, counts ------------------------------------------------------------------

/** A cast worth picking up again: stopped part-way with a position. */
fun CastHistoryEntry.isUnfinished(): Boolean = status == CastHistoryStatus.IN_PROGRESS

fun HistoryItem.matches(filter: HistoryFilter, nowMs: Long, checking: Set<String> = emptySet()): Boolean = when (filter) {
    HistoryFilter.ALL -> true
    else -> if (this !is HistoryItem.Cast) false else when (filter) {
        HistoryFilter.UNFINISHED -> entry.isUnfinished()
        HistoryFilter.FAILED -> entry.failed
        HistoryFilter.EXPIRED -> entry.linkStatus(nowMs, entry.id in checking) is LinkStatus.Expired
        HistoryFilter.ALL -> true
    }
}

/** Counts for the filter chips. Old entries count only under All. */
fun filterCounts(items: List<HistoryItem>, nowMs: Long, checking: Set<String> = emptySet()): Map<HistoryFilter, Int> =
    HistoryFilter.entries.associateWith { f -> items.count { it.matches(f, nowMs, checking) } }

fun HistoryItem.matchesQuery(query: String): Boolean {
    val q = query.trim()
    if (q.isEmpty()) return true
    return when (this) {
        is HistoryItem.Cast -> listOfNotNull(entry.title, entry.host, entry.receiverName)
        is HistoryItem.Old -> listOfNotNull(title, hostOf(url))
    }.any { it.contains(q, ignoreCase = true) }
}

/** Cast entries and old entries together, newest first. */
fun mergeHistory(entries: List<CastHistoryEntry>, old: List<HistoryItem.Old>): List<HistoryItem> =
    (entries.map { HistoryItem.Cast(it) } + old).sortedByDescending { it.atMs }

// ---- actions --------------------------------------------------------------------------------

enum class HistoryAction {
    Resume, PlayFromStart, ChooseWhere, AddToQueue, OpenPage, SaveToCollection, Share, Remove,
}

/** What the current target is like, as far as history is concerned. */
data class TargetInfo(
    /** Name of the device that is selected now; null when none is (play on this phone). */
    val name: String? = null,
    /** A queue exists for the selected target. */
    val canQueue: Boolean = false,
    /** The target can start at a position; when false, a resume starts from the beginning. */
    val canSeekOnLoad: Boolean = true,
)

fun CastHistoryEntry.canResume(): Boolean = canReplay && isUnfinished()

/** The actions sheet for one cast, in order. */
fun actionsFor(entry: CastHistoryEntry, target: TargetInfo): List<HistoryAction> = buildList {
    if (entry.canResume()) add(HistoryAction.Resume)
    if (entry.canReplay) {
        add(HistoryAction.PlayFromStart)
        add(HistoryAction.ChooseWhere)
        if (target.canQueue) add(HistoryAction.AddToQueue)
    }
    if (entry.pageUrl != null) add(HistoryAction.OpenPage)
    if (entry.canReplay) add(HistoryAction.SaveToCollection)
    add(HistoryAction.Share)
    add(HistoryAction.Remove)
}

enum class ExpiredAction { FreshLink, TryOldLink, CheckAgain, Remove }

/** The expired-cast sheet. With no page known there is no way to a fresh link, so none is offered. */
fun expiredActionsFor(entry: CastHistoryEntry): List<ExpiredAction> = buildList {
    if (entry.pageUrl != null) add(ExpiredAction.FreshLink)
    if (entry.canReplay) {
        add(ExpiredAction.TryOldLink)
        add(ExpiredAction.CheckAgain)
    }
    add(ExpiredAction.Remove)
}

fun resumeLabel(entry: CastHistoryEntry) = "Resume from ${formatClock(entry.positionMs)}"

fun resumeSubtitle(target: TargetInfo): String = when {
    target.name == null -> "Choose a device to continue"
    target.canSeekOnLoad -> "On ${target.name}, the device selected now"
    else -> "On ${target.name}; it can't start part-way, so it plays from the start"
}

/** "Stopped at 2:58 of 12:14", or what is known. */
fun stoppedDetail(entry: CastHistoryEntry): String = when (entry.status) {
    CastHistoryStatus.FINISHED -> "Finished"
    CastHistoryStatus.LIVE -> "Live · watched for ${formatWatched(entry.liveWatchedMs)}"
    CastHistoryStatus.FAILED -> failureText(entry.failureReason, entry.receiverKind)
    CastHistoryStatus.PROGRESS_UNKNOWN -> "Position not reported"
    else -> if (entry.durationMs > 0) {
        "Stopped at ${formatClock(entry.positionMs)} of ${formatClock(entry.durationMs)}"
    } else "Stopped at ${formatClock(entry.positionMs)}"
}

/** "Bedroom TV · today, 14:05". */
fun playedOnDetail(entry: CastHistoryEntry, nowMs: Long, zone: ZoneId = ZoneId.systemDefault(), locale: Locale = Locale.getDefault()): String {
    val receiver = receiverLabel(entry.receiverName, entry.receiverKind)
    return "$receiver · ${dayWord(entry.startedAtMs, nowMs, zone, locale)}, ${clockTime(entry.startedAtMs, zone, locale)}"
}

private fun clockTime(atMs: Long, zone: ZoneId, locale: Locale) =
    DateTimeFormatter.ofPattern("HH:mm", locale).format(Instant.ofEpochMilli(atMs).atZone(zone))

private fun dayWord(atMs: Long, nowMs: Long, zone: ZoneId, locale: Locale): String =
    dayLabel(atMs, nowMs, zone, locale).let { if (it == "Today" || it == "Yesterday") it.lowercase() else it }

/** "This link expired yesterday at 21:40", or the plain form when the time is not known. */
fun expiredNotice(expiredAtMs: Long?, nowMs: Long, zone: ZoneId = ZoneId.systemDefault(), locale: Locale = Locale.getDefault()): String {
    if (expiredAtMs == null) return "This link has expired"
    val day = dayWord(expiredAtMs, nowMs, zone, locale)
    val whenText = if (day == "today" || day == "yesterday") day else "on $day"
    return "This link expired $whenText at ${clockTime(expiredAtMs, zone, locale)}"
}

/** The line under "Open the page to get a fresh link". Opens the page; the video is found again there. */
fun freshLinkNote(entry: CastHistoryEntry): String {
    val host = entry.host?.let { "Opens $it" } ?: "Opens the page it came from"
    val where = if (entry.canResume()) " You stopped at ${formatClock(entry.positionMs)}." else ""
    return "$host and finds the video again.$where"
}

/** The toast after opening the page for a fresh link: carries where the person stopped. */
fun freshLinkToast(entry: CastHistoryEntry): String =
    if (entry.canResume()) "Find the video again. You stopped at ${formatClock(entry.positionMs)}."
    else "Find the video again."

// ---- settings maths ---------------------------------------------------------------------------

/** How many casts lowering the limit to [newLimit] removes, given [kept] now. Never negative. */
fun removedByLimit(kept: Int, newLimit: Int): Int = (kept - newLimit).coerceAtLeast(0)

/**
 * The hint under the limit choices: what choosing the smallest limit would remove, or null when
 * nothing would go.
 */
fun limitHint(kept: Int, limits: List<Int> = CastHistorySettings.LIMITS): String? {
    val smallest = limits.minOrNull() ?: return null
    val removed = removedByLimit(kept, smallest)
    if (removed == 0) return null
    return "$kept kept now. Choosing $smallest removes the $removed oldest right away, after you confirm."
}

/** The count line in Settings: "38 casts · 31 pictures". */
fun storageLine(storage: CastHistoryStorage): String {
    val casts = if (storage.casts == 1) "1 cast" else "${storage.casts} casts"
    val pictures = if (storage.pictures == 1) "1 picture" else "${storage.pictures} pictures"
    return "$casts · $pictures"
}
