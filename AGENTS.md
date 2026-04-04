# PlayBridge — AGENTS.md

## Project Overview & Structure
PlayBridge is an Android app suite casting web video from phones to Android TV.
Written in Kotlin with Jetpack Compose (Phone) and Leanback/TV Material (TV).
This repository contains multiple **independent Gradle projects** (not a monorepo build).

| Module | Path | Role |
|---|---|---|
| Phone | `phone/` | Sender app with GeckoView, Debrid, WebSocket client. |
| TV | `tv/` | Receiver app with MPV/VLC, WebSocket server (Ktor), Leanback UI. |
| Protocol | `protocol/` | Shared JVM library with data classes (`Message`, `Command`). |
| Extension | `extension/` | Desktop Firefox extension, pure JS, raw WebSocket JSON. |

## Build & Run Commands
**Always run from the specific project directory (`phone/`, `tv/`, or `protocol/`)!**

```bash
# Build & Bundle
./gradlew app:assembleDebug      # Build Debug APK
./gradlew app:assembleRelease    # Build Release APK
./gradlew app:bundleRelease      # Build Release AAB
./gradlew build                  # Build Protocol module
./gradlew clean app:assembleDebug # Clean and build
```

## Testing & Linting Commands
**Agent note:** It is highly encouraged to run a single test when verifying small changes for faster iteration.

```bash
# Run all unit tests
./gradlew test

# Run a specific unit test class
./gradlew test --tests "com.playbridge.sender.browser.DownloadUtilsTest"

# Run a specific test method (BEST for quick iteration)
./gradlew test --tests "com.playbridge.sender.browser.DownloadUtilsTest.testMethodName"

# Run instrumented tests (requires emulator/device)
./gradlew connectedAndroidTest

# Run a specific Compose UI test class
./gradlew app:connectedAndroidTest --tests "*ComposeTest"

# Run Android Lint
./gradlew lint
```

## Code Style & Guidelines

### Language & Formatting
- **Kotlin**: Follow official [Kotlin coding conventions](https://kotlinlang.org/docs/coding-conventions.html).
- **Format**: No external formatters (ktlint/detekt) are configured. Rely on standard Android Studio / Kotlin formatting rules.
- **Commits**: Use Conventional Commits (`feat:`, `fix:`, `refactor:`, `test:`, etc.).

### Imports
- Use **explicit imports** (strictly no wildcard `*` imports).
- Group imports: Standard library -> AndroidX -> Third-party -> Internal.
- Always remove unused imports before committing.

### Types & Data
- Prefer strict typing and non-nullable types where possible. Use `?` only when logically necessary.
- Use `StateFlow` over `LiveData` for modern state management in Compose.
- **Sealed classes** are heavily used for defining states and protocol messages.

### Naming Conventions
- **Classes / Interfaces**: `PascalCase` (`BrowserActivity`, `Message`).
- **Functions / Properties / Variables**: `camelCase` (`sendCommand()`, `isConnected`).
- **Constants**: `UPPER_SNAKE_CASE` in `companion object` or top-level.
- **Composables**: `PascalCase` (`HomeScreen()`, `VideoPlayer()`).
- **XML Resources**: `snake_case` (`activity_browser.xml`, `ic_play.xml`).

### Architecture
- **MVVM Pattern**: ViewModel handles logic, exposes UI state via `StateFlow`.
- **Repository Pattern**: Used for all data access (Room, Network, Debrid).
- **UI**: Jetpack Compose on Phone (Material 3); Leanback / TV Material on TV.

### Error Handling
- Use Kotlin `Result<T>` or specific sealed classes for modeling success/failure states.
- Surface errors cleanly to the UI through ViewModel state.
- **Security**: Never log sensitive data like Debrid API tokens or signing credentials. Use specific `try-catch` blocks around IO/Network operations.

## Critical Gotchas (Agent Must-Reads)

### 1. Protocol Ripple Effect (CRITICAL)
Any change to `protocol/src/main/java/com/playbridge/protocol/Message.kt` MUST be mirrored in:
- `phone/app/.../connection/ConnectionViewModel.kt`
- `tv/player/app/.../server/ServerService.kt`
- `extension/src/background.js` (Manual JSON parsing/formatting since JS can't import Kotlin).

### 2. GeckoView Version Sync
Phone and TV both depend on GeckoView. The version in `gradle/libs.versions.toml` MUST be identical across both modules to prevent runtime divergence.

### 3. TV Specifics
- Uses `SYSTEM_ALERT_WINDOW` as a workaround for Android 14+ background limits.
- `network_security_config.xml` allows cleartext traffic for development but must be removed for production.
- Pre-flight `ContentSniffer.kt` currently contains an SSL bypass issue.

### 4. Phone Specifics
- Debrid APIs are highly sensitive to token management.
- WebExtension support is embedded natively via `assets/extensions/video_detector/`.

## Environment
- **Target SDK**: 36 | **Min SDK**: 26 (Phone)
- **JDK**: 17 | **AGP**: 9.0.1 | **Kotlin**: 2.2.10
