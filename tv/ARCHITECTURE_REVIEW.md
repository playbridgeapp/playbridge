# TV Player App — Architecture Review

**Date:** 2026-04-20
**Scope:** `tv/player/` Android module (primary), with iOS (`tv/apple-tv/`) cross-reference
**Reference version:** `tv/player/app/build.gradle.kts` versionCode 39 / versionName 0.1.39
**Methodology:** Static read of every file in `tv/player/app/src/main/`, compared against current Android Architecture Guidelines, Media3 guidance, and Kotlin Multiplatform (KMP) maturity as of 2026.

---

## TL;DR

The TV player app works, but its architecture predates most of the patterns that are now standard in the Kotlin/Android ecosystem. The whole player module has:

- **Zero `ViewModel`s** — state lives in 1500-to-2000-line `Activity` god classes
- **Zero automated tests** — no `src/test/` or `src/androidTest/` at all
- **Three near-duplicate player Activities** (`ExoPlayerActivity` 2077 LOC, `VlcPlayerActivity` 1656 LOC, `MpvPlayerActivity` 1487 LOC) that could collapse to one `PlayerActivity` + three `PlaybackEngine` strategies
- **Global mutable singletons via `companion object`** (`PlaylistStore.currentPlaylist`, static `StateFlow`s on `ServerService`) used as a backchannel between Activities and the foreground service
- **Manual DI by direct instantiation** — no Hilt / Koin / constructor graph, so components can't be swapped in tests
- **`SharedPreferences` + `DataStore` coexisting** in 11 files — inconsistent and blocking the main thread in places
- **`BroadcastReceiver` used as an RPC bus** between `ServerService` and the player Activities (`ACTION_CONTROL`, `ACTION_REMOTE`, `ACTION_PLAY`, …) — brittle, not testable, and entirely bypassable by the typed `Command` flow you already have

The `tv/apple-tv/` SwiftUI app you've already started is, architecturally, the version the Android app should aspire to — it has an `@Observable PlayerViewModel`, a `PlaybackEngine` protocol with `AVPlayerEngine` / `VLCPlayerEngine` implementations, and a 500-line `WebSocketServer` that is half the size of Android's equivalent. You've effectively already written the refactor target; now it's about bringing Android up to meet it.

The top three things to fix, in order of leverage:

1. **Introduce `PlayerViewModel` + a `PlaybackEngine` strategy interface** — mirror the Swift side. This collapses ~5,200 lines of Activity code into ~1,500 and unblocks every other improvement.
2. **Replace `BroadcastReceiver`-based Service↔Activity RPC with a shared `Flow<PlayerCommand>`** exposed from a bound service (or injected via DI). The typed `Command` hierarchy in the `protocol` module is already the right shape.
3. **Lift the shared modules (`protocol`, `stremio`, quality/source ranking, resume state) into a Kotlin Multiplatform `shared` module.** You're already maintaining two protocol implementations (Kotlin + Swift) that have to stay in sync manually — that's the exact pain KMP was built to remove.

The rest of this document goes file-by-file.

---

## 1. State management & the "God Activity" problem

### What the code does today

`ExoPlayerActivity` is 2077 lines and directly owns:

- `ExoPlayer` lifecycle, track selection, retry counters (`audioDiscontinuityRetryCount`, `videoDecoderRetryCount`, `malformedContentRetryCount`, `stuckBufferRetryCount`, `networkErrorRetryCount` — all `Int` fields at `ExoPlayerActivity.kt:84-88`)
- Pre-play state (`prePlayPayload`, `isPrePlayLaunching`, `prePlayCountdown`, `isPreBuffering`, `resolutionJob`, `launchJob` at `ExoPlayerActivity.kt:95-101`)
- Playlist queue (`playlistItems`, `playlistIndex` at `ExoPlayerActivity.kt:115-116`)
- Six separate manager instances (`contentSniffer`, `controlsManager`, `videoFilterManager`, `progressManager`, `inputHandler`, `subtitleManager` at `ExoPlayerActivity.kt:104-111`)
- A `BroadcastReceiver` (`controlReceiver` at `ExoPlayerActivity.kt:120-171`) that routes 5 different action types by `when` on strings
- Loop state (`isLooping` at `ExoPlayerActivity.kt:92`)
- Active dialog reference (`activeDialog` at `ExoPlayerActivity.kt:118`)

`VlcPlayerActivity` (1656 LOC) and `MpvPlayerActivity` (1487 LOC) duplicate ~80 % of this same structure with different player libraries underneath. The shared abstract parent `PlayerActivity.kt` only pulls out buffer-config computation, series-navigator setup, and the player-switch dialog — so every new feature has to be written three times.

Why this matters beyond line count: because state lives on the Activity, it dies on config changes and on process death. Media3's `setPlayer` semantics can tolerate config changes if you save/restore, but retry counters, pre-play resolution state, playlist position, and current filter all get wiped. The `ExoPlayerActivity` compensates by declaring `android:configChanges="orientation|screenSize|screenLayout|smallestScreenSize"` in the manifest (`AndroidManifest.xml:89`), which is a workaround, not a fix — it breaks on locale changes, dark-mode flips, font-size changes, and Android 14's predictive back.

### What the Swift side already has

`tv/apple-tv/PlayBridge TV/PlayBridge TV/PlayerViewModel.swift` is 253 lines, fully state-driven, and uses the same `PlayBridgeProtocol` messages. The Android side should look like this:

```kotlin
class PlayerViewModel(
    private val engineFactory: PlaybackEngineFactory,  // picks ExoPlayer / VLC / MPV
    private val progressStore: ProgressStore,          // already exists as HistoryStore
    private val subtitleLoader: SubtitleLoader,        // extract from SubtitleManager
    private val serverCommands: Flow<Command>,         // injected — replaces BroadcastReceiver
) : ViewModel() {
    private val _state = MutableStateFlow(PlayerUiState.Idle)
    val state: StateFlow<PlayerUiState> = _state.asStateFlow()
    // ...
}
```

The Activity becomes a thin shell that calls `setContent {}`, hoists the `PlayerView` via `AndroidView`, and forwards key events. You already use `ComponentActivity` (`PlayerActivity.kt:16`), so this is a compatible migration — no `FragmentActivity` or Leanback-era scaffolding is in the way.

### Recommendation

1. Introduce `PlayerViewModel : ViewModel()` per player family (one is enough — the engine is a dependency, not a subclass).
2. Define a `PlaybackEngine` interface with the same surface as the Swift `PlaybackEngine` protocol (`tv/apple-tv/PlayBridge TV/PlayBridge TV/PlaybackEngine.swift:17-67`). Three implementations: `ExoPlaybackEngine`, `VlcPlaybackEngine`, `MpvPlaybackEngine`.
3. Collapse `ExoPlayerActivity` / `VlcPlayerActivity` / `MpvPlayerActivity` into a single `PlayerActivity` that takes an engine ID via intent extra and constructs the right engine in the ViewModel.
4. Promote all `mutableStateOf`-in-Activity fields into a single `PlayerUiState` data class exposed as `StateFlow`.

**Estimated payoff:** ~5,200 Activity lines → ~1,500 lines total, testability goes from ~0 % to ~70 % of non-view logic, and every engine-specific bug (e.g. VLC subtitle chain, MPV `$HOME` override) stops requiring a parallel patch in the other two files.

---

## 2. Service ↔ Activity communication

### What the code does today

`ServerService` receives a `Command` (typed, sealed, well-modeled in `protocol/src/main/kotlin/com/playbridge/protocol/Message.kt`). It then:

- For `Command.Play`: starts an Activity via `startActivity(...)` with ~14 string `EXTRA_*` keys (`ServerService.kt:422-470`)
- For `Command.Control` / `Command.Remote` / `Command.QueueAdd` / `Command.PlaylistJump`: broadcasts an `Intent` back to the running Activity
- The Activity registers a `BroadcastReceiver` (`ExoPlayerActivity.kt:120-171`) and re-parses each action into a `when` on strings

That's a *second* serialization layer inside the same process. You pay the cost of serializing typed data to `Intent` extras, then deserializing back, and lose all compile-time guarantees in between. It's also why `PlaylistStore.currentPlaylist` (a `@Volatile` static `var` at `PlaylistStore.kt:5-7`) exists — Intent extras can't carry a `List<PlayPayload>` so there's a static field used as an out-of-band channel (`MainActivity.kt:233, 237, 240`, `ServerService.kt:310`).

The `ServerService` also exposes global static state via `companion object`:

- `ServerService.connectionState` and `ServerService.connectedClientCount` as static `StateFlow`s (referenced in `MainActivity.kt:137-138`)
- `ServerService.drainPendingQueueItems()` (called at `ExoPlayerActivity.kt:155, 333`)
- `ServerService.notifyContextPlayer()`, `notifyContextIdle()`, `notifyContextBrowser()` (`PlayerActivity.kt:125, 137`, `ExoPlayerActivity.kt:211`)

Static state on a `Service` companion is a **process-wide mutable singleton.** When the service is killed and restarted, those `StateFlow`s get re-created and subscribers in an already-running Activity are silently detached. This is the real reason the app needs the "overlay window to exempt BAL restrictions" workaround (`OverlayWindowHelper`, `ServerService.kt:216-222`): the communication model assumes the service and its subscribers live as one, but on Android 14+ they don't.

### Recommendation

1. **Bind the service.** `ComponentActivity` can `bindService()` in `onStart` and get back a typed `Binder` exposing `Flow<Command>` and playback-status `Flow<PlaybackStatus>`. The `BroadcastReceiver` machinery goes away entirely.
2. **Replace `PlaylistStore.currentPlaylist` with a `Flow<List<PlayPayload>>`** on the same binder. No more static `var`.
3. **Make `ServerService.connectionState` non-static.** Inject `ConnectionStateHolder` (a tiny class holding the `StateFlow`) via DI; have both the service and the UI observe the same instance. Static state on a `Service` companion is technically legal but any crash-restart of the service creates orphan subscribers.
4. Once #1 is done, the Activity no longer needs `overlayWindowHelper` for any reason but the "wake the TV to pairing screen" case — the current general-purpose BAL bypass (`ServerService.kt:212-222, 286`) can be scoped down.

The cleanup is meaningful for the Play Store submission because Google's reviewers have historically flagged `SYSTEM_ALERT_WINDOW` usage on TV where it isn't strictly necessary.

---

## 3. Dependency Injection

### What the code does today

There is none. Components are built with direct constructor calls wherever they're needed:

- `PairingStore(applicationContext)` is instantiated in `MainActivity` (`MainActivity.kt:50`), in `ServerService.onCreate()` (`ServerService.kt:74`), and again inside `ServerService.registerNsdService()` (`ServerService.kt:113`). That's three separately-initialized copies reading/writing the same DataStore — a race the Android DataStore library explicitly warns against ("only one DataStore per file").
- `HistoryStore(applicationContext)` is instantiated in `MainActivity` (`MainActivity.kt:51`) and again inside `ExoPlayerActivity.onCreate` (`ExoPlayerActivity.kt:218`). Same problem.
- `ContentSniffer()` is instantiated per-call inside `SubtitleManager.downloadUrl` (`SubtitleManager.kt:112`) and again in `SubtitleManager.getPreview` (`SubtitleManager.kt:220`). Each instantiation builds a fresh `OkHttpClient` — this is the single most-expensive object in the app.
- `StremioClient` has its own static `init(applicationContext)` called from `ServerService.onCreate` (`ServerService.kt:79`).

The common pattern is *"new it where I need it, and hope the initialization order works out."* When it doesn't, the symptoms are subtle: stale settings, doubled network traffic, torn DataStore writes.

### Recommendation

Add **Hilt** (or Koin — either is fine, Hilt is more mainstream and the Android docs default to it). Create singleton bindings for:

- `PairingStore`
- `HistoryStore`
- `OkHttpClient` (a shared one; inject into `ContentSniffer`, `SubtitleManager`, `StremioClient`, Ktor `CIO` engine via `HttpClient(OkHttp)`)
- `Json` (the kotlinx.serialization instance — you already have one as `protocolJson`)
- `ProgressStore`, `PlaylistStore`, `ConnectionStateHolder`
- `PlaybackEngineFactory`

Scope `PlayerViewModel` with `@HiltViewModel`.

Manual DI (a single `AppContainer` as in Now-in-Android) is also acceptable and matches how `Components.kt` works on the phone side. The *important* thing is that there's one graph instead of three copies of everything.

---

## 4. Persistence inconsistency

### What the code does today

`DataStore<Preferences>` is used for pairing, server port, device name (`PairingStore.kt:1-166`). Good.

`SharedPreferences` is used for player-mode selection and preferred-IP in 11 files:

- `MainActivity.kt:211` — reads `player_mode` from `browser_prefs` on the main thread inside the intent-launch lambda
- `ServerService.kt:114, 312` — same
- `ExoPlayerActivity.kt`, `VlcPlayerActivity.kt`, `MpvPlayerActivity.kt`, `StreamSelectionDialog.kt`, `PrePlayScreen.kt`, `SettingsScreen.kt`, `PrePlayActivity.kt`, `StremioClient.kt`, `Theme.kt`

The prefs file is called `"browser_prefs"` — a leftover name from when the browser lived in the same APK.

### Recommendation

Migrate everything to DataStore. `SharedPreferences.edit { }` has an implicit `apply()` that writes on a background thread, but `getString` etc. read synchronously. On low-memory Hisense TVs (the target hardware mentioned in `PlayerActivity.kt:151`) this can block the main thread during cold-launch. `androidx.datastore.preferences` with a shared module-level `DataStore` instance makes all reads suspending and removes the landmine.

One `UserSettings` class with properties typed as `Flow<String>`, `Flow<Boolean>`, `Flow<Int>`, injected via Hilt, replaces all 11 call-sites cleanly.

---

## 5. The `BroadcastReceiver` RPC pattern (already in §2, details here)

Concrete state of the broadcast surface:

- `ServerService.ACTION_PLAY`, `ACTION_CONTROL`, `ACTION_REMOTE`, `ACTION_QUEUE_ADD`, `ACTION_PLAYLIST_JUMP`, `ACTION_CONTEXT_IDLE`, `ACTION_OPEN_PAIRING` — 7 action strings
- `EXTRA_URL`, `EXTRA_TITLE`, `EXTRA_CONTENT_TYPE`, `EXTRA_HEADERS`, `EXTRA_DETECTED_BY`, `EXTRA_SUBTITLES`, `EXTRA_COMMAND`, `EXTRA_REMOTE_KEY`, `EXTRA_IS_PLAYLIST`, `EXTRA_PLAYLIST_INDEX`, `EXTRA_PLAYLIST_JUMP_INDEX`, `EXTRA_MAX_BITRATE_CAP_MBPS`, `EXTRA_SERIES_CONTEXT`, `EXTRA_CONTENT_PAYLOAD`, `EXTRA_PREFERRED_AUDIO_LANG`, `EXTRA_PREFERRED_SUBTITLE_LANG`, `EXTRA_EXTERNAL_SUBTITLE_URL`, `EXTRA_VIDEO_FILTER`, `EXTRA_CUSTOM_FILTER_VALUES` — 19 extras

Every one of these is a string→Any map lookup that silently returns `null` if you fat-finger a key. You already have typed replacements: `Command.Play`, `Command.Control`, `Command.Remote`, `PlayPayload`, `ContentPlayPayload`, `SeriesContext` — all `@Serializable` in the `protocol` module. The broadcast layer is translating typed data to stringly-typed Intent bags and back. A bound-service `Flow<Command>` would eliminate this entirely.

---

## 6. The manager pattern: View-bound, not testable

`PlayerControlsManager` (`PlayerControlsManager.kt:22-50`) takes **18 `View` references**, a `playerProvider` lambda, and **8 callback lambdas** in its constructor. It cannot be unit-tested because every field is a `View`. Same pattern in `MpvControlsManager` (306 LOC) and `VlcControlsManager` (359 LOC) — three near-duplicate manager classes for the same overlay.

### Recommendation

Flip the dependency: the manager exposes `StateFlow<ControlsUiState>`, the Compose overlay observes it. The layout XML (`activity_player.xml`, `activity_vlc_player.xml`, `activity_mpv_player.xml`) goes away — it's all Compose, which is what `PrePlayScreen.kt` (695 LOC of pure Compose) already is. You're mixing Compose and XML in the same screen (`ExoPlayerActivity.kt:192` sets `R.layout.activity_player`, then embeds a `ComposeView` at line 194-216), which is the worst of both worlds: no Compose preview for the XML parts, no View-system simplicity for the Compose parts.

Moving the overlay to pure Compose lets you:
- Delete `activity_player.xml`, `activity_vlc_player.xml`, `activity_mpv_player.xml`, `PlayerControlsManager.kt`, `MpvControlsManager.kt`, `VlcControlsManager.kt` (1,101 LOC total)
- Replace with one `@Composable PlayerOverlay(state: PlayerUiState, onAction: (PlayerAction) -> Unit)` — probably ~300 LOC, which is consistent with `PrePlayScreen` size
- Get Compose Preview, recomposition-aware updates, and `@Preview` screenshot tests for free

`BufferSeekBar.kt` (72 LOC, custom SeekBar subclass) is the only widget that's genuinely hard to express in Compose — keep it as `AndroidView` inside the Compose overlay.

---

## 7. Network layer

`ContentSniffer.kt` builds an `OkHttpClient` with an **all-trust TrustManager** for SSL bypass. Known Play Store blocker — tracked in memory, patch pending.

Beyond the Play Store issue: there is no shared `OkHttpClient`. `SubtitleManager` instantiates a new `ContentSniffer` per download; `StremioClient` builds its own Ktor client; Media3's `DefaultHttpDataSource` builds another; WebSocket server uses its own CIO engine. Each holds its own connection pool (5 idle × 5 minutes default). On a TV that's 4 × 5 = 20 idle sockets sitting around after a browse session, eating file descriptors.

### Recommendation

Single `OkHttpClient` bean. Media3 gets `OkHttpDataSource.Factory`. Ktor gets `HttpClient(OkHttp)`. `SubtitleManager` and `StremioClient` get the raw `OkHttpClient`. All share one connection pool, one cache, one cookie jar. When the Play-Store SSL fix lands, it only has to land once.

---

## 8. Coroutines

Mostly fine — you use `lifecycleScope` and structured concurrency throughout. A couple of papercuts:

- `ServerService.scope` is correctly cancelled in `onDestroy` (`ServerService.kt:884`). Good.
- `WebSocketServer.scope = CoroutineScope(Dispatchers.IO + SupervisorJob())` (`WebSocketServer.kt:38`) is **not** cancelled — `stop()` uses `runBlocking` to close sessions (`WebSocketServer.kt:157`) but never calls `scope.cancel()`. Each `start()`/`stop()` cycle leaks a SupervisorJob and any in-flight launches (e.g. the subtitle-HTTP handler). Matters less in practice because the server usually lives for the whole service lifetime, but a clean pair is trivial to add.
- `SubtitleManager.startSyncing()` spins a `while (isActive) { delay(200) }` loop on `Dispatchers.Main` (`SubtitleManager.kt:79-85`). That's fine, but polling every 200 ms during playback is wasteful — Media3 exposes `Player.Listener.onPositionDiscontinuity` and `MediaSession` gives you position broadcasts for free. Switching to event-driven updates saves ~18,000 unnecessary dispatches per hour of playback.
- `subtitleManager = SubtitleManager(subtitleTextView, lifecycleScope)` (`ExoPlayerActivity.kt:258`) leaks the TextView into the `SubtitleManager`. Swap to `SubtitleManager` exposing `Flow<String?>` and let the Compose layer render it.

### Recommendation

- In `WebSocketServer.stop()`, call `scope.cancel()` after closing sessions.
- Drive subtitle sync from `Player.Listener` events (or `MediaSession` position callback) instead of a 200 ms poll.

---

## 9. Testing

### What exists

```
tv/player/app/src/main/     ← all code
```

No `src/test/`, no `src/androidTest/`, no `src/sharedTest/`. Zero lines of test code in the player module.

### What should exist

After the ViewModel refactor, you have natural test seams:

1. **Unit tests** (`src/test/`) — plain JVM, `kotlinx-coroutines-test`, `turbine` for Flow assertions:
   - `QualityRanker` / `SourceTypeRanker` — pure functions, easy wins
   - `M3uParser` — pure string-in / list-out, high value because IPTV regressions are silent
   - `HlsParser` (phone-side counterpart) — same
   - `SeriesNavigator` — the "optimistic mode" logic is subtle and has already been touched multiple times
   - `PlayerViewModel` with fake `PlaybackEngine` — state transitions, retry counters, loop/playlist behavior
   - `Command` parsing — `parseCommand(text)` round-trips for every `Command.*` subtype
2. **Instrumentation tests** (`src/androidTest/`) — Espresso + Compose Test Rule:
   - PairingScreen shows the correct PIN
   - Pressing D-pad center on the Library opens a player
   - Back from a dialog returns focus to the right button (TV focus regressions are the single most common bug on Android TV)
3. **Screenshot tests** — Roborazzi or Paparazzi on `PrePlayScreen`, `PlayerOverlay`, `SettingsScreen`. `Theme.kt` changes caused a visual regression on at least one release — screenshot tests catch that automatically.

Even 20 unit tests covering `QualityRanker`, `SourceTypeRanker`, `M3uParser`, and `Command` parsing would catch the most frequent class of bug in this codebase.

---

## 10. Build setup

`tv/player/app/build.gradle.kts` today:

- `isMinifyEnabled = false` for release (`build.gradle.kts:36`) — ProGuard/R8 disabled. Already in your Play Store blockers list; also means your release APK ships every line of Ktor, Coil, ZXing, libvlc stubs, Media3 test helpers, etc.
- `targetSdk = 36` / `compileSdk = 36` — good.
- `sourceCompatibility / targetCompatibility = VERSION_11` (`build.gradle.kts:45-46`) — fine, but Kotlin/JVM target isn't explicitly set. Add `kotlin { jvmToolchain(17) }` so future contributors on JDK 17+ aren't silently downgraded.
- No version catalog sharing: `phone/gradle/libs.versions.toml`, `tv/player/gradle/libs.versions.toml`, `tv/browser/gradle/libs.versions.toml`, `mpvEx/gradle/libs.versions.toml` are four separate catalogs. When Media3 bumps from 1.9.2 → 1.10, you update it in four places and they drift.
- No KSP — kotlinx-serialization uses a Gradle plugin instead, which is fine. But if you adopt Hilt or Room generation, wire KSP now.
- No baseline profile. Media3 + Compose + GeckoView/WebView startup on low-end Hisense hardware is the slowest path in the app. A baseline profile typically cuts first-frame-to-video by 20-30 %.

### Recommendation

1. Promote `libs.versions.toml` to the repo root so all four modules share one catalog. Gradle 9+ supports this natively via `dependencyResolutionManagement { versionCatalogs { create("libs") { from(files("gradle/libs.versions.toml")) } } }` in `settings.gradle.kts`.
2. Enable R8: `isMinifyEnabled = true`, `isShrinkResources = true`. Start with the default `proguard-android-optimize.txt` and add keep rules as crashes surface (Ktor and kotlinx-serialization are the usual ones — well-documented).
3. Add `baselineprofile` module via `androidx.baselineprofile` plugin. Generate a profile that covers cold start → Library → start playback.
4. Delete the duplicate catalogs once #1 lands.

---

## 11. iOS: KMP vs native Swift — specific recommendation for PlayBridge

You already have `tv/apple-tv/` with:
- `PlayBridgeProtocol` Swift Package — a hand-ported copy of the `protocol/` Kotlin module, 4 files
- `PlayBridge TV` Xcode project with `TVVLCKit` for VLC playback — 22 files, ~3,766 LOC total

The Kotlin `protocol/` module and the Swift `PlayBridgeProtocol` module **must encode/decode the same JSON wire format.** Today that's maintained by hand across two languages. Any new `Command.*` case or new `PlayPayload` field has to land twice. This is exactly the problem KMP solves.

### The spectrum

| Option | Shared code | UI | Verdict for you |
|---|---|---|---|
| **Full native (current)** | None | SwiftUI + Compose | Maximum iOS polish, maximum duplication. Two protocol impls that silently drift. |
| **KMP — shared protocol only** | `protocol/` as KMP module emitting Kotlin/Native → ObjC framework, consumed by Swift | SwiftUI + Compose (unchanged) | **Recommended.** Smallest blast radius. Kills the protocol-drift problem. |
| **KMP — shared protocol + domain** | Above + `stremio/`, `QualityRanker`, `SourceTypeRanker`, `SeriesNavigator`, `ResumeStore`, `ContentSniffer` URL-classification logic | SwiftUI + Compose | Strong pick if you're willing to adopt Ktor-client + kotlinx-datetime on iOS. Adds meaningful CI discipline. |
| **KMP — full, incl. UI (Compose Multiplatform)** | Above + the player screen as `@Composable` on both platforms | Compose Multiplatform (with native interop for `AVPlayer` / `ExoPlayer`) | **Not recommended here.** TV UX on tvOS (focus engine, `TVUIKit`) and Android TV (D-pad, leanback focus) diverge enough that shared UI will fight both platforms. Your video surface is platform-specific either way. |

### Why "shared protocol only" is the right first step

Three reasons:

1. **You're already paying twice** — every `Message.kt` change requires a matching Swift edit. See `tv/apple-tv/PlayBridgeProtocol/Sources/PlayBridgeProtocol/Messages.swift`. KMP makes this one source file in Kotlin that ships an `.xcframework` consumed transparently by Swift.
2. **Zero UI impact** — both apps keep their native UI stacks. No learning curve for the iOS side beyond `import PlayBridgeProtocol` continuing to work.
3. **Production-proven in 2026** — KMP is GA-stable. Kotlin 2.2+ has direct Swift export (still experimental) so even the ObjC bridging header won't be your problem for much longer.

### Why "shared domain" is the next step, not the first

`QualityRanker`, `SourceTypeRanker`, `SeriesNavigator`, and `ResumeStore` already exist on both sides with identical intent. They're pure data transformations — ideal KMP candidates. But they depend on:
- `OkHttp` (Android) vs `URLSession` (iOS) — solved by `HttpClient` from `ktor-client`
- `DataStore` (Android) vs `UserDefaults` (iOS) — solved by `multiplatform-settings`

Moving them cross-platform means adopting `ktor-client` and `multiplatform-settings` in the Android app. That's reasonable, but it touches more surface area, so sequence it *after* the protocol move.

### Why full Compose Multiplatform is not right for this app

Android TV's D-pad focus model (which you've clearly spent time tuning — `PlayerControlsManager.kt` focus handling, `InputHandler.kt:handleRemoteCommand`) and tvOS's focus engine (`UIFocusEnvironment`, `TVUIKit.FocusGuide`) are fundamentally different. Compose Multiplatform renders UI correctly on iOS but does **not** hook into tvOS focus — you'd end up shipping a worse-feeling Apple TV app than the Swift one you've already built. The video surface (`AVPlayerLayer` vs Android `SurfaceView`) is also platform-specific — both worlds already diverge at the player layer.

### Concrete migration path (if you take the protocol-only option)

1. Add a new `:protocol-kmp` module alongside `:protocol`. Targets: `jvm()` (for Android + phone + browser), `iosArm64()`, `iosSimulatorArm64()`, `iosX64()`, `tvosArm64()`, `tvosSimulatorArm64()`.
2. Move `Message.kt`, `Config.kt`, `BluetoothConstants.kt` to `commonMain`.
3. Keep `NsdConstants` in `jvmMain` (it's Android-only anyway).
4. In `tv/apple-tv/`, replace the hand-ported `PlayBridgeProtocol` Swift Package with an `.xcframework` produced by the KMP module via `XcodeFramework` task.
5. Delete `tv/apple-tv/PlayBridgeProtocol/` — one source of truth.

Realistic effort: ~1 week of wall time, most of which is Gradle and Xcode plumbing, not code.

---

## 12. Smaller but worth fixing

### Concurrency / correctness

- `PlaylistStore.currentPlaylist` is a plain `var` on a Kotlin `object` (`PlaylistStore.kt:5-7`) — not even `@Volatile`. Should be a `StateFlow` on the (future) bound service. Current impl is a race if two activities touch it across a config change.
- `ExoPlayerActivity.stuckBufferHandler = Handler(Looper.getMainLooper())` (`ExoPlayerActivity.kt:89`) — prefer `viewModelScope.launch { delay(…) }` once the ViewModel exists. Fewer lifecycle gotchas.
- `MainActivity.kt:134-145` relies on `LaunchedEffect(pairedDevices)` with a local `isInitialCheckDone` flag to avoid re-triggering. Normal pattern would be to collect `pairingStore.pairedDevices` once via `collectAsStateWithLifecycle` and gate on `firstOrNull()` inside a `LaunchedEffect(Unit)`. The current code works but is hard to reason about.

### API surface hygiene

- `getLocalIpAddress(context)` is defined at `MainActivity.kt:269` (private top-level) and again inside `ServerService` — same logic, two copies. Move to a `Network.kt` utility.
- `network_security_config.xml` — Play Store blocker already known, keep on the list.
- `largeHeap="true"` in `AndroidManifest.xml:68` — Play Store blocker. Once R8 is enabled and Media3 state is ViewModel-scoped, the real heap requirement should fit comfortably under the default 192 MB / 256 MB budget on most TV hardware.

### Threading

- `WebSocketServer.stop()` uses `runBlocking { ... }` (`WebSocketServer.kt:156-170`) on whatever thread called it. If called from the main thread (it isn't today, but nothing prevents it) this will ANR. Make it `suspend fun stop()` or dispatch via `scope.launch`.

### Logging

- `FileLogger` is a static object (`FileLogger.init(this)` in `PlayBridgeApplication.kt:31`). Works, but it holds a `Context` reference (implicitly via its internal file handling). Should take `AppContext` via DI and be scoped as a singleton bean. Easy win after Hilt lands.

---

## Prioritized action plan

### Phase 1 — unblock iOS parity (1-2 weeks)
1. **Add `:protocol-kmp`**, move protocol into `commonMain`, delete hand-ported Swift version. Kills the drift problem you'll face every release.

### Phase 2 — refactor the player (2-3 weeks)
2. **Introduce `PlayerViewModel` + `PlaybackEngine` interface**, mirroring the Swift `PlaybackEngine` protocol in `tv/apple-tv/PlayBridge TV/PlayBridge TV/PlaybackEngine.swift`.
3. **Collapse `ExoPlayerActivity` / `VlcPlayerActivity` / `MpvPlayerActivity`** into one `PlayerActivity` + three engine implementations. Delete the duplicated `*ControlsManager` classes, fold into one Compose overlay.
4. **Add Hilt** (or a single `AppContainer`). One `PairingStore`, one `HistoryStore`, one `OkHttpClient`.

### Phase 3 — kill the broadcast RPC (1 week)
5. **Bind `ServerService`**, expose `Flow<Command>` via `Binder`. Remove `BroadcastReceiver`s and all `EXTRA_*` string keys used for intra-process messaging (keep the ones used for cold-launch Intent handoff).
6. **Remove static `StateFlow`s** on `ServerService` companion.

### Phase 4 — build & Play Store (1 week, can run in parallel)
7. Shared root `libs.versions.toml`.
8. Enable R8 + baseline profile.
9. Apply pending Play Store blockers patch (ContentSniffer scope, network_security_config, permissions, AAB).

### Phase 5 — tests (ongoing, start during Phase 2)
10. Unit tests for `QualityRanker`, `SourceTypeRanker`, `M3uParser`, `Command` round-trip, `SeriesNavigator`.
11. Compose preview + Paparazzi screenshot tests for `PlayerOverlay`, `PairingScreen`, `PrePlayScreen`.

---

## What is already good

Not a critique-only document. Worth calling out:

- The `protocol` module design — `sealed class Command` with `@Serializable` payloads — is genuinely correct and is why the iOS port was feasible at all.
- `PlayerActivity.BufferConfig` (`PlayerActivity.kt:163-187`) with memory-tier-based tuning is the right shape of logic to have: hardware-aware, documented, and narrowly scoped. Hisense-low-memory regressions will stay solved.
- `VideoFilterManager` (`VideoFilterManager.kt`) is the rare place in the codebase where concerns are cleanly separated — it owns the GlEffect lifecycle and nothing else. Use it as the template when refactoring the others.
- `PairingStore` (`PairingStore.kt:1-166`) is correctly DataStore-based and exposes suspend/Flow accessors. The rest of the persistence layer should look like this.
- Connection-state modeling in `WebSocketServer.ConnectionState` (sealed, five cases, `data object`s for the stateless ones) is idiomatic.

The bones are fine. The problem is that the player and service layers were written ~2020-era and the rest of the ecosystem has moved.
