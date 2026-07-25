# PlayBridge CLI Changelog

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
