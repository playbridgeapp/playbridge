# Android phone video detection — site types and expected behaviour

This document describes what the built-in GeckoView **video detector** is designed to do on the Android phone browser: which kinds of sites it can cast from, what the cast sheet should show, and how navigation affects stored detections.

It is a **capability and expectation guide**, not a list of supported brand domains. Site-specific support will always drift; pattern-based behaviour should not.

| Component | Location |
|---|---|
| Shared pure core | `extension/src/core/` |
| Phone GeckoView adapter | `extension/src/geckoview/` |
| Generated assets (do not hand-edit) | `mobile/android/app/src/main/assets/extensions/video_detector/` |
| Kotlin store + ranking | `VideoDetector.kt` |
| Native message bridge | `Components.kt` + `DetectorTabBindingTracker` |

Rebuild detector assets with `pnpm build` from `extension/` after changing TypeScript sources.

---

## What “detection” means

The detector **observes network traffic and DOM media elements** in the in-app browser and builds a per-tab list of castable candidates (`DetectedVideo`). It does **not**:

- Break DRM or decrypt protected streams
- Reconstruct pure Media Source Extensions (MSE) pipelines that never expose an `http(s)` media URL
- Guarantee that every detected URL will play on every cast target
- Clear the sheet on every URL-bar cosmetic change (see [Navigation rules](#navigation-rules-when-the-sheet-empties))

Detection is **push-based**: the extension sends native messages; the app does not poll the page.

---

## Navigation rules (when the sheet empties)

This is the behaviour that most often surprises people: **the URL bar can change without the cast sheet clearing.**

### Hard navigation (document load)

Triggered by Gecko `webNavigation.onCommitted` for the main frame (typed URL, full link load, reload that replaces the document, many server redirects that load a new document).

| Step | Behaviour |
|---|---|
| Extension | Bumps `navigationGeneration`, clears its own per-tab detection cache, sends `type: "navigation"` (not `same_document`) |
| Kotlin | Treats a newer generation as **ADVANCE** → **clears** that tab’s `DetectedVideo` list |
| Cast sheet | Empties, then fills only with media from the new document |

### Soft / SPA navigation (history API, no full load)

Triggered by `webNavigation.onHistoryStateUpdated` when the site updates the address bar via `history.pushState` / `replaceState` (or Gecko’s equivalent) **without** loading a new top-level document.

| Step | Behaviour |
|---|---|
| Extension | **Does not** bump `navigationGeneration`; **does not** clear detections; may ask the content script to rescan the DOM; sends `type: "navigation"` with `transitionType: "same_document"` |
| Kotlin | **Keeps** all existing rows; bumps the tab’s **media lifecycle** so ranking prefers streams associated with the new view; rows first seen within a short grace window may adopt the new lifecycle |
| Cast sheet | **Does not empty.** Older streams remain listed and castable. New-view streams are meant to rise to the top once they appear and (when needed) finish verification |

This is intentional: many SPA watch pages leave the previous video’s HLS session alive (playlist polls, live edges). Clearing would drop a still-valid cast target the user might still want.

### Hash-only changes (`#section`)

`onReferenceFragmentUpdated` updates the extension’s notion of the tab URL but **does not** notify Kotlin as `same_document` and **does not** advance media lifecycle. In-page anchors should not reshuffle the cast sheet.

### View change with **no** URL change

Some players swap videos (or open a player overlay) without touching the address bar at all.

| Behaviour | Consequence |
|---|---|
| No `navigation` / `same_document` message | No clear, no lifecycle bump |
| New network/DOM detections still arrive | They **append** (or update by URL) under the **same** lifecycle |
| Ranking | Relies on recency, verification, and evidence scores only — not SPA lifecycle |

### Summary table

| What the user does / site does | URL bar | Sheet empties? | Ranking signal |
|---|---|---|---|
| Open a new full page | Changes (new document) | **Yes** | Fresh list |
| SPA route: list → watch (`pushState`) | Changes, no full load | **No** | New media lifecycle (+ keep old rows) |
| SPA soft-nav between videos | Changes | **No** | Lifecycle bump again |
| Hash fragment only | `#…` changes | **No** | Unchanged lifecycle |
| Player swap, URL unchanged | Same | **No** | Recency / evidence only |
| Close tab / clear detections | — | Yes (tab cleared) | — |

**If you see “URL changed but detections didn’t empty”:** that is expected whenever the change was soft/SPA (or hash-only). Prefer checking whether the **new** stream rose in ranking, not whether the list is empty. Emptying only happens on hard document navigation (or explicit clear / tab close).

---

## Site-type matrix (patterns, not brands)

Expected outcomes assume detection is enabled in settings and the stream is plain `http(s)` (or a playlist the proxy can fetch).

| Site / stream pattern | How it is usually found | Expected cast-sheet behaviour | Typical cast route |
|---|---|---|---|
| Progressive file (`.mp4`, `.webm`, …) with normal CDN | Response `Content-Type` / extension; request headers captured | One (or few) video rows; verified after playability signals if probed | Direct if public; **Via phone** if cookies/Referer/Origin required |
| Classic HLS multivariant (`.m3u8` master) | URL pattern + **body** parse (`body_content_m3u8`) | Master ranked above variants; segment URLs suppressed | Direct or Via phone; quality ladder available after probe |
| HLS media playlist only (no master in network) | Body/URL m3u8 as media role | Single adaptive row; may later grow companion audio | As above |
| Exclusive / bootstrap / demuxed-audio HLS | Shared core builds **synthetic master** | Top dedicated row: **“Synthetic playlist (Via phone)”** with `playlistBody` / `audioUrl` | **Via phone** (synthetic handoff) |
| DASH (`.mpd`) | URL/body MPD | Adaptive row + quality list after parse | Direct / Via phone |
| DOM `<video src>` / `<source>` only | Content script `dom_*` | Appears with weaker evidence until network confirms | Same as progressive if URL is real `http(s)` |
| Blob / `blob:` or `data:` media | Content script ignores; network may never show a file URL | Often **empty or incomplete** sheet | Not castable as raw blob |
| MSE / DRM (Widevine, etc.) | May see license or app traffic; not a clear progressive URL | Sparse or misleading candidates; cast often fails | Out of scope for reliable cast |
| Image-heavy pages | Network/DOM image filters (size floors) | Images tab; not mixed into Videos | N/A |
| Audio-only (podcasts, music) | `audio/` type or extensions | Audio list; Videos badge prefers video when both exist | Direct / Via phone |
| Subtitles (`.vtt` / `.srt`) | Content-type / extension (segments filter must not drop them) | Not primary cast targets; used as related media when present | — |
| Live HLS (rolling playlists) | Body m3u8 + ongoing `lastSeen` polls | Rows stay “fresh”; SPA re-rank may keep an **active previous** live stream high until the new one verifies | Via phone often safer |
| Origin- or cookie-gated CDN | Headers captured on the media request | Cast needs replay headers; Origin may be required upstream | **Via phone** (or remote proxy) |

### Evidence strength (higher wins on updates)

Rough order used when the same URL is reported again:

1. Body-confirmed HLS/DASH (`body_content_m3u8` / `body_content_mpd`)
2. Synthetic HLS master
3. Player-config style signals (when present)
4. Content-type
5. DOM source
6. URL extension / URL pattern
7. URLs scraped from response bodies

Strong body/synthetic detections start as **verified playable**; weaker ones stay **pending** until Kotlin probes the manifest or thumbnail path.

---

## Ranking behaviour (what should sit on top)

For the Videos tab (`buildCastSheetVideos` / `castScore`):

1. **Synthetic handoff** (playlist body and/or demuxed audio / synthetic master) → dedicated top row when any handoff exists.
2. Otherwise sort by approximately:
   - validation (verified ≫ pending ≫ failed)
   - detection evidence
   - adaptive + multi-quality ladder
   - replay headers present
   - thumbnail success
   - **recency** (decays over minutes)
   - **media lifecycle** (newest SPA view gets a large bonus)

Audio and images use simpler “newest first” lists.

**Quick cast** picks the top non-failed video from the same ranking without opening the sheet.

---

## User-visible surface

| UI | Behaviour |
|---|---|
| Toolbar badge | Prefers video count; else audio; else images |
| Cast sheet open | Shows current tab’s list (or forced single item from link-menu / library) |
| Long-press link → “Link Options” | Can force a URL into the sheet; not detector output |
| Explicit clear / tab close | Clears that tab’s detections |

Async after detection (does not require the sheet to be open):

- HLS/DASH quality probe for adaptive URLs
- Thumbnail prefetch for a small number of top candidates

---

## Non-goals / known limitations

- **Not a site compatibility catalog.** Treat failures on a named site as fixtures for a pattern, not as “add Netflix to supported list.”
- **Blob/MSE-only players** often cannot be cast until a real network media URL appears.
- **DRM** is not supported as a first-class path.
- **Detection ≠ playability on every target** (codecs, HLS segment naming, CORS, geo tokens).
- Soft navigation **keeps** old rows; if ranking fails to promote the new view, the sheet can look “stuck” on the previous video even though the URL bar moved.
- View changes with **no** history update never get a lifecycle bump; rows only accumulate.

---

## Debugging checklist (phone)

1. Did the URL change via **full load** or **SPA**? (Sheet empty only expected on full load.)
2. Is the candidate in network as `http(s)` playlist/file, or only as blob/MSE?
3. Does the row have **headers** (Origin/Referer/Cookie) if the CDN is gated?
4. For HLS: is there a **synthetic** top row when audio is demuxed?
5. After SPA nav: did a **new** URL appear, and is it verified? Lifecycle + verification decide order.
6. Confirm detector assets match extension sources (`pnpm build` in `extension/`).

Useful log tags (**debug builds only** for detection payloads):

- `Components` — native/port messages, navigation, routing into `VideoDetector` (`debugDetectorLog`)
- `VideoDetector` — media ingest (`MEDIA DETECTED` for video/audio/image), updates, probes (`debugLog`)

Copy-paste `adb logcat` commands (video/audio/image filters, SPA nav, cast E2E):  
[`mobile/android/docs/logging.md`](../mobile/android/docs/logging.md).

Release builds omit full detector payloads and stream URLs from these paths (see root `AGENTS.md` logging rules).

---

## Code map (for agents)

| Concern | Primary files |
|---|---|
| Capture + native emit | `extension/src/geckoview/background.ts` |
| DOM scan | `extension/src/geckoview/content.ts` |
| SPA vs hard load lifecycle helpers | `extension/src/geckoview/detection-lifecycle.ts` |
| Synthetic HLS / roles | `extension/src/core/synthetic-hls.ts`, `media-candidate.ts` |
| Tab binding + message dispatch | `Components.kt`, `DetectorTabBindingTracker.kt` |
| Store, SPA lifecycle, ranking | `VideoDetector.kt` |
| Sheet list construction | `buildCastSheetVideos` in `VideoDetector.kt`, `CastSheet*.kt` |
| Packaging / proxy | `StreamRouteService.kt`, target-specific loaders |

Cross-project rule: GeckoView detector changes must be checked together with Kotlin consumers and sheet/ranking tests (see root `AGENTS.md`).

---

## Related tests (non-exhaustive)

- `VideoDetectorLifecycleTest` — generation / SPA lifecycle counters
- `BuildCastSheetVideosTest` — synthetic row and ranking shape
- `extension/test/*` — shared core + GeckoView lifecycle unit tests
- `DetectorTabBindingTrackerTest` — extension tab ↔ Kotlin tab binding

Add fixture-style tests for new **patterns** (e.g. “SPA soft-nav keeps rows”, “hard commit clears”) rather than named websites.
