# PlayBridge Video Detector — Privacy Policy

_Last updated: 2026-07-12_

PlayBridge Video Detector ("the extension") helps you cast video and audio you
encounter while browsing to a TV or computer running the PlayBridge app. This
policy explains exactly what the extension accesses, why, and where it goes.

**Summary: the extension does not collect, transmit, or sell your data to us or
to any third party. Everything it reads stays on your own computer and is only
handed to the PlayBridge app you installed.**

## What the extension accesses

To detect a playable stream and let your player reproduce it, the extension
reads, on pages you visit:

- **Network request and response metadata** — URLs and HTTP headers of media
  requests (for example `Content-Type`, and request headers such as
  `User-Agent`, `Referer`, and where present `Cookie` or `Authorization`). These
  headers are needed so the receiving player can fetch the same stream the page
  was playing; many servers reject a request that lacks them.
- **The detected media URL(s)** for the current tab.
- **The active tab's URL and hostname**, to associate detections with the
  correct tab and apply your per-site cast-overlay preference.
- **Video element metadata needed for the optional cast overlay**, such as the
  video source, size, visibility, and playback state. This is used locally to
  place the button over the primary video and match it to a detected stream.

It does **not** read form inputs, keystrokes, your browsing history, or files on
your computer.

## Where the data goes

Detected media URLs and the headers above are sent **only** to the PlayBridge
application running locally on your own machine — either through the browser's
native-messaging channel to the PlayBridge desktop app, or to that app over a
loopback (127.0.0.1) connection on your own computer. From there the desktop app
forwards the stream to your chosen TV over your local network.

- Nothing is sent to the extension's developers.
- Nothing is sent to any remote/third-party server operated by us.
- There is no analytics, telemetry, advertising, tracking, or fingerprinting.
- Your data is never sold or shared.

The extension is useless without the separately-installed PlayBridge app; it has
no backend of its own.

## Storage and retention

Detected media for a tab is held in the browser's in-memory session storage so
the toolbar popup can show it. It is **cleared when you close the browser**, when
you navigate the tab, or when you press "clear" in the popup.

Your global cast-overlay switch and any hostname-specific on/off or position
preferences are stored in the browser's local extension storage. They remain
until you reset them or remove the extension. These preferences are not synced
to an account or sent anywhere.

## Permissions and why they're requested

- **Access to all sites (`<all_urls>` / host permissions)** — media can appear on
  any site, so detection must be able to observe requests anywhere. Observation
  is passive; the extension does not modify page content.
- **`webRequest` (and `webRequestBlocking` on Firefox)** — to see media requests
  and read the headers a player needs. On Firefox this also allows sniffing the
  first bytes of a response to recognise an HLS (`#EXTM3U`) playlist.
- **`tabs` / `activeTab` / `webNavigation`** — to attribute detections to the
  correct tab and reset them on navigation.
- **`nativeMessaging`** — to hand the detected stream to the local PlayBridge app.
- **`storage`** — to keep per-tab detections across the service worker's idle
  suspension and save your local cast-overlay preferences.
- **`notifications`, `contextMenus` / `menus`** — to show a "Play on TV" entry and
  status notifications.

## Your choices

- Firefox 140+ displays the declared browsing activity and website content data
  permissions during installation. Chrome displays a full-page disclosure and
  requires an affirmative choice before media detection begins.
- In Chrome, open the popup's Settings tab to review or revoke media-data access.
  Revoking access stops media observation, removes on-video controls, and clears
  detected session media. The extension remains inactive until access is allowed
  again.
- Open the toolbar popup to view or clear detected media at any time.
- Use the popup's Settings tab to disable the cast overlay globally, disable it
  for the current hostname, choose its per-site position, or reset that site.
- Remove the extension to stop all access immediately; session data is discarded.

## Changes

If this policy changes, the "Last updated" date above will change and the new
version will ship with the corresponding extension update.

## Contact

Questions about this policy can be raised via the project's issue tracker at
<https://github.com/playbridgeapp/playbridge>.
