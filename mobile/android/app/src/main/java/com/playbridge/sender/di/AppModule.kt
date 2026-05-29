package com.playbridge.sender.di

import com.playbridge.sender.connection.ConnectionStore
import com.playbridge.sender.connection.ConnectionViewModel
import com.playbridge.sender.connection.NsdHelper
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
    // 1. OkHttpClient Global Singleton
    single {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .followRedirects(true)
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
    single { get<HistoryDatabase>().commandHistoryDao() }
    single { get<HistoryDatabase>().tabDao() }

    // 3. Core Repositories & Services
    single {
        AddonRepository(
            addonDao = get(),
            cacheDir = androidContext().cacheDir,
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

    // 4. WebSocket Client & NSD Helpers
    single { WebSocketClient() }
    single { ConnectionStore(androidContext()) }
    single { NsdHelper(androidContext()) }

    // 5. ConnectionCoordinator Singleton
    single {
        ConnectionCoordinator(
            webSocketClient = get(),
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        )
    }

    // 6. ViewModels
    viewModel {
        ConnectionViewModel(
            application = androidApplication(),
            webSocketClient = get(),
            connectionStore = get(),
            nsdHelper = get(),
            commandHistoryDb = get()
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
}
