# Native MPV foreground video recovery

The user confirmed the AudioUnit patch restored sound, then reported audio
without video after returning from the Apple TV Home Screen.

The native MPV surface is an AVSampleBufferDisplayLayer. Apple documents that
backgrounding can make its renderer fail with `requiresFlushToResumeDecoding`;
rendering cannot resume until the renderer is flushed. Previously the TV
controller did not observe foreground/background or required-flush events.

The controller now:

- Tracks actual background entry and flushes native video on return to active.
- Also observes the native renderer required-flush notification and recovers
  while active. Uses sampleBufferRenderer on tvOS 17+, legacy layer APIs earlier.
- Retains the layer/MPV core and lets subsequent decoded pixel buffers resume
  presentation. Does not reload the URL, reset audio, change tracks or unpause.
- For a paused, seekable file, issues a zero-relative exact seek after flush to
  redraw the paused position. Nonseekable streams are not forced to seek.
- Deduplicates pending flushes and rejects completions after backgrounding,
  item replacement or teardown. Host tests exercise the token state machine.
- No longer treats every viewWillDisappear as final dismissal; actual removal
  still destroys the core through SwiftUI dismantle or explicit dismissal.

MPV uses native AVFoundation video in both Debug and Release. The GPU
experiment has been removed. Physical-device recovery verification is pending.

## Device test

1. Play a known-audio MP4 with MPV. Go Home and return; verify moving picture
   and sound. Repeat after a longer stay on Home.
2. Pause before going Home. Return: picture should redraw and stay paused.
3. Repeat Home/return quickly, then exit playback while a return is in progress.
4. Advance the queue and switch engines; no old item should restart or seek.
5. Check representative HDR content and a nonseekable/live stream.

If video remains blank, share `[MPV video-recovery]` and renderer warnings
only, without URLs/headers. A successful host build and
flush completion are not proof of visible rendering on a physical Apple TV.
