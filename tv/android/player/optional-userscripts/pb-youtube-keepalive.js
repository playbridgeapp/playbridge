/*
 * PlayBridge YouTube keep-alive — OPTIONAL, user-loaded script (NOT bundled in the app).
 *
 * Stops YouTube's "Video paused. Continue watching?" idle prompt from interrupting
 * playback while casting (the viewer never touches the TV, so YouTube's idle timer fires).
 *
 * Install it the same way as any user script (see this folder's README): from the phone's
 * script manager, or by dropping it into the player app's external files dir. The published
 * build ships no YouTube-specific code; this is purely opt-in.
 *
 * Approach adapted from the YouTube NonStop browser extension (MIT-licensed):
 * https://github.com/lawfx/YoutubeNonStop. Rather than trying to RESUME after YouTube's
 * idle pause — which needs a trusted user gesture we can't synthesise — it SUPPRESSES the
 * pause: the <video> element's pause() is overridden so an idle-triggered pause is ignored
 * (the video keeps playing) and the confirm dialog is dismissed in plain JS (no gesture
 * needed, since playback never actually stopped).
 *
 * Self-gates to YouTube hosts and self-guards against double injection. Loaded via
 * onPageFinished, so it runs in the main frame (the YouTube watch page); embedded YouTube
 * iframes aren't covered, but the idle prompt is a watch-page feature anyway.
 *
 * Bridge: exposes window.__pbMarkInteraction(), which pb-video-control.js calls ONLY for an
 * explicit remote pause — so this guard honours a deliberate pause but still suppresses
 * YouTube's idle pause. (Seeks and other remote commands must NOT mark interaction, or
 * repeated seeking would look like activity and the idle pause would be wrongly honoured.)
 */
(function () {
  'use strict';
  if (window.__pbYtGuardInjected) return;
  window.__pbYtGuardInjected = true;

  var IDLE_MS = 5000;
  var lastInteraction = Date.now();
  function markInteraction() { lastInteraction = Date.now(); }
  function isIdle() { return Date.now() - lastInteraction >= IDLE_MS; }

  // Exposed for the video controller (remote commands count as interaction). Set
  // unconditionally so the bridge exists even on non-YouTube pages (where it's a no-op).
  window.__pbMarkInteraction = markInteraction;

  var IS_YT = /(^|\.)youtube\.com$/i.test(location.hostname) ||
              /(^|\.)youtube-nocookie\.com$/i.test(location.hostname);
  if (!IS_YT) return;

  // Real user input resets the idle timer; remote commands do so via __pbMarkInteraction.
  ['pointerdown', 'pointerup', 'keydown', 'keyup', 'touchstart'].forEach(function (ev) {
    document.addEventListener(ev, markInteraction, true);
  });

  function visible(el) {
    if (!el) return false;
    var r = el.getBoundingClientRect();
    if (r.width < 2 || r.height < 2) return false;
    var s = getComputedStyle(el);
    return s.visibility !== 'hidden' && s.display !== 'none' && parseFloat(s.opacity || '1') > 0.1;
  }

  // The AFFIRMATIVE confirm control of YouTube's idle dialog only — never a generic button,
  // so we can't accidentally activate "No", an end-screen suggestion, etc.
  function continueTarget() {
    var sels = [
      'yt-confirm-dialog-renderer #confirm-button',                 // desktop youtube.com
      'tp-yt-paper-dialog #confirm-button',
      'ytm-confirmation-dialog-renderer .yt-spec-button-shape-next--filled' // m.youtube.com
    ];
    for (var i = 0; i < sels.length; i++) {
      var el = document.querySelector(sels[i]);
      if (visible(el)) return el;
    }
    var dlg = document.querySelector('yt-confirm-dialog-renderer, ytm-confirmation-dialog-renderer, [role="dialog"]');
    if (dlg && visible(dlg)) {
      var btns = dlg.querySelectorAll('button, [role="button"], a, tp-yt-paper-button');
      for (var j = 0; j < btns.length; j++) {
        var t = (btns[j].textContent || '').trim().toLowerCase();
        if (visible(btns[j]) && (t === 'yes' || t === 'continue' || t.indexOf('continue watching') === 0)) {
          return btns[j];
        }
      }
    }
    return null;
  }

  function guardPause(v) {
    if (!v || v.__pbPauseGuarded) return;
    v.__pbPauseGuarded = true;
    var realPause = v.pause.bind(v);
    v.__pbRealPause = realPause;
    v.pause = function () {
      // Honour pauses that follow a real/remote interaction; swallow idle auto-pauses
      // (YouTube's "are you still watching") so the video keeps playing.
      if (!isIdle()) return realPause();
    };
  }

  // YouTube swaps the <video> on SPA navigation, so re-guard periodically, and dismiss the
  // confirm dialog if one is up while idle (the video plays behind it).
  setInterval(function () {
    var v = document.querySelector('video');
    if (v) guardPause(v);
    if (isIdle()) {
      var btn = continueTarget();
      if (btn) { try { btn.click(); } catch (e) { /* ignore */ } }
    }
  }, 1000);
})();
