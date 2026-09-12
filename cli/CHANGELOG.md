# PlayBridge CLI Changelog

## 0.3.0 (2026-09-12)

- Fix MCP output schemas for strict SDK clients, reject ambiguous receiver names
  with protocol-qualified choices, preserve media filenames as playback titles,
  and provide dedicated MCP and Google Cast help.

- Add `--skip-history` and `--save-history` overrides for PlayBridge casts,
  MCP `send.skip_history`, and a persisted `config skip-history on|off` default.

- Add `playbridge send|cast <file|URL> --json` to cast to the preferred
  receiver without the dashboard. Prints newline-delimited JSON events, then waits for
  Ctrl+C so a local-file proxy stays up. If the preferred receiver is
  unreachable, discover LAN devices and prompt (TTY) or return
  `preferred_unreachable` with a `receivers` list for agents. `--device`
  selects a receiver by id, uuid, name, or address. Unpaired PlayBridge
  targets prompt for the SAS code or accept `--pair-code`. A successful
  JSON send is saved as the preferred receiver. An active JSON send exposes
  `playbridge status --json` and `playbridge control pause|play|toggle|stop|seek|volume|mute|speed`.
  `playbridge mcp` exposes discover, send, submit_pair_code, status, and control
  over MCP stdio for AI agents, with structured results, isolated session ids,
  and pairing calls that wait for the receiver's actual success or failure.

## 0.2.0 (2026-08-10)

- Make the full-screen dashboard the primary interface for casting, browser
  receiver hosting, discovery, receiver mode, and remote control.
- Add dashboard remote and receiver status/control views, including pairing and
  browser-host approval flows without leaving the dashboard.
- Add verified dashboard self-updates plus macOS/Linux and Windows installers
  that resolve releases through `playbridge.app` and verify SHA-256 checksums.
- Require an explicitly entered, out-of-band SAS pairing code for CLI receiver
  pairing, preventing automatic approval from the same connection.
- Improve terminal restoration and background-task reporting so cast and
  receiver output does not corrupt the dashboard.

## 0.1.1 (2026-07-25)

- Build self-contained Windows executable (`playbridge.exe`) with static MSVC C runtime linking.
- Split CLI shipping into release-build (draft GitHub Release on uprev) and publish (`draft=false`).
- See `docs/release.md` for the monorepo release model.

## 0.1.0 (2026-07-24)

- Add cross-platform Rust command-line client (`playbridge`).
- Add interactive TUI device picker with live discovery.
- Add Google Cast (Chromecast), Roku ECP, and DLNA sender controls.
- Add seekbar position control, status monitoring, and rescan options.
- Add embedded HTTP media server for local file streaming.
- Add `receive` mode for playing streams via libmpv.
