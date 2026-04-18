# Apple TV (tvOS) Receiver — Android Parity Implementation Plan
_Last updated: 2026-04-18_

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

## Phase 0 — Protocol Parity (Swift Package)

**Goal:** A single Swift module that decodes and encodes every message currently flowing between phone and TV, so downstream phases can rely on strongly-typed values instead of dictionary fishing.

### Tasks
1. Create `tv/apple-tv/PlayBridgeProtocol/` as a local Swift Package (SwiftPM `Package.swift`, `Sources/PlayBridgeProtocol/`).
2. Port every subclass of sealed `Command`:
   - `Play`, `PlayContent`, `Playlist`, `QueueAdd`, `PlaylistJump`, `Browser`, `BrowserControl`, `Control`, `Remote`, `Mouse`, `ContextQuery`, `Ping`, `RequestPairing`.
3. Port every payload:
   - `PlayPayload` (all 13 fields including `maxBitrateCapMbps`, `preferredAudioLanguage`, `preferredSubtitleLanguage`, `defaultVideoQuality`, `seriesContext`).
   - `ContentPlayPayload` (all identity + metadata + navigator + playback-preference fields).
   - `SeriesContext`, `SeriesEpisodeRef`.
   - `ControlPayload`, `RemotePayload`, `MousePayload`, `BrowserControlPayload`, `PlaylistPayload`, `QueueAddPayload`, `PlaylistJumpPayload`.
4. Port outbound messages: `StatusMessage`, `ContextMessage`, `PlaylistStatusMessage`, `AuthMessage`, `AuthResponse`.
5. Use `Codable` + custom `CodingKeys` that match Kotlin JSON exactly (snake_case vs camelCase — check `Json` configuration in `Message.kt` serializer).
6. Ship a `PlayBridgeProtocol.decode(_:)` discriminator that reads `type` + `action` and returns a typed `InboundMessage` enum.
7. Add golden-file tests: hardcode 5–10 JSON blobs captured from the Android phone (play, play_content, playlist, queue_add, control, remote) and assert decode → re-encode is idempotent.

### Acceptance
- `swift test` in the package passes with ≥90% coverage of `Message.kt` types.
- Importing `PlayBridgeProtocol` in `PlayBridge TV` compiles cleanly.
- Add a note to `AI_CONTEXT.md`: "**Protocol ripple now includes `tv/apple-tv/PlayBridgeProtocol/Sources/PlayBridgeProtocol/Message.swift`**."

---

## Phase 1 — Server & Pairing Hardening

**Goal:** Replace the current `WebSocketServer.swift` with a split architecture that mirrors Android's `WebSocketServer + ServerService` separation, and bring pairing to parity (minus RFCOMM).

### Tasks
1. Split `WebSocketServer.swift` into three files:
   - `WebSocketServer.swift` — pure NWListener + `InboundMessage` → delegate dispatch. No UI state.
   - `ServerCoordinator.swift` — owns auth state, connection registry, command routing (mirrors `ServerService.kt`). Publishes state for SwiftUI.
   - `PairingStore.swift` — thin actor around `UserDefaults` + Keychain for `authToken`. Keychain because Apple rejects apps that store secrets in plain prefs.
2. Implement the full handshake:
   - `Ping` → `Pong` (outside auth).
   - `RequestPairing` → respond `pairing_ack`, close connection, emit a `connectionAttemptFlow`-equivalent `AsyncStream<PairingAttempt>` that the SwiftUI shell can subscribe to (mirror `ServerService.connectionAttemptFlow` with its 8-second cooldown).
   - `AuthMessage` (token) → validate against stored token.
   - `AuthMessage` (PIN = first 4 chars of token, uppercased) → validate, reply with `AuthResponse{success:true, token: ...}` so the phone can re-auth without re-pairing.
   - Loopback short-circuit: skip auth entirely if `connection.endpoint` resolves to `127.0.0.1` or `::1`.
3. NSD:
   - Registration already uses `NWListener.Service(type: "_playbridge._tcp")`. Extend to include TXT records via `NWTXTRecord`: `uuid` (stable, persisted in PairingStore) and `custom_ip` (only if a manual override is present in prefs).
   - On `.failed(let err)` from the listener, retry up to 4 times with 3s / 6s / 9s backoff, mirroring Android's mDNS race mitigation.
4. Remove Bluetooth fallback from the plan and add a one-line comment in `ServerCoordinator.swift`: `// Bluetooth RFCOMM intentionally omitted — tvOS supports only Wi-Fi + mDNS/BLE. Phone's BluetoothClient will silently fail-over when TV is tvOS.`
5. Unsupported commands: for `Command.Browser`, `BrowserControl`, `Remote`, `Mouse`, reply with `{"type":"command_unsupported","command":"browser","reason":"tvos_receiver"}` so the phone UI can degrade.

### Acceptance
- Phone app pairs via PIN on first launch, reconnects via token thereafter.
- Killing and relaunching the TV app does NOT force re-pairing.
- NSD TXT records visible in `dns-sd -B _playbridge._tcp`.
- Phone receives `command_unsupported` when user taps "Open in browser on TV" from a tvOS-paired session, and phone UI shows a friendly message.

---

## Phase 2 — Engine Abstraction

**Goal:** A single `PlaybackEngine` protocol with `AVPlayerEngine` and `VLCPlayerEngine` implementations, so the server dispatch and overlay UI don't branch on `PlayerMode` in every call site.

### Tasks
1. Define `PlaybackEngine` protocol:
   ```swift
   protocol PlaybackEngine: AnyObject {
       var state: AnyPublisher<PlaybackState, Never> { get }
       var position: AnyPublisher<TimeInterval, Never> { get }
       var duration: TimeInterval { get }
       func load(_ payload: PlayPayload) async throws
       func play(); func pause(); func stop()
       func seek(to: TimeInterval) async
       func setRate(_ rate: Float)
       func setAudioTrack(_ id: String?)
       func setSubtitleTrack(_ id: String?)
       func attachExternalSubtitle(url: URL) async throws
   }
   ```
2. Implement `AVPlayerEngine` wrapping `AVPlayer`. Use `AVPlayerItem.preferredForwardBufferDuration`, `AVAssetResourceLoaderDelegate` for custom headers (the current `AVURLAssetHTTPHeaderFieldsKey` trick is undocumented and breaks on some streams — switch to a loader delegate).
3. Implement `VLCPlayerEngine` wrapping `TVVLCKit`'s `VLCMediaPlayer`. Expose position via a 500ms `DispatchSourceTimer`; VLCKit's delegate callbacks don't fire on every tick.
4. Create `PlayerViewModel` (SwiftUI `@Observable`) that owns the engine, playlist, resume position, and filter state.
5. Replace `ContentView.swift`'s ad-hoc `PlayerCoverView` with a single `PlayerScreen` that takes a `PlayerViewModel` and draws `AVPlayerViewController` OR `VLCPlayerView` based on the engine kind.

### Acceptance
- `ServerCoordinator.handlePlay(_ payload:)` calls `viewModel.load(payload)` and doesn't know which engine is underneath.
- Swapping engines mid-session (e.g., phone sends `playerMode: "internal_vlc"` after an `"internal"` session) works without leaking AVPlayer observers.

---

## Phase 3 — Seeking, Controls & State Sync

**Goal:** Implement the full `Command.Control` action set and start broadcasting `StatusMessage` / `ContextMessage` back to the phone so the phone's remote-control screen stays in sync.

### Tasks
1. Control actions: `play`, `pause`, `stop`, `seek` (with position in payload — check Kotlin `ControlPayload` for the exact seek-position field; today it's a scalar, may be an object `{command:"seek", position: 12345}`).
2. Seek implementation:
   - AVPlayer: `seek(to: CMTime, toleranceBefore: .zero, toleranceAfter: .zero)`.
   - VLCKit: set `mediaPlayer.time = VLCTime(int: ms)` — VLCKit does not accept sub-second positions on some codecs; round to nearest second for VLC.
3. StatusMessage broadcaster: a `Timer.publish(every: 1.0)` inside `ServerCoordinator`, running only while `activeContext == .player`. Sends `StatusMessage(state, position_ms, duration_ms, title)` JSON over the auth'd connection.
4. ContextMessage broadcaster: emit on every transition — `player` when `PlayerScreen` appears, `idle` when dismissed.
5. Handle `Command.ContextQuery`: respond synchronously with current `activeContext`.
6. Buffering / stall state: AVPlayer emits `playbackBufferEmpty` / `playbackLikelyToKeepUp` via KVO — map to `PlaybackState.buffering` so the phone shows a spinner.

### Acceptance
- Phone's remote-control screen shows the real position ticking every second.
- Phone's scrub bar sends `seek` commands that land within ≤500ms on AVPlayer and ≤1s on VLC.
- Killing playback from the phone stops the TV cleanly and emits `context: idle`.

---

## Phase 4 — Subtitles

**Goal:** Feature-match Android's `SubtitleManager.kt`: external SRT/VTT download + cache, embedded track selection, and a local HTTP endpoint so VLC can side-load cached files.

### Tasks
1. Port `SubtitleManager.kt` to `SubtitleManager.swift`:
   - Pure Swift SRT parser (`HH:MM:SS,ms` and `MM:SS.ms`).
   - Pure Swift VTT parser (`HH:MM:SS.ms`).
   - Return `[SubtitleCue]` sorted by start time; lookup via binary search, not linear (Android's linear search is a known perf ceiling).
2. Download pipeline: on `Command.Play` receipt, if `payload.subtitles` is non-nil, download each to `FileManager.default.urls(for: .cachesDirectory).appendingPathComponent("subtitles/sub_\(i).\(ext)")` using URLSession + forwarded headers.
3. Local HTTP endpoint: spin up a tiny `NWListener` on port 8766 (or on the same 8765, multiplexed — pick one in Phase 0) serving `/subtitle/{filename}` so VLCKit can consume the cached file via `addPlaybackSlave(url, type: .subtitle, enforce: true)`.
4. AVPlayer external subtitles: AVFoundation doesn't natively accept sidecar SRT/VTT without an HLS manifest. Three options, in preference order:
   - (a) Build an in-memory HLS manifest that references the subtitle as a `#EXT-X-MEDIA` track, serve it from the local endpoint. Same approach that `SubtitleManager` takes on Android for MPV.
   - (b) Render cues into a SwiftUI overlay driven by `AVPlayer.addPeriodicTimeObserver` (200ms tick, matching Android).
   - Default to (b) — simpler, works offline, no HLS mux gotchas. Revisit (a) if perf becomes an issue.
5. Embedded tracks: `AVPlayer` exposes them via `AVMediaSelectionGroup`; expose in the track-selection UI (Phase 6).

### Acceptance
- Phone sends a `play` with external SRT URL → subtitle appears within 1s of playback start.
- VLC engine plays with embedded SRT from a cached `.mkv` correctly.
- Caption style matches Android: white, drop shadow, 1.2× system size, 12% bottom padding.

---

## Phase 5 — Playlist Queue & Resume

**Goal:** Implement `Command.Playlist`, `QueueAdd`, `PlaylistJump`, broadcast `PlaylistStatusMessage` on every mutation, and persist resume positions across launches.

### Tasks
1. `PlaylistStore` actor on `PlayerViewModel` holding `[PlayPayload]` + `currentIndex`.
2. Dispatch:
   - `Playlist` → replace items, start at `startIndex`, clear pending queue.
   - `QueueAdd` → append, broadcast.
   - `PlaylistJump` → save progress, jump to index, broadcast.
3. Auto-advance on `AVPlayer.currentItem.didPlayToEndTime` and VLC `mediaPlayerStateChanged == .ended`.
4. `PlaylistStatusMessage` broadcast after every mutation (same schema as Android).
5. `ResumeStore`:
   - Android uses DataStore with a 50-item cap. Port as a JSON file in Application Support: `resume_history.json`, same 50-item cap, key by URL.
   - Fields: position_ms, duration_ms, preferred_audio_lang, preferred_subtitle_lang, filter_preset, rate, last_played_at.
   - Restore rule: if resume position is between 5s and (duration − 5s), seek there on load; otherwise start from 0.

### Acceptance
- Phone's playlist widget updates live as user adds/jumps/removes items on TV.
- Reopening the app re-plays the last video from its saved position.
- Queue survives re-pairing (unless phone explicitly clears).

---

## Phase 6 — Filters & Track Selection UI

**Goal:** GPU color filters and a custom track selection dialog matching Android.

### Tasks
1. Color filters via `AVVideoComposition` + a `CIFilter` chain (CIColorMatrix for saturation/contrast, CIColorControls for brightness). Exposed as a preset enum (`NONE`, `SEPIA`, `GRAYSCALE`, `CUSTOM`) + three sliders (brightness offset, contrast multiplier, saturation multiplier) — same defaults as Android.
2. VLCKit filters: `mediaPlayer.adjustFilterEnabled = true` + `.setBrightness / .setContrast / .setSaturation`. Map same 0-centered / 1.0-centered scales.
3. Track selection sheet: SwiftUI List over `AVMediaSelectionGroup.options` (AVPlayer) or `mediaPlayer.audioTrackIndexes / videoSubTitlesIndexes` (VLC). Matching visual style to Android's dialog.
4. Playback speed sheet: 0.5× / 0.75× / 1.0× / 1.25× / 1.5× / 2.0×, persisted per-URL in `ResumeStore`.

### Acceptance
- Filter preview is instantaneous on AVPlayer (no decode re-pipeline).
- VLC filters update without restarting playback.
- Track changes persist to `ResumeStore` on disk.

---

## Phase 7 — Stremio Client & Pre-play Screen

**Goal:** Handle `Command.PlayContent` with `ContentPlayPayload`: show a metadata-rich pre-play screen, resolve streams locally via the addons listed in the payload, and hand off to the engine.

### Tasks
1. Port `StremioClient.kt` to `StremioClient.swift`:
   - `resolveStreamsByContentId(addonBaseUrls, addonNames, contentId, contentType, season, episode, qualityPreference, preferredAddonBaseUrl) async throws -> [Stream]`.
   - Concurrent fetches with `TaskGroup`, return first-ready + merge rest.
   - `StremioStreamItem` decoder.
   - Filter helpers: regex for season packs (`s\(season)`), keyword list for extras (featurette, deleted scenes, interview, etc.).
   - Quality scoring: 1080p > 720p > 480p, plus release-group tiebreaker.
   - Disk cache at `Caches/stremio_streams_cache.json` with configurable TTL (default 0 = off, like Android).
2. `PrePlayScreen.swift`:
   - SwiftUI screen with backdrop, poster, logo, title, year, rating, runtime, genres, cast, synopsis.
   - Coil3 → use `AsyncImage` or `Nuke` (prefer Nuke for tvOS focus-engine stability).
   - Stream list under "Available Sources" with addon name, title, size.
   - Auto-pick: if `defaultVideoQuality` is set and `forcePicker == false`, start a 3s countdown, silently select best-matching stream, then push to `PlayerScreen`.
3. `ServerCoordinator.handlePlayContent(payload:)`:
   - Push `PrePlayScreen` onto the navigation stack.
   - On user selection (or auto-pick), convert the chosen `StremioStreamItem` into a `PlayPayload` (preserving headers, subtitles, metadata) and call the engine path from Phase 2.
4. `SeriesContext` handling: pass `allEpisodes` to the pre-play screen so a "Next Episode" tile appears for series content.

### Acceptance
- Phone sends `play_content` for a Stremio movie → TV shows pre-play screen within 200ms, streams resolve within 2s (network-dependent), playback starts.
- `forcePicker: false` + `defaultVideoQuality: "1080p"` auto-picks the best match with a visible 3s countdown.
- Cache hit skips the network round-trip entirely.

---

## Phase 8 — In-Player Info Overlay

**Goal:** Port Android's `PlayerControlsManager` overlay so the user sees title, S/E info, elapsed/remaining, current playlist index, and track/filter shortcuts.

### Tasks
1. SwiftUI overlay that fades in on remote tap / D-pad up, auto-hides after 5s.
2. Top row: title, season/episode (`S02 E03 – "Episode Title"`), stream info (resolution, codec, bitrate — pull from `AVPlayerItem.tracks[].assetTrack.formatDescriptions` or VLCKit's `videoSize`).
3. Bottom row: scrub bar, elapsed / remaining, playlist index (`3 / 12`), focus-navigable buttons for track / filter / speed.
4. "Next up" tile for series content (uses `SeriesContext.allEpisodes`), similar to Apple TV+ UX — appears 60s before end.

### Acceptance
- Visual parity with Android within ±10% spacing.
- Remote D-pad navigation is focus-engine-clean (no focus traps).
- Overlay never blocks playback state updates from reaching the phone.

---

## Phase 9 — App Store Readiness

**Goal:** Land the tvOS target in a state that can ship to TestFlight and eventually the App Store.

### Tasks
1. **App Transport Security:** Current `Info.plist` will be rejected if it contains `NSAllowsArbitraryLoads`. Use `NSExceptionDomains` keyed by private IP ranges, matching the proposed Android fix in `network_security_config.xml`.
2. **Privacy manifest (`PrivacyInfo.xcprivacy`):** declare `NSPrivacyAccessedAPITypes` for `UserDefaults` and `FileTimestamp`, plus any required reason codes. As of 2026-04, this is mandatory for tvOS.
3. **Privacy policy URL:** hosted page (same one the Android app will need for its own Play Store blocker). Link in Info.plist and App Store Connect.
4. **CI:** add `xcodebuild -scheme "PlayBridge TV" -destination "platform=tvOS Simulator,name=Apple TV"` step to whatever CI the Android side uses. Archive + export to an `.ipa` for TestFlight on tags.
5. **Entitlements audit:** remove anything unused. Currently zero entitlements are declared — keep it that way unless Phase 1's Bonjour needs `NSBonjourServices` (it does — add `_playbridge._tcp` to `Info.plist`'s `NSBonjourServices`).
6. **App Store metadata:** data-safety equivalent ("Privacy Nutrition Label"); fill out "Network Activity" and nothing else.

### Acceptance
- `xcodebuild archive` succeeds with no warnings.
- TestFlight submission passes App Store Connect automated review.
- Bonjour registration works on a fresh simulator (tests that `NSBonjourServices` is declared).

---

## Rollout Strategy

**Vertical slices, not horizontal sweeps.** Each phase should land as its own PR and should leave the app in a shippable (to TestFlight) state. Recommended sequence if the engineer has limited time:

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
