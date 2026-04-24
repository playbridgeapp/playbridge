# Protocol — AI Context
_Last verified: 2026-04-24_

## Ownership
The `protocol/` module is a legacy lightweight, pure-Kotlin shared library containing data models (`Message`, `Command`). It is currently being **deprecated** as its functionality moves to the `shared/` KMP module to support Apple TV.

## Key Files (Legacy)
- `src/main/java/com/playbridge/protocol/Message.kt` — *Source of truth moving to `shared/src/commonMain/kotlin/com/playbridge/shared/protocol/Message.kt`*
- `src/main/java/com/playbridge/protocol/Config.kt` — shared configuration constants
- `src/main/java/com/playbridge/protocol/NsdConstants.kt` — network service discovery constants

## Inter-module Contracts
- Calls into: none.
- Called by: `phone/`, `tv/` (legacy dependencies).
- Communication mechanism: Gradle project dependency. Data is serialized using `kotlinx.serialization.json.Json`.

## Gotchas
WARNING: The web extension (`extension/src/background.js`) does not directly import this Kotlin module, so any changes to JSON structure must be manually synchronized to the JS code.
**DEPRECATED:** New features should be added to the `shared` module instead of here.

## Current State
_As of 2026-04-24:_
- Working: All legacy protocol classes serialize properly.
- Broken/degraded: none.
- In progress: Migration to `shared` (KMP).
- Blockers: none.
