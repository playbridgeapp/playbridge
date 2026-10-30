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

@Database(
    entities = [HistoryEntity::class, BookmarkEntity::class, TabEntity::class, CommandHistoryEntity::class, SearchHistoryEntity::class, IptvPlaylistEntity::class, IptvChannelEntity::class, CollectionEntity::class, CollectionItemEntity::class, DownloadEntity::class],
    version = 25
)
abstract class HistoryDatabase : RoomDatabase() {
    abstract fun historyDao(): HistoryDao
    abstract fun bookmarkDao(): BookmarkDao
    abstract fun tabDao(): TabDao
    abstract fun commandHistoryDao(): CommandHistoryDao
    abstract fun searchHistoryDao(): SearchHistoryDao
    abstract fun iptvPlaylistDao(): IptvPlaylistDao
    abstract fun iptvChannelDao(): IptvChannelDao
    abstract fun collectionDao(): CollectionDao
    abstract fun collectionItemDao(): CollectionItemDao
    abstract fun downloadDao(): DownloadDao
}
