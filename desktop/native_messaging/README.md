# PlayBridge native-messaging host

Lets the browser extension cast through the running desktop app (decision D2).
Flow: **extension ↔ (stdio) host stub ↔ (loopback + token) desktop app ↔ (pinned wss) TV.**
The extension never opens a network port and never handles the TV's cert; tokens
ride the pinned link, not the cleartext LAN.

> **Most users don't need this file.** The desktop app auto-registers the host on
> launch and via **Send to TV → "Set up browser casting"**
> (`lib/native_host_installer.dart`) for Firefox + Chrome/Chromium/Brave/Edge on
> macOS and Linux. The extension ids are fixed: Firefox `video-detector@playbridge`,
> Chrome `lhkbcaaoomlmoleggodlbafalokdgokn` (from the `key` baked into the MV3
> manifest). The steps below are the manual fallback / Windows path.

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

1. The desktop app, on launch, writes `bridge.json` (loopback `port` + random
   `token`, `chmod 600`) to:
   - macOS/Linux: `~/.playbridge/bridge.json`
   - Windows: `%APPDATA%\PlayBridge\bridge.json`
2. The browser launches the host stub on `connectNative("com.playbridge.host")`.
3. The stub reads `bridge.json`, connects to `127.0.0.1:<port>`, sends
   `{"token":"…"}`, then relays JSON both ways.

## 5. Wire protocol (extension ⇄ app, via the stub)

Newline-JSON on the socket side; native length-prefixed frames on the stdio side
(the stub translates). Messages:

- extension → app: `{"cmd":"list_devices"}`,
  `{"cmd":"cast","url":"…","headers":{…},"title":"…"}`,
  `{"cmd":"control","action":"toggle|stop|seek_back|seek_forward"}`
- app → extension: `{"type":"hello","ok":true}`,
  `{"type":"state","connected":bool,"state":"…","activeTv":"…","devices":[…]}`,
  `{"type":"result","ok":bool,"error":"…"}`

If the app isn't running, the stub replies `{"type":"error","error":"PlayBridge is not running"}`.
