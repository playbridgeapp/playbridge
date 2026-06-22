/*
 * PlayBridge WebView video controller.
 *
 * Injected on every page load by SystemWebViewEngine. Exposes window.__pbvc so the
 * native side (BrowserActivity) can drive the active <video> from the phone D-pad.
 *
 * Feedback is rendered NATIVELY by BrowserActivity (an Android overlay over the
 * WebView), not in the DOM: in fullscreen the WebView composites the video surface
 * on top of page HTML, so a DOM overlay would be hidden behind the video. Each
 * command therefore returns a compact result string for the evaluateJavascript
 * callback so native can show the right feedback:
 *
 *   toggle()        -> "playing" | "paused" | "none"
 *   seek(delta)     -> "<currentTime>,<duration>" (seconds) | "none"
 *   volume(delta)   -> "<volume 0..1>" | "none"
 *   setRate(r)      -> "<rate>" | "none"
 *   getRate()       -> "<rate>" | "none"
 *   state()         -> JSON {hasVideo,paused,currentTime,duration,volume,rate}
 */
(function () {
  'use strict';
  if (window.__pbvcInjected) return;
  window.__pbvcInjected = true;

  var clamp = function (v, lo, hi) { return Math.max(lo, Math.min(hi, v)); };

  // ── Video discovery (shadow-DOM aware) ────────────────────────────────────
  // Walk the main document plus any open shadow roots. Cross-origin iframes are
  // unreachable (separate JS context) and are silently skipped.
  function collectVideos(root, out) {
    var vids;
    try {
      vids = root.querySelectorAll('video');
    } catch (e) {
      return;
    }
    for (var i = 0; i < vids.length; i++) out.push(vids[i]);
    var all = root.querySelectorAll('*');
    for (var j = 0; j < all.length; j++) {
      if (all[j].shadowRoot) collectVideos(all[j].shadowRoot, out);
    }
  }

  // Fraction of the viewport that a video's visible rect covers (0..1).
  function coverage(v) {
    var r = v.getBoundingClientRect();
    if (r.width <= 0 || r.height <= 0) return 0;
    var vw = window.innerWidth || document.documentElement.clientWidth || 1;
    var vh = window.innerHeight || document.documentElement.clientHeight || 1;
    var ix = Math.max(0, Math.min(r.right, vw) - Math.max(r.left, 0));
    var iy = Math.max(0, Math.min(r.bottom, vh) - Math.max(r.top, 0));
    return (ix * iy) / (vw * vh);
  }

  // The video the user is most plausibly watching: the one covering the most of
  // the viewport. Looked up fresh each call so SPA navigation needs no re-inject.
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

  function find() {
    return bestVideo().v;
  }

  // A video is "control-active" when it dominates the screen — covers most of the
  // viewport — whether playing or paused. This is the activation signal for the
  // D-pad, since sites like m.youtube.com expand the player in-page rather than
  // entering HTML5 fullscreen (so native onShowCustomView never fires).
  var COVERAGE_THRESHOLD = 0.6;

  // ── Public API ────────────────────────────────────────────────────────────
  window.__pbvc = {
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
      var dur = isFinite(v.duration) ? v.duration : 0;
      return v.currentTime + ',' + dur;
    },
    volume: function (delta) {
      var v = find();
      if (!v) return 'none';
      var d = Number(delta) || 0;
      v.muted = false;
      v.volume = clamp(v.volume + d, 0, 1);
      return String(v.volume);
    },
    setRate: function (r) {
      var v = find();
      if (!v) return 'none';
      var rate = clamp(Number(r) || 1, 0.0625, 16);
      v.playbackRate = rate;
      return String(v.playbackRate);
    },
    getRate: function () {
      var v = find();
      return v ? String(v.playbackRate) : 'none';
    },
    state: function () {
      var v = find();
      if (!v) return JSON.stringify({ hasVideo: false });
      return JSON.stringify({
        hasVideo: true,
        paused: !!v.paused,
        currentTime: v.currentTime || 0,
        duration: isFinite(v.duration) ? v.duration : 0,
        volume: v.volume,
        rate: v.playbackRate
      });
    }
  };

  // ── Activation monitor ────────────────────────────────────────────────────
  // Tell native (PBVideoNative.setActive) whenever a screen-dominating video
  // appears/disappears, so BrowserActivity can route the D-pad to playback
  // control without depending on the HTML5 fullscreen API. Reports only on change.
  var lastActive = null;
  function report() {
    var active = bestVideo().cov >= COVERAGE_THRESHOLD;
    if (active !== lastActive) {
      lastActive = active;
      try {
        if (window.PBVideoNative && window.PBVideoNative.setActive) {
          window.PBVideoNative.setActive(active);
        }
      } catch (e) { /* interface not bound */ }
    }
  }
  setInterval(report, 600);
  // capture=true so we still see play/pause from videos inside shadow roots
  document.addEventListener('play', report, true);
  document.addEventListener('pause', report, true);
  document.addEventListener('loadedmetadata', report, true);
  window.addEventListener('resize', report, true);
  document.addEventListener('fullscreenchange', report, true);
  document.addEventListener('webkitfullscreenchange', report, true);
  report();
})();
