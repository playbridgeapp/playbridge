// CAF has no DNS-pinning API. Callback origins must be approved by the receiver
// deployment (never by LOAD customData) before any credentialed request is made.
export function createProgressReporter({ allowedOrigins = [], fetcher = globalThis.fetch, now = () => Date.now(), uuid = () => globalThis.crypto.randomUUID(), timers = globalThis } = {}) {
  let active = null;
  let queue = [];
  let sending = false;
  const origins = new Set(allowedOrigins);
  const validIdentity = (v) => v && ['movie', 'series'].includes(v.type) &&
    [v.contentId, v.videoId].every((s) => typeof s === 'string' && s.length > 0 && s.length <= 256) &&
    (v.type === 'movie' || [v.season, v.episode].every((n) => Number.isInteger(n) && n >= 0));
  const timestamp = () => new Date(now()).toISOString();
  async function drain() {
    if (sending) return;
    sending = true;
    try {
      while (queue.length) {
        const job = queue.shift();
        if (now() - job.created > 90000) continue;
        for (let attempt = 0; attempt < 3; attempt++) {
          const controller = new AbortController();
          const timeout = timers.setTimeout(() => controller.abort(), 8000);
          let retry = false;
          try {
            const response = await fetcher(job.url, {
              method: 'POST', redirect: 'error', credentials: 'omit', cache: 'no-store',
              headers: { Authorization: `Bearer ${job.token}`, 'Content-Type': 'application/json' },
              body: job.body, signal: controller.signal
            });
            retry = response.status === 429 || response.status >= 500;
          } catch { retry = true; }
          finally { timers.clearTimeout(timeout); }
          if (!retry) break;
          if (attempt < 2) await new Promise((resolve) => timers.setTimeout(resolve, 1000 * (2 ** attempt)));
        }
      }
    } finally { sending = false; }
  }
  function emit(event) {
    if (!active || active.durationMs <= 0) return;
    const body = JSON.stringify({ version: 1, eventId: uuid(), playbackId: active.playbackId,
      itemId: active.itemId, event, content: active.identity,
      positionMs: active.positionMs, durationMs: active.durationMs, occurredAt: timestamp() });
    if (queue.length < 32) {
      queue.push({ url: active.url, token: active.token, body, created: now() });
      active.lastSent = now();
      void drain();
    }
  }
  function finish(event = 'stopped') { emit(event); active = null; }
  return {
    start(webhook, identity, itemId) {
      finish();
      if (!webhook || !validIdentity(identity)) return false;
      try {
        const url = new URL(webhook.url);
        if (url.protocol !== 'https:' || url.username || url.password || url.hash || url.search || (url.port && url.port !== '443') || url.href.length > 2048 ||
          !origins.has(url.origin) || typeof webhook.bearerToken !== 'string' ||
          !/^[\x21-\x7e]{1,4096}$/.test(webhook.bearerToken)) return false;
        active = { url: url.href, token: webhook.bearerToken, identity: {
          type: identity.type, contentId: identity.contentId, videoId: identity.videoId,
          ...(identity.season != null ? { season: identity.season } : {}),
          ...(identity.episode != null ? { episode: identity.episode } : {})
        }, itemId: typeof itemId === 'string' ? itemId : identity.videoId, playbackId: uuid(),
        state: null, lastSent: null, positionMs: 0, durationMs: 0 };
        return true;
      } catch { return false; }
    },
    observe(state, positionMs, durationMs) {
      if (!active) return;
      if (Number.isFinite(positionMs) && positionMs >= 0 && Number.isFinite(durationMs) && durationMs > 0) {
        if (!(['ended', 'stopped'].includes(state) && positionMs === 0 && active.positionMs > 0)) {
          active.positionMs = Math.min(Math.floor(positionMs), Math.floor(durationMs));
        }
        active.durationMs = Math.floor(durationMs);
      }
      if (state === 'ended' || state === 'stopped') { finish(state); return; }
      if (durationMs <= 0 || !Number.isFinite(durationMs)) return;
      if (state === 'playing') {
        if (active.state !== 'playing') emit('started');
        else if (active.lastSent == null || now() - active.lastSent >= 30000) emit('progress');
        active.state = state;
      } else if (state === 'paused' && active.state !== state) { emit('paused'); active.state = state; }
    },
    finish
  };
}
