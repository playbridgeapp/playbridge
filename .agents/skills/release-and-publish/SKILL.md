---
name: release-and-publish
description: Prepare a PlayBridge release by explicitly requested version uprevs, changelog updates, verification, commit, push, and PR creation or update. Use only when the user asks for an uprev, version bump, or release; do not trigger for an ordinary commit, push, or PR request.
---

# Release and publish

## Version locations

| Project | Version source | Changelog |
|---|---|---|
| Android phone | `mobile/android/app/build.gradle.kts` | `mobile/android/CHANGELOG.md` |
| Android TV player | `tv/android/player/app/build.gradle.kts` | `tv/android/CHANGELOG.md` |
| Desktop | `desktop/pubspec.yaml` and `desktop/lib/update/app_version.dart` | `desktop/CHANGELOG.md` |
| iOS phone | `mobile/apple/PlayBridge Phone/PlayBridge Phone.xcodeproj/project.pbxproj` | `mobile/apple/CHANGELOG.md` |
| Apple TV | `tv/apple/PlayBridge TV/PlayBridge TV.xcodeproj/project.pbxproj` | `tv/apple/CHANGELOG.md` |
| Extension | `extension/manifests/chrome.json` | `extension/CHANGELOG.md` |

For Desktop, keep `version: <semver>+<build>` and `kAppVersion = '<semver>'` in lockstep. CI checks this in `desktop/test/update_test.dart`.

## Workflow

1. Inspect `git status` and the diff against the intended base. Identify affected projects without absorbing unrelated worktree changes.
   - Changes under `shared/` affect both Android phone and TV releases.
   - Keep Apple versions in their existing unstable `0.x` scheme.
2. Choose the bump requested by the user. If unspecified, use minor for features and patch for fixes/refactors; increment the numeric build/version code once.
3. Update every affected project's version source and add a concise Keep a Changelog entry dated today.
4. Stage only task and release-metadata files. Never use `git add -A` when unrelated changes exist.
5. Run focused verification for each affected project. For Desktop, always run `flutter test test/update_test.dart`.
6. Commit with a Conventional Commit message, push the feature/release branch, and create or update the PR with `gh`.
7. Include new versions and verification results in the PR description.

Use the environment's configured Git/GitHub authentication. Do not clear or override authentication variables unless the user or environment specifically requires it.
