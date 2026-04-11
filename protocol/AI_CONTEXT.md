# Protocol — AI Context
_Last verified: 2026-04-11_

## Ownership
The `protocol/` module is a lightweight, pure-Kotlin shared library containing only the data models (`Message`, `Command`) used to communicate between the Sender (Phone) and Receiver (TV). It does NOT own any networking logic, WebSockets, or UI code.

## Key Files
- `src/main/java/com/playbridge/protocol/Message.kt` — primary source of truth for all WebSocket JSON payloads
- `src/main/java/com/playbridge/protocol/Config.kt` — shared configuration constants
- `src/main/java/com/playbridge/protocol/NsdConstants.kt` — network service discovery constants
- `src/main/java/com/playbridge/protocol/BluetoothConstants.kt` — Bluetooth discovery constants

## Inter-module Contracts
- Calls into: none (no dependencies outside standard library/serialization).
- Called by: `phone/` module, `tv/` module.
- Communication mechanism: Included as a standard Gradle project dependency (`project(":protocol")`). Data is serialized using `kotlinx.serialization.json.Json`.

## Gotchas
WARNING: The web extension (`extension/src/background.js`) does not directly import this Kotlin module, so any changes to JSON structure must be manually synchronized to the JS code.

## Current State
_As of 2026-04-11:_
- Working: All protocol classes serialize and deserialize properly.
- Broken/degraded: nothing critical
- In progress: none
- Blockers: none
