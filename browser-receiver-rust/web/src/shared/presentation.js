export function redactUrl(raw, baseUrl) {
  if (!raw) return '—';
  try {
    const parsed = new URL(raw, baseUrl || globalThis.location?.href || 'https://receiver.invalid/');
    const sensitive = /(^|[_-])(token|session|sig|signature|auth|authorization|key|policy|cookie|expires|credential|secret)($|[_-])/i;
    const pairs = [];
    parsed.searchParams.forEach((value, name) => {
      if (sensitive.test(name) || /^(hdnea|jwt)$/i.test(name)) {
        pairs.push(encodeURIComponent(name) + '=<redacted>');
      } else if (name.toLowerCase() === 'uri') {
        pairs.push(encodeURIComponent(name) + '=' + encodeURIComponent(redactUrl(value, baseUrl)));
      } else {
        pairs.push(encodeURIComponent(name) + '=' + encodeURIComponent(value));
      }
    });
    parsed.search = pairs.length ? '?' + pairs.join('&') : '';
    return parsed.href;
  } catch (_) {
    return String(raw);
  }
}

export function redactUrlsInText(value, baseUrl) {
  return String(value || '').replace(/https?:\/\/[^\s"'<>]+/gi, (url) => redactUrl(url, baseUrl));
}

export function createReceiverPresentation(document, options = {}) {
  const mode = options.mode || 'browser';
  const setup = document.getElementById('setup');
  const instructions = document.getElementById('instructions');
  const code = document.getElementById('code');
  const deviceName = document.getElementById('device-name');
  const playerWrap = document.getElementById('player-wrap');
  const mediaOverlay = document.getElementById('media-overlay');
  const mediaArtwork = document.getElementById('media-artwork');
  const mediaTitle = document.getElementById('media-title');
  const status = document.getElementById('status');
  document.body.dataset.receiverMode = mode;

  function showReady(details = {}) {
    if (setup) setup.style.display = 'block';
    if (playerWrap) playerWrap.style.display = 'none';
    if (instructions) {
      instructions.textContent = details.instructions ||
        (mode === 'cast' ? 'Ready to cast' : 'Connected. Choose media in PlayBridge.');
    }
    if (code) code.textContent = details.code || '';
    if (deviceName) deviceName.textContent = details.deviceName || '';
    if (mediaOverlay) mediaOverlay.hidden = true;
  }

  function showMedia(media, state) {
    if (setup) setup.style.display = 'none';
    if (playerWrap) playerWrap.style.display = mode === 'cast' ? 'block' : 'flex';
    if (mediaTitle) mediaTitle.textContent = media && media.title || '';
    if (mediaArtwork) {
      const artwork = media && media.artwork && media.artwork[0];
      mediaArtwork.hidden = !artwork;
      if (artwork) mediaArtwork.src = artwork.url;
    }
    if (mediaOverlay) mediaOverlay.hidden = state === 'playing';
    showStatus(state === 'buffering' ? 'Loading…' : (media && media.title) || 'Playing');
  }

  function showStatus(message, error) {
    if (!status) return;
    status.textContent = message;
    status.classList.toggle('error', Boolean(error));
    status.classList.remove('hidden');
  }

  return { mode, showReady, showMedia, showStatus };
}
