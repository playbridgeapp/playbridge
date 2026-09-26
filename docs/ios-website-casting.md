# iOS website casting

The iOS browser supports Android's `window.playbridge.cast()` and
`window.playbridge.linkCast()` page APIs. Website requests use a PlayBridge
receiver (TV or Desktop). If the selected destination uses another protocol,
the request opens a PlayBridge device picker. Normal user-initiated casting
from the detected-media sheet still supports its existing destination types.

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

Linked casts keep the browser visible and show a **Controlled by [website]**
mini bar with Remote access and **Unlink**. One-off website casts also leave the
browser visible.

## Page API

The main frame exposes `playbridge_injected`, `playbridge_injected_version = 4`,
and these capability flags:

```js
window.playbridge.capabilities
// { linkedCast: 1, explicitHeaders: 1, privateNetworkOriginPermission: 1 }
```

`cast()` is the legacy, fire-and-forget API. It accepts one media object, an array
of objects, or `{ items, startIndex, metadata, skipPreplay, privateNetworkOrigins }`.
Each item supports `url`, `title`, `contentType`, explicit `headers`, `subtitles`,
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
and commands from a replaced document are rejected. Page response delivery is
bound to both native document identity and a per-document JavaScript token.

The bridge uses readiness acknowledgement before initial events, a 20-second
heartbeat, and bounded timeouts. Sessions expire after 10 minutes without page
activity or 2 hours total, matching Android's limits. WebKit can suspend page
JavaScript while iOS is backgrounded; on-demand resolution needs a live page.
Already-delivered items remain on the receiver.

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
