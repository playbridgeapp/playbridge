/*
 * PlayBridge WebView video controller.
 *
 * Injected at document start into EVERY frame (main + cross-origin iframes) via
 * WebViewCompat.addDocumentStartJavaScript, so it can reach videos that the main
 * frame's JS can't (same-origin policy). Each frame — INCLUDING the main frame —
 * talks to native over its own WebMessageListener channel: window.pbVideoChannel.
 *
 * Transport is auto-detected by channel presence (NOT by frame type):
 *   - window.pbVideoChannel present  -> per-frame message channel. Used for the main
 *                                       frame and every iframe alike, so there is a
 *                                       single unified pathway. Native picks which
 *                                       frame the D-pad drives via the per-frame score.
 *   - else window.PBVideoNative      -> legacy fallback for WebViews lacking the
 *                                       DOCUMENT_START_SCRIPT / WEB_MESSAGE_LISTENER
 *                                       features (main frame only; evaluateJavascript
 *                                       drives __pbvc, PBVideoNative.setActive reports
 *                                       activation).
 *
 * Native -> frame commands (channel, string): "toggle" | "seek,<s>" | "vol,<d>" |
 *   "rate,<r>" | "state". Frame -> native messages (channel, JSON):
 *   {type:"active",active,cov} on change, and {type:"result",kind,value} per command.
 *
 * Feedback is rendered NATIVELY by BrowserActivity (a DOM overlay would be hidden
 * behind the video surface in fullscreen), so this script renders no UI.
 */
(function () {
  'use strict';
  if (window.__pbvcInjected) return;
  window.__pbvcInjected = true;

  var clamp = function (v, lo, hi) { return Math.max(lo, Math.min(hi, v)); };

  // ── Video discovery (shadow-DOM aware, this frame only) ───────────────────
  function collectVideos(root, out) {
    var vids;
    try { vids = root.querySelectorAll('video'); } catch (e) { return; }
    for (var i = 0; i < vids.length; i++) out.push(vids[i]);
    var all = root.querySelectorAll('*');
    for (var j = 0; j < all.length; j++) {
      if (all[j].shadowRoot) collectVideos(all[j].shadowRoot, out);
    }
  }

  // Fraction of THIS frame's viewport that a video's visible rect covers (0..1).
  function coverage(v) {
    var r = v.getBoundingClientRect();
    if (r.width <= 0 || r.height <= 0) return 0;
    var vw = window.innerWidth || document.documentElement.clientWidth || 1;
    var vh = window.innerHeight || document.documentElement.clientHeight || 1;
    var ix = Math.max(0, Math.min(r.right, vw) - Math.max(r.left, 0));
    var iy = Math.max(0, Math.min(r.bottom, vh) - Math.max(r.top, 0));
    return (ix * iy) / (vw * vh);
  }

  function bestVideo() {
    var out = [];
    collectVideos(document, out);
    var best = null, bestCov = 0;
    for (var i = 0; i < out.length; i++) {
      var c = coverage(out[i]);
      if (c > bestCov) { bestCov = c; best = out[i]; }
    }
    return { v: best, cov: bestCov };
  }

  function find() { return bestVideo().v; }

  // A video is "control-active" when it dominates this frame's viewport, whether
  // playing or paused — the activation signal for the D-pad. Covers m.youtube.com's
  // in-page expand (no HTML5 fullscreen) and cross-origin iframe players alike.
  var COVERAGE_THRESHOLD = 0.6;

  // Absolute on-screen area of the dominating video in CSS px². Comparable ACROSS
  // frames (unlike coverage, which is relative to each frame's own viewport), so
  // native can pick the genuinely largest video among main frame + all iframes.
  // This alone defeats tiny ad iframes (small area => loses), so there's no minimum
  // gate — keeping the gate off avoids suppressing legitimately small embeds.
  function videoScore(cov) {
    var vw = window.innerWidth || document.documentElement.clientWidth || 1;
    var vh = window.innerHeight || document.documentElement.clientHeight || 1;
    return cov * vw * vh;
  }

  // ── Direct API (also used by the legacy evaluateJavascript fallback) ───────
  var api = {
    toggle: function () {
      var v = find();
      if (!v) return 'none';
      if (v.paused) { v.play(); } else { v.pause(); }
      return v.paused ? 'paused' : 'playing';
    },
    seek: function (delta) {
      var v = find();
      if (!v) return 'none';
      var d = Number(delta) || 0;
      if (isFinite(v.duration) && v.duration > 0) {
        v.currentTime = clamp(v.currentTime + d, 0, v.duration);
      } else {
        v.currentTime = Math.max(0, v.currentTime + d);
      }
      return v.currentTime + ',' + (isFinite(v.duration) ? v.duration : 0);
    },
    seekTo: function (seconds) {
      var v = find();
      if (!v) return 'none';
      var t = Number(seconds) || 0;
      v.currentTime = isFinite(v.duration) && v.duration > 0 ? clamp(t, 0, v.duration) : Math.max(0, t);
      return v.currentTime + ',' + (isFinite(v.duration) ? v.duration : 0);
    },
    volume: function (delta) {
      var v = find();
      if (!v) return 'none';
      v.muted = false;
      v.volume = clamp(v.volume + (Number(delta) || 0), 0, 1);
      return String(v.volume);
    },
    setRate: function (r) {
      var v = find();
      if (!v) return 'none';
      v.playbackRate = clamp(Number(r) || 1, 0.0625, 16);
      return String(v.playbackRate);
    },
    getRate: function () { var v = find(); return v ? String(v.playbackRate) : 'none'; },
    state: function () {
      var v = find();
      if (!v) return JSON.stringify({ hasVideo: false });
      return JSON.stringify({
        hasVideo: true, paused: !!v.paused, currentTime: v.currentTime || 0,
        duration: isFinite(v.duration) ? v.duration : 0, volume: v.volume, rate: v.playbackRate
      });
    }
  };
  window.__pbvc = api;

  // ── Command execution (shared by channel + fallback) ──────────────────────
  // Returns {kind, value} so native can render the right overlay.
  function execute(cmd) {
    var parts = String(cmd).split(',');
    var op = parts[0];
    switch (op) {
      case 'toggle': return { kind: 'toggle', value: api.toggle() };
      case 'seek': return { kind: 'seek', value: api.seek(parts[1]) };
      case 'seekto': return { kind: 'seek', value: api.seekTo(parts[1]) };
      case 'vol': return { kind: 'vol', value: api.volume(parts[1]) };
      case 'rate': return { kind: 'rate', value: api.setRate(parts[1]) };
      case 'state': return { kind: 'state', value: api.state() };
      default: return { kind: 'none', value: 'none' };
    }
  }

  // ── Transport: one pathway, chosen by channel presence ────────────────────
  // CHANNEL (preferred): window.pbVideoChannel, injected by native into EVERY frame
  //   incl. the main one. All frames report activation/score/status and receive
  //   commands the same way; native decides which frame the D-pad drives.
  // LEGACY (fallback): only when no channel exists (old WebView without the webkit
  //   features) — native reports activation via PBVideoNative and drives __pbvc
  //   through evaluateJavascript on the main frame.
  function useChannel() { return !!window.pbVideoChannel; }

  var channel = null;

  function reportActive(active, score, playing) {
    if (useChannel()) {
      bindChannel();
      if (channel) channel.postMessage(JSON.stringify({
        type: 'active', active: active, score: score, playing: playing
      }));
    } else if (window.PBVideoNative && window.PBVideoNative.setActive) {
      try { window.PBVideoNative.setActive(active); } catch (e) { /* unbound */ }
    }
  }

  // Bind the channel (any frame) so native can post commands and read results.
  function bindChannel() {
    if (channel || !window.pbVideoChannel) return;
    channel = window.pbVideoChannel;
    channel.onmessage = function (e) {
      var res = execute(e.data);
      channel.postMessage(JSON.stringify({ type: 'result', kind: res.kind, value: res.value }));
    };
  }

  // ── Status push (drives the phone's now-playing scrubber) ─────────────────
  // Same shape the native player emits, so the phone parses it unchanged.
  function sendStatus(json) {
    if (useChannel()) {
      bindChannel();
      if (channel) channel.postMessage(json);
    } else if (window.PBVideoNative && window.PBVideoNative.onStatus) {
      try { window.PBVideoNative.onStatus(json); } catch (e) { /* unbound */ }
    }
  }

  function activeStatusJson() {
    var v = find();
    return JSON.stringify({
      type: 'status',
      state: !v ? 'idle' : (v.paused ? 'paused' : 'playing'),
      position: v ? Math.round((v.currentTime || 0) * 1000) : 0,
      duration: (v && isFinite(v.duration)) ? Math.round(v.duration * 1000) : 0,
      title: (document.title || '')
    });
  }

  function idleStatusJson() {
    return JSON.stringify({ type: 'status', state: 'idle', position: 0, duration: 0 });
  }

  // ── Activation monitor ────────────────────────────────────────────────────
  var lastActive = null, lastScore = -1, lastPlaying = null;
  function report() {
    bindChannel();
    var b = bestVideo();
    var score = videoScore(b.cov);
    var active = b.cov >= COVERAGE_THRESHOLD;
    var playing = active && b.v && !b.v.paused;
    // Re-send when active/playing flips, or score shifts notably (resize/maximize),
    // so native's cross-frame "which frame wins" pick stays current.
    if (active !== lastActive || playing !== lastPlaying ||
        (active && Math.abs(score - lastScore) > score * 0.2)) {
      var becameInactive = (lastActive === true && !active);
      lastActive = active; lastScore = score; lastPlaying = playing;
      reportActive(active, score, playing);
      // Push status immediately on change; one idle status when the video goes away
      // so the phone clears its scrubber.
      if (active) sendStatus(activeStatusJson());
      else if (becameInactive) sendStatus(idleStatusJson());
    }
  }

  // Periodic status while active, for the live scrubber.
  setInterval(function () { if (lastActive) sendStatus(activeStatusJson()); }, 1000);
  setInterval(report, 600);
  document.addEventListener('play', report, true);
  document.addEventListener('pause', report, true);
  document.addEventListener('loadedmetadata', report, true);
  window.addEventListener('resize', report, true);
  document.addEventListener('fullscreenchange', report, true);
  document.addEventListener('webkitfullscreenchange', report, true);
  report();
})();
