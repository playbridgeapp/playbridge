# iOS popup policy review

Reviewed and fixed 2026-09-08. The findings below describe the original implementation; the confirmed synthetic-interaction and inherited-permission bypasses are now covered by regression checks.

## Implemented fixes

- Replaced navigation-type guessing with a trusted-click listener in a separate `WKContentWorld`. The dedicated native handler is absent from the page world, validates its WebKit world/view, and never accepts page-supplied origin values. See [Apple's content-world handler API](https://developer.apple.com/documentation/webkit/wkusercontentcontroller/add(_:contentworld:name:)).
- On iOS 16.0–16.3, which predate the [User Activation API](https://webkit.org/blog/13966/webkit-features-in-safari-16-4/), the listener requires `isTrusted`; newer WebKit also checks transient activation. Older runtimes were not available for device validation.
- Each click grants at most one window for one second. The native gate matches the initiating security origin, document URL, and main-frame status. Grants are cleared on navigation, tab changes, browser dismissal, element picking, and document visibility changes.
- Site exceptions use the initiating frame's WebKit security origin. A parent site's exception does not authorize a different-origin iframe. The blocked notice names the requesting origin, and its Allow action applies to that origin.
- Kept WebKit's supplied window configuration and original request to preserve new-tab ownership and POST requests. Native context-menu actions remain available.

The automated popup audit now asserts expected outcomes instead of merely printing observations. It also checks `requestSubmit()`, forged click/bridge messages, and that the authorization handler is not accessible in the page world. The full browser fixture continues to cover navigation, picker behavior, consent, dialogs, downloads, and allowed window requests.

`--popup-touch` uses XCTest taps against the same production browser code for ordinary links, forms, script-driven windows, and a two-window burst. All six cases passed on the iOS 26.4 simulator: links, forms, script-driven windows, a two-window burst, an expired interaction, and a link inside a cross-origin frame. The burst opened one window; the expired interaction opened none.

This is a bounded user-interaction policy, not a guarantee that every ad will be recognized. A site can use a real click for an unwanted window; long-running asynchronous popup flows can require an explicit site exception. Destination filtering is implemented in the follow-up below; unknown ad destinations can still require filter-list updates. Physical-device checks are still needed.

## Original findings

1. **High: synthetic links and forms bypass the popup blocker.** `BrowserTab.webView(_:createWebViewWith:for:windowFeatures:)` treats `.linkActivated` and `.formSubmitted` as proof of a user gesture. In the simulator, timer-driven `anchor.click()` and `form.submit()` each opened a new tab with `navigator.userActivation.isActive == false`, while popup permission was disabled. An iframe's synthetic anchor also bypassed the policy. Disabling `javaScriptCanOpenWindowsAutomatically` did not prevent either synthetic-link or synthetic-form case in this fixture. Navigation type alone is therefore insufficient; changing that preference alone is not a fix.

2. **Medium: popup exceptions apply to embedded third-party frames.** The permission lookup uses `webView.url`, the top-level page, rather than the initiating frame's origin. A cross-origin iframe calling `window.open()` opened a tab when only its parent origin had popup permission. Scope exceptions to the intended initiating origin and explicitly decide whether embedded content may use them.

3. **Needs device validation: legitimate script-driven popup flows can be rejected.** The same delegate rejects `.other` navigation without a site exception, without checking whether a real tap initiated it. Test login, payment, and player buttons that call `window.open()` from a trusted touch before replacing the policy. The automated audit did not simulate trusted finger input, so it does not establish which legitimate flows fail.

## Original WebKit observations

Executed `bash mobile/apple/tests/run-browser-startup-checks.sh --popup-audit` on the iOS 26.4 simulator, using production browser delegate/store code and a local HTTP fixture. All popup cases reported no JavaScript user activation.

| Case | Observed result |
| --- | --- |
| Timer-driven `window.open()`, permission off | Blocked; notice shown |
| Timer-driven synthetic link click, permission off | New tab opened |
| Timer-driven form submission, permission off | New tab opened |
| Background tab synthetic link click | Blocked |
| `window.open()`, origin allowed | New tab opened |
| `window.open()`, allowance revoked | Blocked; notice shown |
| Cross-origin iframe synthetic link, permission off | New tab opened |
| Cross-origin iframe `window.open()`, parent allowed | New tab opened |
| Synthetic link with native automatic windows disabled | New tab opened |
| Synthetic form with native automatic windows disabled | New tab opened |
| Timer-driven same-tab location change | Navigation allowed |

Same-tab redirects are outside the current new-window policy. This does not by itself justify blocking all redirects: normal websites, authentication, and media players depend on them.

The harness uses a stub content blocker with no live filter lists, isolating popup policy from URL filtering. Real ad lists may block a known destination, but this test does not measure filter-list coverage and those lists do not correct the generic policy bypass. Physical-device, trusted-touch, popunder/focus, and live advertising-site behavior remain unverified. Audit completion reports observations; it is not a passing assertion that all popups are blocked.

## Original recommended follow-up

Replace the navigation-type gesture heuristic with a bounded, frame-aware interaction policy, preserving real new-tab links and legitimate forms. Validate any JavaScript/native interaction bridge against synthetic events and avoid treating arbitrary page messages as authorization. Add regression coverage for the bypasses above, exception origin boundaries, and real user interactions before declaring the blocker complete.


## Destination blocking follow-up

Added a native top-level navigation check alongside the interaction policy:

- Direct ad popup destinations are rejected before creating a child tab, including when the popup itself had user interaction or site permission.
- Same-tab ad navigation is canceled, preserving the committed page and URL. Final main-frame responses are also checked for server redirects before committing the document. A server redirect may already have fetched its destination before this response check; this is not a promise of zero network contact.
- Script-created tabs which redirect to ads before committing real content are closed and selection returns to their opener when appropriate. Existing committed pages are never automatically closed. A site that opens a copy of the video and redirects its original tab keeps both video pages rather than risking loss through speculative deduplication.
- Navigation rules refresh with normal and forced filter-list compilation, and respect the global ad-blocking toggle. Explicitly user-blocked source hosts also block navigation.

`NavigationAdRules` is a separate host-indexed matcher, avoiding the detector's lossy host-only projection. It supports host-anchored rules, paths/wildcards/separators, popup/document/all scopes and negations, match-case, positive and negative source-domain constraints, exceptions, and badfilter removal. Untyped whole-host rules can block document navigation; untyped path block rules apply to popups only, while path exceptions can exempt document navigation. Unsupported resource, party/PSL, regex, rewriting, priority, and other options are skipped rather than broadened. This is a conservative subset, not full uBlock Origin parity. Built-in ad-network hosts provide fallback coverage, while matching supported exceptions take precedence over that fallback.

Validation: `--ad-navigation` tests direct popups, same-tab redirects, HTTP redirects, removal of uncommitted ad tabs, tab swaps, normal redirects, ad-block disablement, and filter matching boundaries/exceptions. It uses production navigation and matcher code with local fixture rules. Rotating live ad destinations on the reported websites have not been verified; blocking depends on supported rules matching those destinations.
