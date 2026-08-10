# PlayBridge CLI Changelog

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
