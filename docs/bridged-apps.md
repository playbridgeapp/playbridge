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

Installed apps appear as tiles beside **Cast History** on the second tile page. Swipe left across the dashboard tiles or tap the second dot below them to reach it. The dashboard header and connection status stay in place. Tap an app to reopen its saved app session or start URL. App sessions stay out of the normal tab switcher and its count, search, and close actions. Long press to remove the tile and close its session. In app mode, Android Back navigates within the site; at the first page, it returns to the dashboard. On iOS, use **Back** in the edge menu (or swipe through existing page history). An external web link opens in a normal browser tab while the app keeps its place. Existing website casting and private-network permissions still apply. The **Browser** tile restores an ordinary browser tab. iOS persists installations and the last app URL; after a cold launch it restores app tabs lazily, loading only when opened.

On Android and iOS, a valid same-origin `/.well-known/playbridge-app.json` declaration disables automatic media detection for the entire origin, including ordinary browser tabs and embedded frames. Both platforms also disable it immediately for installed bridged-app sessions. Android's **Media detect → Advanced → Detect on bridged sites** override can enable selected detector features there; it is off by default. App pages with detection disabled do not start the detector’s DOM observers, player probes, visibility overrides, header capture, or response-body scanning. The lightweight `window.playbridge` casting API and its navigation/session checks stay available. Undeclared websites retain their existing detection behavior. Declaration results are cached for five minutes, and unavailable or invalid manifests do not opt an ordinary website out. Android stops detection once a new origin is recognized; iOS checks before allowing the document to load. Turning detection off also stops observers and pending probes in already-open browser pages and detaches active response scanners.

The frosted edge menu includes **Remote**. Opening it from a bridged app keeps that app's session and position; the Remote return arrow (and Android Back) reopens the same app. If the app was removed, its tab was closed, or the tab left its app origin, return goes to the dashboard instead. On iOS the edge menu also offers **Back**, **Dashboard**, **Connect TV**, and **Reload**. In iPhone landscape mode the handle stays on the side opposite the front camera, with its chevron pointing inward; portrait keeps it on the right. Closing Dashboard returns to the app which opened it. On iOS the handle hides while a main-page Movi player is fullscreen, including its canvas-based CSS fallback. Inline playback keeps it visible; pausing or buffering in fullscreen keeps it hidden. Exiting fullscreen, removing the player, or leaving the document restores it. This uses a separate lightweight fullscreen observer and does not enable media or image detection.

Linked website casts do not display a mini playback bar over browser or bridged-app pages. Remote shows **Controlled by [website]** with an **Unlink** action while a website controls the session. Unlink releases website control while the current TV playback continues; Remote's playback and queue controls remain available.

## Unified playback destinations

Android and iOS advertise `playbridge.capabilities.playback === 1`. The selected native destination is authoritative, including an explicit **This device** selection. Websites can display and change it without starting media:

```ts
const { destination } = await playbridge.getPlaybackDestination()
// { id, name, kind: 'local' | 'native' | 'external', connected }
await playbridge.choosePlaybackDestination() // opens the existing native destination picker
await playbridge.choosePlaybackDestination({ destinationId: 'this-device' }) // explicit local recovery
const session = await playbridge.play({
  destinationId: destination.id,
  items: [{ id: 'episode-1', url: 'https://media.example/one.mp4', startPositionMs: 120000 }],
  startIndex: 0
})
```

`play()` uses the linked-session event and `provideItems()` contract. Phone playback opens the existing native fullscreen player; a selected receiver uses its normal native transport. Resume positions, explicit media headers, metadata and supported subtitles travel with the items. Website and private-server permissions apply to playback just as they do to casting. The requested destination is checked again after asynchronous preparation; a changed or disconnected target rejects the request, allowing the website to offer reconnect or explicit local playback.

Local playback and native PlayBridge receivers support the website's lazy episode queue. External receivers retain their existing single-item capabilities; they do not request next episodes. Unsupported external queues or subtitle delivery fail explicitly. Unlinking releases website control and progress reporting while playback continues. Existing `cast()` and `linkCast()` remain available for compatibility. A website without the bridge uses its own web player.

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
