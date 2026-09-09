# iOS adblock review — 2026-09-08

Scope: ContentBlocker, BrowserStore/BrowserTab integration, picker bridge,
VideoDetector filtering and AdblockSettingsSheet in the current working tree.
Paths below are relative to `mobile/apple/PlayBridge Phone/PlayBridge Phone/`.
This is a source review plus a real-WebKit simulator regression for the picker;
it is not a full live-site/filter-list compatibility audit.

## Fixed in this pass

**Linked elements navigated while picking.** The picker removed click/touchend
listeners after its first selection. The later synthetic click and subsequent
interactions could reach the underlying anchor. `stopPropagation` also did not
stop other listeners on the same node, and underlying iframe content was not
covered by an input layer.

The picker now installs a transparent hit layer above the page, hit-tests beneath
it to select the real element, and keeps capture-phase interception active through
selection, Up/Down, Preview, Block and Cancel. It suppresses trailing clicks during
cleanup. Native BrowserTab navigation and new-window creation are also guarded
while picking, with a native Cancel button and explicit cleanup for user navigation.
Panel queries are scoped to the panel instead of trusting page-wide element IDs.

**Picker bridge requests were accepted outside picker mode.** Custom-rule and
resource-block messages now require an active picker and the main frame. Cosmetic
rules use the frame's URL host instead of the untrusted `host` message field.
This reduces unintended rule injection; it is not a claim of full isolation from
scripts in the same JavaScript world while the picker is running.

## Remaining findings, in priority order

### P1 — Stream detection does not share WebKit's rule semantics

`Browser/ContentBlocker.swift:1129` builds a separate in-memory matcher by taking
the domain from `||` filters and discarding paths and options. It ignores `@@`
exceptions, resource types and site restrictions. `Browser/VideoDetector.swift:169`
drops media when this matcher returns true.

Example: `||cdn.example/ads/$script` becomes a match for the entire CDN in the
detector. A legitimate media URL on that host can disappear from the cast sheet
even though it does not match the WebKit script/path rule. The in-memory snapshot
also is not invalidated by ordinary `compileAll()` updates; only force compilation
explicitly resets it.

Next: share a conservative parsed policy with detection, or stop using filters
whose media applicability cannot be established from the available context.
Test media alongside script/path/domain-restricted rules and matching exceptions,
including filter-list changes during a session.

### P1 — Negative resource types broaden blocking

`Browser/ContentBlocker.swift:682` ignores negated resource types. A filter such as
`||cdn.example^$~media` therefore blocks media as well as the intended other types.

Next: implement resource-type subtraction or skip unsupported constrained rules.
Test positive, negative and mixed resource types, including child-frame documents.

### P1 — Typed exceptions allow more than intended

`Browser/ContentBlocker.swift:700` emits every exception without a resource type,
even when the parsed rule specifies one. `@@||ads.example^$image` can exempt scripts
and media from prior matching blocks too.

Next: preserve exception scope using the same type/context mapping as block rules.
Test an allowed image and a still-blocked script from the same host.

### P2 — Mixed domain inclusion/exclusion loses the exclusion

`Browser/ContentBlocker.swift:491` and `:697` choose positive domains OR negative
domains. When a rule has both, the negative list is discarded. For example,
`example.com,~shop.example.com##.banner` also hides matching elements on the
explicitly excluded shop subdomain. Network `$domain=` rules have the same issue.

Next: represent the combined scope correctly, or skip combinations that cannot
be represented safely. Do not simply emit unsupported combinations of WebKit
trigger fields. Test parent-domain inclusion with a subdomain exclusion.

### P2 — Some host filters lack a hostname boundary

`Browser/ContentBlocker.swift:869` and the in-memory matcher use a host prefix
without requiring the end of the hostname. Blocking `ads.example` can also match
`ads.example-other.test`. The regular network parser already handles boundaries
more carefully, so these paths disagree.

Next: reuse one host-boundary builder for built-in, user-source, curated and
in-memory rules. Test exact host, legitimate subdomain, port, and lookalike suffix.

### P2 — Site exemptions also disable explicit user blocks

`Browser/BrowserStore.swift:246` removes all rule lists for the YouTube exemption,
including element-picker rules and user-blocked sources. An element can disappear
immediately after Block but reappear on reload because its saved rule is not attached.

Next: distinguish compatibility exemptions for automatic lists from explicit user
rules, and make the exemption visible in the adblock UI. Verify persistence after
reload without reintroducing the playback breakage the exemption was added to avoid.

## Follow-up validation

- `compileAll()`/`forceCompileAll()` await multiple operations and each prunes the
  shared rule cache. Overlapping startup/settings/picker compilations may prune
  another run's desired lists. Validate with overlapping generations and serialize
  or generation-gate publication/pruning if reproduced. The previous missing-cache
  logs alone do not prove this is their cause.
- CSS compilation is isolated from network compilation, which is useful, but one
  rejected selector can still discard a source's cosmetic list. Surface partial
  success and consider isolating invalid selector chunks.
- Unsupported scriptlet/extended-cosmetic syntax is intentionally omitted. Do not
  present the EasyList subset as full uBlock/Android-extension compatibility.

## Verification

The updated `run-browser-startup-checks.sh` bundles the exact production picker JS
into its isolated simulator app. It verifies outside-picker rule rejection,
linked-element selection with touchend followed by click, suppression of page
handlers/popups, hiding and verified-host reporting, cancel without saving, and
restoration of normal navigation. The broader browser regression also passes.
The iOS simulator app build passes. Physical-device touch interaction and selection
inside complex live-site layouts remain useful acceptance checks.
