// Playback failures leave the browser as fixed sentences: browser and library
// error text can carry URLs, so it never reaches senders.

export const PLAYBACK_FAILED = 'Playback failed in the browser.';
export const STARTUP_STALLED = "The video didn't start loading.";
export const PLAYBACK_STALLED = 'The video stopped loading.';
export const NOT_LOADED = "The video couldn't be loaded.";

// HTMLMediaElement MediaError codes, which Video.js errors share.
const MEDIA_ERROR_MESSAGES = {
  1: 'Playback was stopped before the video loaded.',
  2: "The browser couldn't reach the video server.",
  3: "The browser couldn't decode this video.",
  4: "The browser can't play this video format."
};

/**
 * A fixed sentence for [error]: its media error code if it has one, else [fallback].
 * Pass [loaded] false when the error arrived before any metadata: Chrome then reports a
 * failed or cut-off download as code 4 too, so "can't play this format" would mislead.
 */
export function playbackErrorMessage(error, fallback, { loaded = true } = {}) {
  const code = error && typeof error === 'object' ? error.code : null;
  if (code === 4 && !loaded) return NOT_LOADED;
  return MEDIA_ERROR_MESSAGES[code] || fallback || PLAYBACK_FAILED;
}

/** Seconds buffered ahead of [position] in a TimeRanges-like [buffered]. */
export function bufferedAhead(buffered, position) {
  if (!buffered) return 0;
  for (let i = 0; i < buffered.length; i++) {
    if (buffered.start(i) <= position + 0.25 && position <= buffered.end(i)) {
      return Math.max(0, buffered.end(i) - position);
    }
  }
  return 0;
}

/**
 * Reports a load that never makes progress: no position or buffered-ahead
 * growth for [startupMs] before playback starts, or [stallMs] once it has.
 * Time spent paused doesn't count, and a seek starts the window again. Before
 * metadata arrives ([readyState] 0) the element is paused only because the receiver
 * hasn't called play() yet, so that time does count.
 */
export function createStallWatchdog({ startupMs = 30000, stallMs = 45000, now = Date.now } = {}) {
  let phase = 'idle';
  let since = 0;
  let lastPosition = 0;
  let lastBuffered = 0;

  function restart(position, buffered) {
    since = now();
    lastPosition = position;
    lastBuffered = buffered;
  }

  return {
    start() {
      phase = 'startup';
      restart(0, 0);
    },
    stop() {
      phase = 'idle';
    },
    /** Returns STARTUP_STALLED or PLAYBACK_STALLED once when a deadline passes, else null. */
    observe({
      position = 0,
      buffered = 0,
      paused = false,
      seeking = false,
      ended = false,
      readyState = 4
    }) {
      if (phase === 'idle') return null;
      const waitingForMetadata = phase === 'startup' && readyState < 1;
      if ((paused && !waitingForMetadata) || ended || seeking) {
        restart(position, buffered);
        return null;
      }
      const played = position > lastPosition + 0.01;
      if (played || buffered > lastBuffered + 0.01) {
        if (played) phase = 'playing';
        restart(position, buffered);
        return null;
      }
      lastBuffered = Math.min(lastBuffered, buffered);
      const limit = phase === 'startup' ? startupMs : stallMs;
      if (now() - since < limit) return null;
      const message = phase === 'startup' ? STARTUP_STALLED : PLAYBACK_STALLED;
      phase = 'idle';
      return message;
    }
  };
}
