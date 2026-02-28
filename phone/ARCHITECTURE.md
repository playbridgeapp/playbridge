# Phone App Architecture

## Package Structure
```
com.playbridge.sender/
├── browser/                # GeckoView browser, video detection, extensions, downloads
│   ├── AddonInstallDialog.kt       (extension install confirmation dialog)
│   ├── BookmarksScreen.kt          (bookmarks list UI with add/remove)
│   ├── BrowserActivity.kt          (main browser activity, ~1330 lines)
│   ├── BrowserToolbar.kt           (custom Compose toolbar with URL bar, navigation, SSL lock, menu)
│   ├── Components.kt               (singleton DI container for GeckoRuntime, BrowserStore)
│   ├── DetectedVideosSheet.kt      (bottom sheet for detected videos + quality selection)
│   ├── DownloadConfirmDialog.kt    (download confirmation dialog + PendingDownload model)
│   ├── DownloadManagerSingleton.kt  (Media3 ExoPlayer download manager for HLS)
│   ├── DownloadsScreen.kt          (downloads list UI with progress tracking)
│   ├── DownloadUtils.kt            (download helpers: enqueue, file size, error strings)
│   ├── ExtensionsScreen.kt         (addon management screen)
│   ├── FindOnPageBar.kt            (UI for in-page text search)
│   ├── HistoryScreen.kt            (browsing history list UI)
│   ├── HlsParser.kt               (HLS manifest parsing for quality selection with audio tracks)
│   ├── HomeScreen.kt               (browser home page with bookmarks)
│   ├── LinkContextMenu.kt          (long-press link context menu dialog)
│   ├── MediaDownloadService.kt     (foreground service for HLS/media downloads)
│   ├── RemoteControlScreen.kt      (TV remote control UI: D-pad, touchpad, player controls)
│   ├── SessionObserverSetup.kt     (session observer + GeckoSession delegate proxies)
│   ├── SettingsScreen.kt           (browser settings: inbuilt extension visibility)
│   ├── TabManager.kt               (tab/session lifecycle, find-in-page helpers)
│   ├── TabsScreen.kt               (tab management overlay)
│   └── VideoDetector.kt            (video content type detection via request interception)
├── connection/             # WebSocket client + service discovery
│   ├── ConnectionStore.kt          (DataStore persistence for connection history)
│   ├── NsdHelper.kt                (Network Service Discovery to find TV services)
│   └── WebSocketClient.kt          (OkHttp-based client with auto-retry)
├── data/                   # Local data persistence
│   └── history/
│       ├── BookmarkDao.kt           (Room DAO for bookmarks CRUD)
│       ├── BookmarkEntity.kt        (Bookmark entry data class: url, title, timestamp)
│       ├── DatabaseProvider.kt      (Room database singleton provider)
│       ├── HistoryDao.kt            (Room DAO for browsing history CRUD)
│       ├── HistoryDatabase.kt       (Room database definition with history, bookmarks, tabs)
│       ├── HistoryEntity.kt         (History entry data class: url, title, timestamp)
│       ├── TabDao.kt                (Room DAO for tab persistence)
│       └── TabEntity.kt             (Tab state data class: id, url, title, parentId, isSelected)
├── model/                  # App-specific models
│   ├── Message.kt                   (QRCodeData + parseQRCode — phone-only)
│   └── TvDevice.kt                  (TV device connection info)
└── ui/                     # Compose UI screens
    ├── ConnectionScreen.kt          (NSD discovery + QR scan + manual IP + PIN auth)
    ├── HomeScreen.kt                (main home with device connection)
    └── theme/
```

## Key Components

| Component | File | Purpose |
|-----------|------|---------|
| Browser Engine | Components.kt | Singleton DI container for GeckoRuntime, BrowserStore, AddonManager |
| Browser UI | BrowserActivity.kt | Main browser activity (~1330 lines) with UI composition, screen routing, toolbar, dropdown menu, tab persistence |
| Browser Toolbar | BrowserToolbar.kt | Custom Compose toolbar with navigation, URL bar (full-width on edit), SSL lock indicator, desktop mode toggle, menu |
| Tab Management | TabManager.kt | Tab/session lifecycle, session sync, find-in-page helpers |
| Tab UI | TabsScreen.kt | Tab list/grid overlay with thumbnails |
| Session Observer | SessionObserverSetup.kt | EngineSession.Observer + GeckoSession delegate proxies, desktop mode user-agent switching |
| Extension Management | ExtensionsScreen.kt | Addon installation and management UI |
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
