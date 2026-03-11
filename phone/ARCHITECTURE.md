# Phone App Architecture

## Package Structure
```
com.playbridge.sender/
├── browser/                # GeckoView browser, video detection, extensions, downloads
│    ├── AddonInstallDialog.kt       (extension install confirmation dialog)
│   ├── AddonSettingsScreen.kt      (Stremio addon management UI)
│   ├── BookmarksScreen.kt          (bookmarks list UI with add/remove)
│   ├── BrowserActivity.kt          (main browser activity, ~1685 lines)
│   ├── BrowserToolbar.kt           (custom Compose toolbar with URL bar, navigation, SSL lock, menu)
│   ├── Components.kt               (singleton DI container for GeckoRuntime, BrowserStore)
│   ├── DebridLibraryScreen.kt      (Debrid torrent/magnet management and resolution UI)
│   ├── DetectedVideosSheet.kt      (bottom sheet for detected videos + quality selection)
│   ├── DownloadConfirmDialog.kt    (download confirmation dialog + PendingDownload model)
│   ├── DownloadManagerSingleton.kt  (Media3 ExoPlayer download manager for HLS)
│   ├── DownloadsScreen.kt          (downloads list UI with progress tracking)
│   ├── DownloadUtils.kt            (download helpers: enqueue, file size, error strings)
│   ├── ErrorPageUtils.kt           (utility for rendering error pages)
│   ├── ExtensionsScreen.kt         (browser addon management screen)
│   ├── FindOnPageBar.kt            (UI for in-page text search)
│   ├── HistoryScreen.kt            (browsing history list UI)
│   ├── HlsParser.kt               (HLS manifest parsing for quality selection with audio tracks)
│   ├── HomeScreen.kt               (browser home page with bookmarks)
│   ├── LibraryDetailScreen.kt      (movie/TV details with stream resolution)
│   ├── LibraryScreen.kt            (TMDB popular/trending/search UI)
│   ├── LinkContextMenu.kt          (long-press link context menu dialog)
│   ├── MagnetParsingSheet.kt       (bottom sheet UI for Debrid magnet and .torrent parsing)
│   ├── MediaDownloadService.kt     (foreground service for HLS/media downloads)
│   ├── RemoteControlScreen.kt      (TV remote control UI: D-pad, touchpad, player controls)
│   ├── SessionObserverSetup.kt     (session observer + GeckoSession delegate proxies)
│   ├── SettingsScreen.kt           (browser settings: inbuilt extension visibility)
│   ├── StreamPickerSheet.kt        (bottom sheet for resolved Stremio streams)
│   ├── TabManager.kt               (tab/session lifecycle, find-in-page helpers)
│   ├── TabsScreen.kt               (tab management overlay)
│   └── VideoDetector.kt            (video content type detection via request interception)
├── connection/             # WebSocket client + service discovery
│   ├── ConnectionStore.kt          (DataStore persistence for connection history)
│   ├── NsdHelper.kt                (Network Service Discovery to find TV services)
│   └── WebSocketClient.kt          (OkHttp-based client with auto-retry)
├── data/                   # Local data persistence
│   ├── debrid/
│   │   ├── AllDebridClient.kt       (All-Debrid API client implementation)
│   │   ├── DebridModels.kt          (Data models for torrents, files, links)
│   │   ├── DebridProvider.kt        (Abstract interface for Debrid services)
│   │   ├── DebridRepository.kt      (Factory and active provider configuration)
│   │   ├── PremiumizeClient.kt      (Premiumize API client implementation)
│   │   └── RealDebridClient.kt      (Real-Debrid API client implementation)
│   ├── history/
│   │   ├── BookmarkDao.kt           (Room DAO for bookmarks CRUD)
│   │   ├── BookmarkEntity.kt        (Bookmark entry data class: url, title, timestamp)
│   │   ├── DatabaseProvider.kt      (Room database singleton provider)
│   │   ├── HistoryDao.kt            (Room DAO for browsing history CRUD)
│   │   ├── HistoryDatabase.kt       (Room database with history, bookmarks, tabs, addons)
│   │   ├── HistoryEntity.kt         (History entry data class: url, title, timestamp)
│   │   ├── TabDao.kt                (Room DAO for tab persistence)
│   │   └── TabEntity.kt             (Tab state data class: id, url, title, parentId, isSelected)
│   └── library/
│       ├── AddonDao.kt              (Room DAO for installed Stremio addons)
│       ├── AddonModels.kt           (Stremio manifest and stream models)
│       ├── AddonRepository.kt       (Stremio addon config and stream resolution)
│       ├── TmdbModels.kt            (TMDB API data models)
│       └── TmdbRepository.kt        (TMDB API client for movies/TV shows)
├── model/                  # App-specific models
│   ├── Message.kt                   (QRCodeData + parseQRCode — phone-only)
│   └── TvDevice.kt                  (TV device connection info)
└── ui/                     # Compose UI screens
    ├── ConnectionScreen.kt          (NSD discovery + QR scan + manual IP + PIN auth)
    ├── HomeScreen.kt                (main home with device connection)
    └── theme/
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
| Library Details | LibraryDetailScreen.kt | Movie/TV Details with stream resolution integration |
| Addon Manager | AddonRepository.kt | Installs Stremio addons and resolves video streams via IMDB ID |
| TMDB Client | TmdbRepository.kt | Coroutine-based client for TMDB API v3 |
| Stream Selection | StreamPickerSheet.kt | Bottom sheet displaying resolved Debrid/Addon streams |
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
| Downloads | DownloadsScreen.kt | Download list UI (system + HLS/ExoPlayer downloads) |
| Download Utils | DownloadUtils.kt | Download helper: standard files via DownloadManager, HLS via ExoPlayer DownloadService |
| HLS Download Manager | DownloadManagerSingleton.kt | Media3 ExoPlayer download manager singleton for HLS offline downloads |
| Media Download Service | MediaDownloadService.kt | Foreground service for HLS media downloads with notifications |
| Browser Settings | SettingsScreen.kt | Browser settings (e.g., toggle inbuilt extension visibility) |
| Browser History | HistoryScreen.kt | Browsing history list with clear functionality |
| Bookmarks | BookmarksScreen.kt | Bookmarks list UI with add/remove |
| Home Page | HomeScreen.kt | Browser home page with bookmarks display |
| Database | DatabaseProvider.kt | Room database singleton with History, Bookmark, and Tab DAOs |
| Tab Persistence | TabDao.kt | Room DAO for saving/restoring tab state across sessions |
| Find on Page | FindOnPageBar.kt | UI for finding text within web pages |
| WebSocket | WebSocketClient.kt | OkHttp-based client with auto-retry (60 attempts, 5s intervals) |
| Connection | ConnectionScreen.kt | NSD auto-discovery, QR scanning, manual IP entry, PIN authentication |
| Service Discovery | NsdHelper.kt | Network Service Discovery to find TV services on local network |
| Embedded Extension | `assets/extensions/video_detector` | Legacy internal extension bundled with the phone app for video detection in GeckoView |

## Dependencies
- **GeckoView** (Mozilla) v147 — Full Firefox engine
- **Mozilla Android Components** v147 — Tabs, toolbar, extensions, sessions, prompts support
- **OkHttp** v4.12 — WebSocket client
- **CameraX** v1.4 + **ML Kit Barcode** v17.3 — QR code scanning
- **Jetpack Compose** — UI (Material3)
- **Kotlin Serialization** v1.7 — JSON protocol
- **DataStore** v1.1 — Preferences persistence
- **Room** — SQLite persistence for browsing history
- **Media3 ExoPlayer** — HLS offline download support
- **Coil** — Image loading
