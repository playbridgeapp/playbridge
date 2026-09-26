# iPhone standalone fixture checks

Website casting, remembered permissions, and linked/lazy playlists:

```sh
bash mobile/apple/tests/run-page-cast-model-checks.sh
bash mobile/apple/tests/run-page-cast-coordinator-checks.sh
node --test mobile/apple/tests/PageCastScriptTests.js
```

These test bounded payloads, headers/subtitle preservation, exact-origin grants,
permission resets, session ownership, queue demand/retries, cancellation, and
receiver changes. `run-browser-startup-checks.sh` additionally runs the production
page API through WKWebView to a mock receiver. See
`docs/ios-website-casting.md` for the API and physical-receiver test scope.

AirPlay queue and external subtitle packaging:

```sh
bash mobile/apple/tests/run-airplay-queue-checks.sh
bash mobile/apple/tests/run-airplay-subtitle-checks.sh
```

These cover FIFO advance, replacement, subtitle updates, removal/reordering,
HLS rendition preservation, name collisions, SRT/WebVTT conversion and timestamp
mapping. Physical AirPlay route selection, captions and background transitions
still require device testing; see `docs/ios-airplay.md` for scope and limitations.

Background casting lifetime and Lock Screen command mapping:

```sh
bash mobile/apple/tests/run-cast-playback-checks.sh
bash mobile/apple/tests/run-google-cast-checks.sh
```

The first suite uses the production casting session with a fake renderer and clock.
It checks receiver timing, bounded seeking, STOP fencing, proxy-release callbacks,
reconnect grace, pause grace, and live/image behavior. These tests do not prove iOS
background execution. See `docs/ios-background-casting.md` for physical-device checks
and the Debug audio-session-only comparison.

From the repository root, compile the production stream model and manifest parsers
with the deterministic HTTP fixture in the test runner:

```sh
swiftc -module-cache-path /tmp/playbridge-swift-module-cache \
  'mobile/apple/PlayBridge Phone/PlayBridge Phone/Browser/StreamDebugTrace.swift' \
  'mobile/apple/PlayBridge Phone/PlayBridge Phone/Models/DetectedVideo.swift' \
  'mobile/apple/PlayBridge Phone/PlayBridge Phone/Browser/HLSParser.swift' \
  'mobile/apple/PlayBridge Phone/PlayBridge Phone/Browser/DASHParser.swift' \
  mobile/apple/tests/CastStreamRankingTests.swift \
  -o /tmp/playbridge-cast-ranking-tests
/tmp/playbridge-cast-ranking-tests
```

This checks parsed HLS/DASH ladders, relative HLS URLs, multi-quality priority,
misleading filenames, duplicate variants, empty probes and stable ordering.
It does not exercise SwiftUI gestures, real HTTP authentication or receiver playback.
The fixture replaces `StreamHTTP` only for this standalone executable; do not add
it to the app target.

For detector background-work lifecycle checks, compile the production detector
with injected loaders and platform stubs:

```sh
swiftc -module-cache-path /tmp/playbridge-swift-module-cache \
  'mobile/apple/PlayBridge Phone/PlayBridge Phone/Browser/StreamDebugTrace.swift' \
  'mobile/apple/PlayBridge Phone/PlayBridge Phone/Models/DetectedVideo.swift' \
  'mobile/apple/PlayBridge Phone/PlayBridge Phone/Browser/VideoDetector.swift' \
  mobile/apple/tests/VideoDetectorEnrichmentTests.swift \
  -o /tmp/playbridge-enrichment-tests
/tmp/playbridge-enrichment-tests
```

These checks cover work starting at ingestion without a sheet, independent quality
and thumbnail completion, duplicate detection, three-job concurrency, subtitle
exclusion, late results after navigation (including the same URL), and detector
teardown without retention by its tasks. They do not test native AVFoundation
cancellation or real device background/suspension behavior.

The fixture runner also checks SRT/VTT subtitle sample parsing, three-cue display
limits, longer language samples, confidence gating, newest-first subtitle order,
markup cleanup, malformed-cue fallback, and empty previews. Network authentication
and actual sheet rendering still require an iPhone or simulator check.

Run all standalone fixtures (macOS Swift toolchain and Node required):

```sh
bash mobile/apple/tests/run-fixture-checks.sh
```

This also exercises the IPTV store's asynchronous load, large file import,
ordered background saves, deletion, and reopen behavior.

Additional regression coverage mirrors Android's ranking scenarios: newest
activity breaks equal-score ties; freshness decays 50 points per minute up to
150; newest SPA lifecycle receives 400 points; current verified streams can
outweigh new pending detections; quality ladders beat comparable child streams.
The script tests pushState/replaceState/popstate/hashchange reporting and
main-frame isolation as well as the production detector's activity timestamps.

HLS sample checks cover TS/fMP4 planning, relative URLs, discontinuities and
bounded segments. TS decoder checks cover PES extraction, SPS/PPS and multi-slice
IDR boundaries. Actual iOS decoding was additionally tested in a temporary XCTest
project with a privately supplied TS sample (not committed): AVAssetImageGenerator
failed with -11828, while TransportStreamThumbnail produced a 640×360 image.
Reproduce native decoder tests with locally generated H.264 TS fixtures; do not
commit signed stream URLs, credentials or user media. Standalone parser tests do
not prove hardware-decoder or webpage playback behavior.

Debug builds expose **Copy diagnostics** below each stream's actions. The copied
report includes detection/manifest/thumbnail state and bounded per-job traces.
Requests report HTTP codes and byte counts; decoder failures report stages and
error codes. Signed query/path parameters and sensitive header values are
redacted. Reports remain in memory, clear with the detector, and copy to the
local clipboard for 15 minutes. Release builds omit the action and retain no
trace entries. `StreamDebugTraceTests` checks debug/release behavior and redaction.

Rust sender-services integration (macOS):

```sh
bash mobile/apple/tests/run-apple-upstream-checks.sh
RUSTC="$(rustup which --toolchain stable rustc)" rustup run stable cargo build -p playbridge-cast-core-ffi --features sender-services-apple --locked
bash mobile/apple/tests/run-sender-services-checks.sh
```

The upstream suite uses a small C ABI shim to exercise URLSession streaming,
headers, ranges, cancellation, and redirect metadata delivery. The sender-services suite
links the real Rust archive and verifies nested HLS rewriting, header forwarding,
video MIME correction for `.jpg` segments, registration revocation, and cross-host
MP4 redirects with probe/seek ranges and credential scoping. Both use
an ephemeral local HTTP fixture, require local networking (the Rust integration uses the Mac’s LAN address because
loopback upstreams are deliberately forbidden), and clean up their
processes and temporary outputs. Their synthetic segment is not a playable video;
physical Apple TV playback remains a separate device check.

Explicit stream routing:

```sh
bash mobile/apple/tests/run-stream-route-checks.sh
```

Verifies Direct never starts a proxy, phone/remote selection and header handling,
missing configuration, no silent fallback, and the remote registration contract
including URL prefixes and passwords containing query delimiters and plus signs.

Add `--network` to run remote registration against the local HTTP fixture,
including successful password authentication and HTTP403 handling.

Playback failures and retries:

```sh
bash mobile/apple/tests/run-playback-error-checks.sh
```

Uses AVPlayer and a local HTTP fixture to verify visible failure state,
route-preserving retries, dismissal during preparation, and safe error messages.
Requires local networking; it does not validate an actual AirPlay receiver.

Google Cast controller and discovery parsing checks (macOS, no receiver needed):

```bash
bash mobile/apple/tests/run-google-cast-checks.sh
```

Uses a mock Rust session transport to verify ready-state gating, load acknowledgements,
status conversion, command failures, cancellation, reconnect, and STOP versus ending
the receiver. Physical Google Cast discovery and playback still require hardware.

The sender-services fixture also injects a local listener connection failure:
registration replaces the native host once and health-checks its replacement.
Persistent connection failure is reported after two probes, without unbounded retries.

DLNA sender checks using the real host Rust archive and a local SOAP receiver:

```bash
bash mobile/apple/tests/run-dlna-checks.sh
```

Covers load/play/pause/seek/stop/status, URL XML escaping, discovery record parsing
and compatibility with previously saved Google Cast records. Physical SSDP and TV
rendering need hardware; see `../docs/dlna.md` for iOS multicast provisioning.

`run-dlna-checks.sh` additionally verifies Roku ECP launch and transport controls,
manual endpoints, media URL query encoding, and distinct DIAL discovery records.

### iOS browser interactions and downloads

With an iOS simulator booted, run from the repository root:

```sh
bash mobile/apple/tests/run-browser-startup-checks.sh
```

The script builds a separate simulator test app and a loopback-only HTTP fixture.
It exercises production BrowserStore, BrowserTab, BrowserDataStore, browser prompt
lifecycle, and WKDownload management. Detector enrichment and remote adblock list
fetches are stubbed. It verifies lazy restoration/migration, new-window POST
preservation, consent/source attribution, iframe rejection, JavaScript dialogs,
popup exceptions, external-link approval cancellation, navigation recovery,
search encoding, cookie-preserving download bytes, failed-download retry and
cancellation. A test failure exits nonzero even if `simctl launch` itself succeeds.
Set `IOS_TEST_SIMULATOR` to a simulator UUID to select a specific booted simulator.
The fixture does not use the real app's saved tabs or third-party websites.

Manual device acceptance: navigation error/retry UI, sequential native prompts,
Files export/share, installed and missing external-app destinations, and download
interruption when iOS backgrounds the app. WebKit downloads do not promise
background transfer or HLS offline assembly.

### Phone media library

```sh
bash mobile/apple/tests/run-media-library-checks.sh
```

Requires a booted iOS simulator (optional `IOS_TEST_SIMULATOR` UUID). The isolated
fixture exercises production import/persistence, media classification, stable
local collection references and backward compatibility, completed-download
filtering, safe removal, and the actual library UI. It writes a rendered screen
to `/tmp/playbridge-library.png`. Connections are stubbed; Photos permission,
iCloud media and real receiver playback need physical-device acceptance.

The browser fixture also extracts the current production element-picker script
from ContentBlocker.swift. It exercises linked-element touchend/click suppression,
picker message gating, hide/cancel, popup suppression and navigation after cleanup.
`--picker-menu-ui` tests real simulator taps through the browser menu and native
picker controls. `--picker-live-ui` repeats Block and Cancel on the reported
overlay-heavy site; it needs external network access and is opt-in because that
site's content can change.

Run `bash mobile/apple/tests/run-browser-startup-checks.sh --popup-audit` from the
repository root for popup-policy regression checks. It exercises automatic
windows, synthetic links/forms, background tabs, cross-origin frames, permission
revocation, and same-tab redirects without live ad filter lists. The cases assert expected outcomes, including synthetic-event and forged-message
rejection. See `../docs/ios-popup-review.md` for policy limits.

Run `bash mobile/apple/tests/run-browser-startup-checks.sh --popup-touch` for
XCTest taps on links, forms, script-driven windows, burst attempts, expired
interactions, and embedded-frame links. This generates a temporary XCTest
project and leaves the shipping Xcode project unchanged.


Run `bash mobile/apple/tests/run-browser-startup-checks.sh --ad-navigation` for
ad-destination navigation checks. The local WebKit fixture covers direct popup
ads, current-tab navigation, HTTP redirects, popup cleanup, video tab swaps,
legitimate redirects, disabled blocking, and conservative filter semantics.
It compiles the production `NavigationAdRules` matcher and browser code, with
fixture filter text replacing live downloads.


Run `bash mobile/apple/tests/run-browser-startup-checks.sh --network-log` for
per-tab network log checks. It verifies detailed capture is off during normal
browsing, then enables capture and reloads. The WebKit fixture exercises fetch, XHR, images,
iframe attribution, failed requests, blocked navigation records, redaction,
request updates, log clearing, tab isolation, and the 1,000-entry bound.


Run `bash mobile/apple/tests/run-browser-startup-checks.sh --network-log-ui` for
XCTest taps through a network entry, its request-details screen, and the block /
unblock confirmation. This uses the production network log view with a fixture
domain store; it checks presentation and action flow without modifying real
app preferences.


Run `bash mobile/apple/tests/run-browser-startup-checks.sh --domain-block` to
compile the production domain-rule JSON using WebKit and verify iframe and
srcdoc-resource blocking against a local server. It also verifies that repeated
navigation blocks do not extend the notification deadline or bypass its cooldown.

Run `bash mobile/apple/tests/run-browser-startup-checks.sh --tab-management` for
duplicate placement/metadata, bookmark deduplication, batch closure and lazy-tab
checks. Run with `--tabs-ui` for XCTest interaction with the production row
switcher, search, duplication, selection, and close-all confirmation.

Tab playback indicators:

```sh
bash mobile/apple/tests/run-browser-startup-checks.sh --playback-state
```

Uses real WebKit audio elements with a local silent WAV to verify tab-specific
play/pause indicators, stopping a previous tab without clearing the current one,
cross-origin iframe playback/removal, stale report expiry, and dormant-tab safety.
