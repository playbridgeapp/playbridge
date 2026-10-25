package com.playbridge.sender.cast

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Phone Files tabs, in display order. */
enum class PhoneFilesTab(val label: String, val kind: MediaKind) {
    VIDEOS("Videos", MediaKind.VIDEO),
    MUSIC("Music", MediaKind.AUDIO),
    PHOTOS("Photos", MediaKind.IMAGE),
}

/** How the Videos grid and Music song list are ordered. */
enum class SortKey(val label: String) {
    NEWEST("Newest first"),
    OLDEST("Oldest first"),
    NAME("Name"),
    LARGEST("Largest first"),
}

/** Music sub-views (board 5). */
enum class MusicView(val label: String) { SONGS("Songs"), ALBUMS("Albums"), ARTISTS("Artists"), FOLDERS("Folders") }

/** A folder (MediaStore bucket) chip. */
data class PhoneFolder(val id: Long, val name: String, val count: Int)

/** The fields Phone Files groups, sorts and searches on; [PhoneMediaItem] implements it. */
interface PhoneMediaFacts {
    val title: String
    val durationMs: Long
    val artist: String?
    val album: String?
    val trackNumber: Int?
    val sizeBytes: Long
    val dateAdded: Long
    val bucketName: String?
    val bucketId: Long?
}

/** An album, artist or folder grouping of songs; [key] is stable for navigation. */
data class SongGroup<T : PhoneMediaFacts>(
    val key: String,
    val title: String,
    val subtitle: String?,
    val songs: List<T>,
) {
    val totalDurationMs: Long get() = songs.sumOf { it.durationMs.coerceAtLeast(0L) }
}

/** A dated section of the Photos grid. */
data class PhotoSection<T : PhoneMediaFacts>(val label: String, val items: List<T>)

/** Pure list logic for Phone Files, kept out of Compose so it can be unit-tested. */
object PhoneFilesModel {

    /** Folder chips for [items], most populated first, ties by name. */
    fun <T : PhoneMediaFacts> folders(items: List<T>): List<PhoneFolder> =
        items.filter { it.bucketId != null }
            .groupBy { it.bucketId!! }
            .map { (id, group) -> PhoneFolder(id, group.first().bucketName ?: "Folder", group.size) }
            .sortedWith(compareByDescending<PhoneFolder> { it.count }.thenBy { it.name.lowercase() })

    /** Folder filter, then search, then sort. */
    fun <T : PhoneMediaFacts> visible(
        items: List<T>,
        folderId: Long?,
        query: String,
        sort: SortKey,
    ): List<T> {
        val q = query.trim()
        val filtered = items.filter { item ->
            (folderId == null || item.bucketId == folderId) && (q.isEmpty() || matches(item, q))
        }
        return sorted(filtered, sort)
    }

    fun <T : PhoneMediaFacts> matches(item: T, query: String): Boolean =
        item.title.contains(query, ignoreCase = true) ||
            item.artist?.contains(query, ignoreCase = true) == true ||
            item.album?.contains(query, ignoreCase = true) == true

    fun <T : PhoneMediaFacts> sorted(items: List<T>, sort: SortKey): List<T> = when (sort) {
        SortKey.NEWEST -> items.sortedByDescending { it.dateAdded }
        SortKey.OLDEST -> items.sortedBy { it.dateAdded }
        SortKey.NAME -> items.sortedBy { it.title.lowercase() }
        SortKey.LARGEST -> items.sortedByDescending { it.sizeBytes }
    }

    /** Albums keyed by album and album artist, so same-named albums by different artists stay apart. */
    fun <T : PhoneMediaFacts> albums(songs: List<T>): List<SongGroup<T>> =
        songs.filter { !it.album.isNullOrBlank() }
            .groupBy { it.album!!.lowercase() to (it.artist?.lowercase() ?: "") }
            .map { (_, group) ->
                val first = group.first()
                SongGroup(
                    key = "album:${first.album}|${first.artist.orEmpty()}",
                    title = first.album!!,
                    subtitle = first.artist,
                    songs = trackOrder(group),
                )
            }
            .sortedBy { it.title.lowercase() }

    fun <T : PhoneMediaFacts> artists(songs: List<T>): List<SongGroup<T>> =
        songs.filter { !it.artist.isNullOrBlank() }
            .groupBy { it.artist!!.lowercase() }
            .map { (_, group) ->
                val name = group.first().artist!!
                SongGroup(
                    key = "artist:$name",
                    title = name,
                    subtitle = null,
                    songs = group.sortedWith(
                        compareBy<T> { it.album?.lowercase() ?: "" }
                            .thenBy { it.trackNumber ?: Int.MAX_VALUE }
                            .thenBy { it.title.lowercase() },
                    ),
                )
            }
            .sortedBy { it.title.lowercase() }

    fun <T : PhoneMediaFacts> musicFolders(songs: List<T>): List<SongGroup<T>> =
        songs.filter { it.bucketId != null }
            .groupBy { it.bucketId!! }
            .map { (id, group) ->
                SongGroup(
                    key = "folder:$id",
                    title = group.first().bucketName ?: "Folder",
                    subtitle = "Folder",
                    songs = group.sortedBy { it.title.lowercase() },
                )
            }
            .sortedBy { it.title.lowercase() }

    /** Album track order: track number when tagged, otherwise title. */
    fun <T : PhoneMediaFacts> trackOrder(songs: List<T>): List<T> =
        songs.sortedWith(compareBy<T> { it.trackNumber ?: Int.MAX_VALUE }.thenBy { it.title.lowercase() })

    /** Photos grouped by the day they were added, newest first: Today, Yesterday, then dates. */
    fun <T : PhoneMediaFacts> photoSections(
        photos: List<T>,
        today: LocalDate = LocalDate.now(),
        zone: ZoneId = ZoneId.systemDefault(),
        locale: Locale = Locale.getDefault(),
    ): List<PhotoSection<T>> {
        val sameYear = DateTimeFormatter.ofPattern("EEE d MMM", locale)
        val otherYear = DateTimeFormatter.ofPattern("d MMM yyyy", locale)
        return photos.sortedByDescending { it.dateAdded }
            .groupBy { Instant.ofEpochSecond(it.dateAdded).atZone(zone).toLocalDate() }
            .map { (day, items) ->
                val label = when {
                    day == today -> "Today"
                    day == today.minusDays(1) -> "Yesterday"
                    day.year == today.year -> day.format(sameYear)
                    else -> day.format(otherYear)
                }
                PhotoSection(label, items)
            }
    }

    /**
     * Slideshow order (board 8): photos plus, optionally, the folder's videos, newest first or
     * shuffled, capped at [limit], and rotated so [startIndex] plays first (keeping the rest).
     */
    fun <T : PhoneMediaFacts> slideshow(
        photos: List<T>,
        videos: List<T>,
        includeVideos: Boolean,
        shuffle: Boolean,
        startIndex: Int,
        limit: Int,
        random: kotlin.random.Random = kotlin.random.Random.Default,
    ): List<T> {
        val merged = (photos + if (includeVideos) videos else emptyList()).sortedByDescending { it.dateAdded }
        val ordered = (if (shuffle) merged.shuffled(random) else merged).take(limit)
        if (ordered.isEmpty()) return ordered
        val start = startIndex.coerceIn(0, ordered.size - 1)
        return ordered.drop(start) + ordered.take(start)
    }

    /**
     * Play next for several new items: each move puts one item right after the current one,
     * so moving them last-first leaves them in their original order.
     */
    fun playNextMoveOrder(newItemIds: List<String>): List<String> = newItemIds.reversed()

    /** Toggles [item] in a pick-ordered selection; removing renumbers the rest. */
    fun toggle(selection: List<String>, item: String): List<String> =
        if (item in selection) selection - item else selection + item

    /** "1:52:10" or "3:41". */
    fun clock(ms: Long): String {
        val s = (ms / 1000).coerceAtLeast(0)
        val h = s / 3600
        val m = (s % 3600) / 60
        val sec = s % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%d:%02d".format(m, sec)
    }

    /** "48 min" or "2 h 5 min" for album totals. */
    fun minutes(ms: Long): String {
        val totalMin = (ms / 60_000).coerceAtLeast(if (ms > 0) 1 else 0)
        val h = totalMin / 60
        val m = totalMin % 60
        return if (h > 0) "$h h $m min" else "$m min"
    }

    /** "24 MB", "1.8 GB"; null when unknown. */
    fun size(bytes: Long): String? {
        if (bytes <= 0) return null
        val kb = bytes / 1024.0
        val mb = kb / 1024.0
        val gb = mb / 1024.0
        return when {
            gb >= 1 -> "%.1f GB".format(gb)
            mb >= 1 -> "%.0f MB".format(mb)
            else -> "%.0f KB".format(kb.coerceAtLeast(1.0))
        }
    }

    fun songCount(n: Int): String = if (n == 1) "1 song" else "$n songs"
}
