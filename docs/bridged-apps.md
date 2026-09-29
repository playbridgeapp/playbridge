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
