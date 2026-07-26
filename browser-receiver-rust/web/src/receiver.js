import Hls from 'hls.js';
import { MediaPlayer as DashMediaPlayer, supportsMediaSource as dashSupportsMediaSource } from 'dashjs';

(function () {
  var player = document.getElementById('player');
  var playerWrap = document.getElementById('player-wrap');
  var setup = document.getElementById('setup');
  var code = document.getElementById('code');
  var instructions = document.getElementById('instructions');
  var deviceName = document.getElementById('device-name');
  var status = document.getElementById('status');
  var playOverlay = document.getElementById('play-overlay');
  var socket = null;
  var hls = null;
  var dash = null;
  var currentTitle = null;
  var lastStatusAt = 0;
  var mediaActive = false;
  var statusHideTimer = null;
  var lastPlaybackStatusKey = null;
  /** Bumps on every load/teardown so late media events ignore stale work. */
  var loadGeneration = 0;

  function receiverId() {
    var key = 'playbridge.browser.receiverId';
    var value = localStorage.getItem(key);
    if (!value) {
      value = 'browser-' + randomHex(16);
      localStorage.setItem(key, value);
    }
    return value;
  }

  function receiverName() {
    var key = 'playbridge.browser.name';
    var value = localStorage.getItem(key);
    if (!value) {
      value = (navigator.platform || 'Web') + ' Browser';
      localStorage.setItem(key, value);
    }
    return value;
  }

  function randomHex(length) {
    var bytes = new Uint8Array(length);
    if (window.crypto && window.crypto.getRandomValues) {
      window.crypto.getRandomValues(bytes);
    } else {
      for (var i = 0; i < bytes.length; i++) bytes[i] = Math.floor(Math.random() * 256);
    }
    return Array.prototype.map.call(bytes, function (value) {
      var hex = value.toString(16);
      return hex.length < 2 ? '0' + hex : hex;
    }).join('');
  }

  function send(frame) {
    if (socket && socket.readyState === WebSocket.OPEN) {
      socket.send(JSON.stringify(frame));
    }
  }

  function connect() {
    var scheme = location.protocol === 'https:' ? 'wss:' : 'ws:';
    socket = new WebSocket(scheme + '//' + location.host + '/v1/browser/ws');
    socket.onopen = function () {
      setStatus('Connected — waiting for approval');
      send({
        type: 'hello',
        protocolVersion: 1,
        receiverId: receiverId(),
        name: receiverName()
      });
      sendCapabilities();
    };
    socket.onmessage = function (event) {
      var frame;
      try { frame = JSON.parse(event.data); } catch (_) { return; }
      handleFrame(frame);
    };
    socket.onclose = function () {
      teardownPlayer();
      setup.style.display = 'block';
      playerWrap.style.display = 'none';
      instructions.textContent =
        'Disconnected from PlayBridge. Reload this page after the host is running again.';
      code.textContent = '';
      setStatus('Disconnected', true);
    };
    socket.onerror = function () {
      setStatus('Could not connect to PlayBridge', true);
    };
  }

  function sendCapabilities() {
    var dashJsSupported = supportsDash();
    var mimeTypes = [
      'video/mp4',
      'video/webm',
      'audio/mpeg',
      'audio/mp4',
      'application/vnd.apple.mpegurl'
    ].filter(function (type) { return player.canPlayType(type) !== ''; });
    if (dashJsSupported) mimeTypes.push('application/dash+xml');
    send({
      type: 'capabilities',
      capabilities: {
        nativeHls: supportsNativeHls(),
        mediaSource: typeof window.MediaSource !== 'undefined',
        hlsJs: Hls.isSupported(),
        dashJs: dashJsSupported,
        webVtt: player.canPlayType('text/vtt') !== 'no',
        volumeControl: true,
        mimeTypes: mimeTypes
      }
    });
  }

  function supportsNativeHls() {
    return player.canPlayType('application/vnd.apple.mpegurl') !== '';
  }

  function supportsDash() {
    return typeof DashMediaPlayer === 'function' &&
      typeof dashSupportsMediaSource === 'function' &&
      dashSupportsMediaSource();
  }

  function errorMessage(error, fallback) {
    if (!error) return fallback;
    if (typeof error === 'string') return error;
    if (typeof error.message === 'string' && error.message) return error.message;
    if (error.error) return errorMessage(error.error, fallback);
    if (error.event) return errorMessage(error.event, fallback);
    if (typeof error.type === 'string' && error.type) return error.type;
    return fallback;
  }

  function reportPlaybackError(requestId, error, fallback) {
    var message = errorMessage(error, fallback || 'Playback error');
    showStatus(message, true);
    send({ type: 'error', requestId: requestId || undefined, message: message });
  }

  function handleFrame(frame) {
    switch (frame.type) {
      case 'pairing_required':
        instructions.textContent = 'Enter this code in PlayBridge';
        code.textContent = frame.code;
        deviceName.textContent = receiverName();
        break;
      case 'pairing_approved':
        showReadyScreen();
        break;
      case 'pairing_denied':
        instructions.textContent = frame.reason || 'Pairing failed';
        code.textContent = '';
        setStatus('Pairing failed', true);
        break;
      case 'disconnect':
        teardownPlayer();
        setup.style.display = 'block';
        playerWrap.style.display = 'none';
        instructions.textContent = frame.reason || 'Disconnected by PlayBridge';
        code.textContent = '';
        setStatus('Disconnected', true);
        if (socket && socket.readyState === WebSocket.OPEN) {
          socket.close();
        }
        break;
      case 'load':
        loadMedia(frame.requestId, frame.media);
        break;
      case 'command':
        runCommand(frame);
        break;
      case 'ping':
        send({ type: 'pong', requestId: frame.requestId });
        break;
    }
  }

  function teardownPlayer() {
    // Invalidate in-flight load callbacks before pause/load so their media
    // events cannot report a trailing state after stop or a newer cast.
    loadGeneration += 1;
    mediaActive = false;
    if (hls) {
      try { hls.destroy(); } catch (_) {}
      hls = null;
    }
    if (dash) {
      try { dash.destroy(); } catch (_) {}
      dash = null;
    }
    try {
      player.pause();
    } catch (_) {}
    player.removeAttribute('src');
    player.removeAttribute('poster');
    while (player.firstChild) player.removeChild(player.firstChild);
    try {
      player.load();
    } catch (_) {}
    playOverlay.style.display = 'none';
  }

  function showReadyScreen() {
    teardownPlayer();
    currentTitle = null;
    setup.style.display = 'block';
    playerWrap.style.display = 'none';
    instructions.textContent = 'Connected. Choose media in PlayBridge.';
    code.textContent = '';
    deviceName.textContent = receiverName();
    setStatus('Ready');
  }

  function pathFromUrl(url) {
    try {
      var parsed = new URL(url, location.href);
      return (parsed.pathname || '').toLowerCase();
    } catch (_) {
      return String(url || '').split('?')[0].split('#')[0].toLowerCase();
    }
  }

  function detectStreamKind(url, contentType) {
    var type = (contentType || '').toLowerCase();
    var path = pathFromUrl(url);
    if (
      type.indexOf('mpegurl') >= 0 ||
      type.indexOf('x-mpegurl') >= 0 ||
      /\.m3u8$/i.test(path) ||
      /[?&](format|type|ext)=m3u8\b/i.test(url)
    ) {
      return 'hls';
    }
    if (
      type.indexOf('dash') >= 0 ||
      type.indexOf('mpd') >= 0 ||
      /\.mpd$/i.test(path)
    ) {
      return 'dash';
    }
    return 'progressive';
  }

  function loadMedia(requestId, media) {
    teardownPlayer();
    var generation = loadGeneration;
    mediaActive = true;
    currentTitle = media.title || null;
    if (media.posterUrl) player.poster = media.posterUrl;
    if (media.subtitleUrl) {
      var track = document.createElement('track');
      track.kind = 'subtitles';
      track.label = 'Subtitles';
      track.srclang = 'und';
      track.src = media.subtitleUrl;
      track.default = true;
      player.appendChild(track);
    }
    setup.style.display = 'none';
    playerWrap.style.display = 'block';

    var url = media.url;
    var kind = detectStreamKind(url, media.contentType);
    var nativeSource = true;

    if (kind === 'hls') {
      // Prefer Safari/native HLS when the engine can play it; hls.js elsewhere.
      if (supportsNativeHls()) {
        nativeSource = true;
        player.src = url;
      } else if (Hls.isSupported()) {
        nativeSource = false;
        hls = new Hls({
          enableWorker: true,
          lowLatencyMode: false,
          // Avoid noisy retries that leave Desktop thinking we are still casting.
          manifestLoadingMaxRetry: 2,
          levelLoadingMaxRetry: 2,
          fragLoadingMaxRetry: 3
        });
        hls.on(Hls.Events.ERROR, function (_event, data) {
          if (generation !== loadGeneration || !mediaActive) return;
          if (!data) return;
          if (data.fatal) {
            reportPlaybackError(
              requestId,
              data.details || data.type,
              'HLS playback error'
            );
            try { hls.destroy(); } catch (_) {}
            hls = null;
          }
        });
        hls.loadSource(url);
        hls.attachMedia(player);
      } else {
        reportPlaybackError(
          requestId,
          null,
          'This browser cannot play HLS streams'
        );
        return;
      }
    } else if (kind === 'dash') {
      if (!supportsDash()) {
        reportPlaybackError(
          requestId,
          null,
          'DASH playback requires Media Source support in this browser'
        );
        return;
      }
      nativeSource = false;
      try {
        dash = DashMediaPlayer().create();
        dash.on(DashMediaPlayer.events.ERROR, function (event) {
          if (generation !== loadGeneration || !mediaActive) return;
          reportPlaybackError(requestId, event, 'DASH playback error');
        });
        dash.initialize(player, url, false);
      } catch (error) {
        if (dash) {
          try { dash.destroy(); } catch (_) {}
          dash = null;
        }
        reportPlaybackError(requestId, error, 'Could not start DASH playback');
        return;
      }
    } else {
      player.src = url;
    }

    function onLoadedMetadata() {
      player.removeEventListener('loadedmetadata', onLoadedMetadata);
      if (generation !== loadGeneration || !mediaActive) return;
      if (media.startPositionMs) {
        try {
          player.currentTime = media.startPositionMs / 1000;
        } catch (_) {}
      }
      send({ type: 'ready', requestId: requestId });
      tryPlay(requestId);
    }
    player.addEventListener('loadedmetadata', onLoadedMetadata);
    if (nativeSource) {
      try {
        player.load();
      } catch (error) {
        reportPlaybackError(requestId, error, 'Could not load media');
        return;
      }
    }
    sendStatus(requestId, 'buffering');
  }

  function tryPlay(requestId) {
    var promise = player.play();
    if (promise && promise.catch) {
      promise.catch(function () {
        if (!mediaActive) return;
        playOverlay.style.display = 'grid';
        sendStatus(requestId, 'autoplay_blocked');
      });
    }
  }

  function runCommand(frame) {
    switch (frame.action) {
      case 'play':
        tryPlay(frame.requestId);
        break;
      case 'pause':
        player.pause();
        break;
      case 'stop':
        sendStatus(frame.requestId, 'stopped');
        showReadyScreen();
        return;
      case 'seek':
        if (typeof frame.value === 'number') {
          try {
            player.currentTime = Math.max(0, frame.value / 1000);
          } catch (_) {}
        }
        break;
      case 'set_volume':
        if (typeof frame.value === 'number') {
          player.volume = Math.max(0, Math.min(1, frame.value));
        }
        break;
    }
    sendStatus(frame.requestId);
  }

  function state() {
    if (player.error) return 'error';
    if (player.ended) return 'ended';
    if (player.readyState < 3 && !player.paused) return 'buffering';
    return player.paused ? 'paused' : 'playing';
  }

  function sendStatus(requestId, forcedState) {
    var playbackState = forcedState || state();
    send({
      type: 'status',
      requestId: requestId || undefined,
      state: playbackState,
      positionMs: Math.max(0, Math.round((player.currentTime || 0) * 1000)),
      durationMs: isFinite(player.duration) ? Math.max(0, Math.round(player.duration * 1000)) : 0,
      volume: player.volume,
      muted: player.muted,
      title: currentTitle
    });
    if (mediaActive) setPlaybackStatus(playbackState);
  }

  function setPlaybackStatus(playbackState) {
    var statusKey = playbackState + '|' + (currentTitle || '');
    if (statusKey === lastPlaybackStatusKey) return;
    lastPlaybackStatusKey = statusKey;
    switch (playbackState) {
      case 'playing':
        showStatus(currentTitle ? 'Playing · ' + currentTitle : 'Playing', false, 3000);
        break;
      case 'paused':
        showStatus('Paused');
        break;
      case 'buffering':
        showStatus('Loading…');
        break;
      case 'autoplay_blocked':
        showStatus('Press to play');
        break;
      case 'seeking':
        showStatus('Seeking…');
        break;
      case 'error':
        showStatus('Playback error', true);
        break;
      default:
        showStatus(playbackState.charAt(0).toUpperCase() + playbackState.slice(1));
        break;
    }
  }

  function setStatus(message, error) {
    lastPlaybackStatusKey = null;
    showStatus(message, error);
  }

  function showStatus(message, error, hideAfterMs) {
    if (statusHideTimer) {
      window.clearTimeout(statusHideTimer);
      statusHideTimer = null;
    }
    status.textContent = message;
    status.classList.toggle('error', Boolean(error));
    status.classList.remove('hidden');
    if (hideAfterMs) {
      statusHideTimer = window.setTimeout(function () {
        status.classList.add('hidden');
        statusHideTimer = null;
      }, hideAfterMs);
    }
  }

  playOverlay.onclick = function () {
    playOverlay.style.display = 'none';
    tryPlay();
  };
  ['play', 'pause', 'waiting', 'seeking', 'seeked', 'volumechange'].forEach(function (name) {
    player.addEventListener(name, function () {
      if (mediaActive) sendStatus(null, name === 'waiting' ? 'buffering' : undefined);
    });
  });
  player.addEventListener('timeupdate', function () {
    if (!mediaActive) return;
    var now = Date.now();
    if (now - lastStatusAt >= 1000) {
      lastStatusAt = now;
      sendStatus();
    }
  });
  player.addEventListener('ended', function () {
    if (!mediaActive) return;
    sendStatus(null, 'ended');
    send({ type: 'ended' });
    showReadyScreen();
  });
  player.addEventListener('error', function () {
    if (!mediaActive) return;
    setPlaybackStatus('error');
    reportPlaybackError(null, player.error, 'Media error');
  });

  connect();
}());
