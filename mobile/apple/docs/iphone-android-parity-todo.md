# iPhone → Android parity plan

Status: planning only; no implementation tasks completed by this document.
Baseline: 2026-09-06, repository commit `1765536b`.

## Goal

Make the iPhone app recognizably the same PlayBridge product as the Android phone app: comparable navigation, visual hierarchy, browser-to-cast workflow, remote controls, and useful everyday features. Preserve native iOS interactions and explicitly document features that are deferred or use a different implementation.

The priorities and deferrals below are proposed defaults, not user-approved permanent omissions. This is a source-based audit, not a device screenshot comparison or a claim that existing features pass runtime tests. The graph tool returned an empty graph, so the baseline was established through focused source inspection. Recheck the current branch before implementing; Android is a moving reference.

## Instructions for the implementing agent

1. Read root `AGENTS.md`, applicable local instructions, and the Apple and Android specialist skills. Load protocol/Rust skills if a task crosses those boundaries.
2. Read this plan and the referenced implementation before editing. Extend working iPhone features instead of rebuilding them based on the stale Apple README.
3. Pick one unchecked task with satisfied dependencies. Record its status, scope, and evidence in the work log below. Split large tasks into reviewable changes.
4. Use Android phone behavior as the product reference, SwiftUI/WebKit as the implementation. Do not modify Android merely to make comparison easier.
5. Do not mark a task complete because its screen compiles. Meet its acceptance criteria, document any device-only verification still pending, and update the feature matrix.
6. Preserve Keychain pairing records, TLS/SPKI validation, existing local files and saved data. Do not persist credentials or authenticated URLs in logs, snapshots, or fixtures.
7. Keep protocol and ABI changes with one designated writer. Follow all consumer/build ripple checks in `AGENTS.md`; an iPhone UI task is not authorization to redesign the wire protocol.

### Path shorthand

All paths below are repository-relative unless prefixed with these aliases:

- **I** = `mobile/apple/PlayBridge Phone/PlayBridge Phone/`
- **A** = `mobile/android/app/src/main/java/com/playbridge/sender/`
- **D** = `mobile/apple/docs/`

## Current feature matrix

“Present” means source implementation exists, not full parity or verified runtime success.

| Area | iPhone baseline evidence | Work needed |
|---|---|---|
| Shell/dashboard | `I/ContentView.swift`, `I/UI/NavigationViewModel.swift`, `I/UI/DashboardScreen.swift`: browser-first custom navigation, six cards, Cast History placeholder | Layout, branding, settings access, return navigation, remove forced-exit flow |
| Appearance | `I/UI/Theme.swift`: fixed dark colors; screen-local system typography | Android uses Poppins and Dark/AMOLED/Light themes; centralize and align tokens |
| Browser/tabs | `I/UI/BrowserScreen.swift`, `I/Browser/BrowserTab.swift`, `I/UI/TabsScreen.swift`: navigation, multi-tab, desktop mode, system find | Compare toolbar/menu states, restoration, errors, contextual actions |
| History/bookmarks | `I/UI/BrowserLibraryScreens.swift`, `I/Data/BrowserDataStore.swift` | Refine organization/actions; browser history is distinct from cast history |
| Ad blocking | `I/Browser/ContentBlocker.swift`, `I/UI/AdblockSettingsSheet.swift`: lists, custom lists, element/source blocking | Verify semantics and failure states; not missing wholesale |
| Detection/cast sheet | `I/Browser/DetectionScript.swift`, `I/Browser/VideoDetector.swift`, `I/UI/CastSheet.swift`: streams, subtitles, quality, Play/Queue/Browse | Compare ranking, navigation lifecycle, async selection safety, authenticated streams |
| Local playback | `I/UI/CastSheet.swift`: `playOnPhone` creates an AVPlayer | Basic playback exists; dedicated player experience is narrower than Android |
| PlayBridge connection | `I/Network/ConnectionViewModel.swift`, `I/UI/ConnectionScreen.swift`: saved devices, manual connection, code pairing and reconnect states | Validate permission/recovery UX and consistency across entry points |
| Remote | `I/UI/RemoteControlScreen.swift`, `I/UI/RemoteControlView.swift`: transport, D-pad, touchpad, status and playlist jump | Android has richer seeking, volume, text input, tracks, browser controls |
| Phone files | `I/UI/PhoneFilesScreen.swift`, `I/Network/LocalFileServer.swift`: Photos/Files selection and casting | Validate lifecycle, seeking/ranges, retries, selection feedback |
| IPTV | `I/Data/IptvStore.swift`, `I/UI/IptvScreen.swift`, `I/UI/IptvDetailScreen.swift`: URL/file import, refresh, groups and casting | Compare search/sort and action states; large-list/error handling |
| Collections | `I/Data/CollectionsStore.swift`, `I/UI/CollectionDetailScreen.swift`: persistence, add/remove/reorder helpers | Audit UI reachability, editing, playlist semantics and cast entry points |
| Cast history | Dashboard presents “Coming Soon”; no route in `AppScreen` | Implement dedicated history with privacy rules |
| Settings | `I/UI/BrowserLibraryScreens.swift`: search engine and data clearing | Add app settings hub, appearance, supported streaming/TV settings and backup |
| Google Cast | `I/Network/GoogleCastSession.swift`, Apple README: optional adapter | Bonjour discovery, Rust sessions, routing and remote UI implemented; hardware validation pending |
| Downloads/library/debrid/mirroring | No corresponding routes in current iPhone `AppScreen`; Android has separate feature trees | Separate milestones or explicit deferrals below |
| Extensions | `I/UI/MenuSheet.swift`: “Coming Soon” entry | Do not promise GeckoView extensions in WKWebView |

## P0 — Visual baseline and coherent app shell

### PAR-01 — Capture the actual comparison baseline

- [ ] Capture matching Android/iPhone screens with identical sample data: browser home and loaded page, menu, tabs, dashboard, connection, detected streams and selected stream, remote idle/playing, files, IPTV, collections, history/bookmarks and settings.
- [ ] Store sanitized images and a short discrepancy table under `D/parity-baseline/`, recording device, viewport, theme, font scale, app commit and state. Separate measured differences from guesses.
- [ ] Record layout metrics: outer padding, toolbar/card heights, radii, icon sizes, typography, surface/accent colors and sheet detents. List native exceptions explicitly.

**Accept:** each core screen has a reproducible reference and a concrete discrepancy list. If hardware is unavailable, leave captures pending and label source-derived proposals as unverified.

### PAR-02 — Shared visual tokens and appearance settings

References: `A/ui/theme/{Color,Type,Theme}.kt`, `A/settings/AppearanceSettingsScreen.kt`; `I/UI/Theme.swift`, `I/PlayBridgePhoneApp.swift` and all iPhone UI consumers. Depends on PAR-01 for measured alignment.

- [ ] Introduce semantic color, typography, spacing, radius and icon-size tokens; replace scattered hardcoded values on core screens.
- [ ] Match Android's Poppins brand typography where the bundled font's license permits reuse; register fonts correctly and preserve Dynamic Type. Document any deliberate system-font exception.
- [ ] Implement persisted Dark, AMOLED and Light appearance with immediate updates. Compare Android's current resolved palette rather than assuming the existing violet palette is current.
- [ ] Apply tokens to sheets, forms, navigation chrome and disabled/error states, not just dashboard backgrounds.

**Accept:** theme changes retain screen, tab and cast state; relaunch restores preference; core screens remain legible at large text sizes in every supported theme.

### PAR-03 — Dashboard, navigation and common components

References: `A/ui/DashboardScreen.kt`, `A/ui/DashboardOnboarding.kt`, `A/browser/AppNavHost.kt`; `I/UI/{DashboardScreen,NavigationViewModel}.swift`, `I/ContentView.swift`. Depends on PAR-02.

- [ ] Match supported dashboard card hierarchy, logo treatment, density, status pill and settings entry. Reflow omitted cards without blank slots or fake active features.
- [ ] Standardize headers, back/close controls, status badges, row actions, empty states and sheet presentation across the app.
- [ ] Define a predictable return path for dashboard → feature → remote → back and browser → cast sheet → device setup → back. Retain browser tab, scroll and selection state.
- [ ] Replace the current `exit(0)` action with explicit disconnect/stop-serving actions where useful; normal app dismissal remains system-owned. Explain effects on phone-hosted streams accurately.
- [ ] Replace indefinite “Coming Soon” traps with hidden entries or clearly scoped unavailable explanations. Remove the Extensions promise unless a concrete supported feature exists.

**Accept:** every visible action works or states a specific limitation; back/close returns to the correct origin without losing work; no forced process exit; dashboard comparison is recorded.

## P1 — Complete the everyday browser → cast → remote loop

### PAR-04 — Browser chrome and library usability

References: `A/browser/{BrowserToolbar,HomeScreen,MenuSheet,TabsScreen,SiteInfoSheet,LinkContextMenu,ClearDataSheet}.kt`, `A/history/`; `I/UI/{BrowserScreen,MenuSheet,TabsScreen,BrowserLibraryScreens}.swift`, `I/Browser/{BrowserStore,BrowserTab}.swift`, `I/Data/BrowserDataStore.swift`. Depends on PAR-03.

- [ ] Align address bar, connection/cast indicators, loading/stop/reload, toolbar order and menu grouping against screenshots.
- [ ] Audit and complete tab selection/closing/new-tab actions, restoration and long titles. Preserve independent per-tab browser/detector state.
- [ ] Add actionable navigation failures, retry and site information. Existing navigation failure delegates only update `pageLoaded`; verify actual user-visible handling.
- [ ] Compare bookmark/history search, edit/delete/clear, URL opening, home shortcuts, sharing and link context actions; implement missing supported actions.
- [ ] Preserve the working native find navigator and desktop mode; make their active state and dismissal obvious.

**Accept:** scripted multi-tab browse/back/reload/bookmark/find/relaunch sequence works; failed pages show recovery; no tab displays another tab's detections.

### PAR-05 — Detection and cast-sheet correctness plus visual parity

References: `A/cast/{CastSheet,CastSheetComponents,VideoDetector}.kt`, `docs/android-video-detection.md`, `extension/src/core/`; `I/Browser/{DetectionScript,VideoDetector,HLSParser,DASHParser,StreamHTTP}.swift`, `I/UI/CastSheet.swift`.

- [ ] Align stream row hierarchy, badges, selection, preview, quality, subtitle attachment, receiver selection and Play/Queue/Browse actions.
- [ ] Compare deduplication, ranking and metadata enrichment against Android fixtures. Keep SPA detections when appropriate; clear on new document; reject late messages from previous page generations.
- [ ] Guard/cancel thumbnail and manifest tasks when selection changes or the sheet closes. Current `selectVideo` tasks assign results without checking that their video is still selected.
- [ ] Verify effective User-Agent, Referer/Origin, required cookies/headers and selected-quality propagation through preview and cast. Record WKWebView limits instead of claiming complete request interception.
- [ ] Keep unsupported local previews castable when the receiver supports the stream. Show manifest/preview/send errors and retry instead of silently dismissing failures.
- [ ] Test disconnected cast → choose/pair receiver → resume the same intent without double-sending or losing stream/subtitles. Respect receiver capabilities for Queue/Browse/player choices.

**Accept:** fixtures cover HLS master, DASH, MP4, subtitles, duplicate detections, SPA/hard navigation and rapid A→B selection; real receiver plays the chosen stream/quality; stale results cannot overwrite current selection.

### PAR-06 — Connection and pairing polish

References: `A/ui/ConnectionScreen.kt`, `A/connection/`; `I/UI/{ConnectionScreen,DeviceConnectionSheet}.swift`, `I/Network/{BonjourBrowser,ConnectionViewModel,ConnectionCoordinator}.swift`.

- [ ] Unify discovery, saved-device, connecting and connected visuals across full screen and sheets.
- [ ] Make denied local-network permission, empty scan, offline saved receiver, manual address validation, wrong code, cancellation, timeout and changed-pin recovery understandable.
- [ ] Verify reconnection after IP changes and foregrounding; avoid duplicate discovery/connect attempts when views reappear.

**Accept:** successful pairing/relaunch reconnect and all listed failure paths are exercised; changed pins never silently reconnect; recovery preserves unrelated saved devices.

### PAR-07 — Rich remote controls

References: `A/cast/RemoteControlScreen.kt`, `A/cast/TVSettingsScreen.kt`; `I/UI/{RemoteControlScreen,RemoteControlView}.swift`, `I/Network/ConnectionCoordinator.swift`.

- [ ] Match now-playing hierarchy, art/title, live/VOD states, play/pause and seek presentation.
- [ ] Expose supported seek-to, volume/mute, loop, audio/subtitle selection, subtitle attachment, playlist navigation and player settings using existing contracts.
- [ ] Add supported text input and browser navigation controls; retain D-pad and touchpad with accessible alternatives.
- [ ] Render controls from receiver context/capabilities; reconcile optimistic changes with received state and disable impossible actions.

**Accept:** test idle/browser/VOD/live/disconnected states, external receiver-side changes, track selection and queue transitions. No stale seek/volume display after reconnect; unsupported commands are not offered.

### PAR-08 — Settings hub, privacy and local cast history

References: `A/settings/SettingsScreen.kt`, `A/history/CommandHistoryScreen.kt`, `A/cast/{StreamingSettingsScreen,TVSettingsScreen}.kt`; `I/UI/BrowserLibraryScreens.swift`, `I/Data/`, `I/UI/NavigationViewModel.swift`.

- [ ] Add a settings hub reachable from dashboard and browser; include working appearance, browser, playback and receiver settings with shared visual components.
- [ ] Implement dedicated cast history: persisted entries, replay, delete/clear, empty state and useful filtering based on Android behavior. Keep it distinct from browsing history.
- [ ] Define recording rules for successful/failed casts, duplicates, queue items and expired links; protect sensitive persisted data and avoid recording secrets in diagnostics.
- [ ] Port “Prevent TV cast history” semantics where supported: affects new receiver history/progress, does not claim to erase existing records. Validate the current protocol and older-receiver fallback first.
- [ ] Compare website casting consent and popup exception settings; add them when their corresponding automation exists. Do not add settings with no underlying behavior.

**Accept:** settings persist and apply; history replay uses the current receiver and handles expired URLs; clear/delete works after relaunch; receiver-history preference is verified with a supporting TV and an older receiver.

## P2 — Bring existing secondary features to parity

### PAR-09 — IPTV and collections completion

References: `A/iptv/`, `A/data/iptv/`, `A/collection/`; `I/UI/{IptvScreen,IptvDetailScreen,CollectionsScreen,CollectionDetailScreen}.swift`, `I/Data/{IptvStore,CollectionsStore}.swift`.

- [ ] Compare and complete search, grouping, sort, refresh, counts, logos, menus and loading/error/empty states.
- [ ] Expose existing collection rename/reorder helpers where needed; audit item editing and add-to-collection entry points from detected streams, IPTV and history.
- [ ] Preserve ordered playlist casting, headers and metadata; connect-first should retain the chosen item or playlist.
- [ ] Handle malformed/empty M3U, failed refresh, duplicates, large lists and persistence migration without losing prior data.

**Accept:** URL/file import, refresh failure recovery, group/search/sort, collection edit/reorder/relaunch and ordered receiver playback pass with deterministic fixtures.

### PAR-10 — Phone files and local player

References: `A/cast/PhoneFilesScreen.kt`, `A/player/`; `I/UI/{PhoneFilesScreen,CastSheet}.swift`, `I/Network/LocalFileServer.swift`.

- [ ] Align selection metadata, preparing/progress, cancel/retry and connected-device affordances using native Photos/Files pickers.
- [ ] Verify security-scoped access, iCloud loading, temporary-file cleanup, HTTP range/seek support and connection loss.
- [ ] Expand existing AVPlayer playback into a reusable local-player flow with loading/error, supported track controls and return-to-source state.
- [ ] Document foreground/background limits for phone-hosted streams based on device tests; distinguish those streams from receiver-fetched URLs.

**Accept:** representative audio/video Files and Photos items cast and seek; unsupported formats fail usefully; cancellation cleans up; lock/unlock and interruption behavior is documented.

### PAR-11 — Adblock, backup and diagnostics completion

References: `A/browser/PopupBlockerSettingsScreen.kt`, `A/settings/ImportExportSettingsScreen.kt`, `A/diagnostics/`; `I/Browser/ContentBlocker.swift`, `I/UI/AdblockSettingsSheet.swift`, `I/Data/`.

- [ ] Verify filter updates, custom-list errors, enabled states, per-site behavior and element-picker undo/removal. Explain unsupported rule types precisely.
- [ ] Add versioned export/import for supported preferences/bookmarks/collections/IPTV, with preview, validation and merge/replace behavior. Exclude pairing credentials and secrets by default.
- [ ] Provide useful redacted connection/cast diagnostics and explicit share/delete actions; follow repository persisted-log rules.

**Accept:** broken filter updates retain the last usable rules; export/import round-trip and corrupt/newer-schema input tests pass; diagnostics contain no sensitive header values, tokens or authenticated URLs.

## P3 — Larger feature additions; separate implementation milestones

These are real feature gaps, not excuses to leave the core UI unfinished. Each needs a scoped design and feasibility check before implementation.

- [ ] **PAR-12: Downloads.** Reference `A/downloads/`. Start with supported direct-file downloads, progress/cancel/retry, persistence and Files export; separately scope HLS/offline media. Prove interruption and restart behavior on a physical iPhone before promising background parity.
- [ ] **PAR-13: Media library/discovery.** Reference `A/library/LibraryScreen.kt`, `LibraryDetailScreen.kt`, `LibrarySettingsScreen.kt` and their data dependencies. Inventory providers, search/detail/source selection and saved state; deliver one complete supported provider path before expanding. Accept when search → details → source → local play/cast is verified with errors and empty states.
- [ ] **PAR-14: Google Cast.** Reference `I/Network/GoogleCastSession.swift`, `A/cast/googlecast/` and Apple README. Add discovery, device modeling, routing, readiness/errors and capability-aware remote UI. Preserve ordinary builds without the optional framework. With the framework, verify launch/join + media GET_STATUS, STOP versus receiver exit, fresh sessions after exit and real-device playback. Load Rust/protocol skills and follow native artifact checks.
- [ ] **PAR-15: Stream routing and debrid.** Reference `A/cast/proxy/`, `A/library/DebridSettingsScreen.kt`, `DebridLibraryScreen.kt`. Record supported direct/local/remote-proxy routes and provider authentication/storage before UI work. Ship only backed routes, verify headers and subtitle propagation, and make dependency on phone lifetime clear. Do not expose provider/settings placeholders.

## Proposed intentional differences / deferred scope

| Area | Proposed treatment | Revisit condition |
|---|---|---|
| GeckoView/WebExtensions | Keep WebKit; remove nonfunctional Extensions promise; provide supported content blocking | A concrete iOS-compatible extension design exists |
| Screen mirroring | Defer as separate feature; do not port Android MediaProjection code | Scoped Apple capture/transport design and real-device prototype |
| AirPlay / DLNA receiver breadth | Inventory separately; do not imply support through a generic “TV” label | Transport scope and interoperability tests are defined |
| Android file system / foreground services | Use native pickers and measured iOS lifecycle behavior | A specific user workflow remains unsatisfied |
| Codec/DRM differences | Keep local-play capability separate from cast capability; explain unsupported cases | A supported playback solution is selected and verified |
| OS gestures, keyboard, safe areas, system dialogs | Native iOS behavior with matching product hierarchy and branding | Screenshot/UX evidence shows a material inconsistency |
| Exit application | Omit forced quit; offer meaningful disconnect/stop actions | No parity requirement for process termination |

Do not describe a feature as forbidden by platform/store policy without checking current authoritative documentation during its feasibility work. These are scope decisions, not policy findings.

## Verification and completion gate

- [ ] Establish an iPhone unit/UI test target if none exists; no test files were found in the audited Apple phone tree. Use deterministic fixtures for detector lifecycle, parsers, history, migration and command mapping.
- [ ] Build the phone target from `mobile/apple/PlayBridge Phone/`:

  ```sh
  xcodebuild -project "PlayBridge Phone.xcodeproj" -scheme "PlayBridge Phone" -destination 'generic/platform=iOS Simulator' build
  ```

- [ ] Run relevant tests once the test scheme exists. Record exact commands and results; do not report unavailable SDK/device checks as passes.
- [ ] Compare screenshots after each UI milestone, using the same data and viewport assumptions as PAR-01. Check small/large phones, supported themes, large text, VoiceOver, keyboard overlap and Reduce Motion.
- [ ] Exercise physical-iPhone discovery/pairing/casting against PlayBridge Android TV and at least one other available receiver; document untested receiver types. Google Cast requires its own hardware checks.
- [ ] Run one complete regression journey: browser → detection → quality/subtitles → connect → cast → remote → back; plus IPTV, collections and local file casting.
- [ ] Correct `mobile/apple/README.md` after implementation: its two-tab/scope description and several missing-feature claims are already stale. Keep documentation aligned with shipped behavior.

Recommended sequence: PAR-01 → PAR-02 → PAR-03; then PAR-04–08 as separate core milestones; PAR-09–11; then individually scoped PAR-12–15. Establish focused tests alongside behavior changes, not only at the end.

## Work log / handoff

Append one row per task or subtask. Leave its checkbox open if required validation remains.

| Task | Status | Changes / commit | Validation evidence | Remaining work / intentional differences |
|---|---|---|---|---|
| Initial audit | Plan created | Baseline `1765536b`; source review only | File/route/action inspection; no app builds or device comparison | All implementation and runtime checks pending |

| PAR-05 async preview subtask | Implemented; runtime verification pending | CastSheet uses a selection request identity and lifecycle-managed task; stale/cancelled results cannot update thumbnail or qualities. HLS thumbnail polling observes cancellation. | iOS Simulator generic-target xcodebuild succeeded (2026-09-06); diff whitespace check passed | Exercise slow A→B→A selection, same-row retry, dismissal during loading, and local-player cover/return. Full PAR-05 remains open. |

### PAR-05 async preview regression checks

Use two distinguishable streams with different quality ladders and a deliberately delayed thumbnail/manifest response:

- Select A then B before A finishes. Only B's thumbnail and qualities may appear, even when A finishes last.
- Select A→B→A and retry the selected row. Earlier requests must not replace the newest request's results or clear its loading indicators.
- Close the sheet during HLS thumbnail loading; reopen and select B. No previous results may appear, and cancelled polling must stop.
- Open local playback while preview loading is in flight, then return. The selected stream must remain intact and its preview/quality loading must complete or resume.
- Cast after selecting a quality; confirm the receiver receives the currently selected stream's variant.

These runtime checks have not yet been executed; a successful build is not evidence that they passed.

### PAR-05 stream rows and ranking update — 2026-09-06

Implemented quality discovery for all adaptive streams while the sheet is open,
with at most three manifest requests in flight. Qualities are stored per stream,
independently of thumbnail loading and selection. Confirmed multi-quality
manifests rank first; ties retain detection order and reordering preserves the
user's selection. This replaces the original filename-based `master` heuristic.
The earlier selected-preview task was replaced by per-row thumbnail tasks;
late thumbnail completion cannot update another row.

Cards now use a format badge, 16:9 preview, title/URL and metadata, always-visible
quality options, and independent local-play/copy controls. Tapping an HLS quality
selects its stream and variant together. DASH tiers remain informational because
the receiver handles adaptive resolution. Unavailable previews have an explicit
terminal state. The fixed header, action dropdown, tab badges and native drag
handle from the preceding UI changes are retained.

Validation: generic iPhone Simulator build passed; standalone production-parser
and ranking fixture tests passed (see `mobile/apple/tests/README.md`). Full
PAR-05 stays open: physical-device scrolling/taps, authenticated manifests,
receiver playback, and screenshot parity have not been verified. Ranking here
uses available iOS quality metadata; Android's richer validation/evidence and
synthetic-stream ranking remain separate parity work.

Thumbnail ranking follow-up: row thumbnail state now feeds the sheet's ranking.
Within the multi-quality and remaining-stream groups, a ready preview outranks
pending/unavailable previews; equal candidates retain detection order. A failed
preview does not remove a castable stream. Selection remains attached to stream
identity when rows move. Added regression checks for two failures followed by a
successful third preview, pending stability, type-only guesses and preservation
of multi-quality priority. Standalone fixture tests and simulator build passed;
physical-device verification remains pending.

Background enrichment follow-up: `VideoDetector` now owns quality and thumbnail
jobs and published per-tab results. Ingestion starts enrichment without a cast
sheet; the sheet observes the detector and rows only render its results. Up to
three independent jobs run per tab. Closing the sheet preserves results and does
not cancel work; document commit clears results and invalidates/cancels old jobs,
and closing a tab clears its detector. Cancelled thumbnail jobs cannot populate
the shared thumbnail cache. This supersedes the earlier sheet-owned quality and
row-owned thumbnail lifecycle described above. This is asynchronous work while
the app is active, not a guarantee of execution while iOS suspends the app.

Added standalone production-detector tests with injected loaders for eager work,
independent results, deduplication, bounded concurrency, subtitle exclusion,
old-page completion rejection and teardown. Real browsing/network/AVFoundation
and device suspension checks remain pending.

### SPA ranking and non-playing HLS thumbnails — follow-up

Replaced the simplified fixed quality/preview scores with Android's supported
validation/evidence/adaptive/ladder/header/preview weights, recency decay and
newest-lifecycle bonus. `DetectedVideo` records first/last-seen times and lifecycle;
repeated detections update activity and strengthen evidence without repeating
unchanged enrichment work. Main-frame SPA history changes start a lifecycle with
Android's two-second adoption grace; old rows remain available. Synthetic
handoffs are still not emitted on iOS and are not fabricated by ranking.

Removed off-screen AVPlayer use from thumbnail generation. HLS samples are
bounded downloads decoded without playback. Fragmented MP4 uses image generation;
raw H.264 TS uses PES/Annex-B extraction and VideoToolbox because iOS image
generation rejects that container. Confirmed this distinction on an iPhone
simulator: supplied sample failed the old local-file decoder and passed the new
TS decoder at 640×360. Signed URLs and user samples are not repository fixtures.
Encrypted/byte-range HLS and non-AVC TS remain unsupported by this thumbnail
sample path; playback/casting remains available even when a preview cannot be
created. Webpage interruption is not reproduced on physical hardware yet, but
background previews no longer create a player or activate an audio session.

Validation: iPhone simulator app build, native sample XCTest, and standalone
ranking/enrichment/HLS-plan/TS-parser/SPA-script tests passed. See
`mobile/apple/tests/run-fixture-checks.sh`. Exact on-device webpage playback and
remaining HLS layout/codec support still need follow-up.

Debug diagnostics follow-up: debug stream cards expose **Copy diagnostics**, with
copy confirmation, on both selected and unselected rows. Per-job task-local
traces capture HTTP status/byte limits, HLS layout rejection and decoder stages
in memory. Reports include app/OS and detector/manifest/thumbnail state, redact
signed URL values and sensitive headers, and use a local clipboard with expiry.
Release UI excludes the action and traces retain no messages. Debug and Release
iPhone simulator builds and debug/release redaction/isolation fixture checks
passed. This makes remaining site-specific failures reportable; it does not
claim those streams have been fixed.

TS extraction follow-up: corrected continuity handling for independently packaged
HLS segments. Only identical packets with repeated counters are duplicates;
changed packets reset assembly. Counter gaps or explicit discontinuities preserve
an already complete keyframe, otherwise discard partial data and resume at a PES
start. Added packet-reset/retransmission/truncated-frame regression cases and
precise packet/PES/SPS/PPS/IDR diagnostics. Fixture suite and iPhone simulator
build passed. The reported site's redacted diagnostic confirms extraction-stage
failure, but does not by itself prove continuity reset was that stream's cause;
retest and inspect the new details if its thumbnail remains unavailable.

Local playback follow-up: configure and activate the playback/movie audio session
on explicit Play on phone, unmute the player, and release the session on dismissal.
Move Close into a separate safe-area bar above native video controls so it cannot
cover AirPlay. Enable external playback and the audio/AirPlay background mode.
Simulator build passed; silent-switch audio and a physical AirPlay receiver still
need device verification. Authenticated remote HLS may need a LAN proxy that
replays headers and rewrites playlists; the existing local-file server does not
implement that remote-stream proxy.

AirPlay investigation: user confirms this HLS stream plays with sound on iPhone
but fails after switching to Apple TV. A manifest fetch from the development
machine returned Cloudflare HTTP 403; this is not proof of the receiver response.
Added debug-only, bounded in-memory playback diagnostics to the stream's existing
Copy diagnostics report: external playback transitions, item/time-control state,
audio route types, AVPlayer error-log codes and redacted resource URLs, and nested
NSError domain/codes. Do not record arbitrary error descriptions or device names.
The report survives player dismissal and clears with detector document state.
Next: reproduce on Apple TV and inspect the playback section before attributing
the failure to browser headers, segment MIME types, or receiver codec support.
A LAN HLS proxy remains pending, not implemented by this diagnostics change.

Rust sender-services integration follow-up: added the Apple feature using the
existing host callback ABI, built device/simulator XCFramework slices, and linked
the phone target. Play on phone now registers media with the Rust proxy and feeds
its LAN URL to AVPlayer. URLSession owns authenticated upstream reads; Rust owns
HLS rewriting and segment MIME correction. Player lifetime owns revocation;
preparation runs off the main thread, supports cancelled selections, and reports
preparation errors. A timed-out native host is retired to avoid orphaned grants.
Without a LAN address, local playback uses loopback and external playback is
disabled with a Wi-Fi hint. See rust-sender-services.md for build and limitations.

Verified: Rust Apple-feature FFI tests and clippy; Apple upstream callback tests;
full Swift/Rust fixture covering required Referer on master/media/segments,
nested HLS rewriting, .jpg TS video MIME, and revoked-session HTTP403; existing
iOS fixture suite, debug simulator build, and unsigned release iPhone build.
Actual Apple TV rendering remains
unverified. Google Cast UI/discovery adoption is implemented in the follow-up below; DLNA integration and migration
of the existing authenticated Swift PlayBridge connection remain separate tasks.

Cross-CDN playback regression follow-up: the reported proxy segment failure was
accompanied by AVPlayer -1102 / CoreMedia -12660. Inspection found that Rust
stripped every supplied header when an HLS segment changed origin, including
browser User-Agent and Referer. Preserve non-credential browser context across
CDNs, reducing Referer/Origin to origins and continuing to scope cookies,
authorization, and custom credentials to the original media origin. Added Rust
regression coverage and a two-origin URLSession/Rust fixture that requires browser
headers and rejects credential leakage. All 35 focused Rust proxy tests and the
integration fixture pass; rebuilt Apple XCFramework and iOS simulator app.

Play on phone now retries the prior direct AVPlayer path once if proxy setup or
local proxy playback fails; it does not replace an active AirPlay session.
Diagnostics follow current-item replacements. A real AVPlayer fixture verifies
failed-item replacement and that the fallback does not loop. Device/site retest
is still needed; the passing fixture is not proof of that CDN's current behavior.

Explicit routing follow-up: the cast sheet now offers Direct / Via phone / Via
proxy and persists the selected route, initially Direct. Removed the TV player
selector for Play/Queue; Browse retains its browser choice. A gear opens remote
proxy URL/password configuration, with the password stored in Keychain. An empty
remote configuration opens setup instead of starting Via proxy.

Play on phone, Send, and Queue use the same StreamRouteService. Direct keeps the
original URL/headers and never starts Rust. Via phone retains a Rust registration;
Via proxy POSTs /register?token=... using the Android request contract and uses the
returned proxy URL. Receiver routes clear original headers and route attached
subtitles too. The connection owner retains phone registrations beyond sheet
dismissal and for queued items. Explicit proxy errors do not fall back to Direct;
this supersedes the previous automatic local fallback behavior.

Verification: explicit-route fixtures passed, including a real URLSession POST
to the local remote-proxy fixture, password delimiter encoding, and HTTP403
handling. The iOS simulator build passed. Physical receiver playback and the
user's configured remote proxy remain device/environment checks.

Playback error handling: added a user-facing failure panel for terminal AVPlayer
item failures and failed-to-play-to-end notifications. Safe messages distinguish
access/expired-link, missing resource, server, network, TLS, and unsupported-format
errors without printing raw NSError descriptions or authenticated URLs. Try again
prepares the same route/configuration and replaces the failed item; closing
cancels the retry and prevents stale completion from restarting playback. Debug
builds expose Copy diagnostics directly in the error panel. Recoverable buffering
is not treated as a terminal error. Focused real-AVPlayer fixtures and the debug
iPhone simulator build passed; physical AirPlay remains a device check.

### Google Cast sender integration (2026-09-07)

Implemented Google Cast discovery through declared OS Bonjour `_googlecast._tcp`,
feeding resolved addresses into the linked Rust ABI-v2 session adapter. Device
pickers show friendly names and saved receivers separately from PlayBridge
pairing records. Connection readiness follows Rust launch/join + media GET_STATUS;
sending awaits a load acknowledgement. Direct, Via phone, and Via proxy selections
apply to Google Cast sends, with phone registrations retained for receiver playback.

Remote controls include play/pause, stop, relative seek, absolute volume, and a
separate End receiver session action. Request failures show safe messages; receiver
exit invalidates the session and reconnect creates a fresh native session. Native
polling, commands, cancellation and handle destruction run on one serial worker.
Unsupported queue, browser, and external subtitle actions are hidden for Cast.
Direct sends cannot attach browser authentication headers; use Via phone or a
configured proxy for streams that require them. DLNA is a subsequent task.

Automated checks: `bash mobile/apple/tests/run-google-cast-checks.sh` covers TXT
parsing, readiness gating, acknowledged loads, status conversion, STOP versus
receiver exit, safe request errors, cancellation, and reconnect. These use a mock
native transport, not a physical Chromecast. PAR-14 remains open until physical
iPhone discovery, receiver launch/join and video playback are verified on hardware.

Build validation: Debug iOS Simulator and unsigned Release iPhone builds pass
with the existing generated Rust XCFramework linked. No native ABI change was
needed for this consumer integration.

### DLNA sender integration (2026-09-07)

Implemented Rust SSDP discovery in setup only, manual device-description URL
connection, shared external receiver history, Direct/Via phone/Via proxy sending,
status and play/pause/seek/stop controls. Existing Google Cast history decodes
unchanged. DLNA volume, external subtitles, queues and app shutdown are unavailable
in the current adapter and hidden. Real Swift/Rust SOAP fixture checks pass.
Physical discovery is pending Apple multicast entitlement provisioning and hardware
validation; `dlna.md` documents the supplied entitlement template and setup steps.

### Remaining Rust discovery protocols (2026-09-07)

Roku SSDP discovery, manual ECP endpoint connection, recent receiver history,
routed sending and supported transport controls are integrated. Optional generic
DIAL discovery is available in setup as informational app-receiver results; no
unsupported generic Cast action is exposed. All SSDP searches stop on leaving
setup, and recent-device pickers never start scans. Physical iPhone multicast
provisioning remains pending. Real Rust Roku ECP and DLNA SOAP fixture checks pass.
