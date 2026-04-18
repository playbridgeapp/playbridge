# Apple TV (tvOS) Receiver — Android Parity Implementation Plan
_Last updated: 2026-04-18 (status re-audit)_

**Legend:** ✅ Done · 🟡 Partial · ❌ Missing · ⏭ Out of scope

## Status at a glance (2026-04-18)

| Phase | Status | What's left |
|---|---|---|
| **0 — Protocol Parity** | ✅ Done | — |
| **1 — Server & Pairing** | 🟡 ~70% | Loopback auth skip, NSD TXT records, retry backoff, `command_unsupported` reply |
| **2 — Engine Abstraction** | 🟡 ~90% | Swap `AVURLAssetHTTPHeaderFieldsKey` for `AVAssetResourceLoaderDelegate` |
| **3 — Seeking & State Sync** | 🟡 ~95% | Gate 1 Hz status timer to `activeContext == .player` |
| **4 — Subtitles** | 🟡 ~50% | VTT parser, binary-search lookup, explicit cache path, local HTTP endpoint for VLC, embedded-track UI |
| **5 — Playlist & Resume** | ✅ Done | — |
| **6 — Filters & Tracks** | 🟡 ~40% | Custom filter slider UI, track-selection sheet, playback-speed sheet |
| **7 — Stremio + Pre-play** | 🟡 ~95% | Extras keyword/regex filter (season packs, featurettes) |
| **8 — In-Player Overlay** | 🟡 ~60% | Stream format details (resolution/codec/bitrate), "Next up" tile 60s before end |
| **9 — App Store Readiness** | 🟡 ~25% | ATS `NSExceptionDomains`, privacy policy URL, CI pipeline, App Store Connect metadata |

Roughly 6.5 of 10 phases complete. The critical playback path (protocol, engines, seek, playlist, Stremio) is solid; remaining work is hardening + UI polish + submission prep.

---

## Overview

The tvOS `PlayBridge TV` app is currently a 665-line SwiftUI prototype: a raw `NWListener` WebSocket, a 4-char PIN auth flow, and two thin playback surfaces (`AVPlayerViewController` + a `TVVLCKit` controller). It handles only `Command.Play` and a stripped-down `Command.Control`. Everything else — seeking, subtitles, state sync, playlists, Stremio pre-play, metadata UI — is stubbed or absent.

The Android TV receiver (`tv/player/…`) dispatches **13 command types**, broadcasts `StatusMessage` / `ContextMessage` / `PlaylistStatusMessage` back to the phone, persists resume positions, resolves Stremio streams locally, and drives a pre-play metadata screen. This document is the step-by-step plan to close that gap on tvOS, scoped to **player only** (browser is out of scope), targeting **AVPlayer + TVVLCKit** as the two engines, with **receiver-side Stremio resolution**.

### Guiding principles
1. **Protocol is law.** `protocol/src/main/kotlin/com/playbridge/protocol/Message.kt` (Kotlin) is the source of truth. The Swift side mirrors it as a single `PlayBridgeProtocol` Swift package — no ad-hoc JSON shapes.
2. **Mirror Android file boundaries when sensible.** `ServerService.kt` → `ServerCoordinator.swift`, `ExoPlayerActivity.kt` → `AVPlayerScreen.swift`, `SubtitleManager.kt` → `SubtitleManager.swift`, `StremioClient.kt` → `StremioClient.swift`. When future protocol edits ripple, the matching filename makes the port obvious.
3. **Do NOT port the dual-engine browser**, the uBlock adblocker, or the GeckoView surface. tvOS has no GeckoView and Apple rejects embedded general-purpose browsers. `Command.Browser`, `Command.BrowserControl`, `Command.Remote`, `Command.Mouse` should parse as valid commands but respond with a typed `UnsupportedOnTvOS` error rather than silently dropping.
4. **One protocol ripple = three edits.** When `Message.kt` changes, update `ServerService.kt`, `ConnectionViewModel.kt`, AND `PlayBridgeProtocol.swift` + `ServerCoordinator.swift`. Add this to `AI_CONTEXT.md`'s ripple warning in Phase 0.
5. **tvOS platform reality:** tvOS does not support classic Bluetooth RFCOMM — the Android `BluetoothServer` fallback is not portable. Wi-Fi + mDNS is the only transport. This is a scope deviation, not a TODO.

---

## Feature Delta Matrix

| Feature | Android TV | Apple TV today | Target phase |
|---|---|---|---|
| Protocol `Command` types handled | 13 | 2 (`play`, `control` partial) | Phase 0 |
| Strongly-typed payloads | sealed classes | ad-hoc `[String: Any]` | Phase 0 |
| Pairing (PIN + token + re-auth) | full, w/ cooldown | basic, no cooldown, no attempt-flow | Phase 1 |
| NSD TXT records (`uuid`, `custom_ip`) | yes, with retry/backoff | none | Phase 1 |
| Bluetooth RFCOMM fallback | yes | **N/A on tvOS** | — (documented out) |
| Engines | ExoPlayer + VLC + MPV | AVPlayer + VLC | Phase 2 |
| Seek / scrub | full, sub-second | **stubbed** | Phase 3 |
| `StatusMessage` progress broadcast (1 Hz) | yes | none | Phase 3 |
| `ContextMessage` active-context broadcast | yes | none | Phase 3 |
| External SRT/VTT download + cache | yes | **missing** | Phase 4 |
| Embedded track selection | yes | native AVPlayer only | Phase 4 |
| Subtitle HTTP endpoint for VLC | yes | none | Phase 4 |
| Playlist queue (`Playlist`, `QueueAdd`, `PlaylistJump`) | yes | **missing** | Phase 5 |
| `PlaylistStatusMessage` broadcast | yes | none | Phase 5 |
| Resume-position persistence | HistoryStore (DataStore-backed) | none | Phase 5 |
| Color filters (brightness/contrast/saturation) | GlEffect | none | Phase 6 |
| Track selection dialog (custom) | yes | native only | Phase 6 |
| Stremio stream resolver + cache | `StremioClient.kt` | **missing** | Phase 7 |
| Pre-play metadata screen | `PrePlayScreen.kt` | **missing** | Phase 7 |
| In-player info overlay (S/E, elapsed, next-up) | yes | none | Phase 8 |
| App Store submission readiness | n/a | not started | Phase 9 |

---

## Phase 0 — Protocol Parity (Swift Package)  ✅ Done

**Goal:** A single Swift module that decodes and encodes every message currently flowing between phone and TV, so downstream phases can rely on strongly-typed values instead of dictionary fishing.

### Tasks
1. ✅ Create `tv/apple-tv/PlayBridgeProtocol/` as a local Swift Package (SwiftPM `Package.swift`, `Sources/PlayBridgeProtocol/`).
2. ✅ Port every subclass of sealed `Command`:
   - `Play`, `PlayContent`, `Playlist`, `QueueAdd`, `PlaylistJump`, `Browser`, `BrowserControl`, `Control`, `Remote`, `Mouse`, `ContextQuery`, `Ping`, `RequestPairing`.
3. ✅ Port every payload:
   - `PlayPayload` (all 13 fields including `maxBitrateCapMbps`, `preferredAudioLanguage`, `preferredSubtitleLanguage`, `defaultVideoQuality`, `seriesContext`).
   - `ContentPlayPayload` (all identity + metadata + navigator + playback-preference fields).
   - `SeriesContext`, `SeriesEpisodeRef`.
   - `ControlPayload`, `RemotePayload`, `MousePayload`, `BrowserControlPayload`, `PlaylistPayload`, `QueueAddPayload`, `PlaylistJumpPayload`.
4. ✅ Port outbound messages: `StatusMessage`, `ContextMessage`, `PlaylistStatusMessage`, `AuthMessage`, `AuthResponse`.
5. ✅ Use `Codable` + custom `CodingKeys` that match Kotlin JSON exactly (snake_case vs camelCase — check `Json` configuration in `Message.kt` serializer).
6. ✅ Ship a `PlayBridgeProtocol.decode(_:)` discriminator that reads `type` + `action` and returns a typed `InboundMessage` enum.
7. ✅ Add golden-file tests: hardcode 5–10 JSON blobs captured from the Android phone (play, play_content, playlist, queue_add, control, remote) and assert decode → re-encode is idempotent. _(6 blobs present in `PlayBridgeProtocolTests.swift` — sufficient; add more opportunistically when new protocol types land.)_

### Acceptance
- `swift test` in the package passes with ≥90% coverage of `Message.kt` types.
- Importing `PlayBridgeProtocol` in `PlayBridge TV` compiles cleanly.
- Add a note to `AI_CONTEXT.md`: "**Protocol ripple now includes `tv/apple-tv/PlayBridgeProtocol/Sources/PlayBridgeProtocol/Message.swift`**."

---

## Phase 1 — Server & Pairing Hardening  🟡 Partial

**Goal:** Replace the current `WebSocketServer.swift` with a split architecture that mirrors Android's `WebSocketServer + ServerService` separation, and bring pairing to parity (minus RFCOMM).

### Tasks
1. 🟡 Split `WebSocketServer.swift` into three files:
   - ✅ `WebSocketServer.swift` — NWListener + receive loop + auth handshake.
   - ✅ `ServerCoordinator.swift` — owns routing state, publishes state for SwiftUI.
   - ❌ `PairingStore.swift` — **not extracted**; `authToken` is still stored directly in `UserDefaults` inside `WebSocketServer.swift`. Plan calls for Keychain + dedicated actor. Apple reviewers typically accept UserDefaults for a random pairing PIN, but moving to Keychain is cleaner and eliminates the risk of iCloud sync leaking the token across devices.
2. 🟡 Full handshake:
   - ✅ `Ping` → `Pong` outside auth.
   - ✅ `RequestPairing` → `pairing_ack`.
   - ✅ `AuthMessage` token validation.
   - ✅ `AuthMessage` PIN (first 4 chars, uppercased) → `AuthResponse{success:true, token:…}`.
   - ❌ **Loopback short-circuit missing.** No `127.0.0.1` / `::1` check in `WebSocketServer.handleAuthentication()`. Needed if future tvOS-side tooling ever talks to the receiver locally.
   - ❌ **`connectionAttemptFlow` equivalent missing.** Android wakes `PairingScreen` on unauthed connections with an 8-second cooldown; tvOS has no pairing UI hook for incoming attempts yet.
3. ❌ NSD TXT records + retry backoff:
   - `NSBonjourServices = _playbridge._tcp` is declared in `project.pbxproj` (correct).
   - `NWListener.Service(type: "_playbridge._tcp")` registers bare — no `NWTXTRecord` with `uuid` / `custom_ip` attached.
   - `.failed` handler calls `stop()` once with no retry loop; Android retries 4× with 3s/6s/9s backoff to survive mDNS races during server restart.
4. ⏭ Bluetooth RFCOMM — documented out. Add the one-line comment to `ServerCoordinator.swift`: `// Bluetooth RFCOMM intentionally omitted — tvOS supports only Wi-Fi + mDNS/BLE. Phone's BluetoothClient will silently fail-over when TV is tvOS.`
5. ❌ Unsupported-command reply missing. `Command.Browser`, `BrowserControl`, `Remote`, `Mouse` currently fall through the `switch` default (silent log). Phone has no way to know the receiver is tvOS; should reply `{"type":"command_unsupported","command":"browser","reason":"tvos_receiver"}` so the phone can grey out the "Open on TV" affordance.

### Acceptance
- Phone app pairs via PIN on first launch, reconnects via token thereafter.
- Killing and relaunching the TV app does NOT force re-pairing.
- NSD TXT records visible in `dns-sd -B _playbridge._tcp`.
- Phone receives `command_unsupported` when user taps "Open in browser on TV" from a tvOS-paired session, and phone UI shows a friendly message.

---

## Phase 2 — Engine Abstraction  🟡 Mostly done

**Goal:** A single `PlaybackEngine` protocol with `AVPlayerEngine` and `VLCPlayerEngine` implementations, so the server dispatch and overlay UI don't branch on `PlayerMode` in every call site.

### Tasks
1. ✅ `PlaybackEngine` protocol defined in `PlaybackEngine.swift` with `load/play/pause/stop/seek/setRate/setAudioTrack/setSubtitleTrack/attachExternalSubtitle/setFilter` — superset of the plan's sketch.
2. 🟡 `AVPlayerEngine` implemented, but **still uses `AVURLAssetHTTPHeaderFieldsKey`** (see `AVPlayerEngine.swift:85` with a `// TODO` referencing this phase). Switch to an `AVAssetResourceLoaderDelegate`-based approach: this undocumented options key is silently dropped on redirects and some HLS variants, which will show up as "works for most streams, mysteriously 401s for some Debrid links."
3. ✅ `VLCPlayerEngine` wraps `TVVLCKit`'s `VLCMediaPlayer`. Position via 500 ms `DispatchSourceTimer`.
4. ✅ `PlayerViewModel` owns engine + playlist + resume + filter state.
5. ✅ `PlayerScreen` replaces the old `PlayerCoverView`; branches on engine kind only at the view layer.

### Acceptance
- `ServerCoordinator.handlePlay(_ payload:)` calls `viewModel.load(payload)` and doesn't know which engine is underneath.
- Swapping engines mid-session (e.g., phone sends `playerMode: "internal_vlc"` after an `"internal"` session) works without leaking AVPlayer observers.

---

## Phase 3 — Seeking, Controls & State Sync  🟡 Nearly done

**Goal:** Implement the full `Command.Control` action set and start broadcasting `StatusMessage` / `ContextMessage` back to the phone so the phone's remote-control screen stays in sync.

### Tasks
1. ✅ Control actions (`play`, `pause`, `stop`, `seek`) dispatched in `ServerCoordinator`.
2. ✅ Seek implementation — AVPlayer uses zero-tolerance seek; VLC rounds to ms via `VLCTime`.
3. 🟡 StatusMessage timer runs at 1 Hz in `ServerCoordinator.setupStatusTimer()`, but is **not gated** to `activeContext == .player` — it currently emits even when the player screen isn't on. Low-impact today, but wastes BT/Wi-Fi cycles and will spam the phone's status log. Gate it.
4. ✅ ContextMessage emitted via `sendContext()`.
5. ✅ `Command.ContextQuery` handled.
6. ✅ Buffering/stall state: AVPlayer's `timeControlStatus == .waitingToPlayAtSpecifiedRate` and VLC's `.buffering` both map to `PlaybackState.buffering`.

### Acceptance
- Phone's remote-control screen shows the real position ticking every second.
- Phone's scrub bar sends `seek` commands that land within ≤500ms on AVPlayer and ≤1s on VLC.
- Killing playback from the phone stops the TV cleanly and emits `context: idle`.

---

## Phase 4 — Subtitles  🟡 Half done

**Goal:** Feature-match Android's `SubtitleManager.kt`: external SRT/VTT download + cache, embedded track selection, and a local HTTP endpoint so VLC can side-load cached files.

### Tasks
1. 🟡 `SubtitleManager.swift` ships a working SRT parser (`HH:MM:SS,ms`) but:
   - ❌ **No VTT parser.** Only an inline comment hint on line ~117. Many Stremio sub packs are VTT.
   - ❌ **Linear scan, not binary search.** Fine for a 1-hour film, but 4-hour content + 1000+ cues × 5 fps overlay refresh will hurt.
2. 🟡 Download pipeline exists in `downloadSubtitle(url:headers:)`; saves to Caches. But:
   - ❌ Not writing to the explicit `Caches/subtitles/sub_{i}.{ext}` path the plan called out — cache files are anonymous temp files and hard to inspect/clear.
   - ❌ Extension detection is SRT-only; fails silently for VTT/ASS.
3. ❌ **Local HTTP endpoint missing.** No `/subtitle/{filename}` server. VLC engine can't `addPlaybackSlave(url:…)` a cached file because there's no URL to slave. This is the single biggest subtitle gap for VLC.
4. 🟡 AVPlayer external subtitles — option (b) implemented:
   - ✅ SwiftUI `SubtitleOverlay` in `PlayerScreen.swift` renders cues driven by periodic time observer.
   - ❌ Overlay styling minimal; doesn't yet match Android's drop-shadow / 1.2× / 12% bottom padding spec.
5. 🟡 Embedded tracks: `setSubtitleTrack()` is stubbed in the engine protocol, but no track-selection UI is wired up yet. _(Tracked under Phase 6.3 below.)_

### Acceptance
- Phone sends a `play` with external SRT URL → subtitle appears within 1s of playback start.
- VLC engine plays with embedded SRT from a cached `.mkv` correctly.
- Caption style matches Android: white, drop shadow, 1.2× system size, 12% bottom padding.

---

## Phase 5 — Playlist Queue & Resume  ✅ Done

**Goal:** Implement `Command.Playlist`, `QueueAdd`, `PlaylistJump`, broadcast `PlaylistStatusMessage` on every mutation, and persist resume positions across launches.

### Tasks
1. ✅ `playlistItems: [PlayPayload]` + `currentIndex: Int` on `PlayerViewModel`.
2. ✅ Dispatch: `playPlaylist()`, `queueAdd()`, `playlistJump()` handle the three commands.
3. ✅ Auto-advance via `AVPlayerItemDidPlayToEndTime` and VLC ended-state → `advanceToNext()`.
4. ✅ `broadcastPlaylistStatus()` emits after every mutation and on periodic tick.
5. ✅ `ResumeStore`: JSON file in Application Support (`resume_history.json`), 50-item LRU cap, full field set, 5s/5s-before-end restore rule.

### Acceptance
- Phone's playlist widget updates live as user adds/jumps/removes items on TV.
- Reopening the app re-plays the last video from its saved position.
- Queue survives re-pairing (unless phone explicitly clears).

---

## Phase 6 — Filters & Track Selection UI  🟡 Engine side done, UI missing

**Goal:** GPU color filters and a custom track selection dialog matching Android.

### Tasks
1. 🟡 Color-filter engine: `ColorFilter.swift` defines `FilterPreset` (NONE, SEPIA, GRAYSCALE, CUSTOM) + `ColorFilterSettings`. `AVPlayerEngine` applies via `CIColorControls` on a `AVVideoComposition`. **Preset application works; custom sliders (brightness/contrast/saturation) have no UI yet** — `applyFilterPreset()` only handles the preset enum.
2. ✅ VLCKit filter path wired via `adjustFilter*` keys.
3. ❌ **Track selection sheet not implemented.** `setAudioTrack(_:)` / `setSubtitleTrack(_:)` are engine hooks with no UI consumer. Needed: a focus-navigable SwiftUI list over `AVMediaSelectionGroup.options` (AVPlayer) and VLC's `audioTrackIndexes` / `videoSubTitlesIndexes`.
4. ❌ **Playback speed sheet not implemented.** `playbackRate` property exists on the view model but no 0.5× → 2.0× picker UI, and the rate is not persisted through `ResumeStore` yet.

### Acceptance
- Filter preview is instantaneous on AVPlayer (no decode re-pipeline).
- VLC filters update without restarting playback.
- Track changes persist to `ResumeStore` on disk.

---

## Phase 7 — Stremio Client & Pre-play Screen  🟡 ~95% done

**Goal:** Handle `Command.PlayContent` with `ContentPlayPayload`: show a metadata-rich pre-play screen, resolve streams locally via the addons listed in the payload, and hand off to the engine.

### Tasks
1. 🟡 `StremioClient.swift`:
   - ✅ `resolveStreamsByContentId(...)` with all arguments including `preferredAddonBaseUrl` / `preferredAddonName`.
   - ✅ Concurrent fetches via `TaskGroup`.
   - ✅ `StremioStreamItem` decoder.
   - ❌ **Extras filter not yet implemented.** Android's `isSeasonPack` regex (`s{season}`) and extras keyword list (featurette, deleted scenes, interview, etc.) are not ported. Without this, the stream list leaks episode packs and behind-the-scenes clips into the user's pick list.
   - ✅ Quality scoring via `QualityRanker.swift` (1080p > 720p > 480p + release-group tiebreaker).
   - ✅ Disk cache at `Caches/stremio_streams_cache.json` with configurable TTL.
2. ✅ `PrePlayScreen.swift` shows backdrop, poster, title, year, rating, runtime, genres, cast, synopsis; stream picker with addon name / title / size; 3s auto-pick countdown when `defaultVideoQuality` is set and `forcePicker == false`. _(Uses `AsyncImage`; Nuke upgrade is optional polish.)_
3. ✅ `ServerCoordinator.handlePlayContent(...)` pushes `.prePlay(payload)` and `selectStream()` converts the chosen stream → `PlayPayload` → engine.
4. ✅ `SeriesContext.allEpisodes` threaded through the pre-play payload and surfaced for series content.

### Acceptance
- Phone sends `play_content` for a Stremio movie → TV shows pre-play screen within 200ms, streams resolve within 2s (network-dependent), playback starts.
- `forcePicker: false` + `defaultVideoQuality: "1080p"` auto-picks the best match with a visible 3s countdown.
- Cache hit skips the network round-trip entirely.

---

## Phase 8 — In-Player Info Overlay  🟡 Skeleton shipped, polish pending

**Goal:** Port Android's `PlayerControlsManager` overlay so the user sees title, S/E info, elapsed/remaining, current playlist index, and track/filter shortcuts.

### Tasks
1. ✅ `PlayerOverlayView.swift` fades in on tap, auto-hides after 5 s.
2. 🟡 Top row: title ✅, engine type shown ✅. **Season/episode format (`S02 E03 – "Episode Title"`) and stream info (resolution/codec/bitrate) not displayed.** Data is available from `PlayPayload.seriesContext` and `AVPlayerItem.tracks[*].assetTrack.formatDescriptions` — just needs to be wired up.
3. 🟡 Bottom row: scrub bar ✅, elapsed/remaining ✅, playlist index (`X / Y`) ✅, ±10 s skip buttons ✅. **Track / filter / speed shortcut buttons missing** (blocked by Phase 6 UI sheets).
4. ❌ **"Next up" tile missing.** Should appear 60 s before end for series content using `SeriesContext.allEpisodes`, à la Apple TV+. Requires the pre-play metadata to be carried through to the player session (currently `SeriesContext` is available in the `PlayPayload` but not consumed by `PlayerOverlayView`).

### Acceptance
- Visual parity with Android within ±10% spacing.
- Remote D-pad navigation is focus-engine-clean (no focus traps).
- Overlay never blocks playback state updates from reaching the phone.

---

## Phase 9 — App Store Readiness  🟡 Entitlements + Privacy manifest only

**Goal:** Land the tvOS target in a state that can ship to TestFlight and eventually the App Store.

### Tasks
1. ❌ **ATS `NSExceptionDomains` not configured.** `Info.plist` hasn't been audited; any `NSAllowsArbitraryLoads` will be auto-rejected. Needs private-IP range exceptions matching the Android `network_security_config.xml` proposal.
2. ✅ **Privacy manifest:** `PrivacyInfo.xcprivacy` declares `UserDefaults` (CA92.1) and `FileTimestamp` (C617.1) with valid reason codes. `NSPrivacyTracking = false`.
3. ❌ **Privacy policy URL:** no hosted page yet. Needs to be created once (shared with the Android Play Store submission blocker) and linked in `Info.plist` + App Store Connect.
4. ❌ **CI pipeline missing.** No `xcodebuild -scheme "PlayBridge TV" -destination "platform=tvOS Simulator,name=Apple TV"` step; no `.ipa` export for TestFlight on tag.
5. ✅ **Entitlements audit:** zero custom entitlements. `NSBonjourServices = _playbridge._tcp` set via the target's Info tab (confirmed in `project.pbxproj`).
6. ❌ **App Store Connect metadata:** Privacy Nutrition Label ("Network Activity" only) not submitted.

### Acceptance
- `xcodebuild archive` succeeds with no warnings.
- TestFlight submission passes App Store Connect automated review.
- Bonjour registration works on a fresh simulator (tests that `NSBonjourServices` is declared).

---

## Remaining Work — Prioritized Punch List (2026-04-18)

Given the status above, here is the concrete work left to close parity, ordered by user impact:

### P0 — Blocks correct behavior with existing features
1. **Phase 4.1 — VTT parser** in `SubtitleManager.swift`. Without it, any Stremio sub pack delivered as `.vtt` silently fails.
2. **Phase 4.3 — Local HTTP endpoint for VLC subtitles.** VLC engine currently can't load any external sub because there's no URL for `addPlaybackSlave(...)` to slave.
3. **Phase 2.2 — Replace `AVURLAssetHTTPHeaderFieldsKey` with an `AVAssetResourceLoaderDelegate`.** Undocumented key drops headers on redirects; presents as "some Debrid links just 401."
4. **Phase 1.5 — `command_unsupported` reply for `browser` / `browser_control` / `remote` / `mouse`.** Without it the phone has no way to degrade its UI for tvOS-paired sessions.

### P1 — Parity with Android user-visible features
5. **Phase 6.3 — Track selection sheet** (audio / subtitle). Blocks both embedded-subtitle UX (Phase 4.5) and multi-audio content.
6. **Phase 6.4 — Playback speed sheet** + `ResumeStore` persistence.
7. **Phase 8.2 — Season/episode label + stream format details** in the overlay top row.
8. **Phase 7.1 — Stremio extras filter** (season-pack regex + extras keyword list). Today's pre-play list can include featurettes and episode packs.

### P2 — Hardening & polish
9. **Phase 1.1 — Extract `PairingStore` with Keychain.** Low behavioral risk today but cleaner for App Store review.
10. **Phase 1.3 — NSD TXT records + retry-with-backoff.** Without TXT the phone's discovery can't distinguish multiple TVs by UUID; without retry a fast listener restart can lose advertisement.
11. **Phase 3.3 — Gate the 1 Hz status timer to `activeContext == .player`.** Low-impact but cleaner.
12. **Phase 4.1 — Binary-search cue lookup.** Only matters for long content with large cue counts.
13. **Phase 4.4 — Overlay styling parity** (drop-shadow / 1.2× / 12% padding).
14. **Phase 8.4 — "Next up" tile** for series content.

### P3 — App Store submission
15. **Phase 9.1 — ATS `NSExceptionDomains`.**
16. **Phase 9.3 — Privacy policy URL.** Shared with the Android Play Store blocker.
17. **Phase 9.4 — CI pipeline** (`xcodebuild archive` + TestFlight upload on tag).
18. **Phase 9.6 — App Store Connect Privacy Nutrition Label.**

### Suggested sequencing
- **Week 1:** P0 block (Phase 4 VTT + HTTP endpoint, Phase 2 loader delegate, Phase 1 unsupported reply). Lands real correctness wins.
- **Week 2:** P1 UI sheets (track / speed / overlay). Parity visible to the user.
- **Week 3:** P2 hardening (PairingStore, NSD TXT, overlay polish, Next-up tile).
- **Week 4:** P3 App Store submission pipeline and TestFlight build.

### Originally planned sequence (for reference, now superseded)

- **Week 1-2:** Phase 0 + Phase 1. No user-visible change but unblocks everything else.
- **Week 3:** Phase 2 + Phase 3. First user-visible parity win (seek + status sync).
- **Week 4:** Phase 4. Subtitles unlock a huge class of content that was unplayable.
- **Week 5:** Phase 5 + Phase 6. Polish + playlist.
- **Week 6-7:** Phase 7. Stremio is the biggest single phase and should have its own dedicated time.
- **Week 8:** Phase 8 + Phase 9. Overlay polish + App Store prep.

**Defer / explicitly out of scope:**
- MPV engine (Android has it via `MpvPlayerActivity`; tvOS port is possible via `mpv-ios` but not worth the XcFramework complexity for a two-engine parity target).
- GeckoView / SystemWebView dual-engine browser (platform-hostile on tvOS).
- Bluetooth RFCOMM fallback (platform-impossible on tvOS).
- Extension parity (Firefox V2 desktop extension is platform-specific).

**Protocol ripple reminder:** Any change to `Message.kt` during this work must be mirrored in FOUR places from Phase 0 onward:
1. `tv/player/app/src/main/java/com/playbridge/player/server/ServerService.kt`
2. `phone/app/src/main/java/com/playbridge/sender/connection/ConnectionViewModel.kt`
3. `tv/apple-tv/PlayBridgeProtocol/Sources/PlayBridgeProtocol/Message.swift`
4. `tv/apple-tv/PlayBridge TV/PlayBridge TV/ServerCoordinator.swift`

Update `AI_CONTEXT.md` in Phase 0 so this is documented where future contributors will see it.
