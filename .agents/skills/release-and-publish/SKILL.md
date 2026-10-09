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
| Android TV GeckoView plugin / TV Browser | `tv/android/geckoview-plugin/app/build.gradle.kts` | `tv/android/CHANGELOG.md` (GeckoView Plugin entries) |
| Desktop | `desktop/pubspec.yaml` and `desktop/lib/update/app_version.dart` | `desktop/CHANGELOG.md` |
| iOS phone | `mobile/apple/PlayBridge Phone/PlayBridge Phone.xcodeproj/project.pbxproj` | `mobile/apple/CHANGELOG.md` |
| Apple TV | `tv/apple/PlayBridge TV/PlayBridge TV.xcodeproj/project.pbxproj` | `tv/apple/CHANGELOG.md` |
| Extension | `extension/manifests/chrome.json` and `extension/manifests/firefox.json` | `extension/CHANGELOG.md` |
| CLI | `cli/Cargo.toml` and its root `Cargo.lock` entry | `cli/CHANGELOG.md` |
| Stream proxy (Rust) | `stream-proxy-rust/Cargo.toml` and its root `Cargo.lock` entry | `stream-proxy-rust/CHANGELOG.md` |

The embedded GeckoView manifest has its own version; do not synchronize it to the
store extension version automatically. Shared libraries, protocol and native ABI
versions also remain independent compatibility versions, not product train versions.

For Desktop, keep `version: <semver>+<build>` and `kAppVersion = '<semver>'` in lockstep. CI checks this in `desktop/test/update_test.dart`.

Monorepo release policy (PR checks → draft release-build on uprev → publish): see `docs/release.md`. CLI is the reference: uprev arms `cli_build.yml` (draft `cli-v*`); `cli_publish.yml` sets `draft=false`. Inspect the affected workflow before shipping: the Rust proxy's current `stream_proxy_build.yml` publishes images directly on eligible main changes; it does not yet implement the draft/promotion model. Merging a stream-proxy version
bump to `main` therefore publishes that version automatically after CI
passes (unless that version's stream-proxy tag already exists).

## Workflow

1. Inspect `git status` and the diff against the intended base. Identify affected projects without absorbing unrelated worktree changes.
   - Changes under `shared/` require checking both Android consumers. For every product, inspect direct changes and changes in shared code it builds from (for example `shared/`, `protocol/`, `cast/`, and the proxy) since its last published release.
   - A product with no direct or consumed shared-code changes skips the train and keeps its current version. When it next changes, it jumps straight to the then-current train minor at `x.y.0`.
   - For a patch release, uprev only the products intended to ship fixes; for a minor train, uprev every changed product in the version table and report unchanged products that skip it.
   - Keep Apple versions numeric and in the existing unstable `0.x` scheme, aligned with the train. Update all configurations/targets that carry the marketing/build versions, including extensions when they share them.
2. Choose the bump requested by the user. Use **minor for features, patch for fixes**: a minor bump advances changed products to the same `x.y.0` train, while unchanged products skip it; patches advance independently per product within its current train. A changed product that skipped the current train joins at `x.y.0` before shipping further patches. Features wait for the next train or start one. Internal refactors without a shipping fix do not require a bump. Increment each affected product's numeric build/version code once; Android codes are independent per application ID, not a monorepo-wide counter.
   - Sender/receiver compatibility is governed by the protocol version, not matching application versions. Protocol-breaking changes ship only in a new train, never in a patch; check supported protocol versions rather than requiring matching app versions.
3. Update every affected project's version source and add a concise Keep a Changelog entry dated today, based on changes since its last published release. Fold unpublished version entries into the new release. Keep Desktop's pubspec and kAppVersion synchronized and update the CLI/proxy lockfile entries without unrelated dependency upgrades.
4. Stage only task and release-metadata files. Never use `git add -A` when unrelated changes exist.
5. Run focused verification for each affected project. Always run Desktop's `flutter test test/update_test.dart`; check/test the CLI and stream proxy with `--locked` after updating Cargo.lock; run `./gradlew help` or focused checks from both Android roots sequentially because they share build outputs; lint both Apple project files with `plutil -lint` or inspect Xcode build settings; run extension tests and relevant store validation for manifest changes. Inspect website metadata when advancing a train.
6. Commit with a Conventional Commit message and follow the user's requested branch/PR delivery workflow using `commit-and-open-pr`. An explicitly requested direct-main push does not require a PR.
7. Include new versions and verification results in the PR description or direct-push report, as applicable.
8. After merge, for CLI: confirm the **draft** GitHub Release from `CLI Release Build`, then run **CLI Publish** when ready (do not treat draft creation as public ship).

Use the environment's configured Git/GitHub authentication. Do not clear or override authentication variables unless the user or environment specifically requires it.
