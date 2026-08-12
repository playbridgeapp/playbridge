# PlayBridge release model

PlayBridge is a monorepo with **independently versioned** products. Shipping one
project does not require shipping the others.

## Three stages

```
1. PR checks          → quality gate (every PR)
2. Release-build      → versioned candidates (uprev + changelog)
3. Publish            → public GitHub Release and/or store submit
```

| Stage | When | Output | Public users? |
| --- | --- | --- | --- |
| **PR checks** | Pull request (path-filtered) | Tests / lint / debug builds | No |
| **Release-build** | Version **uprev** (+ changelog) on `main`, or explicit dispatch | Installable binaries as a **draft** GitHub Release (or registry RC) | No (draft / internal) |
| **Publish** | Manual workflow / environment approval | `draft=false`, store promote, image retag | Yes |

### Rules

1. **Independent versions** — extension-only work only uprevs the extension.
2. **Uprev = intent to ship that project** — bump its version file and changelog.
3. **No uprev → no release-build** — ordinary merges only run PR-style CI.
4. **Draft is the handoff** — release-build attaches assets to a draft release;
   publish promotes that same version without rebuilding it.
5. **Stores are separate steps from GitHub** — Android uploads to Play first and
   only undrafts the GitHub release after the configured store upload succeeds.
6. **Shared crates** (`cast/`, `protocol/`, `shared/`) rebuild consumer CI; only
   **upreved consumers** get release-build / publish.

Draft releases are visible to collaborators with write access, not to anonymous
users or the `playbridge.app` download resolver, which only considers published
stable releases after each release's rollout delay.

---

## Per-project map

| Project | Version source | Tag prefix | Changelog | PR CI | Release-build | Publish |
| --- | --- | --- | --- | --- | --- | --- |
| **CLI** | `cli/Cargo.toml` | `cli-v*` | `cli/CHANGELOG.md` | `rust_pr.yml` | `cli_build.yml` → **draft** | `cli_publish.yml` → undraft |
| **Extension** | `extension/manifests/*.json` | `extension-v*` | `extension/CHANGELOG.md` | `extension_pr.yml` | `extension_build.yml` → draft | Publish workflow — *target* |
| **Desktop** | `desktop/pubspec.yaml` | `desktop-v*` | `desktop/CHANGELOG.md` | `desktop_pr.yml` | `desktop_build.yml` → **draft** | `desktop_publish.yml` → undraft |
| **Android phone** | `versionName` / `versionCode` | `phone-v*` | `mobile/android/CHANGELOG.md` | `android_pr.yml` | `android_build.yml` → **draft** | `android_publish.yml` → Play + undraft |
| **Android TV player** | TV `versionName` / `versionCode` | `tv-player-v*` | `tv/android/CHANGELOG.md` | `android_pr.yml` | `android_build.yml` → **draft** | `android_publish.yml` → Play + undraft |
| **Android TV GeckoView plugin** | TV `versionName` / `versionCode` | `tv-geckoview-plugin-v*` | `tv/android/CHANGELOG.md` | `android_pr.yml` | `android_build.yml` → **draft** | `android_publish.yml` → undraft |
| **Stream proxy** | crate / image version | image tags / optional git tag | project changelog | `stream_proxy_pr.yml` | `stream_proxy_build.yml` | Promote image tags — *target* |
| **Web** | deploy-on-main | n/a | n/a | `web_pr.yml` | — | `web_deploy.yml` (Pages) |
| **Protocol / Rust core** | n/a (library) | n/a | n/a | contract / rust PR checks | ships inside consumers | — |
| **Apple apps** | Xcode marketing version | store / TestFlight | Apple changelogs | local / future CI | archive | App Store Connect |

### GitHub release search markers

Each GitHub Release body includes a stable HTML comment so the site and README can
deep-link to that product’s releases (`?q=<marker>&expanded=true`):

| Product | Marker | Example |
| --- | --- | --- |
| Desktop | `1a4b6c` | [releases?q=1a4b6c](https://github.com/playbridgeapp/PlayBridge/releases?q=1a4b6c&expanded=true) |
| Phone | `5c9b2f` | [releases?q=5c9b2f](https://github.com/playbridgeapp/PlayBridge/releases?q=5c9b2f&expanded=true) |
| TV player | `8d2a1c` | [releases?q=8d2a1c](https://github.com/playbridgeapp/PlayBridge/releases?q=8d2a1c&expanded=true) |
| TV GeckoView plugin | `3e7f9a` | [releases?q=3e7f9a](https://github.com/playbridgeapp/PlayBridge/releases?q=3e7f9a&expanded=true) |
| Extension | `9f2d8e` | [releases?q=9f2d8e](https://github.com/playbridgeapp/PlayBridge/releases?q=9f2d8e&expanded=true) |
| CLI | `7b2c9a` | [releases?q=7b2c9a](https://github.com/playbridgeapp/PlayBridge/releases?q=7b2c9a&expanded=true) |

Keep the marker in every draft/publish body for that product. Do not reuse markers across products.

---

## CLI (reference implementation)

### Uprev checklist

1. Bump `cli/Cargo.toml` `version`.
2. Add a `## X.Y.Z (YYYY-MM-DD)` section to `cli/CHANGELOG.md`.
3. Open a PR; merge to `main` after CI is green.
4. **Release-build** (`CLI Release Build`) runs when the version **changed** on
   that push (or via `workflow_dispatch`), and no published tag exists yet.
5. Inspect the **draft** GitHub Release `cli-vX.Y.Z` and download assets to test.
6. Run **CLI Publish** (`workflow_dispatch`, input = version) to set `draft=false`.
7. Wait for the configured rollout delay. The `playbridge.app` manifest and download
   endpoints then offer the release to `cli/install.sh` and the dashboard updater.

### Workflows

| Workflow | File | Role |
| --- | --- | --- |
| Rust / CLI PR checks | `.github/workflows/rust_pr.yml` | Format, test, lint; smoke-build CLI |
| CLI Release Build | `.github/workflows/cli_build.yml` | Multi-arch package → **draft** `cli-v*` |
| CLI Publish | `.github/workflows/cli_publish.yml` | Promote draft → public release |

### Release-build skip logic

Runs the full matrix only when:

- event is `workflow_dispatch`, or
- `cli/Cargo.toml` version **differs** from the previous commit, and
- a **published** (non-draft) release for `cli-v$VERSION` does not already exist.

If a draft already exists for that tag, re-run with **force** on
`workflow_dispatch` to delete the draft/tag and rebuild.

### Publish

```text
gh release edit cli-vX.Y.Z --draft=false
```

No rebuild. Assets stay those attached at draft time. No app store for CLI.

### CLI update distribution

GitHub Releases remains the source of CI-built archives and `SHA256SUMS`. Clients
do not query GitHub directly: Cloudflare Pages resolves the current eligible
release at `GET /api/v1/updates/cli?os=<os>&arch=<arch>`. The resolver rejects
drafts and prereleases, applies the release-note rollout delay, and returns the
matching asset URL and SHA-256 digest. `/download/cli-<os>-<arch>` uses the same
resolver for shell installs, preventing the installer and dashboard from drifting
to different versions.

### Release rollout delay

All PlayBridge artifacts distributed through `playbridge.app` use the same
release-note marker:

```html
<!-- playbridge-rollout-delay-hours: 24 -->
```

The generated release notes include this marker by default. It is editable in
the GitHub release form before or after publication: use `0` to make a published
release eligible immediately, or an integer from `1` through `168` to delay it.
If the marker is omitted or invalid, the resolver defaults to 24 hours. This
applies to CLI, Android phone, Android TV Player, Android TV GeckoView plugin,
Desktop, and Firefox extension downloads; store-distributed builds are excluded.

The resolver caches a result for at most 10 minutes. GitHub release pages and
their assets are public immediately after publication; the marker only controls
what `playbridge.app` advertises through its download and update endpoints.

The CLI caches successful checks for 24 hours and failed checks for one hour.
Dashboard installation verifies the digest, stages a sibling executable, keeps a
rollback copy, and relaunches only after the TUI has restored the terminal. It
must not replace the executable while a cast or receiver session is active.

### Manual / force

```text
Actions → CLI Release Build → Run workflow
  force: true   # optional rebuild of draft
Actions → CLI Publish → Run workflow
  version: 0.1.1
```

## Android and Desktop publish

`Android Release Build` creates separate draft releases for Phone, TV Player,
and the TV GeckoView plugin. Run `Android Publish` with the product and version
after inspecting the draft assets. Phone and TV Player upload the attached AAB
to their configured Play Store track first; the GitHub release is undrafted only
after that upload succeeds. The GeckoView plugin has no Play Store step.

`Desktop Release Build` creates a draft `desktop-v*` release containing all
platform archives. Run `Desktop Publish` with the version to undraft the existing
release without rebuilding its assets.

---

## Extending the model to other products

When touching another `*_build.yml`:

1. Gate on **version bump** (not only “tag missing”).
2. Emit a **draft** GitHub Release (or version-keyed staging), not a live publish.
3. Add a small **publish** workflow: undraft and/or store submit.
4. Keep PR workflows free of release side effects.

Prefer GitHub **Environments** (e.g. `cli-release`) for human approval on
publish jobs rather than mid-build Issue bots.

---

## Agent / contributor notes

- Ordinary PRs must **not** bump versions unless the user asked for an uprev or
  release (`release-and-publish` skill).
- One uprev PR per product when practical (`bump(cli): 0.1.1`).
- Never log secrets, store keys, or pairing tokens in release notes or CI logs.
