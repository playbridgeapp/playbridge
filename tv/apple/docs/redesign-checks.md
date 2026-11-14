# Apple TV Redesign — Verification & Hardware Checks

## Covered in the tvOS Simulator and host test suite

- **Home & remote navigation (`HomeView`, `SettingsView`, `WatchTogetherView`, `AppFocusModel`)**
  - Home top bar displays **Watch Together** and **Settings** (paired phone management lives under **Settings -> Phones**).
  - Moving focus across Settings sidebar categories automatically updates the detail pane to match the focused category without requiring Select.
  - Pressing Right enters the detail pane's first control; pressing Left from the detail pane's left edge returns to the active sidebar category; Back closes any open picker or overlay first, then returns focus from the detail pane to the sidebar category, then exits Settings.
  - Works across both Siri Remote navigation and paired phone remote controls (`dpad_*`, `back`, `home`).

- **Watch Together lifecycle, replacement, and live detection (`TogetherModels`, `PlayerView`, `TvTogether`)**
  - Pairing code/QR flow (`pair.playbridge.app` or configurable pairing URL); authenticated phone LAN `watch_together_join` (`watch_together_joined`) message handling is implemented and unit-checked.
  - Replacing a party video reuses the active non-image playback session via `TogetherPlaybackPolicy.transition` with a monotonic request nonce and token-fenced `TogetherPlayerAttachment` so out-of-order `onAppear`/`onDisappear` events cannot drop the active party item or leave the room.
  - `TogetherLoadedPlanner.isMediaReady` requires `itemLoaded == true` and `generationMatches == true` before reporting readiness or arming the 2 s live fallback timer, preventing stale status frames on a reused player from falsely reporting on-demand videos as live; `lateDurationCorrection` updates duration if reported early.
  - `TogetherPlaybackPolicy.shouldSeekToLiveEdge` seeks on `go_live` only when the active item is loaded and lacks a finite positive duration, preventing finite on-demand streams from seeking to EOF.
  - Inside a Watch Together party, playback failure screens do not auto-close (`shouldAutoCloseFailure == false`, vs. 60 s outside a party), and natural end-of-video does not dismiss or leave the room; leaving the party occurs on explicit user exit while no replacement request is pending, or via **Leave party**.

- **Subtitles settings & phone Configure TV sync (`SubtitleStyleSettings`, `SubtitleFontLibrary`, `WebSocketServer`)**
  - **Settings -> Subtitles** shows a live preview and controls for Size (`Small`, `Medium`, `Large`, `Extra large`), Font (`Default`, `Sans`, `Serif`, `Monospace`, or an installed custom font), Background (`None`, `Semi-transparent black`, `Solid black`), Edge (`Outline`, `Drop shadow`, `None`), and **Use this style on pre-styled subtitles too** (`overrideStyled`).
  - Style changes persist and apply immediately across both `mpv` (including ASS overrides when enabled) and `AVPlayer` (`AVTextStyleRule` + sidecar caption overlay).
  - Connected phones with `subtitle_style_v1` and `subtitle_font_v1` can push style updates (`subtitle_style`), inspect/remove custom fonts (`subtitle_font_remove`), and upload custom fonts in chunks (`subtitle_font_begin`, `subtitle_font_chunk`, `subtitle_font_end`).
  - Custom font library limits (`SubtitleFontLimits`): max `10 MiB` per font, max `5` fonts, max `30 MiB` total library storage, and `192 KiB` upload chunks. Uploads verify SHA-256, parse sfnt `name` tables, and validate font data with CoreText (`CTFontManagerCreateFontDescriptorsFromData`) before registration.

- **mpv HTTP reconnect & freeze/EOF recovery (`PlaybackStallDetector`, `MPVPlayerView`)**
  - Configures FFmpeg `lavf` reconnect options on both `stream-lavf-o` and `demuxer-lavf-o` (`reconnect=1,reconnect_streamed=1,reconnect_on_network_error=1,reconnect_on_http_error="429,5xx",reconnect_delay_max=5,reconnect_max_retries=3`), intentionally omitting `reconnect_at_eof` so finite VOD streams complete normally. Each FFmpeg outage retries at most 3 times (`0 s + 1 s + 3 s = 4 s` backoff sleep) before failing.
  - `PlaybackFreezeRecovery` monitors silent stalls (frozen `time-pos`) and premature EOFs from truncated HTTP streams, reloading at the last good position (`15 s` stall reload delay, `3 s` EOF-freeze reload delay, at most `2` recoveries without `2 s` of forward progress, and a hard cap of `6` total reloads per video reset only by a new item or pressing **Try again**) before presenting a failure screen within `PlaybackStallDetector`'s `30 s` startup / `45 s` mid-play budgets.

## Real-Hardware Manual Checks

Checks on the tvOS Simulator do not cover hardware-only behavior. Run these on a physical Apple TV before shipping:

1. **Siri Remote touch scrubbing**
   - Pause playback on both `mpv` and `AVPlayer`, swipe on the Siri Remote trackpad to scrub along the seek strip, and confirm smooth preview/seek without accidental chapter jumps.
   - Confirm tap/click vs. swipe gestures on the two-tier overlay (seek strip vs. full controls) and auto-hide timers (3 s seek strip, 8 s full controls).

2. **Watch Together on real hardware**
   - Join a party from a phone scanning the QR code or entering the 6-character code (`pair.playbridge.app`), and by adding an already-paired TV over LAN (`watch_together_join` -> `watch_together_joined`).
   - Verify long-session sync drift, pause/play/seek forwarding from both the Siri Remote and a paired phone remote, and **Leave party** continuing local playback outside history/bookmarks on both `mpv` and `AVPlayer`.
   - Test a true live stream (non-finite duration) in a party to confirm `go_live` resumes at the live edge without showing seeking UI.

3. **Video, HDR, and audio output**
   - Verify 4K HDR/Dolby Vision display mode switching (`mpv` and `AVPlayer`), surround/passthrough audio, and foreground/background transitions.
   - Verify bookmark thumbnail capture on real GPU surfaces for both `mpv` and `AVPlayer`.

4. **TestFlight / Release archive**
   - Run `cast/build-apple-tvos.sh` and archive the Release build to confirm `PlayBridgeCastCore.xcframework` embeds and signs cleanly with `pb_together_*` symbols.
