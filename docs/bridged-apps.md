# Bridged Apps (Android and iOS v1)

The [`bridged-app-development` skill](../.agents/skills/bridged-app-development/SKILL.md) is the workflow guide for building one. Update it when this page changes how sites declare, play or test.

A Bridged App is a website opened inside PlayBridge's existing browser (GeckoView on Android, WKWebView on iOS) with its toolbar hidden. It uses the same `window.playbridge.cast()` and `window.playbridge.linkCast()` APIs as a normal browser tab. Installation adds a tile to the PlayBridge dashboard; it does not create a system home-screen shortcut or grant casting permission. Public sites require HTTPS. Local development servers on literal private IPv4 or loopback addresses, or `localhost`, may use HTTP.

To opt in, serve `/.well-known/playbridge-app.json` from the same origin as the site:

```json
{
  "protocol": "playbridge-app-v1",
  "name": "Example",
  "start_url": "/",
  "icon_url": "/icon.png",
  "theme_color": "#16433F"
}
```

`start_url` and `icon_url` must resolve to the manifest's origin. The name is limited to 60 characters. The document must return HTTP 200 without redirect and be at most 16 KiB. The browser checks the origin when a page loads and offers **Add Bridged App** in its menu. The icon is optional. `theme_color` is optional too: only `#RGB` or `#RRGGBB` hex (case-insensitive) is accepted; named colors, `rgb()`, alpha and other formats are ignored.

Dashboard tiles use the user's saved **Tile color** choice first. **Automatic** uses the site's `theme_color` when valid, otherwise a palette color assigned once at install. The assignment picks the least-used color among the other installed apps (their saved choice, or their own automatic color when they don't show a site color), starting from FNV-1a over the UTF-8 origin (without a trailing slash) so it is deterministic. Installed apps therefore get distinct colors until all ten are in use, and the saved color never changes when apps are reordered, added or removed. Apps installed before this existed are assigned once, in install order, on the first launch after updating. Each phone assigns from its own installed apps, so the same site can differ between devices. Android and iOS use the same palette, order and assignment rule. Site colors become a darker top-left and slightly lighter bottom-right gradient; bright colors are darkened until white title/icon text has WCAG contrast of at least 4.5:1 against both stops. **Tile color** in Web app details offers Automatic and named palette swatches. Save applies the draft; Cancel discards it. Name and Home page edits preserve the color. New declarations update the saved site color through existing declaration checks, without a separate color request, and never override a user palette choice.

Artwork uses the declared same-origin `icon_url` first. If it is absent or cannot load, both current browser hosts use one bounded request for the origin's `/favicon.ico`: their ordinary browser favicon cache uses a third-party service and cannot supply same-origin artwork. No page or manifest crawling is performed. Only HTTPS (or the local HTTP policy above), same-origin icons are accepted, and redirects are rejected. Downloads have a 256 KiB limit, a 2048 × 2048 input limit and the declaration fetch's timeout policy; accepted images are saved locally as PNGs of at most 128 × 128 pixels. A failed lookup is remembered as well. If neither icon is usable, a white uppercase first-letter monogram is shown. Dashboard tiles, Customize home and the Info header share this behavior. The cache survives launches, refreshes when the declared icon URL changes, and is deleted on removal; existing installations fill it on first display.

Installed apps appear as tiles beside **Cast History** on the second tile page. Swipe left across the dashboard tiles or tap the second dot below them to reach it. The dashboard header and connection status stay in place. Tap an app to reopen its live app session, or its saved **Home URL** (initially the manifest’s `start_url`) when starting a new session. After closing and reopening PlayBridge or a fresh process launch, installed apps start from that home URL, not their last deep link; cookies/logins remain unchanged. Switching through Dashboard or Remote during the same session keeps the app’s current page. App sessions stay out of the normal tab switcher and its count, search, and close actions. Long press an app tile to open **Bridged App Info**, showing its editable name and Home URL plus its website origin. Save persists edits without interrupting a loaded app; Cancel discards the draft. Names must contain 1–60 characters, and home URLs must remain on the installed origin with the existing HTTPS/local-HTTP policy and no URL credentials. Changing website origins requires removing and reinstalling that site, not moving its permissions. **Remove Bridged App** asks for confirmation, removes the tile and closes its session without deleting website data or casting grants. Dashboard tile order remains unchanged by edits. In app mode, Android Back navigates within the site; at the first page, it returns to the dashboard. On iOS, use **Back** in the edge menu (or swipe through existing page history). An external web link opens in a normal browser tab while the app keeps its place. Existing website casting and private-network permissions still apply. The **Browser** tile restores an ordinary browser tab. Both platforms persist installations and edited home URLs. App tabs restore at their home URLs after a cold launch; both platforms keep pages lazy until Browser or an app is opened. Ordinary browser tabs retain saved history. Android also leaves a replacement tab unloaded when closing tabs in the tab switcher; see [Android browser lifecycle](android-browser-lifecycle.md).

On Android and iOS, a valid same-origin `/.well-known/playbridge-app.json` declaration disables automatic media detection for the entire origin, including ordinary browser tabs and embedded frames. Both platforms also disable it immediately for installed bridged-app sessions. Android's **Media detect → Advanced → Detect on bridged sites** override can enable selected detector features there; it is off by default. App pages with detection disabled do not start the detector’s DOM observers, player probes, visibility overrides, header capture, or response-body scanning. The lightweight `window.playbridge` casting API and its navigation/session checks stay available. Undeclared websites retain their existing detection behavior. Declaration results are cached for five minutes, and unavailable or invalid manifests do not opt an ordinary website out. Android stops detection once a new origin is recognized; iOS checks before allowing the document to load. Turning detection off also stops observers and pending probes in already-open browser pages and detaches active response scanners.

The frosted edge menu includes **Remote**. Opening it from a bridged app keeps that app's session and position; the Remote return arrow (and Android Back) reopens the same app. If the app was removed, its tab was closed, or the tab left its app origin, return goes to the dashboard instead. On iOS the edge menu also offers **Back**, **Dashboard**, **Connect TV**, and **Reload**. In iPhone landscape mode the handle stays on the side opposite the front camera, with its chevron pointing inward; portrait keeps it on the right. Closing Dashboard returns to the app which opened it. On iOS the handle hides while a main-page Movi player is fullscreen, including its canvas-based CSS fallback. Inline playback keeps it visible; pausing or buffering in fullscreen keeps it hidden. Exiting fullscreen, removing the player, or leaving the document restores it. This uses a separate lightweight fullscreen observer and does not enable media or image detection.

Linked website casts do not display a mini playback bar over browser or bridged-app pages. Remote shows **Controlled by [website]** with an **Unlink** action while a website controls the session. Unlink releases website control while the current TV playback continues; Remote's playback and queue controls remain available.

## Unified playback destinations

Android and iOS advertise `playbridge.capabilities.playback === 1`. The selected native destination is authoritative, including an explicit **This device** selection. Websites can display and change it without starting media, but the destination identity is consent-gated:

```ts
const status = await playbridge.getPlaybackDestination()
// Before website-casting consent: { id: null, name: null, kind, connected }
// After consent: { id, name, kind: 'local' | 'native' | 'external', connected }
const { destination } = await playbridge.choosePlaybackDestination() // obtains consent if needed, then opens the native picker
// Or choosePlaybackDestination({ destinationId: 'this-device' }) for explicit local recovery
const session = await playbridge.play({
  destinationId: destination.id,
  // Only for This device, when capabilities.localPlaybackOrientation === 1:
  initialOrientation: 'landscape', // 'auto' | 'portrait' | 'landscape'
  items: [{ id: 'episode-1', url: 'https://media.example/one.mp4', startPositionMs: 120000 }],
  startIndex: 0
})
```

Before the origin has website-casting consent, `getPlaybackDestination()` returns only `{ id: null, name: null, kind, connected }`. `kind` and `connected` are not identifying. After consent, `name` is the device name and `id` is `this-device` for phone playback, or HMAC-SHA256 of an install secret (generated once and stored only on the device) over `origin`, a NUL byte, and the receiver endpoint key. The raw `protocol:stableId` endpoint key is not sent to websites, and two origins do not receive the same id for one receiver. `play()` must pass a consented id from `getPlaybackDestination()` or `choosePlaybackDestination()`. `this-device` remains the literal id for explicit local playback. A previously learned raw endpoint key does not match.

Call `choosePlaybackDestination()` from a user interaction to obtain a usable destination id. For an unapproved origin, the host shows the existing website-casting consent prompt before opening the picker or selecting **This device**. Approval is remembered and the response includes the consented destination id; denial or dismissal rejects with `not_allowed` without opening the picker. A cancelled request or a page that navigates away cannot grant consent or change the destination (`session_ended`). Choosing a destination does not grant private-network media permission; play/cast requests still obtain their own required private-server grants.

`choosePlaybackDestination()` and `{ destinationId: 'this-device' }` require a user gesture. The isolated content script reads `navigator.userActivation.isActive` and native code checks that attestation; a page script cannot supply it. Without an attested gesture, native rejects with `user_gesture_required`. While a receiver is connected, `this-device` opens the native picker instead of disconnecting it. A website never disconnects a live receiver by itself.

`play()` uses the linked-session event and `provideItems()` contract. Phone playback opens the existing native fullscreen player; a selected receiver uses its normal native transport. Resume positions, explicit media headers, metadata and supported subtitles travel with the items. Website and private-server permissions apply to playback just as they do to casting, and linked-session operations re-check that consent. The requested destination is checked again after asynchronous preparation; a changed or disconnected target rejects the request, allowing the website to offer reconnect or explicit local playback.

Phone hosts advertise `capabilities.localPlaybackOrientation === 1`. The optional top-level `play()` field `initialOrientation` accepts only `auto`, `portrait`, or `landscape` and is honored only for **This device**. Omission/`auto` preserves the host’s existing orientation policy; explicit choices set the opening orientation without removing native rotation controls. iOS requests scene geometry once and restores the preceding page on dismissal; if the OS refuses the request, playback continues with a visible explanation. Orientation is session presentation state, not receiver playlist metadata, and episode changes do not reopen or rotate the player. Websites must feature-detect this capability rather than assume older hosts support it.

Local playback and native PlayBridge receivers support the website's lazy episode queue. The Android and iOS phone players expose a live **Queue** with next/previous controls; it updates when the website supplies items. Queued episodes advance automatically, and a late supply can continue after the current episode ends. Only resolved items appear in the queue, so Next stays disabled until another item is supplied. The owning page must remain loaded to resolve further episodes; already-delivered items remain playable after Unlink. External receivers retain their existing single-item capabilities; they do not request next episodes. Unsupported external queues fail explicitly. Website subtitles reach external devices only where PlayBridge can show them (AirPlay on iOS, the TV browser on Android); elsewhere the video plays without them. Unlinking releases website control and progress reporting while playback continues. Existing `cast()` and `linkCast()` remain available for compatibility. A website without the bridge uses its own web player.

## Website progress callbacks

Website requests must not contain `progressWebhook` at the envelope, payload,
or item level, even with a null or false value. Linked requests fail with
`invalid_request`; legacy fire-and-forget casts are discarded. Use linked
session events for page progress. See the [page-API policy](../protocol/page-api/README.md);
this restriction does not remove trusted-sender WSS webhook support.

## Playback authority

Installed bridged apps use the same page casting API and permission model as other websites.
A website supplies its own playable media URLs and may maintain a linked playlist through the existing queue API.
