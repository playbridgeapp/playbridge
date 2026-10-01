# Bridged Apps (Android v1)

A Bridged App is a website opened inside PlayBridge's existing GeckoView browser with its toolbar hidden. It uses the same `window.playbridge.cast()` and `window.playbridge.linkCast()` APIs as a normal browser tab. Installation adds a tile to the PlayBridge dashboard; it does not create an Android launcher shortcut or grant casting permission. Public sites require HTTPS. Local development servers on literal private IPv4 or loopback addresses, or `localhost`, may use HTTP.

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

Installed apps appear as tiles beside **Cast History** on the second tile page. Swipe left across the dashboard tiles or tap the second dot below them to reach it. The dashboard header and connection status stay in place. Tap an app to reopen its saved app session or start URL. App sessions stay out of the normal tab switcher and its count, search, and close actions. Long press to remove the tile and close its session. In app mode, Android Back navigates within the site; at the first page, it returns to the dashboard. An external web link opens in a normal browser tab while the app keeps its place. Existing website casting and private-network permissions still apply.

On Android and iOS, a valid same-origin `/.well-known/playbridge-app.json` declaration disables automatic media detection for the entire origin, including ordinary browser tabs and embedded frames. Android also disables it immediately for installed bridged-app sessions, regardless of the browser's detection switch. App pages do not start DOM observers, player probes, visibility overrides, header capture, or response-body scanning. The lightweight `window.playbridge` casting API and its navigation/session checks stay available. Undeclared websites retain their existing detection behavior. Declaration results are cached for five minutes, and unavailable or invalid manifests do not opt a website out. Android stops detection once a new origin is recognized; iOS checks before allowing the document to load. Turning detection off also stops observers and pending probes in already-open browser pages and detaches active response scanners.

The frosted edge menu includes **Remote**. Opening it from a bridged app keeps that app's session and position; both the Remote return arrow and Android Back reopen the same app. If the app was removed, its tab was closed, or the tab left its app origin, return goes to the dashboard instead.

Linked website casts do not display a mini playback bar over browser or bridged-app pages. Remote shows **Controlled by [website]** with an **Unlink** action while a website controls the session. Unlink releases website control while the current TV playback continues; Remote's playback and queue controls remain available.
