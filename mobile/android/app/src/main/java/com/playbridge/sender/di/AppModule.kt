package com.playbridge.sender.di

import com.playbridge.sender.cast.CastHistorySettings
import com.playbridge.sender.connection.ConnectionStore
import com.playbridge.sender.connection.ConnectionViewModel
import com.playbridge.sender.connection.WebSocketClient
import com.playbridge.sender.connection.ConnectionCoordinator
import com.playbridge.sender.data.history.DatabaseProvider
import com.playbridge.sender.data.history.HistoryDatabase
import okhttp3.OkHttpClient
import org.koin.android.ext.koin.androidApplication
import org.koin.android.ext.koin.androidContext
import org.koin.androidx.viewmodel.dsl.viewModel
import org.koin.dsl.module
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.preferencesDataStoreFile
import com.playbridge.sender.cast.atv.toAtvTarget
import com.playbridge.sender.data.settings.SettingsRepository

val appModule = module {
    // 1. OkHttpClient Global Singleton.
    // Raised per-host parallelism keeps IPTV and download requests from queuing on
    // the 5-req/host default. Generous timeouts (15s) allow slow streams and M3U fetches.
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
    single { get<HistoryDatabase>().searchHistoryDao() }
    single { get<HistoryDatabase>().commandHistoryDao() }
    single { get<HistoryDatabase>().tabDao() }
    single { get<HistoryDatabase>().iptvPlaylistDao() }
    single { get<HistoryDatabase>().iptvChannelDao() }
    single { get<HistoryDatabase>().collectionDao() }
    single { get<HistoryDatabase>().collectionItemDao() }
    single { get<HistoryDatabase>().downloadDao() }

    // 3. Core Repositories & Services
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
    single { CastHistorySettings(androidContext()) }
    single { com.playbridge.sender.diagnostics.CastAttemptDiagnostics(androidContext()) }
    single { com.playbridge.sender.history.CastReplayStore(androidContext(), get()) }
    single { WebSocketClient(get(), get(), get()) }
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
            castAttemptDiagnostics = get(),
            castReplayStore = get(),
        )
    }

    // 5b'. PhoneQueueCoordinator — phone file lists of any length as a moving TV queue window.
    single {
        com.playbridge.sender.cast.PhoneQueueCoordinator(
            packager = com.playbridge.sender.connection.LocalCastPackager(androidContext() as android.app.Application),
            webSocketClient = get(),
            connectionCoordinator = get(),
            castSessionManager = get(),
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Default.limitedParallelism(1)),
        )
    }

    // 5c. ExternalQueueCoordinator — supplied playlists for third-party receivers
    single {
        com.playbridge.sender.connection.ExternalQueueCoordinator(
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
            externalQueueCoordinator = get(),
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Default.limitedParallelism(1)),
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

    // Remote adapters reuse the process-wide connection/session owners.
    single<com.playbridge.sender.cast.remote.NativeRemoteTransport> {
        com.playbridge.sender.cast.remote.WebSocketNativeRemoteTransport(
            get(), get(), get(), get(), CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
        )
    }
    single<com.playbridge.sender.cast.remote.ExternalRemoteTransport> {
        com.playbridge.sender.cast.remote.CastSessionExternalRemoteTransport(
            get(), get(), get(), CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
        )
    }
    single {
        com.playbridge.sender.cast.atv.AtvCredentialStore(
            com.playbridge.sender.cast.atv.FilesAtvSecretFile(androidContext()),
            com.playbridge.sender.cast.atv.AndroidAtvCipher(),
        )
    }
    single<com.playbridge.sender.cast.atv.AtvControlSession> {
        com.playbridge.sender.cast.atv.AtvRemoteClient(
            com.playbridge.sender.cast.atv.RustAtvBoundary(),
            CoroutineScope(SupervisorJob() + Dispatchers.IO),
            phoneUUID = { com.playbridge.sender.connection.PlayBridgePhoneIdentity.uuid(
                androidContext().getSharedPreferences("browser_prefs", android.content.Context.MODE_PRIVATE)) },
        )
    }
    single {
        val sessions = get<com.playbridge.sender.cast.CastSessionManager>()
        val receiverScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val receivers = sessions.remoteDevice
            .map { device -> device?.toAtvTarget() }
            .stateIn(receiverScope, SharingStarted.Eagerly, sessions.remoteDevice.value?.toAtvTarget())
        com.playbridge.sender.cast.atv.AtvTvRemoteChannel(
            session = get(),
            credentials = get(),
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
            receivers = receivers,
        ).also { channel ->
            receiverScope.launch(Dispatchers.Main.immediate) {
                androidx.lifecycle.ProcessLifecycleOwner.get().lifecycle.addObserver(object : androidx.lifecycle.DefaultLifecycleObserver {
                    override fun onStart(owner: androidx.lifecycle.LifecycleOwner) { channel.setAppForeground(true) }
                    override fun onStop(owner: androidx.lifecycle.LifecycleOwner) { channel.setAppForeground(false) }
                })
            }
        }
    }
    single<com.playbridge.sender.cast.remote.TvRemoteChannel> { get<com.playbridge.sender.cast.atv.AtvTvRemoteChannel>() }
    single<com.playbridge.sender.cast.atv.AtvPairingController> { get<com.playbridge.sender.cast.atv.AtvTvRemoteChannel>() }
    single { com.playbridge.sender.cast.remote.RemoteInputRouter(get()) }
    single {
        com.playbridge.sender.cast.remote.RemoteControllerRegistry.create(
            get(), get(), get(), get(), CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
        )
    }
    single<com.playbridge.sender.cast.remote.RemotePresentationPreferenceStore> { com.playbridge.sender.cast.remote.RemotePresentationPreferences(androidContext()) }
    viewModel {
        com.playbridge.sender.cast.remote.RemoteViewModel(
            get(),
            get(),
            presentationPreferences = get(),
            tvRemote = get(),
        )
    }

    single {
        com.playbridge.sender.cast.atv.RemoteTVDirectory(androidContext(), get(), get(), get(), get(), get(), get(),
            CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate))
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
            remoteTVDirectory = get(),
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
