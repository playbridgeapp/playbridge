# PlayBridge — Agent Guide

PlayBridge is a multi-platform casting suite. Treat its Android, Flutter, Apple, extension, and web trees as separate projects; run commands from the project that owns the change.

## Shared skills

Portable project skills live in `.agents/skills/` and are the canonical specialist definitions for Codex, OpenCode, Claude, and other agents.

### Task workflows

| Skill | Use when |
|---|---|
| `release-and-publish` | The user explicitly requests an uprev or release |
| `commit-and-open-pr` | Commit, push, or create/update a pull request without an implicit uprev |
| `code-review-graph-workflow` | Explore, debug, review, or refactor using the repository graph |

### Project specialists

| Skill | Owns |
|---|---|
| `playbridge-android` | Android phone, Android TV, shared Kotlin, and shared Android dependencies |
| `playbridge-apple` | Apple phone and Apple TV applications |
| `playbridge-desktop-proxy` | Flutter Desktop, its Rust-backed receiver adapter, and the Dart stream proxy |
| `playbridge-extension` | Browser extension and native-messaging integration |
| `playbridge-web` | Svelte website and static web assets |
| `playbridge-protocol` | Protocol schema, generated bindings, and consumer compatibility |
| `playbridge-rust-core` | Portable Rust casting and receiver engines, UniFFI/C/JNI bindings, and Rust CLI |
| `playbridge-stream-proxy-rust` | High-performance Rust streaming proxy and MediaFlow encryption |

Before working in a project, load the matching specialist skill. For cross-project work, load each affected specialist or delegate non-overlapping consumers to subagents using those skills. Keep shared contracts and files with one designated writer, and keep the primary agent responsible for integration and final verification.

Load a task workflow only when its description matches the request. A normal commit, push, or PR request must not change versions unless the user also requests an uprev or release.

## Local agent instructions

If `SUBAGENTS.local.md` exists at the repository root, read and follow it for optional machine-local subagent tools and workflows. The file is intentionally gitignored; shared repository instructions in this file take precedence if they conflict.

## Project layout

| Project | Path | Notes |
|---|---|---|
| Android phone | `mobile/android/` | Kotlin, Compose, GeckoView; Gradle modules `:app` and `:shared` |
| Android TV | `tv/android/` | Kotlin, TV UI, MPV/VLC; Gradle modules `:player:app`, `:geckoview-plugin:app`, and `:shared` |
| Shared Kotlin | `shared/` | KMP protocol and shared playback/domain logic, included by both Android builds |
| Apple phone | `mobile/apple/` | Swift/Xcode project |
| Apple TV | `tv/apple/` | Swift/Xcode project |
| Desktop | `desktop/` | Flutter receiver for macOS, Windows, and Linux |
| Stream proxy (Dart) | `stream-proxy-dart/` | Standalone Dart proxy, embedded by Desktop and released as a Docker image |
| Stream proxy (Rust) | `stream-proxy-rust/` | High-performance Rust streaming proxy with MediaFlow AES-256 encryption |
| Rust Cast Core | `cast/core/` | Portable discovery, protocol clients, pairing primitives, and casting sessions |
| Rust Receiver | `cast/receiver/` | Secure reusable PlayBridge WSS receiver runtime; consumers provide playback and platform lifecycle |
| Rust FFI | `cast/ffi/` | UniFFI plus stable C/JNI bindings for Cast Core and the receiver runtime |
| Dart Cast bindings | `packages/playbridge_cast_core_dart/` | Dart FFI wrappers consumed by Flutter Desktop and standalone Dart applications |
| Rust CLI | `cli/` | Command-line client binary (`playbridge`) for Rust Core |
| Extension | `extension/` | Browser extension, JavaScript/TypeScript |
| Web | `web/` | Svelte site |
| Protocol assets | `protocol/` | In-repository AsyncAPI contract, protocol documentation, and generated artifacts |

## Build and test

On macOS, always run Gradle through `zsh -c "source ~/.zshrc && ./gradlew ..."` from the relevant Gradle root.

```bash
# Phone — from mobile/android
zsh -c "source ~/.zshrc && ./gradlew :app:assembleDebug"
zsh -c "source ~/.zshrc && ./gradlew :app:testFossDebugUnitTest"
zsh -c "source ~/.zshrc && ./gradlew :app:lintFossDebug"

# TV — from tv/android
zsh -c "source ~/.zshrc && ./gradlew :player:app:assembleDebug"
zsh -c "source ~/.zshrc && ./gradlew test"
zsh -c "source ~/.zshrc && ./gradlew lint"

# Desktop — from desktop
flutter test
flutter analyze

# Prefer a focused test while iterating
zsh -c "source ~/.zshrc && ./gradlew :app:testFossDebugUnitTest --tests \"fully.qualified.TestClass.methodName\""
flutter test test/path_to_test.dart
```

The root `shared/` directory has no standalone Gradle wrapper. Validate shared changes through both Android Gradle roots when they affect both consumers.

## Repository rules

- Follow Kotlin conventions and existing local style; no ktlint/detekt formatter is configured.
- Use explicit imports, remove unused imports, prefer non-nullable types, and expose Compose state through `StateFlow`.
- Use Conventional Commits.
- Preserve unrelated user changes in a dirty worktree; stage only files belonging to the requested task.
- Never log Debrid tokens, signing credentials, authenticated stream URLs, or other secrets.

## Critical cross-project constraints

### Protocol ripple

Changes to `shared/src/commonMain/kotlin/com/playbridge/shared/protocol/Message.kt` must be checked against:

- `mobile/android/app/src/main/java/com/playbridge/sender/connection/ConnectionViewModel.kt`
- `tv/android/player/app/src/main/java/com/playbridge/player/server/ServerService.kt`
- `extension/src/background.ts`, which manually handles protocol JSON

### Native receiver runtime ripple

Changes to the PlayBridge receiver wire behavior, `cast/receiver/` public API, or
the receiver C ABI must be checked across:

- `cast/core/src/playbridge.rs`
- `cast/ffi/src/receiver_runtime.rs` and `cast/ffi/include/playbridge_cast_core.h`
- `packages/playbridge_cast_core_dart/lib/src/receiver_runtime.dart`
- `desktop/lib/receiver_server.dart`, `desktop/lib/cert_manager.dart`, and `desktop/lib/pairing_store.dart`
- `cli/src/receive.rs`

Rust owns TLS/WSS, pairing, authentication, resource limits, and typed command
delivery. Consumer applications own playback, UI, discovery lifecycle, and
platform services. Preserve existing TLS identities/SPKI pins, accept compatible
raw and SHA-256 token records during migrations, and never log the raw token
emitted by a successful pairing. Bump the receiver ABI version for incompatible
C/Dart changes, rebuild Desktop libraries with `cast/build-desktop.sh`, and run
the native receiver smoke test.

### Shared dependency versions

Phone and TV consume the root `gradle/libs.versions.toml`. Keep GeckoView/Media3 changes compatible with both Android projects and the shared decoder AARs under `prebuilt/media3/`.

### TV networking

The TV app intentionally permits cleartext stream traffic, including a blanket cleartext base configuration, because direct and torrent streams may use HTTP. Do not “harden” this away without an explicit product decision. Local/private URL checks scope the exceptional TLS behavior in `ContentSniffer.kt`; do not broaden it to public hosts.

## Knowledge graph first

Before Grep/Glob/Read exploration, use the `code-review-graph` MCP tools:

1. Start with `get_minimal_context` for the task.
2. Use `detect_changes` for reviews, `semantic_search_nodes` for discovery, and `query_graph` for callers/callees/imports/tests.
3. Use `get_impact_radius` or `get_affected_flows` when blast radius matters.
4. Fall back to `rg` and focused file reads when the graph lacks coverage or exact text is required.

The graph updates through repository hooks. Do not impose fixed tool-call or token quotas when additional evidence is needed.

## Environment

- Target SDK 36; phone min SDK 26
- JDK 17; AGP 9.0.1; Kotlin 2.2.10
- Root version catalog: `gradle/libs.versions.toml`
