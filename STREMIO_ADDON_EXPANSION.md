# Stremio Addon Expansion — Implementation Plan

Expand PlayBridge's Stremio addon support from stream-only to the full four-resource protocol:
**stream** (existing) · **catalog** · **meta** · **subtitles**

Implementation order matters — each phase is a prerequisite for the next.

---

## Phase 1 — Prerequisite: persist addon capabilities

> **Why first:** Every phase below needs to know what each installed addon actually supports.
> Currently `InstalledAddonEntity` only stores `types`; it drops `resources` and `catalogs` from the manifest on install.

### 1.1 Extend `InstalledAddonEntity`
**File:** `phone/app/src/main/java/com/playbridge/sender/data/library/AddonModels.kt`

- [x] Add two new columns to the Room entity:
  ```kotlin
  val resources: String = "",      // JSON array of resource names, e.g. ["stream","catalog","meta","subtitles"]
  val catalogsJson: String = ""    // JSON array of StremioResource objects from manifest.catalogs
  ```
- [x] Add helper extension functions on the entity:
  ```kotlin
  fun InstalledAddonEntity.supportsResource(name: String): Boolean
  fun InstalledAddonEntity.parsedCatalogs(): List<StremioResource>
  ```

### 1.2 Add new data models
**File:** `phone/app/src/main/java/com/playbridge/sender/data/library/AddonModels.kt`

- [x] Add `StremioMetaPreview` — lightweight catalog row item
- [x] Add `StremioMetaDetail` — full metadata for a detail screen
- [x] Add `StremioVideo` — episode entry inside a series meta
- [x] Add `StremioMetaResponse` and `StremioMetasResponse` response wrappers

### 1.3 Write Room database migration
**File:** `phone/app/src/main/java/com/playbridge/sender/data/history/DatabaseProvider.kt`

- [x] Increment the database version from 7 → 8
- [x] Add `MIGRATION_7_8` with both `ALTER TABLE` statements
- [x] Register `MIGRATION_7_8` in `.addMigrations(...)`

### 1.4 Populate new fields on install
**File:** `phone/app/src/main/java/com/playbridge/sender/data/library/AddonRepository.kt` — `installAddon()`

- [x] Serialize `manifest.resources` names to JSON and store in `resources`
- [x] Serialize `manifest.catalogs` to JSON and store in `catalogsJson`
- [x] Add `getAvailableCatalogs(): List<Triple<InstalledAddonEntity, String, String>>`

---

## Phase 2 — Subtitle routing through installed addons

> **Current state:** `StremioSubtitleService` is hardcoded to `opensubtitles-v3.strem.io`.
> After this phase: any installed addon that declares `subtitles` in its resources is queried.

### 2.1 Add subtitle resolution to `AddonRepository`
**File:** `phone/app/src/main/java/com/playbridge/sender/data/library/AddonRepository.kt`

- [x] Add `resolveSubtitles(type: String, id: String): List<StremioStream>`:
  - Filters addons by `supportsResource("subtitles")` AND matching content type
  - Hits `${addon.baseUrl}/subtitles/$type/$id.json` per addon in parallel
  - Parses via `StremioStreamResponse` (handles both `.subtitles` and `.streams` keys)
  - Caches in a dedicated `subtitleCache: ConcurrentHashMap<String, Pair<Long, List<StremioStream>>>` — **not** `streamCache`, since `CacheEntry` is typed to `List<ResolvedStream>` (build error fixed here)

### 2.2 Update `StremioSubtitleService`
**File:** `phone/app/src/main/java/com/playbridge/sender/data/library/StremioSubtitleService.kt`

- [x] Constructor now takes `addonRepository: AddonRepository? = null`
- [x] `getSubtitlesForMovie` and `getSubtitlesForEpisode` fire default OpenSubtitles fetch and addon fetch concurrently via `async/await`
- [x] Results merged via `mergeSubtitles()` — deduplicates by `(url, name)`, addon results prepended

### 2.3 UI wiring
- [x] **`CastSheet.kt`** — `subtitleService: StremioSubtitleService = StremioSubtitleService()` added as parameter; internal `remember { StremioSubtitleService() }` removed
- [x] **`LibraryDetailScreen.kt`** — both `MovieDetailScreen` and `TvShowDetailScreen` now use `remember { StremioSubtitleService(addonRepository) }` (no signature change needed, both already had `addonRepository`)
- [x] **`BrowserActivity.kt`** — service built once at `remember { StremioSubtitleService(addonRepository) }` and passed to `CastSheet` via `subtitleService = subtitleService`

---

## Phase 3 — Catalog browsing from addons

> Lets users browse content that addons curate (e.g. "My Debrid Library", "Top 4K YIFY") without needing a TMDB API key.

### 3.1 Add catalog fetch to `AddonRepository`
**File:** `phone/app/src/main/java/com/playbridge/sender/data/library/AddonRepository.kt`

- [x] Add `fetchCatalog(addon: InstalledAddonEntity, type: String, catalogId: String, skip: Int = 0): List<StremioMetaPreview>`:
  - URL pattern: `${addon.baseUrl}/catalog/$type/$catalogId.json` (no skip) or `/catalog/$type/$catalogId/skip=$skip.json` (paginated)
  - Parse via `StremioMetasResponse`
  - Cache in `catalogCache: ConcurrentHashMap<String, Pair<Long, List<StremioMetaPreview>>>` with 15-minute TTL

### 3.2 Add catalog state to `LibraryViewModel`
**File:** `phone/app/src/main/java/com/playbridge/sender/browser/LibraryViewModel.kt`

- [x] Instantiate `AddonRepository` directly in ViewModel using `DatabaseProvider` (matches non-Hilt architecture)
- [x] Add state: `_availableCatalogs`, `_selectedCatalog`, `_catalogItems`, `_isCatalogLoading`, `_hasMoreCatalogItems`, `catalogSkip`, `catalogGridState`
- [x] Add `loadAvailableCatalogs()` — called from `init`
- [x] Add `selectCatalog(catalog)` — resets skip, fetches first page
- [x] Add `loadMoreCatalogItems()` for pagination (skip increments by 100)

### 3.3 Add catalog tab to `LibraryScreen`
**File:** `phone/app/src/main/java/com/playbridge/sender/browser/LibraryScreen.kt`

- [x] Added 4th tab (Extension icon) to pill row in TopAppBar
- [x] Added `AddonCatalogTab` composable: chip row to select catalog, empty state, `LazyVerticalGrid` with `PosterCard`s and load-more trigger
- [x] Tapping an item calls `onAddonItemClick(id, type)` → navigates to `Screen.AddonDetail`

### 3.4 Wire navigation in `BrowserActivity`
**File:** `phone/app/src/main/java/com/playbridge/sender/browser/BrowserActivity.kt`

- [x] Added `Screen.AddonDetail(id: String, type: String)` to `Screen` sealed class
- [x] Added `is Screen.AddonDetail` case invoking `AddonDetailScreen`
- [x] Added `onAddonItemClick` to `LibraryScreen` call

---

## Phase 4 — Metadata from addons

> Required for addon catalog items that don't have IMDb IDs (e.g. IPTV channels, private libraries). Also useful as a fallback when TMDB has no data.

### 4.1 Add meta fetch to `AddonRepository`
**File:** `phone/app/src/main/java/com/playbridge/sender/data/library/AddonRepository.kt`

- [x] Add `fetchMeta(type: String, id: String): StremioMetaDetail?`:
  - Filters installed addons by `supportsResource("meta")` AND matching content type
  - Tries each addon in order, hits `${addon.baseUrl}/meta/$type/$id.json`, parses via `StremioMetaResponse`
  - Returns first non-null result; caches in `metaCache: ConcurrentHashMap<String, Pair<Long, StremioMetaDetail>>` with 60-minute TTL

### 4.2 `AddonDetailScreen` — full meta detail UI
**File:** `phone/app/src/main/java/com/playbridge/sender/browser/AddonDetailScreen.kt`

- [x] IMDb IDs (`tt...`) still redirect to `MovieDetailScreen`/`TvShowDetailScreen` via TMDB `/find` (unchanged)
- [x] Non-IMDb IDs: calls `addonRepository.fetchMeta(type, id)` and renders a native detail view:
  - Backdrop/poster image, title, meta chips (year · rating · runtime · genres), description, cast
  - "Watch on TV" / "On Phone" buttons for movies (or series with no episode list)
  - Episode list (`LazyColumn`) for series sourced from `StremioMetaDetail.videos`; per-episode TV/Phone buttons
  - Per-episode `CircularProgressIndicator` during stream resolution
- [x] Stream resolution via `resolveStreamsFlow(type, streamId)` + `StreamPickerSheet`
- [x] `onPlayStream` callback passed in and wired to `onDismiss` → `showVideoSheet` in `BrowserActivity`

### 4.3 Wire `onPlayStream` in `BrowserActivity`
**File:** `phone/app/src/main/java/com/playbridge/sender/browser/BrowserActivity.kt`

- [x] Added `onPlayStream` lambda to `AddonDetailScreen` call, mirrors `MovieDetailScreen` pattern (wraps URL in `DetectedVideo`, sets `forcedVideos`, triggers `showVideoSheet = true`)

---

## Phase 5 — Addon management UI improvements

> Small but important: users need to know what each installed addon is capable of.

### 5.1 Show resource badges in the addon list
**File:** `phone/app/src/main/java/com/playbridge/sender/browser/AddonSettingsScreen.kt`

- [x] Added `AddonCapabilityBadges` composable: reads `InstalledAddonEntity.resources` via `supportsResource()` and renders a `CapabilityBadge` for each declared capability (Streams, Catalogs, Meta, Subtitles)
- [x] Color-coded per capability: stream → primaryContainer, catalog → tertiaryContainer, meta → secondaryContainer, subtitles → amber
- [x] Catalog badge includes the count of browseable catalog+type combinations from `parsedCatalogs().sumOf { it.types.size }`

### 5.2 Validate capability before querying
**File:** `AddonRepository.kt`

- [x] `resolveSubtitles()` — guards with `supportsResource("subtitles")` ✓ (Phase 2)
- [x] `fetchMeta()` — guards with `supportsResource("meta")` ✓ (Phase 4)
- [x] `fetchCatalog()` — called with an addon already filtered by `getAvailableCatalogs()` which only returns addons with non-empty catalogs ✓ (Phase 3)
- [x] `resolveStreams()` and `resolveStreamsFlow()` — added `resources.isBlank() || supportsResource("stream")` guard with backward-compatibility fallback for pre-Phase 1 installs (Phase 5)

---

## Testing checklist (per phase)

- [ ] **Phase 1:** Install a known addon (e.g. Torrentio), verify `resources` and `catalogsJson` are non-empty in the Room DB. Run the migration on a device that already has addons installed.
- [ ] **Phase 2:** Install OpenSubtitles v3 addon; verify subtitles are returned for a movie and an episode. Remove it; verify fallback to the hardcoded URL still works.
- [ ] **Phase 3:** Install Cinemeta (`https://v3-cinemeta.strem.io/manifest.json`); verify its "Top movies" catalog appears in the UI and items can be tapped through to streams.
- [ ] **Phase 4:** Use a catalog item with a non-IMDb ID; verify the detail screen populates from addon meta and episode list is correct for a series.
- [ ] **Phase 5:** Verify badges display correctly for addons that declare different resource subsets.

---

## Reference — Stremio addon endpoint patterns

```
GET {baseUrl}/manifest.json
GET {baseUrl}/catalog/{type}/{id}.json
GET {baseUrl}/catalog/{type}/{id}/skip={n}.json
GET {baseUrl}/meta/{type}/{id}.json
GET {baseUrl}/stream/{type}/{id}.json
GET {baseUrl}/subtitles/{type}/{id}.json
GET {baseUrl}/subtitles/{type}/{id}/{extra}.json
```

`type` is always one of: `movie`, `series`, `channel`, `tv`  
`id` is an IMDb ID for standard content (`tt1234567`), or an addon-specific ID for custom catalogs



use this to verify

export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" && ./gradlew app:assembleDebug