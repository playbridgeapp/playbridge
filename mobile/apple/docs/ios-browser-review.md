# iOS browser completeness review

Reviewed 2026-09-07 against the current working tree, including the pending lazy-tab/new-window changes. This is a source review, not a claim of complete physical-device or website compatibility testing. See also [the broader parity plan](iphone-android-parity-todo.md).

## Verdict

The core browser is implemented, but it is not feature complete. Prioritize reliability and website compatibility before adding more menu items. Android is the product reference; platform-specific features should be explicitly supported or deferred, rather than promised through placeholders.

Implemented surfaces include address/search input, history/bookmark suggestions, back/forward/reload/stop, multiple tabs, URL restoration, desktop mode, system find-in-page, bookmarks/history, search-engine selection, website-data clearing, content-blocking lists and element/source picking, media detection, and the cast sheet. Presence in code is not proof that every website or device works.

All source paths below are relative to `mobile/apple/PlayBridge Phone/PlayBridge Phone/` unless stated otherwise.

## Prioritized findings and acceptance checks

### BROWSER-01 — P1: Website casting lacks consent and source-tab attribution

Evidence: `Browser/BrowserStore.swift` forwards every tab's `onPageCast` without the source tab; `TabScriptHandler` accepts `cast` messages without a main-frame/origin check. `UI/BrowserScreen.swift:27` immediately calls `vm.castStream` and derives origin/headers from the currently active tab. Scripts are installed in all frames.

A page or iframe can initiate a cast without a confirmation. A background tab can also supply a URL while the active tab supplies the wrong origin/Referer. Android's `browser/BrowserActivity.kt` has explicit website casting consent and separate private-origin grants.

- [ ] Carry the originating tab/frame and verified origin through the native bridge.
- [ ] Require user approval for website-initiated casting; define iframe and background-tab behavior.
- [ ] Keep explicit native actions such as long-press Cast distinct from unsolicited page requests.
- [ ] Match Android's applicable permission semantics without changing wire contracts casually.
- [ ] Verify no send before approval, rejection, repeated requests, background tabs, and cross-origin frames; headers must belong to the initiating page.

### BROWSER-02 — P1: Navigation failures have no useful recovery UI

Evidence: `Browser/BrowserTab.swift:182` and `:186` only set a private `pageLoaded` flag on failure. `UI/BrowserScreen.swift` has no navigation-error state. There is no `webViewWebContentProcessDidTerminate` handler.

Offline, DNS, TLS, or failed navigation can leave a blank page or previous content without explaining the failure. There is no explicit recovery path for a terminated WebContent process.

- [ ] Publish navigation failures and show a useful message, requested address, Retry, and Back where applicable.
- [ ] Treat cancellation as cancellation, not an error; never bypass certificate validation to make retry work.
- [ ] Recover from process termination without reload loops or losing the intended URL.
- [ ] Verify offline startup, failed navigation from an existing page, retry after connectivity returns, cancellation, and process termination.

### BROWSER-03 — P1: JavaScript dialog and popup compatibility is incomplete

Evidence: `Browser/BrowserTab.swift` implements no JavaScript alert/confirm/text-input dialog delegates. Its new-window handler (`:199`) equates navigation type with user gesture and rejects everything except links/forms. Android forwards prompt requests in `cast/SessionObserverSetup.kt` and provides per-site popup exceptions in `browser/PopupBlockerSettingsScreen.kt`.

Sites requiring confirm/prompt interactions lack native dialog handling. Legitimate script-created windows are not reliably covered by the link/form policy, and the user has no allow-once or per-site exception.

- [ ] Implement alert/confirm/prompt presentation and complete every callback exactly once, including tab closure or dismissal.
- [ ] Add a controlled allow-once/per-site popup flow while retaining unsolicited-popup blocking.
- [ ] Verify target-blank links, POST forms, user-triggered script windows, blocked automatic windows, dialogs, and opener behavior.

### BROWSER-04 — P2: Search query encoding changes user input

Evidence: `Data/BrowserDataStore.swift:38` uses `.urlQueryAllowed` for an individual query value. A Foundation check returned `cats%20&%20dogs%20+%20birds` for `cats & dogs + birds`; the ampersand remains a query separator and plus is ambiguous to form-style search decoders.

- [ ] Encode the search parameter as a value, including literal ampersands and plus signs.
- [ ] Verify the server-decoded query equals the input for `&`, `+`, `#`, Unicode, and percent signs for all supported engines.

### BROWSER-05 — P2: Restoration loses useful tab identity and state

Evidence: `Browser/BrowserStore.swift:147` persists only URLs and the active index. `BrowserTab.title` starts as "New Tab"; `UI/TabsScreen.swift` displays it without a URL fallback. Lazy restoration leaves unopened tabs with that generic label and no snapshot. Home tabs are skipped when saving, so an active home tab restores to a different tab if saved web tabs exist.

- [ ] Persist title and desktop-mode metadata; show a host/address fallback for legacy saved tabs without loading them.
- [ ] Preserve home tabs and the selected tab across restart using a backward-compatible storage migration.
- [ ] Explicitly decide whether back/forward history and scroll restoration are in scope; they currently are not persisted.
- [ ] Verify a 20-tab restore is identifiable without waking inactive tabs, and switching retains already-created web views.

### BROWSER-06 — P2: File/download and external-link flows are incomplete

Evidence: navigation policy always returns `.allow`; there is no `WKDownload` integration, download manager, or explicit browser handoff for unsupported/external schemes. Android has browser-response downloads and a separate downloads feature tree.

- [ ] Define supported direct-file downloads with destination, progress, cancellation, failure handling, and Files export.
- [ ] Explicitly scope HLS/offline and background downloads separately.
- [ ] Handle non-renderable responses and external-app links with visible, user-controlled outcomes instead of silent navigation failures.
- [ ] Test attachment responses, unsupported MIME types, download interruption, and installed/uninstalled external-app destinations.

## Additional parity or product decisions

- [ ] Bookmarks/history: add search and bookmark editing; current UI supports opening and deletion but little organization.
- [ ] Page actions: add Share/Open in Safari and site information where useful. Current long-press link menu offers Cast, new/background tab, and Copy.
- [ ] Private browsing: either scope an isolated nonpersistent mode or explicitly defer it; all tabs currently share the default persistent website-data store. Do not assume this is a confirmed Android parity requirement.
- [ ] Extensions: replace the "Coming Soon" placeholder with a clear supported/deferred decision. Do not promise Android extension compatibility on WebKit.
- [ ] Lifecycle: lazy startup does not limit how many previously opened web views stay alive. Profile a long session before choosing a memory-pressure eviction policy.
- [ ] Accessibility/UI: validate VoiceOver names for icon-only controls, Dynamic Type, keyboard dismissal, rotation, and tab-sheet usability on a physical iPhone. Source inspection alone does not establish these outcomes.

## Verification status and next implementation order

The earlier startup/new-window implementation had a successful simulator app build before the test-storage initializer was added. The standalone simulator regression harness then reported a generic timeout; it did not produce a full pass. Diagnose the failing stage before treating new-window behavior as verified. This timeout alone does not establish a production defect.

The search-encoding issue above was reproduced using Foundation. Focused whitespace/diff checks on the pending browser source changes passed. No physical-device browser compatibility matrix was run for this review.

Recommended order: complete startup/new-window verification and restored-tab labels; then BROWSER-02 navigation recovery, BROWSER-03 site interactions, BROWSER-01 casting consent/source attribution, and BROWSER-04 encoding. Keep all P1 items ahead of declaring the browser complete. Downloads and the remaining product decisions are separate milestones.

## Implementation update — 2026-09-07

The six requested findings now have implementations in the working tree:

- **BROWSER-01:** Main-frame website requests require per-request approval, show the requesting origin and destination origin, and retain the originating page for Referer construction. Background tabs, mismatched origins, and iframe cast messages are rejected. Switching tabs or navigating cancels pending approval; native long-press Cast remains an explicit user action.
- **BROWSER-02:** Navigation errors and WebContent termination expose a recovery screen with Back and Try Again. Retry uses the failed address. Cancelled/policy-interrupted loads do not produce an error screen; certificate failures have no bypass.
- **BROWSER-03:** JavaScript alert, confirm, and prompt callbacks have native sheets and resolve once on acceptance, cancellation, navigation, or closure. Script-created popups remain blocked by default; the blocked notice can allow the current origin, and the browser menu can revoke that exception. After allowing a site, repeat the original website action so WebKit retains the original window/request semantics.
- **BROWSER-04:** Search parameter values use unreserved-character encoding. Tests round-trip ampersands, plus, percent, fragment characters, and Unicode through every search engine.
- **BROWSER-05:** A backward-compatible tab-file migration persists titles, home tabs, selection, and desktop mode. Legacy unopened tabs display their host without loading. Inactive restored tabs and tab previews remain lazy. Back/forward history and scroll position across app termination remain outside this change.
- **BROWSER-06:** WebKit attachments, download links, and non-renderable responses offer a download approval. The Downloads menu has progress, cancellation, failure/retry, deletion, sharing, and Save to Files. Completed-file metadata survives restart. URL/header/resume credentials are not persisted. External-app links require approval; unsupported links or a missing destination app produce feedback.

Downloads are direct-file downloads managed by WebKit while the app is running. This does not implement offline HLS assembly or guarantee continued transfer after iOS suspends/terminates the app. Interrupted records after restart direct the user back to the website, rather than persisting authenticated requests. Download retry can resend the original request and asks for confirmation.

The expanded `run-browser-startup-checks.sh` now passes on the iOS simulator against a loopback HTTP fixture. It covers lazy restore, tab metadata migration, real new-window links and POST bodies, cast consent/source checks, iframe rejection, JavaScript dialog results/cancellation, popup exceptions, external-link cancellation, navigation/process recovery callbacks, search encoding, cookie-bearing downloads, byte integrity, interruption/retry, and cancellation. The earlier generic timeout is superseded by this passing run. Native permission sheets, Files export, real external-app handoff, and physical-device website compatibility still need hands-on acceptance testing.
