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
| Extension | `extension/manifests/chrome.json` and `extension/manifests/firefox.json` | `extension/CHANGELOG.md` |
| CLI | `cli/Cargo.toml` | `cli/CHANGELOG.md` |
| Stream proxy (Rust) | `stream-proxy-rust/Cargo.toml` | `stream-proxy-rust/CHANGELOG.md` |

The embedded GeckoView manifest has its own version; do not synchronize it to the
store extension version automatically. Inspect the TV GeckoView plugin's own
`tv/android/geckoview-plugin/app/build.gradle.kts` when releasing that product.

For Desktop, keep `version: <semver>+<build>` and `kAppVersion = '<semver>'` in lockstep. CI checks this in `desktop/test/update_test.dart`.

Monorepo release policy (PR checks → draft release-build on uprev → publish): see `docs/release.md`. CLI is the reference: uprev arms `cli_build.yml` (draft `cli-v*`); `cli_publish.yml` sets `draft=false`. Inspect the affected workflow before shipping: the Rust proxy's current `stream_proxy_build.yml` publishes images directly on eligible main changes; it does not yet implement the draft/promotion model.

## Workflow

1. Inspect `git status` and the diff against the intended base. Identify affected projects without absorbing unrelated worktree changes.
   - Changes under `shared/` require checking both Android consumers; uprev only the products intended for this release.
   - Keep Apple versions in their existing unstable `0.x` scheme.
2. Choose the bump requested by the user. If unspecified, use minor for features and patch for fixes/refactors; increment the numeric build/version code once.
3. Update every affected project's version source and add a concise Keep a Changelog entry dated today.
4. Stage only task and release-metadata files. Never use `git add -A` when unrelated changes exist.
5. Run focused verification for each affected project. For Desktop, always run `flutter test test/update_test.dart`.
6. Commit with a Conventional Commit message and follow the user's requested branch/PR delivery workflow using `commit-and-open-pr`. An explicitly requested direct-main push does not require a PR.
7. Include new versions and verification results in the PR description or direct-push report, as applicable.
8. After merge, for CLI: confirm the **draft** GitHub Release from `CLI Release Build`, then run **CLI Publish** when ready (do not treat draft creation as public ship).

Use the environment's configured Git/GitHub authentication. Do not clear or override authentication variables unless the user or environment specifically requires it.
