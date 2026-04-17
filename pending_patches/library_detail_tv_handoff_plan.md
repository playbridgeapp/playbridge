# Library → TV Handoff Refactor — Plan

_Draft: 2026-04-16 — send title metadata instead of resolved streams; TV shows a "Loading" UI and resolves streams itself._

---

## 1. Goal

Today, when a user taps **Watch on TV** in `LibraryDetailScreen`, the phone:

1. Resolves streams via addons on the **phone**.
2. Either auto-picks or shows a picker.
3. Sends a `play` command whose `PlayPayload.url` is the **already-resolved** CDN/Debrid URL.
4. TV launches `ExoPlayerActivity` and starts playing that URL immediately (a `SeriesContext` rides along so the TV can navigate prev/next on its own).

We want to flip this for Library casts:

1. Phone sends **only the title metadata** (TMDB/TVDB/IMDb/Kitsu/MAL identifiers + backdrop + episode context).
2. TV shows a new **pre‑play splash** (backdrop, title, episode info, "Resolving streams…" spinner).
3. TV runs the existing Stremio resolver (already baked in via `StremioClient` and `SeriesNavigator`) for the **initial** episode/movie — not just for prev/next.
4. Once a stream resolves, TV transitions into `ExoPlayerActivity` with the real URL.

The goal is purely a **transport change** — resolution logic on the TV is already written; we just need to reuse it for the first-play case.

---

## 2. Why

- **Bandwidth**: today the phone fetches addon stream indexes over its mobile/Wi‑Fi link, then the TV opens the same Debrid URL anyway. Moving resolution to the TV skips the phone round‑trip and uses whatever wired/5 GHz link the TV is on.
- **Debrid freshness**: Debrid URLs are often tied to the client IP that requested them. Resolving from the TV's own IP prevents occasional 403s when the phone is on cell data and the TV is on Wi‑Fi.
- **UX**: a backdrop + synopsis screen on the TV while streams resolve is a much nicer handoff than a black player staring at the user for 3–5 seconds.
- **Anime/Kitsu/MAL parity**: Kitsu/MAL titles currently skip TMDB entirely and ride on addon-native IDs — these already have no IMDb to hang a `SeriesContext` on, so the existing cast path loses the navigator. Fixing this transport is the natural moment to support them properly.

---

## 3. Kitsu/MAL — identifier strategy

The addon ecosystem (Stremio convention) accepts these ID shapes in stream/meta URLs:

| Prefix | Example | Who accepts it |
|---|---|---|
| `tt…` | `tt0944947` | Cinemeta, Torrentio, most Debrid addons |
| `kitsu:<id>` | `kitsu:41982` | Kitsu addon, Torrentio, most anime addons |
| `mal:<id>` | `mal:40748` | Torrentio (direct), few anime-specific addons |
| `tvdb:<id>` | `tvdb:404367` | TVDB addon, some scrapers |
| `tmdb:<id>` | `tmdb:90462` | A minority of addons |

Recommendation, in order of preference when the phone constructs the payload:

1. **IMDb** if present — universal support.
2. **Kitsu** for anime titles — broadly supported by anime stream addons.
3. **MAL → Kitsu cross-lookup** on the phone, only once, before sending the command. Kitsu's addon exposes `…/meta/series/kitsu:MAL_ID.json` style mapping; alternatively `https://kitsu.io/api/edge/mappings?filter[externalSite]=myanimelist/anime&filter[externalId]=<MAL>` returns the Kitsu record. Cache the result in `AddonRepository` so we don't repeat the call per cast. If lookup fails, fall back to raw `mal:<id>` (Torrentio will still work).
4. **TVDB / TMDB** — last resort; rarely useful for stream resolution.

Net effect: `SeriesContext` / new payload always carries a single **normalised** stream-capable ID. The TV never has to guess.

---

## 4. Protocol changes (`protocol/src/main/java/com/playbridge/protocol/Message.kt`)

Add a new sibling to `PlayPayload` rather than overloading it. This keeps the "raw URL" play path (browser video-detector, desktop extension) untouched and avoids making `url` nullable.

```kotlin
@Serializable
data class ContentPlayPayload(
    // ── Identity ─────────────────────────────────────────────
    /** Canonical stream-capable ID, already normalised by the phone. */
    val contentId: String,          // "tt0944947" | "kitsu:41982" | "mal:40748" | "tvdb:404367"
    val contentType: String,        // "movie" | "series"

    // ── Display metadata for the pre-play screen ─────────────
    val title: String,
    val year: String? = null,
    val rating: String? = null,     // IMDb/TMDB string, as already shown on phone
    val runtime: String? = null,    // formatted e.g. "1h 42m" or "3 Seasons"
    val overview: String? = null,
    val genres: List<String> = emptyList(),
    val cast: List<String> = emptyList(),
    val director: String? = null,   // null for series

    val backdropUrl: String? = null,
    val posterUrl: String? = null,
    val logoUrl: String? = null,

    // ── Episode selection (series only) ──────────────────────
    val season: Int? = null,
    val episode: Int? = null,
    val episodeTitle: String? = null,

    // ── Navigator payload (reuses SeriesContext fields) ──────
    /** Flat list across all seasons; null => optimistic +1 navigation. */
    val allEpisodes: List<SeriesEpisodeRef>? = null,
    val addonBaseUrls: List<String>,
    val addonNames: List<String>? = null,
    val preferredAddonBaseUrl: String? = null,
    val preferredAddonName: String? = null,

    // ── Playback preferences (carry-through) ─────────────────
    val playerMode: String? = null,
    val preferredAudioLanguage: String? = null,
    val preferredSubtitleLanguage: String? = null,
    val defaultVideoQuality: String? = null,
    val maxBitrateCapMbps: Double? = null,
    val forcePicker: Boolean = false        // long-press on phone = "always show picker on TV"
)

@Serializable
data class PlayContentCommand(
    val type: String = "command",
    val action: String = "play_content",
    val payload: ContentPlayPayload
)
```

Add to the sealed `Command` class:

```kotlin
data class PlayContent(val payload: ContentPlayPayload) : Command()
```

Extend `parseCommand()` with a `"play_content" ->` branch and add `createPlayContentCommandJson(...)`.

Nothing already in the protocol is removed or renamed — `PlayPayload` / `SeriesContext` stay exactly as they are so the existing `PlayContent … SeriesContext` navigator pipeline still works once the TV finishes resolving.

---

## 5. Phone changes

### 5.1 `LibraryDetailScreen.kt`

Introduce a sibling callback next to `onPlayStream`:

```kotlin
onPlayContent: (ContentPlayPayload) -> Unit = {}
```

Inside `startResolution` today's body does three things: set resolution state, run the flow, auto-pick / show picker. **Gut all of that for the library→TV case** and replace with a single helper that builds a `ContentPlayPayload` and fires `onPlayContent`.

Rules:

- **Phone target** (`forPhone == true`): keep the existing flow unchanged. We still need a concrete URL to hand to the phone's external player.
- **TV target** (`forPhone == false`): skip the resolver entirely and build the payload.

New helper (next to `buildSeriesContext`):

```kotlin
private suspend fun buildContentPayload(
    isSeries: Boolean,
    contentIdRaw: String,          // whatever LibraryDetailScreen has: "tt…", "123" (TMDB), "kitsu:…", "mal:…"
    resolvedImdbId: String?,
    resolvedTmdbId: Int?,
    tvDetails: TmdbTvDetails?,
    movieDetails: TmdbMovieDetails?,
    addonMeta: StremioMetaDetail?,
    selectedSeason: Int,
    episode: StremioVideo?,
    addonRepository: AddonRepository,
    preferences: CastPrefs           // audio/sub lang, quality, mbps, playerMode, forcePicker
): ContentPlayPayload?
```

Logic:

1. Pick `contentId` / `contentType` in this order: IMDb → Kitsu (if addon-native id starts with `kitsu:`) → MAL→Kitsu lookup via `AddonRepository.lookupKitsuFromMal(...)` (new, cached) → raw `mal:` / `tvdb:` / `tmdb:<numeric>` fallback.
2. Compose addon base URLs, names, preferred addon (already done inside `buildSeriesContext`; extract into a small shared util so both paths use one implementation).
3. Copy display metadata straight from whichever source populated each `displayXxx` field already in the screen.
4. For series, also set `season`, `episode`, `episodeTitle`, and `allEpisodes` from `addonMeta.videos` (same projection as `buildSeriesContext`).

Delete the now-dead branch of `startResolution` that launched the stream picker for TV casts; keep the picker for phone-target casts.

### 5.2 `BrowserActivity.kt`

Current wiring passes `onPlayStream` into `LibraryDetailScreen`, pushes a `DetectedVideo` into `forcedVideos`, and shows the `castSheetInitialMode` sheet. Replace with:

```kotlin
onPlayContent = { payload ->
    val cmd = com.playbridge.protocol.createPlayContentCommandJson(
        payload.copy(
            playerMode = prefs.getString("tv_player_mode", "tv")?.takeIf { it != "tv" },
            preferredAudioLanguage = preferredAudioLang.takeIf { it.isNotEmpty() },
            preferredSubtitleLanguage = preferredSubLang.takeIf { it.isNotEmpty() },
            defaultVideoQuality = defaultVideoQuality.takeIf { it != "Auto" },
            maxBitrateCapMbps = maxBitrateCapMbps
        )
    )
    connectionViewModel.webSocketClient.send(cmd)
    // Optional: show a transient "Cast to TV…" toast instead of the cast sheet.
    if (payload.contentType == "series") {
        onNowPlayingStarted(resolvedTmdbId ?: 0, payload.season ?: 1, payload.episode ?: 1)
    }
}
```

The pending-series-context plumbing (`pendingSeriesContext`, `pendingQualityTier`, `pendingBitrateCapMbps`, `forcedVideos`) can stay for phone-playback and extension video-detection; library→TV casts stop using it.

### 5.3 `AddonRepository.kt`

Add the Kitsu mapping helper used above:

```kotlin
/** Maps a MAL id to a Kitsu id via Kitsu's public mappings API. Cached in memory. */
suspend fun lookupKitsuFromMal(malId: String): String?
```

Nothing else in `AddonRepository` needs to change — we already have `getInstalledAddons()`, which is the only network dependency the pre-play path needs.

---

## 6. TV changes

### 6.1 New `PrePlayActivity` (preferred over folding into `ExoPlayerActivity`)

Folding the pre-play UI into `ExoPlayerActivity` was considered, but `ExoPlayerActivity` already weighs ~1.4 k lines and owns Media3, track selection, subtitle management, and progress restoration. Adding a second "not-yet-playing" lifecycle state to it increases the surface area of a file that's already fragile. A dedicated activity is cheaper.

Location: `tv/player/app/src/main/java/com/playbridge/player/preplay/PrePlayActivity.kt`.

Layout (Compose — TV module already uses Compose TV material):

- Full-screen blurred `AsyncImage(backdropUrl)`, same treatment as `TranslucentBackground` on the phone.
- Centred `Column`:
  - Optional `logoUrl` or bold `title`.
  - `year • rating • runtime • genres.take(3).joinToString(" · ")`.
  - For series: `S0?E0? — <episodeTitle>` row.
  - Short `overview` (max ~3 lines).
  - Circular progress indicator + "Resolving streams…" caption.
  - If resolution takes more than a few seconds, swap to a skeleton list of resolved streams as they arrive (reuse `StreamSelectionDialog` in a flat Composable form) so the user sees progress.

Behaviour:

1. `onCreate` reads the `ContentPlayPayload` JSON from the intent, displays the UI, sets `activeContext = "preplay"`, and calls `server.broadcastContext()` so the phone's remote switches context if needed (new context value — update `Command.ContextQuery` docstring only, no protocol change required since `active` is a free-form string).
2. Launches a coroutine that calls `StremioClient.resolveStreams(...)`. `resolveStreams` already supports `imdbId` and builds `…/stream/<type>/<id>.json`; we add a small polymorphic entry point:

   ```kotlin
   suspend fun resolveStreamsByContentId(
       addonBaseUrls: List<String>,
       addonNames: List<String>?,
       contentId: String,         // "tt…" | "kitsu:…" | "mal:…" | "tvdb:…"
       contentType: String,       // "movie" | "series"
       season: Int? = null,
       episode: Int? = null,
       qualityPreference: String? = null,
       sourceHint: String? = null,
       preferredAddonBaseUrl: String? = null
   ): List<ScoredStremioStream>
   ```

   Internally the request path is identical: `…/stream/<movie|series>/<contentId>[:S:E].json`. The existing `imdbId` parameter on `resolveStreams`/`resolveEpisode` is just the string that gets dropped in; no logic depends on the `tt` prefix. Refactor `imdbId` → `contentId` in `StremioClient` (internal rename only; no behavioural change).
3. Auto-pick vs. picker:
   - Read TV-side `auto_stream_quality` and `auto_stream_addon` (new TV prefs mirroring the phone's, or forward them inside the payload — already present as `defaultVideoQuality` and `preferredAddonBaseUrl` / `preferredAddonName`, so just honour those).
   - If `payload.forcePicker == true` → always show picker.
   - Else if `defaultVideoQuality != null` → auto-pick via a server-side port of `StreamSelector.selectBest` (also used for `SeriesNavigator`; lives in `tv/player/app/src/main/java/com/playbridge/player/stremio/`).
   - Else → show picker.
4. When a stream is chosen:
   - Build a `SeriesContext` from the payload (series only) — same shape as before, but now `imdbId` is whatever we normalised to (see §7).
   - Launch `ExoPlayerActivity` (or Vlc/Mpv based on `playerMode`) via the **existing** intent contract: `EXTRA_URL`, `EXTRA_TITLE`, `EXTRA_SERIES_CONTEXT`, quality/bitrate extras, etc. No changes required to `ExoPlayerActivity`.
5. If the addon returns zero streams: show a "No streams found" message with a Retry button and an "Open on phone" hint.

Manifest: new `<activity android:name=".preplay.PrePlayActivity" android:exported="false" android:theme="@style/Theme.Player" />`.

### 6.2 `ServerService.kt`

Add a branch in `handleCommand`:

```kotlin
is Command.PlayContent -> {
    com.playbridge.player.player.PlaylistStore.currentPlaylist = null
    activeContext = "preplay"
    broadcastContext()
    val json = protocolJson.encodeToString(ContentPlayPayload.serializer(), command.payload)
    val intent = Intent(this, PrePlayActivity::class.java).apply {
        putExtra(EXTRA_CONTENT_PAYLOAD, json)
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
    }
    launchActivityFromBackground(intent, "Opening pre-play")
}
```

`EXTRA_CONTENT_PAYLOAD` is a new companion-object constant on `ServerService`.

### 6.3 `SeriesContext` field-name question

`SeriesContext.imdbId` is now a misnomer for Kitsu/MAL titles. Two choices:

- **A (recommended)**: keep the field name (minimises diff; it's already an opaque string on the wire) but update the KDoc to call it "primary stream-capable ID" and note which prefixes are valid.
- **B**: rename to `contentId` across protocol, phone, and TV. This ripples through `SeriesNavigator`, `StremioClient`, and every existing `serializer()`-compiled caller. It's clean but purely cosmetic.

Tentatively go with A to keep this patch tight and deferred to a separate renaming pass if ever wanted.

---

## 7. Things to verify before coding

1. **Stream URL format for Kitsu** against the addons actually installed by the user (Torrentio vs. Kitsu Anime vs. others). The plan assumes `…/stream/series/kitsu:<id>:S:E.json` works for episodic anime; spot-check against a couple of real responses before committing.
2. **Debrid resume token behaviour** — if a Debrid URL is tied to the phone's IP today, confirm moving resolution to the TV produces a URL the TV can actually open. This is probably a *fix* rather than a regression, but needs to be watched.
3. **Progress restoration** — `HistoryStore` keys on the stream URL in some paths; confirm series progress still restores across casts, because the URL now changes per session rather than being the same Debrid URL as before.
4. **`forcePicker` plumbing** — phone has a long-press shortcut to force picker (`onWatchOnTvLongClick`). Make sure `payload.forcePicker` is set from it and honoured in `PrePlayActivity`.
5. **Phone offline during TV playback** — TV resolves streams independently. Good. But confirm the NSD + WebSocket heartbeat doesn't send unnecessary commands back to the phone during pre-play.
6. **Cancellation** — if the user presses Back on the pre-play screen, cancel the resolver coroutine and set `activeContext = "idle"`.

---

## 8. Files touched (summary)

**Protocol**
- `protocol/src/main/java/com/playbridge/protocol/Message.kt` — add `ContentPlayPayload`, `PlayContentCommand`, `Command.PlayContent`, `parseCommand` branch, `createPlayContentCommandJson`.

**Phone**
- `phone/app/src/main/java/com/playbridge/sender/browser/LibraryDetailScreen.kt` — new `onPlayContent` callback, TV path bypasses resolver, new `buildContentPayload` helper, Kitsu/MAL normalisation at payload build time.
- `phone/app/src/main/java/com/playbridge/sender/browser/BrowserActivity.kt` — wire `onPlayContent` to `createPlayContentCommandJson` + `webSocketClient.send`.
- `phone/app/src/main/java/com/playbridge/sender/data/library/AddonRepository.kt` — `lookupKitsuFromMal` (memoised).

**TV**
- `tv/player/app/src/main/java/com/playbridge/player/preplay/PrePlayActivity.kt` — new.
- `tv/player/app/src/main/java/com/playbridge/player/preplay/PrePlayScreen.kt` — new Compose UI.
- `tv/player/app/src/main/java/com/playbridge/player/server/ServerService.kt` — `Command.PlayContent` branch + `EXTRA_CONTENT_PAYLOAD`.
- `tv/player/app/src/main/java/com/playbridge/player/stremio/StremioClient.kt` — add `resolveStreamsByContentId` that accepts any ID prefix (or rename the existing `imdbId` param to `contentId`).
- `tv/player/app/src/main/AndroidManifest.xml` — register `PrePlayActivity`.

**Docs**
- `AI_CONTEXT.md` — bump the "Cross-cutting Gotchas" note to mention the new command.
- `protocol/ARCHITECTURE.md` — document `play_content`.
- `tv/ARCHITECTURE.md` — mention `preplay` context and the new activity.

Extension (`extension/src/background.js`) does **not** need changes — it only emits `play` from the desktop browser, which is unrelated to the library flow.

---

## 9. Rollout order

1. Protocol additions + compile all three modules (no behaviour change yet).
2. TV pre-play activity + ServerService branch — verify end-to-end by hand-crafting a `play_content` JSON in `adb shell` against the dev TV app.
3. Phone `buildContentPayload` + `onPlayContent` wiring — library → TV should now work for IMDb-backed titles.
4. Kitsu / MAL normalisation in `AddonRepository` — verify with an anime title.
5. Docs + clean up the unused `pendingSeriesContext` path in `BrowserActivity` once nothing calls `onPlayStream` for the library→TV case.
