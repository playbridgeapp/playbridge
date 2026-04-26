# Phone App Architecture

## Package Structure
```
com.playbridge.sender/
├── PlayBridgeApplication.kt
├── browser
│   ├── AddonInstallDialog.kt
│   ├── AddonSettingsScreen.kt
│   ├── AppearanceSettingsScreen.kt
│   ├── BookmarksScreen.kt
│   ├── BrowserActivity.kt
│   ├── BrowserToolbar.kt
│   ├── CastSheet.kt
│   ├── CommandHistoryScreen.kt
│   ├── Components.kt
│   ├── DashParser.kt
│   ├── DebridLibraryScreen.kt
│   ├── DebridSettingsScreen.kt
│   ├── DownloadConfirmDialog.kt
│   ├── DownloadHeadersStore.kt
│   ├── DownloadManagerSingleton.kt
│   ├── DownloadUtils.kt
│   ├── DownloadsScreen.kt
│   ├── ErrorPageUtils.kt
│   ├── ExportedSettings.kt
│   ├── ExtensionsScreen.kt
│   ├── FindOnPageBar.kt
│   ├── HistoryScreen.kt
│   ├── HlsExportRegistry.kt
│   ├── HlsExportService.kt
│   ├── HlsExporter.kt
│   ├── HlsParser.kt
│   ├── HomeScreen.kt
│   ├── ImportExportSettingsScreen.kt
│   ├── LibraryDetailScreen.kt
│   ├── LibraryEnums.kt
│   ├── LibraryScreen.kt
│   ├── LibrarySettingsScreen.kt
│   ├── LibraryUtils.kt
│   ├── LibraryViewModel.kt
│   ├── LinkContextMenu.kt
│   ├── MagnetParsingSheet.kt
│   ├── MediaDownloadService.kt
│   ├── MediaflowProxy.kt
│   ├── MediaflowSettingsScreen.kt
│   ├── MyListTab.kt
│   ├── PopupBlockerSettingsScreen.kt
│   ├── RemoteControlScreen.kt
│   ├── SessionObserverSetup.kt
│   ├── SettingsScreen.kt
│   ├── SiteInfoSheet.kt
│   ├── StreamingSettingsScreen.kt
│   ├── StreamPickerSheet.kt
│   ├── StreamSelector.kt
│   ├── SubtitlePreferences.kt
│   ├── TVSettingsScreen.kt
│   ├── TabManager.kt
│   ├── TabsScreen.kt
│   ├── TrackingSheet.kt
│   ├── VideoDetector.kt
│   └── VideoPreviewSheet.kt
├── connection
│   ├── BluetoothClient.kt
│   ├── ConnectionStore.kt
│   ├── ConnectionViewModel.kt
│   ├── NsdHelper.kt
│   └── WebSocketClient.kt
├── data
│   ├── backup
│   │   ├── BackupManager.kt
│   │   ├── BackupTrigger.kt
│   │   └── BackupUtils.kt
│   ├── debrid
│   │   ├── AllDebridClient.kt
│   │   ├── DebridModels.kt
│   │   ├── DebridProvider.kt
│   │   ├── DebridRepository.kt
│   │   ├── PremiumizeClient.kt
│   │   └── RealDebridClient.kt
│   ├── history
│   │   ├── BookmarkDao.kt
│   │   ├── BookmarkEntity.kt
│   │   ├── CommandHistoryDao.kt
│   │   ├── CommandHistoryEntity.kt
│   │   ├── DatabaseProvider.kt
│   │   ├── HistoryDao.kt
│   │   ├── HistoryDatabase.kt
│   │   ├── HistoryEntity.kt
│   │   ├── SearchHistoryDao.kt
│   │   ├── SearchHistoryEntity.kt
│   │   ├── TabDao.kt
│   │   └── TabEntity.kt
│   └── library
│       ├── AddonDao.kt
│       ├── AddonModels.kt
│       ├── AddonRepository.kt
│       ├── OmdbModels.kt
│       ├── OmdbRepository.kt
│       ├── StremioSubtitleService.kt
│       ├── TmdbModels.kt
│       ├── TmdbRepository.kt
│       ├── TvdbModels.kt
│       ├── TvdbRepository.kt
│       ├── WatchlistDao.kt
│       ├── WatchlistEntity.kt
│       └── WatchlistStatus.kt
├── model
│   └── TvDevice.kt
└── ui
    ├── ConnectionScreen.kt
    ├── HomeScreen.kt
    └── theme
        ├── Color.kt
        ├── Theme.kt
        └── Type.kt
```

## Key Components

| Component | File | Purpose |
|-----------|------|---------|
| Browser Engine | Components.kt | Singleton DI container for GeckoRuntime, BrowserStore, AddonManager |
| Browser UI | BrowserActivity.kt | Main browser activity (~1685 lines) with UI composition, target routing, toolbar, dropdown menu |
| Browser Toolbar | BrowserToolbar.kt | Custom Compose toolbar with navigation, URL bar, SSL lock indicator, desktop mode toggle |
| Library View | LibraryScreen.kt | Main Library screen for TMDB browsing and multi-search |
| Library ViewModel | LibraryViewModel.kt | ViewModel managing TMDB API calls and pagination |
| Library Details | LibraryDetailScreen.kt | Movie/TV Details with stream resolution integration |
| Addon Manager | AddonRepository.kt | Installs Stremio addons and resolves video streams via IMDB ID |
| TMDB Client | TmdbRepository.kt | Coroutine-based client for TMDB API v3 |
| OMDB Client | OmdbRepository.kt | Client for OMDB API (ratings and metadata) |
| Stream Selection | StreamPickerSheet.kt | Bottom sheet displaying resolved Addon streams |
| Video Actions | VideoPreviewSheet.kt | Unified bottom sheet handling all 'Play on TV' actions (Browser, TMDB, Debrid, Magnets), routing single videos (Videos/Subtitles tabs) and multi-item playlists (`playlistPayload`). |
| Debrid Integration | DebridProvider.kt | Abstract interface for Debrid magnet and `.torrent` parsing |
| Debrid Clients | RealDebrid, AllDebrid, Premiumize | API implementations for major Debrid services |
| Magnet UI | MagnetParsingSheet.kt | Bottom sheet UI for selecting files from intercepted magnets/torrents |
| Addon Config | AddonSettingsScreen.kt | UI for managing installed Stremio addons |
| Tab Management | TabManager.kt | Tab/session lifecycle, session sync, find-in-page helpers |
| Tab UI | TabsScreen.kt | Tab list/grid overlay with thumbnails |
| Session Observer | SessionObserverSetup.kt | EngineSession.Observer + GeckoSession delegate proxies, user-agent switching, download interceptions |
| Extension Config | ExtensionsScreen.kt | Browser addon installation and management UI |
| Addon Install | AddonInstallDialog.kt | Extension installation confirmation dialog |
| Remote Control | RemoteControlScreen.kt | Context-aware D-pad, touchpad, and player controls for TV |
| HLS Parser | HlsParser.kt | Parses HLS manifests for quality selection with audio track support |
| Video Detection | VideoDetector.kt | Video content type detection via request interception |
| Watchlist System | WatchlistDao.kt | Room-based persistence for 'My List', tracking progress, and episode-level 'watched' states |
| Cloud Backup | BackupManager.kt | S3-compatible cloud backup/restore for all app settings and databases |
| Ambient UI | LibraryScreen.kt | Dynamic 'Hero' backdrops that adapt to currently highlighted media |
| Downloads | DownloadsScreen.kt | Download list UI (system + HLS/ExoPlayer downloads) |
| Download Utils | DownloadUtils.kt | Download helper: standard files via DownloadManager, HLS via ExoPlayer DownloadService |
| HLS Download Manager | DownloadManagerSingleton.kt | Media3 ExoPlayer download manager singleton for HLS offline downloads |
| Media Download Service | MediaDownloadService.kt | Foreground service for HLS media downloads with notifications |
| Command History | CommandHistoryScreen.kt | UI for viewing command history sent to TV |
| Home Page | HomeScreen.kt | Default browser landing page |
| Browser Settings | SettingsScreen.kt | Browser settings (e.g., toggle inbuilt extension visibility, import/export settings) |
| Settings Export | ExportedSettings.kt | Serializable data models for settings export/import including tabs, addons, Debrid configs |
| Browser History | HistoryScreen.kt | Browsing history list with clear functionality |
| Bookmarks | BookmarksScreen.kt | Bookmarks list UI with add/remove |
| Home Page | HomeScreen.kt | Browser home page with bookmarks display |
| Database | DatabaseProvider.kt | Room database singleton with History, Bookmark, and Tab DAOs |
| Tab Persistence | TabDao.kt | Room DAO for saving/restoring tab state across sessions |
| Find on Page | FindOnPageBar.kt | UI for finding text within web pages |
| Connection VM | ConnectionViewModel.kt | Centralized logic for WebSocket + NSD discovery, TV commands, state |
| WebSocket | WebSocketClient.kt | OkHttp-based client with auto-retry (60 attempts, 5s intervals) |
| Bluetooth Client | BluetoothClient.kt | Bluetooth RFCOMM socket client fallback for TV connections |
| Connection | ConnectionScreen.kt | NSD auto-discovery, manual IP entry, and PIN authentication |
| Service Discovery | NsdHelper.kt | Network Service Discovery to find TV services on local network |
| Embedded Extension | `assets/extensions/video_detector` | Legacy internal extension bundled with the phone app for video detection in GeckoView |

## Dependencies
- **GeckoView** (Mozilla) v150.0.20260415192539 — Full Firefox engine
- **Mozilla Android Components** v150.0 — Tabs, toolbar, extensions, sessions, prompts support
- **OkHttp** v4.12.0 — WebSocket client
- **Jetpack Compose BOM** v2024.09.00 — UI (Material3)
- **Kotlin Serialization** v1.7.3 — JSON protocol
- **DataStore** v1.1.1 — Preferences persistence
- **Room** v2.8.4 — SQLite persistence for browsing history
- **Media3 ExoPlayer** v1.9.2 — HLS offline download support
- **Coil** v2.7.0 — Image loading
