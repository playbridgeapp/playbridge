# Phone App — Code Smells Review

Scope: `mobile/android/app/src/main/java/com/playbridge/sender`
Date: 2026-05-28

Findings are ordered by impact. File pointers use `path:line`.

## 1. God objects & long methods (highest impact)

The single worst offender:

- **`BrowserActivity.onCreate` is 1,445 lines** (`browser/BrowserActivity.kt:232`). One
  function does engine init, runtime permission requests, a legacy
  SharedPreferences→DataStore migration (`:266-294`), tab restoration, and ~1,100 lines
  of inline Compose UI inside `setContent`. The class itself is 1,588 lines. This should
  be a thin Activity that hosts a navigation composable; the migration belongs in its own
  migrator, and the UI in extracted screen composables.

**Monster composables** — single `@Composable` functions that are effectively whole
screens with embedded state, business logic, and many nested sub-layouts:

| Function | Lines | File |
|---|---|---|
| `LibraryDetailScreen` | 1,053 | `library/LibraryDetailScreen.kt:79` |
| `PlayerScreen` | 958 | `player/PlayerActivity.kt:402` |
| `AppNavHost` | 939 | `browser/AppNavHost.kt:73` |
| `CastSheet` | 864 | `cast/CastSheet.kt:67` |
| `DebridLibraryScreen` | 810 | `library/DebridLibraryScreen.kt:37` |
| `LibraryScreenContent` | 759 | `library/LibraryScreen.kt:157` |
| `SessionObserverSetup` | 676 | `cast/SessionObserverSetup.kt:47` |

A Compose function over ~150 lines is hard to follow and recompose-reason about. These
need decomposition into smaller composables + state hoisting.

**God classes (low cohesion):**

- **`AddonRepository`** (1,105 lines, `data/library/AddonRepository.kt:30`) juggles HTTP,
  JSON parsing, *five* separate in-memory caches (stream/subtitle/catalog/meta/kitsu),
  disk persistence, and stream resolution. That's 4–5 responsibilities in one class.
- **`LibraryViewModel`** (866 lines) exposes **20 separate `MutableStateFlow`s**
  (`library/LibraryViewModel.kt`). A god-ViewModel — should consolidate into one or a few
  `UiState` data classes.

## 2. Mixed/inconsistent persistence

22 files call `getSharedPreferences(...)` directly even though a `SettingsRepository`
backed by DataStore exists. `BrowserActivity` even contains a one-time prefs→DataStore
*migration* (`:266-294`) yet still reads prefs directly in the composable (`:302`). Two
persistence abstractions are live at once; reads bypass the repository.

## 3. Logging smells

- **393 raw `Log.*` calls**, no logging facade.
- **Debug scaffolding left in:** `Log.d("PB_STARTUP", ...)` appears 20× (`BrowserActivity`)
  — clearly temporary startup tracing that should be removed.
- **Inconsistent tags:** same file mixes a companion `TAG` constant with inline string
  literals (`Log.d("BrowserActivity", ...)`, `Log.d("PB_STARTUP", ...)`). Across the app,
  26 `TAG` declarations use four different styles (`private val`, `private const val`,
  top-level `const`), and `"HlsExport"` is duplicated across two files.

## 4. Exception handling

- **134 broad `catch (Exception/Throwable)`** blocks. Most at least log, but **8 are fully
  silent** (`catch (_: Exception) {}`), e.g. `cast/SessionObserverSetup.kt:395,407`,
  `browser/TabManager.kt:388,552`, `library/LibraryViewModel.kt:441` — errors vanish with
  no trace.

## 5. Ad-hoc coroutine scopes & DI inconsistency

- Repositories/objects create their own uncancellable scopes:
  `AddonRepository.ioScope = CoroutineScope(Dispatchers.IO)` (`:62`, no `SupervisorJob`,
  never cancelled), and the global `Components.applicationScope`. Repositories should
  expose `suspend` functions or receive an injected scope rather than owning lifecycle.
- **DI is inconsistent:** most dependencies come via Koin (`by viewModel()`,
  `by inject()`), but `TabManager()` is `new`'d directly in the Activity (`:202`), and
  `Components` is a global singleton service-locator holding the store + app scope.

## 6. Lower-severity

- **55 `!!` force-unwraps**, concentrated in `data/backup/BackupManager.kt` (10),
  `cast/CastSheet.kt` (8), `library/DebridLibraryScreen.kt` (6).
- **Fully-qualified inline types** instead of imports throughout `BrowserActivity`
  (`com.playbridge.sender.browser.BrowserViewModel`, `android.os.Build.VERSION...`,
  `kotlinx.coroutines.Dispatchers.IO`) — hurts readability.

## Suggested priority

1. Carve up `BrowserActivity.onCreate` — extract the UI, the prefs migration, and
   permission handling. Biggest readability/risk win.
2. Split `AddonRepository` (cache layer vs. network/resolution) and collapse
   `LibraryViewModel`'s 20 flows into a `UiState`.
3. Decomposition pass on the 600+ line composables.
4. Quick cleanups: delete `PB_STARTUP` debug logs, fix the 8 silent catches, route
   SharedPreferences reads through `SettingsRepository`.
