---
name: uprev-and-publish
description: Stage changes, identify modified projects, uprev version codes/names (semver), update per-project changelogs, commit, push, and create/update PRs using the GitHub CLI with an empty GITHUB_TOKEN.
---

# Uprev and Publish Skill

Use this skill when the user requests to uprev versions, update changelogs, commit, push, or create/update a Pull Request for PlayBridge project changes.

## Project Structure & Version Locations

| Project | Config File Path | Version Keys | Changelog Path |
| :--- | :--- | :--- | :--- |
| **Android Phone** | `mobile/android/app/build.gradle.kts` | `versionCode = <Int>`<br>`versionName = "<SemVer>"` | `mobile/android/CHANGELOG.md` |
| **Android TV Player** | `tv/android/player/app/build.gradle.kts` | `versionCode = <Int>`<br>`versionName = "<SemVer>"` | `tv/android/CHANGELOG.md` |
| **Desktop Receiver** | `desktop/pubspec.yaml`<br>**and** `desktop/lib/update/app_version.dart` | `version: <SemVer>+<BuildNumber>` in pubspec<br>`const String kAppVersion = '<SemVer>';` in `app_version.dart` (**semver only**, no `+build`) | `desktop/CHANGELOG.md` |
| **iOS Phone App** | `mobile/apple/PlayBridge Phone/PlayBridge Phone.xcodeproj/project.pbxproj` | `CURRENT_PROJECT_VERSION = <BuildNumber>;`<br>`MARKETING_VERSION = "<SemVer>";` | `mobile/apple/CHANGELOG.md` |
| **Apple TV App** | `tv/apple/PlayBridge TV/PlayBridge TV.xcodeproj/project.pbxproj` | `CURRENT_PROJECT_VERSION = <BuildNumber>;`<br>`MARKETING_VERSION = <SemVer>;` | `tv/apple/CHANGELOG.md` |
| **Extension** | `extension/manifests/chrome.json` | `"version": "<SemVer>"` | `extension/CHANGELOG.md` |

### Desktop dual-source rule (CRITICAL)

Desktop versions live in **two** places and **must stay in lockstep**:

1. `desktop/pubspec.yaml` → `version: 0.x.y+N` (semver + build metadata)
2. `desktop/lib/update/app_version.dart` → `const String kAppVersion = '0.x.y';` (**semver only**, strip the `+N` build suffix)

CI enforces this via `desktop/test/update_test.dart` (`kAppVersion matches pubspec.yaml`). Updating only `pubspec.yaml` will **fail Desktop PR Checks**. Always bump both in the same commit when upreving desktop.

---

## Workflow Steps

### Step 1: Identify Modified Projects
Run `git status` or `git diff --name-only origin/main` to identify which directories contain modifications:
* If changes are in `mobile/android/` or `shared/` -> Android Phone needs uprev.
* If changes are in `tv/android/player/` or `shared/` -> Android TV Player needs uprev.
* If changes are in `desktop/` -> Desktop needs uprev.
* If changes are in `mobile/apple/` -> iOS Phone needs uprev.
* If changes are in `tv/apple/` -> Apple TV needs uprev.
* If changes are in `extension/` -> Extension needs uprev.

### Step 2: Determine Bump Type
Analyze the commits or staged changes to decide the version bump:
* **Features**: Increment the minor version (e.g. `0.5.0` -> `0.6.0`, build/code incremented by 1).
* **Bug Fixes / Refactors**: Increment the patch version (e.g. `0.6.0` -> `0.6.1`, build/code incremented by 1).
* **Apple TV / iOS Phone Warning**: Keep versions in the unstable `0.x.y` range (e.g. `0.2.0-alpha` or `0.3.0-alpha`) since they are not fully stable.

### Step 3: Edit Versions and Changelogs
1. Apply version bumps to the respective configuration files.
   - **Desktop**: update **both** `desktop/pubspec.yaml` (`version: <SemVer>+<Build>`) **and** `desktop/lib/update/app_version.dart` (`kAppVersion = '<SemVer>'`). Example: pubspec `0.6.7+25` → `kAppVersion = '0.6.7'`.
2. Add a new version header and summary of changes to the top of the corresponding `CHANGELOG.md` file using the **[Keep a Changelog](https://keepachangelog.com/)** format:
   ```markdown
   ## [<Version>] — <YYYY-MM-DD> (build <BuildNumber>/versionCode <Code>)

   ### Added (or Fixed/Changed)
   - Description of changes with PR/issue references.
   ```
3. Stage all modified and untracked files using `git add -A`.

### Step 4: Verification
Confirm that projects build and check configs for syntax issues (e.g. run `./gradlew check` for Android modules).

When desktop was upreved, run the drift guard test before push:
```bash
cd desktop && flutter test test/update_test.dart
```
Expect `kAppVersion matches pubspec.yaml` to pass.

### Step 5: Git Branching & Commit
1. If not already on a feature/bugfix branch, checkout to a new descriptive branch:
   ```bash
   git checkout -b <branch-name>
   ```
2. Commit all staged files:
   ```bash
   git commit -m "chore: bump project versions and update changelogs for <version>"
   ```

### Step 6: Push & Pull Request Creation (Empty GITHUB_TOKEN)
**CRITICAL**: You MUST execute all push and `gh` CLI commands setting `GITHUB_TOKEN` to empty so the local keychain/SSH configuration is used:
```bash
env GITHUB_TOKEN="" git push origin <branch-name>
env GITHUB_TOKEN="" gh pr create --title "<Descriptive PR Title>" --body-file "<Path to PR Description MD>"
```

### Step 7: Update PR Title and Description
If the PR is already created, update its details:
```bash
env GITHUB_TOKEN="" gh pr edit <PR_Number> --title "<PR Title>" --body-file "<Path to PR Description MD>"
```
Ensure the PR description lists all upreved version numbers and a clear changes summary.
