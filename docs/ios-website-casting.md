# iOS website casting

The iOS browser supports `window.playbridge.cast()`, `linkCast()`, and the
unified `play()` page API. `play()` uses the selected native destination: this
phone, a PlayBridge receiver, or a supported external destination. Legacy
`cast()`/`linkCast()` use PlayBridge receiver selection. Normal user-initiated
casting from the detected-media sheet retains its existing destination types.

## Permission and device flow

- A main-frame request from the visible tab asks for website permission once.
  Approval is stored by exact HTTP(S) origin, including a non-default port.
- Media, subtitles, and artwork that reference private-network servers require
  a separate approval for those exact server origins. Additional servers in
  later linked items prompt separately. The sender forwards `allowedPrivateOrigins`
  with each item for receiver-side enforcement of subsequent network requests.
- Missing/unsupported destinations open a picker with saved and discovered
  PlayBridge receivers, manual address entry, and pairing code/approval UI.
- Browser settings → Website casting permissions lists allowed websites.
  Swipe to revoke a website, reset all permissions, or reset just local-server
  access. The same screen sets the queue-ahead count (default 3; range 1–10).
- Revocation, explicit Unlink, page reload/close, receiver changes, a new manual
  cast, and Stop end website queue ownership. Unlink/revocation does not send
  Stop: media already delivered to the receiver can continue playing.
- Same-document SPA navigation retains a linked session. Switching to Remote
  or another tab does not transfer ownership to that tab.

Receiver casts keep the browser visible without a mini playback bar over the
page. Remote shows **Controlled by [website]** with **Unlink**. Phone-local
`play()` opens the native fullscreen player and returns to the page on dismissal.

## Page API

The main frame exposes `playbridge_injected`, `playbridge_injected_version = 4`,
and these capability flags:

```js
window.playbridge.capabilities
// { linkedCast: 1, playback: 1, localPlaybackOrientation: 1,
//   explicitHeaders: 1, privateNetworkOriginPermission: 1 }
```

`getPlaybackDestination()` reads the native destination. Before the origin has
website-casting consent it returns only `{ id: null, name: null, kind, connected }`.
After consent, `name` is the receiver name and `id` is `this-device` or a per-origin
HMAC-SHA256(install secret, origin + NUL + endpoint key). The install secret is
generated once and kept in the app's local defaults; it is not cleared when casting
permission is reset. The raw endpoint key is not disclosed, so two websites cannot
correlate the same receiver. `play()` must use the id from this call. Call
`choosePlaybackDestination()` from a user interaction to show the native picker,
or pass `{ destinationId: "this-device" }` for explicit phone playback. The user
gesture is checked natively, not only in the page. While a TV or AirPlay receiver
is connected, `this-device` opens the native picker so the user confirms leaving
it; a website never disconnects a receiver by itself.
`play({ destinationId, items, initialOrientation })` returns a linked session and
rechecks the destination after asynchronous preparation. Feature-detect
`capabilities.playback` and `capabilities.localPlaybackOrientation`; see
[Unified playback destination](bridged-apps.md#unified-playback-destinations) for
examples, local queues, external limitations, and orientation behavior.

`cast()` is the legacy, fire-and-forget API. It accepts one media object, an array
of objects, or `{ items, startIndex, metadata, skipPreplay, privateNetworkOrigins }`.
Each item supports `url`, `title`, `contentType`, `startPositionMs` (milliseconds), explicit `headers`, `subtitles`,
`subtitleResources`, and `metadata`. iOS also preserves the receiver's
`mediaKind` and `displayDurationMs` fields for audio/image items.

```js
window.playbridge.cast({
  url: 'https://media.example/video.m3u8',
  title: 'Episode 1',
  headers: { Origin: 'https://site.example' },
  subtitleResources: [{
    url: 'https://media.example/en.vtt',
    headers: { Origin: 'https://site.example' },
    label: 'English', language: 'en'
  }]
});
```

`linkCast()` returns a promise for a session. Each linked item additionally needs
a unique `id`. The session implements Android's methods and events:

```js
const session = await window.playbridge.linkCast({
  items: [{ id: 'episode-1', url: 'https://media.example/1.m3u8' }]
});
session.addEventListener('needitems', async ({ detail }) => {
  // detail: requestId, afterIndex, afterItemId, count
  const result = await getNextItems(detail.afterItemId, detail.count);
  await session.provideItems(detail.requestId, {
    items: result.items, endOfList: result.endOfList
  });
});
session.addEventListener('statechange', ({ detail }) => {
  // state, title, positionMs, durationMs, currentIndex, totalCount, items
});
session.addEventListener('ended', ({ detail }) => {
  // detail.reason
});
// Also: session.replace(items, startIndex, metadata),
// session.append(items, { privateNetworkOrigins }), session.jump(index),
// session.unlink(). Each returns a promise.
```

The receiver still fetches original media/subtitle URLs with their headers.
Linked supply retries use the same demand ID, and an already-accepted demand is
acknowledged without adding items again. Stale demand and duplicate item IDs are
rejected. Only one website owns the linked playlist at a time.

## Bounds and lifecycle

The native bridge validates 64 KiB requests, at most 50 items per request, 200
items per linked session, 16 subtitle URLs/resources per item, and 16 private
origins per linked session. Replayed request IDs, subframes, cross-tab commands,
and commands from a replaced document are rejected. Native frame, origin and
document identity are the authority.

Within the page, the API is a shim that talks to native only through a private
`MessageChannel`. The native handler and the per-document token live in an
isolated WebKit content world that page scripts cannot reach; that world also
attests user activation. The port is handed over at document start, before page
scripts run, so another script on the page cannot read requests or replies, or
forge responses and session events. A script that wraps `window.playbridge`
itself still sees the calls it wraps.

The bridge uses readiness acknowledgement before initial events, a 20-second
heartbeat, and bounded timeouts. Sessions expire after 10 minutes without page
activity or 2 hours total. Android has the same duration bounds but different
transport/heartbeat behavior; these are not a cross-platform liveness guarantee.
WebKit can suspend page JavaScript while iOS is backgrounded; on-demand resolution
needs a live page.
Already-delivered items remain on the receiver.

## Website progress callbacks

Website requests must not contain `progressWebhook` at the envelope, payload,
or item level, even with a null or false value. Linked requests fail with
`invalid_request`; legacy fire-and-forget casts are discarded. Use linked
session events for page progress. See the [page-API policy](../protocol/page-api/README.md);
this restriction does not remove trusted-sender WSS webhook support.

## Verification

```sh
bash mobile/apple/tests/run-page-cast-model-checks.sh
bash mobile/apple/tests/run-page-cast-coordinator-checks.sh
node --test mobile/apple/tests/PageCastScriptTests.js
bash mobile/apple/tests/run-browser-startup-checks.sh
```

The browser fixture runs the real WKWebView bridge with a mock receiver,
including consent, remembered approval, playlist supply, revocation, and iframe
isolation. It does not verify TV playback or physical-device pairing. Test a
real receiver with protected media/subtitles, Stop, reconnect, and page navigation
before treating receiver playback as verified.
