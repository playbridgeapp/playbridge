# Design Plan: Mixed-Media Receiver Playlists

| Field | Value |
|---|---|
| **Date** | 2026-08-21 |
| **Status** | MVP implemented; on-device transition validation pending |
| **Initial scope** | Android phone sender, Android TV receiver, Desktop receiver |
| **Deferred** | Apple TV, CLI presentation parity, animated-image and advanced slideshow features |
| **Related** | PlayBridge WSS protocol, receiver capabilities, playlists, player controls, history/resume |

## Overview

PlayBridge playlists should support video, audio, and image items in any order. The media kind belongs to each `PlayPayload`, not to `PlaylistPayload`, because a single queue may contain different kinds:

```json
{
  "action": "playlist",
  "payload": {
    "items": [
      {
        "url": "https://media.example/intro.mp4",
        "contentType": "video/mp4",
        "mediaKind": "video"
      },
      {
        "url": "https://media.example/song.mp3",
        "contentType": "audio/mpeg",
        "mediaKind": "audio"
      },
      {
        "url": "https://media.example/photo.jpg",
        "contentType": "image/jpeg",
        "mediaKind": "image",
        "displayDurationMs": 10000
      }
    ],
    "startIndex": 0
  }
}
```

The receiver keeps one playlist session and one queue cursor while changing the presentation used by the current item. A transition such as video → image → audio must not reconnect the sender, replace the playlist, lose queue additions, or briefly return to the receiver home screen.

## Goals

- Support `video`, `audio`, and `image` on individual playlist items.
- Support mixed-kind initial playlists and mixed-kind `queue_add` operations.
- Keep playlist navigation above individual playback engines and presentation widgets.
- Give audio and images dedicated receiver presentations instead of showing an empty video surface.
- Preserve current video behavior and old sender/receiver compatibility.
- Let the Android phone discover receiver support and send explicit media kinds.
- Keep status, remote controls, queue UI, history, and error handling synchronized during kind transitions.
- Preserve request headers and receiver-side network policy for every media kind.

## Non-goals for the first release

- Apple TV receiver implementation.
- A protocol-breaking replacement for `VisualMetadata`.
- Lyrics, audio visualizers, EXIF information panels, image editing, or casting arbitrary documents.
- Animated GIF/WebP/AVIF guarantees. They may render as a static frame until separately implemented and advertised.
- Configurable image transitions, Ken Burns effects, or synchronized multi-device slideshows.
- Mixed PlayBridge queues on DLNA or Google Cast targets. This plan covers the native PlayBridge receiver protocol.

## Terminology and ownership

Keep these concepts separate:

| Concept | Purpose | Examples |
|---|---|---|
| `mediaKind` | Presentation and broad media semantics | `video`, `audio`, `image` |
| `contentType` | MIME/transport hint | `video/mp4`, `audio/mpeg`, `image/jpeg` |
| `playerMode` | Decoder engine preference for timed media | `exo`, `mpv`, `internal_mpv` |
| Presentation options | Kind-specific behavior | image display duration |

`playerMode` does not choose video, music, or image presentation. It chooses a decoder only when the current kind needs one.

The playlist/session coordinator owns:

- queue contents and current index;
- next, previous, jump, and queue-add behavior;
- current declared and resolved media kind;
- transition generation and cancellation;
- status and playlist-status identity;
- end-of-item advancement.

A decoder or image loader owns only the active item's rendering and local lifecycle.

## Protocol contract

### `PlayPayload`

Add optional fields after the existing protobuf fields:

```yaml
mediaKind:
  type: [string, 'null']
  enum: [video, audio, image, null]

displayDurationMs:
  type: [integer, 'null']
  minimum: 0
  description: Image display time. Positive values enable timed advancement; absent or zero waits for explicit navigation.
```

Use optional strings in protobuf/generated models for forward-compatible rollout:

```proto
optional string media_kind = 17 [json_name = "mediaKind"];
optional int64 display_duration_ms = 18 [json_name = "displayDurationMs"];
```

Absence means “infer.” Do not add `mediaKind` to `PlaylistPayload`, and do not add a root default in the first release. This avoids precedence rules and keeps every queue item self-describing.

### Metadata

For the first music UI, extend `VisualMetadata` additively rather than renaming it:

```yaml
artist: string | null
album: string | null
albumArtist: string | null
artworkUrl: string | null
trackNumber: integer | null
```

Receivers fall back to the existing fields in this order:

- music artwork: `artworkUrl`, then `posterUrl`, then `backdropUrl`;
- music title: item `title`, then metadata `title`, then a safe filename/host label;
- image caption: item `title`, then metadata `title`.

A future protocol revision may introduce a general `MediaMetadata` model, but that rename is not required for this feature.

### Receiver capabilities

Add an optional `mediaKinds` array to authenticated receiver capability responses:

```json
{
  "players": ["exo", "mpv"],
  "browsers": ["webview"],
  "mediaKinds": ["video", "audio", "image"]
}
```

Initial capability policy:

- Updated Android TV advertises `video`, `audio`, and `image`.
- Updated Desktop advertises `video`, `audio`, and `image`.
- A receiver that omits `mediaKinds` is treated by the Android phone as a legacy video receiver.
- Unknown capability values are ignored.
- More detailed features such as animated images or zoom should not be implied by `image`; add separate feature capabilities later if needed.

Desktop capability advertisement passes through `cast/receiver`, the receiver-runtime JSON config, the Dart wrapper, and the checked-in Desktop native library. Adding a defaulted JSON configuration field is compatible, but the Desktop native library must still be rebuilt. No receiver-runtime ABI version bump is needed unless the C function signatures or incompatible event/config semantics change.

### Status and playlist echo

Add optional `mediaKind` to `status` and each `playlist_status.items[]` entry. The value in receiver output is the receiver's resolved kind, not an unvalidated sender string.

Examples:

```json
{
  "type": "status",
  "state": "playing",
  "position": 4210,
  "duration": 180000,
  "title": "Song",
  "mediaKind": "audio"
}
```

```json
{
  "index": 2,
  "title": "Photo",
  "mediaKind": "image"
}
```

Keep active context as `player` for all three kinds. Do not add `music` or `image` contexts; context identifies the receiver subsystem, while `mediaKind` identifies the item presentation.

### Image status semantics

- Static image without a positive `displayDurationMs`: `state = paused`, `position = 0`, `duration = 0`.
- Timed image: `state = playing`, `duration = displayDurationMs`, and `position` is elapsed display time.
- Pausing a timed image freezes its timer and reports `paused`.
- `play` resumes a paused timer when a positive duration exists.
- `seek_to` is unsupported for images in the first release.
- `next`, `previous`, `playlist_jump`, and `stop` work for images.
- When the timer reaches its duration, the common queue coordinator advances exactly as an audio/video end event would.

The existing `player_settings.isSeekable` and availability flags should drive remote controls. Image items report seek unavailable and no track/quality/scaling features.

## Media-kind resolution

Resolve each item independently and retain both the declared and resolved values for diagnostics without logging sensitive URLs.

Resolution precedence:

1. Valid explicit `mediaKind` from the item.
2. Unambiguous MIME prefix: `video/`, `audio/`, or `image/`.
3. Known URL/path extension after removing query and fragment.
4. Receiver probe or decoder information when available.
5. Backward-compatible fallback to `video`.

Special cases:

- HLS and DASH application MIME types are not inherently audio or video. Explicit sender classification wins; otherwise use `video` for compatibility with existing casts.
- Existing phone/library paths sometimes put semantic values such as `movie` or `series` in `contentType`. These values contain no MIME prefix and must not be treated as media kinds. This plan does not require cleaning all of those legacy values before rollout.
- An unsupported or malformed explicit value is ignored and normal inference continues.
- If probing contradicts a weak inferred fallback, the receiver may update its resolved kind before presentation is revealed. An explicit supported kind should not be silently changed solely because a server reports `application/octet-stream`.

Create focused resolver implementations with shared test vectors on Android and Desktop so precedence remains behaviorally identical.

## Common transition behavior

Every current-item change follows one transition pipeline:

1. Increment the media generation and cancel stale loads, timers, callbacks, and pending seeks.
2. Save progress/history for the outgoing item according to its kind.
3. Resolve the incoming item's media kind.
4. Enter `buffering` for audio/video or an internal loading state for images.
5. Cover the old presentation before changing its source.
6. Stop or suspend resources that cannot be reused.
7. Prepare the incoming decoder or image loader.
8. Atomically reveal the new presentation.
9. Broadcast status, tracks/settings availability, and playlist status for the new item.
10. Accept end, failure, navigation, or replacement callbacks only when their generation still matches.

The host must never launch a separate receiver session solely because the next item has a different kind.

### Transition matrix

| From | To | Behavior |
|---|---|---|
| Video | Video | Reuse or switch the selected media renderer; preserve current video behavior. |
| Video | Audio | Reuse Exo/MPV decoding where possible, cover/hide video output, and reveal music UI. |
| Video | Image | Stop/clear timed media and audio focus; keep the host alive; load dedicated image presentation. |
| Audio | Video | Keep decoder if compatible, attach/reveal video output only after the first valid frame. |
| Audio | Image | Stop timed media and audio focus, then reveal image presentation. |
| Image | Video/Audio | Cancel image request/timer, prepare timed-media renderer, then swap presentation. |
| Image | Image | Reuse image presentation, cancel the old request, and reset the timer. |

A black or themed transition curtain prevents stale video frames, artwork, or images from flashing between items.

## Android TV receiver plan

### Architecture

Keep `PlayerHostActivity` as the permanent receiver shell and `PlaybackCoordinator` as queue/index owner. Extend the host from a video-only shell into a media-presentation shell:

```text
PlayerHostActivity
 ├── PlaybackCoordinator
 ├── TimedMediaPresentation
 │    ├── Exo renderer service
 │    └── MPV renderer service
 ├── MusicPresentation (Compose UI over Exo/MPV audio)
 └── ImagePresentation (Compose + Coil)
```

Do not reintroduce separate runtime activities for audio or images.

### Implementation tasks

1. Add a Kotlin `MediaKind` model and pure resolver near the player coordinator.
2. Extend `PlaybackCoordinator.Host.loadItem` integration so `PlayerHostActivity.startPlaylistItem()` dispatches by resolved kind.
3. Track current kind and transition generation in `PlayerHostActivity`.
4. Generalize video-only host fields and naming where they affect all timed media, without performing unrelated broad renames.
5. For video:
   - preserve Exo/MPV renderer selection and fallback behavior;
   - preserve subtitles, quality, scaling, pre-play, thumbnail capture, and progress.
6. For audio:
   - use the selected Exo/MPV renderer for decode and timing;
   - keep the `SurfaceView` attached if required by renderer stability but fully cover it;
   - render title, artist, album, artwork, timeline, play/pause, previous/next, and queue entry point in Compose;
   - hide video-only controls and continue exposing audio track selection where meaningful;
   - do not show the movie-style pre-play screen—the music presentation is the initial presentation.
7. For images:
   - stop and clear the active renderer media item and release audio focus;
   - use Coil 3, already present in the TV project, for bounded image loading;
   - pass approved headers through the image request or consume the receiver-prepared/proxied URL;
   - use contain/fit as the first-release default;
   - implement manual next/previous and optional `displayDurationMs` timer;
   - cancel requests and timers by generation;
   - show a safe error state with Skip/Back rather than ending the entire queue automatically without user feedback.
8. Update keep-screen-on policy:
   - video/audio playing or buffering: current behavior;
   - static image: keep awake while the image presentation is active;
   - timed image: keep awake while showing/paused;
   - release on stop/session finish.
9. Make progress/history kind-aware:
   - video/audio retain position and completion behavior;
   - static images record viewing/history but no resume position;
   - image transitions do not attempt frame thumbnail capture because the image/artwork is already available.
10. Make still-watching kind-aware:
    - track playing video/audio as today;
    - track timed image slideshows only while their timer is running;
    - static image viewing does not accumulate unattended playback time.
11. Broadcast resolved kind in status and playlist status and clear stale tracks/player-settings when entering image mode.
12. Extend `TvCapabilities` and auth responses with `mediaKinds`.

### Android TV file areas

Primary areas expected to change:

- `tv/android/player/app/src/main/java/com/playbridge/player/player/PlaybackCoordinator.kt`
- `tv/android/player/app/src/main/java/com/playbridge/player/player/PlayerHostActivity.kt`
- `tv/android/player/app/src/main/java/com/playbridge/player/player/PlayerLauncher.kt`
- `tv/android/player/app/src/main/java/com/playbridge/player/server/ServerService.kt`
- `tv/android/player/app/src/main/java/com/playbridge/player/server/TvCapabilities.kt`
- TV player Compose controls/presentation files under `ui/player/`
- history/progress and keep-awake helpers where behavior is currently video-specific

Prefer new focused files such as `MediaKindResolver.kt`, `MusicPresentation.kt`, and `ImagePresentation.kt` rather than expanding `PlayerHostActivity` with all rendering details.

## Desktop receiver plan

### Architecture

Rename the UI concept from “showing video” to “showing media” while preserving the MPV texture lifecycle. Split session coordination from the active presentation:

```text
PlayerController / media session owner
 ├── queue, index, proxy preparation, generation
 ├── MPV timed-media driver (video + audio)
 └── image presentation driver/timer

MediaPresentationSurface
 ├── VideoPresentationSurface
 ├── MusicPresentationSurface
 └── ImagePresentationSurface
```

The existing `PlaybackSurface` can remain the video-specific child. A new parent selects the correct child from `PlayerController.currentMediaKind`.

### Implementation tasks

1. Add Dart `MediaKind` and resolver with the same precedence/test vectors as Android.
2. Add declared/resolved media kind and `displayDurationMs` to `QueueItem`.
3. Preserve those fields through:
   - protocol parsing in `receiver_server.dart`;
   - proxy/request preparation;
   - queue add, jump, history replay, and playlist materialization;
   - playlist-status echo.
4. Generalize `PlayerController` so queue/index state remains valid when the current item does not use MPV.
5. Keep MPV as the timed-media driver for both video and audio:
   - video uses the existing `PlaybackSurface`;
   - audio keeps MPV alive but presents dedicated Flutter music UI;
   - avoid recreating the video controller on every video/audio transition.
6. Add an image driver/controller that owns:
   - authenticated image fetch or receiver-prepared URL;
   - bounded decode and error state;
   - timer position/state for `displayDurationMs`;
   - play/pause/stop and completion callback into the common queue coordinator;
   - generation cancellation.
7. Add `MediaPresentationSurface` and update the main shell:
   - replace `_showingVideo` semantics with `_showingMedia`;
   - keep the MPV texture mounted/offstage to avoid current Linux texture regressions;
   - show music or image UI above/alongside that retained texture;
   - only apply video auto-hide, stats overlay, and double-click fullscreen behavior where appropriate;
   - keep playlist drawer and common previous/next controls available for every kind.
8. Make `_PlayerControlsBar` capability-driven:
   - video: existing controls;
   - audio: play/pause, seek, volume, tracks, previous/next, queue;
   - static image: previous/next, queue, stop/fullscreen; no seek or volume;
   - timed image: add play/pause and elapsed/total timer, but no seek in v1.
9. Update media session integration so OS now-playing metadata reports audio title/artwork correctly and does not expose meaningless seek actions for images.
10. Make history/resume and still-watching follow the same policy as Android TV.
11. Advertise `mediaKinds` through:
    - `cast/receiver` configuration/auth response;
    - `cast/ffi/src/receiver_runtime.rs` runtime config;
    - `packages/playbridge_cast_core_dart/lib/src/receiver_runtime.dart`;
    - `desktop/lib/receiver_server.dart`.
12. Rebuild checked-in Desktop receiver libraries after the Rust/runtime configuration change.

### Desktop file areas

Primary areas expected to change:

- `desktop/lib/player_engine.dart`
- `desktop/lib/player_controller.dart`
- `desktop/lib/playback_surface.dart`
- new Desktop music/image/media-presentation widgets
- `desktop/lib/main.dart`
- `desktop/lib/playback_osd.dart` and controls/playlist widgets as needed
- `desktop/lib/media_session_bridge.dart`
- `desktop/lib/history_store.dart`
- `desktop/lib/receiver_server.dart`
- Desktop focused tests under `desktop/test/`

## Android phone sender plan

The phone already distinguishes detected video, audio, and images through `DetectedMediaKind` and separate cast-sheet lists. The initial sender work should propagate that classification into native PlayBridge payloads rather than inventing another detector.

### Data and capability tasks

1. Add media kind to the phone's transport-agnostic `MediaItem`.
2. Add one reusable sender-side mapping from `DetectedMediaKind`/MIME/URL to protocol `mediaKind`.
3. Propagate explicit kind through native PlayBridge `PlayPayload` construction, including:
   - generic `NativeCastTarget.load`;
   - cast-sheet video/audio/image actions;
   - browser page-cast and linked-page playlist items;
   - local files and collections;
   - `queue_add` and copied/decorated payloads.
4. Preserve per-item kinds when creating or modifying playlists. Never derive a root playlist kind from the first item.
5. Parse and persist receiver `mediaKinds` alongside `players` and `browsers` in:
   - `WebSocketClient.TvCapabilities`;
   - `TvDevice`/credentials persistence and connection merging;
   - receiver endpoint models where capability data is retained.
6. Capability policy in UI/routing:
   - empty/absent `mediaKinds`: legacy video-only native receiver;
   - supported kind: enable native cast;
   - unsupported kind: disable that target/action with an explanatory message rather than sending and failing later;
   - do not apply PlayBridge `mediaKinds` to DLNA/Google Cast capability decisions.
7. Update now-playing/remote state to read resolved `mediaKind` from receiver status:
   - audio: music labels and audio-appropriate controls;
   - image: hide seek/volume/track controls unless receiver settings say otherwise; keep next/previous/stop;
   - legacy status without kind: retain existing video UI.
8. Parse media kind from playlist-status items so mixed queue rows can show the right icon and the phone can restore the current control layout after reconnect/context resync.
9. Keep existing content detection ranking separate by kind; video quick-cast must not begin auto-selecting images or standalone HLS audio.
10. Add `displayDurationMs` only to explicit image/slideshow creation flows. A single image cast defaults to manual viewing.

### Existing `contentType` cleanup boundary

Some Android phone library code currently uses `content_type = "movie"` or `"series"`. Do not overload `mediaKind` with those values. During implementation:

- set `mediaKind = "video"` for those payloads;
- retain the existing semantic `contentType` temporarily for compatibility;
- file a separate cleanup to move movie/series identity to metadata if desired.

This prevents a large unrelated migration from blocking mixed-media support.

### Android phone file areas

Primary areas expected to change:

- `mobile/android/app/src/main/java/com/playbridge/sender/cast/CastTarget.kt`
- `mobile/android/app/src/main/java/com/playbridge/sender/cast/NativeCastTarget.kt`
- `mobile/android/app/src/main/java/com/playbridge/sender/cast/VideoDetector.kt`
- cast-sheet payload construction in `CastSheet.kt` and `CastSheetComponents.kt`
- browser and linked-page payload construction
- `mobile/android/app/src/main/java/com/playbridge/sender/connection/WebSocketClient.kt`
- `ConnectionViewModel.kt`, `TvDevice.kt`, and capability persistence/merge helpers
- phone remote/playlist state parsing and UI

Because there are many direct `PlayPayload(...)` constructors, use a central classifier/decorator where possible and add an audit test or focused search checklist to prevent missing a send path.

## Network and security requirements

Audio and image support must not bypass protections already applied to video:

- Preserve item headers only for the intended media resource and approved derived requests.
- Do not log authenticated media URLs or sensitive header values outside the existing debug-only exception.
- Page-cast and linked-page image/audio requests must continue through receiver-side proxy/network policy when required.
- Validate redirects and private-address access using the existing policy; do not let Coil or Desktop image loading become an unrestricted second HTTP client path.
- Bound image response bytes, decoded dimensions, and memory use before displaying untrusted images.
- Cancel image requests when the playlist item changes or credentials are revoked.
- Do not persist authenticated artwork/image URLs in release logs or unsafe diagnostics.

## Error behavior

- Failure to load one mixed-media item does not corrupt the queue or receiver context.
- Display a typed, kind-appropriate error with Retry, Skip/Next when available, and Stop/Back.
- Do not automatically skip immediately on every error; the user must be able to see what failed.
- A stale failure from the outgoing renderer/image request is ignored by generation.
- Unsupported explicit kind produces a typed `unsupported_media_kind` error if inference cannot recover.
- On receiver capability mismatch discovered after send, return an error rather than treating an image as video indefinitely.

A future protocol change may add structured receiver errors. The first implementation can use the current error/status surfaces while keeping stable internal reason codes.

## Rollout order

Implement in receiver-first order so the phone does not advertise actions to receivers that cannot present them:

### Phase 1 — Contract and compatibility

- Update `protocol/asyncapi.yaml` first.
- Update protobuf and regenerate Kotlin, Dart, and Swift models.
- Add per-item media kind, image duration, metadata fields, capability fields, and optional status echoes.
- Add shared contract fixtures for mixed playlists and legacy omitted-kind payloads.
- Update Rust receiver capability transport needed by Desktop.

### Phase 2 — Android TV reference receiver

- Implement resolver and media-aware host dispatch.
- Implement audio presentation using Exo/MPV.
- Implement static/timed image presentation using Coil.
- Add mixed transition, control, status, history, and stale-callback tests.
- Advertise all three media kinds.

### Phase 3 — Desktop receiver

- Implement resolver, queue model propagation, presentation selection, music UI, and image driver.
- Generalize the shell and controls without unmounting the MPV video texture.
- Advertise all three kinds through the Rust runtime and rebuild libraries.
- Add Flutter tests for every transition direction.

### Phase 4 — Android phone enablement

- Parse/persist capabilities.
- Propagate explicit per-item kinds through native send paths.
- Enable audio/image native casting only for receivers advertising support.
- Adapt remote and mixed-playlist UI to resolved kind.
- Run end-to-end mixed playlists against Android TV and Desktop.

### Phase 5 — Hardening

- Exercise large/corrupt images, redirects, authenticated requests, disconnect/reconnect, queue-add during transitions, renderer fallback, and rapid navigation.
- Confirm old phone → new receiver, new phone → old receiver, and mixed-version reconnect behavior.
- Add performance/memory checks before enabling animated images or advanced slideshow behavior.

## Test plan

### Protocol and compatibility

- Mixed playlist round-trip retains each item's kind and image duration.
- `queue_add` retains kind independently from the active item.
- Omitted kind remains valid and infers video for existing video payloads.
- Unknown kind is ignored for inference rather than crashing generated consumers.
- `movie`/`series` legacy `contentType` values still resolve as video by fallback/explicit sender kind.
- Optional capability/status fields remain compatible with old consumers.

### Resolver vectors on Android and Desktop

Cover at least:

- explicit image with `application/octet-stream` and extensionless URL;
- explicit audio HLS;
- video HLS without explicit kind;
- normal `video/mp4`, `audio/mpeg`, and `image/jpeg`;
- query-string and fragment extensions;
- misleading image MIME on an explicit video item;
- invalid/unknown explicit value;
- legacy `movie` and `series` values;
- URL with no useful MIME or extension.

### Mixed transition matrix

Automate all nine kind transitions where practical:

- video → video/audio/image;
- audio → video/audio/image;
- image → video/audio/image.

For every transition verify:

- index changes once;
- old callback/timer cannot complete the new item;
- queue contents and queue additions survive;
- correct presentation is visible with no stale frame;
- status and playlist status report the new resolved kind;
- controls and player-settings availability match the new kind;
- history/resume behavior matches policy;
- next/end advances once, not twice.

### Image-specific

- Static image waits for explicit navigation.
- Timed image plays, pauses, resumes, and advances at duration.
- Rapid next/previous cancels old image loads.
- Oversized/corrupt/unsupported image produces bounded failure UI.
- Header-bearing and proxied image requests preserve authorization without leaking credentials.
- Stop during load returns to idle and does not reveal the image later.

### Audio-specific

- Audio does not expose a stale/frozen video frame.
- Timeline, play/pause, seek, previous/next, volume, and supported track controls work.
- Video-only controls disappear.
- Audio → video restores video output and first-frame reveal correctly.
- Renderer switch during audio does not reset the queue.

### Phone-specific

- Receiver media capabilities persist and are replaced when connecting to a different TV.
- Legacy receiver is treated as video-only.
- Audio/image targets are enabled only for supporting native receivers.
- Detected kind reaches every native payload path and every item of a mixed playlist.
- Remote controls change when receiver status changes kind.
- Reconnect/context resync restores current mixed-playlist kind and controls.

## Verification commands

Protocol:

```bash
cd protocol
ruby scripts/check-spec.rb
./generate.sh
./generate.sh --check
```

Rust/Desktop receiver runtime changes, from repository root unless noted:

```bash
cargo fmt --all -- --check
cargo test -p playbridge-cast-receiver -p playbridge-cast-core-ffi --locked
cargo clippy -p playbridge-cast-receiver -p playbridge-cast-core-ffi --all-targets --locked -- -D warnings
sh cast/build-desktop.sh
cd packages/playbridge_cast_core_dart && dart analyze --fatal-infos
cd ../../desktop && flutter test && flutter analyze
```

Android phone, from `mobile/android/` on macOS:

```bash
zsh -c "source ~/.zshrc && ./gradlew :app:testFossDebugUnitTest"
zsh -c "source ~/.zshrc && ./gradlew :app:assembleDebug"
zsh -c "source ~/.zshrc && ./gradlew :app:lintFossDebug"
```

Android TV, from `tv/android/` on macOS:

```bash
zsh -c "source ~/.zshrc && ./gradlew test"
zsh -c "source ~/.zshrc && ./gradlew :player:app:assembleDebug"
zsh -c "source ~/.zshrc && ./gradlew lint"
```

Validate generated/shared Kotlin changes through both Android Gradle roots.

## End-to-end acceptance scenarios

1. Android phone sends video → audio → static image → video to Android TV. Every item uses the correct presentation, manual image Next continues the queue, and the sender remote updates controls at each transition.
2. Android phone sends image → timed image → audio to Desktop. The first image waits, the second auto-advances, MPV starts audio without showing a stale video texture, and status remains synchronized.
3. While audio is playing, the phone appends an image and video with `queue_add`; both retain their own kind and play in order.
4. During an image load, the phone jumps to video; the old image completion cannot cover the video.
5. The phone reconnects while a receiver is on an image item and reconstructs the mixed queue and image controls from context/status/playlist echoes.
6. A new phone connects to an old receiver: video remains available, while audio/image native actions are shown as unsupported rather than miscast.
7. An old phone sends ordinary video to new Android TV and Desktop receivers with no behavior regression.

## Completion criteria

The initial feature is complete when Android TV and Desktop advertise and correctly render all three media kinds, the Android phone sends per-item kinds only to capable receivers, every mixed transition preserves one playlist session, and compatibility/security/verification scenarios above pass. Apple TV and CLI can then adopt the same contract without changing mixed-playlist semantics.
