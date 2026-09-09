# Browser network logs

Open the browser hamburger menu → **Network logs**. Activity is collected while
a tab is loaded, so opening the screen does not require a reload to start capture.
Search by domain, URL, request type, or state. Embedded-frame requests are marked
**Frame**, and inline srcdoc frames have an explicit source label. Tap an entry for details and its
**Block domain** action; the same action is available from the row's context menu.
Saved domain rules apply to the exact host and subdomains on all pages. They
remain in effect on playback compatibility sites when ad blocking is enabled.
Unblock from an entry or Adblock settings. Reload from the log toolbar to retry
existing resources; adding a rule does not automatically interrupt playback.

The log records fetch/XHR attempts and exposed responses/failures, resource timing
entries (images, scripts, styles, media, etc.), frame activity, browser navigation
attempts/responses, and native ad/popup-policy blocks. JavaScript instrumentation
cannot expose every WebKit request: workers, sockets, some media internals,
intermediate redirects, and requests omitted by content blocking/resource timing
may be absent. Websites can also replace page-world hooks. A status of zero or
an opaque response is not presented as a confirmed HTTP error; “Failed or
blocked” does not imply a confirmed ad-block match. Resource timing may describe
cached resources, and navigation events can include both request and response
rows.

Each tab retains at most 1,000 entries across navigation, until cleared or closed.
Entries are not persisted. URL credentials, fragments, query values, signed path
components, and long opaque path components are redacted before entering the
model. No headers or request/response bodies are captured. The copy actions
export only this redacted representation. Source attribution uses WebKit's
message frame URL rather than page-provided origin metadata.

Validation: simulator fixtures `--network-log` (including inline srcdoc frames)
and `--network-log-ui` (details-screen block/unblock confirmation), the broader browser fixture, and
an iOS Simulator app build. Live-site completeness is not guaranteed.


Domain-rule updates compile only the user's domain list; unrelated downloaded
filter errors no longer report a domain action as failed. Failed domain changes
are rolled back. WebKit-compatible separate host-boundary rules replace the
unsupported alternation/end-anchor expression. Navigation notifications expire
after 2.5 seconds, have a dismiss button, and are throttled to one per eight
seconds while all blocked events remain available in the network log.

Blocking an ad-server domain is different from skipping a pre-roll served by the
video player itself. Blocking the player's own host can also prevent playback.
No blanket iframe blocking or generic pre-roll skipping is implemented.
