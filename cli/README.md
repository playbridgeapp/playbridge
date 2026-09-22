# PlayBridge CLI

`playbridge` is a cross-platform sender and receiver built on PlayBridge's Rust
casting core. It can discover receivers, cast local files or URLs, host a browser
receiver, or receive PlayBridge casts through an installed `mpv`.

## Install

On macOS or Linux:

```sh
curl -fsSL https://playbridge.app/install.sh | sh
```

On Windows, run in PowerShell:

```powershell
irm https://playbridge.app/install.ps1 | iex
```

The installer resolves the latest stable build through `playbridge.app`, verifies
its SHA-256 checksum, and installs `playbridge` to `~/.local/bin` on macOS/Linux
or `%LOCALAPPDATA%\PlayBridge\bin` on Windows. The Windows installer adds that
directory to the current user's `PATH` without requiring administrator access.
Windows ARM64 uses the published x64 build through Windows emulation. GitHub
Releases remain the artifact store, while the PlayBridge update service controls
which published build is offered. Override the defaults with:

```sh
PLAYBRIDGE_VERSION=0.1.0 PLAYBRIDGE_INSTALL_DIR=/usr/local/bin \
  sh cli/install.sh
```

The installer scripts are served over HTTPS by `playbridge.app`; release binaries
are independently verified against the checksum advertised by the update service.
Manual archives for every supported platform remain available from
[GitHub Releases](https://github.com/playbridgeapp/playbridge/releases).

Receiver mode requires [`mpv`](https://mpv.io/) to be installed and available on
`PATH`. Its TLS/WSS, pairing, authentication, limits, queue commands, and status
transport use the same `playbridge-cast-receiver` crate as Flutter Desktop;
only the external-mpv playback adapter remains CLI-specific.

## Examples

```sh
playbridge
playbridge discover
playbridge send video.mp4
playbridge send video.mp4 --json
playbridge send video.mp4 --json --skip-history
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
Machine-readable commands remain available for scripts and agents. `discover
--json` lists receivers. `send <file|URL> --json` casts to the preferred
receiver (saved with `P` in the dashboard), prints newline-delimited JSON events, and waits
until Ctrl+C so a local-file proxy stays up. If that receiver is unreachable,
the command discovers LAN devices and either prompts (TTY) or returns
`"error": "preferred_unreachable"` with a `receivers` list. Pass `--device`
to select one. If a name or address matches multiple protocol endpoints, the
CLI returns `ambiguous_device`; pass the protocol-qualified `id` from discovery.
Unpaired PlayBridge receivers prompt for the SAS code, or
accept `--pair-code`. While a JSON send is running, `status --json` reports
playback and `control pause|play|toggle|stop|seek|volume|mute|loop|speed|audio_boost` drives
the receiver without the dashboard. `playbridge mcp` is a stdio MCP server
for agents (discovery, rich media/playlist sends, pairing, status, playback,
queue, browser, and remote control). Every send returns
a `session_id`; pass it to pairing, status, and control calls so concurrent
agents cannot affect each other's casts:

```toml
[mcp_servers.playbridge]
command = "playbridge"
args = ["mcp"]
```

PlayBridge receivers can exclude a cast from playback history. Use
`--skip-history` for one cast or `--save-history` to explicitly retain it. Set
the default for CLI and MCP casts with:

```sh
playbridge config skip-history on
playbridge config skip-history off
playbridge config skip-history
```

The MCP `send` tool accepts an optional `skip_history` boolean; when omitted it
uses this saved default. Other receiver protocols ignore this PlayBridge-only
history preference.

For simple casts, pass `target`. For Android-sender parity, pass `items` with
the PlayBridge media fields, including request `headers`, `contentType`,
`subtitleResources`, language/quality preferences, `visualMetadata`,
`startPositionMs`, `mediaKind`, and `displayDurationMs`. Header values are kept
out of command arguments, logs, and MCP results. A `Referer` is an ordinary
entry in `headers`:

```json
{
  "items": [{
    "url": "https://cdn.example/video.m3u8",
    "title": "Episode 1",
    "headers": {
      "Referer": "https://example.com/",
      "User-Agent": "Mozilla/5.0"
    },
    "startPositionMs": 120000
  }],
  "device": "playbridge:receiver-uuid"
}
```

Use `queue_add`, `playlist_jump`, `browser`, `browser_control`, and `remote`
with the returned `session_id` for PlayBridge-only receiver features. For
receiver-owned playback started by another app or script, use device-centric
`get_state`, `queue_add`, `queue_remove`, `queue_move`, `queue_clear`, and
`playlist_jump`. Queue entries have stable `itemId` values, while `playbackId`
can be passed as `if_playback_id` to prevent stale automation from changing a
replacement cast. Device calls connect, authenticate, wait for the receiver's
correlated command result, and detach without stopping playback. `queue_add`
accepts up to 50 items per call. Do not pass both `device` and `session_id`.
`list_paired` returns saved UUID/name metadata without credentials; for legacy
records that predate saved names, it briefly discovers reachable PlayBridge
receivers and fills names in that response by exact UUID without rewriting the
credential files.

Interactive dashboard workflows still require a terminal.

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

[cast]
skip_history = false

[keys]
down = ["down", "j"]
up = ["up", "k"]
palette = ["ctrl+p", ":"]

[theme]
accent = "#43d3ee"
selection = "blue"
```

Run `playbridge --help` for all commands and options.

The dashboard checks `playbridge.app` for updates in the background. An available
version appears as a header badge and in Settings, where it can be reviewed and
installed without leaving the TUI. Downloads are verified before a staged helper
replaces the executable, keeps a rollback copy, and relaunches the dashboard.
Installation is disabled while casting or hosting a receiver; stop that work
first. Successful checks are cached for 24 hours and failures for one hour. Set
`PLAYBRIDGE_NO_UPDATE_CHECK=1` to disable checks. Unsupported or read-only install
locations show a manual install command instead of attempting replacement.

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

## Docs

- Code quality review: [`docs/code-quality-review.md`](docs/code-quality-review.md)
