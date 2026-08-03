const PLAYBACK_STATES = new Set([
  'idle', 'buffering', 'playing', 'paused', 'seeking', 'stopped', 'ended', 'error'
]);

export function createLoadLifecycle() {
  let generation = 0;
  return {
    begin() {
      generation += 1;
      return generation;
    },
    cancel() {
      generation += 1;
      return generation;
    },
    current() {
      return generation;
    },
    isCurrent(candidate) {
      return candidate === generation;
    }
  };
}

export function normalizePlaybackState(state) {
  const normalized = String(state || '').toLowerCase();
  if (normalized === 'loading' || normalized === 'waiting') return 'buffering';
  if (normalized === 'complete') return 'ended';
  return PLAYBACK_STATES.has(normalized) ? normalized : 'idle';
}

export function classifyPlaybackError(error) {
  const code = error && error.code;
  const message = String(error && (error.message || error.reason) || error || 'Playback error');
  if (/manifest|playlist/i.test(message)) return { category: 'manifest', code, message };
  if (/network|fetch|segment|http/i.test(message)) return { category: 'network', code, message };
  if (/decode|codec|format|media/i.test(message)) return { category: 'media', code, message };
  return { category: 'unknown', code, message };
}
