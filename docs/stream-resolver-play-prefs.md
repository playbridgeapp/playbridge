# `/api/play` Streaming Preferences — Implementation Plan

## Overview

Add per-request streaming preferences to the `/api/play/{type}/{id}` endpoint as URL
query parameters. The TV player is a dumb player (ExoPlayer/VLC) that only follows
redirect URLs — it cannot POST, so all preferences must travel as GET query params.

Preferences are applied as a **post-ranking filter** inside `handlePlay` after
`getStreamList()` returns the pre-ranked list. The shared stream list cache
(`cache/streams/`) is unaffected. Only the play result cache (`cache/play/`) needs
to incorporate the active prefs in its key.

---

## Query Parameters

| Param | Type | Values | Notes |
|---|---|---|---|
| `quality` | string | `4K`, `1080p`, `720p`, `480p`, `any` | Matched against `name` field only — avoids a 1080p encode of a 4K source being misclassified |
| `sourceType` | string | `remux`, `bluray`, `web-dl`, `webrip`, `hdtv`, `dvd`, `cam/ts`, `other`, `any` | Comma-separated for multiple; `other` = no known source token present |
| `source` | string | addon name | Bidirectional substring match against `SourceName` (e.g. `AIOStreams`, `4KHDHub`) |
| `minSize` | float | GB | Parsed from description/name text; streams with unknown size are let through |
| `maxSize` | float | GB | Same parsing as `minSize` |
| `minBitrate` | float | Mbps | Parsed from description text or computed from size + runtime; unknown size let through |
| `maxBitrate` | float | Mbps | Same parsing as `minBitrate` |
| `audioLang` | string | `en`, `multi`, `fr`, `es`, … | Substring search in name + description; `multi` matches dual/multilang streams |
| `noCache` | int | `0` (default), `1` | Bypass play result cache for this request |
| `probe` | int | `1` (default), `0` | Override server probing config per-request |
| `skip` | int | `0` (default) | Already implemented — start from rank index N |

### Example URLs

```
# Basic quality filter
http://resolver:7001/api/play/movie/tt1234567?quality=1080p

# Quality + source type + size cap
http://resolver:7001/api/play/movie/tt1234567?quality=1080p&sourceType=web-dl,remux&maxSize=15

# Prefer AIOStreams, 4K, force fresh result
http://resolver:7001/api/play/movie/tt1234567?quality=4K&source=AIOStreams&noCache=1

# English audio only, bitrate cap for slow connection
http://resolver:7001/api/play/movie/tt1234567?quality=1080p&audioLang=en&maxBitrate=10

# TV retry — next candidate, same prefs
http://resolver:7001/api/play/movie/tt1234567?quality=1080p&maxSize=15&skip=1
```

---

## Source Types Reference

Ported from the hub's `checkPreferred` logic. The `sanitize()` function strips
non-alphanumeric chars before matching. Short tokens (≤ 3 chars) use word-boundary
regex to avoid false positives.

| Value | Tokens matched in stream content |
|---|---|
| `remux` | `remux` |
| `bluray` | `bluray`, `blu-ray` |
| `web-dl` | `web-dl` |
| `webrip` | `webrip` |
| `hdtv` | `hdtv` |
| `dvd` | `dvd` |
| `cam/ts` | `cam`, `ts` (word-boundary) |
| `other` | stream content contains **none** of the above known tokens |
| `any` | disables source type filtering entirely |

---

## File Size Parsing

Neither HDHub nor AIOStreams include `behaviorHints.videoSize`. Size is embedded
in the `description` text in different formats:

- HDHub: `[💾 14.66 GB]`
- AIOStreams: `◈  5.61 GB · 7.83 ᴹᵇᵖˢ`

A single regex `(\d+\.?\d*)\s*(TB|GB|MB)` applied to `Description → Name → Title`
(in order) handles both. If no size is found, the stream is **not filtered out** —
we don't punish streams that don't advertise their size.

---

## Bitrate Parsing

Two strategies in priority order:

**1. Explicit Mbps text in description** — AIOStreams embeds it directly:

```
◈  5.61 GB · 7.83 ᴹᵇᵖˢ
```

The superscript `ᴹᵇᵖˢ` (U+1D39 U+1D47 U+1D56 U+02E2) must be matched alongside
plain ASCII `Mbps`. Regex: `(\d+\.?\d*)\s*(?i:Mbps|ᴹᵇᵖˢ)` applied to
`Description → Name → Title`.

**2. Computed from size + runtime** — when explicit Mbps is absent, derive it from
the parsed file size (GB) and the expected runtime already fetched by `runtimeMins()`
for probing:

```
bitrateMbps = (sizeGB × 1073741824 × 8) / (runtimeMins × 60 × 1_000_000)
```

Both strategies share the same `bitrateOf(rs, runtimeMins)` helper that returns 0
if neither source yields a value. If bitrate is 0 (unknown), the stream is let
through regardless of `minBitrate`/`maxBitrate`.

---

## Quality Matching

Checked against `Name` field only — not `Description`. This avoids the case where
a 1080p encode of a 4K source has `2160p` in its description but is actually a
downscaled file. AIOStreams embeds zero-width joiners (U+200D) in `Name`; these
must be stripped before matching.

| Param value | Tokens matched in `Name` |
|---|---|
| `4K` | `2160`, `4k`, `uhd` |
| `1080p` | `1080` |
| `720p` | `720` |
| `480p` | `480` |
| `any` / empty | no filtering |

---

## Audio Language Matching

Searched in `Name + Description` (combined, lowercased). Language is expressed
inconsistently across addons:

- HDHub uses filename tokens: `Multi.DDP5.1`, `English`
- AIOStreams uses Unicode small caps in description: `ᴇɴ` (= "en"), `ᴍᴜʟᴛɪ`

Because small caps are semantically equivalent to their ASCII forms, the matcher
normalizes a known set of small-cap sequences to ASCII before checking:

| Small caps | ASCII |
|---|---|
| `ᴇɴ` | `en` |
| `ᴍᴜʟᴛɪ` | `multi` |
| `ғʀ` | `fr` |
| `ᴇs` | `es` |
| `ᴅᴇ` | `de` |

After normalization, the following token groups are checked:

| `audioLang` value | Matches |
|---|---|
| `en` | `en`, `english`, `eng` |
| `multi` | `multi`, `multilang`, `dual`, `multi audio` |
| `fr` | `fr`, `french`, `français` |
| `es` | `es`, `spanish`, `español` |
| `de` | `de`, `german`, `deutsch` |
| other | exact substring match of the normalized param value |

If none of the tokens for the requested language appear in the combined content,
the stream is filtered out. If the language is not detectable (no token match for
any known language), the stream is **let through** — we don't punish streams that
don't embed language information.

---

## Play Cache Key

The play result cache key must include active prefs so different preference combos
don't collide. The stream list cache key is **unchanged** (`type/id`).

```
play cache key = "{type}/{id}|q={quality}&st={sourceType}&src={source}&min={minSize}&max={maxSize}&minbr={minBitrate}&maxbr={maxBitrate}&lang={audioLang}"
```

Only non-zero/non-empty prefs are appended. `noCache=1` bypasses the cache entirely.
`skip > 0` already bypasses the cache (existing behaviour).

---

## Implementation Steps

### Phase 1 — Go server (`stream-resolver/`)

#### 1.1 `server/play.go` — add helpers and wire into `handlePlay`

New functions to add:

- `PlayPrefs` struct
- `parsePlayPrefs(r *http.Request) PlayPrefs`
- `prefsKey(p PlayPrefs) string`
- `matchesPrefs(rs types.RankedStream, p PlayPrefs, runtimeMins int) bool`
- `qualityTier(s types.Stream) string` — name-only, strips zero-width chars
- `sizeGB(s types.Stream) float64` — regex over description/name/title
- `bitrateOf(rs types.RankedStream, runtimeMins int) float64` — explicit Mbps parse, fallback to size÷runtime
- `audioLangMatches(s types.Stream, lang string) bool` — small-caps normalization + token group check
- `sanitize(s string) string` — strips non-alphanumeric for source type matching
- `wordBoundaryMatch(content, token string) bool` — word-boundary regex for short tokens

Changes to `handlePlay`:

1. Call `parsePlayPrefs(r)` at the top
2. Append `prefsKey(prefs)` to `playCacheKey`
3. Add `prefs.NoCache` check to the cache bypass condition alongside `skip == 0`
4. Gate probing on `!prefs.ProbeOff && s.cfg.Probing.Enabled && prober.Available()`
5. Pass `expectedMins` into `matchesPrefs` so `bitrateOf` can use it for the size÷runtime fallback
6. In the probe loop: `if rs.Stream.URL == "" || !matchesPrefs(rs, prefs, expectedMins) { continue }`
   — non-matching streams are skipped but do **not** count against `probeCount`

#### 1.2 `types/types.go` — advertise `/api/play` in manifest hints

```go
type ManifestHints struct {
    Configurable bool   `json:"configurable,omitempty"`
    PlayEndpoint string `json:"playEndpoint,omitempty"`
}
```

#### 1.3 `server/manifest.go` — populate `PlayEndpoint`

```go
BehaviorHints: types.ManifestHints{
    Configurable: true,
    PlayEndpoint: "/api/play/{type}/{id}",
},
```

This lets the phone app detect play endpoint support by reading the manifest — no
hard-coding needed.

---

### Phase 2 — Phone app (`phone/`)

#### 2.1 `data/library/AddonModels.kt` — parse manifest hints

```kotlin
@Serializable
data class StremioManifestHints(
    val playEndpoint: String? = null
)

// Add to StremioManifest:
val behaviorHints: StremioManifestHints? = null

// Add to InstalledAddonEntity:
val playEndpoint: String = ""  // "/api/play/{type}/{id}" or empty = standard /stream only
```

#### 2.2 `data/library/AddonRepository.kt` — store on install and refresh

In `installAddon()` and `refreshAddon()`, read `manifest.behaviorHints?.playEndpoint`
and store it in the entity.

#### 2.3 Room migration

Add a new migration version that adds the `playEndpoint TEXT NOT NULL DEFAULT ''`
column to the `installed_addons` table.

#### 2.4 Streaming preferences storage (`data/StreamingPrefs.kt`) — new file

Store user preferences in DataStore. Fields matching all supported query params:

```kotlin
@Serializable
data class StreamingPrefs(
    val quality: String = "",              // "4K", "1080p", "720p", "480p", "" = any
    val sourceTypes: List<String> = emptyList(), // ["web-dl", "remux"], empty = any
    val maxSizeGB: Float = 0f,             // 0 = no limit
    val minSizeGB: Float = 0f,             // 0 = no limit
    val minBitrateMbps: Float = 0f,        // 0 = no limit
    val maxBitrateMbps: Float = 0f,        // 0 = no limit
    val audioLang: String = "",            // "en", "multi", "fr", etc. — "" = any
)
```

Persisted to DataStore under a `streaming_prefs` key. Shared across sessions.

#### 2.5 Play URL construction — wherever the TV cast decision is made

When sending a stream URL to the TV via `PlayCommand`:

1. Check `addon.playEndpoint.isNotBlank()` — if true, this addon supports `/api/play`
2. Substitute `{type}` and `{id}` in the endpoint template
3. Append active preference params from `StreamingPrefs`
4. Send the constructed URL to the TV instead of the raw stream URL

```kotlin
fun buildPlayUrl(
    addon: InstalledAddonEntity,
    type: String,
    id: String,
    prefs: StreamingPrefs,
): String? {
    val template = addon.playEndpoint.ifBlank { return null }
    val base = addon.baseUrl + template
        .replace("{type}", type)
        .replace("{id}", id)
    val params = buildList {
        if (prefs.quality.isNotBlank()) add("quality=${prefs.quality}")
        if (prefs.sourceTypes.isNotEmpty()) add("sourceType=${prefs.sourceTypes.joinToString(",")}")
        if (prefs.maxSizeGB > 0) add("maxSize=${prefs.maxSizeGB}")
        if (prefs.minSizeGB > 0) add("minSize=${prefs.minSizeGB}")
        if (prefs.maxBitrateMbps > 0) add("maxBitrate=${prefs.maxBitrateMbps}")
        if (prefs.minBitrateMbps > 0) add("minBitrate=${prefs.minBitrateMbps}")
        if (prefs.audioLang.isNotBlank()) add("audioLang=${prefs.audioLang}")
    }
    return if (params.isEmpty()) base else "$base?${params.joinToString("&")}"
}
```

#### 2.6 TV retry — pass `skip=N`

When the TV signals playback failure (via `StatusMessage` with `state = "error"`),
the phone increments `skip` and re-sends the same URL with `?skip=1`, `?skip=2`, etc.,
preserving all other preference params.

#### 2.7 Preferences UI — settings screen

A new section in app settings (or within the stream picker sheet) exposing:

- Quality picker: Any / 4K / 1080p / 720p / 480p
- Source type multi-select: Remux, Blu-ray, WEB-DL, WEBRip, HDTV, DVD, Cam/TS, Other
- Audio language picker: Any / English / Multi / French / Spanish / German
- Max file size slider (0 = unlimited, up to ~50 GB)
- Min file size slider (0 = none)
- Max bitrate slider in Mbps (0 = unlimited — useful for bandwidth-constrained connections)
- Min bitrate slider in Mbps (0 = none — useful to avoid low-quality re-encodes)

---

## Rollout Order

1. **Go server changes** (Phase 1) — self-contained, can be deployed and tested via Bruno
   before any phone changes land.
2. **Manifest hint** (Phase 1.2–1.3) — deploy alongside Phase 1; lets the phone detect
   support once Phase 2 ships.
3. **AddonModels + Room migration** (Phase 2.1–2.3) — schema change, needs careful
   migration version bump.
4. **StreamingPrefs DataStore + play URL construction** (Phase 2.4–2.5) — core phone logic.
5. **TV retry with skip** (Phase 2.6) — requires the `StatusMessage` error state from
   the protocol pending patch to be applied first.
6. **Preferences UI** (Phase 2.7) — can ship after the rest; defaults work without it.
