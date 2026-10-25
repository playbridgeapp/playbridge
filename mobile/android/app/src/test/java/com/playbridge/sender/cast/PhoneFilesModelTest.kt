package com.playbridge.sender.cast

import java.time.LocalDate
import java.time.ZoneOffset
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Test

class PhoneFilesModelTest {

    private data class Item(
        override val title: String,
        override val durationMs: Long = 0L,
        override val artist: String? = null,
        override val album: String? = null,
        override val trackNumber: Int? = null,
        override val sizeBytes: Long = 0L,
        override val dateAdded: Long = 0L,
        override val bucketName: String? = null,
        override val bucketId: Long? = null,
    ) : PhoneMediaFacts

    @Test
    fun foldersAreOrderedByCountThenName() {
        val items = listOf(
            Item("a", bucketId = 1, bucketName = "Movies"),
            Item("b", bucketId = 2, bucketName = "Camera"),
            Item("c", bucketId = 2, bucketName = "Camera"),
            Item("d", bucketId = 3, bucketName = "Downloads"),
            Item("e"),
        )
        assertEquals(
            listOf(PhoneFolder(2, "Camera", 2), PhoneFolder(3, "Downloads", 1), PhoneFolder(1, "Movies", 1)),
            PhoneFilesModel.folders(items),
        )
    }

    @Test
    fun visibleFiltersByFolderAndQueryThenSorts() {
        val items = listOf(
            Item("Beach", dateAdded = 1, sizeBytes = 30, bucketId = 1),
            Item("beach party", dateAdded = 3, sizeBytes = 10, bucketId = 1),
            Item("Mountain", dateAdded = 2, sizeBytes = 20, bucketId = 1),
            Item("Beach other", dateAdded = 4, bucketId = 2),
        )
        assertEquals(
            listOf("beach party", "Beach"),
            PhoneFilesModel.visible(items, folderId = 1, query = "BEACH", sort = SortKey.NEWEST).map { it.title },
        )
        assertEquals(
            listOf("Beach", "Mountain", "beach party"),
            PhoneFilesModel.visible(items, folderId = 1, query = "", sort = SortKey.LARGEST).map { it.title },
        )
        assertEquals(
            listOf("Beach", "Mountain", "beach party", "Beach other"),
            PhoneFilesModel.visible(items, folderId = null, query = "", sort = SortKey.OLDEST).map { it.title },
        )
    }

    @Test
    fun searchMatchesArtistAndAlbum() {
        val song = Item("Track", artist = "Nina", album = "Blue")
        assertEquals(true, PhoneFilesModel.matches(song, "nin"))
        assertEquals(true, PhoneFilesModel.matches(song, "blu"))
        assertEquals(false, PhoneFilesModel.matches(song, "red"))
    }

    @Test
    fun albumsKeepSameNamedAlbumsByDifferentArtistsApartAndOrderTracks() {
        val songs = listOf(
            Item("Two", album = "Greatest Hits", artist = "A", trackNumber = 2),
            Item("One", album = "Greatest Hits", artist = "A", trackNumber = 1),
            Item("Other", album = "Greatest Hits", artist = "B"),
            Item("Loose"),
        )
        val albums = PhoneFilesModel.albums(songs)
        assertEquals(2, albums.size)
        assertEquals(listOf("One", "Two"), albums.first { it.subtitle == "A" }.songs.map { it.title })
    }

    @Test
    fun artistsGroupCaseInsensitively() {
        val songs = listOf(Item("x", artist = "Nina"), Item("y", artist = "nina"), Item("z", artist = "Bob"))
        assertEquals(listOf("Bob" to 1, "Nina" to 2), PhoneFilesModel.artists(songs).map { it.title to it.songs.size })
    }

    @Test
    fun photoSectionsLabelTodayYesterdayAndDates() {
        val today = LocalDate.of(2026, 10, 25)
        fun at(day: LocalDate) = day.atStartOfDay().toEpochSecond(ZoneOffset.UTC) + 3600
        val photos = listOf(
            Item("old", dateAdded = at(LocalDate.of(2025, 3, 2))),
            Item("today", dateAdded = at(today)),
            Item("yesterday", dateAdded = at(today.minusDays(1))),
            Item("week", dateAdded = at(LocalDate.of(2026, 10, 20))),
        )
        val sections = PhoneFilesModel.photoSections(photos, today, ZoneOffset.UTC, Locale.UK)
        assertEquals(listOf("Today", "Yesterday", "Tue 20 Oct", "2 Mar 2025"), sections.map { it.label })
    }

    @Test
    fun toggleKeepsPickOrder() {
        var selection = emptyList<String>()
        selection = PhoneFilesModel.toggle(selection, "a")
        selection = PhoneFilesModel.toggle(selection, "c")
        selection = PhoneFilesModel.toggle(selection, "b")
        selection = PhoneFilesModel.toggle(selection, "a")
        assertEquals(listOf("c", "b"), selection)
    }

    @Test
    fun formatsDurationsAndSizes() {
        assertEquals("1:52:10", PhoneFilesModel.clock(6_730_000))
        assertEquals("0:08", PhoneFilesModel.clock(8_000))
        assertEquals("48 min", PhoneFilesModel.minutes(48 * 60_000L))
        assertEquals("2 h 5 min", PhoneFilesModel.minutes(125 * 60_000L))
        assertEquals("24 MB", PhoneFilesModel.size(24L * 1024 * 1024))
        assertEquals(null, PhoneFilesModel.size(0))
    }

    @Test
    fun slideshowMergesVideosNewestFirstAndRotatesToStart() {
        val photos = listOf(Item("p1", dateAdded = 1), Item("p3", dateAdded = 3))
        val videos = listOf(Item("v2", dateAdded = 2))
        assertEquals(
            listOf("p3", "v2", "p1"),
            PhoneFilesModel.slideshow(photos, videos, includeVideos = true, shuffle = false, startIndex = 0, limit = 10).map { it.title },
        )
        assertEquals(
            listOf("p3", "p1"),
            PhoneFilesModel.slideshow(photos, videos, includeVideos = false, shuffle = false, startIndex = 0, limit = 10).map { it.title },
        )
        // Changing the time per photo mid-show restarts at the current item, keeping the rest.
        assertEquals(
            listOf("v2", "p1", "p3"),
            PhoneFilesModel.slideshow(photos, videos, includeVideos = true, shuffle = false, startIndex = 1, limit = 10).map { it.title },
        )
        assertEquals(2, PhoneFilesModel.slideshow(photos, videos, true, false, 0, limit = 2).size)
        assertEquals(emptyList<Item>(), PhoneFilesModel.slideshow(emptyList<Item>(), emptyList(), true, false, 3, 10))
    }

    @Test
    fun slideshowShuffleKeepsEveryItemOnce() {
        val photos = (1..20).map { Item("p$it", dateAdded = it.toLong()) }
        val shuffled = PhoneFilesModel.slideshow(photos, emptyList(), false, shuffle = true, startIndex = 0, limit = 50, random = kotlin.random.Random(7))
        assertEquals(photos.map { it.title }.toSet(), shuffled.map { it.title }.toSet())
        assertEquals(20, shuffled.size)
        assertEquals(true, shuffled.map { it.title } != photos.sortedByDescending { it.dateAdded }.map { it.title })
    }

    @Test
    fun playNextMovesLastFirstSoPickOrderSurvives() {
        // Simulate the receiver: each Play next move inserts right after the current item.
        val queue = mutableListOf("current", "later", "a", "b", "c")
        for (id in PhoneFilesModel.playNextMoveOrder(listOf("a", "b", "c"))) {
            queue.remove(id)
            queue.add(queue.indexOf("current") + 1, id)
        }
        assertEquals(listOf("current", "a", "b", "c", "later"), queue)
    }
}
