# On-Video Cast Overlay — Implementation Prompt

Use this prompt with an implementation agent working in the PlayBridge repository.

## Repository and task

You are working in:

```text
/Users/atulmehla/repos/personal/playbridgeapp/PlayBridge
```

Add a non-destructive PlayBridge cast-button overlay to web videos in the browser extension. Put the feature behind a persisted toggle in the extension's popup settings.

Read and obey the repository's `AGENTS.md` before making changes. Use the code-review graph workflow first, then focused `rg` searches and file reads where JavaScript graph coverage is incomplete.

Do not commit, push, release, or open a pull request unless separately requested.

## Product outcome

When **Show cast button on videos** is enabled, display a small, polished cast icon over the primary visible video on supported pages. Clicking it must cast the correct detected media URL through PlayBridge's existing desktop/native bridge.

The control may feel familiar to users of Google Cast, but it must use a generic original cast glyph and PlayBridge styling. Do not use Google branding, assets, SDKs, or services.

The overlay must not replace, wrap, reparent, pause, or otherwise modify the site's video element. When the setting is disabled, the content script must remove its UI and stop unnecessary observation until re-enabled.

## Inspect before implementation

- `extension/src/content.ts`
- `extension/src/background.ts`
- `extension/src/browser.ts`
- `extension/src/native-bridge.ts`
- `extension/src/ui/popup.ts`
- `extension/src/ui/popup.html`
- `extension/src/ui/popup.css`
- `extension/manifests/chrome.json`
- `extension/manifests/firefox.json`
- `extension/build.mjs`
- `extension/package.json`

Treat TypeScript files as the source of truth. Do not implement the feature in checked-in stale files such as `extension/src/content.js`, `extension/src/background.js`, or `extension/src/ui/popup.js`. Confirm whether those files are intentionally retained before modifying or deleting them.

## Existing architecture

1. `extension/src/content.ts` is intentionally empty and is not currently built or referenced by a manifest. This task intentionally reintroduces a content script only for a lightweight video-local overlay, not the old page-wide FAB or injected popup.
2. `extension/src/background.ts` detects HLS, DASH, direct video files, and subtitles with `webRequest`. It captures useful request headers and stores `VideoData` per tab.
3. `VideoData` includes the media URL, tab, content type, detection method, origin URL, timestamp, and optional headers, subtitles, and HLS qualities.
4. Casting already flows through `wsPlayOnTv` to `castVideo(video)` and then `bridge.cast(url, headers, title)`. Reuse this route. Do not create another native connection, WebSocket, receiver-selection system, or protocol.
5. Existing background messages include `getVideos`, `wsGetStatus`, and `wsPlayOnTv`.
6. The detected `VideoData` must remain the source of truth because it can include request headers required by protected streams.
7. Chrome uses Manifest V3 with a service worker. Firefox uses Manifest V2 with a persistent background page. Support both.

## FastStream reference

Use this code for design ideas only:

```text
inspirations/FastStream
```

Inspect:

- `inspirations/FastStream/chrome/content.js`
- `inspirations/FastStream/chrome/background/background.mjs`
- `inspirations/FastStream/chrome/manifest.json`
- `inspirations/FastStream/chrome/player/VideoSource.mjs`

Relevant ideas are its all-frame content script, open-shadow-root video discovery, visible-area calculation, largest-video selection, frame-aware network-source tracking, and preference for a network-detected source over the DOM URL.

Do not copy FastStream's player replacement behavior. PlayBridge must leave the site's player intact.

## Feature setting

Add a dedicated **Settings** tab or clearly identifiable Settings section to the extension popup. Prefer a dedicated tab so the preference is discoverable and not mixed into TV connection state.

Add an accessible switch or checkbox:

- Label: **Show cast button on videos**
- Supporting text: **Display a PlayBridge cast control on the primary video on each page.**
- Storage key: use a clear namespaced constant such as `showVideoCastOverlay`
- Storage: `browser.storage.local`
- Default: enabled, unless established extension conventions clearly favor opt-in; document the final choice

Requirements:

1. Load the saved value when the popup opens.
2. Save immediately when changed.
3. Synchronize the setting across all open content-script contexts without requiring a page reload. Use `browser.storage.onChanged` in the content script, or broadcast a typed runtime message after persistence.
4. A newly loaded content script must read the setting before creating UI.
5. Disabling must immediately remove the overlay, disconnect observers, cancel scheduled work, and detach listeners.
6. Re-enabling must restart discovery and render the overlay when an eligible video exists.
7. Changing the setting must not affect toolbar video detection, badge counts, popup lists, context menus, or background network observation.
8. Avoid a flash of overlay before the stored setting has loaded.
9. Use the existing `storage` permission; do not add unnecessary permissions.

Centralize the storage key/default in a small shared module if that can be bundled safely into both popup and content entry points without creating circular dependencies.

## Content script and manifests

1. Implement `extension/src/content.ts` as a bundled content script.
2. Add it to `extension/build.mjs` entry points so each target emits `content.js`.
3. Declare it in both manifests with `<all_urls>`, all applicable frames, and preferably `document_idle`.
4. Support related `about:blank` or blob-created frames where the browser's manifest format permits it.
5. Keep it in the default isolated world.
6. Do not add the `scripting` permission when a declarative content script is sufficient.
7. Restricted browser pages where scripts cannot run must fail quietly.

## Frame-aware media records

Extend the background's internal `VideoData` with an optional `frameId`, populated from `webRequest` details wherever reliable. Preserve compatibility with records already persisted in `storage.session` without this field.

For messages from content scripts, derive authority from `sender.tab.id` and `sender.frameId`. Do not trust a tab or frame ID supplied by page-controlled input.

Add a safe frame-aware retrieval message or extend `getVideos`. Return only records belonging to the sender's tab and preferably its frame when reliable frame metadata exists.

## Select the playable URL correctly

Do not blindly cast `video.src` or `video.currentSrc`. Players may use direct files, `<source>` children, MSE `blob:` URLs, JavaScript-loaded HLS/DASH, or protected requests requiring captured headers.

Create a deterministic candidate-ranking helper with this order:

1. Exact normalized match between a detected record and `video.currentSrc`.
2. Exact normalized match against `video.src` or a `<source src>` child.
3. A same-frame network record, preferring an HLS master playlist, then DASH manifest, then direct MP4/WebM/etc.
4. The newest plausible same-frame detection.
5. A newest plausible tab candidate only when frame metadata is unavailable and the association is not obviously ambiguous.

Exclude subtitles and media segments such as `.vtt`, `.srt`, `.ts`, `.m4s`, fragments, and segment URLs.

Treat `blob:` and `data:` DOM URLs as matching/context signals only. Never send them to the TV. For an MSE player, choose its associated network-detected HLS, DASH, or direct source. Preserve the detected record's stored headers.

If there is no credible candidate, hide or disable the overlay and provide a short **No castable stream detected yet** state when useful. Never substitute the page URL.

## Select the primary video

Avoid controls on tiny previews, advertisements, animations, and hidden players.

Within each frame:

- Discover videos in the document and reachable open shadow roots.
- Ignore disconnected, hidden, zero-sized, or nearly invisible elements.
- Use a documented minimum size, approximately 200 by 112 pixels or equivalent area.
- Intersect `getBoundingClientRect()` with the viewport to calculate visible area.
- Prefer the largest visible eligible video, following FastStream's useful selection approach.
- Move the overlay if the primary video changes.
- Keep video selection, candidate ranking, rendering, and casting separate.

Start with one overlay per frame's primary video. Do not put a control on every video unless the implementation can reliably associate a detected stream with each element.

## Overlay rendering

Do not wrap or reparent the video. Append a single extension-owned overlay host, preferably to `document.documentElement`, and isolate its styles with Shadow DOM.

- Position it with the selected video's bounding rectangle.
- Use `position: fixed` with a 12–16 pixel top-right inset.
- Use a high but reasonable `z-index`.
- Set host `pointer-events: none` and button `pointer-events: auto`.
- Coalesce layout work with `requestAnimationFrame`.
- Safely support fullscreen, attaching the overlay under the fullscreen subtree if browser behavior requires it without moving the video.

React to DOM mutations, media source events, resize, capture-phase scroll, `ResizeObserver`, fullscreen changes, SPA navigation, and player replacement. Do not use high-frequency polling. Clean up observers and listeners when disabled or no longer needed.

## Visual and accessibility requirements

- Use a local inline generic cast SVG or CSS glyph; no remote assets.
- Use a 36–44 pixel circular or rounded-square target.
- Use a dark translucent background and PlayBridge accent `#D0BCFF` where appropriate.
- Provide hover, focus-visible, pressed, disabled, casting, success, and error states.
- Honor `prefers-reduced-motion`.
- Use a native `button` with `type="button"`.
- Add `aria-label="Cast this video with PlayBridge"` and a useful title.
- Use native keyboard behavior.
- Stop propagation only for the overlay interaction; do not block player input.
- Put local status in an `aria-live="polite"` region inside the Shadow DOM.

## Casting interaction and security

On click:

1. Prevent duplicate in-flight requests.
2. Re-resolve the media candidate.
3. Request `wsGetStatus`.
4. If unavailable, show **Open the PlayBridge desktop app.**
5. If the desktop is connected without a TV, show **Connect a TV in PlayBridge.**
6. If connected, request a cast using the detected record.
7. Show temporary casting and success states.
8. Restore idle state and expose a concise reason on failure.
9. Never use `alert()`.

Prefer sending only a detected URL identifier from the content script. The background must derive tab/frame identity from `sender`, find the stored record, use its stored headers, and call the existing `castVideo()` helper. Do not accept content-script-provided authentication headers as authoritative.

Treat content-script input as untrusted. Validate actions and payloads, ensure the media belongs to the sender's tab, use DOM construction and `textContent`, and never log captured headers, authenticated URLs, tokens, native host data, or credentials.

## Verification

Run:

```bash
cd /Users/atulmehla/repos/personal/playbridgeapp/PlayBridge/extension
npm run build
```

The build must succeed for `dist/chrome` and `dist/firefox`. Confirm both generated manifests reference an emitted `content.js`.

Manually verify:

1. Setting defaults correctly on a fresh profile.
2. Disabling removes an existing overlay without reload.
3. Enabling discovers an existing video without reload.
4. The setting persists across popup and browser restarts.
5. One direct MP4 video.
6. Nested `<source>` elements.
7. Dynamically inserted SPA video.
8. A `blob:` player backed by detected HLS.
9. One large video plus small previews.
10. Permitted iframe videos.
11. No-video pages.
12. Desktop app absent.
13. Desktop connected without a TV.
14. Successful casting.
15. Fullscreen entry and exit.
16. Player removal or replacement.
17. A stream requiring captured headers.

Confirm no regressions in toolbar media listing, badge updates, context-menu casting, Chrome MV3 rehydration, Firefox HLS body sniffing, or native bridge status.

## Out of scope

Do not replace the website player, build a custom player, add Google Cast dependencies, change protocols or versions, remove the popup, weaken header capture, cast raw blob URLs, or broaden the task into unrelated cleanup.

## Final report

Report:

1. Files changed.
2. Setting key, default, persistence, and live synchronization behavior.
3. Primary-video selection behavior.
4. DOM-to-network-source association behavior.
5. How stored headers remain protected.
6. Chrome and Firefox manifest differences.
7. Verification performed.
8. Known limitations, especially ambiguous multi-player pages and closed shadow roots.

Before editing, inspect the worktree and preserve unrelated user changes. Summarize the intended architecture before implementation.
