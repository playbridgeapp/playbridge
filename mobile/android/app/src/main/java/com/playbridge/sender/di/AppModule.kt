package com.playbridge.sender.di

import com.playbridge.sender.connection.ConnectionStore
import com.playbridge.sender.connection.ConnectionViewModel
import com.playbridge.sender.connection.WebSocketClient
import com.playbridge.sender.connection.ConnectionCoordinator
import com.playbridge.sender.data.debrid.DebridRepository
import com.playbridge.sender.data.history.DatabaseProvider
import com.playbridge.sender.data.history.HistoryDatabase
import com.playbridge.sender.data.library.AddonRepository
import com.playbridge.sender.data.library.StremioSubtitleService
import com.playbridge.sender.data.library.TmdbRepository
import com.playbridge.sender.library.LibraryViewModel
import okhttp3.OkHttpClient
import org.koin.android.ext.koin.androidApplication
import org.koin.android.ext.koin.androidContext
import org.koin.androidx.viewmodel.dsl.viewModel
import org.koin.dsl.module
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.preferencesDataStoreFile
import com.playbridge.sender.data.settings.SettingsRepository

val appModule = module {
    // 1. OkHttpClient Global Singleton.
    // Perf: raised per-host parallelism so TMDB/addon/IPTV fan-out doesn't queue on
    // the 5-req/host default and stall Home refresh. Timeouts stay generous (15s):
    // this client also serves downloads, IPTV M3U fetches, and debrid — a short
    // global read timeout would fail slow streams, not just catalogs.
    single {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .followRedirects(true)
            .dispatcher(
                okhttp3.Dispatcher().apply {
                    maxRequests = 128
                    maxRequestsPerHost = 16
                }
            )
            .build()
    }

    // 2. Room Database & DAOs
    single<HistoryDatabase> {
        DatabaseProvider.getDatabase(androidContext())
    }
    single { get<HistoryDatabase>().historyDao() }
    single { get<HistoryDatabase>().bookmarkDao() }
    single { get<HistoryDatabase>().addonDao() }
    single { get<HistoryDatabase>().watchlistDao() }
    single { get<HistoryDatabase>().searchHistoryDao() }
    single { get<HistoryDatabase>().playbackResumeDao() }
    single { get<HistoryDatabase>().commandHistoryDao() }
    single { get<HistoryDatabase>().tabDao() }
    single { get<HistoryDatabase>().iptvPlaylistDao() }
    single { get<HistoryDatabase>().iptvChannelDao() }
    single { get<HistoryDatabase>().collectionDao() }
    single { get<HistoryDatabase>().collectionItemDao() }
    single { get<HistoryDatabase>().downloadDao() }
    single { get<HistoryDatabase>().nuvioScraperDao() }

    // 3. Core Repositories & Services
    single {
        AddonRepository(
            addonDao = get(),
            cacheDir = androidContext().cacheDir,
            client = get()
        )
    }
    // Nuvio scraper-plugin support: sandboxed JS runner + repository.
    single { com.playbridge.sender.data.nuvio.NuvioScraperRunner(context = androidContext(), client = get()) }
    single {
        com.playbridge.sender.data.nuvio.NuvioRepository(
            addonDao = get(),
            scraperDao = get(),
            runner = get(),
            settingsRepository = get(),
            filesDir = androidContext().filesDir,
            client = get()
        )
    }
    single {
        TmdbRepository(
            context = androidContext(),
            client = get()
        )
    }
    single {
        StremioSubtitleService(
            addonRepository = get(),
            client = get()
        )
    }
    single {
        DebridRepository(
            context = androidContext(),
            client = get()
        )
    }
    single {
        com.playbridge.sender.data.iptv.IptvRepository(
            context = androidContext(),
            playlistDao = get(),
            channelDao = get(),
            httpClient = get()
        )
    }
    single {
        com.playbridge.sender.data.collection.CollectionsRepository(
            collectionDao = get(),
            itemDao = get()
        )
    }

    // 4. WebSocket client, persistence, and the process-wide Rust discovery owner
    single { WebSocketClient() }
    single { ConnectionStore(androidContext()) }
    single {
        com.playbridge.sender.connection.ReceiverDiscoveryRepository(
            context = androidContext(),
            scope = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(1)),
        )
    }
    single { com.playbridge.sender.connection.NetworkStatusRepository(androidContext()) }

    // Browser receiver host (cast-to-browser) — process-wide host + pairing state.
    single {
        com.playbridge.sender.cast.browser.BrowserReceiverRepository(
            context = androidContext(),
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
        )
    }

    // 5. ConnectionCoordinator Singleton
    single {
        ConnectionCoordinator(
            webSocketClient = get(),
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        )
    }

    single {
        com.playbridge.sender.cast.mirror.ScreenMirrorCoordinator(
            context = androidContext(),
            webSocketClient = get(),
            appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
        )
    }

    single {
        com.playbridge.sender.cast.mirror.ExternalScreenMirrorCoordinator(
            context = androidContext(),
            appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
        )
    }

    // 5a. CastSessionManager — process-wide owner of the active cast target (native/DLNA)
    //     and the CastSessionService foreground lifecycle.
    //     Single-threaded scope: the reconnect-supervisor state (attempt counter, job,
    //     hasConnectedThisSession) is mutated from several collectors plus external
    //     callbacks; serializing the scope removes the data races without locks.
    single {
        com.playbridge.sender.cast.CastSessionManager(
            context = androidContext(),
            webSocketClient = get(),
            connectionCoordinator = get(),
            connectionStore = get(),
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Default.limitedParallelism(1)),
            discoveryRepository = get(),
            settingsRepository = get(),
            screenMirrorCoordinator = get(),
            externalScreenMirrorCoordinator = get(),
        )
    }

    // 5b. TvQueueCoordinator — lazy episode queueing for series without a play-endpoint addon
    single {
        com.playbridge.sender.connection.TvQueueCoordinator(
            context = androidContext(),
            webSocketClient = get(),
            addonRepository = get(),
            connectionCoordinator = get(),
            settingsRepository = get(),
            subtitleService = get(),
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        )
    }

    // 5c. ExternalQueueCoordinator — phone-driven episode auto-advance for third-party receivers
    single {
        com.playbridge.sender.connection.ExternalQueueCoordinator(
            context = androidContext(),
            addonRepository = get(),
            castSessionManager = get(),
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        )
    }

    // Website-linked lazy queue authority for the built-in GeckoView browser.
    single {
        com.playbridge.sender.browser.LinkedPageCastCoordinator(
            webSocketClient = get(),
            connectionCoordinator = get(),
            connectionStore = get(),
            settingsRepository = get(),
            tvQueueCoordinator = get(),
            externalQueueCoordinator = get(),
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Default.limitedParallelism(1)),
        )
    }

    // 5d. PlaybackProgressTracker — auto-updates watchlist progress from TV playback.
    //     Eager: it has no UI injector (only PlayerActivity injects it), so lazy
    //     registration would leave TV/DLNA progress tracking dead on direct casts.
    //     Single-threaded scope: the three transport legs (native/DLNA/in-app) share
    //     non-thread-safe session state (markedKeys/ensuredKeys, threshold arming);
    //     serializing the scope makes their interleaving safe.
    single(createdAtStart = true) {
        com.playbridge.sender.connection.PlaybackProgressTracker(
            watchlistDao = get(),
            resumeDao = get(),
            tmdb = get(),
            settingsRepository = get(),
            connectionCoordinator = get(),
            castSessionManager = get(),
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Default.limitedParallelism(1))
        )
    }

    // 5e. Download engine (Phase 1) — strategies + repository. The DownloadWorker
    //     resolves these from Koin itself (KoinComponent), so they only need to be
    //     registered here, not injected into a WorkerFactory.
    single<com.playbridge.sender.downloads.engine.Muxer> {
        com.playbridge.sender.downloads.engine.PlatformMuxer()
    }
    single {
        com.playbridge.sender.downloads.engine.DownloadStrategies(
            listOf(
                com.playbridge.sender.downloads.engine.FileStrategy(client = get()),
                com.playbridge.sender.downloads.engine.HlsStrategy(
                    context = androidContext(),
                    client = get(),
                    muxer = get(),
                ),
            ),
        )
    }
    single {
        com.playbridge.sender.downloads.engine.DownloadRepository(
            context = androidContext(),
            dao = get(),
        )
    }

    // 6. ViewModels
    viewModel {
        ConnectionViewModel(
            application = androidApplication(),
            webSocketClient = get(),
            connectionStore = get(),
            commandHistoryDb = get(),
            castSessionManager = get(),
            discoveryRepository = get(),
            networkStatusRepository = get(),
        )
    }

    viewModel {
        LibraryViewModel(
            application = androidApplication(),
            tmdb = get(),
            database = get(),
            addonRepository = get()
        )
    }

    viewModel {
        com.playbridge.sender.iptv.IptvViewModel(
            application = androidApplication(),
            repository = get(),
            settings = get()
        )
    }

    viewModel {
        com.playbridge.sender.collection.CollectionsViewModel(
            repository = get()
        )
    }

    viewModel {
        com.playbridge.sender.browser.BrowserViewModel(
            application = androidApplication(),
            historyDao = get(),
            bookmarkDao = get(),
            tabDao = get()
        )
    }

    // 7. Jetpack DataStore & SettingsRepository
    single {
        PreferenceDataStoreFactory.create(
            produceFile = { androidContext().preferencesDataStoreFile("playbridge_settings") }
        )
    }
    single { SettingsRepository(get()) }

    // 8. In-app update flow (sideload self-update vs Play Store redirect)
    single { com.playbridge.sender.update.ApkInstaller(androidContext()) }
    single {
        com.playbridge.sender.update.UpdateChecker(
            appContext = androidContext(),
            installer = get(),
        )
    }
}
