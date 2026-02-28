# Standalone Browser Extension

A standalone extension architecture exists in the `extension/` directory to bring PlayBridge casting capabilities to desktop browsers.
This is a natively built Web Extension specifically targeted for **Firefox (Desktop)** (Manifest V2, direct WebSocket connection to TV, injected Shadow DOM UI).

*(Note: The Android Phone app uses its own dedicated, lightweight legacy extension found in `phone/app/src/main/assets/extensions/video_detector` for internal GeckoView communication).*

## Key Components

| Component | File | Purpose |
|-----------|------|---------|
| Background Script | `extension/src/background.js` | Video detection logic, WebSocket client for direct TV connection, sends TV commands |
| Content Script | `extension/src/content.js` | In-page video UI (Shadow DOM floating button) |
| HLS Parser | `extension/src/hls-parser.js` | Parses HLS manifests for quality selection |
| Extension UI | `extension/src/ui/popup.*` | Video list view, Subtitles view, URLs sender, and TV connection settings |
