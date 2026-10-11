# PlayBridge native-messaging host

Lets the browser extension cast through the running desktop app (decision D2).
Flow: **extension ↔ (stdio) host stub ↔ (loopback + token) desktop app ↔ (pinned wss) TV.**
The extension never opens a network port and never handles the TV's cert; tokens
ride the pinned link, not the cleartext LAN.

> **Most users don't need this file.** The desktop app auto-registers the host on
> launch and via **Send to TV → "Set up browser casting"**
> (`lib/native_host_installer.dart`) for Firefox + Chrome/Chromium/Brave/Edge on
> macOS and Linux. The extension ids are fixed: Firefox
> `video-detector@playbridge`, Chrome unpacked development
> `lhkbcaaoomlmoleggodlbafalokdgokn`, and Chrome Web Store
> `gofdcnocpnieoonficfnfccolcocoaim`. The steps below are the manual fallback /
> Windows path.

## 1. Build the host binary

The host is plain Dart (no Flutter), so compile it with the Dart SDK:

```bash
cd desktop
dart compile exe bin/playbridge_host.dart -o build/playbridge_host
# Windows: -o build/playbridge_host.exe
```

In a packaged build, ship this binary alongside the app and point the manifest
`path` at its installed location.

## 2. Fill in the manifest

Edit the manifest for your browser:

- `path` → the **absolute** path to the compiled `playbridge_host` binary.
- Chrome (`com.playbridge.host.chrome.json`): set `allowed_origins` to
  `chrome-extension://<your extension id>/` (find the id at `chrome://extensions`).
- Firefox (`com.playbridge.host.firefox.json`): set `allowed_extensions` to the
  extension's id (its `browser_specific_settings.gecko.id` — currently
  `video-detector@playbridge`).

The manifest filename must match the host name: `com.playbridge.host.json`.

## 3. Install the manifest

Copy the (renamed) manifest to the per-browser, per-OS location:

| Browser | macOS | Linux |
|---------|-------|-------|
| Chrome  | `~/Library/Application Support/Google/Chrome/NativeMessagingHosts/` | `~/.config/google-chrome/NativeMessagingHosts/` |
| Chromium| `~/Library/Application Support/Chromium/NativeMessagingHosts/` | `~/.config/chromium/NativeMessagingHosts/` |
| Firefox | `~/Library/Application Support/Mozilla/NativeMessagingHosts/` | `~/.mozilla/native-messaging-hosts/` |

Windows: register a key whose default value is the absolute path to the manifest:
- Chrome: `HKCU\Software\Google\Chrome\NativeMessagingHosts\com.playbridge.host`
- Firefox: `HKCU\Software\Mozilla\NativeMessagingHosts\com.playbridge.host`

## 4. How it connects

1. The desktop app, on launch, writes `bridge.json` (loopback `port`, random
   `token` and its `appExecutable` launch-path hint, `chmod 600`) to:
   - macOS/Linux: `~/.playbridge/bridge.json`
   - Windows: `%APPDATA%\PlayBridge\bridge.json`
2. The browser launches the host stub on `connectNative("com.playbridge.host")`.
3. The stub reads `bridge.json`, connects to `127.0.0.1:<port>`, sends
   `{"token":"…"}`, then relays JSON both ways.

## 5. Wire protocol (extension ⇄ app, via the stub)

The app socket uses newline-delimited JSON; browser stdio uses a four-byte
little-endian length followed by UTF-8 JSON. The stub relays objects without
changing fields. Authentication is the first socket line: `{"token":"…"}`.
The app then sends `{"type":"hello","ok":true}` and a `state` frame.
Never log bridge.json, its token, pairing tokens, authenticated URLs or headers.

State fields are additive; older extensions ignore the new fields:

```json
{
  "type": "state",
  "connected": true,
  "target": "tv",
  "state": "connected",
  "activeTv": "Living Room",
  "capabilities": { "selectDevice": 1, "linkedPlayback": 1 },
  "selection": { "kind": "tv", "id": "playBridge:receiver-uuid",
    "name": "Living Room", "status": "connected" },
  "devices": [{ "id": "playBridge:receiver-uuid", "uuid": "receiver-uuid",
    "name": "Living Room", "protocol": "playBridge", "connectable": true,
    "paired": true, "saved": true, "online": true, "active": true }]
}
```

Local selection is `{"kind":"local"}`, with `target:"local"` and
`activeTv:"This computer"`. A TV selection's status is `connected`, `connecting`
or `offline`. The selection remains a TV after unexpected drops or reconnect
failure; `target` names the selected route, while `connected:true` means the app
bridge is available. Devices are the union of saved and discovered receivers,
keyed by `identityKey` (protocol + UUID), ordered active first, saved online,
saved offline, then unsaved discovered, with stable name/identity ordering.
`online` means currently discovered; `active` means selected. `paired` indicates
that no pairing is needed (non-pairing protocols can be used immediately).
IDs stay internal to the extension; never expose them to page scripts.
The app pushes state on selection, connection or device-list changes, skipping
frames identical to the last state sent to that client. Authentication and
explicit `list_devices` always send the current state, even if unchanged.

Commands on each authenticated connection execute in order, preserving FIFO
result correlation. State pushes may interleave with results.

| Command | Behaviour and result |
| --- | --- |
| `{"cmd":"list_devices"}` | Existing command: sends current `state`. |
| `{"cmd":"select_device","id":"playBridge:receiver-uuid"}` | Persist that TV and connect with saved credentials; settle within 10 seconds. Returns one `result` with `ok:true,target:"tv",targetName`, or `ok:false,error:"unknown_device"`, `"pairing_required"`, `"offline"`, `"connect_failed"` or `"not_connectable"`. Unpaired PlayBridge TVs return `pairing_required` without changing selection or starting pairing. |
| `{"cmd":"select_local"}` | Persist local and explicitly disconnect, cancelling automatic retry. Returns `ok:true,target:"local",targetName:"This computer"`. |
| `{"cmd":"refresh_devices"}` | Trigger an immediate discovery rescan and push state; returns `ok:true`. |
| `{"cmd":"show_app","pairId":"playBridge:receiver-uuid"}` | Show/focus the main window. Optional `pairId` starts the existing Send to TV pairing flow for that discovered TV; returns `ok:true`. Successful pairing sets the selection. |
| `{"cmd":"cast","url":"…","headers":{},"title":"…"}` | Use the persisted selection. Local success returns `ok:true,target:"local",targetName:"This computer"`; TV results include `target:"tv",targetName`. An offline selected TV is reconnected for at most 8 seconds, then returns `ok:false,error:"destination_offline",targetName` on failure. Never silently falls back locally. Existing media fields, including `contentType`, `playlistBody` and `audioUrl`, remain supported. |
| `{"cmd":"cast","url":"…","destination":"local"}` | One-off local playback; the persisted selection remains unchanged. |
| `{"cmd":"cast_file","path":"…","title":"…"}` | OS context-menu file casts keep their existing live-connection routing: TV when connected, otherwise local. |
| `{"cmd":"control","action":"toggle"}` | Existing live-connection routing; supports playback and seek actions. |

Every result row above uses the envelope `{"type":"result",...}`. Persisted
selection is local by default, or stores only `kind:"tv",protocol,uuid` alongside
saved TV credentials. User connect, successful pairing and explicit extension
selection update it. User Disconnect or forgetting the selected TV resets it to
local; unexpected drops and retry give-up do not. Launch restores a saved TV
with the existing silent token/pin reconnect path.

## 6. App absence and explicit launch

When bridge.json is absent/stale or connection/authentication fails, the stub
sends `{"type":"error","error":"PlayBridge is not running","code":"app_not_running"}`
and stays alive reading browser stdin. Each non-launch command first retries
connection and authentication for up to two seconds, then forwards the command
if the app is available. This also resumes relay with `hello` and `state` after
the user manually starts or restarts the app. A failed retry sends
`app_not_running`. It never auto-launches on cast or other commands.
`{"cmd":"launch_app"}` while disconnected invokes the same packaged
platform launcher as `playbridge_cast`, without file arguments. Hosts extracted
into app support use the GUI-written `appExecutable` hint; packaged bundle or
sibling-executable layouts remain supported. The stub then polls
bridge.json and authenticates for up to 20 seconds. Success resumes normal
relay with the app's `hello` and `state`. Failure sends
`{"type":"error","code":"launch_failed","error":"Could not launch PlayBridge"}`;
the browser port stays open for retry until stdin closes. App shutdown returns
the stub to the same disconnected state.


## 7. Website linked playback

`capabilities.linkedPlayback:1` advertises the desktop half of the website API.
The authenticated extension owns website consent, gesture attestation, per-origin
private-network grants, document binding and opaque destination IDs. The app
validates media again and owns one linked session across native ports. Endpoint
keys and origins in these messages are private extension/app data, never page
responses. Native host stubs relay these frames verbatim.

Linked requests use `type`, not `cmd`, and each carries a unique
`bridgeRequestId` (1–128 characters). They do not join the legacy command FIFO.
Every request receives exactly one response:

```json
{"type":"linked_result","bridgeRequestId":"request-1","ok":true,"sessionId":"session-uuid"}
{"type":"linked_result","bridgeRequestId":"request-2","ok":false,"error":"destination_offline"}
```

Only open/play results create a `sessionId`. Subsequent operations are bound to
that session and the native connection that opened it. Errors use the shared
page API codes; malformed or stale supplies return `invalid_request`.

| Request type | Fields besides `bridgeRequestId` |
| --- | --- |
| `linked_play` | `origin`, `destination:"local"` or selected `protocol:uuid`, `payload:{items,startIndex,metadata?,skipPreplay?}`, `allowedPrivateOrigins:[]` |
| `linked_open` | Same as play, with destination omitted; uses the current selection |
| `linked_replace` | `sessionId`, `payload:{items,startIndex,metadata?,skipPreplay?}`, `allowedPrivateOrigins:[]` |
| `linked_append` | `sessionId`, `payload:{items}`, `allowedPrivateOrigins:[]` |
| `linked_jump` | `sessionId`, `payload:{index}` |
| `linked_supply` | `sessionId`, `payload:{requestId,items,endOfList}`, `allowedPrivateOrigins:[]` |
| `linked_ping` | `sessionId`; the page is ready for progress and demand events |
| `linked_unlink` | `sessionId`, optional `reason:"unlinked"|"navigation"|"permission_reset"` |

Items carry a unique `id`, HTTP(S) `url`, optional allow-listed `headers`, `title`,
`contentType`, `startPositionMs`, `subtitles`, `subtitleResources` and `metadata`.
Subtitle resources carry `url`, optional independent `headers`, `label` and
`language`. `progressWebhook` is rejected at the envelope, payload and item
levels, even when null or false. Metadata remains ordinary visual metadata;
no webhook or other sender-only fields are inherited into outgoing playlists.
Private-origin grants are exact HTTP(S) origins, never wildcard network access.
Local playback and external receiver streams go through the existing proxy
policy; PlayBridge TVs receive `detectedBy:"linked_page"` and the exact grants.
Website URLs, headers and origins are never written to diagnostic logs.

This computer uses the native PlayerController queue. PlayBridge TVs receive
`playlist`, `queue_add` and `playlist_jump` commands with progress from receiver
status. External receivers (DLNA, Google Cast, Roku and Web Browser) support one
current item and reject queued operations with `unsupported_queue`, jumps with
`unsupported_operation`, and subtitles
with `unsupported_subtitles`. They do not emit item demand.

The app emits events to the opening native connection:

```json
{"type":"linked_event","sessionId":"session-uuid","event":"needitems","detail":{"requestId":"need-uuid","afterIndex":0,"afterItemId":"episode-1","count":3}}
{"type":"linked_event","sessionId":"session-uuid","event":"statechange","detail":{"state":"playing","positionMs":12000,"durationMs":24000,"title":"Episode 1","currentIndex":0,"totalCount":1,"items":[{"index":0,"id":"episode-1","title":"Episode 1"}]}}
{"type":"linked_event","sessionId":"session-uuid","event":"ended","detail":{"reason":"unlinked"}}
```

States are `connecting`, `playing`, `paused`, `buffering`, `idle`, `ended` or
`error`. Progress is throttled to one event per second, with immediate state/index
changes. At the last resolved item, one outstanding demand requests up to three
items; retries reuse the request ID after five seconds. Supplies are idempotent
for the last accepted request. Local playback can wait at the end and resume
when a late supply arrives. `endOfList:true` suppresses further demand.

Unlink releases website control and keeps delivered playback/queue running.
The app banner and tray show `Controlled by <origin>` with an Unlink action.
Changing selection ends control with `receiver_changed`; a new linked play ends
the old session with `superseded`. Other endings are `navigation`,
`permission_reset`, `receiver_stopped`, `session_expired` and
`queue_update_failed`. A native-port disconnect releases control as navigation.
Destination identity/availability is checked again after asynchronous preparation;
changes return `destination_changed`/`destination_offline` without local fallback.

Limits: 50 items/request, 200 delivered items/session, 64 KiB/request, 16
allow-listed headers (16 KiB total), 16 subtitles, 16 private origins, 16 KiB
metadata, eight in-flight linked requests and one active session. Playback
operations time out after 20 seconds; late preparation cannot commit to an ended
session. Sessions expire after more than 30 minutes without page ping/operations.
