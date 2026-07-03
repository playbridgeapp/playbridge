package com.playbridge.sender.data.history

import androidx.room.Database
import androidx.room.RoomDatabase
import com.playbridge.sender.data.collection.CollectionDao
import com.playbridge.sender.data.collection.CollectionEntity
import com.playbridge.sender.data.collection.CollectionItemDao
import com.playbridge.sender.data.collection.CollectionItemEntity
import com.playbridge.sender.data.downloads.DownloadDao
import com.playbridge.sender.data.downloads.DownloadEntity
import com.playbridge.sender.data.iptv.IptvChannelDao
import com.playbridge.sender.data.iptv.IptvChannelEntity
import com.playbridge.sender.data.iptv.IptvPlaylistDao
import com.playbridge.sender.data.iptv.IptvPlaylistEntity
import com.playbridge.sender.data.library.AddonDao
import com.playbridge.sender.data.library.InstalledAddonEntity
import com.playbridge.sender.data.library.PlaybackResumeDao
import com.playbridge.sender.data.library.PlaybackResumeEntity
import com.playbridge.sender.data.library.WatchlistDao
import com.playbridge.sender.data.library.WatchlistEntity
import com.playbridge.sender.data.nuvio.NuvioScraperDao
import com.playbridge.sender.data.nuvio.NuvioScraperEntity

@Database(
    entities = [HistoryEntity::class, BookmarkEntity::class, TabEntity::class, InstalledAddonEntity::class, CommandHistoryEntity::class, WatchlistEntity::class, SearchHistoryEntity::class, PlaybackResumeEntity::class, IptvPlaylistEntity::class, IptvChannelEntity::class, CollectionEntity::class, CollectionItemEntity::class, DownloadEntity::class, NuvioScraperEntity::class],
    version = 22
)
abstract class HistoryDatabase : RoomDatabase() {
    abstract fun historyDao(): HistoryDao
    abstract fun bookmarkDao(): BookmarkDao
    abstract fun tabDao(): TabDao
    abstract fun addonDao(): AddonDao
    abstract fun commandHistoryDao(): CommandHistoryDao
    abstract fun watchlistDao(): WatchlistDao
    abstract fun searchHistoryDao(): SearchHistoryDao
    abstract fun playbackResumeDao(): PlaybackResumeDao
    abstract fun iptvPlaylistDao(): IptvPlaylistDao
    abstract fun iptvChannelDao(): IptvChannelDao
    abstract fun collectionDao(): CollectionDao
    abstract fun collectionItemDao(): CollectionItemDao
    abstract fun downloadDao(): DownloadDao
    abstract fun nuvioScraperDao(): NuvioScraperDao
}
