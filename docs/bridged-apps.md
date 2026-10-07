# Bridged Apps (Android and iOS v1)

A Bridged App is a website opened inside PlayBridge's existing browser (GeckoView on Android, WKWebView on iOS) with its toolbar hidden. It uses the same `window.playbridge.cast()` and `window.playbridge.linkCast()` APIs as a normal browser tab. Installation adds a tile to the PlayBridge dashboard; it does not create a system home-screen shortcut or grant casting permission. Public sites require HTTPS. Local development servers on literal private IPv4 or loopback addresses, or `localhost`, may use HTTP.

To opt in, serve `/.well-known/playbridge-app.json` from the same origin as the site:

```json
{
  "protocol": "playbridge-app-v1",
  "name": "Example",
  "start_url": "/",
  "icon_url": "/icon.png"
}
```

`start_url` and `icon_url` must resolve to the manifest's origin. The name is limited to 60 characters. The document must return HTTP 200 without redirect and be at most 16 KiB. The browser checks the origin when a page loads and offers **Add Bridged App** in its menu. The icon is optional.

Installed apps appear as tiles beside **Cast History** on the second tile page. Swipe left across the dashboard tiles or tap the second dot below them to reach it. The dashboard header and connection status stay in place. Tap an app to reopen its live app session, or its saved **Home URL** (initially the manifest’s `start_url`) when starting a new session. After closing and reopening PlayBridge or a fresh process launch, installed apps start from that home URL, not their last deep link; cookies/logins remain unchanged. Switching through Dashboard or Remote during the same session keeps the app’s current page. App sessions stay out of the normal tab switcher and its count, search, and close actions. Long press an app tile to open **Bridged App Info**, showing its editable name and Home URL plus its website origin. Save persists edits without interrupting a loaded app; Cancel discards the draft. Names must contain 1–60 characters, and home URLs must remain on the installed origin with the existing HTTPS/local-HTTP policy and no URL credentials. Changing website origins requires removing and reinstalling that site, not moving its permissions. **Remove Bridged App** asks for confirmation, removes the tile and closes its session without deleting website data or casting grants. Dashboard tile order remains unchanged by edits. In app mode, Android Back navigates within the site; at the first page, it returns to the dashboard. On iOS, use **Back** in the edge menu (or swipe through existing page history). An external web link opens in a normal browser tab while the app keeps its place. Existing website casting and private-network permissions still apply. The **Browser** tile restores an ordinary browser tab. Both platforms persist installations and edited home URLs. App tabs restore at their home URLs after a cold launch; both platforms keep pages lazy until Browser or an app is opened. Ordinary browser tabs retain saved history. Android also leaves a replacement tab unloaded when closing tabs in the tab switcher; see [Android browser lifecycle](android-browser-lifecycle.md).

On Android and iOS, a valid same-origin `/.well-known/playbridge-app.json` declaration disables automatic media detection for the entire origin, including ordinary browser tabs and embedded frames. Both platforms also disable it immediately for installed bridged-app sessions. Android's **Media detect → Advanced → Detect on bridged sites** override can enable selected detector features there; it is off by default. App pages with detection disabled do not start the detector’s DOM observers, player probes, visibility overrides, header capture, or response-body scanning. The lightweight `window.playbridge` casting API and its navigation/session checks stay available. Undeclared websites retain their existing detection behavior. Declaration results are cached for five minutes, and unavailable or invalid manifests do not opt an ordinary website out. Android stops detection once a new origin is recognized; iOS checks before allowing the document to load. Turning detection off also stops observers and pending probes in already-open browser pages and detaches active response scanners.

The frosted edge menu includes **Remote**. Opening it from a bridged app keeps that app's session and position; the Remote return arrow (and Android Back) reopens the same app. If the app was removed, its tab was closed, or the tab left its app origin, return goes to the dashboard instead. On iOS the edge menu also offers **Back**, **Dashboard**, **Connect TV**, and **Reload**. In iPhone landscape mode the handle stays on the side opposite the front camera, with its chevron pointing inward; portrait keeps it on the right. Closing Dashboard returns to the app which opened it. On iOS the handle hides while a main-page Movi player is fullscreen, including its canvas-based CSS fallback. Inline playback keeps it visible; pausing or buffering in fullscreen keeps it hidden. Exiting fullscreen, removing the player, or leaving the document restores it. This uses a separate lightweight fullscreen observer and does not enable media or image detection.

Linked website casts do not display a mini playback bar over browser or bridged-app pages. Remote shows **Controlled by [website]** with an **Unlink** action while a website controls the session. Unlink releases website control while the current TV playback continues; Remote's playback and queue controls remain available.

## Unified playback destinations

Android and iOS advertise `playbridge.capabilities.playback === 1`. The selected native destination is authoritative, including an explicit **This device** selection. Websites can display and change it without starting media, but the destination identity is consent-gated:

```ts
const { destination } = await playbridge.getPlaybackDestination()
// Before website-casting consent: { id: null, name: null, kind, connected }
// After consent: { id, name, kind: 'local' | 'native' | 'external', connected }
await playbridge.choosePlaybackDestination() // opens the existing native destination picker
await playbridge.choosePlaybackDestination({ destinationId: 'this-device' }) // explicit local recovery
const session = await playbridge.play({
  destinationId: destination.id,
  // Only for This device, when capabilities.localPlaybackOrientation === 1:
  initialOrientation: 'landscape', // 'auto' | 'portrait' | 'landscape'
  items: [{ id: 'episode-1', url: 'https://media.example/one.mp4', startPositionMs: 120000 }],
  startIndex: 0
})
```

Before the origin has website-casting consent, `getPlaybackDestination()` returns only `{ id: null, name: null, kind, connected }`. `kind` and `connected` are not identifying. After consent, `name` is the device name and `id` is `this-device` for phone playback, or HMAC-SHA256 of an install secret (generated once and stored only on the device) over `origin`, a NUL byte, and the receiver endpoint key. The raw `protocol:stableId` endpoint key is not sent to websites, and two origins do not receive the same id for one receiver. `play()` must pass the id from this call. `this-device` remains the literal id for explicit local playback. A previously learned raw endpoint key does not match.

`choosePlaybackDestination()` and `{ destinationId: 'this-device' }` require a user gesture. The isolated content script reads `navigator.userActivation.isActive` and native code checks that attestation; a page script cannot supply it. Without an attested gesture, native rejects with `user_gesture_required`. While a receiver is connected, `this-device` opens the native picker instead of disconnecting it. A website never disconnects a live receiver by itself.

`play()` uses the linked-session event and `provideItems()` contract. Phone playback opens the existing native fullscreen player; a selected receiver uses its normal native transport. Resume positions, explicit media headers, metadata and supported subtitles travel with the items. Website and private-server permissions apply to playback just as they do to casting, and linked-session operations re-check that consent. The requested destination is checked again after asynchronous preparation; a changed or disconnected target rejects the request, allowing the website to offer reconnect or explicit local playback.

Phone hosts advertise `capabilities.localPlaybackOrientation === 1`. The optional top-level `play()` field `initialOrientation` accepts only `auto`, `portrait`, or `landscape` and is honored only for **This device**. Omission/`auto` preserves the host’s existing orientation policy; explicit choices set the opening orientation without removing native rotation controls. iOS requests scene geometry once and restores the preceding page on dismissal; if the OS refuses the request, playback continues with a visible explanation. Orientation is session presentation state, not receiver playlist metadata, and episode changes do not reopen or rotate the player. Websites must feature-detect this capability rather than assume older hosts support it.

Local playback and native PlayBridge receivers support the website's lazy episode queue. The Android and iOS phone players expose a live **Queue** with next/previous controls; it updates when the website supplies items. Queued episodes advance automatically, and a late supply can continue after the current episode ends. Only resolved items appear in the queue, so Next stays disabled until another item is supplied. The owning page must remain loaded to resolve further episodes; already-delivered items remain playable after Unlink. External receivers retain their existing single-item capabilities; they do not request next episodes. Unsupported external queues or subtitle delivery fail explicitly. Unlinking releases website control and progress reporting while playback continues. Existing `cast()` and `linkCast()` remain available for compatibility. A website without the bridge uses its own web player.

## Website progress callbacks

Website requests must not contain `progressWebhook` at the envelope, payload,
or item level, even with a null or false value. Linked requests fail with
`invalid_request`; legacy fire-and-forget casts are discarded. Use linked
session events for page progress. See the [page-API policy](../protocol/page-api/README.md);
this restriction does not remove trusted-sender WSS webhook support.

## Device plugins on Android FOSS

Library and installed Bridged Apps share the device's Nuvio plugin manager and resolver. The resolver is opt-in and initially disabled. The Play flavor does not include the QuickJS plugin runtime; iOS has no native plugin resolver. Library remains accessible during the transition to Streams. Device plugin installation, provider settings and approvals stay on the device and are not imported from a website or its Nuvio cloud profile.

The GeckoView page API extends the existing `window.playbridge` object:

```ts
playbridge.capabilities.nativePlugins // 1 = supported, 0 = unavailable
await playbridge.plugins.status() // available, enabled, installed provider identifiers and approval state
await playbridge.plugins.resolve({
  repoUrl: 'https://plugins.example/manifest.json',
  scraperIds: ['example'], tmdbId: '60625', mediaType: 'tv', season: 8, episode: 2
}) // streams and safe warnings
await playbridge.plugins.manage() // opens the shared device manager from a user gesture
playbridge.plugins.cancel() // cancels this document's outstanding resolutions
```

Only the installed app's top-level document may use the native endpoint. The sender's actual GeckoSession, tab and origin are checked by Android; declaring an app manifest alone does not grant access. Release builds require HTTPS app origins; debug builds also accept local HTTP development origins. Requests contain installed repository and scraper identifiers, never downloaded code, arbitrary HTTP requests, provider settings or credentials. Resolve requests accept at most 32 distinct scraper IDs, and a document may have at most four pending bridge requests. Closing the document or changing its hash route cancels lookups. Native failure, disabled providers and pending approvals must not fall back to executing website plugin code.

Plugin resolution does not remove browser restrictions on playback. A returned stream may still require provider headers, CORS support, a compatible codec, or native casting. The resolver's network client is separate from browser and app authentication, and provider domain access must be approved in device settings.
