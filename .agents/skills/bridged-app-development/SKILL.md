---
name: bridged-app-development
description: Build or change a PlayBridge Bridged App, a website that PlayBridge installs as a dashboard tile and opens in its own browser. Use when adding the playbridge-app.json declaration, playing or casting through window.playbridge (destinations, play(), linked queues), testing a site inside PlayBridge, or reviewing a site's bridge integration. The host-side API spec is docs/bridged-apps.md.
---

# Bridged App development

A Bridged App is an ordinary website. PlayBridge opens it in its own browser
(GeckoView on Android, WKWebView on iOS) with the toolbar hidden, and the page
plays media through `window.playbridge`. There is no SDK or package to install.

The specification is [`docs/bridged-apps.md`](../../../docs/bridged-apps.md).
This skill is the workflow; check exact limits and behavior there instead of
repeating them in code comments. Published at
`https://playbridge.app/skills/bridged-apps`.

## 1. Declare the site

Serve `/.well-known/playbridge-app.json` from the site's origin:

```json
{
  "protocol": "playbridge-app-v1",
  "name": "Example",
  "start_url": "/",
  "icon_url": "/icon.png",
  "theme_color": "#16433F"
}
```

- Return it with HTTP 200 and no redirect. Keep `start_url` and `icon_url` on
  the same origin. Public sites need HTTPS; `localhost`, loopback and private
  IPv4 addresses may use HTTP for development.
- `theme_color` takes `#RGB` or `#RRGGBB` only. The icon is optional and is
  shown at up to 128 × 128, so a square PNG is best.
- Static hosts serve `.well-known/` from the build's public folder (for Vite,
  `public/.well-known/playbridge-app.json`). Check the deployed URL directly:
  some hosts hide dot-folders.
- Declaring opts the **whole origin** out of PlayBridge's automatic media
  detection, in ordinary browser tabs too. The site must hand over its media
  through the API; PlayBridge no longer finds `<video>` elements for it.

## 2. Design for the host

- **Cold launches start at the home URL**, not the last deep link. Every route
  must load from a fresh visit; hash routes suit static hosting. Never put
  credentials or signed stream URLs in a route.
- Users manage the app (name, home URL, tile color, removal) in PlayBridge.
  There is no API for it, so don't build one into the site.
- Android Back walks the site's history, then leaves the app. Keep history
  meaningful: push entries for screens, replace them for filters.
- External links open in a normal browser tab while the app keeps its place.
- Without a bridge (a desktop browser, or an older host), keep a working web
  player or a clear message. Don't make the site depend on PlayBridge.

## 3. Use the bridge

Read the bridge when you need it; don't cache it at startup, because the host
may inject it after the first script runs. Feature-detect each capability:

```ts
const api = window.playbridge;
const modern = !!(api?.capabilities?.playback && api.play
  && api.getPlaybackDestination && api.choosePlaybackDestination);
```

Rules that hosts enforce:

- **Never assign, wrap or replace `window.playbridge`.** Tests inject a mock
  through their own hook instead (see section 4).
- **Consent first.** Until the origin has casting consent,
  `getPlaybackDestination()` returns `{ id: null, name: null, kind, connected }`.
  Call `choosePlaybackDestination()` from a click or tap: it asks for consent,
  then opens the native picker. It rejects with `not_allowed` if the user says
  no, and `user_gesture_required` without a real user gesture.
- **Play to the chosen destination.** Pass the id from those calls to
  `play({ destinationId, items, startIndex })`. Ids are per origin, so never
  store or share them across sites. `this-device` is the literal id for the
  phone itself and also needs a user gesture.
- **Re-check after async work.** If the destination changes while you resolve a
  stream, `play()` rejects. Offer reconnecting or This device; never fall back
  to another device silently.
- **Items** carry `url`, and optionally `id`, `title`, `contentType`,
  `startPositionMs`, `headers`, `subtitleResources` and `metadata` (title,
  year, poster and backdrop URLs, season, episode). Send only media URLs the
  user is allowed to play.
- Never send `progressWebhook`, at any level. Requests containing it fail.
- `capabilities.localPlaybackOrientation === 1` allows
  `initialOrientation: 'auto' | 'portrait' | 'landscape'`, for This device only.

### Linked sessions and queues

`play()` (and the older `linkCast()`) resolves to a session:

```ts
session.addEventListener('statechange', (e) => { /* e.detail: state, positionMs, durationMs, currentIndex */ });
session.addEventListener('needitems', (e) => {
  const { requestId, count } = e.detail;
  // Resolve up to `count` next items, then:
  void session.provideItems(requestId, { items, endOfList });
});
session.addEventListener('ended', (e) => { /* release listeners, save final progress */ });
// Also: session.jump(index), session.unlink()
```

- Resolve only the first item before calling `play()`; supply the rest on
  `needitems`. The page must stay loaded to supply more.
- A failed or empty lookup is **not** the end of the list. Keep the request
  retryable and only set `endOfList` when nothing is left.
- Fence late results with a generation counter: a lookup that finishes after
  the user started something else must not reach the old session.
- `unlink()` hands control back to PlayBridge; playback may continue on the TV.
- External receivers (Google Cast, DLNA and similar) take single items and
  fewer subtitle formats. Fail clearly when a queue isn't supported.

### Older hosts

Hosts without `capabilities.playback` may still offer `cast(payload)` (one-off,
no progress) and `linkCast(payload)` (a linked session with
`capabilities.linkedCast`). Keep these as separate fallbacks; don't mix them
into the destination flow.

## 4. Test

- **Mock bridge.** Have the site read the bridge through one function that
  prefers a test hook, such as
  `window.__bridgedTest?.playbridge ?? window.playbridge`, and inject a fake
  API there from Playwright `addInitScript`. Cover: no bridge, consent denied,
  a missing user gesture, destination changed during preparation, `needitems`
  with a failed lookup, and `ended`.
- **On an Android phone.** Run the dev server, forward it with
  `adb reverse tcp:<port> tcp:<port>`, then open `http://localhost:<port>/` in
  PlayBridge (for example `adb shell am start -a android.intent.action.VIEW
  -d http://localhost:<port>/ -p com.playbridge.sender`). Choose **Add Bridged
  App** from the browser menu to test as an installed app.
- **On an iPhone.** Serve on the computer's private LAN address over HTTP and
  open that address in PlayBridge.
- A local address is a different origin from production: storage, logins and
  casting consent start empty.

## Checklist

- [ ] `/.well-known/playbridge-app.json` returns 200 on the deployed origin,
      with no redirect, and **Add Bridged App** appears.
- [ ] Every route loads on a cold visit; no secrets in URLs.
- [ ] The site works without `window.playbridge`.
- [ ] Destination choice and This device run from user gestures; denial and
      stale destinations show a clear message.
- [ ] Queues supply on `needitems`, retry failures and end only when empty.
- [ ] No `progressWebhook`; `window.playbridge` is never assigned.
- [ ] Tested on a real phone, both as a browser tab and as an installed app.

For PlayBridge's own apps (Streams, Jellyfin), also follow the
[`bridged-apps` repo guide](https://github.com/playbridgeapp/bridged-apps/blob/main/AGENTS.md)
and its app skills. Change host behavior in this repository, with
`docs/bridged-apps.md` and the Android, Apple and extension skills.
