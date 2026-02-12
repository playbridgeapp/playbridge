package com.playbridge.sender.browser

import android.content.Context
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.cache.NoOpCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.exoplayer.offline.DownloadManager
import androidx.media3.common.util.UnstableApi
import java.io.File
import java.util.concurrent.Executors

@UnstableApi
object DownloadManagerSingleton {
    private var downloadManager: DownloadManager? = null
    private var downloadCache: SimpleCache? = null
    private var databaseProvider: StandaloneDatabaseProvider? = null

    @Synchronized
    fun getDownloadManager(context: Context): DownloadManager {
        if (downloadManager == null) {
            val databaseProvider = StandaloneDatabaseProvider(context)
            this.databaseProvider = databaseProvider
            
            val downloadContentDirectory = File(context.getExternalFilesDir(null), "downloads")
            if (!downloadContentDirectory.exists()) {
                downloadContentDirectory.mkdirs()
            }
            
            val cache = SimpleCache(
                downloadContentDirectory, 
                NoOpCacheEvictor(), 
                databaseProvider
            )
            downloadCache = cache

            val dataSourceFactory = DefaultHttpDataSource.Factory()
            val executor = Executors.newFixedThreadPool(6)

            downloadManager = DownloadManager(
                context,
                databaseProvider,
                cache,
                dataSourceFactory,
                executor
            ).apply {
                maxParallelDownloads = 3
            }
        }
        return downloadManager!!
    }
    
    // Helper to access cache if needed (e.g. for playing offline)
    @Synchronized
    fun getDownloadCache(context: Context): SimpleCache {
        if (downloadCache == null) {
            getDownloadManager(context) // Initialize
        }
        return downloadCache!!
    }
}
