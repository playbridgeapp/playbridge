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
playbridge discover
playbridge send video.mp4
playbridge receiver --name "My Computer"
playbridge browser video.mp4
```

Run `playbridge --help` for all commands and options.

When started interactively, the CLI checks for a newer `cli-v*` release at most
once per day and prints the install command when an update is available. Network
failures are silent and retried after one hour. Set `PLAYBRIDGE_NO_UPDATE_CHECK=1`
to disable the check.

## Build from source

From the repository root:

```sh
cargo build --release --locked -p playbridge-cast-cli
```
