# iOS tab switcher

The switcher uses compact rows based on Android's `TabsScreen.kt`: title and URL,
active-tab highlight, a close button, and an indicator for media currently playing
in that tab. Isolated scripts observe playback events and advancing media time in
all frames, including cross-origin iframe players. Pause/end events clear the
indicator; reports expire when frames are removed or suspended. WebKit's aggregate
playback state is not used because it can remain playing after an interruption. Inactive titles use one line; the active title
can wrap to two lines. Search matches titles and URLs. Native app typography uses
the same bundled Poppins Regular face as Android, with Dynamic Type scaling.

Opening the switcher scrolls to the active row after scroll targets are registered.
A floating arrow follows the scrolling direction and jumps to the top or bottom
of the filtered list. It points down at the top and up at the bottom, stays
available for three seconds after scrolling, and hides when the list fits onscreen.
The control is a sibling of the scroll view and uses a native finger-down action
that cancels the current pan/deceleration before jumping. VoiceOver activation
uses the same action.

Long-press a row to duplicate, bookmark, copy or share its link. Duplicates open
immediately after their source in the background and preserve the title and
desktop-site preference; they load the URL when selected rather than copying
WebKit's private navigation/session state.

The toolbar menu offers Select tabs and Close all tabs. Selection mode supports
select/deselect all visible search results, batch bookmarking, and batch closing.
Bulk closure asks for confirmation and leaves one fresh home tab when all tabs
are closed. The store closes a batch without selecting intermediate tabs.

Rows do not create background web views or take page snapshots. Favicons use the
same Google favicon service as Android, sending only the hostname, never page
paths or query parameters. Non-web URLs, local IPs and `.local` hosts keep local
fallback icons. A dedicated HTTP cache stores cacheable responses on disk;
decoded images are bounded in memory, same-host requests are coalesced, and
failures have a retry delay. Cached images are read synchronously during row
rendering, so recreated rows do not flash the globe. Uncached icons use a neutral
placeholder; the globe is reserved for unavailable icons and unsupported URLs.

Checks: `run-browser-startup-checks.sh --tab-management` covers store operations;
`--tabs-ui` checks active-row visibility, both jump buttons, and the SwiftUI
row/search/context-menu and batch-close flows, selected-only title wrapping, and
jump-button placement outside the scroll view. Startup checks also verify font
registration and favicon decoding, request coalescing, caching and failure backoff.
