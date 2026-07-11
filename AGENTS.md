# PlayBridge — Agent Guide

PlayBridge is a multi-platform casting suite. Treat its Android, Flutter, Apple, extension, and web trees as separate projects; run commands from the project that owns the change.

## Shared skills

Project skills live in `.agents/skills/`:

| Skill | Use when |
|---|---|
| `release-and-publish` | The user explicitly requests an uprev or release |
| `commit-and-open-pr` | Commit, push, or create/update a pull request without an implicit uprev |
| `code-review-graph-workflow` | Explore, debug, review, or refactor using the repository graph |

Load the matching `SKILL.md` only when its description matches the request. A normal commit, push, or PR request must not change versions unless the user also requests an uprev or release.

## Project layout

| Project | Path | Notes |
|---|---|---|
| Android phone | `mobile/android/` | Kotlin, Compose, GeckoView; Gradle modules `:app` and `:shared` |
| Android TV | `tv/android/` | Kotlin, TV UI, MPV/VLC; Gradle modules `:player:app`, `:geckoview-plugin:app`, and `:shared` |
| Shared Kotlin | `shared/` | KMP protocol and shared playback/domain logic, included by both Android builds |
| Apple phone | `mobile/apple/` | Swift/Xcode project |
| Apple TV | `tv/apple/` | Swift/Xcode project |
| Desktop | `desktop/` | Flutter receiver for macOS, Windows, and Linux |
| Extension | `extension/` | Browser extension, JavaScript/TypeScript |
| Web | `web/` | Svelte site |
| Protocol assets | `protocol/` | Protocol definitions and generated artifacts |

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
- `extension/src/background.js`, which manually handles protocol JSON

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
