# Standalone Browser Extension

A standalone extension architecture exists in the `extension/` directory to bring PlayBridge casting capabilities to desktop browsers.
This is a natively built Web Extension specifically targeted for **Firefox (Desktop)** (Manifest V2, direct WebSocket connection to TV, injected Shadow DOM UI).

*(Note: The Android Phone app uses its own dedicated, lightweight legacy extension found in `phone/app/src/main/assets/extensions/video_detector` for internal GeckoView communication).*

## Package Structure
```
extension/src/
├── background.js        (Video detection logic, WebSocket client for direct TV connection, TV commands)
├── content.js           (In-page video UI, Shadow DOM floating button)
├── hls-parser.js        (Parses HLS manifests for quality selection)
├── icon.png             (Extension icon)
├── manifest.json        (Firefox Manifest V2 configuration)
└── ui/                  (Extension popup user interface)
    ├── fonts/           (Outfit fonts and CSS)
    │   └── outfit.css   (Outfit font face CSS definitions)
    ├── popup.css        (Popup styling)
    ├── popup.html       (Popup layout)
    └── popup.js         (Popup logic: Video list view, Subtitles view, URLs sender, and TV connection settings)
```

## Key Components

| Component | File | Purpose |
|-----------|------|---------|
| Background Script | `extension/src/background.js` | Video detection logic, WebSocket client for direct TV connection, sends TV commands |
| Content Script | `extension/src/content.js` | In-page video UI (Shadow DOM floating button) |
| HLS Parser | `extension/src/hls-parser.js` | Parses HLS manifests for quality selection |
| Extension UI | `extension/src/ui/popup.*` | Video list view, Subtitles view, URLs sender, and TV connection settings |
| Context Menu | `extension/src/background.js` | Right-click "PlayBridge" menu with "Play on TV" (links, video/audio elements) and "Open on TV" (links or current tab) |

## Build & Release

The extension is continuously integrated and released alongside the Android apps via GitHub Actions.
When a new release is triggered (via merging into `main` and updating the version), the `.github/workflows/extension_build.yml` workflow automatically packages the contents of the `extension/src/` directory into a `.xpi` file (Firefox extension package) and attaches it to the newly created GitHub Release.
