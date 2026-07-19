# Android TV Player Process Refactor

## Decision

Ship the renderer refactor directly on its dedicated branch. The permanent player host replaces
the old engine-specific activity routing; there is no runtime feature flag. The branch remains the
rollback boundary until device stress testing is complete.

## Process model

| Process | Owner | Startup | Failure boundary |
|---|---|---|---|
| Main | `PlayerHostActivity`, controls, server, history | App/server startup | Normal app process |
| `:mpv` | `MpvRendererService`, libmpv, MediaCodec | Lightly prewarmed | Kill/rebind process |
| `:exo` | `ExoRendererService`, Media3, MediaCodec | Lightly prewarmed | Kill/rebind process |
| `:web` | `BrowserActivity`, System WebView | Lazy | Web process death |
| Main (future) | Image renderer hosted in player shell | Lazy | Promote to `:image` only if native decoders prove unsafe |

MPV and Exo prewarming binds only the service/Binder endpoint. Player engines and hardware
decoders remain lazy until a playback request. Bindings use waived process priority so Android can
reclaim idle renderer processes under memory pressure.

System WebView is isolated but intentionally not prewarmed because its idle memory cost is much
higher. Its process uses a unique WebView data-directory suffix and relays status/context events to
the main server process.

## Playback lifecycle

1. `PlayerLauncher` converts live casts, history replay, and pre-play launches into one canonical
   playlist intent targeting `PlayerHostActivity`.
2. The host owns the stable player shell, controls, playlist, history, and progress state. It
   creates a fresh `SurfaceView` for each cross-process renderer handoff because some vendor
   BufferQueues remain producer-connected briefly after a renderer is killed.
3. A monotonically increasing session ID is sent with every renderer command and callback.
4. Same-engine replacement intents reuse the warm service and surface.
5. Cross-engine switches release and terminate only the old renderer process, rotate the playback
   surface, then bind the warm fallback renderer behind the same host.
6. An 8-second first-frame watchdog kills an unresponsive renderer and tries the other engine once.
7. Stale request IDs and stale renderer callbacks are ignored.
8. A crashed/killed renderer process is lightly prewarmed again after a cooldown.

## Completed

- MPV and Exo isolated Binder services with oneway AIDL commands/callbacks.
- Stable host UI with native controls, playlists, tracks, subtitles, speed, scaling, looping,
  loudness enhancement, subtitle delay, progress/history, and phone status synchronization.
- Same-host MPV/Exo fallback and process-level recovery.
- Canonical live-cast, history, and pre-play routing through the host.
- In-place replacement intents without exposing the history screen between engines.
- MPV/Exo process prewarming without decoder allocation.
- System WebView isolation in `:web` with cross-process server events.
- Legacy Exo/MPV activities removed from the runtime manifest.

## Remaining validation and follow-up

- Stress test rapid MPV/Exo replacement, manual switching, stop/start, renderer process kills, and
  app background/foreground transitions on the target MediaTek/Hisense TV.
- Verify WebView browsing, fullscreen video, status updates, downloads, and renderer-process crash
  recovery in `:web`.
- Measure idle RSS with both renderer processes prewarmed; adjust waived binding/rebind cooldown if
  the TV is memory constrained.
- Add image payload routing and an in-host image renderer. Start in the main host because bitmap
  decode is cancellable and does not share MediaCodec/libmpv global state; isolate it later only if
  device testing finds native-decoder hangs or unacceptable memory retention.
- Delete the now-unregistered legacy activity source after the device stress matrix passes.
