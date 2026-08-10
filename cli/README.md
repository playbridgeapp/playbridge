# PlayBridge CLI

`playbridge` is a cross-platform sender and receiver built on PlayBridge's Rust
casting core. It can discover receivers, cast local files or URLs, host a browser
receiver, or receive PlayBridge casts through an installed `mpv`.

## Install

On macOS or Linux:

```sh
curl -fsSL https://raw.githubusercontent.com/playbridgeapp/playbridge/main/cli/install.sh | sh
```

The installer downloads the latest `cli-v*` GitHub release, verifies its SHA-256
checksum, and installs `playbridge` to `~/.local/bin`. Override the defaults with:

```sh
PLAYBRIDGE_VERSION=0.1.0 PLAYBRIDGE_INSTALL_DIR=/usr/local/bin \
  sh cli/install.sh
```

Windows binaries and manual archives for every supported platform are available
from [GitHub Releases](https://github.com/playbridgeapp/playbridge/releases).

Receiver mode requires [`mpv`](https://mpv.io/) to be installed and available on
`PATH`. Its TLS/WSS, pairing, authentication, limits, queue commands, and status
transport use the same `playbridge-cast-receiver` crate as Flutter Desktop;
only the external-mpv playback adapter remains CLI-specific.

## Examples

```sh
playbridge
playbridge discover
playbridge send video.mp4
playbridge receiver --name "My Computer"
playbridge browser video.mp4
```

Running `playbridge` in an interactive terminal opens the dashboard. `send`,
`cast`, and a bare media path open that same dashboard with the source already
selected; casting, browser-receiver pairing, and receiver hosting remain inside
it when started. It includes live receiver discovery, a media-focused local file
picker, URL casting, receiver hosting, an outgoing-cast Remote with live status
and controls, incoming receiver playback status and controls, settings,
contextual help, and a command palette.
First-time PlayBridge pairing also stays in the dashboard: compare the six-digit
code shown there with the receiver, then confirm without leaving the TUI.
Arrow keys and Vim keys (`h`, `j`, `k`, `l`) are supported; press `?` for the
complete key guide.
Machine-readable discovery and low-level diagnostic commands remain available
for scripts. Interactive workflows require a terminal and always use the
dashboard.

The dashboard adapts to narrow terminals and supports mouse input when enabled.
It honors `NO_COLOR` and includes dark, light, terminal, and monochrome themes:

```sh
playbridge --theme playbridge-light
playbridge config path
playbridge config check
```

Persistent UI settings live in `~/.config/playbridge/config.toml`. Every field is
optional. For example:

```toml
[ui]
theme = "playbridge-dark"
mouse = true
unicode = true

[keys]
down = ["down", "j"]
up = ["up", "k"]
palette = ["ctrl+p", ":"]

[theme]
accent = "#43d3ee"
selection = "blue"
```

Run `playbridge --help` for all commands and options.

Automatic update output is suppressed while the full-screen dashboard is active
so it cannot corrupt the TUI. Selected non-dashboard diagnostic commands check
for a newer `cli-v*` release at most once per day and print the install command
when an update is available. Network failures are silent and retried after one
hour. Set `PLAYBRIDGE_NO_UPDATE_CHECK=1` to disable these checks.

## Build from source

From the repository root:

```sh
cargo build --release --locked -p playbridge-cast-cli
```

## Releasing

CLI versions are independent of other monorepo products. Shipping is **uprev-gated**:

1. Bump `cli/Cargo.toml` and add a section to `cli/CHANGELOG.md`.
2. Merge to `main` → **CLI Release Build** creates a **draft** `cli-v*` GitHub Release with multi-arch archives.
3. Run **Actions → CLI Publish** with that version to set `draft=false` (no rebuild).

Full monorepo policy: [`docs/release.md`](../docs/release.md).
