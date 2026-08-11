# PlayBridge CLI — Code Quality Review

Status: engineering review backlog
Review date: 2026-08-11
Scope: `cli/` (`playbridge-cast-cli` v0.2.0) — ~8.2k LOC Rust + installers
Method: full-tree inspection plus specialist passes (core, send/receive, update/security, dashboard UI)

This document records correctness, security, and maintainability findings for the
CLI project. It is an engineering backlog, not a claim that every item is
presently exploitable. Update evidence paths when items are fixed, or remove
resolved items.

## Severity

| Level | Meaning |
| --- | --- |
| High | Reproducible blocked workflow, destructive UI behavior, or persistent session failure |
| Medium | Confirmed correctness/reliability gap with bounded impact, or material hardening inconsistency |
| Low | Dead code, structure, consistency, or limited-impact hardening |

## Verdict

Solid architecture and security baseline. Several **correctness bugs** in the
dashboard input path and cast disconnect handling should land before treating the
TUI as production-stable.

## Architecture snapshot

| Area | Role | Size | Notes |
| --- | --- | --- | --- |
| `src/ui/mod.rs` | Full-screen dashboard | ~3.3k | God module; owns terminal, overlays, async cast/receiver/update |
| `src/send.rs` | Cast + browser host | ~1.2k | Generation-scoped events; uses `stream-proxy-rust` for local files |
| `src/receive.rs` | Local WSS receiver + mpv | ~1.1k | Receiver actor is generation-safe; mpv stderr bypasses terminal ownership |
| `src/update.rs`, `src/update_installer.rs` | Self-update | ~0.8k | HTTPS + SHA-256 + path-safe extract |
| `install.sh` / `install.ps1` | Bootstrap installers | — | PowerShell is hardened; shell lags |
| `src/http_server.rs` | Orphan | ~50 | Not in `main.rs`; no `axum` dependency |

### What works well

- Dashboard-first product model is consistent: human flows go through the TUI;
  machine output stays on `discover --json*`, `config`, and `google-cast`.
- Cast events are generation-scoped; stale `Connected` / `Snapshot` events are
  ignored (covered by tests).
- Credentials: path sanitization, `0o600` / `0o700`, atomic temp write on Unix;
  pairing tokens are not forwarded in `ReceiverUiEvent::Paired`.
- Update path: HTTPS-only assets, SHA-256 verification, refresh-before-install,
  path traversal blocked in Rust extract; dashboard blocks install while cast or
  receiver work is active.
- URL redaction in the UI (`display_source`) and a matching test helper in send.
- Terminal restore via `Drop` on `TerminalSession`.

## High

### H1. Overlay keys lose to Remote / Receiver hotkeys

**Where:** `src/ui/mod.rs:1106-1158` — Remote/Receiver media hotkeys run
before overlay dispatch in `handle_key`.

Media hotkeys for `Section::Remote` / `Section::Receiver` execute before the
overlay match. Modal overlays that can coexist with those sections—Pairing,
Help, Palette, and Quit—therefore do not own the keyboard. Keys such as `1`–`4`,
`Space`, and `X` still become cast/receiver commands.

Concrete failures:

- Pairing code entry: digits `1`–`4` become `SetSpeed` and never reach
  `app.input` if the user navigates to Remote/Receiver while connection is in
  progress, before the pairing prompt appears.
- Quit/Help/Palette open on Remote during cast: `X` / `Space` still stop or
  toggle playback under the dialog.

**Fix:** Gate hotkeys on `app.overlay == Overlay::None`, or handle overlays
first.

**Tests to add:** `handle_key` with `Overlay::Pairing|Quit|Help` +
`section = Remote|Receiver` + active session; digits must fill input and Esc must
cancel the overlay, not control playback.

### H2. PlayBridge cast never detects socket close

**Where:** `src/send.rs:193-209,553-577` — `dashboard_poll` PlayBridge
branch and the outer poll loop that tracks `consecutive_poll_failures`.

```rust
while let Ok(Ok(Some(frame))) =
    tokio::time::timeout(Duration::from_millis(10), socket.receive()).await
{ /* Status only */ }
// falls through to Ok(())
```

`receive()` returning `Ok(None)` (peer closed) does not become `Err`. Ping only
runs every third tick (`heartbeat`); intervening successful polls reset
`consecutive_poll_failures` to `0`. After the receiver drops WSS, the cast loop
keeps emitting snapshots until the user hits Stop.

**Fix:** Treat `Ok(None)` / receive `Err` as disconnect immediately, or
accumulate failures across non-heartbeat ticks without resetting on an empty
successful drain.


## Medium

### M1. Fire-and-forget `try_send` for Stop / pairing

**Where:** `src/ui/mod.rs:636-663` — `DashboardAction::CastCommand` /
`ReceiverCommand` (command channels have capacity 16).

```rust
let _ = commands.try_send(command);
if stopping { app.notice(..., "Stopping cast…"); }
```

Under a full command queue (rapid remote keys + slow actor), `Stop`,
`SubmitPairingCode`, `CancelPairing`, and `StopHost` can be dropped while the UI
claims success.

**Fix:** Do not block the dashboard event loop on a full bounded channel. Inspect
`try_send` failures and show an accurate notice; deliver Stop/cancel through a
dedicated cancellation mechanism or reserved reliable control lane. Pairing
submissions likewise need reliable delivery with a user-visible failure path.

### M2. mpv stderr inherits into the dashboard TUI

**Where:** `src/receive.rs:153-157` — mpv spawn uses
`stderr(Stdio::inherit())`.

Dashboard mode correctly quiets `println!` / `eprintln!`, but mpv warnings still
write to the alternate screen and corrupt the UI.

**Fix:** Use `Stdio::null()` (or a log pipe) when dashboard/quiet mode is active.

### M3. Playlist `startIndex` loads the wrong item

**Where:** `src/receive.rs:772-798` — `ReceiverCommand::Playlist` handling.

`current_index` is clamped with `index.min(len - 1)`, but load uses
`queue.get(index).or_else(|| queue.first())`. An out-of-range `startIndex`
points the index at the last item while mpv plays the first. Next/Previous and
status then disagree with playback.

**Fix:** Load `queue[current_index]` (the clamped index), or reject an
out-of-range `startIndex`.

### M4. Playlist title is the receiver name, not the media

**Where:** `src/send.rs:1116-1130` — `send_playlist`; the Cast/Roku load
paths in `cast_to_target` use the same receiver-name title.

Browser cast uses `media_title(...)`. PlayBridge/Cast/Roku pass `device_name`.
Receiver UI and status frames then show the TV name as the playing title (and
overwrite the sender snapshot title).

**Fix:** Pass the media title derived from the source into load/playlist
payloads, matching browser cast.

### M5. Google Cast discovery aborts on first `DiscoveryEvent::Error`

**Where:** `src/google_cast.rs:232-259` — `discover_receiver`.

```rust
DiscoveryEvent::Error { message, .. } => return Err(message),
```

`main.rs` discover accumulates errors and continues. Core can emit `Error`
after/among `Found` events. Named-device lookup can fail even when the device was
(or would be) found.

**Fix:** Record the error, keep reading the stream; fail only if no match by end.
Dead `device_name.is_none()` fallback can be deleted — `parse_options` always
requires `--device` or `--address`.

### M6. Self-update does not smoke-test the staged binary

**Where:** `src/update_installer.rs:152-173,298-405` — prepare and handoff.

`install.ps1` runs the staged binary with `--version` before replacing the
installed executable. The Rust self-update path verifies the archive checksum,
extracts the expected regular-file entry, and copies executable permissions, but
does not execute the staged binary before the swap helper replaces the working
CLI. A correctly hashed but broken release can therefore replace a working
installation; the backup remains available, but recovery is not automatic if the
new process cannot start.

**Fix:** Run the staged binary with `--version` under a short timeout and verify
the reported target version before arming handoff.

### M7. Shell installer lacks explicit archive allowlisting and staged replacement

**Where:** `install.sh:93-94`.

The shell installer extracts the whole archive and writes directly to the final
path:

```sh
tar -xzf "$TMP_DIR/$ASSET" -C "$TMP_DIR/unpacked"
install -m 755 "$TMP_DIR/unpacked/playbridge" "$INSTALL_DIR/playbridge"
```

This is weaker than both `install.ps1` and the Rust updater: it does not
explicitly allowlist archive members, cap decompressed size, smoke-test the
binary, stage the replacement in the install directory, or preserve a rollback
copy. The supported macOS `tar` was experimentally confirmed to reject `..`
members, so this is a portability and reliability gap—not a demonstrated
high-severity path-traversal vulnerability. A publisher/update-channel compromise
would also already permit substitution of the installed binary.

**Fix:** Extract only the expected regular-file member, enforce a decompressed
size limit, stage beside the destination, run `--version`, and atomically rename
with rollback.

## Accepted design assumptions

- Update authenticity currently rests on HTTPS plus SHA-256 metadata from the
  same publisher/update channel. A detached signature would add a second trust
  anchor, but its absence is an architectural choice rather than a CLI code
  defect under the current project contract.
- `CancelUpdate` drops the in-flight prepare future. Cancellation can occur
  during the awaited download, before install-directory staging begins; the
  temporary directory is RAII-managed. After the download, staging and marker
  creation contain no await point, and an unconsumed `PreparedUpdate` removes its
  staged files and marker on drop. No leak was established. A cancellation test
  would still document this invariant.

## Low / maintainability

| Finding | Evidence |
| --- | --- |
| Orphan `http_server.rs` | Not `mod`’d; needs `axum` / `tower-http`, which are not in `Cargo.toml`. `send.rs` already uses `stream-proxy-rust`. Delete. |
| `ui/mod.rs` ~3.3k god module | Render / input / async orchestration / helpers in one file. Split: app state, input, render, update flow; keep `run_dashboard` thin. |
| Windows credential replace non-atomic | `credentials.rs` delete-then-rename can lose pairing on crash mid-replace. |
| `PreferredDevice::save` swallows `create_dir_all` | `preferred.rs`; credentials propagate the error. |
| Browser pairing Esc leaves `pairing_device` | BrowserPairing Esc vs Pairing Esc which clears it. |
| Pending-update marker trusts stored paths | `take_restart_notice` removes staged/backup paths deserialized from user-owned state. Same-user impact only; allowlist names under the executable directory as hardening. |
| `display_media_target` is `#[cfg(test)]` only | Real UI uses `display_source` (good); avoid long-term redaction duplication. |
| Debug receiver logs full URLs/headers | Under `debug_assertions` + non-quiet — OK per monorepo rules if never release-persisted. |

## Test coverage

| Module | Tests | Gap |
| --- | --- | --- |
| `main.rs` | 7 | Arg parsing solid |
| `ui/mod.rs` | 19 | Render/launch/generation; missing overlay-vs-hotkey and full/closed command-channel cases |
| `send.rs` | 3 | Validation/redaction only; no disconnect / pairing session tests |
| `receive.rs` | 4 | Millisecond conversion, handshake filter, mpv IPC; no playlist index test |
| `update*` | 6 | Manifest + extract path safety; no staged-binary smoke-test coverage |
| `credentials` | 2 | Path escape + permissions |
| `preferred` / `http_server` / `terminal` | 0 | — |
| `install.ps1` | has tests | `install.sh` lacks equivalent archive/rollback tests |

Roughly 50 unit tests, skewed to pure helpers and render smoke. Highest ROI tests
cover H1, H2, M1, and M3.

## Priority fix order

1. Overlay-before-hotkeys in `handle_key` (+ tests)
2. PlayBridge disconnect in `dashboard_poll`
3. Reliable Stop/pairing command delivery (not bare `try_send`)
4. mpv stderr null in dashboard mode
5. Playlist index + media title
6. Google Cast discovery error continuation
7. Shell installer allowlisting + staged replacement
8. Staged self-update smoke test
9. Delete `http_server.rs`; split `ui/mod.rs` only when changing those concerns

## Summary

The CLI is thoughtfully productized: the dashboard owns the terminal,
pairing/credentials are careful, and the Rust self-update path is better than
average. The weak spots are TUI input priority, session liveness for PlayBridge
casts, and shell installer parity with the hardened PowerShell/Rust paths. None
of those need a redesign — they are localized fixes with high user impact.
