# MPV background, foreground and HDR switching

MPV renders with `vo=gpu-next` into a `CAMetalLayer` (`MPVMetalLayer` in
`Player/MPVPlayerView.swift`). The earlier AVSampleBufferDisplayLayer surface,
and its flush-based recovery after `requiresFlushToResumeDecoding`, went away
with the switch to standard MPVKit.

## Background and return

- Entering the background sets `vid=no`, so mpv stops drawing to a surface
  the system may reclaim. Pause and audio state are untouched.
- Becoming active again sets `vid=auto`; mpv reselects the video track and
  presentation resumes without reloading the URL, resetting audio or seeking.
- mpv is created only after the view has non-zero bounds, and the Metal layer
  ignores drawable sizes of 1 or less, so the swapchain is never created at a
  placeholder size.
- Teardown keeps the layer alive until `mpv_terminate_destroy` returns.

## HDR display switching

The decoded colorimetry exists only after the first frame, well after
`FILE_LOADED`. The controller observes `video-params/gamma` and, once it is
known, sets `AVDisplayManager.preferredDisplayCriteria`: HDR10 for BT.2020/PQ,
HLG for BT.2020/HLG, and none (system default) for everything else. Reading
`video-params` at `FILE_LOADED` always produced SDR, so HDR files played in
SDR mode.

Debug builds log one line three seconds after each change:
`[MPV] video output=… source=<primaries>/<gamma> target=<primaries>/<gamma> display=<mode>`.

## Device test

1. Play HDR10 content: the log shows `display=hdr10` and the TV shows HDR.
   (Verified October 2026 on an Apple TV 4K, A15.)
2. Go Home and return during playback: picture and sound resume. (Verified.)
3. Pause before going Home; return: picture redraws and stays paused.
4. Play SDR after HDR: the display returns to SDR.
5. Exit playback while returning from Home; advance the queue; switch engines.
