# PlayBridge Project Uprev & PR Release Guide

This document is a quick reference guide on how to update project versions, write changelogs, commit, push, and manage Pull Requests across the PlayBridge repository.

## Project Layout & Version Locations

| Project | Config File Path | Version Keys | Changelog Path |
| :--- | :--- | :--- | :--- |
| **Android Phone** | [mobile/android/app/build.gradle.kts](file:///Users/atulmehla/repos/personal/playbridgeapp/PlayBridge/mobile/android/app/build.gradle.kts) | `versionCode = <Int>`<br>`versionName = "<SemVer>"` | [mobile/android/CHANGELOG.md](file:///Users/atulmehla/repos/personal/playbridgeapp/PlayBridge/mobile/android/CHANGELOG.md) |
| **Android TV Player** | [tv/android/player/app/build.gradle.kts](file:///Users/atulmehla/repos/personal/playbridgeapp/PlayBridge/tv/android/player/app/build.gradle.kts) | `versionCode = <Int>`<br>`versionName = "<SemVer>"` | [tv/android/CHANGELOG.md](file:///Users/atulmehla/repos/personal/playbridgeapp/PlayBridge/tv/android/CHANGELOG.md) |
| **Desktop Receiver** | [desktop/pubspec.yaml](file:///Users/atulmehla/repos/personal/playbridgeapp/PlayBridge/desktop/pubspec.yaml) | `version: <SemVer>+<BuildNumber>` | [desktop/CHANGELOG.md](file:///Users/atulmehla/repos/personal/playbridgeapp/PlayBridge/desktop/CHANGELOG.md) |
| **iOS Phone App** | [mobile/apple/PlayBridge Phone/PlayBridge Phone.xcodeproj/project.pbxproj](file:///Users/atulmehla/repos/personal/playbridgeapp/PlayBridge/mobile/apple/PlayBridge%20Phone/PlayBridge%20Phone.xcodeproj/project.pbxproj) | `CURRENT_PROJECT_VERSION = <BuildNumber>;`<br>`MARKETING_VERSION = "<SemVer>";` | [mobile/apple/CHANGELOG.md](file:///Users/atulmehla/repos/personal/playbridgeapp/PlayBridge/mobile/apple/CHANGELOG.md) |
| **Apple TV App** | [tv/apple/PlayBridge TV/PlayBridge TV.xcodeproj/project.pbxproj](file:///Users/atulmehla/repos/personal/playbridgeapp/PlayBridge/tv/apple/PlayBridge%20TV/PlayBridge%20TV.xcodeproj/project.pbxproj) | `CURRENT_PROJECT_VERSION = <BuildNumber>;`<br>`MARKETING_VERSION = <SemVer>;` | [tv/apple/CHANGELOG.md](file:///Users/atulmehla/repos/personal/playbridgeapp/PlayBridge/tv/apple/CHANGELOG.md) |
| **Extension** | [extension/manifests/chrome.json](file:///Users/atulmehla/repos/personal/playbridgeapp/PlayBridge/extension/manifests/chrome.json) | `"version": "<SemVer>"` | [extension/CHANGELOG.md](file:///Users/atulmehla/repos/personal/playbridgeapp/PlayBridge/extension/CHANGELOG.md) |

---

## Workflow Steps

### 1. Identify Modified Projects
Run `git status` or `git diff --name-only origin/main` to identify modified directories:
* Changes in `mobile/android/` or `shared/` -> Android Phone
* Changes in `tv/android/player/` or `shared/` -> Android TV Player
* Changes in `desktop/` -> Desktop
* Changes in `mobile/apple/` -> iOS Phone
* Changes in `tv/apple/` -> Apple TV
* Changes in `extension/` -> Extension

### 2. Determine Version Bumps
* **Minor Bump (Features)**: Bumps the minor version digit (e.g. `0.5.0` -> `0.6.0`, build/code incremented by 1).
* **Patch Bump (Fixes/Refactors)**: Bumps the patch version digit (e.g. `0.6.0` -> `0.6.1`, build/code incremented by 1).
* **Apple TV / iOS Phone Caveat**: Keep version prefixes in the unstable `0.x` range (e.g. `0.2.0-alpha` or `0.3.0-alpha`).

### 3. Edit Version Files and Changelogs
Update the config files and append new release headers to the respective `CHANGELOG.md` files using the [Keep a Changelog](https://keepachangelog.com/) template:
```markdown
## [<Version>] — <YYYY-MM-DD> (build <BuildNumber>/versionCode <Code>)

### Added (or Fixed/Changed)
- Detailed release description with issue/PR references.
```

### 4. Git Push & PR Commands
**IMPORTANT**: When pushing or interacting with the GitHub CLI (`gh`), you must clear the `GITHUB_TOKEN` environment variable so that standard SSH/keychain configuration is used instead of system variables:
```bash
# Push branch to remote
env GITHUB_TOKEN="" git push origin <branch-name>

# Create Pull Request
env GITHUB_TOKEN="" gh pr create --title "<PR Title>" --body-file "<Path to PR body MD>"

# Update an existing Pull Request's metadata
env GITHUB_TOKEN="" gh pr edit <PR_Number> --title "<PR Title>" --body-file "<Path to PR body MD>"
```
