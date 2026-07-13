# Browser extension store listing copy

Use this copy in the Chrome Web Store and Firefox Add-ons dashboards. Replace
all bracketed placeholders before submission and keep the final dashboard text
consistent with `PRIVACY.md` and the extension UI.

## Shared listing details

### Name

PlayBridge Video Detector

### Short description

Detect and cast browser videos through the PlayBridge desktop app.

### Long description

PlayBridge Video Detector finds playable media in the current browser tab and
lets you cast the selected stream to a TV connected through the PlayBridge
desktop app.

Features:

- detects direct video files, HLS, DASH, and subtitles;
- shows detected streams in the toolbar popup;
- adds an optional cast button over the primary video;
- supports global and per-site overlay controls and five button positions;
- passes stream headers needed by protected media only to the local PlayBridge
  desktop app when you cast;
- includes no advertising, analytics, tracking, or remote executable code.

The separately installed PlayBridge desktop app is required for casting. Pair
and select a TV in the desktop app, play a video in the browser, open the
extension, and choose the stream to cast.

PlayBridge observes media requests on sites you visit so it can identify a
stream that the receiving player can load. See the privacy policy for the exact
data handled and retention behavior.

### Public URLs

- Homepage: `https://github.com/playbridgeapp/playbridge`
- Support: `https://github.com/playbridgeapp/playbridge/issues`
- Privacy policy: `https://github.com/playbridgeapp/playbridge/blob/main/extension/PRIVACY.md`

Confirm these URLs are publicly reachable without signing in before submission.

## Chrome Web Store privacy fields

### Single purpose

Detect media playing in the current browser tab and cast a user-selected stream
through the local PlayBridge desktop app to the user's chosen TV.

### Permission justifications

| Permission | Dashboard justification |
| --- | --- |
| `webRequest` | Observes media request URLs and headers so PlayBridge can identify HLS, DASH, direct files, and the request context needed to replay a selected stream. It does not block or modify Chrome traffic. |
| `<all_urls>` host access | Media can appear on any site selected by the user. Host access is needed to observe media requests and run the optional on-video cast control in the page's isolated extension world. |
| `webNavigation` | Clears stale detections after full navigation and re-evaluates the active player after same-document SPA navigation. |
| `storage` | Keeps detected media in browser session storage across MV3 service-worker suspension and saves local consent and overlay preferences. |
| `tabs` | Associates detections with the correct tab, reads the active tab title for the TV display, updates the badge, and messages extension content scripts. |
| `contextMenus` | Adds user-initiated PlayBridge Play/Open commands to page, link, video, and audio context menus. |
| `notifications` | Explains when the PlayBridge desktop app or a connected TV is unavailable after a user invokes a context-menu cast. |
| `nativeMessaging` | Sends a user-selected media URL and required replay headers to the PlayBridge desktop app running on the same computer. |

### Remote code

Select **No, I am not using remote code**. All JavaScript and fonts execute from
the submitted package. Network responses are treated only as media data and are
never evaluated as code.

### Data types to disclose

Select the dashboard categories corresponding to:

- web browsing activity (current page, media domains, and media URLs);
- website content/resources (video/audio URLs, metadata, request and response
  information);
- authentication information (Cookie or Authorization headers can be present on
  protected media requests).

Do not select advertising, analytics, personalization, credit, or unrelated-use
purposes. Complete every Limited Use certification. State that handling occurs
locally and selected stream data is transferred only to the PlayBridge desktop
app and chosen TV to perform the cast requested by the user.

### Prominent disclosure behavior

Chrome MV3 opens `ui/consent.html` on first install or update when the current
disclosure has not been accepted. Until acceptance, background media capture,
DOM media registration, overlay display, current-tab URL handling, and casting
are disabled. Revocation clears detected session media.

## Firefox Add-ons declarations

The Firefox manifest declares these required built-in data collection types:

- `browsingActivity` for media/current-page URLs and domains;
- `websiteContent` for video/audio resources, cookies, and request/response
  headers.

The minimum Firefox version is 140 so the built-in consent prompt is available.
Use the same explanation in the AMO listing and privacy fields. Mark the AMO
listing as Firefox desktop only: PlayBridge depends on a desktop native-messaging
host, and Firefox Android does not provide the required desktop integration.

## Reviewer notes

PlayBridge Video Detector is a companion to the PlayBridge desktop app. It
observes browser media requests, stores detections in browser session storage,
and sends data outside the extension only when the user chooses a cast/open
action. The selected media URL and any required request headers are passed via
the native-messaging host `com.playbridge.host` to the desktop app on the same
computer. The desktop app sends the stream to the user's selected TV over the
PlayBridge connection. No data is sent to PlayBridge developers or an analytics
backend.

The Firefox package is MV2 because it uses Firefox-only response filtering to
recognize headerless HLS playlists. The Chrome package is MV3 and observes
headers/URLs without response-body filtering. Neither target modifies network
requests.

Production bundles are generated with esbuild. The separate AMO source archive
contains `AMO_SOURCE_BUILD.md`, the lockfile, and all source needed to reproduce
`dist/firefox`.

### Reviewer test setup — complete before submission

1. Install PlayBridge desktop build: `[PUBLIC REVIEWER DOWNLOAD URL]`.
2. Start the app and use `[PAIRING/TEST TV INSTRUCTIONS]`.
3. Install the submitted extension package.
4. On Chrome, accept the full-page media-data disclosure.
5. Open `[PUBLIC TEST PAGE OR DIRECT MEDIA URL]` and start playback.
6. Open the PlayBridge popup; the stream should appear in Videos.
7. Select it and choose **Play Selected on TV**.
8. Confirm the TV receives the selected stream.
9. Stop the desktop app and retry to confirm the extension displays the expected
   unavailable state.

Provide a test path that does not require reviewer credentials. If credentials
are unavoidable, enter them only in the private reviewer-instructions field,
never in this repository.

## Listing assets still required

Capture real release UI rather than mock data:

- Chrome store icon: existing `src/icon.png` (128x128);
- Chrome screenshots: at least one 1280x800 image, ideally showing detected
  videos, connected desktop/TV state, and per-site overlay settings;
- Chrome small promo tile: 440x280;
- optional Chrome marquee tile: 1400x560;
- refreshed AMO screenshots showing the same release behavior.

Do not include authenticated media URLs, request headers, tokens, private IP
addresses, pairing PINs, or reviewer credentials in screenshots.
