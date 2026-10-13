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
 *   "rate,<r>" | "state" | "maximize" | "restore", plus JSON
 *   {"type":"text","b64":...} and {"type":"enter"}. Text and Enter are delivered
 *   to every frame; only the frame whose document has focus and an editable
 *   active element (including inside an open shadow root) applies them.
 *   Frame -> native messages (channel, JSON): {type:"active",active,score,playing}
 *   on change, and {type:"result",kind,value} per command. maximize/restore
 *   values are short tokens (ok_site, ok_video, ok_fill, ok_iframe, ok_held,
 *   no_video, ok, ok_native, not_maximized). Text/Enter values are ok, no_focus,
 *   or err. Enter fullscreen prefers the site's own fullscreen button, then the
 *   player container that holds the controls. A repeat does not request again
 *   and does not rebuild styles. Text replaces the whole field.
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
      // NOTE: we intentionally do NOT touch v.muted here. Unmuting autoplay-muted video
      // requires a trusted user gesture, which this injected call doesn't carry — setting
      // muted=false silently fails on YouTube and can even block an unmuted play(). Unmute
      // is handled natively via a real synthesized tap (see BrowserActivity video_unmute).
      if (v.paused) {
        v.play();
      } else {
        // Mark interaction ONLY for an explicit pause, so a user script can tell this
        // deliberate pause apart from an automatic/idle one and honour it. Other commands
        // (seek/volume) must NOT mark — otherwise repeated seeking looks like activity and
        // the idle guard would wrongly honour YouTube's "Continue watching?" pause.
        markInteraction();
        v.pause();
      }
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
    unmute: function () {
      var v = find();
      if (!v) return 'none';
      // Runs right after native's real tap, inside the user-activation window that tap
      // grants — so muted=false sticks here even on sites without a "tap to unmute".
      v.muted = false;
      if (v.volume === 0) v.volume = 1;
      // The preceding tap may have toggled playback off on a player that has no unmute
      // affordance (e.g. a muted background video). Resume so unmuting never leaves it
      // paused — the symptom on sites whose videos don't start muted.
      if (v.paused) { try { v.play(); } catch (e) {} }
      return v.muted ? 'muted' : 'unmuted';
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
    },
    // Idempotent. "pending" means the token is posted when requestFullscreen settles.
    // Sync tokens are posted here so a direct __pbvc call and a channel command
    // both reach native.
    maximize: function () {
      var value = beginMaximize();
      if (value !== 'pending') postControlResult('maximize', value);
      return value;
    },
    restore: function () {
      var value = restoreMaximize();
      postControlResult('restore', value);
      return value;
    },
    // Replace the focused field. The base64 payload stays inside the frame;
    // native only sees ok / no_focus / err.
    applyText: function (b64) {
      var value = writeFocusedText(b64);
      postControlResult('text', value);
      return value;
    },
    submitFocused: function () {
      var value = pressFocusedEnter();
      postControlResult('enter', value);
      return value;
    }
  };
  window.__pbvc = api;

  function focusedEditable() {
    try {
      if (typeof document.hasFocus !== 'function' || !document.hasFocus()) return null;
    } catch (e) { return null; }
    var el = document.activeElement;
    var guard = 0;
    while (el && el.shadowRoot && el.shadowRoot.activeElement && guard < 8) {
      el = el.shadowRoot.activeElement;
      guard++;
    }
    if (!el) return null;
    var tag = el.tagName;
    if (tag === 'INPUT' || tag === 'TEXTAREA' || el.isContentEditable) return el;
    return null;
  }

  function decodeFocusText(b64) {
    try {
      if (typeof b64 !== 'string') return null;
      return decodeURIComponent(escape(atob(b64)));
    } catch (e) { return null; }
  }

  // Gecko runs this file in an isolated content script. wrappedJSObject is the page
  // object, so the prototype setter and the input event land where React/Vue listen.
  // A WebView injects this file into the page, which has no wrapper.
  function pageNode(el) {
    try { if (el && el.wrappedJSObject) return el.wrappedJSObject; } catch (e) { /* page world */ }
    return el;
  }

  function pageView() {
    try { if (window.wrappedJSObject) return window.wrappedJSObject; } catch (e) { /* page world */ }
    return window;
  }

  function setFormValue(el, text) {
    var target = pageNode(el);
    var view = pageView();
    var ctor = target.tagName === 'TEXTAREA' ? view.HTMLTextAreaElement : view.HTMLInputElement;
    var desc = null;
    try { desc = ctor && Object.getOwnPropertyDescriptor(ctor.prototype, 'value'); } catch (e) { desc = null; }
    if (desc && typeof desc.set === 'function') desc.set.call(target, text);
    else target.value = text;
  }

  function dispatchInput(el, text) {
    var target = pageNode(el);
    var view = pageView();
    var InputCtor = view.InputEvent || InputEvent;
    var EventCtor = view.Event || Event;
    try {
      target.dispatchEvent(new InputCtor('input', { bubbles: true, inputType: 'insertReplacementText', data: text }));
    } catch (e) {
      try { target.dispatchEvent(new EventCtor('input', { bubbles: true })); } catch (e2) { /* ignore */ }
    }
    try { target.dispatchEvent(new EventCtor('change', { bubbles: true })); } catch (e3) { /* ignore */ }
  }

  function writeFocusedText(b64) {
    try {
      var el = focusedEditable();
      if (!el) return 'no_focus';
      var text = decodeFocusText(b64 == null ? '' : b64);
      if (text == null) return 'err';
      if (el.isContentEditable) pageNode(el).textContent = text;
      else setFormValue(el, text);
      dispatchInput(el, text);
      return 'ok';
    } catch (e) { return 'err'; }
  }

  function pressFocusedEnter() {
    try {
      var el = focusedEditable();
      if (!el) return 'no_focus';
      var target = pageNode(el);
      var view = pageView();
      var KeyCtor = view.KeyboardEvent || KeyboardEvent;
      var mk = function (ty) {
        return new KeyCtor(ty, { key: 'Enter', code: 'Enter', keyCode: 13, which: 13, bubbles: true, cancelable: true });
      };
      target.dispatchEvent(mk('keydown'));
      target.dispatchEvent(mk('keypress'));
      target.dispatchEvent(mk('keyup'));
      var form = target.form;
      if (form) {
        try {
          if (form.requestSubmit) form.requestSubmit();
          else form.submit();
        } catch (e) { /* the key events already landed */ }
      }
      return 'ok';
    } catch (e) { return 'err'; }
  }

  // ── Fullscreen (site button, then the player container, then ancestor iframes)
  // Prefer the control the site already shows, so its seek bar and menus stay.
  // Otherwise fullscreen the player box that contains those controls, never the
  // bare <video> when a parent exists (unless the video has native controls).
  // If the frame refuses requestFullscreen, that same box is pinned and each
  // ancestor is asked to expand the child iframe. A repeat does not request again.
  var PB_FRAME = 'pb-frame-fit';
  var fitState = null;
  var fitGeneration = 0;
  var fitted = [];
  var siteButton = null;
  var siteUsedApi = false;

  function fullscreenElement() {
    return document.fullscreenElement || document.webkitFullscreenElement || null;
  }

  function containsNode(el, node) {
    if (!el || !node) return false;
    if (el === node) return true;
    try { return !!el.contains && el.contains(node); } catch (e) { return false; }
  }

  function classText(el) {
    var name = el && el.className;
    if (!name) return '';
    return typeof name === 'string' ? name : (name.baseVal || '');
  }

  function ownBody(video) {
    try {
      var doc = (video && video.ownerDocument) || document;
      return doc.body || null;
    } catch (e) { return null; }
  }

  function controlText(el) {
    var aria = '', title = '', plyr = '';
    try { aria = el.getAttribute ? (el.getAttribute('aria-label') || '') : ''; } catch (e) { aria = ''; }
    try { title = el.getAttribute ? (el.getAttribute('title') || '') : ''; } catch (e2) { title = ''; }
    try { plyr = el.getAttribute ? (el.getAttribute('data-plyr') || '') : ''; } catch (e3) { plyr = ''; }
    return aria + ' ' + title + ' ' + classText(el) + ' ' + plyr + ' ' + ((el && el.id) || '');
  }

  function isSiteFullscreenButton(el) {
    return !!(el && /full[\s-]?screen/i.test(controlText(el)));
  }

  function findSiteFullscreenButton(video) {
    var el = video ? video.parentElement : null;
    var stop = ownBody(video);
    var hops = 0;
    while (el && hops < 8 && el !== stop && el !== document.documentElement) {
      var nodes = [];
      try { nodes = el.querySelectorAll('button,[role="button"],a'); } catch (e) { nodes = []; }
      for (var i = 0; i < nodes.length; i++) {
        if (isSiteFullscreenButton(nodes[i])) return nodes[i];
      }
      if (el.querySelector) {
        var selectors = [
          '.vjs-fullscreen-control', '.jw-icon-fullscreen', '[data-plyr="fullscreen"]',
          '.shaka-fullscreen-button', '.art-icon-fullscreen', '.ytp-fullscreen-button',
          '.bmpui-ui-fullscreentogglebutton'
        ];
        for (var s = 0; s < selectors.length; s++) {
          var hit = null;
          try { hit = el.querySelector(selectors[s]); } catch (e2) { hit = null; }
          if (hit && isSiteFullscreenButton(hit)) return hit;
        }
      }
      el = el.parentElement;
      hops++;
    }
    return null;
  }

  function rectOf(el) {
    try {
      if (!el || !el.getBoundingClientRect) return null;
      var r = el.getBoundingClientRect();
      if (!r || r.width <= 0 || r.height <= 0) return null;
      return r;
    } catch (e) { return null; }
  }

  function nearVideoBox(container, video) {
    var c = rectOf(container);
    var v = rectOf(video);
    if (!c || !v) return false;
    var covers = c.left <= v.left + 8 && c.top <= v.top + 8 && c.right + 8 >= v.right && c.bottom + 8 >= v.bottom;
    if (!covers) return false;
    var areaV = v.width * v.height;
    if (areaV <= 0) return false;
    return (c.width * c.height) <= areaV * 2.5 && c.width <= v.width * 1.35 + 24;
  }

  function hasOverlayControls(container, video) {
    if (!container || container === video) return !!(video && video.controls);
    var nodes = [];
    try { nodes = container.querySelectorAll('button,[role="button"],input'); } catch (e) { nodes = []; }
    var vr = rectOf(video);
    for (var i = 0; i < nodes.length; i++) {
      var node = nodes[i];
      if (!node || node === video || containsNode(video, node)) continue;
      if (/control|seek|progress|subtitle|caption|settings|quality|volume|playback|toolbar|vjs-|jw-|plyr|ytp-|shaka-|art-|bmpui-/i.test(controlText(node))) return true;
      var role = '';
      try { role = node.getAttribute ? (node.getAttribute('role') || '') : ''; } catch (e2) { role = ''; }
      if (node.tagName === 'BUTTON' || node.tagName === 'INPUT' || role === 'button') {
        var br = rectOf(node);
        if (!vr || !br) return true;
        var overlaps = br.left < vr.right && br.right > vr.left && br.top < vr.bottom && br.bottom > vr.top;
        if (overlaps) return true;
      }
    }
    return false;
  }

  // Highest ancestor that still hugs the video and holds the control overlay.
  // A parent is the fallback so a wrapper's sibling controls stay inside the box.
  function playerContainer(video) {
    var best = null;
    var el = video.parentElement;
    var stop = ownBody(video);
    var hops = 0;
    while (el && hops < 8 && el !== stop && el !== document.documentElement) {
      if (hasOverlayControls(el, video) && nearVideoBox(el, video)) best = el;
      el = el.parentElement;
      hops++;
    }
    if (best) return best;
    if (video.controls) return video;
    var parent = video.parentElement;
    if (parent && parent !== stop && parent !== document.documentElement) return parent;
    return video;
  }

  function fullscreenModeTokens(video) {
    var tokens = [];
    var el = video ? video.parentElement : null;
    var stop = ownBody(video);
    var hops = 0;
    while (el && hops < 8 && el !== stop && el !== document.documentElement) {
      var parts = classText(el).split(/\s+/);
      for (var i = 0; i < parts.length; i++) {
        var token = parts[i];
        if (token && /fullscreen/i.test(token) && !/button|control|icon|toggle|menu/i.test(token)) tokens.push(token);
      }
      el = el.parentElement;
      hops++;
    }
    return tokens.join(' ');
  }

  function activateControl(el) {
    var target = pageNode(el);
    try {
      if (typeof target.click === 'function') { target.click(); return; }
    } catch (e) { /* try a plain click event */ }
    try {
      var view = pageView();
      var Mouse = view.MouseEvent;
      if (typeof Mouse === 'function' && typeof target.dispatchEvent === 'function') {
        target.dispatchEvent(new Mouse('click', { bubbles: true, cancelable: true }));
      }
    } catch (e2) { /* the container path still runs */ }
  }

  function rememberStyle(el) {
    if (!el || el.__pbFit) return false;
    el.__pbFit = true;
    try { el.__pbFitStyle = el.getAttribute ? el.getAttribute('style') : null; } catch (e) { el.__pbFitStyle = null; }
    fitted.push(el);
    return true;
  }

  function restoreStyle(el) {
    if (!el || !el.__pbFit) return;
    try {
      if (el.__pbFitStyle) el.setAttribute('style', el.__pbFitStyle);
      else if (el.removeAttribute) el.removeAttribute('style');
    } catch (e) { /* element already gone */ }
    el.__pbFit = false;
    el.__pbFitStyle = null;
  }

  function applyFill(el) {
    if (!rememberStyle(el) || !el.style || !el.style.setProperty) return;
    var props = [
      ['position', 'fixed'], ['top', '0'], ['left', '0'], ['width', '100%'], ['height', '100%'],
      ['max-width', '100%'], ['max-height', '100%'], ['margin', '0'], ['object-fit', 'contain'],
      ['z-index', '2147483647'], ['background', '#000'], ['border', '0']
    ];
    for (var i = 0; i < props.length; i++) {
      try { el.style.setProperty(props[i][0], props[i][1], 'important'); } catch (e) { /* ignore */ }
    }
  }

  function restoreFitted() {
    for (var i = 0; i < fitted.length; i++) restoreStyle(fitted[i]);
    fitted = [];
  }

  function inFrame() {
    try { return !!(window.parent && window.parent !== window); } catch (e) { return false; }
  }

  function postParent(op) {
    if (!inFrame()) return;
    try { window.parent.postMessage({ pb: PB_FRAME, op: op }, '*'); } catch (e) { /* detached */ }
  }

  function sameWindow(iframe, source) {
    try { if (iframe.contentWindow === source) return true; } catch (e) { /* cross-origin window */ }
    try { if (source && iframe.contentWindow === source.wrappedJSObject) return true; } catch (e2) { /* no wrapper */ }
    return false;
  }

  function tryNativeFullscreen(el) {
    return new Promise(function (resolve, reject) {
      if (!el) { reject(); return; }
      var existing = fullscreenElement();
      if (existing === el || containsNode(existing, el)) { resolve('held'); return; }
      var req = el.requestFullscreen || el.webkitRequestFullscreen || el.webkitRequestFullScreen;
      if (typeof req !== 'function') { reject(); return; }
      var settled = false;
      function finish(ok) {
        if (settled) return;
        settled = true;
        if (ok) resolve('entered'); else reject();
      }
      var ret;
      try { ret = req.call(el); } catch (e) { finish(false); return; }
      // A promise that settles late must not be failed by the timer first.
      // The timer is only how a non-thenable webkit call reports success.
      if (ret && typeof ret.then === 'function') {
        ret.then(function () { finish(true); }, function () { finish(false); });
        return;
      }
      setTimeout(function () {
        var current = fullscreenElement();
        finish(!!current && (current === el || containsNode(current, el)));
      }, 300);
    });
  }

  function exitDocumentFullscreen() {
    var standard = null;
    var webkit = null;
    try { standard = document.fullscreenElement || null; } catch (e) { standard = null; }
    try { webkit = document.webkitFullscreenElement || null; } catch (e2) { webkit = null; }
    if (!standard && !webkit) return;
    try {
      if (standard && typeof document.exitFullscreen === 'function') document.exitFullscreen();
      else if (webkit) {
        var exitWebkit = document.webkitExitFullscreen || document.webkitCancelFullScreen;
        if (typeof exitWebkit === 'function') exitWebkit.call(document);
      }
    } catch (e3) { /* already left fullscreen */ }
  }

  function expandIframe(iframe) {
    if (!iframe || iframe.__pbFit) return;
    applyFill(iframe);
    tryNativeFullscreen(iframe).then(function () {}, function () {});
  }

  function sameOriginTarget() {
    var frames;
    try { frames = document.querySelectorAll('iframe'); } catch (e) { return null; }
    var best = null;
    var bestArea = -1;
    for (var i = 0; i < frames.length; i++) {
      var doc = null;
      try { doc = frames[i].contentDocument; } catch (e) { continue; }
      if (!doc) continue;
      var videos = [];
      try { collectVideos(doc, videos); } catch (e2) { continue; }
      for (var j = 0; j < videos.length; j++) {
        var rect = videos[j].getBoundingClientRect ? videos[j].getBoundingClientRect() : null;
        var area = rect ? rect.width * rect.height : 0;
        if (!best || area >= bestArea) { best = { iframe: frames[i], video: videos[j] }; bestArea = area; }
      }
    }
    return best;
  }

  function largestIframe() {
    var frames;
    try { frames = document.querySelectorAll('iframe'); } catch (e) { return null; }
    var best = null;
    var bestArea = 0;
    for (var i = 0; i < frames.length; i++) {
      var area = (frames[i].offsetWidth || 0) * (frames[i].offsetHeight || 0);
      if (area > bestArea) { best = frames[i]; bestArea = area; }
    }
    return bestArea > 100 ? best : null;
  }

  function postControlResult(kind, value) {
    var payload = JSON.stringify({ type: 'result', kind: kind, value: value });
    if (useChannel()) {
      bindChannel();
      if (channel) { channel.postMessage(payload); return; }
    }
    if (window.PBVideoNative && window.PBVideoNative.onControlResult) {
      try { window.PBVideoNative.onControlResult(kind, value); } catch (e) { /* unbound */ }
    }
  }

  function beginMaximize() {
    if (fitState) return 'ok_held';
    var video = find();
    if (video) return enterPlayer(video, null);
    if (inFrame()) return 'no_video';
    var same = sameOriginTarget();
    if (same) return enterPlayer(same.video, same.iframe);
    var iframe = largestIframe();
    if (!iframe) return 'no_video';
    expandIframe(iframe);
    try { iframe.contentWindow.postMessage({ pb: PB_FRAME, op: 'fill-video' }, '*'); } catch (e) { /* cross-origin */ }
    fitState = 'iframe';
    return 'ok_iframe';
  }

  function enterPlayer(video, iframe) {
    var existing = fullscreenElement();
    if (existing && (containsNode(existing, video) || existing === video)) {
      fitState = 'native';
      return 'ok_held';
    }
    if (fullscreenModeTokens(video)) {
      fitState = 'site';
      siteButton = findSiteFullscreenButton(video);
      siteUsedApi = false;
      return 'ok_held';
    }
    var button = findSiteFullscreenButton(video);
    if (button) {
      var before = fullscreenModeTokens(video);
      activateControl(button);
      if (fullscreenElement() || fullscreenModeTokens(video) !== before) {
        fitState = 'site';
        siteButton = button;
        siteUsedApi = !!fullscreenElement();
        return 'ok_site';
      }
      var generation = ++fitGeneration;
      fitState = 'pending';
      siteButton = button;
      setTimeout(function () {
        if (generation !== fitGeneration || fitState !== 'pending') return;
        if (fullscreenElement() || fullscreenModeTokens(video) !== before) {
          fitState = 'site';
          siteUsedApi = !!fullscreenElement();
          postControlResult('maximize', 'ok_site');
          return;
        }
        siteButton = null;
        requestContainer(video, iframe, generation);
      }, 300);
      return 'pending';
    }
    var direct = ++fitGeneration;
    fitState = 'pending';
    requestContainer(video, iframe, direct);
    return 'pending';
  }

  function requestContainer(video, iframe, generation) {
    var shell = playerContainer(video);
    tryNativeFullscreen(shell).then(function () {
      if (generation !== fitGeneration || fitState !== 'pending') return;
      fitState = 'native';
      postControlResult('maximize', 'ok_video');
    }, function () {
      if (generation !== fitGeneration || fitState !== 'pending') return;
      applyFill(shell);
      if (iframe) expandIframe(iframe);
      else postParent('enter');
      fitState = 'fill';
      postControlResult('maximize', 'ok_fill');
    });
  }

  function restoreMaximize() {
    var had = fitState;
    var button = siteButton;
    var viaSite = fitState === 'site';
    fitGeneration++;
    fitState = null;
    siteButton = null;
    siteUsedApi = false;
    restoreFitted();
    var frames;
    try { frames = document.querySelectorAll('iframe'); } catch (e) { frames = []; }
    for (var i = 0; i < frames.length; i++) {
      try { frames[i].contentWindow.postMessage({ pb: PB_FRAME, op: 'exit-video' }, '*'); } catch (err) { /* cross-origin */ }
    }
    postParent('exit');
    if (viaSite && button) activateControl(button);
    if (fullscreenElement()) {
      exitDocumentFullscreen();
      return 'ok';
    }
    return had ? 'ok' : 'not_maximized';
  }

  function onFrameMessage(event) {
    var data = event && event.data;
    if (!data || data.pb !== PB_FRAME) return;
    if (data.op === 'fill-video' || data.op === 'exit-video') {
      if (inFrame() && event.source && event.source !== window.parent) return;
      if (data.op === 'fill-video') beginMaximize();
      else restoreMaximize();
      return;
    }
    var frames;
    try { frames = document.querySelectorAll('iframe'); } catch (e) { return; }
    var target = null;
    for (var i = 0; i < frames.length; i++) {
      if (sameWindow(frames[i], event.source)) { target = frames[i]; break; }
    }
    if (!target) return;
    if (data.op === 'enter') expandIframe(target);
    else if (data.op === 'exit') {
      restoreStyle(target);
      if (fullscreenElement() === target) exitDocumentFullscreen();
    } else return;
    postParent(data.op);
  }

  function onOwnedFullscreenEnd() {
    if (!fullscreenElement() && (fitState === 'native' || (fitState === 'site' && siteUsedApi))) {
      fitState = null;
      siteButton = null;
      siteUsedApi = false;
      postControlResult('restore', 'ok_native');
    }
  }

  // ── Command execution (shared by channel + fallback) ──────────────────────
  // Returns {kind, value} so native can render the right overlay.
  function execute(cmd) {
    // NOTE: do NOT mark interaction for every command — only an explicit pause does
    // (see api.toggle). Marking on seek/volume would make repeated seeking look like
    // activity, and the idle guard would then honour YouTube's "Continue watching?" pause.
    var parts = String(cmd).split(',');
    var op = parts[0];
    switch (op) {
      case 'toggle': return { kind: 'toggle', value: api.toggle() };
      case 'seek': return { kind: 'seek', value: api.seek(parts[1]) };
      case 'seekto': return { kind: 'seek', value: api.seekTo(parts[1]) };
      case 'vol': return { kind: 'vol', value: api.volume(parts[1]) };
      case 'unmute': return { kind: 'vol', value: api.unmute() };
      case 'rate': return { kind: 'rate', value: api.setRate(parts[1]) };
      case 'state': return { kind: 'state', value: api.state() };
      case 'maximize': return { kind: 'maximize', value: api.maximize() };
      case 'restore': return { kind: 'restore', value: api.restore() };
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

  function repeatChar(char, count) {
    var out = "";
    for (var i = 0; i < count; i++) out += char;
    return out;
  }

  function fmtTime(totalSec) {
    if (!isFinite(totalSec) || totalSec < 0) return "0:00";
    var s = Math.floor(totalSec);
    var h = Math.floor(s / 3600);
    var m = Math.floor((s % 3600) / 60);
    var sec = s % 60;
    if (h > 0) {
      return h + ":" + (m < 10 ? "0" : "") + m + ":" + (sec < 10 ? "0" : "") + sec;
    } else {
      return m + ":" + (sec < 10 ? "0" : "") + sec;
    }
  }

  function progressBar(frac) {
    var slots = 16;
    var filled = Math.round(Math.max(0, Math.min(1, frac)) * slots);
    return repeatChar("█", filled) + repeatChar("░", slots - filled);
  }

  function showFeedbackOverlay(kind, value) {
    var text = "";
    switch (kind) {
      case 'toggle':
        text = (value === 'playing') ? '▶' : '❙❙';
        break;
      case 'seek':
        var parts = value.split(',');
        var t = parseFloat(parts[0]) || 0;
        var d = parseFloat(parts[1]) || 0;
        var frac = d > 0 ? t / d : 0;
        text = fmtTime(t) + "   /   " + fmtTime(d) + "\n" + progressBar(frac);
        break;
      case 'vol':
        var vol = parseFloat(value) || 0;
        text = "🔊  " + Math.round(vol * 100) + "%\n" + progressBar(vol);
        break;
      case 'rate':
        var r = parseFloat(value) || 1;
        text = (r + "").replace(/\.0$/, "") + "×";
        break;
      default:
        return;
    }

    if (window.pbVideoChannel) {
      try {
        window.pbVideoChannel.postMessage(JSON.stringify({
          type: 'overlay',
          visible: true,
          text: text,
          style: 'minimal',
          autoDismissMs: 1500
        }));
      } catch (e) { /* ignore */ }
    }
  }

  // Bind the channel (any frame) so native can post commands and read results.
  function bindChannel() {
    if (channel || !window.pbVideoChannel) return;
    channel = window.pbVideoChannel;
    channel.onmessage = function (e) {
      var raw = e && e.data;
      if (typeof raw === 'string' && raw.charAt(0) === '{') {
        var msg = null;
        try { msg = JSON.parse(raw); } catch (err) { msg = null; }
        if (msg && msg.type === 'text') {
          postControlResult('text', writeFocusedText(msg.b64 || ''));
          return;
        }
        if (msg && msg.type === 'enter') {
          postControlResult('enter', pressFocusedEnter());
          return;
        }
      }
      var res = execute(raw);
      if (res.kind === 'maximize' || res.kind === 'restore') return;
      if (res.kind !== 'none' && res.value !== 'none') {
        showFeedbackOverlay(res.kind, res.value);
      }
      channel.postMessage(JSON.stringify({ type: 'result', kind: 'none', value: 'none' }));
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

  // Optional bridge: a user script (e.g. pb-youtube-keepalive.js) may expose
  // window.__pbMarkInteraction to learn when the viewer deliberately acts, so it can tell a
  // genuine action apart from idleness. A remote command is a deliberate action, so we call
  // it here. No-op if no such script is loaded.
  function markInteraction() {
    if (typeof window.__pbMarkInteraction === 'function') {
      try { window.__pbMarkInteraction(); } catch (e) { /* ignore */ }
    }
  }

  function activeStatusJson() {
    var v = find();
    return JSON.stringify({
      type: 'status',
      state: !v ? 'idle' : (v.paused ? 'paused' : 'playing'),
      position: v ? Math.round((v.currentTime || 0) * 1000) : 0,
      duration: (v && isFinite(v.duration)) ? Math.round(v.duration * 1000) : 0,
      title: (document.title || ''),
      // Lets native auto-unmute only when actually muted (so it never taps an already-
      // audible video). Extra field; older phone parsers ignore it.
      muted: !!(v && v.muted),
      // Optional user scripts may set window.__pbSuppressUnmute to tell native to hold off
      // its auto-unmute tap while a transient overlay/interstitial is on screen (the tap
      // would land on that instead of the player). Undefined (=> false) when none set it.
      suppressUnmute: !!window.__pbSuppressUnmute
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
  document.addEventListener('fullscreenchange', onOwnedFullscreenEnd, true);
  document.addEventListener('webkitfullscreenchange', onOwnedFullscreenEnd, true);
  window.addEventListener('message', onFrameMessage, true);
  report();
})();
