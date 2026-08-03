// Video.js + VHS adaptive playback with PlayBridge's lightweight custom chrome.
import videojs from 'video.js';
import { detectStreamKind, normalizeReceiverMedia, sourceTypeForKind } from './shared/media.js';
import { createLoadLifecycle, normalizePlaybackState } from './shared/lifecycle.js';
import { createReceiverPresentation, redactUrl } from './shared/presentation.js';

(function () {
  var playerWrap = document.getElementById('player-wrap');
  var setup = document.getElementById('setup');
  var code = document.getElementById('code');
  var instructions = document.getElementById('instructions');
  var deviceName = document.getElementById('device-name');
  var status = document.getElementById('status');
  var playOverlay = document.getElementById('play-overlay');
  /** @type {HTMLVideoElement|null} */
  var player = document.getElementById('player');
  var socket = null;
  var reconnectTimer = null;
  var reconnectAttempt = 0;
  var reconnectAllowed = true;
  /** @type {import('video.js').VideoJsPlayer|null} */
  var vjsPlayer = null;
  var currentTitle = null;
  var currentMedia = null;
  var currentStreamKind = null;
  var lastStatusAt = 0;
  var mediaActive = false;
  var statusHideTimer = null;
  var lastPlaybackStatusKey = null;
  /** Bumps on every load/teardown so late media events ignore stale work. */
  var loadLifecycle = createLoadLifecycle();
  var chromeHideTimer = null;
  var statsTimer = null;
  /** null = Auto ABR; otherwise playlist id/uri lock */
  var lockedQualityId = null;
  var playbackRate = 1;
  var seekingUi = false;
  var SPEEDS = [0.5, 0.75, 1, 1.25, 1.5, 2];

  var chrome = document.getElementById('chrome');
  var statsEl = document.getElementById('stats');
  var btnPlay = document.getElementById('btn-play');
  var btnMute = document.getElementById('btn-mute');
  var btnFs = document.getElementById('btn-fs');
  var btnQuality = document.getElementById('btn-quality');
  var btnCaptions = document.getElementById('btn-captions');
  var btnSpeed = document.getElementById('btn-speed');
  var menuQuality = document.getElementById('menu-quality');
  var menuCaptions = document.getElementById('menu-captions');
  var menuSpeed = document.getElementById('menu-speed');
  var seekEl = document.getElementById('seek');
  var timeLabel = document.getElementById('time-label');
  var liveBadge = document.getElementById('live-badge');
  var btnInfo = document.getElementById('btn-info');
  var infoOverlay = document.getElementById('info-overlay');
  var infoContent = document.getElementById('info-content');
  var infoClose = document.getElementById('info-close');
  var receiverPresentation = createReceiverPresentation(document, { mode: 'browser' });

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
      for (var i = 0; i < length; i++) bytes[i] = Math.floor(Math.random() * 256);
    }
    return Array.prototype.map
      .call(bytes, function (b) {
        var hex = b.toString(16);
        return hex.length < 2 ? '0' + hex : hex;
      })
      .join('');
  }

  function send(frame) {
    if (socket && socket.readyState === WebSocket.OPEN) {
      socket.send(JSON.stringify(frame));
    }
  }

  function scheduleReconnect() {
    if (!reconnectAllowed || reconnectTimer || navigator.onLine === false) return;
    var delay = Math.min(10000, 500 * Math.pow(2, Math.min(reconnectAttempt, 5)));
    reconnectAttempt += 1;
    reconnectTimer = window.setTimeout(function () {
      reconnectTimer = null;
      connect();
    }, delay);
  }

  function connect() {
    if (
      socket &&
      (socket.readyState === WebSocket.CONNECTING || socket.readyState === WebSocket.OPEN)
    ) {
      return;
    }
    if (reconnectTimer) {
      window.clearTimeout(reconnectTimer);
      reconnectTimer = null;
    }
    var proto = location.protocol === 'https:' ? 'wss:' : 'ws:';
    var nextSocket = new WebSocket(proto + '//' + location.host + '/v1/browser/ws');
    socket = nextSocket;
    nextSocket.onopen = function () {
      if (socket !== nextSocket) return;
      reconnectAttempt = 0;
      setStatus(mediaActive ? 'Reconnected to PlayBridge' : 'Connected — waiting for approval');
      send({
        type: 'hello',
        protocolVersion: 1,
        receiverId: receiverId(),
        name: receiverName()
      });
      sendCapabilities();
    };
    nextSocket.onmessage = function (event) {
      if (socket !== nextSocket) return;
      var frame;
      try {
        frame = JSON.parse(event.data);
      } catch (_) {
        return;
      }
      handleFrame(frame);
    };
    nextSocket.onclose = function () {
      if (socket !== nextSocket) return;
      socket = null;
      if (!mediaActive) {
        setup.style.display = 'block';
        hidePlayerStage();
        instructions.textContent =
          'Connection lost. Reconnecting automatically when the host is available.';
        code.textContent = '';
      }
      setStatus(reconnectAllowed ? 'Connection lost — reconnecting…' : 'Disconnected', true);
      scheduleReconnect();
    };
    nextSocket.onerror = function () {
      if (socket === nextSocket) setStatus('Could not connect — retrying…', true);
    };
  }

  function supportsVideoJs() {
    return typeof videojs === 'function';
  }

  function supportsNativeHls() {
    var el = mediaElement();
    return el && el.canPlayType('application/vnd.apple.mpegurl') !== '';
  }

  function sendCapabilities() {
    var vjsOk = supportsVideoJs();
    var el = mediaElement() || document.createElement('video');
    var mimeTypes = [
      'video/mp4',
      'video/webm',
      'audio/mpeg',
      'audio/mp4',
      'application/vnd.apple.mpegurl'
    ].filter(function (type) {
      return el.canPlayType(type) !== '';
    });
    if (vjsOk) mimeTypes.push('application/dash+xml');
    send({
      type: 'capabilities',
      capabilities: {
        nativeHls: supportsNativeHls(),
        mediaSource: typeof window.MediaSource !== 'undefined',
        // Protocol field names kept for compatibility.
        hlsJs: vjsOk || supportsNativeHls(),
        dashJs: vjsOk,
        webVtt: el.canPlayType('text/vtt') !== 'no',
        volumeControl: true,
        mimeTypes: mimeTypes
      }
    });
  }

  function handleFrame(frame) {
    switch (frame.type) {
      case 'pairing_required':
        setup.style.display = 'block';
        hidePlayerStage();
        code.textContent = frame.code || '';
        instructions.textContent =
          'Enter this code in PlayBridge to pair this browser.';
        deviceName.textContent = receiverName();
        setStatus('Waiting for approval');
        break;
      case 'pairing_approved':
        if (mediaActive) {
          setup.style.display = 'none';
          showPlayerStage();
          setStatus('Reconnected to PlayBridge', false, 3000);
          sendStatus();
        } else {
          showReadyScreen();
        }
        break;
      case 'pairing_denied':
        reconnectAllowed = false;
        setStatus('Pairing denied — reload to try again', true);
        break;
      case 'disconnect':
        reconnectAllowed = frame.reason === 'PlayBridge browser host stopped';
        teardownPlayer();
        setup.style.display = 'block';
        hidePlayerStage();
        instructions.textContent =
          frame.reason ||
          (reconnectAllowed
            ? 'Browser host stopped. Waiting for it to start again.'
            : 'Disconnected by PlayBridge.');
        code.textContent = '';
        if (socket) socket.close();
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

  function mediaElement() {
    if (vjsPlayer && typeof vjsPlayer.el === 'function') {
      var tech = vjsPlayer.el().querySelector('video');
      if (tech) return tech;
    }
    return document.getElementById('player');
  }

  /** Ensure a bare <video id="player"> exists inside #player-wrap (after dispose). */
  function ensureVideoElement() {
    var existing = document.getElementById('player');
    if (existing && existing.tagName === 'VIDEO' && !existing.classList.contains('vjs-tech')) {
      player = existing;
      return player;
    }
    // video.js dispose removes the original node; rebuild a clean video.
    if (existing) {
      try {
        existing.parentNode && existing.parentNode.removeChild(existing);
      } catch (_) {}
    }
    // Also remove leftover video-js wrappers.
    var leftovers = playerWrap.querySelectorAll('.video-js, video');
    for (var i = 0; i < leftovers.length; i++) {
      try {
        leftovers[i].parentNode.removeChild(leftovers[i]);
      } catch (_) {}
    }
    var video = document.createElement('video');
    video.id = 'player';
    video.controls = false;
    video.removeAttribute('controls');
    video.setAttribute('playsinline', '');
    video.setAttribute('webkit-playsinline', '');
    playerWrap.insertBefore(video, playOverlay);
    player = video;
    bindMediaListeners(video);
    return video;
  }

  function disposeVjs() {
    if (!vjsPlayer) return;
    try {
      vjsPlayer.dispose();
    } catch (_) {}
    vjsPlayer = null;
  }

  function teardownPlayer() {
    loadLifecycle.cancel();
    mediaActive = false;
    currentMedia = null;
    currentStreamKind = null;
    stopStatsLoop();
    closeAllMenus();
    playerWrap.classList.remove('show-chrome');
    lockedQualityId = null;
    disposeVjs();
    ensureVideoElement();
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
    receiverPresentation.showReady({ deviceName: receiverName() });
    setStatus('Ready');
  }

  /** Strip native/Video.js controls; our chrome owns the UI. */
  function disableNativeControls() {
    try {
      if (vjsPlayer) {
        vjsPlayer.controls(false);
        var tech = vjsPlayer.tech(true);
        var techEl = tech && typeof tech.el === 'function' ? tech.el() : null;
        if (techEl) {
          techEl.controls = false;
          techEl.removeAttribute('controls');
          techEl.setAttribute('playsinline', '');
          techEl.setAttribute('webkit-playsinline', '');
        }
        fillPlayerStage();
      }
      if (player) {
        player.controls = false;
        player.removeAttribute('controls');
        player.setAttribute('playsinline', '');
      }
    } catch (_) {}
  }

  /** Make Video.js wrapper + <video> cover the full #player-wrap viewport. */
  function fillPlayerStage() {
    try {
      if (vjsPlayer && typeof vjsPlayer.el === 'function') {
        var root = vjsPlayer.el();
        if (root) {
          root.style.width = '100%';
          root.style.height = '100%';
          root.style.position = 'absolute';
          root.style.inset = '0';
          root.style.maxWidth = '100vw';
          root.style.maxHeight = '100vh';
          root.style.padding = '0';
          root.style.margin = '0';
        }
        if (typeof vjsPlayer.fluid === 'function') vjsPlayer.fluid(false);
        if (typeof vjsPlayer.fill === 'function') vjsPlayer.fill(true);
        // Nudge layout after tech attaches.
        if (typeof vjsPlayer.trigger === 'function') {
          try {
            vjsPlayer.trigger('playerresize');
          } catch (_) {}
        }
      }
      var el = mediaElement();
      if (el) {
        el.style.width = '100%';
        el.style.height = '100%';
        el.style.objectFit = 'contain';
        el.style.position = 'absolute';
        el.style.inset = '0';
      }
    } catch (_) {}
  }

  function showPlayerStage() {
    playerWrap.classList.add('is-playing');
    playerWrap.style.display = 'flex';
    fillPlayerStage();
    startStatsLoop();
    showChrome();
    refreshChrome();
  }

  function hidePlayerStage() {
    playerWrap.classList.remove('is-playing');
    playerWrap.style.display = 'none';
  }

  function isLivePlayback(el) {
    if (!el) return false;
    // Live HLS reports duration Infinity / NaN; VOD is finite.
    return !(isFinite(el.duration) && el.duration > 0);
  }

  /** Seconds of media already buffered ahead of the playhead. */
  function bufferedAheadSeconds(el) {
    if (!el || !el.buffered || el.buffered.length === 0) return 0;
    var t = el.currentTime || 0;
    var i;
    for (i = 0; i < el.buffered.length; i++) {
      var start = el.buffered.start(i);
      var end = el.buffered.end(i);
      if (t + 0.25 >= start && t < end) return end - t;
      if (t < start) return Math.max(0, end - start);
    }
    var last = el.buffered.length - 1;
    return Math.max(0, el.buffered.end(last) - el.buffered.start(last));
  }

  /**
   * Via phone is slower (browser → phone → CDN). Hold play for a short prebuffer
   * so VHS has headroom before the playhead moves — reduces live rebuffer thrash.
   */
  var LIVE_MIN_BUFFER_SEC = 6;
  var LIVE_MAX_PREBUFFER_MS = 8000;

  function waitForLivePrebuffer(el, generation, requestId) {
    return new Promise(function (resolve) {
      var started = Date.now();
      sendStatus(requestId, 'buffering');
      showStatus('Buffering…');
      function tick() {
        if (!loadLifecycle.isCurrent(generation) || !mediaActive) {
          resolve(false);
          return;
        }
        var media = el || mediaElement();
        var ahead = bufferedAheadSeconds(media);
        var elapsed = Date.now() - started;
        if (ahead >= LIVE_MIN_BUFFER_SEC || elapsed >= LIVE_MAX_PREBUFFER_MS) {
          resolve(true);
          return;
        }
        window.setTimeout(tick, 150);
      }
      tick();
    });
  }

  /**
   * Disable VHS early-abort. Via phone throughput looks artificially low (double hop),
   * so earlyAbortWhenNeeded_ aborts high-bitrate segments mid-download, blacklists
   * every rendition, and freezes live on "Loading…".
   */
  function softenVhsAbrForProxy() {
    try {
      var tech = vjsPlayer && vjsPlayer.tech(true);
      var vhs = tech && tech.vhs;
      var mpc = vhs && vhs.masterPlaylistController_;
      if (!mpc) return;
      // Never early-abort main (or audio) segment downloads.
      if (mpc.mainSegmentLoader_ && mpc.mainSegmentLoader_.earlyAbortWhenNeeded_) {
        mpc.mainSegmentLoader_.earlyAbortWhenNeeded_ = function () {};
      }
      if (mpc.audioSegmentLoader_ && mpc.audioSegmentLoader_.earlyAbortWhenNeeded_) {
        mpc.audioSegmentLoader_.earlyAbortWhenNeeded_ = function () {};
      }
      // Soften "playlist no longer updating" thrash over Via phone (slow reloads
      // look stuck), but keep real dead-rendition recovery: require several
      // consecutive stuck detections without mediaSequence advancing.
      if (mpc.stuckAtPlaylistEnd_ && !mpc.__pbStuckWrapped) {
        mpc.__pbStuckWrapped = true;
        var originalStuck = mpc.stuckAtPlaylistEnd_.bind(mpc);
        var stuckHits = 0;
        var lastMediaSequence = null;
        var STUCK_HITS_BEFORE_BLACKLIST = 3;
        mpc.stuckAtPlaylistEnd_ = function (playlist) {
          var stuck = false;
          try {
            stuck = !!originalStuck(playlist);
          } catch (_) {
            stuck = false;
          }
          if (!stuck) {
            stuckHits = 0;
            return false;
          }
          try {
            var media = playlist;
            if (!media) {
              media =
                vhs.playlists && typeof vhs.playlists.media === 'function'
                  ? vhs.playlists.media()
                  : null;
            }
            var seq = media && media.mediaSequence;
            if (seq != null) {
              if (lastMediaSequence != null && seq !== lastMediaSequence) {
                stuckHits = 0;
              }
              lastMediaSequence = seq;
            }
          } catch (_) {}
          stuckHits += 1;
          // False positives from slow phone proxy: ignore first few hits.
          return stuckHits >= STUCK_HITS_BEFORE_BLACKLIST;
        };
      }
    } catch (_) {}
  }

  /**
   * Video.js + VHS for adaptive streams (same stack Livepush reports), with
   * native HTML5 controls only — same look as our pre-Video.js UI.
   */
  function loadWithVideoJs(requestId, url, kind, generation) {
    if (!supportsVideoJs()) {
      reportPlaybackError(requestId, null, 'This browser cannot play adaptive streams');
      return Promise.reject(new Error('video.js unsupported'));
    }
    ensureVideoElement();
    return new Promise(function (resolve, reject) {
      var settled = false;
      function done(err) {
        if (settled) return;
        settled = true;
        if (timeoutId) window.clearTimeout(timeoutId);
        if (err) reject(err);
        else resolve();
      }

      vjsPlayer = videojs(player, {
        // Custom chrome owns transport; keep textTrackDisplay for CC cues.
        controls: false,
        bigPlayButton: false,
        controlBar: false,
        errorDisplay: false,
        loadingSpinner: false,
        posterImage: false,
        textTrackDisplay: true,
        textTrackSettings: false,
        // Do not autoplay immediately — live prebuffers first (see waitForLivePrebuffer).
        autoplay: false,
        preload: 'auto',
        fluid: false,
        fill: true,
        liveui: true,
        html5: {
          vhs: {
            overrideNative: true,
            // Start on the lowest rendition then ramp (must keep default bandwidth
            // for this flag to take effect in VHS 2.x).
            enableLowInitialPlaylist: true,
            limitRenditionByPlayerDimensions: false,
            useBandwidthFromLocalStorage: false,
            experimentalBufferBasedABR: false,
            allowSeeksWithinUnsafeLiveWindow: true,
            liveRangeSafeTimeDelta: 30,
            handleManifestRedirects: true,
            // Do not trust Network Information API on TV browsers (often wrong).
            useNetworkInformationApi: false
          },
          nativeAudioTracks: false,
          nativeVideoTracks: false,
          // Emulated tracks so VHS WebVTT/CEA captions surface via player.textTracks().
          nativeTextTracks: false
        }
      });

      vjsPlayer.one('error', function () {
        if (!loadLifecycle.isCurrent(generation) || !mediaActive) return;
        var err = vjsPlayer.error();
        var msg =
          (err && (err.message || (err.code != null && 'Media error ' + err.code))) ||
          'Playback error';
        reportPlaybackError(requestId, err, msg);
        done(err || new Error(msg));
      });

      // Live often fires canplay/loadeddata before (or without) a useful
      // loadedmetadata duration — do not wait only on loadedmetadata.
      function onCanStart() {
        if (!loadLifecycle.isCurrent(generation) || !mediaActive) return;
        disableNativeControls();
        softenVhsAbrForProxy();
        done();
      }
      vjsPlayer.one('loadedmetadata', onCanStart);
      vjsPlayer.one('loadeddata', onCanStart);
      vjsPlayer.one('canplay', onCanStart);
      // Note: do not resolve on 'playing' — we deliberately delay play for live prebuffer.

      // If metadata never arrives (some live edges), still proceed to prebuffer/play.
      var timeoutId = window.setTimeout(function () {
        if (!loadLifecycle.isCurrent(generation) || !mediaActive) return;
        disableNativeControls();
        softenVhsAbrForProxy();
        done();
      }, 2500);

      vjsPlayer.ready(function () {
        disableNativeControls();
        try {
          vjsPlayer.src({
            src: url,
            type: sourceTypeForKind(kind, currentMedia && currentMedia.contentType)
          });
          // After src is set, VHS creates loaders — soften ABR ASAP.
          // Preload downloads segments while paused so prebuffer can fill.
          window.setTimeout(function () {
            if (loadLifecycle.isCurrent(generation) && mediaActive) softenVhsAbrForProxy();
          }, 0);
          window.setTimeout(function () {
            if (loadLifecycle.isCurrent(generation) && mediaActive) softenVhsAbrForProxy();
          }, 500);
        } catch (e) {
          done(e);
        }
      });
    });
  }

  function loadMedia(requestId, media) {
    teardownPlayer();
    var generation = loadLifecycle.current();
    mediaActive = true;
    try {
      var normalized = normalizeReceiverMedia(media, { baseUrl: location.href });
      media = Object.assign({}, media, {
        url: normalized.url,
        contentType: normalized.contentType,
        streamType: normalized.streamType,
        title: normalized.title,
        posterUrl: normalized.artwork[0] && normalized.artwork[0].url,
        subtitleUrl: normalized.subtitleTracks[0] && normalized.subtitleTracks[0].url,
        startPositionMs: normalized.startPosition * 1000
      });
    } catch (error) {
      mediaActive = false;
      reportPlaybackError(requestId, error, 'Invalid media');
      return;
    }
    currentTitle = media.title || null;
    ensureVideoElement();
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
    showPlayerStage();

    var url = media.url;
    var kind = detectStreamKind(url, media.contentType);
    currentMedia = media;
    currentStreamKind = kind;

    function afterReady() {
      if (!loadLifecycle.isCurrent(generation) || !mediaActive) return;
      var el = mediaElement();
      // Never seek live to startPositionMs (often 0) — that leaves the playhead
      // outside the live window and VHS buffers forever (spinner / Loading…).
      if (
        media.startPositionMs &&
        el &&
        !isLivePlayback(el) &&
        isFinite(el.duration) &&
        el.duration > 0
      ) {
        try {
          el.currentTime = media.startPositionMs / 1000;
        } catch (_) {}
      }
      send({ type: 'ready', requestId: requestId });
      setPlaybackSpeed(playbackRate);
      applyQualityLock();
      rebuildQualityMenu();
      rebuildCaptionsMenu();
      rebuildSpeedMenu();
      refreshChrome();
      // HLS subtitle playlists / in-band captions appear after playlist parse.
      window.setTimeout(function () {
        if (loadLifecycle.isCurrent(generation) && mediaActive) {
          rebuildCaptionsMenu();
          rebuildQualityMenu();
        }
      }, 1200);
      window.setTimeout(function () {
        if (loadLifecycle.isCurrent(generation) && mediaActive) {
          rebuildCaptionsMenu();
          rebuildQualityMenu();
        }
      }, 3500);

      // Live (or duration still unknown on first canplay): build ~6s of buffer
      // before play so Via phone's slower hop has headroom.
      var needsPrebuffer =
        kind === 'hls' || kind === 'dash'
          ? !el || isLivePlayback(el) || !isFinite(el.duration)
          : false;
      if (needsPrebuffer) {
        waitForLivePrebuffer(el, generation, requestId).then(function () {
          if (!loadLifecycle.isCurrent(generation) || !mediaActive) return;
          tryPlay(requestId);
        });
        return;
      }
      tryPlay(requestId);
    }

    if (kind === 'hls' || kind === 'dash') {
      sendStatus(requestId, 'buffering');
      loadWithVideoJs(requestId, url, kind, generation)
        .then(function () {
          afterReady();
        })
        .catch(function (error) {
          if (!loadLifecycle.isCurrent(generation) || !mediaActive) return;
          reportPlaybackError(requestId, error, 'Could not start adaptive playback');
        });
      return;
    }

    // Progressive containers only (mp4/webm/audio).
    player.addEventListener('loadedmetadata', function onMeta() {
      player.removeEventListener('loadedmetadata', onMeta);
      afterReady();
    });
    player.src = url;
    try {
      player.load();
    } catch (error) {
      reportPlaybackError(requestId, error, 'Could not load media');
      return;
    }
    sendStatus(requestId, 'buffering');
  }


  function formatTime(sec) {
    if (!isFinite(sec) || sec < 0) return '0:00';
    sec = Math.floor(sec);
    var h = Math.floor(sec / 3600);
    var m = Math.floor((sec % 3600) / 60);
    var s = sec % 60;
    var mm = h > 0 ? (m < 10 ? '0' + m : '' + m) : '' + m;
    var ss = s < 10 ? '0' + s : '' + s;
    return h > 0 ? h + ':' + mm + ':' + ss : mm + ':' + ss;
  }

  function getVhs() {
    try {
      var tech = vjsPlayer && vjsPlayer.tech(true);
      return tech && tech.vhs ? tech.vhs : null;
    } catch (_) {
      return null;
    }
  }

  function closeAllMenus() {
    [menuQuality, menuCaptions, menuSpeed].forEach(function (m) {
      if (m) m.classList.remove('open');
    });
    [btnQuality, btnCaptions, btnSpeed].forEach(function (b) {
      if (b) b.setAttribute('aria-expanded', 'false');
    });
  }

  function toggleMenu(menu, btn) {
    var open = menu && menu.classList.contains('open');
    closeAllMenus();
    if (!open && menu) {
      menu.classList.add('open');
      if (btn) btn.setAttribute('aria-expanded', 'true');
    }
  }

  function showChrome() {
    if (!mediaActive) return;
    playerWrap.classList.add('show-chrome');
    if (chromeHideTimer) window.clearTimeout(chromeHideTimer);
    chromeHideTimer = window.setTimeout(function () {
      chromeHideTimer = null;
      var el = mediaElement();
      if (el && !el.paused && !seekingUi) {
        playerWrap.classList.remove('show-chrome');
        closeAllMenus();
      }
    }, 3500);
  }

  function hideChromeSoon() {
    showChrome();
  }

  function updatePlayButton() {
    if (!btnPlay) return;
    var el = mediaElement();
    var paused = !el || el.paused;
    btnPlay.textContent = paused ? '▶' : '❚❚';
    btnPlay.title = paused ? 'Play' : 'Pause';
  }

  function updateMuteButton() {
    if (!btnMute) return;
    var el = mediaElement();
    var muted = !el || el.muted || el.volume === 0;
    btnMute.textContent = muted ? '🔇' : '🔊';
  }

  function updateSeekUi() {
    if (!seekEl || !timeLabel) return;
    var el = mediaElement();
    if (!el) {
      seekEl.disabled = true;
      timeLabel.textContent = '0:00';
      if (liveBadge) liveBadge.classList.remove('show');
      return;
    }
    var live = isLivePlayback(el);
    if (liveBadge) liveBadge.classList.toggle('show', live);
    if (live || !isFinite(el.duration) || el.duration <= 0) {
      seekEl.disabled = true;
      seekEl.value = '1000';
      timeLabel.textContent = live ? 'LIVE' : formatTime(el.currentTime || 0);
      return;
    }
    seekEl.disabled = false;
    if (!seekingUi) {
      var ratio = (el.currentTime || 0) / el.duration;
      seekEl.value = String(Math.max(0, Math.min(1000, Math.round(ratio * 1000))));
    }
    timeLabel.textContent =
      formatTime(el.currentTime || 0) + ' / ' + formatTime(el.duration);
  }

  function listRenditions() {
    var vhs = getVhs();
    if (!vhs || !vhs.playlists || !vhs.playlists.master) return [];
    var list = vhs.playlists.master.playlists || [];
    var out = [];
    for (var i = 0; i < list.length; i++) {
      var p = list[i];
      if (!p) continue;
      var attrs = p.attributes || {};
      var res = attrs.RESOLUTION;
      var bw = attrs.BANDWIDTH || 0;
      var height = res && res.height ? res.height : 0;
      var id = p.id || p.uri || String(i);
      var label = height ? height + 'p' : 'Rendition ' + (i + 1);
      if (bw) label += ' · ' + Math.round(bw / 1000) + ' kbps';
      out.push({ id: id, playlist: p, label: label, bandwidth: bw, height: height });
    }
    out.sort(function (a, b) {
      return b.height - a.height || b.bandwidth - a.bandwidth;
    });
    return out;
  }

  function currentRenditionLabel() {
    if (lockedQualityId == null) return 'Auto';
    var list = listRenditions();
    for (var i = 0; i < list.length; i++) {
      if (list[i].id === lockedQualityId) return list[i].label.split(' · ')[0];
    }
    return 'Auto';
  }

  function applyQualityLock() {
    var vhs = getVhs();
    if (!vhs) return;
    // Prefer representations() API when present (clean enable/disable).
    try {
      if (typeof vhs.representations === 'function') {
        var reps = vhs.representations() || [];
        if (reps.length) {
          for (var ri = 0; ri < reps.length; ri++) {
            var rep = reps[ri];
            if (!rep || typeof rep.enabled !== 'function') continue;
            if (lockedQualityId == null) {
              rep.enabled(true);
            } else {
              var rid = rep.id;
              if (rid == null && rep.playlist) {
                rid = rep.playlist.id || rep.playlist.uri;
              }
              rep.enabled(String(rid) === String(lockedQualityId));
            }
          }
          return;
        }
      }
    } catch (_) {}
    if (!vhs.playlists || !vhs.playlists.master) return;
    var playlists = vhs.playlists.master.playlists || [];
    var target = null;
    for (var i = 0; i < playlists.length; i++) {
      var p = playlists[i];
      var id = p.id || p.uri || String(i);
      if (lockedQualityId == null) {
        p.disabled = false;
      } else {
        var match = id === lockedQualityId;
        p.disabled = !match;
        if (match) target = p;
      }
    }
    if (target && typeof vhs.playlists.media === 'function') {
      try {
        vhs.playlists.media(target);
      } catch (_) {}
    }
  }

  function escapeHtml(s) {
    return String(s)
      .replace(/&/g, '&amp;')
      .replace(/</g, '&lt;')
      .replace(/>/g, '&gt;')
      .replace(/"/g, '&quot;');
  }

  function rebuildQualityMenu() {
    if (!menuQuality || !btnQuality) return;
    var list = listRenditions();
    var html = '';
    html +=
      '<button type="button" data-q="auto" class="' +
      (lockedQualityId == null ? 'selected' : '') +
      '">Auto</button>';
    for (var i = 0; i < list.length; i++) {
      var r = list[i];
      html +=
        '<button type="button" data-q="' +
        encodeURIComponent(r.id) +
        '" class="' +
        (lockedQualityId === r.id ? 'selected' : '') +
        '">' +
        escapeHtml(r.label) +
        '</button>';
    }
    if (!list.length) {
      var el = mediaElement();
      if (el && el.videoHeight) {
        html +=
          '<button type="button" disabled>' +
          el.videoHeight +
          'p (source)</button>';
      }
    }
    menuQuality.innerHTML = html;
    btnQuality.textContent = currentRenditionLabel();
    btnQuality.classList.toggle('active', lockedQualityId != null);
  }

  function listCaptionTracks() {
    var out = [];
    // Prefer Video.js tracks (VHS WebVTT / emulated text tracks).
    try {
      if (vjsPlayer && typeof vjsPlayer.textTracks === 'function') {
        var vjsTracks = vjsPlayer.textTracks();
        if (vjsTracks) {
          for (var i = 0; i < vjsTracks.length; i++) {
            var t = vjsTracks[i];
            if (!t) continue;
            if (t.kind === 'subtitles' || t.kind === 'captions') out.push(t);
          }
          if (out.length) return out;
        }
      }
    } catch (_) {}
    var el = mediaElement();
    if (!el || !el.textTracks) return out;
    for (var j = 0; j < el.textTracks.length; j++) {
      var nt = el.textTracks[j];
      if (nt && (nt.kind === 'subtitles' || nt.kind === 'captions')) out.push(nt);
    }
    return out;
  }

  function rebuildCaptionsMenu() {
    if (!menuCaptions) return;
    var tracks = listCaptionTracks();
    var html =
      '<button type="button" data-c="off">Off</button>';
    var anyOn = false;
    for (var i = 0; i < tracks.length; i++) {
      var t = tracks[i];
      var on = t.mode === 'showing';
      if (on) anyOn = true;
      var label = t.label || t.language || 'Track ' + (i + 1);
      html +=
        '<button type="button" data-c="' +
        i +
        '" class="' +
        (on ? 'selected' : '') +
        '">' +
        escapeHtml(label) +
        '</button>';
    }
    if (!tracks.length) {
      html += '<button type="button" disabled>No captions</button>';
    }
    menuCaptions.innerHTML = html;
    var offBtn = menuCaptions.querySelector('[data-c="off"]');
    if (offBtn) {
      if (!anyOn) offBtn.classList.add('selected');
      else offBtn.classList.remove('selected');
    }
    if (btnCaptions) {
      btnCaptions.classList.toggle('active', anyOn);
      btnCaptions.title = anyOn ? 'Captions on' : 'Captions';
    }
  }

  function setCaptionIndex(indexOrOff) {
    var tracks = listCaptionTracks();
    for (var i = 0; i < tracks.length; i++) {
      // hidden keeps the track loaded but not painted; disabled unloads cues.
      tracks[i].mode = indexOrOff === i ? 'showing' : 'disabled';
    }
    rebuildCaptionsMenu();
  }

  function rebuildSpeedMenu() {
    if (!menuSpeed || !btnSpeed) return;
    var html = '';
    for (var i = 0; i < SPEEDS.length; i++) {
      var r = SPEEDS[i];
      html +=
        '<button type="button" data-s="' +
        r +
        '" class="' +
        (playbackRate === r ? 'selected' : '') +
        '">' +
        r +
        '×</button>';
    }
    menuSpeed.innerHTML = html;
    btnSpeed.textContent = playbackRate + '×';
  }

  function setPlaybackSpeed(rate) {
    playbackRate = rate;
    var el = mediaElement();
    try {
      if (vjsPlayer && typeof vjsPlayer.playbackRate === 'function') {
        vjsPlayer.playbackRate(rate);
      } else if (el) {
        el.playbackRate = rate;
      }
    } catch (_) {}
    rebuildSpeedMenu();
  }

  function collectStatsLine() {
    var el = mediaElement();
    if (!el) return '';
    var parts = [];
    var w = el.videoWidth || 0;
    var h = el.videoHeight || 0;
    if (w && h) parts.push(w + '×' + h);
    var live = isLivePlayback(el);
    parts.push(live ? 'Live' : 'VOD');
    var renditions = listRenditions();
    var active = null;
    var vhs = getVhs();
    try {
      if (vhs && vhs.playlists && typeof vhs.playlists.media === 'function') {
        active = vhs.playlists.media();
      }
    } catch (_) {}
    if (active && active.attributes && active.attributes.BANDWIDTH) {
      parts.push((active.attributes.BANDWIDTH / 1e6).toFixed(2) + ' Mbps');
    } else if (lockedQualityId == null && renditions.length) {
      parts.push('Auto');
    }
    var buf = bufferedAheadSeconds(el);
    if (buf > 0) parts.push('Buf ' + buf.toFixed(1) + 's');
    try {
      if (typeof el.getVideoPlaybackQuality === 'function') {
        var q = el.getVideoPlaybackQuality();
        if (q && q.totalVideoFrames) {
          parts.push('Drop ' + (q.droppedVideoFrames || 0) + '/' + q.totalVideoFrames);
        }
      }
    } catch (_) {}
    return parts.join(' · ');
  }

  function inferredRoute(raw) {
    try {
      var parsed = new URL(raw, location.href);
      var host = parsed.hostname || '';
      if (
        host === 'localhost' ||
        host === '127.0.0.1' ||
        /^10\./.test(host) ||
        /^192\.168\./.test(host) ||
        /^172\.(1[6-9]|2\d|3[01])\./.test(host)
      ) {
        return 'Via phone';
      }
      return parsed.origin === location.origin ? 'Receiver host' : 'Via remote proxy / direct';
    } catch (_) {
      return 'Unknown';
    }
  }

  function activePlaylistUrl() {
    var vhs = getVhs();
    try {
      var active =
        vhs && vhs.playlists && typeof vhs.playlists.media === 'function'
          ? vhs.playlists.media()
          : null;
      return active && (active.resolvedUri || active.uri || active.id);
    } catch (_) {
      return null;
    }
  }

  function updateInfoOverlay() {
    if (!infoContent) return;
    var el = mediaElement();
    var sourceUrl = currentMedia && currentMedia.url;
    var activeUrl = activePlaylistUrl();
    var websocketState =
      socket && socket.readyState === WebSocket.OPEN
        ? 'Connected'
        : socket && socket.readyState === WebSocket.CONNECTING
          ? 'Connecting'
          : 'Disconnected';
    var lines = [
      'Title: ' + (currentTitle || '—'),
      'State: ' + state(),
      'Type: ' + (currentStreamKind || '—'),
      'Route: ' + inferredRoute(sourceUrl),
      'WebSocket: ' + websocketState,
      'Playback: ' + (collectStatsLine() || '—'),
      'Source URL: ' + redactUrl(sourceUrl),
      'Active playlist: ' + redactUrl(activeUrl),
      'Media element URL: ' + redactUrl(el && el.currentSrc),
      'Subtitle URL: ' + redactUrl(currentMedia && currentMedia.subtitleUrl)
    ];
    infoContent.textContent = lines.join('\n');
  }

  function setInfoOverlayVisible(visible) {
    if (!infoOverlay) return;
    infoOverlay.hidden = !visible;
    infoOverlay.setAttribute('aria-hidden', visible ? 'false' : 'true');
    if (btnInfo) btnInfo.classList.toggle('active', visible);
    if (visible) {
      updateInfoOverlay();
      showChrome();
    }
  }

  function toggleInfoOverlay() {
    setInfoOverlayVisible(Boolean(infoOverlay && infoOverlay.hidden));
  }

  function refreshChrome() {
    if (!mediaActive) return;
    updatePlayButton();
    updateMuteButton();
    updateSeekUi();
    if (statsEl) statsEl.textContent = collectStatsLine();
    if (btnQuality) btnQuality.textContent = currentRenditionLabel();
    if (infoOverlay && !infoOverlay.hidden) updateInfoOverlay();
  }

  function startStatsLoop() {
    stopStatsLoop();
    statsTimer = window.setInterval(function () {
      if (!mediaActive) return;
      refreshChrome();
      // Avoid thrashing open menus (innerHTML wipe closes focus / selection).
      var qualityOpen = menuQuality && menuQuality.classList.contains('open');
      var captionsOpen = menuCaptions && menuCaptions.classList.contains('open');
      if (!qualityOpen) rebuildQualityMenu();
      if (!captionsOpen) rebuildCaptionsMenu();
    }, 1000);
  }

  function stopStatsLoop() {
    if (statsTimer) {
      window.clearInterval(statsTimer);
      statsTimer = null;
    }
  }

  function wireChrome() {
    if (!chrome) return;

    playerWrap.addEventListener('mousemove', showChrome);
    playerWrap.addEventListener('touchstart', showChrome, { passive: true });
    playerWrap.addEventListener('click', function (e) {
      if (e.target === playOverlay) return;
      if (e.target && e.target.closest && e.target.closest('#chrome')) {
        showChrome();
        return;
      }
      // Tap video toggles chrome
      if (playerWrap.classList.contains('show-chrome')) {
        playerWrap.classList.remove('show-chrome');
        closeAllMenus();
      } else {
        showChrome();
      }
    });

    if (btnPlay) {
      btnPlay.addEventListener('click', function (e) {
        e.stopPropagation();
        var el = mediaElement();
        if (!el) return;
        if (el.paused) tryPlay();
        else if (vjsPlayer) vjsPlayer.pause();
        else el.pause();
        updatePlayButton();
        showChrome();
      });
    }
    if (btnMute) {
      btnMute.addEventListener('click', function (e) {
        e.stopPropagation();
        var el = mediaElement();
        if (!el) return;
        el.muted = !el.muted;
        if (vjsPlayer) vjsPlayer.muted(el.muted);
        updateMuteButton();
        showChrome();
      });
    }
    if (btnFs) {
      btnFs.addEventListener('click', function (e) {
        e.stopPropagation();
        try {
          if (!document.fullscreenElement) {
            var req =
              playerWrap.requestFullscreen ||
              playerWrap.webkitRequestFullscreen ||
              playerWrap.msRequestFullscreen;
            if (req) req.call(playerWrap);
          } else if (document.exitFullscreen) {
            document.exitFullscreen();
          }
        } catch (_) {}
        showChrome();
      });
    }
    if (btnInfo) {
      btnInfo.addEventListener('click', function (e) {
        e.stopPropagation();
        toggleInfoOverlay();
      });
    }
    if (seekEl) {
      seekEl.addEventListener('pointerdown', function () {
        seekingUi = true;
        showChrome();
      });
      seekEl.addEventListener('pointerup', function () {
        seekingUi = false;
        showChrome();
      });
      seekEl.addEventListener('input', function () {
        seekingUi = true;
        showChrome();
      });
      seekEl.addEventListener('change', function () {
        var el = mediaElement();
        if (!el || seekEl.disabled || !isFinite(el.duration) || el.duration <= 0) {
          seekingUi = false;
          return;
        }
        var ratio = Number(seekEl.value) / 1000;
        try {
          el.currentTime = Math.max(0, Math.min(el.duration, ratio * el.duration));
        } catch (_) {}
        seekingUi = false;
        refreshChrome();
      });
    }
    if (btnQuality) {
      btnQuality.addEventListener('click', function (e) {
        e.stopPropagation();
        rebuildQualityMenu();
        toggleMenu(menuQuality, btnQuality);
        showChrome();
      });
    }
    if (menuQuality) {
      menuQuality.addEventListener('click', function (e) {
        e.stopPropagation();
        var t = e.target;
        if (!t || t.tagName !== 'BUTTON' || t.disabled) return;
        var q = t.getAttribute('data-q');
        if (q === 'auto' || q == null) lockedQualityId = null;
        else lockedQualityId = decodeURIComponent(q);
        applyQualityLock();
        rebuildQualityMenu();
        closeAllMenus();
        showChrome();
      });
    }
    if (btnCaptions) {
      btnCaptions.addEventListener('click', function (e) {
        e.stopPropagation();
        rebuildCaptionsMenu();
        toggleMenu(menuCaptions, btnCaptions);
        showChrome();
      });
    }
    if (menuCaptions) {
      menuCaptions.addEventListener('click', function (e) {
        e.stopPropagation();
        var t = e.target;
        if (!t || t.tagName !== 'BUTTON' || t.disabled) return;
        var c = t.getAttribute('data-c');
        if (c === 'off') setCaptionIndex(-1);
        else setCaptionIndex(parseInt(c, 10));
        closeAllMenus();
        showChrome();
      });
    }
    if (btnSpeed) {
      btnSpeed.addEventListener('click', function (e) {
        e.stopPropagation();
        rebuildSpeedMenu();
        toggleMenu(menuSpeed, btnSpeed);
        showChrome();
      });
    }
    if (menuSpeed) {
      menuSpeed.addEventListener('click', function (e) {
        e.stopPropagation();
        var t = e.target;
        if (!t || t.tagName !== 'BUTTON') return;
        var s = parseFloat(t.getAttribute('data-s'));
        if (isFinite(s)) setPlaybackSpeed(s);
        closeAllMenus();
        showChrome();
      });
    }

    rebuildSpeedMenu();
    rebuildCaptionsMenu();
    rebuildQualityMenu();
  }

  document.addEventListener('keydown', function (event) {
    var target = event.target;
    var tag = target && target.tagName ? target.tagName.toLowerCase() : '';
    if (event.ctrlKey || event.metaKey || event.altKey || /^(input|select|textarea)$/.test(tag)) {
      return;
    }
    if (event.key === 'i' || event.key === 'I') {
      event.preventDefault();
      toggleInfoOverlay();
    } else if (event.key === 'Escape' && infoOverlay && !infoOverlay.hidden) {
      event.preventDefault();
      setInfoOverlayVisible(false);
    }
  });
  if (infoClose) {
    infoClose.addEventListener('click', function () {
      setInfoOverlayVisible(false);
    });
  }
  if (infoOverlay) {
    infoOverlay.addEventListener('click', function (event) {
      if (event.target === infoOverlay) setInfoOverlayVisible(false);
    });
  }
  window.addEventListener('online', function () {
    if (reconnectAllowed) connect();
  });
  document.addEventListener('visibilitychange', function () {
    if (!document.hidden && reconnectAllowed) connect();
  });

  function tryPlay(requestId) {
    playOverlay.style.display = 'none';
    var promise;
    if (vjsPlayer) {
      promise = vjsPlayer.play();
    } else if (player) {
      promise = player.play();
    }
    updatePlayButton();
    showChrome();
    if (promise && promise.catch) {
      promise.catch(function () {
        if (!mediaActive) return;
        playOverlay.style.display = 'grid';
        sendStatus(requestId, 'autoplay_blocked');
        showChrome();
      });
    }
  }

  function runCommand(frame) {
    var el = mediaElement();
    switch (frame.action) {
      case 'play':
        tryPlay(frame.requestId);
        break;
      case 'pause':
        if (vjsPlayer) vjsPlayer.pause();
        else if (el) el.pause();
        break;
      case 'stop':
        sendStatus(frame.requestId, 'stopped');
        showReadyScreen();
        return;
      case 'seek':
        if (typeof frame.value === 'number' && el) {
          try {
            el.currentTime = Math.max(0, frame.value / 1000);
          } catch (_) {}
        }
        break;
      case 'set_volume':
        if (typeof frame.value === 'number') {
          var vol = Math.max(0, Math.min(1, frame.value));
          if (vjsPlayer) vjsPlayer.volume(vol);
          else if (el) el.volume = vol;
        }
        break;
    }
    sendStatus(frame.requestId);
  }

  function state() {
    var el = mediaElement();
    if (!el) return 'stopped';
    if (el.error) return 'error';
    if (el.ended) return 'ended';
    if (el.readyState < 3 && !el.paused) return 'buffering';
    return normalizePlaybackState(el.paused ? 'paused' : 'playing');
  }

  function sendStatus(requestId, forcedState) {
    var el = mediaElement();
    var playbackState = forcedState || state();
    send({
      type: 'status',
      requestId: requestId || undefined,
      state: playbackState,
      positionMs: Math.max(0, Math.round(((el && el.currentTime) || 0) * 1000)),
      durationMs:
        el && isFinite(el.duration) ? Math.max(0, Math.round(el.duration * 1000)) : 0,
      volume: el ? el.volume : 1,
      muted: el ? el.muted : false,
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

  function errorMessage(error, fallback) {
    if (!error) return fallback || 'Playback error';
    if (typeof error === 'string') return error;
    if (error.message) return String(error.message);
    if (error.code != null) return 'Error ' + error.code;
    try {
      return JSON.stringify(error);
    } catch (_) {
      return fallback || 'Playback error';
    }
  }

  function reportPlaybackError(requestId, error, fallback) {
    var message = errorMessage(error, fallback || 'Playback error');
    showStatus(message, true);
    send({
      type: 'error',
      requestId: requestId || undefined,
      message: message
    });
  }

  function bindMediaListeners(el) {
    if (!el || el.__pbBound) return;
    el.__pbBound = true;
    ['play', 'pause', 'waiting', 'seeking', 'seeked', 'volumechange'].forEach(function (name) {
      el.addEventListener(name, function () {
        if (mediaActive) {
          sendStatus(null, name === 'waiting' ? 'buffering' : undefined);
          refreshChrome();
        }
      });
    });
    el.addEventListener('timeupdate', function () {
      if (!mediaActive) return;
      updateSeekUi();
      var now = Date.now();
      if (now - lastStatusAt >= 1000) {
        lastStatusAt = now;
        sendStatus();
        if (statsEl) statsEl.textContent = collectStatsLine();
      }
    });
    el.addEventListener('ended', function () {
      if (!mediaActive) return;
      sendStatus(null, 'ended');
      send({ type: 'ended' });
      showReadyScreen();
    });
    el.addEventListener('error', function () {
      if (!mediaActive) return;
      setPlaybackStatus('error');
      reportPlaybackError(null, el.error, 'Media error');
    });
  }

  playOverlay.onclick = function () {
    playOverlay.style.display = 'none';
    tryPlay();
  };

  // Keep Video.js transport chrome invisible; keep text-track display for captions.
  try {
    var layout = document.createElement('style');
    layout.textContent =
      '#player-wrap.is-playing{display:flex!important;align-items:center;justify-content:center}' +
      '#player-wrap .video-js,#player-wrap video{' +
      'position:absolute!important;inset:0!important;' +
      'width:100%!important;height:100%!important;' +
      'max-width:100vw!important;max-height:100vh!important;' +
      'margin:0!important;padding:0!important;background:#000}' +
      '#player-wrap .video-js .vjs-tech{position:absolute!important;inset:0!important;' +
      'width:100%!important;height:100%!important;object-fit:contain;background:#000}' +
      '#player-wrap .vjs-control-bar,#player-wrap .vjs-big-play-button,' +
      '#player-wrap .vjs-loading-spinner,#player-wrap .vjs-modal-dialog,' +
      '#player-wrap .vjs-hidden{display:none!important}' +
      /* Captions: sit above the video, leave room for our bottom chrome. */
      '#player-wrap .vjs-text-track-display{display:block!important;pointer-events:none;' +
      'position:absolute!important;inset:0 0 72px 0!important;z-index:5}' +
      '#player-wrap .vjs-text-track-display .vjs-text-track-cue{font-size:clamp(16px,2.6vw,28px)!important}';
    document.head.appendChild(layout);
  } catch (_) {}

  wireChrome();
  bindMediaListeners(player);
  connect();
})();
