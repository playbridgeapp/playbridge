# Receiver error follow-up audit

Source audit against `origin/main` at `012d14d5` for `RECEIVER_ERRORS_PLAN.md`.
These checks do not replace device playback tests.

| Surface | Verified behavior | Follow-up |
| --- | --- | --- |
| Browser receiver | The media element's error state is sent as `status.state = "error"`; a separate error event sets `BrowserPlaybackState::Error` in the Rust host. | [#441](https://github.com/playbridgeapp/playbridge/issues/441): add stall deadlines and fixed safe error reasons; raw browser error text/JSON currently crosses the browser-host boundary. |
| Firefox extension sender | Initial cast command failures are shown by the popup's `reportPlaybackResult`. | [#442](https://github.com/playbridgeapp/playbridge/issues/442): carry and persist asynchronous receiver playback failures through Desktop's native bridge. Its state frame currently omits `lastPlaybackError`. |
| Android TV command rejection | Parse failures, invalid request IDs and a full command queue are dropped; the playlist launch route has no correlated launch-failure receipt. | [#443](https://github.com/playbridgeapp/playbridge/issues/443): return and display correlated play/playlist rejection receipts. |
| Apple TV command rejection | Generic invalid payloads get `command_result ok=false`, but invalid play/playlist decoding inside the command switch only logs. | [#443](https://github.com/playbridgeapp/playbridge/issues/443). |
| Desktop command rejection | Exceptions while handling commands return `command_result ok=false, error=invalid_command`. Full preplay rejection coverage was not established by this source audit. | [#443](https://github.com/playbridgeapp/playbridge/issues/443). |
| Phone/CLI sender receipts | Both phone coordinators consume command results primarily for queue operations. CLI has command confirmation and retains playback failure snapshots. | [#443](https://github.com/playbridgeapp/playbridge/issues/443): verify visible general play/playlist rejection handling end to end. |

Relevant sources:

- `browser-receiver-rust/web/src/receiver.js`: `state`, `sendStatus`, `reportPlaybackError`, `bindMediaListeners`.
- `browser-receiver-rust/src/lib.rs`: browser Status and Error frame handling.
- `desktop/lib/extension_bridge.dart`: `_stateFrame`.
- `extension/src/native-bridge.ts`: state/result/error frame handling.
- `extension/src/ui/popup.ts`: `reportPlaybackResult`.
- Android TV `server/WebSocketServer.kt`: `onMessage`; `server/ServerService.kt`: playlist handling.
- Apple TV `Network/WebSocketServer.swift`: `handleCommand`.
- `desktop/lib/receiver_server.dart`: runtime command dispatch and `_handleCommand`.
- Android/iOS `ConnectionCoordinator`: command-result handling; `cli/src/send.rs`: `managed_command_result` and `apply_playback_error`.

The four receiver-errors changes keep the existing wire schema. No device was
installed, launched or interacted with during these automated/source checks.
