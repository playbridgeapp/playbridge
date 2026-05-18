# Stream Resolver Addon

A standalone Go-based Stremio stream addon that aggregates streams from multiple
source addons, merges and ranks them, and exposes two endpoints: a standard
Stremio `/stream` endpoint for client-side picking and a headless `/api/play`
endpoint that probes candidates and redirects to the first verified working stream.

**Status:** Built and working. All packages compile cleanly (`go build ./...`).

**Location:** `/Users/atulmehla/repos/personal/PlayBridge/stream-resolver/`

**Module:** `github.com/truedem0n/playbridge-stream-resolver`

**Go version:** 1.22 — uses the new `http.ServeMux` path parameter syntax `{param}`

**No external dependencies** — pure stdlib only.

---

## Why This Exists

AIOStreams ranks debrid/torrent streams well, but:
- Does not cover direct HTTP stream addons (HDHub, etc.)
- Cannot guarantee a stream actually works — links can be stale
- Has no runtime/duration validation

The Go hub currently handles probing but is being refactored into a PlayBridge
state server (continue watching, addon registry, etc.). This addon extracts that
stream resolution logic into a self-contained service.

---

## Role in the Stack

```
Source addons
  ├── AIOStreams          (debrid/torrent, pre-ranked by AIOStreams pipeline)
  ├── HDHub              (direct HTTP streams)
  └── Any Stremio addon  (any addon with /stream/:type/:id.json)
            │
            ▼
  Stream Resolver        (this service — port 7001 locally)
  ├── GET /stream/{type}/{id}       ← Stremio protocol, for phone stream picker
  ├── GET /api/play/{type}/{id}     ← Probes + 307 redirect, for TV / Apple TV
  ├── GET /configure                ← Web UI (Stremio opens this)
  └── REST /api/addons, /api/meta-addons, /api/cache,
           /api/config/cache, /api/config/probing
            │
    ┌───────┴────────┐
    │                │
  TV app          Phone app
  (api/play)      (/stream endpoint + Stremio)
```

---

## File Structure

```
stream-resolver/
├── main.go                    # Entry point — loads config, starts server
├── config.example.json        # Reference config with all fields
├── go.mod
├── config/
│   └── config.go              # Config structs + Load() + applyDefaults()
├── types/
│   └── types.go               # Stream, Manifest, RankedStream, etc.
├── cache/
│   └── cache.go               # On-disk TTL cache + InflightMap
├── resolver/
│   ├── fetch.go               # FetchAll() — parallel fan-out to source addons
│   └── rank.go                # Rank() — scoring, sorting, deduplication
├── prober/
│   └── prober.go              # ProbeDuration() via ffprobe, Available()
├── server/
│   ├── server.go              # Server struct, route registration, CORS middleware
│   ├── manifest.go            # GET /manifest.json
│   ├── stream.go              # GET /stream/{type}/{id}
│   ├── play.go                # GET /api/play/{type}/{id}
│   ├── meta.go                # Runtime lookup via meta addons + caching
│   ├── addons.go              # GET/POST/DELETE /api/addons + persistConfig()
│   ├── meta_addons_api.go     # GET/POST/DELETE /api/meta-addons
│   ├── cache_api.go           # DELETE /api/cache, GET/PUT /api/config/cache
│   ├── probing_api.go         # GET/PUT /api/config/probing
│   └── configure.go           # GET /configure — HTML management UI
└── bruno/
    └── stream-resolver/       # Bruno API collection (all endpoints)
```

---

## All Endpoints

| Method | Path | Description |
|--------|------|-------------|
| GET | `/manifest.json` | Stremio manifest (`behaviorHints.configurable: true`) |
| GET | `/stream/{type}/{id}` | Ranked stream list — Stremio protocol |
| GET | `/configure` | Web management UI (HTML) |
| GET | `/api/play/{type}/{id}` | Probe + 307 redirect to best working stream |
| GET | `/api/addons` | List configured source addons |
| POST | `/api/addons` | Add a source addon (validates manifest first) |
| DELETE | `/api/addons?url=` | Remove a source addon by URL |
| GET | `/api/meta-addons` | List configured meta addons |
| POST | `/api/meta-addons` | Add a meta addon (validates manifest + meta resource) |
| DELETE | `/api/meta-addons?url=` | Remove a meta addon by URL |
| DELETE | `/api/cache?type=` | Clear cache (`streams`, `play`, `meta`, or `all`) |
| GET | `/api/config/cache` | Get current TTL settings |
| PUT | `/api/config/cache` | Update TTL settings (persists to config.json) |
| GET | `/api/config/probing` | Get current probing settings |
| PUT | `/api/config/probing` | Update probing settings (persists to config.json) |

### `/api/play` query params
| Param | Default | Description |
|-------|---------|-------------|
| `skip` | `0` | Start probing from this rank index. TV uses this to retry next candidate on failure. |

### `/api/cache` query params
| `type` value | Effect |
|---|---|
| `streams` | Clears merged stream list cache |
| `play` | Clears resolved play URL cache |
| `meta` | Clears runtime lookup cache |
| `all` | Clears all three (default if omitted) |

---

## Config (`config.json`)

```json
{
  "port": 7001,
  "base_url": "http://localhost:7001",
  "addons": [
    {
      "url": "https://your-aiostreams/stremio/UUID/TOKEN",
      "name": "AIOStreams",
      "priority": 0,
      "timeout_ms": 8000
    },
    {
      "url": "https://hdhub.example.com",
      "name": "HDHub",
      "priority": 1,
      "timeout_ms": 5000
    }
  ],
  "meta_addons": [
    {
      "url": "https://v3-cinemeta.strem.io",
      "name": "Cinemeta",
      "timeout_ms": 5000
    }
  ],
  "probing": {
    "enabled": true,
    "max_attempts": 5,
    "timeout_ms": 5000
  },
  "cache": {
    "stream_list_ttl_seconds": 300,
    "play_result_ttl_seconds": 3600,
    "meta_ttl_seconds": 86400
  }
}
```

**Defaults applied if omitted:** port 7000, source addon timeout 8000ms, meta addon
timeout 5000ms, max probe attempts 5, probe timeout 5000ms, stream list TTL 300s,
play result TTL 3600s, meta TTL 86400s.

---

## Ranking Logic (`resolver/rank.go`)

Score formula: `500 - (priority × 50) - (sourcePos × 10) + resolutionBonus`

| Resolution | Bonus |
|---|---|
| 4K / 2160p / UHD | +100 |
| 1080p | +80 |
| 720p | +60 |
| other | 0 |

- **priority**: addon's configured priority field (lower number = higher score)
- **sourcePos**: stream's position within its addon's response (0 = best)
- After scoring: sort descending, then deduplicate by URL and InfoHash

AIOStreams streams at priority 0 + position 0 score highest, preserving its
internal pipeline ranking.

---

## Caching (`cache/cache.go`)

Three independent on-disk JSON caches under `cache/` (relative to working dir):

| Store | Directory | Key | Default TTL | Shared with |
|-------|-----------|-----|-------------|-------------|
| streamCache | `cache/streams/` | `type/id` | 300s | `/stream` + `/api/play` |
| playCache | `cache/play/` | `type/id` | 3600s | `/api/play` only |
| metaCache | `cache/meta/` | `meta:imdbID` | 86400s | `/api/play` only |

- File modtime is used as the write timestamp for TTL checks
- `InflightMap` (in `cache/cache.go`) deduplicates concurrent requests for the
  same key — only one fan-out happens, others wait and reuse the result
- `Store.SetTTL()` updates the TTL live (used by `PUT /api/config/cache`)
- `Store.Clear()` removes all files in the store's directory

---

## Probing (`prober/prober.go`)

- Calls `ffprobe` via PATH, then `/opt/homebrew/bin`, `/usr/local/bin`, `/usr/bin`
- `Available() bool` — checked at startup, logs warning if missing
- `ProbeDuration(url, timeoutMs) (int, error)` — returns duration in minutes
- Duration check: `probed_mins >= expected_mins * 0.5`

### Runtime lookup (`server/meta.go`)

Expected runtime is sourced from **meta addons** configured under `meta_addons`
in `config.json` — not OMDB. Meta addons are queried in order; the first to return
a non-zero runtime wins. Each addon is tried as `movie` then `series` type via the
standard Stremio meta endpoint: `{base}/meta/{type}/{id}.json`.

Cinemeta (`https://v3-cinemeta.strem.io`) is the recommended default — it's free,
public, requires no key, and has reliable runtime data for virtually everything.
Additional meta addons can be added as fallbacks via the UI or `config.json`.

Results are cached for `meta_ttl_seconds` (default 24h) so repeat `/api/play`
calls don't re-hit the meta addon.

**Probing is skipped when:**
- No meta addons are configured
- All meta addons return no runtime for the title
- `expected_mins <= 10` (too short to validate meaningfully)
- Probing is disabled in config
- ffprobe is not found (degraded mode — redirects to rank 0)

`max_attempts` bounds worst-case probing latency (default: 5 streams tried).

---

## Addon Management (`server/addons.go`, `server/meta_addons_api.go`)

**Source addons** (`/api/addons`): When adding with no explicit name, the server
fetches `{base}/manifest.json` and reads the `name` field. This also serves as
validation — unreachable URLs, non-200 responses, or manifests missing `id`/`name`
are rejected with a `400`. The `timeout_ms` field controls the per-request HTTP
timeout for that addon when fetching streams (default 8000ms; increase for slow
addons like AIOStreams).

**Meta addons** (`/api/meta-addons`): Same manifest validation as source addons,
plus an additional check that the manifest advertises a `meta` resource (in either
string or object form). Queried in configured order for runtime lookup only.

All changes persist to `config.json` immediately under the server's write lock and
take effect on the next request — no restart needed.

---

## Configure UI (`server/configure.go`)

Dark-themed HTML page served at `/configure`. Stremio shows a "Configure" button
on the addon card because `manifest.json` includes `behaviorHints.configurable: true`.

Sections:
1. **Source Addons** — lists addons with name / URL / priority / timeout, remove button
2. **Add Source Addon** — URL, optional name, priority, timeout_ms; validates manifest on submit
3. **Meta Addons** — lists meta addons with name / URL / order / timeout, remove button
4. **Add Meta Addon** — URL, optional name, timeout_ms; validates manifest + meta resource
5. **Cache** — editable TTL fields for each cache store + per-store clear buttons
6. **Probing** — enable toggle, max attempts, probe timeout
7. **Install bar** — shows manifest URL + `stremio://` deep link to install

All interactions use `fetch()` against the REST API — no page reloads.

---

## Server Internals (`server/server.go`)

```go
type Server struct {
    mu          sync.RWMutex   // guards cfg and cfgPath
    cfg         *config.Config
    cfgPath     string

    streamCache *cache.Store
    playCache   *cache.Store
    metaCache   *cache.Store
    inflight    *cache.InflightMap
}
```

`getStreamList()` in `server/stream.go` is the shared function used by both
`/stream` and `/api/play` — it checks the stream cache, uses InflightMap to
coalesce concurrent requests, and calls `resolver.FetchAll` + `resolver.Rank`.

CORS middleware sets `Access-Control-Allow-Origin: *` (required by Stremio).

---

## Running

```bash
cd stream-resolver
cp config.example.json config.json
# edit config.json with your addon URLs and port
go run .
```

Server logs at startup:
```
[server] Listening on :7001
[server] Manifest:   http://localhost:7001/manifest.json
[server] Configure:  http://localhost:7001/configure
[server] Configured addons: 2
[server]   [priority 0] AIOStreams (https://...)
[server]   [priority 1] HDHub (https://...)
```

Per-request logs:
```
[fetcher] AIOStreams: got 17 streams for movie/tt16431404
[fetcher] HDHub: got 3 streams for movie/tt16431404
[meta] Cinemeta: runtime for tt16431404 = 96 min (raw: "96 min", type: movie)
[play] expected runtime: 96 min
[play] probing [1/5] StreamName (pos 0 from AIOStreams)
[play] probe passed (94 min), redirecting to stream 0
```

---

## Bruno Collection

`stream-resolver/bruno/stream-resolver/` contains a Bruno API collection with
all 18 endpoints. Environment variable `{{baseUrl}}` defaults to
`http://localhost:7001` in `environments/stream-resolver.yml`.

Play endpoints have `followRedirects: false` so the `307` redirect location is
visible directly in Bruno.

---

## Pending / Next Steps

- **Protocol change in `Message.kt`:** Add `errorReason: String? = null` and
  `state = "error"` to `StatusMessage` so the TV can signal playback failure to
  the phone.
- **TV (`ExoPlayerActivity`):** Broadcast `state = "error"` when
  `Player.STATE_IDLE` with non-null `playerError`.
- **Phone (`RemoteControlScreen`):** On `state == "error"`, auto-open
  `StreamPickerSheet` for the current item — phone picks a stream, TV receives
  it via `PlayCommand`.
- **Hub refactor:** Remove `GetUnifiedStreams`, `ResolveBestStream`,
  `probeDuration`, `checkPreferred`, `StreamingPreferences` from `aggregator.go`
  once the TV app switches to calling this addon's `/api/play` directly.
- **ffprobe in Docker:** Bundle ffprobe in the addon's Docker image to avoid
  degraded mode in hosted deployments.
- **Preferences system:** Optional `config.json` block for preferred resolution,
  min/max file size, cached-only debrid streams, etc. Would feed into
  `resolver/rank.go` scoring.
