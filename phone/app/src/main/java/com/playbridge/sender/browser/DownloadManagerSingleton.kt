package com.playbridge.sender.browser

import android.content.Context
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.cache.NoOpCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.exoplayer.offline.DownloadManager
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

            // Wrap DefaultHttpDataSource so every open() call gets cookies/headers
            // from DownloadHeadersStore, keyed by the request host. This is necessary
            // because DownloadRequest.Builder has no setHttpRequestHeaders() in Media3.
            val httpFactory = DefaultHttpDataSource.Factory()
            val dataSourceFactory = DataSource.Factory {
                val inner = httpFactory.createDataSource()
                object : DataSource by inner {
                    override fun open(dataSpec: DataSpec): Long {
                        val extra = DownloadHeadersStore.headersForUrl(dataSpec.uri.toString())
                        if (extra.isEmpty()) return inner.open(dataSpec)
                        val merged = HashMap(dataSpec.httpRequestHeaders).apply { putAll(extra) }
                        return inner.open(dataSpec.buildUpon().setHttpRequestHeaders(merged).build())
                    }
                }
            }
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
